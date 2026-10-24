//! Director statuses as the errno a FUSE reply carries.

use vfs_provider::{
    ST_BAD_FH, ST_BAD_REQUEST, ST_CROSS_ROOT, ST_EXISTS, ST_IO_ERROR, ST_IS_DIR,
    ST_NOT_A_DIRECTORY, ST_NOT_FOUND, ST_NOT_SUPPORTED, ST_NO_SPACE, ST_READ_ONLY,
};

/// The errno for a director status. `ST_NOT_SUPPORTED` is `EOPNOTSUPP`, never
/// `ENOSYS`: the kernel remembers an `ENOSYS` for some operations (create,
/// fsync, flush) and stops asking for the life of the mount.
pub fn errno_of(status: i32) -> i32 {
    match status {
        ST_NOT_FOUND => libc_errno::ENOENT,
        ST_NOT_A_DIRECTORY => libc_errno::ENOTDIR,
        ST_BAD_REQUEST => libc_errno::EINVAL,
        ST_IO_ERROR => libc_errno::EIO,
        ST_IS_DIR => libc_errno::EISDIR,
        ST_BAD_FH => libc_errno::EBADF,
        ST_NO_SPACE => libc_errno::ENOSPC,
        ST_NOT_SUPPORTED => libc_errno::EOPNOTSUPP,
        ST_READ_ONLY => libc_errno::EROFS,
        ST_EXISTS => libc_errno::EEXIST,
        ST_CROSS_ROOT => libc_errno::EXDEV,
        _ => libc_errno::EIO,
    }
}

/// The errno values, from `rustix` so this crate names no `libc`.
pub(crate) mod libc_errno {
    use rustix::io::Errno;
    pub const ENOENT: i32 = Errno::NOENT.raw_os_error();
    pub const ENOTDIR: i32 = Errno::NOTDIR.raw_os_error();
    pub const EINVAL: i32 = Errno::INVAL.raw_os_error();
    pub const EIO: i32 = Errno::IO.raw_os_error();
    pub const EISDIR: i32 = Errno::ISDIR.raw_os_error();
    pub const EBADF: i32 = Errno::BADF.raw_os_error();
    pub const ENOSPC: i32 = Errno::NOSPC.raw_os_error();
    pub const EOPNOTSUPP: i32 = Errno::OPNOTSUPP.raw_os_error();
    pub const EROFS: i32 = Errno::ROFS.raw_os_error();
    pub const EEXIST: i32 = Errno::EXIST.raw_os_error();
    pub const EXDEV: i32 = Errno::XDEV.raw_os_error();
    pub const ENOTEMPTY: i32 = Errno::NOTEMPTY.raw_os_error();
    pub const EPERM: i32 = Errno::PERM.raw_os_error();
    pub const ENOTCONN: i32 = Errno::NOTCONN.raw_os_error();
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn statuses_map_to_their_errno() {
        assert_eq!(errno_of(ST_NOT_FOUND), 2);
        assert_eq!(errno_of(ST_READ_ONLY), 30);
        assert_eq!(errno_of(ST_EXISTS), 17);
        assert_eq!(errno_of(ST_NOT_SUPPORTED), 95);
        assert_eq!(errno_of(-999), 5);
    }
}
