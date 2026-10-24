//! `fusermount3` for a sandboxed process (a Flatpak), run on the host through
//! `flatpak-spawn --host` as the sandbox's `$FUSERMOUNT_PATH`.
//!
//! Ubuntu's AppArmor profile for `fusermount3` refuses to inherit a socket
//! whose other end is inside a sandbox (`file_inherit`, peer `bwrap`), so the
//! FUSE descriptor cannot come back to the sandbox directly. The relay gives
//! `fusermount3` a socket of its own and passes the descriptor on over the one
//! the sandbox forwarded (`_FUSE_COMMFD`). Without `_FUSE_COMMFD` (an unmount)
//! it only runs `fusermount3`. Built static, so it runs on any host.

// The inherited socket's number becomes an owned descriptor.
#![allow(unsafe_code)]

#[cfg(target_os = "linux")]
fn main() -> std::process::ExitCode {
    use std::ffi::{OsStr, OsString};
    use std::os::fd::{FromRawFd, OwnedFd, RawFd};
    use std::process::ExitCode;

    // The relay is what `FUSERMOUNT_PATH` names: it runs the real helper.
    std::env::remove_var("FUSERMOUNT_PATH");
    let args: Vec<OsString> = std::env::args_os().skip(1).collect();
    let args: Vec<&OsStr> = args.iter().map(OsString::as_os_str).collect();
    let comm = std::env::var("_FUSE_COMMFD")
        .ok()
        .and_then(|value| value.parse::<RawFd>().ok());
    let result = match comm {
        None => vfs_fuse::fusermount(&args),
        Some(comm) => {
            // SAFETY: the sandbox forwarded this descriptor for this process to
            // pass the FUSE descriptor over; nothing else here owns it.
            let comm = unsafe { OwnedFd::from_raw_fd(comm) };
            vfs_fuse::fusermount_receive(&args).and_then(|fd| send_fd(&comm, &fd))
        }
    };
    match result {
        Ok(()) => ExitCode::SUCCESS,
        Err(e) => {
            eprintln!("fusermount-relay: {e}");
            ExitCode::FAILURE
        }
    }
}

/// Send `fd` over `socket`, as `fusermount3` itself does.
#[cfg(target_os = "linux")]
fn send_fd(socket: &std::os::fd::OwnedFd, fd: &std::os::fd::OwnedFd) -> std::io::Result<()> {
    use std::io::IoSlice;
    use std::mem::MaybeUninit;
    use std::os::fd::AsFd;

    use rustix::net::{sendmsg, SendAncillaryBuffer, SendAncillaryMessage, SendFlags};

    let fds = [fd.as_fd()];
    let mut space = [MaybeUninit::uninit(); rustix::cmsg_space!(ScmRights(1))];
    let mut control = SendAncillaryBuffer::new(&mut space);
    control.push(SendAncillaryMessage::ScmRights(&fds));
    sendmsg(socket, &[IoSlice::new(&[0])], &mut control, SendFlags::empty())?;
    Ok(())
}

#[cfg(not(target_os = "linux"))]
fn main() {}
