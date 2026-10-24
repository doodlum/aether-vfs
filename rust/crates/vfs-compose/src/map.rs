//! File-map provider — serves an explicit list of `virtual path -> host file`.
//!
//! A host that has already decided, file by file, what a directory should
//! contain (a mod manager resolving conflicts, overrides and per-mod target
//! folders) hands that decision over as data rather than as a stack of
//! folder layers. Directories are implied by the paths: `a/b/c.txt` makes `a`
//! and `a/b` exist. Nothing is read from disk until a file is stat'ed or
//! opened, so a map of a hundred thousand entries costs one allocation per
//! entry and no I/O to build.
//!
//! Names match case-insensitively by [`vfs_core::fold`], the same function
//! the shim folds vpaths with, and keep their given spelling for listings.

use std::collections::{BTreeMap, HashMap};
use std::fs::File;
use std::path::{Path, PathBuf};
use std::sync::atomic::{AtomicU64, Ordering};
use std::sync::{Arc, Mutex};

use vfs_provider::{
    bad_fh, is_dir, map_io_err, not_a_dir, not_found, read_only, Capabilities, DirEntry, Handle,
    Provider, SetAttr, Stat, VPath, KIND_DIR, KIND_FILE, OPEN_CREATE, OPEN_TRUNC, OPEN_WRITE,
};

/// One directory of the map: children keyed by their folded name.
#[derive(Default)]
struct Dir {
    children: BTreeMap<String, (String, Node)>,
}

enum Node {
    Dir(Dir),
    File(PathBuf),
}

/// Why a map could not be built.
#[derive(Debug, Clone, PartialEq, Eq)]
pub enum MapError {
    /// A path is empty, absolute, or has an empty, `.` or `..` component.
    BadPath(String),
    /// A path names a file and also a directory holding other entries.
    FileAndDir(String),
}

impl std::fmt::Display for MapError {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        match self {
            MapError::BadPath(p) => write!(f, "not a relative path: {p:?}"),
            MapError::FileAndDir(p) => {
                write!(f, "{p:?} is both a file and a directory in the map")
            }
        }
    }
}

impl std::error::Error for MapError {}

pub struct MapProvider {
    root: Dir,
    next: AtomicU64,
    opens: Mutex<HashMap<Handle, Arc<File>>>,
}

impl MapProvider {
    /// Build a map from `(virtual path, host file)` pairs. Virtual paths use
    /// `/` (a `\` is accepted too) and are relative to the root they serve.
    ///
    /// A later pair for a fold-equal path replaces the earlier one, so a host
    /// can list its entries in priority order and let the last one win. A
    /// path that is both a file and the parent of another entry is an error:
    /// no program could see both, and silently keeping one would hide a bug
    /// in the host's resolution.
    pub fn new<I, S, P>(entries: I) -> Result<Self, MapError>
    where
        I: IntoIterator<Item = (S, P)>,
        S: AsRef<str>,
        P: Into<PathBuf>,
    {
        let mut root = Dir::default();
        for (vpath, host) in entries {
            let vpath = vpath.as_ref();
            let parts = split(vpath).ok_or_else(|| MapError::BadPath(vpath.to_owned()))?;
            let (name, parents) = parts
                .split_last()
                .expect("split never returns an empty list");
            let mut dir = &mut root;
            for part in parents {
                let slot = dir
                    .children
                    .entry(vfs_core::fold(part))
                    .or_insert_with(|| ((*part).to_owned(), Node::Dir(Dir::default())));
                dir = match &mut slot.1 {
                    Node::Dir(d) => d,
                    Node::File(_) => return Err(MapError::FileAndDir(vpath.to_owned())),
                };
            }
            match dir.children.get(&vfs_core::fold(name)) {
                Some((_, Node::Dir(d))) if !d.children.is_empty() => {
                    return Err(MapError::FileAndDir(vpath.to_owned()));
                }
                _ => {}
            }
            dir.children.insert(
                vfs_core::fold(name),
                ((*name).to_owned(), Node::File(host.into())),
            );
        }
        Ok(MapProvider {
            root,
            next: AtomicU64::new(1),
            opens: Mutex::new(HashMap::new()),
        })
    }

    /// How many files the map serves.
    pub fn file_count(&self) -> usize {
        fn count(dir: &Dir) -> usize {
            dir.children
                .values()
                .map(|(_, node)| match node {
                    Node::Dir(d) => count(d),
                    Node::File(_) => 1,
                })
                .sum()
        }
        count(&self.root)
    }

    fn lookup(&self, rel: &str) -> Result<Option<&Node>, i32> {
        if rel.is_empty() {
            return Ok(None);
        }
        let parts = split(rel).ok_or_else(vfs_provider::bad_request)?;
        let mut dir = &self.root;
        let (name, parents) = parts
            .split_last()
            .expect("split never returns an empty list");
        for part in parents {
            match dir.children.get(&vfs_core::fold(part)) {
                Some((_, Node::Dir(d))) => dir = d,
                Some((_, Node::File(_))) => return Err(not_a_dir()),
                None => return Err(not_found()),
            }
        }
        match dir.children.get(&vfs_core::fold(name)) {
            Some((_, node)) => Ok(Some(node)),
            None => Err(not_found()),
        }
    }

    /// The directory at `rel`, or the root for `""`.
    fn dir_at(&self, rel: &str) -> Result<&Dir, i32> {
        match self.lookup(rel)? {
            None => Ok(&self.root),
            Some(Node::Dir(d)) => Ok(d),
            Some(Node::File(_)) => Err(not_a_dir()),
        }
    }
}

/// Split a virtual path into its components, refusing anything that is not
/// a plain relative path. `None` for an empty path.
fn split(path: &str) -> Option<Vec<&str>> {
    let parts: Vec<&str> = path
        .split(['/', '\\'])
        .filter(|part| !part.is_empty())
        .collect();
    if parts.is_empty() || parts.iter().any(|p| *p == "." || *p == "..") {
        return None;
    }
    if path.starts_with(['/', '\\']) || Path::new(path).is_absolute() {
        return None;
    }
    Some(parts)
}

fn dir_stat() -> Stat {
    Stat {
        kind: KIND_DIR,
        size: 0,
        mtime: 0,
    }
}

/// The stat of a mapped file. A host file that has gone away reads as
/// missing, never as an I/O error, so a stale map degrades to "not there".
fn file_stat(host: &Path) -> Result<Option<Stat>, i32> {
    match std::fs::metadata(host) {
        Ok(m) if m.is_file() => Ok(Some(Stat {
            kind: KIND_FILE,
            size: m.len(),
            // The host file's own time, as a hard link to it would show: games that order
            // plugins by file time (Oblivion, Fallout 3 and New Vegas) need it.
            mtime: crate::disk::mtime_secs(&m),
        })),
        Ok(_) => Ok(None),
        Err(e) if e.kind() == std::io::ErrorKind::NotFound => Ok(None),
        Err(_) => Err(map_io_err()),
    }
}

impl Provider for MapProvider {
    fn capabilities(&self) -> Capabilities {
        // Mutable: the host files can change underneath the map.
        Capabilities::read_only()
    }

    fn getattr(&self, p: VPath) -> Result<Option<Stat>, i32> {
        match self.lookup(p.rel) {
            Ok(None) | Ok(Some(Node::Dir(_))) => Ok(Some(dir_stat())),
            Ok(Some(Node::File(host))) => file_stat(host),
            Err(e) if e == not_found() => Ok(None),
            Err(e) => Err(e),
        }
    }

    fn readdir(&self, p: VPath) -> Result<Vec<DirEntry>, i32> {
        let dir = self.dir_at(p.rel)?;
        let mut out = Vec::with_capacity(dir.children.len());
        // BTreeMap order is fold order, which every listing in this stack sorts by.
        for (name, node) in dir.children.values() {
            let stat = match node {
                Node::Dir(_) => dir_stat(),
                Node::File(host) => match file_stat(host)? {
                    Some(stat) => stat,
                    None => continue,
                },
            };
            out.push(DirEntry {
                name: name.clone(),
                stat,
            });
        }
        Ok(out)
    }

    fn open(&self, p: VPath, flags: u32) -> Result<(Handle, u64, bool), i32> {
        if flags & (OPEN_WRITE | OPEN_CREATE | OPEN_TRUNC) != 0 {
            return Err(read_only());
        }
        let node = self.lookup(p.rel)?;
        let host = match node {
            None | Some(Node::Dir(_)) => {
                return Ok((self.next.fetch_add(1, Ordering::Relaxed), 0, true));
            }
            Some(Node::File(host)) => host,
        };
        let file = File::open(host).map_err(|e| {
            if e.kind() == std::io::ErrorKind::NotFound {
                not_found()
            } else {
                map_io_err()
            }
        })?;
        let meta = file.metadata().map_err(|_| map_io_err())?;
        if meta.is_dir() {
            return Err(is_dir());
        }
        let h = self.next.fetch_add(1, Ordering::Relaxed);
        vfs_provider::lock_or_status(&self.opens)?.insert(h, Arc::new(file));
        Ok((h, meta.len(), false))
    }

    fn close(&self, h: Handle) -> Result<(), i32> {
        // Directory handles were never stored; closing one is a no-op.
        vfs_provider::lock_or_status(&self.opens)?.remove(&h);
        Ok(())
    }

    fn read_at(&self, h: Handle, offset: u64, buf: &mut [u8]) -> Result<usize, i32> {
        // Positional reads need no lock while they run: the table lock only
        // guards finding the file.
        let file = vfs_provider::lock_or_status(&self.opens)?
            .get(&h)
            .cloned()
            .ok_or_else(bad_fh)?;
        read_at(&file, offset, buf).map_err(|_| map_io_err())
    }

    fn set_attr(&self, _p: VPath, _attr: SetAttr) -> Result<(), i32> {
        Err(read_only())
    }
}

#[cfg(windows)]
fn read_at(file: &File, offset: u64, buf: &mut [u8]) -> std::io::Result<usize> {
    std::os::windows::fs::FileExt::seek_read(file, buf, offset)
}

#[cfg(unix)]
fn read_at(file: &File, offset: u64, buf: &mut [u8]) -> std::io::Result<usize> {
    std::os::unix::fs::FileExt::read_at(file, buf, offset)
}

#[cfg(test)]
mod tests {
    use super::*;
    use vfs_provider::RootId;

    fn vp(rel: &str) -> VPath<'_> {
        VPath {
            root: RootId(0),
            rel,
        }
    }

    /// The reference tree, served from files whose host names and folders
    /// differ from their virtual ones, as a mod manager's map would.
    fn fixture(name: &str) -> (PathBuf, MapProvider) {
        let dir = vfs_testkit::scratch_path(name);
        let _ = std::fs::remove_dir_all(&dir);
        std::fs::create_dir_all(dir.join("mod-a")).unwrap();
        std::fs::create_dir_all(dir.join("mod-b")).unwrap();
        let mut entries = Vec::new();
        for (i, (rel, body)) in vfs_provider::FIXTURE_FILES.iter().enumerate() {
            let host = dir
                .join(if i % 2 == 0 { "mod-a" } else { "mod-b" })
                .join(format!("f{i}"));
            std::fs::write(&host, body).unwrap();
            entries.push((rel.to_string(), host));
        }
        (dir, MapProvider::new(entries).unwrap())
    }

    #[test]
    fn map_provider_passes_conformance() {
        let (dir, p) = fixture("vfs-map-conf");
        vfs_provider::assert_conformance(Arc::new(p));
        let _ = std::fs::remove_dir_all(&dir);
    }

    #[test]
    fn the_last_entry_for_a_fold_equal_path_wins_and_keeps_its_spelling() {
        let dir = vfs_testkit::scratch_path("vfs-map-last");
        let _ = std::fs::remove_dir_all(&dir);
        std::fs::create_dir_all(&dir).unwrap();
        std::fs::write(dir.join("low"), b"low").unwrap();
        std::fs::write(dir.join("high"), b"high!").unwrap();
        let p = MapProvider::new([
            ("Data/Plugin.esp", dir.join("low")),
            ("data/PLUGIN.ESP", dir.join("high")),
        ])
        .unwrap();
        assert_eq!(p.file_count(), 1);
        assert_eq!(p.getattr(vp("DATA/plugin.esp")).unwrap().unwrap().size, 5);
        let listing = p.readdir(vp("data")).unwrap();
        assert_eq!(listing.len(), 1);
        assert_eq!(listing[0].name, "PLUGIN.ESP");
        let _ = std::fs::remove_dir_all(&dir);
    }

    #[test]
    fn a_path_that_is_both_file_and_directory_is_refused() {
        let err = MapProvider::new([("a", "x"), ("a/b", "y")]).err().unwrap();
        assert_eq!(err, MapError::FileAndDir("a/b".into()));
        let err = MapProvider::new([("a/b", "y"), ("a", "x")]).err().unwrap();
        assert_eq!(err, MapError::FileAndDir("a".into()));
    }

    #[test]
    fn paths_that_escape_or_are_absolute_are_refused() {
        for bad in ["", "/a", "\\a", "a/../b", "./a", "C:\\a"] {
            assert!(
                matches!(MapProvider::new([(bad, "x")]), Err(MapError::BadPath(_))),
                "{bad:?} must be refused"
            );
        }
    }

    #[test]
    fn a_vanished_host_file_reads_as_missing() {
        let dir = vfs_testkit::scratch_path("vfs-map-gone");
        let _ = std::fs::remove_dir_all(&dir);
        std::fs::create_dir_all(&dir).unwrap();
        let p = MapProvider::new([("a.txt", dir.join("never-written"))]).unwrap();
        assert_eq!(p.getattr(vp("a.txt")).unwrap(), None);
        assert!(p.readdir(vp("")).unwrap().is_empty());
        assert_eq!(p.open(vp("a.txt"), 0).err(), Some(not_found()));
        let _ = std::fs::remove_dir_all(&dir);
    }

    #[test]
    fn writes_are_refused() {
        let (dir, p) = fixture("vfs-map-ro");
        assert_eq!(p.open(vp("a.txt"), OPEN_WRITE).err(), Some(read_only()));
        assert_eq!(p.open(vp("new.txt"), OPEN_CREATE).err(), Some(read_only()));
        let _ = std::fs::remove_dir_all(&dir);
    }
}
