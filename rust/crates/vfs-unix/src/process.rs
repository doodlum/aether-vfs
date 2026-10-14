//! Questions about processes that std cannot answer: who this process runs
//! as, and whether another one is alive and what it is called.
//!
//! Each was answered elsewhere by reading `/proc`, which Linux has and macOS
//! does not; asking the kernel directly works on both.

/// The effective uid this process runs as.
pub fn effective_uid() -> u32 {
    // SAFETY: `geteuid` takes nothing, cannot fail, and touches no memory of
    // ours.
    #[allow(unsafe_code)]
    unsafe {
        libc::geteuid()
    }
}

/// Whether a process `pid` exists — running, stopped or a zombie not yet
/// reaped, as `/proc/<pid>` would say. A process of another user's counts:
/// it exists, this process is only not allowed to signal it.
pub fn process_alive(pid: u32) -> bool {
    // 0 is "my process group" and anything above `i32::MAX` is a negative
    // pid, "that process group": neither names one process.
    let Ok(pid) = libc::pid_t::try_from(pid) else {
        return false;
    };
    if pid == 0 {
        return false;
    }
    // SAFETY: signal 0 is the documented existence check: it delivers
    // nothing and touches no memory of ours.
    #[allow(unsafe_code)]
    let r = unsafe { libc::kill(pid, 0) };
    r == 0 || std::io::Error::last_os_error().raw_os_error() == Some(libc::EPERM)
}

/// The short command name of process `pid` — what `/proc/<pid>/comm` holds on
/// Linux, and `proc_name` on macOS — or `None` when there is no such process.
#[cfg(target_os = "linux")]
pub fn process_name(pid: u32) -> Option<String> {
    let comm = std::fs::read_to_string(format!("/proc/{pid}/comm")).ok()?;
    Some(comm.trim_end_matches('\n').to_owned())
}

/// The short command name of process `pid` — what `/proc/<pid>/comm` holds on
/// Linux, and `proc_name` on macOS — or `None` when there is no such process.
#[cfg(target_os = "macos")]
pub fn process_name(pid: u32) -> Option<String> {
    let pid = libc::c_int::try_from(pid).ok().filter(|&p| p > 0)?;
    // `proc_name` fills at most 2 * MAXCOMLEN (32) bytes and NUL-terminates.
    let mut buf = [0u8; 64];
    // SAFETY: `buf` is writable for the length passed, and outlives the call.
    #[allow(unsafe_code)]
    let n = unsafe { libc::proc_name(pid, buf.as_mut_ptr().cast(), buf.len() as u32) };
    let n = usize::try_from(n).ok().filter(|&n| n > 0)?;
    Some(String::from_utf8_lossy(&buf[..n]).into_owned())
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn this_process_is_alive_and_a_reaped_child_is_not() {
        assert!(process_alive(std::process::id()));
        let mut child = std::process::Command::new("true").spawn().unwrap();
        let pid = child.id();
        child.wait().unwrap();
        assert!(!process_alive(pid));
        assert!(!process_alive(0), "0 is a process group, not a process");
        assert!(
            !process_alive(u32::MAX),
            "a negative pid is a process group"
        );
    }

    #[test]
    fn the_effective_uid_owns_what_this_process_creates() {
        use std::os::unix::fs::MetadataExt;
        let dir = vfs_testkit::scratch_dir("vfs-unix-euid");
        assert_eq!(std::fs::metadata(&*dir).unwrap().uid(), effective_uid());
    }

    #[cfg(any(target_os = "linux", target_os = "macos"))]
    #[test]
    fn a_process_is_named_after_its_executable() {
        let mut child = std::process::Command::new("sleep")
            .arg("30")
            .spawn()
            .unwrap();
        let name = process_name(child.id());
        child.kill().unwrap();
        child.wait().unwrap();
        assert_eq!(name.as_deref(), Some("sleep"));
        assert_eq!(process_name(child.id()), None);
    }
}
