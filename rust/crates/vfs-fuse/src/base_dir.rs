//! The real folder underneath a mount, read through a directory descriptor
//! opened before the mount.
//!
//! A FUSE mount over `/games/Game` hides `/games/Game` from every path lookup,
//! including the daemon's own: a [`vfs_compose::DiskProvider`]-style provider
//! that opened `/games/Game/base.txt` by path would ask the mount for it, and
//! the mount would ask the provider, forever. A descriptor opened on the folder
//! *before* the mount still names the directory underneath, and lookups
//! relative to it (`openat2`) walk the real filesystem.
//!
//! Every lookup is confined beneath that descriptor (`RESOLVE_BENEATH`): a
//! symlink in the game folder that points outside it, or at an absolute path
//! (which would lead back through the mount), is treated as absent rather
//! than followed.
//!
//! Names match case-insensitively with [`vfs_core::fold`], the same rule every
//! other provider in the stack uses: exact spelling first, then a scan of the
//! directory only on a miss.

use std::collections::HashMap;
use std::ffi::OsStr;
use std::fs::File;
use std::io;
use std::os::fd::OwnedFd;
use std::os::unix::fs::FileExt;
use std::path::{Path, PathBuf};
use std::sync::atomic::{AtomicU64, Ordering};
use std::sync::{Arc, Mutex};

use rustix::fs::{
    fstat, openat, openat2, statat, AtFlags, Dir, FileType, Mode, OFlags, ResolveFlags, CWD,
};
use rustix::io::Errno;
use vfs_provider::{
    bad_request, map_io_err, not_a_dir, not_found, read_only, Access, Capabilities, CaseMatch,
    DirEntry, Handle, Provider, Stat, VPath, KIND_DIR, KIND_FILE, OPEN_APPEND, OPEN_CREATE,
    OPEN_TRUNC, OPEN_WRITE,
};

/// A real directory, held open so that it stays readable after something is
/// mounted over its path.
#[derive(Clone, Debug)]
pub struct BaseDir {
    fd: Arc<OwnedFd>,
    path: PathBuf,
}

impl BaseDir {
    /// Open the directory at `path`. Do this **before** mounting over it (or
    /// over any folder above it): afterwards `path` names the mount.
    pub fn open(path: impl AsRef<Path>) -> io::Result<Self> {
        let path = path.as_ref();
        let fd = openat(
            CWD,
            path,
            OFlags::RDONLY | OFlags::DIRECTORY | OFlags::CLOEXEC,
            Mode::empty(),
        )?;
        Ok(BaseDir {
            fd: Arc::new(fd),
            path: path.to_path_buf(),
        })
    }

    /// The path this directory was opened at — which, once a mount covers
    /// it, no longer leads here.
    pub fn path(&self) -> &Path {
        &self.path
    }

    /// The directory at `rel` beneath this one, looked up through this
    /// descriptor: the real one even while a mount covers this directory.
    /// `rel` is matched exactly, the way a host spells a folder it chose.
    pub fn subdir(&self, rel: impl AsRef<Path>) -> io::Result<BaseDir> {
        let rel = rel.as_ref();
        if rel.as_os_str().is_empty() || rel == Path::new(".") {
            return Ok(self.clone());
        }
        let fd = open_beneath(
            &self.fd,
            rel.as_os_str(),
            OFlags::RDONLY | OFlags::DIRECTORY | OFlags::CLOEXEC,
        )?;
        Ok(BaseDir {
            fd: Arc::new(fd),
            path: self.path.join(rel),
        })
    }

    /// The directory `path` names, if it is this one or lies beneath it:
    /// `Some(Ok(..))` through this descriptor, `None` when `path` is
    /// elsewhere (compared lexically, both sides absolute).
    pub fn resolve_host_path(&self, path: &Path) -> Option<io::Result<BaseDir>> {
        let rel = path.strip_prefix(&self.path).ok()?;
        Some(self.subdir(rel))
    }

    /// A read-only provider over this directory's content.
    pub fn provider(&self) -> BaseDirProvider {
        BaseDirProvider::new(self.clone())
    }

    pub(crate) fn fd(&self) -> &OwnedFd {
        &self.fd
    }
}

/// `openat2` beneath `dir`, refusing to leave it by `..` or a symlink and
/// never following `/proc` magic links. Falls back to a plain `openat` where
/// `openat2` is unavailable (kernels before 5.6, or a seccomp filter that
/// refuses it, as some sandboxes do).
fn open_beneath(dir: &OwnedFd, rel: &OsStr, flags: OFlags) -> rustix::io::Result<OwnedFd> {
    let rel: &OsStr = if rel.is_empty() { OsStr::new(".") } else { rel };
    match openat2(
        dir,
        rel,
        flags,
        Mode::empty(),
        ResolveFlags::BENEATH | ResolveFlags::NO_MAGICLINKS,
    ) {
        Err(Errno::NOSYS) | Err(Errno::PERM) => openat(dir, rel, flags, Mode::empty()),
        other => other,
    }
}

/// Read-only access to a [`BaseDir`]'s content as a [`Provider`].
///
/// Reports real sizes and modification times (seconds since the Unix epoch).
/// Symlinks are followed while they stay beneath the directory and are absent
/// otherwise; anything that is neither a file nor a directory is absent.
pub struct BaseDirProvider {
    dir: BaseDir,
    next: AtomicU64,
    opens: Mutex<HashMap<Handle, Arc<File>>>,
}

/// What a lookup found: the stored spelling of the whole path, and its stat.
struct Found {
    real: String,
    stat: rustix::fs::Stat,
}

fn kind_of(st: &rustix::fs::Stat) -> Option<u8> {
    match FileType::from_raw_mode(st.st_mode) {
        FileType::Directory => Some(KIND_DIR),
        FileType::RegularFile => Some(KIND_FILE),
        _ => None,
    }
}

// `st_mtime` is `i64` on x86_64 and `c_long` on other Linux targets.
#[allow(clippy::unnecessary_cast)]
fn to_stat(st: &rustix::fs::Stat) -> Option<Stat> {
    let kind = kind_of(st)?;
    Some(Stat {
        kind,
        size: if kind == KIND_DIR {
            0
        } else {
            st.st_size as u64
        },
        mtime: st.st_mtime as i64,
    })
}

fn join(parent: &str, name: &str) -> String {
    if parent.is_empty() {
        name.to_string()
    } else {
        format!("{parent}/{name}")
    }
}

/// Errors that mean "nothing there" for a lookup: missing, a file where a
/// directory was expected, or a path that would leave the directory.
fn is_absent(e: Errno) -> bool {
    matches!(
        e,
        Errno::NOENT | Errno::NOTDIR | Errno::XDEV | Errno::LOOP | Errno::NAMETOOLONG
    )
}

impl BaseDirProvider {
    pub fn new(dir: BaseDir) -> Self {
        BaseDirProvider {
            dir,
            next: AtomicU64::new(1),
            opens: Mutex::new(HashMap::new()),
        }
    }

    /// Stat `rel` exactly as spelled, following symlinks beneath the directory.
    fn stat_exact(&self, rel: &str) -> rustix::io::Result<rustix::fs::Stat> {
        let fd = open_beneath(
            self.dir.fd(),
            OsStr::new(rel),
            OFlags::PATH | OFlags::CLOEXEC,
        )?;
        fstat(fd)
    }

    /// The names in directory `rel` (stored spelling), with `.`/`..` and
    /// names that are not UTF-8 left out (no vpath can name them).
    fn names(&self, rel: &str) -> rustix::io::Result<(OwnedFd, Vec<String>)> {
        let fd = open_beneath(
            self.dir.fd(),
            OsStr::new(rel),
            OFlags::RDONLY | OFlags::DIRECTORY | OFlags::CLOEXEC,
        )?;
        let mut out = Vec::new();
        let mut dir = Dir::read_from(&fd)?;
        while let Some(entry) = dir.read() {
            let entry = entry?;
            let name = entry.file_name().to_bytes();
            if name == b"." || name == b".." {
                continue;
            }
            if let Ok(s) = std::str::from_utf8(name) {
                out.push(s.to_string());
            }
        }
        Ok((fd, out))
    }

    /// Find `rel` under any spelling: exact first, then component by component
    /// with a fold-equal scan where the exact name misses. Two fold-equal
    /// names in one directory (legal on ext4) resolve to the byte-smallest,
    /// so the choice is deterministic.
    fn resolve(&self, rel: &str) -> Result<Option<Found>, i32> {
        if rel.split('/').any(|c| c == "..") {
            return Err(bad_request());
        }
        match self.stat_exact(rel) {
            Ok(stat) => {
                return Ok(Some(Found {
                    real: rel.to_string(),
                    stat,
                }))
            }
            Err(e) if is_absent(e) => {}
            Err(_) => return Err(map_io_err()),
        }
        let mut cur = String::new();
        for part in rel.split('/').filter(|c| !c.is_empty()) {
            let candidate = join(&cur, part);
            match self.stat_exact(&candidate) {
                Ok(_) => {
                    cur = candidate;
                    continue;
                }
                Err(e) if is_absent(e) => {}
                Err(_) => return Err(map_io_err()),
            }
            let names = match self.names(&cur) {
                Ok((_, names)) => names,
                Err(e) if is_absent(e) => return Ok(None),
                Err(_) => return Err(map_io_err()),
            };
            let want = vfs_core::fold(part);
            let best = names
                .into_iter()
                .filter(|n| vfs_core::fold(n) == want)
                .min();
            match best {
                Some(name) => cur = join(&cur, &name),
                None => return Ok(None),
            }
        }
        match self.stat_exact(&cur) {
            Ok(stat) => Ok(Some(Found { real: cur, stat })),
            Err(e) if is_absent(e) => Ok(None),
            Err(_) => Err(map_io_err()),
        }
    }
}

impl Provider for BaseDirProvider {
    fn capabilities(&self) -> Capabilities {
        Capabilities {
            access: Access::Read,
            // A real directory can change underneath us.
            immutable: false,
            slow: false,
            preferred_block: None,
            case: CaseMatch::Insensitive,
        }
    }

    fn getattr(&self, p: VPath) -> Result<Option<Stat>, i32> {
        Ok(self.resolve(p.rel)?.and_then(|f| to_stat(&f.stat)))
    }

    fn readdir(&self, p: VPath) -> Result<Vec<DirEntry>, i32> {
        let found = self.resolve(p.rel)?.ok_or_else(not_found)?;
        if kind_of(&found.stat) != Some(KIND_DIR) {
            return Err(not_a_dir());
        }
        let (fd, names) = match self.names(&found.real) {
            Ok(x) => x,
            Err(e) if is_absent(e) => return Err(not_found()),
            Err(_) => return Err(map_io_err()),
        };
        let mut out = Vec::with_capacity(names.len());
        for name in names {
            // One component, not followed: it cannot leave the directory.
            let st = match statat(&fd, name.as_str(), AtFlags::SYMLINK_NOFOLLOW) {
                Ok(st) => st,
                Err(_) => continue,
            };
            let st = if FileType::from_raw_mode(st.st_mode) == FileType::Symlink {
                // Followed only while it stays beneath the base directory.
                match self.stat_exact(&join(&found.real, &name)) {
                    Ok(st) => st,
                    Err(_) => continue,
                }
            } else {
                st
            };
            if let Some(stat) = to_stat(&st) {
                out.push(DirEntry { name, stat });
            }
        }
        out.sort_by_cached_key(|e| vfs_core::fold(&e.name));
        Ok(out)
    }

    fn open(&self, p: VPath, flags: u32) -> Result<(Handle, u64, bool), i32> {
        if flags & (OPEN_WRITE | OPEN_CREATE | OPEN_TRUNC | OPEN_APPEND) != 0 {
            return Err(read_only());
        }
        let found = self.resolve(p.rel)?.ok_or_else(not_found)?;
        let h = self.next.fetch_add(1, Ordering::Relaxed);
        match kind_of(&found.stat) {
            Some(KIND_DIR) => Ok((h, 0, true)),
            Some(_) => {
                let fd = open_beneath(
                    self.dir.fd(),
                    OsStr::new(&found.real),
                    OFlags::RDONLY | OFlags::CLOEXEC,
                )
                .map_err(|e| {
                    if is_absent(e) {
                        not_found()
                    } else {
                        map_io_err()
                    }
                })?;
                let file = File::from(fd);
                let size = file.metadata().map_err(|_| map_io_err())?.len();
                self.opens
                    .lock()
                    .map_err(|_| map_io_err())?
                    .insert(h, Arc::new(file));
                Ok((h, size, false))
            }
            None => Err(not_found()),
        }
    }

    fn read_at(&self, h: Handle, offset: u64, buf: &mut [u8]) -> Result<usize, i32> {
        let file = self
            .opens
            .lock()
            .map_err(|_| map_io_err())?
            .get(&h)
            .cloned()
            .ok_or_else(vfs_provider::bad_fh)?;
        file.read_at(buf, offset).map_err(|_| map_io_err())
    }

    fn close(&self, h: Handle) -> Result<(), i32> {
        // Directory handles were never recorded.
        let _ = self.opens.lock().map_err(|_| map_io_err())?.remove(&h);
        Ok(())
    }

    fn stored_name(&self, p: VPath) -> Result<Option<String>, i32> {
        if p.rel.is_empty() {
            return Ok(None);
        }
        Ok(self.resolve(p.rel)?.map(|f| {
            f.real
                .rsplit('/')
                .next()
                .unwrap_or(f.real.as_str())
                .to_string()
        }))
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn scratch(name: &str) -> PathBuf {
        let dir = std::env::temp_dir().join(format!("vfs-fuse-{name}-{}", std::process::id()));
        let _ = std::fs::remove_dir_all(&dir);
        std::fs::create_dir_all(&dir).unwrap();
        dir
    }

    #[test]
    fn reads_and_lists_case_insensitively_with_real_times() {
        let dir = scratch("basedir");
        std::fs::create_dir_all(dir.join("Data/Sub")).unwrap();
        std::fs::write(dir.join("Data/Plugin.esp"), b"plugin").unwrap();
        std::fs::write(dir.join("top.txt"), b"t").unwrap();
        let p = BaseDir::open(&dir).unwrap().provider();

        for spelling in ["Data/Plugin.esp", "data/plugin.ESP", "DATA/PLUGIN.ESP"] {
            let st = p.getattr(VPath::at_default(spelling)).unwrap().unwrap();
            assert_eq!((st.kind, st.size), (KIND_FILE, 6), "{spelling}");
        }
        let real = std::fs::metadata(dir.join("Data/Plugin.esp")).unwrap();
        let st = p
            .getattr(VPath::at_default("data/plugin.esp"))
            .unwrap()
            .unwrap();
        assert_eq!(
            st.mtime,
            real.modified()
                .unwrap()
                .duration_since(std::time::UNIX_EPOCH)
                .unwrap()
                .as_secs() as i64
        );

        let (h, size, is_dir) = p.open(VPath::at_default("DATA/plugin.esp"), 1).unwrap();
        assert_eq!((size, is_dir), (6, false));
        let mut buf = [0u8; 16];
        let n = p.read_at(h, 0, &mut buf).unwrap();
        assert_eq!(&buf[..n], b"plugin");
        p.close(h).unwrap();

        let names: Vec<String> = p
            .readdir(VPath::at_default("data"))
            .unwrap()
            .into_iter()
            .map(|e| e.name)
            .collect();
        assert_eq!(names, ["Plugin.esp", "Sub"]);
        assert_eq!(
            p.stored_name(VPath::at_default("data/plugin.esp")).unwrap(),
            Some("Plugin.esp".to_string())
        );
        assert_eq!(p.getattr(VPath::at_default("missing")).unwrap(), None);
        assert_eq!(
            p.open(VPath::at_default("top.txt"), OPEN_WRITE),
            Err(read_only())
        );
        let _ = std::fs::remove_dir_all(&dir);
    }

    #[test]
    fn a_symlink_out_of_the_directory_is_absent() {
        let dir = scratch("basedir-link");
        let outside = scratch("basedir-outside");
        std::fs::write(outside.join("secret"), b"s").unwrap();
        std::fs::write(dir.join("inside"), b"i").unwrap();
        std::os::unix::fs::symlink(outside.join("secret"), dir.join("abs")).unwrap();
        std::os::unix::fs::symlink("inside", dir.join("rel")).unwrap();
        let p = BaseDir::open(&dir).unwrap().provider();
        assert_eq!(p.getattr(VPath::at_default("abs")).unwrap(), None);
        assert_eq!(
            p.getattr(VPath::at_default("rel")).unwrap().unwrap().size,
            1
        );
        let names: Vec<String> = p
            .readdir(VPath::at_default(""))
            .unwrap()
            .into_iter()
            .map(|e| e.name)
            .collect();
        assert_eq!(names, ["inside", "rel"]);
        let _ = std::fs::remove_dir_all(&dir);
        let _ = std::fs::remove_dir_all(&outside);
    }
}
