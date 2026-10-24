//! The pure NT byte layouts and decisions the shim's hooks apply, in a crate that builds and
//! tests on any host. The hooks keep the raw-pointer plumbing (`slice::from_raw_parts_mut`, the
//! `IO_STATUS_BLOCK` write); everything that is arithmetic on bytes lives here.
#![forbid(unsafe_code)]

// Two layouts here hold a pointer, `objname` (`UNICODE_STRING::Buffer`) and `rename`
// (`RootDirectory`); each has a `_for` form taking the pointer width, so both the 64-bit and the
// 32-bit (WOW64 guest) layouts are tested on any host, and a plain form for this target. Every
// other layout is pointer-free: its `LARGE_INTEGER`s are 8-aligned on both targets.
#[cfg(not(any(target_pointer_width = "64", target_pointer_width = "32")))]
compile_error!("vfs-ntlayout encodes the 64-bit and 32-bit NT layouts only");

mod dirinfo;
mod disposition;
mod info;
mod objname;
mod rename;

pub use dirinfo::*;
pub use disposition::*;
pub use info::*;
pub use objname::*;
pub use rename::*;
