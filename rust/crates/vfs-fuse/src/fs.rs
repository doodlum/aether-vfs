//! The FUSE operation set, answered by one director root.
//!
//! A thin translation, in the spirit of `vfs_director::ring_dispatch`: FUSE's
//! inode-addressed requests become the director's path-addressed calls, and
//! director statuses become errno ([`crate::errno_of`]). Semantics that the
//! director leaves to its caller and POSIX requires — `rmdir` only of an
//! empty directory, `unlink` only of a file, `rename`'s type checks and
//! `RENAME_NOREPLACE` — are checked here, before the call.

use std::collections::HashMap;
use std::ffi::OsStr;
use std::path::PathBuf;
use std::sync::atomic::{AtomicU64, Ordering};
use std::sync::{Arc, Mutex, MutexGuard};
use std::time::{Duration, SystemTime, UNIX_EPOCH};

use fuser::{
    Errno, FileAttr, FileHandle, FileType, Filesystem, FopenFlags, Generation, INodeNo, LockOwner,
    OpenFlags, RenameFlags, ReplyAttr, ReplyCreate, ReplyData, ReplyDirectory, ReplyEmpty,
    ReplyEntry, ReplyOpen, ReplyStatfs, ReplyWrite, Request, TimeOrNow, WriteFlags,
};
use rustix::fs::OFlags;
use vfs_director::Director;
use vfs_provider::{
    RootId, SetAttr, Stat, KIND_DIR, OPEN_APPEND, OPEN_CREATE, OPEN_EXCL, OPEN_READ, OPEN_TRUNC,
    OPEN_WRITE, ST_NOT_SUPPORTED,
};

use crate::errno::{errno_of, libc_errno};
use crate::inodes::{join, Inodes, ROOT};

/// One entry of an open directory's listing, taken at `opendir` so offsets
/// stay stable while the directory is read.
struct ListEntry {
    ino: u64,
    kind: FileType,
    name: String,
}

/// What an open file handle knows about its own file, for `fstat` on a handle
/// whose path no longer leads to it: the file was removed, or a remount put
/// other content there. The kernel also caps reads at the size it reports,
/// so answering such a handle by path would cut its reads short.
#[derive(Clone, Copy)]
struct OpenRec {
    size: u64,
    mtime: i64,
    /// The director's mount generation when it was opened.
    gen: u32,
}

/// State shared by the FUSE threads and the [`crate::FuseMount`] that owns them.
pub(crate) struct Shared {
    pub(crate) director: Arc<Director>,
    pub(crate) root: RootId,
    pub(crate) inodes: Mutex<Inodes>,
    opens: Mutex<HashMap<u64, OpenRec>>,
    lists: Mutex<HashMap<u64, Vec<ListEntry>>>,
    next_list: AtomicU64,
    pub(crate) ttl: Duration,
    uid: u32,
    gid: u32,
    /// Reported for a `Stat.mtime` of 0 ("no time"): the mount's start, a
    /// stable and plausible time rather than 1970.
    no_time: SystemTime,
    /// What `statfs` reports the free space of: the folder holding the mount
    /// point (never a path under the mount itself).
    statfs_path: Option<PathBuf>,
}

impl Shared {
    pub(crate) fn new(
        director: Arc<Director>,
        root: RootId,
        ttl: Duration,
        statfs_path: Option<PathBuf>,
    ) -> Self {
        Shared {
            director,
            root,
            inodes: Mutex::new(Inodes::new()),
            opens: Mutex::new(HashMap::new()),
            lists: Mutex::new(HashMap::new()),
            next_list: AtomicU64::new(1),
            ttl,
            uid: rustix::process::getuid().as_raw(),
            gid: rustix::process::getgid().as_raw(),
            no_time: SystemTime::now(),
            statfs_path,
        }
    }

    pub(crate) fn inodes(&self) -> MutexGuard<'_, Inodes> {
        self.inodes.lock().unwrap_or_else(|e| e.into_inner())
    }

    fn path(&self, ino: INodeNo) -> Result<String, Errno> {
        self.inodes()
            .path(ino.0)
            .ok_or(Errno::from_i32(libc_errno::ENOENT))
    }

    fn child(&self, parent: INodeNo, name: &OsStr) -> Result<(String, String), Errno> {
        // The director's paths are UTF-8; a name that is not cannot exist there.
        let name = name.to_str().ok_or(Errno::from_i32(libc_errno::ENOENT))?;
        Ok((join(&self.path(parent)?, name), name.to_string()))
    }

    fn stat(&self, path: &str) -> Result<Option<Stat>, Errno> {
        self.director.getattr(self.root, path).map_err(err)
    }

    fn attr(&self, ino: u64, st: &Stat) -> FileAttr {
        let dir = st.kind == KIND_DIR;
        let time = if st.mtime > 0 {
            UNIX_EPOCH + Duration::from_secs(st.mtime as u64)
        } else {
            self.no_time
        };
        let size = if dir { 0 } else { st.size };
        FileAttr {
            ino: INodeNo(ino),
            size,
            blocks: size.div_ceil(512),
            atime: time,
            mtime: time,
            ctime: time,
            crtime: time,
            kind: if dir {
                FileType::Directory
            } else {
                FileType::RegularFile
            },
            // Executable as well as readable: `Stat` carries no mode, and a
            // native Linux game's own binary must stay runnable through the
            // mount. Only the mounting user can reach the mount at all.
            perm: 0o755,
            nlink: if dir { 2 } else { 1 },
            uid: self.uid,
            gid: self.gid,
            rdev: 0,
            blksize: 4096,
            flags: 0,
        }
    }

    /// Reply with the entry for `path` after a create, and take its lookup.
    fn entry_of(&self, parent: INodeNo, name: &str, path: &str) -> Result<FileAttr, Errno> {
        let st = self
            .stat(path)?
            .ok_or(Errno::from_i32(libc_errno::ENOENT))?;
        let ino = self.inodes().looked_up(parent.0, name, path);
        Ok(self.attr(ino, &st))
    }

    fn open_rec(&self, fh: u64) -> Option<OpenRec> {
        self.opens
            .lock()
            .unwrap_or_else(|e| e.into_inner())
            .get(&fh)
            .copied()
    }

    fn update_open(&self, fh: u64, f: impl FnOnce(&mut OpenRec)) {
        if let Some(rec) = self
            .opens
            .lock()
            .unwrap_or_else(|e| e.into_inner())
            .get_mut(&fh)
        {
            f(rec);
        }
    }

    /// Record a new file handle (after an open or create of `path`).
    fn track_open(&self, fh: u64, size: u64, gen: u32, path: &str) {
        let mtime = self.stat(path).ok().flatten().map_or(0, |s| s.mtime);
        self.opens
            .lock()
            .unwrap_or_else(|e| e.into_inner())
            .insert(fh, OpenRec { size, mtime, gen });
    }

    /// The attributes of `ino`, asked through handle `fh` if the kernel gave
    /// one: by path, unless the handle's file is no longer what the path
    /// names (removed, or remounted since it was opened).
    fn attr_for(&self, ino: INodeNo, fh: Option<FileHandle>) -> Result<FileAttr, Errno> {
        let rec = fh.and_then(|fh| self.open_rec(fh.0));
        let from_rec = |rec: OpenRec| {
            let st = Stat {
                kind: vfs_provider::KIND_FILE,
                size: rec.size,
                mtime: rec.mtime,
            };
            self.attr(ino.0, &st)
        };
        if let Some(rec) = rec {
            if rec.gen != self.director.mount_gen() {
                return Ok(from_rec(rec));
            }
        }
        let path = self.path(ino)?;
        match self.stat(&path)? {
            Some(st) => Ok(self.attr(ino.0, &st)),
            None => rec.map(from_rec).ok_or(Errno::from_i32(libc_errno::ENOENT)),
        }
    }

    fn is_empty_dir(&self, path: &str) -> Result<bool, Errno> {
        Ok(self
            .director
            .readdir(self.root, path)
            .map_err(err)?
            .is_empty())
    }
}

fn err(status: i32) -> Errno {
    Errno::from_i32(errno_of(status))
}

fn open_bits(flags: i32) -> u32 {
    let flags = OFlags::from_bits_retain(flags as u32);
    let mut out = OPEN_READ;
    if flags.intersects(OFlags::WRONLY | OFlags::RDWR) {
        out |= OPEN_WRITE;
    }
    if flags.contains(OFlags::APPEND) {
        out |= OPEN_WRITE | OPEN_APPEND;
    }
    if flags.contains(OFlags::TRUNC) {
        out |= OPEN_TRUNC;
    }
    if flags.contains(OFlags::EXCL) {
        out |= OPEN_EXCL;
    }
    out
}

fn system_secs(t: SystemTime) -> i64 {
    t.duration_since(UNIX_EPOCH)
        .map(|d| d.as_secs() as i64)
        .unwrap_or(0)
}

/// The FUSE filesystem: one director root.
pub(crate) struct AetherFs {
    pub(crate) shared: Arc<Shared>,
}

impl Filesystem for AetherFs {
    fn lookup(&self, _req: &Request, parent: INodeNo, name: &OsStr, reply: ReplyEntry) {
        let s = &self.shared;
        let result = (|| {
            let (path, name) = s.child(parent, name)?;
            let st = s.stat(&path)?.ok_or(Errno::from_i32(libc_errno::ENOENT))?;
            let ino = s.inodes().looked_up(parent.0, &name, &path);
            Ok(s.attr(ino, &st))
        })();
        match result {
            Ok(attr) => reply.entry(&s.ttl, &attr, Generation(0)),
            Err(e) => reply.error(e),
        }
    }

    fn forget(&self, _req: &Request, ino: INodeNo, nlookup: u64) {
        self.shared.inodes().forget(ino.0, nlookup);
    }

    fn getattr(&self, _req: &Request, ino: INodeNo, fh: Option<FileHandle>, reply: ReplyAttr) {
        let s = &self.shared;
        match s.attr_for(ino, fh) {
            Ok(attr) => reply.attr(&s.ttl, &attr),
            Err(e) => reply.error(e),
        }
    }

    fn setattr(
        &self,
        _req: &Request,
        ino: INodeNo,
        _mode: Option<u32>,
        _uid: Option<u32>,
        _gid: Option<u32>,
        size: Option<u64>,
        _atime: Option<TimeOrNow>,
        mtime: Option<TimeOrNow>,
        _ctime: Option<SystemTime>,
        fh: Option<FileHandle>,
        _crtime: Option<SystemTime>,
        _chgtime: Option<SystemTime>,
        _bkuptime: Option<SystemTime>,
        _flags: Option<fuser::BsdFileFlags>,
        reply: ReplyAttr,
    ) {
        let s = &self.shared;
        let result = (|| {
            let path = s.path(ino)?;
            if let Some(len) = size {
                match fh {
                    Some(fh) => {
                        s.director.set_len(fh.0, len).map_err(err)?;
                        s.update_open(fh.0, |r| r.size = len);
                    }
                    None => s
                        .director
                        .set_attr(
                            s.root,
                            &path,
                            SetAttr {
                                size: Some(len),
                                mtime: None,
                            },
                        )
                        .map_err(err)?,
                }
            }
            if let Some(t) = mtime {
                let secs = match t {
                    TimeOrNow::SpecificTime(t) => system_secs(t),
                    TimeOrNow::Now => system_secs(SystemTime::now()),
                };
                s.director
                    .set_attr(
                        s.root,
                        &path,
                        SetAttr {
                            size: None,
                            mtime: Some(secs),
                        },
                    )
                    .map_err(err)?;
            }
            // Mode and owner changes are accepted and ignored: the mount has
            // one owner and fixed permissions.
            s.attr_for(ino, fh)
        })();
        match result {
            Ok(attr) => reply.attr(&s.ttl, &attr),
            Err(e) => reply.error(e),
        }
    }

    fn mknod(
        &self,
        _req: &Request,
        parent: INodeNo,
        name: &OsStr,
        mode: u32,
        _umask: u32,
        _rdev: u32,
        reply: ReplyEntry,
    ) {
        let s = &self.shared;
        let result = (|| {
            let file_type = rustix::fs::FileType::from_raw_mode(mode);
            if file_type != rustix::fs::FileType::RegularFile {
                return Err(Errno::from_i32(libc_errno::EPERM));
            }
            let (path, name) = s.child(parent, name)?;
            let fh = s
                .director
                .open(
                    s.root,
                    &path,
                    OPEN_READ | OPEN_WRITE | OPEN_CREATE | OPEN_EXCL,
                )
                .map_err(err)?
                .0;
            let _ = s.director.close(fh);
            s.entry_of(parent, &name, &path)
        })();
        match result {
            Ok(attr) => reply.entry(&s.ttl, &attr, Generation(0)),
            Err(e) => reply.error(e),
        }
    }

    fn mkdir(
        &self,
        _req: &Request,
        parent: INodeNo,
        name: &OsStr,
        _mode: u32,
        _umask: u32,
        reply: ReplyEntry,
    ) {
        let s = &self.shared;
        let result = (|| {
            let (path, name) = s.child(parent, name)?;
            if s.stat(&path)?.is_some() {
                return Err(Errno::from_i32(libc_errno::EEXIST));
            }
            s.director.mkdir(s.root, &path).map_err(err)?;
            s.entry_of(parent, &name, &path)
        })();
        match result {
            Ok(attr) => reply.entry(&s.ttl, &attr, Generation(0)),
            Err(e) => reply.error(e),
        }
    }

    fn unlink(&self, _req: &Request, parent: INodeNo, name: &OsStr, reply: ReplyEmpty) {
        let s = &self.shared;
        let result = (|| {
            let (path, _) = s.child(parent, name)?;
            let st = s.stat(&path)?.ok_or(Errno::from_i32(libc_errno::ENOENT))?;
            if st.kind == KIND_DIR {
                return Err(Errno::from_i32(libc_errno::EISDIR));
            }
            s.director.remove(s.root, &path).map_err(err)?;
            s.inodes().detach(&path);
            Ok(())
        })();
        match result {
            Ok(()) => reply.ok(),
            Err(e) => reply.error(e),
        }
    }

    fn rmdir(&self, _req: &Request, parent: INodeNo, name: &OsStr, reply: ReplyEmpty) {
        let s = &self.shared;
        let result = (|| {
            let (path, _) = s.child(parent, name)?;
            let st = s.stat(&path)?.ok_or(Errno::from_i32(libc_errno::ENOENT))?;
            if st.kind != KIND_DIR {
                return Err(Errno::from_i32(libc_errno::ENOTDIR));
            }
            if !s.is_empty_dir(&path)? {
                return Err(Errno::from_i32(libc_errno::ENOTEMPTY));
            }
            s.director.remove(s.root, &path).map_err(err)?;
            s.inodes().detach(&path);
            Ok(())
        })();
        match result {
            Ok(()) => reply.ok(),
            Err(e) => reply.error(e),
        }
    }

    fn rename(
        &self,
        _req: &Request,
        parent: INodeNo,
        name: &OsStr,
        newparent: INodeNo,
        newname: &OsStr,
        flags: RenameFlags,
        reply: ReplyEmpty,
    ) {
        let s = &self.shared;
        let result = (|| {
            if flags.contains(RenameFlags::RENAME_EXCHANGE)
                || flags.contains(RenameFlags::RENAME_WHITEOUT)
            {
                return Err(Errno::from_i32(libc_errno::EINVAL));
            }
            let (from, _) = s.child(parent, name)?;
            let (to, _) = s.child(newparent, newname)?;
            let src = s.stat(&from)?.ok_or(Errno::from_i32(libc_errno::ENOENT))?;
            let same_entry = vfs_core::fold(&from) == vfs_core::fold(&to);
            if !same_entry {
                if let Some(dst) = s.stat(&to)? {
                    if flags.contains(RenameFlags::RENAME_NOREPLACE) {
                        return Err(Errno::from_i32(libc_errno::EEXIST));
                    }
                    match (src.kind == KIND_DIR, dst.kind == KIND_DIR) {
                        (true, false) => return Err(Errno::from_i32(libc_errno::ENOTDIR)),
                        (false, true) => return Err(Errno::from_i32(libc_errno::EISDIR)),
                        (true, true) if !s.is_empty_dir(&to)? => {
                            return Err(Errno::from_i32(libc_errno::ENOTEMPTY))
                        }
                        _ => {}
                    }
                }
            }
            s.director.rename(s.root, &from, &to).map_err(err)?;
            s.inodes().renamed(&from, &to);
            Ok(())
        })();
        match result {
            Ok(()) => reply.ok(),
            Err(e) => reply.error(e),
        }
    }

    fn open(&self, _req: &Request, ino: INodeNo, flags: OpenFlags, reply: ReplyOpen) {
        let s = &self.shared;
        let result = (|| {
            let path = s.path(ino)?;
            let info = s
                .director
                .open_info(s.root, &path, open_bits(flags.0) & !OPEN_EXCL)
                .map_err(err)?;
            if info.is_dir {
                let _ = s.director.close(info.fh);
                return Err(Errno::from_i32(libc_errno::EISDIR));
            }
            s.track_open(info.fh, info.size, info.mount_gen, &path);
            // Cached pages survive the next open only for content that cannot
            // change while open; a remount drops them (`FuseMount::invalidate`).
            let fopen = if info.immutable {
                FopenFlags::FOPEN_KEEP_CACHE
            } else {
                FopenFlags::empty()
            };
            Ok((info.fh, fopen))
        })();
        match result {
            Ok((fh, fopen)) => reply.opened(FileHandle(fh), fopen),
            Err(e) => reply.error(e),
        }
    }

    fn read(
        &self,
        _req: &Request,
        _ino: INodeNo,
        fh: FileHandle,
        offset: u64,
        size: u32,
        _flags: OpenFlags,
        _lock_owner: Option<LockOwner>,
        reply: ReplyData,
    ) {
        let s = &self.shared;
        let mut buf = vec![0u8; size as usize];
        let mut filled = 0usize;
        // A short read is legal for a provider anywhere; for FUSE it means
        // end of file, so fill the request unless the provider really ends.
        while filled < buf.len() {
            match s
                .director
                .read(fh.0, offset + filled as u64, &mut buf[filled..])
            {
                Ok(0) => break,
                Ok(n) => filled += n,
                Err(st) if filled == 0 => return reply.error(err(st)),
                Err(_) => break,
            }
        }
        reply.data(&buf[..filled]);
    }

    fn write(
        &self,
        _req: &Request,
        _ino: INodeNo,
        fh: FileHandle,
        offset: u64,
        data: &[u8],
        _write_flags: WriteFlags,
        _flags: OpenFlags,
        _lock_owner: Option<LockOwner>,
        reply: ReplyWrite,
    ) {
        let s = &self.shared;
        let mut done = 0usize;
        while done < data.len() {
            match s.director.write(fh.0, offset + done as u64, &data[done..]) {
                Ok(0) => return reply.error(Errno::from_i32(libc_errno::EIO)),
                Ok(n) => done += n,
                Err(st) => return reply.error(err(st)),
            }
        }
        let end = offset + done as u64;
        s.update_open(fh.0, |r| r.size = r.size.max(end));
        reply.written(done as u32);
    }

    fn flush(
        &self,
        _req: &Request,
        _ino: INodeNo,
        _fh: FileHandle,
        _lock_owner: LockOwner,
        reply: ReplyEmpty,
    ) {
        // Called on every close(2); writes are already with the provider.
        reply.ok();
    }

    fn release(
        &self,
        _req: &Request,
        _ino: INodeNo,
        fh: FileHandle,
        _flags: OpenFlags,
        _lock_owner: Option<LockOwner>,
        _flush: bool,
        reply: ReplyEmpty,
    ) {
        let _ = self.shared.director.close(fh.0);
        self.shared
            .opens
            .lock()
            .unwrap_or_else(|e| e.into_inner())
            .remove(&fh.0);
        reply.ok();
    }

    fn fsync(
        &self,
        _req: &Request,
        _ino: INodeNo,
        fh: FileHandle,
        _datasync: bool,
        reply: ReplyEmpty,
    ) {
        match self.shared.director.flush(fh.0) {
            Ok(()) | Err(ST_NOT_SUPPORTED) => reply.ok(),
            Err(st) => reply.error(err(st)),
        }
    }

    fn opendir(&self, _req: &Request, ino: INodeNo, _flags: OpenFlags, reply: ReplyOpen) {
        let s = &self.shared;
        let result = (|| {
            let path = s.path(ino)?;
            let entries = s.director.readdir(s.root, &path).map_err(err)?;
            let inodes = s.inodes();
            let parent = if ino.0 == ROOT {
                ROOT
            } else {
                let parent_path = path.rsplit_once('/').map(|(p, _)| p).unwrap_or("");
                inodes.peek(parent_path)
            };
            let mut list = Vec::with_capacity(entries.len() + 2);
            list.push(ListEntry {
                ino: ino.0,
                kind: FileType::Directory,
                name: ".".into(),
            });
            list.push(ListEntry {
                ino: parent,
                kind: FileType::Directory,
                name: "..".into(),
            });
            for e in entries {
                list.push(ListEntry {
                    ino: inodes.peek(&join(&path, &e.name)),
                    kind: if e.stat.kind == KIND_DIR {
                        FileType::Directory
                    } else {
                        FileType::RegularFile
                    },
                    name: e.name,
                });
            }
            drop(inodes);
            let id = s.next_list.fetch_add(1, Ordering::Relaxed);
            s.lists
                .lock()
                .unwrap_or_else(|e| e.into_inner())
                .insert(id, list);
            Ok(id)
        })();
        match result {
            Ok(id) => reply.opened(FileHandle(id), FopenFlags::empty()),
            Err(e) => reply.error(e),
        }
    }

    fn readdir(
        &self,
        _req: &Request,
        _ino: INodeNo,
        fh: FileHandle,
        offset: u64,
        mut reply: ReplyDirectory,
    ) {
        let lists = self.shared.lists.lock().unwrap_or_else(|e| e.into_inner());
        let Some(list) = lists.get(&fh.0) else {
            return reply.error(Errno::from_i32(libc_errno::EBADF));
        };
        for (i, e) in list.iter().enumerate().skip(offset as usize) {
            if reply.add(INodeNo(e.ino), (i + 1) as u64, e.kind, &e.name) {
                break;
            }
        }
        reply.ok();
    }

    fn releasedir(
        &self,
        _req: &Request,
        _ino: INodeNo,
        fh: FileHandle,
        _flags: OpenFlags,
        reply: ReplyEmpty,
    ) {
        self.shared
            .lists
            .lock()
            .unwrap_or_else(|e| e.into_inner())
            .remove(&fh.0);
        reply.ok();
    }

    fn fsyncdir(
        &self,
        _req: &Request,
        _ino: INodeNo,
        _fh: FileHandle,
        _datasync: bool,
        reply: ReplyEmpty,
    ) {
        reply.ok();
    }

    fn statfs(&self, _req: &Request, _ino: INodeNo, reply: ReplyStatfs) {
        match self
            .shared
            .statfs_path
            .as_ref()
            .and_then(|p| rustix::fs::statvfs(p.as_path()).ok())
        {
            Some(v) => reply.statfs(
                v.f_blocks,
                v.f_bfree,
                v.f_bavail,
                v.f_files,
                v.f_ffree,
                v.f_bsize as u32,
                255,
                v.f_frsize as u32,
            ),
            None => reply.statfs(0, 0, 0, 0, 0, 4096, 255, 4096),
        }
    }

    fn create(
        &self,
        _req: &Request,
        parent: INodeNo,
        name: &OsStr,
        _mode: u32,
        _umask: u32,
        flags: i32,
        reply: ReplyCreate,
    ) {
        let s = &self.shared;
        let result = (|| {
            let (path, name) = s.child(parent, name)?;
            let info = s
                .director
                .open_info(s.root, &path, open_bits(flags) | OPEN_WRITE | OPEN_CREATE)
                .map_err(err)?;
            match s.entry_of(parent, &name, &path) {
                Ok(attr) => {
                    s.track_open(info.fh, attr.size, info.mount_gen, &path);
                    Ok((attr, info.fh))
                }
                Err(e) => {
                    let _ = s.director.close(info.fh);
                    Err(e)
                }
            }
        })();
        match result {
            Ok((attr, fh)) => reply.created(
                &s.ttl,
                &attr,
                Generation(0),
                FileHandle(fh),
                FopenFlags::empty(),
            ),
            Err(e) => reply.error(e),
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn open_flags_translate() {
        let o = |f: OFlags| open_bits(f.bits() as i32);
        assert_eq!(o(OFlags::RDONLY), OPEN_READ);
        assert_eq!(o(OFlags::WRONLY), OPEN_READ | OPEN_WRITE);
        assert_eq!(
            o(OFlags::RDWR | OFlags::TRUNC),
            OPEN_READ | OPEN_WRITE | OPEN_TRUNC
        );
        assert_eq!(
            o(OFlags::WRONLY | OFlags::APPEND),
            OPEN_READ | OPEN_WRITE | OPEN_APPEND
        );
    }
}
