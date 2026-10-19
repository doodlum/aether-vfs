//! Bootstrap glue: the config-file entry point used by the injected DLL to attach
//! the director's client and install the hooks. The config codec is
//! `vfs_protocol::shimcfg`.
#![allow(unsafe_code)] // payload_cfg_usable VirtualQuery validation (payload path)
use crate::hook::{install, install_late, HookGuard, InstallError};
use core::ffi::c_void;
use vfs_inject::PayloadConfig;
use vfs_protocol::shimcfg::{self, ConfigError};

/// True when `p` looks like a live early-payload Config in *this* process
/// (readable page + `nt_protect` matches our ntdll). Rejects inherited parent
/// addresses in child processes. Payload-activation path only.
fn payload_cfg_usable(p: *mut PayloadConfig) -> bool {
    if p.is_null() {
        return false;
    }
    // SAFETY: VirtualQuery on an arbitrary address is defined; we only read
    // through p after the query says the page is committed.
    unsafe {
        use windows_sys::Win32::System::LibraryLoader::{GetModuleHandleA, GetProcAddress};
        use windows_sys::Win32::System::Memory::{
            VirtualQuery, MEMORY_BASIC_INFORMATION, MEM_COMMIT,
        };
        let mut mbi = core::mem::MaybeUninit::<MEMORY_BASIC_INFORMATION>::uninit();
        let n = VirtualQuery(
            p as *const c_void,
            mbi.as_mut_ptr(),
            core::mem::size_of::<MEMORY_BASIC_INFORMATION>(),
        );
        if n == 0 {
            return false;
        }
        let mbi = mbi.assume_init();
        if mbi.State != MEM_COMMIT {
            return false;
        }
        let ntdll = GetModuleHandleA(c"ntdll.dll".as_ptr().cast());
        if ntdll.is_null() {
            return false;
        }
        let expected = match GetProcAddress(ntdll, c"NtProtectVirtualMemory".as_ptr().cast()) {
            Some(f) => f as usize,
            None => return false,
        };
        (*p).nt_protect == expected
    }
}

/// The early-payload Config this process was armed with, or null. Sources, in
/// order (first usable wins): the `VFS_PAYLOAD_CFG_FILE` env (the launcher's
/// top-level process), then the per-pid file the parent's child-hook wrote.
/// Each candidate is validated — an inherited parent path/address must not be
/// trusted (it is in the wrong process's VA space). Payload path only.
fn find_payload_cfg() -> *mut PayloadConfig {
    let mut candidates: Vec<std::path::PathBuf> = Vec::new();
    if let Some(file) = vfs_env::text(vfs_env::PAYLOAD_CFG_FILE) {
        candidates.push(std::path::PathBuf::from(file));
    }
    candidates.push(crate::child::payload_cfg_path_for_pid(std::process::id()));
    candidates
        .into_iter()
        .find_map(|file| {
            std::fs::read_to_string(&file)
                .ok()
                .and_then(|s| usize::from_str_radix(s.trim(), 16).ok())
                .map(|a| a as *mut PayloadConfig)
                .filter(|p| payload_cfg_usable(*p))
        })
        .unwrap_or(core::ptr::null_mut())
}

/// Errors bootstrapping the shim from a config file.
#[derive(Debug)]
pub enum BootstrapError {
    /// The config file could not be read.
    Io,
    /// The config did not decode: truncated or malformed, or written by a host from a
    /// different build (no version header, or another version). The message names both
    /// versions; a launcher shows it, because the fix is rebuilding the pair together.
    Config(ConfigError),
    /// A director was configured (a ring was named) but the FUSE client
    /// failed to attach. Fails before any hook is installed, and before the
    /// program has run: the load fails and the process with it.
    Fuse(String),
    /// The hook could not be installed.
    Install(InstallError),
}

/// The ready-file content that reports a bootstrap failure that is not the
/// director's: [`vfs_env::READY_BOOTSTRAP_FAILED_PREFIX`] and the reason. A
/// config error carries its own message (it names both versions).
pub fn bootstrap_failed_content(e: &BootstrapError) -> String {
    let why = match e {
        BootstrapError::Config(c) => c.to_string(),
        other => format!("{other:?}"),
    };
    format!("{}{why}", vfs_env::READY_BOOTSTRAP_FAILED_PREFIX)
}

/// Read a config file, attach the director's client, and install the hooks. Returns the
/// guard keeping the hooks alive (the shim DLL leaks it).
pub fn bootstrap_from_config_path(path: &str) -> Result<HookGuard, BootstrapError> {
    let result = bootstrap_steps(path);
    crate::loadlog::note(&match &result {
        Ok(_) => "bootstrap: ready".to_string(),
        Err(e) => format!("bootstrap: FAILED {e:?}"),
    });
    result
}

/// [`bootstrap_from_config_path`]'s steps, each noted in the load log.
fn bootstrap_steps(path: &str) -> Result<HookGuard, BootstrapError> {
    let bytes = std::fs::read(path).map_err(|_| BootstrapError::Io)?;
    crate::loadlog::note("bootstrap: config read");
    // The root is the director's to answer for (the client's roots come from the environment), so
    // decoding is only the version and shape check: a config from another build fails here, loudly.
    shimcfg::decode_config(&bytes).map_err(BootstrapError::Config)?;
    crate::loadlog::note("bootstrap: config ok");
    // Attach to the parent director's FUSE ring. A process that names no ring at all is refused
    // like a ring that failed to attach: a shim with no director would run the program
    // un-virtualised while appearing to work. The client's roots
    // (`director::roots_from_env`) are the only notion of "under a root" the hooks have.
    match crate::director::try_init_from_env() {
        Ok(()) => crate::loadlog::note("bootstrap: director attached"),
        Err(crate::director::FuseInitError::NotConfigured) => {
            return Err(BootstrapError::Fuse(
                "no VFS_RING_SECTION configured: standalone (no-director) shim launches are \
                 retired — a director must be attached"
                    .to_string(),
            ));
        }
        Err(crate::director::FuseInitError::ConnectFailed(msg)) => {
            return Err(BootstrapError::Fuse(msg));
        }
    }
    // The program was started from its root's backing directory: now that the
    // loader is done with the real path, tell it where it is.
    // SAFETY: no thread of the program has run yet (see `rename_image`).
    unsafe { crate::whereami::rename_image() };
    // On the early-payload-activation path (`VFS_PAYLOAD_ACTIVATION`), the
    // injector armed this process through `vfs_payload.dll`, which already owns
    // the four path/attr stubs. Hand those off with `install_late`; otherwise
    // (and when no payload cfg is found) do today's full `install`.
    let guard = if vfs_env::opt_in(vfs_env::PAYLOAD_ACTIVATION) {
        let cfg = find_payload_cfg();
        if cfg.is_null() {
            install().map_err(BootstrapError::Install)?
        } else {
            crate::loadlog::note("bootstrap: install_late (early payload)");
            // SAFETY: `cfg` was parsed from the address the injector published
            // for this process and validated by `payload_cfg_usable`.
            unsafe { install_late(cfg) }.map_err(BootstrapError::Install)?
        }
    } else {
        install().map_err(BootstrapError::Install)?
    };
    crate::loadlog::note("bootstrap: hooks installed");
    // Tell any spawning parent (that force-suspended us) our hooks are live.
    crate::child::signal_ready();
    Ok(guard)
}

#[cfg(test)]
mod tests {
    use super::*;

    // Use `matches!` on the whole Result rather than `.unwrap_err()`: the latter
    // needs the Ok type `HookGuard: Debug`, which it deliberately is not (it owns
    // a RawDetour; a guard type carries no useful Debug). Same convention the rest
    // of the workspace uses for resource/guard types.
    #[test]
    fn bootstrap_missing_file_is_io_error() {
        assert!(matches!(
            bootstrap_from_config_path(r"C:\nope\does-not-exist.cfg"),
            Err(BootstrapError::Io)
        ));
    }

    #[test]
    fn payload_cfg_usable_rejects_null() {
        assert!(!payload_cfg_usable(core::ptr::null_mut()));
    }

    #[test]
    fn payload_cfg_usable_rejects_unmapped_garbage_address() {
        // A dangling (never-committed) pointer: VirtualQuery must report it as
        // such (MEM_FREE), so this must be rejected before ever dereferencing it.
        assert!(!payload_cfg_usable(
            std::ptr::dangling_mut::<PayloadConfig>()
        ));
    }

    #[test]
    fn bootstrap_garbage_config_is_bad_config() {
        let dir = std::env::temp_dir();
        let path = dir.join(format!("vfs-shim-badcfg-{}.bin", std::process::id()));
        std::fs::write(&path, [0u8, 1]).unwrap(); // too short for the header
        assert!(matches!(
            bootstrap_from_config_path(path.to_str().unwrap()),
            Err(BootstrapError::Config(_))
        ));
        let _ = std::fs::remove_file(&path);
    }

    /// A config from another build is refused at bootstrap, before the ring is touched, with an
    /// error that names the versions.
    #[test]
    fn bootstrap_refuses_a_config_from_another_build() {
        let dir = std::env::temp_dir();
        let path = dir.join(format!("vfs-shim-oldcfg-{}.bin", std::process::id()));
        // A version-1 config: root, overlay, "VFS1", n_static, snapshot.
        let mut v1 = Vec::new();
        v1.extend_from_slice(&1u32.to_le_bytes());
        v1.push(b'R');
        v1.extend_from_slice(&0u32.to_le_bytes());
        v1.extend_from_slice(b"VFS1");
        v1.extend_from_slice(&0u32.to_le_bytes());
        std::fs::write(&path, &v1).unwrap();
        let r = bootstrap_from_config_path(path.to_str().unwrap());
        assert!(matches!(
            r,
            Err(BootstrapError::Config(ConfigError::Unversioned))
        ));
        let mut v3 = shimcfg::encode_config("R");
        v3[4..8].copy_from_slice(&3u32.to_le_bytes());
        std::fs::write(&path, &v3).unwrap();
        let r = bootstrap_from_config_path(path.to_str().unwrap());
        assert!(matches!(
            r,
            Err(BootstrapError::Config(ConfigError::Version {
                found: 3,
                ..
            }))
        ));
        let _ = std::fs::remove_file(&path);
    }

    /// What the launcher reads in the ready file for a config refusal: the bootstrap spelling
    /// (not the director's), carrying the message that names both versions.
    #[test]
    fn a_config_refusal_is_spelled_for_the_ready_file() {
        let e = BootstrapError::Config(ConfigError::Version {
            found: 3,
            expected: 2,
        });
        let content = bootstrap_failed_content(&e);
        assert!(
            content.starts_with(vfs_env::READY_BOOTSTRAP_FAILED_PREFIX),
            "{content}"
        );
        assert!(
            content.contains("version 3") && content.contains("version 2"),
            "{content}"
        );
        assert!(!content.starts_with(vfs_env::READY_FUSE_FAILED_PREFIX));
        let io = bootstrap_failed_content(&BootstrapError::Io);
        assert_eq!(io, format!("{}Io", vfs_env::READY_BOOTSTRAP_FAILED_PREFIX));
    }
}
