//! The load log: one line per step of each shim's start, in the session's
//! state directory.
//!
//! A shim that fails to come up in a process nobody is watching — a child a
//! launcher starts, injected rather than import-activated — otherwise leaves
//! only its spawner's verdict ("ready-timeout"). So every process's shim says
//! here what it did (`DllMain` entered, each bootstrap step, the outcome), and
//! a spawner says what injecting a child returned. Appended to
//! `<state>/shim-load.log`, the state directory being the shim config's
//! (`VFS_SHIM_CONFIG`); nothing when no config is named. Never `%TEMP%`: that
//! is under the user's profile, which a host may serve purely virtually.

use std::io::Write;

/// The log's path, beside the shim's config.
pub fn path() -> Option<std::path::PathBuf> {
    let config = vfs_env::text(vfs_env::SHIM_CONFIG)?;
    Some(
        std::path::Path::new(&config)
            .parent()?
            .join("shim-load.log"),
    )
}

/// Append `msg` as one line: milliseconds since the epoch, this process's id,
/// the message. Best-effort: a log that cannot be written is skipped.
pub fn note(msg: &str) {
    let Some(path) = path() else {
        return;
    };
    // The shim's own I/O: never through its hooks.
    let _io = crate::hook::ShimIoGuard::enter();
    let ms = std::time::SystemTime::now()
        .duration_since(std::time::UNIX_EPOCH)
        .map(|d| d.as_millis())
        .unwrap_or(0);
    let line = format!("{ms} pid={} {msg}\n", std::process::id());
    if let Ok(mut f) = std::fs::OpenOptions::new()
        .create(true)
        .append(true)
        .open(path)
    {
        let _ = f.write_all(line.as_bytes());
    }
}
