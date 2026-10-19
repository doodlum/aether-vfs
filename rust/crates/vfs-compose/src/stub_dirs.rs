//! A read-only tree of empty directories: the folder chain to somewhere else.
//!
//! Two jobs, both about locations that must look reachable without anything
//! real being made:
//!
//! - the **ancestors of a root's location** that do not exist on disk
//!   (`C:\Haskill` above `C:\Haskill\<list>\game`), served as a hidden root of
//!   their own so a program can open, list and "create" the folder chain;
//! - the **chain to a nested root** inside its parent root
//!   (`Documents\My Games` under a profile root), layered under the parent's
//!   own content so the parent lists it.
//!
//! Each path given brings its ancestors with it. Opening or listing a
//! directory works; making one that is already here reports that it exists
//! (so a folder-chain create succeeds), and every other change — a new name,
//! a delete, a rename — is refused as read-only (`STATUS_ACCESS_DENIED` to
//! the program).

use std::collections::BTreeMap;

use vfs_provider::{
    exists, not_found, read_only, Capabilities, DirEntry, Handle, HandleTable, Provider, Stat,
    VPath, KIND_DIR, OPEN_WRITE,
};

/// See the module docs.
pub struct StubDirsProvider {
    /// Folded path → the path as given; `""` (the root) is always present.
    dirs: BTreeMap<String, String>,
    opens: HandleTable<()>,
}

impl StubDirsProvider {
    /// The directories `paths` (`/`-separated, relative) and all their
    /// ancestors.
    pub fn new<I, P>(paths: I) -> Self
    where
        I: IntoIterator<Item = P>,
        P: AsRef<str>,
    {
        let mut dirs = BTreeMap::new();
        dirs.insert(String::new(), String::new());
        for p in paths {
            let p = vfs_core::trim_rel(p.as_ref());
            let mut at = String::new();
            for c in p.split('/').filter(|c| !c.is_empty()) {
                if !at.is_empty() {
                    at.push('/');
                }
                at.push_str(c);
                dirs.entry(vfs_core::fold(&at))
                    .or_insert_with(|| at.clone());
            }
        }
        StubDirsProvider {
            dirs,
            opens: HandleTable::new(),
        }
    }

    fn known(&self, p: &VPath) -> Option<&String> {
        self.dirs.get(&vfs_core::fold(&vfs_core::trim_rel(p.rel)))
    }
}

const DIR: Stat = Stat {
    kind: KIND_DIR,
    size: 0,
    mtime: 0,
};

impl Provider for StubDirsProvider {
    fn capabilities(&self) -> Capabilities {
        Capabilities {
            immutable: true,
            ..Capabilities::read_only()
        }
    }

    fn getattr(&self, p: VPath) -> Result<Option<Stat>, i32> {
        Ok(self.known(&p).map(|_| DIR))
    }

    fn readdir(&self, p: VPath) -> Result<Vec<DirEntry>, i32> {
        let Some(dir) = self.known(&p) else {
            return Err(not_found());
        };
        let prefix = if dir.is_empty() {
            String::new()
        } else {
            format!("{}/", vfs_core::fold(dir))
        };
        Ok(self
            .dirs
            .iter()
            .filter_map(|(k, spelled)| {
                let rest = k.strip_prefix(&prefix)?;
                (!rest.is_empty() && !rest.contains('/')).then(|| DirEntry {
                    name: spelled.rsplit('/').next().unwrap_or(spelled).to_string(),
                    stat: DIR,
                })
            })
            .collect())
    }

    fn open(&self, p: VPath, flags: u32) -> Result<(Handle, u64, bool), i32> {
        if self.known(&p).is_none() {
            return Err(not_found());
        }
        if flags & OPEN_WRITE != 0 {
            return Err(read_only());
        }
        Ok((self.opens.insert(())?, 0, true))
    }

    fn close(&self, h: Handle) -> Result<(), i32> {
        self.opens.remove(h).map(|_| ())
    }

    fn read_at(&self, _h: Handle, _offset: u64, _buf: &mut [u8]) -> Result<usize, i32> {
        Err(vfs_provider::is_dir())
    }

    fn mkdir(&self, p: VPath) -> Result<(), i32> {
        match self.known(&p) {
            Some(_) => Err(exists()),
            None => Err(read_only()),
        }
    }

    fn remove(&self, _p: VPath) -> Result<(), i32> {
        Err(read_only())
    }

    fn rename(&self, _from: VPath, _to: VPath) -> Result<(), i32> {
        Err(read_only())
    }

    fn stored_name(&self, p: VPath) -> Result<Option<String>, i32> {
        Ok(self
            .known(&p)
            .filter(|s| !s.is_empty())
            .map(|s| s.rsplit('/').next().unwrap_or(s).to_string()))
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn names(p: &StubDirsProvider, dir: &str) -> Vec<String> {
        let mut v: Vec<String> = p
            .readdir(VPath::at_default(dir))
            .unwrap()
            .into_iter()
            .map(|e| e.name)
            .collect();
        v.sort();
        v
    }

    #[test]
    fn each_path_brings_its_chain_and_lists_one_level() {
        let p = StubDirsProvider::new(["list/game", "list/profiles/p1", "Other"]);
        assert_eq!(names(&p, ""), ["Other", "list"]);
        assert_eq!(names(&p, "LIST"), ["game", "profiles"]);
        assert_eq!(names(&p, "list/profiles"), ["p1"]);
        assert!(names(&p, "list/game").is_empty());
        assert_eq!(
            p.getattr(VPath::at_default("list/Profiles")).unwrap(),
            Some(DIR)
        );
        assert_eq!(p.getattr(VPath::at_default("list/x")).unwrap(), None);
        assert_eq!(
            p.stored_name(VPath::at_default("LIST/PROFILES"))
                .unwrap()
                .as_deref(),
            Some("profiles")
        );
    }

    #[test]
    fn a_known_directory_exists_and_anything_else_is_read_only() {
        let p = StubDirsProvider::new(["a/b"]);
        assert_eq!(p.mkdir(VPath::at_default("a")), Err(exists()));
        assert_eq!(p.mkdir(VPath::at_default("a/c")), Err(read_only()));
        assert_eq!(p.remove(VPath::at_default("a/b")), Err(read_only()));
        assert_eq!(
            p.rename(VPath::at_default("a/b"), VPath::at_default("a/z")),
            Err(read_only())
        );
        let (h, size, is_dir) = p.open(VPath::at_default("a"), 0).unwrap();
        assert!(is_dir && size == 0);
        p.close(h).unwrap();
        assert_eq!(p.open(VPath::at_default("a"), OPEN_WRITE), Err(read_only()));
        assert_eq!(p.open(VPath::at_default("nope"), 0), Err(not_found()));
        assert!(p.readdir(VPath::at_default("nope")).is_err());
    }
}
