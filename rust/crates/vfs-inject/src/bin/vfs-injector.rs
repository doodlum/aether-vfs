//! Generic injector: a command-line wrapper over `run_target_with_shim`. The
//! host (the director, or a test) sets the ring env (VFS_RING_SECTION etc.) and
//! spawns this bin; this bin starts the target with the shim (import-activated
//! or injected),
//! which inherits the env and connects its FuseClient back to the host's ring.
//!
//! Usage:
//!   vfs-injector <target_exe> <shim_dll> <config_file> <ready_file> [-- target_args...]
//!
//! Environment: `VFS_READY_TIMEOUT_SECS` (default 180) bounds the wait for the
//! shim's ready report; `VFS_INJECT_CWD` is the target's working directory
//! (default: this process's); `VFS_INJECT_STEAM_HELPER` is the command line
//! of Proton's Steam helper, run before the target so the target's Steam API
//! finds a running Steam client, or `off` to only clear a stale helper pid
//! (default: neither). What it did is written to `<ready_file>.steam-helper`
//! (`vfs_env::STEAM_HELPER_REPORT_SUFFIX`) and stderr; a helper that fails is
//! stopped and the target runs without it.
//!
//! On an injection failure it exits 3 after
//! writing one line — `target-exited:<code>`, `ready-timeout:<secs>` or
//! `inject:<error>` — to `<ready_file>.injector-error`
//! (`vfs_env::INJECTOR_ERROR_SUFFIX`), so a caller that only sees the exit
//! code can still say why.
use std::time::Duration;
use vfs_inject::{
    check_helper_command, clear_active_process_pid, parse_injector_args, run_target_with_shim,
    running_under_wine, start_steam_helper, InjectError, RunConfig, ACTIVE_PROCESS_KEY,
};

/// How long the Steam helper may take to publish itself before the target is
/// started without it. It takes well under a second; the bound is for a
/// prefix in which it never will.
const STEAM_HELPER_TIMEOUT: Duration = Duration::from_secs(15);

fn main() {
    let a: Vec<String> = std::env::args().collect();
    if let Some(parsed) = vfs_inject::parse_load_library_args(&a) {
        match parsed {
            Ok(args) => load_library(args),
            Err(usage) => {
                eprintln!("{usage}");
                std::process::exit(2);
            }
        }
    }
    let parsed = match parse_injector_args(&a) {
        Ok(parsed) => parsed,
        Err(usage) => {
            eprintln!("{usage}");
            std::process::exit(2);
        }
    };
    let vfs_inject::InjectorArgs {
        target,
        shim_dll: dll,
        config,
        ready,
        target_args: args,
    } = parsed;

    let ready_timeout = Duration::from_secs(vfs_env::ready_timeout_secs());
    let current_dir = vfs_env::text(vfs_env::INJECT_CWD).filter(|d| !d.is_empty());
    let report = format!("{ready}{}", vfs_env::INJECTOR_ERROR_SUFFIX);
    let _ = std::fs::remove_file(&report);
    let _ = std::fs::remove_file(format!("{ready}{}", vfs_env::STEAM_HELPER_REPORT_SUFFIX));

    if let Some(request) = vfs_env::text(vfs_env::INJECT_STEAM_HELPER) {
        let line = steam_helper_step(&request);
        eprintln!("[vfs-injector] steam helper: {line}");
        let path = format!("{ready}{}", vfs_env::STEAM_HELPER_REPORT_SUFFIX);
        if let Err(e) = std::fs::write(&path, &line) {
            eprintln!("[vfs-injector] writing {path}: {e}");
        }
    }

    eprintln!("[vfs-injector] target={target} shim={dll} cwd={current_dir:?}");
    let exit = run_target_with_shim(RunConfig {
        target_exe: target,
        args,
        current_dir,
        dll_path: dll,
        config_path: config,
        ready_path: ready,
        ready_timeout,
        detach: false,
        env_set: Vec::new(),
        env_remove: Vec::new(),
        virtual_image: None,
        virtual_cwd: None,
        force_injection: false,
    })
    .unwrap_or_else(|e| {
        eprintln!("[vfs-injector] inject error: {e:?}");
        let line = match &e {
            InjectError::TargetExited(code) => {
                format!("{}{code:#x}", vfs_env::INJECTOR_TARGET_EXITED_PREFIX)
            }
            InjectError::Timeout => format!(
                "{}{}",
                vfs_env::INJECTOR_READY_TIMEOUT_PREFIX,
                ready_timeout.as_secs()
            ),
            InjectError::FuseInit(msg) => {
                format!("{}{msg}", vfs_env::INJECTOR_FUSE_FAILED_PREFIX)
            }
            InjectError::Bootstrap(msg) => {
                format!("{}{msg}", vfs_env::INJECTOR_BOOTSTRAP_FAILED_PREFIX)
            }
            other => format!("{}{other:?}", vfs_env::INJECTOR_FAILED_PREFIX),
        };
        let _ = std::fs::write(&report, line);
        std::process::exit(3);
    });
    eprintln!("[vfs-injector] target exited {exit}");
    std::process::exit(exit);
}

/// `--load-library`: `LoadLibraryW` the DLL into the suspended process `pid` on
/// a remote thread, print the one-line report, and exit 0 if the thread ran to
/// completion (3 otherwise). The 32-bit build is how a 64-bit launch injects a
/// WOW64 guest: this process's kernel32 is the guest's.
#[allow(unsafe_code)]
fn load_library(args: vfs_inject::LoadLibraryArgs) -> ! {
    use windows_sys::Win32::Foundation::CloseHandle;
    use windows_sys::Win32::System::Threading::{
        OpenProcess, PROCESS_CREATE_THREAD, PROCESS_QUERY_INFORMATION, PROCESS_SYNCHRONIZE,
        PROCESS_VM_OPERATION, PROCESS_VM_READ, PROCESS_VM_WRITE,
    };
    let access = PROCESS_CREATE_THREAD
        | PROCESS_QUERY_INFORMATION
        | PROCESS_VM_OPERATION
        | PROCESS_VM_READ
        | PROCESS_VM_WRITE
        | PROCESS_SYNCHRONIZE;
    // SAFETY: opening a process by id; the handle is closed below.
    let process = unsafe { OpenProcess(access, 0, args.pid) };
    let result = if process.is_null() {
        Err(InjectError::RemoteThread)
    } else {
        // SAFETY: a live handle with the rights `inject_dll` needs.
        let r = unsafe { vfs_inject::inject_dll_exit_code(process, &args.dll, args.timeout_ms) };
        // SAFETY: the handle opened above.
        unsafe { CloseHandle(process) };
        r
    };
    println!("{}", vfs_inject::load_library_report(&result));
    std::process::exit(if result.is_ok() { 0 } else { 3 });
}

/// Acts on `VFS_INJECT_STEAM_HELPER` and returns the report line for
/// `<ready file>.steam-helper`.
fn steam_helper_step(request: &str) -> String {
    use vfs_env::{
        STEAM_HELPER_CLEARED as CLEARED, STEAM_HELPER_DISABLED_PREFIX as DISABLED,
        STEAM_HELPER_FAILED_PREFIX as FAILED, STEAM_HELPER_STARTED_PREFIX as STARTED,
    };
    // Under native Windows the key is the real Steam client's.
    if !running_under_wine() {
        return format!("{DISABLED}not running under Wine");
    }
    clear_active_process_pid(ACTIVE_PROCESS_KEY);
    if request.trim() == vfs_env::INJECT_STEAM_HELPER_OFF {
        return CLEARED.to_string();
    }
    if let Err(e) = check_helper_command(request) {
        return format!("{DISABLED}{e}");
    }
    // Without it the helper runs its program and sets nothing up.
    if std::env::var_os("SteamGameId").is_none() {
        return format!("{DISABLED}SteamGameId is not set");
    }
    match start_steam_helper(request, ACTIVE_PROCESS_KEY, STEAM_HELPER_TIMEOUT) {
        Ok(h) => format!("{STARTED}{}:{}", h.pid, h.waited.as_millis()),
        Err(e) => format!("{FAILED}{e}"),
    }
}
