//! Directories that may not be deleted or renamed: the folder chain to a
//! nested root, inside its parent root.
//!
//! A parent root lists the chain to each root nested in its location (see
//! [`crate::StubDirsProvider`]); deleting or renaming one of those folders
//! would hide a root the program is still using, so this wrapper refuses it
//! as read-only — `STATUS_ACCESS_DENIED`, which is what Windows answers for a
//! folder something has open. Everything else goes to the wrapped provider.

use std::collections::BTreeSet;
use std::sync::Arc;

use vfs_provider::{read_only, Capabilities, DirEntry, Handle, Provider, SetAttr, Stat, VPath};

/// See the module docs.
pub struct GuardedProvider {
    inner: Arc<dyn Provider>,
    /// Folded paths of the protected directories.
    protected: BTreeSet<String>,
}

impl GuardedProvider {
    /// `inner`, with each of `dirs` (`/`-separated, relative) and every
    /// ancestor of one protected.
    pub fn new<I, P>(inner: Arc<dyn Provider>, dirs: I) -> Self
    where
        I: IntoIterator<Item = P>,
        P: AsRef<str>,
    {
        let mut protected = BTreeSet::new();
        for d in dirs {
            let d = vfs_core::trim_rel(d.as_ref());
            let mut at = String::new();
            for c in d.split('/').filter(|c| !c.is_empty()) {
                if !at.is_empty() {
                    at.push('/');
                }
                at.push_str(c);
                protected.insert(vfs_core::fold(&at));
            }
        }
        GuardedProvider { inner, protected }
    }

    fn guards(&self, p: &VPath) -> bool {
        self.protected
            .contains(&vfs_core::fold(&vfs_core::trim_rel(p.rel)))
    }
}

impl Provider for GuardedProvider {
    fn capabilities(&self) -> Capabilities {
        self.inner.capabilities()
    }
    fn getattr(&self, p: VPath) -> Result<Option<Stat>, i32> {
        self.inner.getattr(p)
    }
    fn readdir(&self, p: VPath) -> Result<Vec<DirEntry>, i32> {
        self.inner.readdir(p)
    }
    fn open(&self, p: VPath, flags: u32) -> Result<(Handle, u64, bool), i32> {
        self.inner.open(p, flags)
    }
    fn close(&self, h: Handle) -> Result<(), i32> {
        self.inner.close(h)
    }
    fn read_at(&self, h: Handle, offset: u64, buf: &mut [u8]) -> Result<usize, i32> {
        self.inner.read_at(h, offset, buf)
    }
    fn read_next(&self, h: Handle, buf: &mut [u8]) -> Result<usize, i32> {
        self.inner.read_next(h, buf)
    }
    fn write_at(&self, h: Handle, offset: u64, buf: &[u8]) -> Result<usize, i32> {
        self.inner.write_at(h, offset, buf)
    }
    fn set_len(&self, h: Handle, len: u64) -> Result<(), i32> {
        self.inner.set_len(h, len)
    }
    fn flush(&self, h: Handle) -> Result<(), i32> {
        self.inner.flush(h)
    }
    fn mkdir(&self, p: VPath) -> Result<(), i32> {
        self.inner.mkdir(p)
    }
    fn remove(&self, p: VPath) -> Result<(), i32> {
        if self.guards(&p) {
            return Err(read_only());
        }
        self.inner.remove(p)
    }
    fn rename(&self, from: VPath, to: VPath) -> Result<(), i32> {
        if self.guards(&from) || self.guards(&to) {
            return Err(read_only());
        }
        self.inner.rename(from, to)
    }
    fn set_attr(&self, p: VPath, attr: SetAttr) -> Result<(), i32> {
        self.inner.set_attr(p, attr)
    }
    fn stored_name(&self, p: VPath) -> Result<Option<String>, i32> {
        self.inner.stored_name(p)
    }
    fn is_immutable(&self, h: Handle) -> bool {
        self.inner.is_immutable(h)
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::MemoryProvider;

    #[test]
    fn the_chain_cannot_be_deleted_or_renamed_but_everything_else_can() {
        let mem: Arc<dyn Provider> = Arc::new(MemoryProvider::from_files([
            ("Documents/stray.txt", b"x".as_slice()),
            ("other.txt", b"y".as_slice()),
        ]));
        let g = GuardedProvider::new(mem, ["Documents/My Games"]);
        assert_eq!(g.remove(VPath::at_default("documents")), Err(read_only()));
        assert_eq!(
            g.remove(VPath::at_default("Documents/My Games")),
            Err(read_only())
        );
        assert_eq!(
            g.rename(VPath::at_default("Documents"), VPath::at_default("D2")),
            Err(read_only())
        );
        assert_eq!(
            g.rename(
                VPath::at_default("other.txt"),
                VPath::at_default("Documents")
            ),
            Err(read_only())
        );
        assert_eq!(g.remove(VPath::at_default("other.txt")), Ok(()));
        assert_eq!(g.remove(VPath::at_default("Documents/stray.txt")), Ok(()));
    }
}
