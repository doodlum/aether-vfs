//! The Linux delivery of a director root: a FUSE mount **at the root's real
//! location** (a game folder), serving whatever the director composes for it.
//!
//! On Windows a shim injected into each program redirects its file I/O to the
//! director. On Linux the kernel does the redirecting instead: a FUSE mount over
//! the folder is seen by every process alike — a native Linux game, a Windows
//! game under Steam's own Proton in its own prefix, Steam's Play button and the
//! host's own reads — with nothing injected anywhere.
//!
//! The pieces:
//! * [`BaseDir`] and [`BaseDirProvider`] — the real folder **underneath** the
//!   mount. Mounting over a folder hides it from every path lookup, the
//!   daemon's own included, so its content is read through a directory file
//!   descriptor opened *before* the mount (`openat2` relative to it, confined
//!   beneath it). Wrap it in a `ReadOnlyProvider` as a root's base layer.
//! * [`FuseMount`] — mount a root, [`FuseMount::invalidate`] after its content
//!   changed (a `Director::mount` replacing its provider), and unmount, lazily
//!   if something still holds a file open.
//! * [`mount_state`] / [`recover_stale_mount`] — a mount left behind by a
//!   process that died reads `ENOTCONN`; the next start lazily unmounts it.
//!
//! **What the mount must never read through itself.** The daemon's threads
//! serve every request on the mount, so a provider that reads a path *under*
//! the mount point (a mapped file whose source lies inside the game folder, a
//! write folder inside it) waits on itself. A host keeps every source and the
//! write folder outside the mounted location, and reads the folder's own
//! content only through a [`BaseDir`].
//!
//! Builds to an empty crate on every other target.

#![deny(unsafe_code)]

#[cfg(target_os = "linux")]
mod base_dir;
#[cfg(target_os = "linux")]
mod errno;
#[cfg(target_os = "linux")]
mod fs;
#[cfg(target_os = "linux")]
mod inodes;
#[cfg(target_os = "linux")]
mod mount;

#[cfg(target_os = "linux")]
pub use base_dir::{BaseDir, BaseDirProvider};
#[cfg(target_os = "linux")]
pub use errno::errno_of;
#[cfg(target_os = "linux")]
pub use mount::{
    mount_state, recover_stale_mount, FuseMount, MountOpts, MountState, Unmounted, FS_NAME,
};
