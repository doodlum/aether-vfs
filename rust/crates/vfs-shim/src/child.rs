//! Child-process propagation: inject the shim into force-suspended children,
//! readiness events, self-DLL path discovery.
#![allow(unsafe_code)]

use core::ffi::c_void;
use std::path::{Path, PathBuf};
use std::time::{Duration, Instant};

use windows_sys::Win32::Foundation::{CloseHandle, HANDLE, INVALID_HANDLE_VALUE};
use windows_sys::Win32::System::Diagnostics::Debug::{ReadProcessMemory, WriteProcessMemory};
use windows_sys::Win32::System::LibraryLoader::{
    GetModuleFileNameW, GetModuleHandleExW, GetModuleHandleW, GetProcAddress,
    GET_MODULE_HANDLE_EX_FLAG_FROM_ADDRESS, GET_MODULE_HANDLE_EX_FLAG_UNCHANGED_REFCOUNT,
};
use windows_sys::Win32::System::Memory::{VirtualAllocEx, MEM_COMMIT, MEM_RESERVE, PAGE_READWRITE};
use windows_sys::Win32::System::Threading::{
    CreateEventW, CreateRemoteThread, GetCurrentProcessId, GetExitCodeThread, IsWow64Process2,
    ResumeThread, SetEvent, SuspendThread, WaitForMultipleObjects, WaitForSingleObject,
    LPTHREAD_START_ROUTINE,
};

use vfs_inject::{arm_preinit_payload_ex, PreinitRedirect};

fn wide(s: &str) -> Vec<u16> {
    s.encode_utf16().chain(std::iter::once(0)).collect()
}

/// Whether the early-payload-activation path should engage for children:
/// opt-in through `VFS_PAYLOAD_ACTIVATION`, and never under Wine.
fn payload_active() -> bool {
    vfs_env::opt_in(vfs_env::PAYLOAD_ACTIVATION) && !vfs_inject::running_under_wine()
}

/// Per-PID file the child bootstrap reads for the early Config address (hex).
/// The parent writes it after arming, so the child need not rely on inherited
/// env addresses (which are in the parent's VA space). Payload path only.
pub(crate) fn payload_cfg_path_for_pid(pid: u32) -> PathBuf {
    std::env::temp_dir().join(format!("vfs_payload_cfg_{pid}.txt"))
}

/// The readiness event name for a given process id.
fn ready_event_name(pid: u32) -> Vec<u16> {
    wide(&format!(r"Local\vfs_shim_ready_{pid}"))
}

/// The event a child's shim sets when its bootstrap failed.
fn failed_event_name(pid: u32) -> Vec<u16> {
    wide(&format!(r"Local\vfs_shim_failed_{pid}"))
}

/// Why injecting into a child failed. Every one of these ends with the child
/// killed and its `CreateProcess` call failing (see `hook::process`).
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub(crate) enum ChildInjectError {
    /// The child is a 32-bit (WOW64) image; the shim and payload are 64-bit.
    Child32Bit,
    /// This shim's own DLL path is unknown, so there is nothing to inject.
    NoShimDll,
    /// The child exited before it reported ready.
    ChildExited,
    /// The remote `LoadLibrary` of the shim could not be started or did not finish.
    InjectDll,
    /// The child's shim bootstrap failed and said so.
    BootstrapFailed,
    /// The child could not be told its virtual working directory (see
    /// `hook::child_start`).
    VirtualStart,
    /// The child's shim never reported ready within the ready timeout.
    ReadyTimeout,
    // ── payload-activation path only (VFS_PAYLOAD_ACTIVATION) ──
    /// `vfs_payload.dll` could not be found beside the shim.
    NoPayload,
    /// The early payload could not be armed in the child.
    Arm,
    /// The per-pid payload-config file could not be written.
    CfgFile,
    /// The child's primary thread could not be resumed to run the stub.
    Resume,
    /// The early payload never reported installed.
    SentinelTimeout,
    /// The spin gate could not be released.
    Release,
}

impl ChildInjectError {
    /// A short stable spelling, the key of the refused-child counter.
    pub(crate) fn label(self) -> &'static str {
        match self {
            Self::Child32Bit => "child-32bit",
            Self::NoShimDll => "no-shim-dll",
            Self::ChildExited => "child-exited",
            Self::InjectDll => "inject-dll",
            Self::BootstrapFailed => "bootstrap-failed",
            Self::ReadyTimeout => "ready-timeout",
            Self::VirtualStart => "virtual-start",
            Self::NoPayload => "no-payload",
            Self::Arm => "arm-failed",
            Self::CfgFile => "cfg-file",
            Self::Resume => "resume-failed",
            Self::SentinelTimeout => "sentinel-timeout",
            Self::Release => "release-failed",
        }
    }
}

/// How long a spawning process waits for a child's shim: the same ready timeout
/// the top-level launch used. `run_target_with_shim` publishes it in
/// `VFS_READY_TIMEOUT_SECS`, which the child inherits; without it, the shared
/// default.
pub(crate) fn child_ready_timeout_ms() -> u32 {
    u32::try_from(vfs_env::ready_timeout_secs().saturating_mul(1000)).unwrap_or(u32::MAX - 1)
}

/// Inject into a force-suspended child, the way the launcher does: give its
/// primary thread the shim's stack, then `LoadLibrary` the shim on a remote
/// thread. That thread runs the child's process initialisation and then the
/// shim's `DllMain`, which bootstraps before it returns. The child's primary
/// thread is never resumed here, so a caller that asked for a suspended child
/// gets it still suspended, now virtualised.
///
/// **Fails closed.** On any `Err` the caller must kill the child: there is no
/// fallback that runs it without the shim.
pub(crate) fn inject_child(
    process: HANDLE,
    thread: HANDLE,
    pid: u32,
    full_shim_dll: Option<&str>,
    timeout_ms: u32,
    // The caller asked for a suspended child. The in-memory import patch needs
    // the primary thread to run so the loader loads the shim, so it is used
    // only when the child is not meant to stay suspended; otherwise the shim
    // goes in on a remote thread, which leaves the primary suspended.
    caller_suspended: bool,
) -> Result<(), ChildInjectError> {
    let full_shim_dll = full_shim_dll.ok_or(ChildInjectError::NoShimDll)?;
    if is_32bit(process) {
        return Err(ChildInjectError::Child32Bit);
    }

    // Early-payload-activation path: arm the payload, hand off, release. The
    // per-pid config file is removed on every path; a caller-suspended child is
    // left suspended for the caller once its shim is up.
    if payload_active() {
        let cfg_path = payload_cfg_path_for_pid(pid);
        let r = inject_child_dual_layer(process, thread, pid, full_shim_dll, timeout_ms, &cfg_path);
        let _ = std::fs::remove_file(&cfg_path);
        if r.is_ok() && caller_suspended {
            re_suspend(thread);
        }
        return r;
    }

    // SAFETY: `process`/`thread` come from our own `CreateProcessInternalW`
    // hook, which forced CREATE_SUSPENDED, so the child has not run and we hold
    // the rights these calls need.
    unsafe {
        // Best-effort, as for the launch: a failure leaves the stock stack.
        let _ = vfs_inject::expand_primary_stack(process, thread, vfs_inject::PRIMARY_STACK_BYTES);
        // Prefer the in-memory import patch: the loader loads the shim before
        // the child's own static imports initialise (those read the game's
        // files, which are in the VFS). Remote injection runs them first, on
        // the injected thread, which is what refused SkyrimSE.exe — its
        // imports fault before the shim is up. Fall back to injection only
        // when the patch is refused, and record which path.
        match vfs_inject::patch_imports_in_memory(process) {
            Ok(()) => {
                if caller_suspended {
                    // The caller (SKSE) created the child suspended and will
                    // resume it itself. The shim is first in the import order,
                    // so when it is resumed the loader loads and bootstraps the
                    // shim before any game code — and a failed bootstrap returns
                    // FALSE, which fails process start (the loader's own
                    // fail-closed, the same guarantee file import-patching
                    // relies on). We do not resume, and cannot wait for a
                    // signal that only comes on the caller's later resume.
                    crate::loadlog::note(&format!(
                        "child {pid}: activation in-memory import patch (left suspended for the caller)"
                    ));
                    return Ok(());
                }
                crate::loadlog::note(&format!("child {pid}: activation in-memory import patch"));
                // Not caller-suspended: the shim runs as the loader processes
                // imports, so the primary thread must run; the caller does not
                // resume an unsuspended child again.
                vfs_inject::resume_primary_thread(thread);
                let wait = wait_ready(process, pid, timeout_ms);
                crate::loadlog::note(&format!("child {pid}: ready wait {wait:?}"));
                return match wait {
                    ReadyWait::Ready => Ok(()),
                    ReadyWait::BootstrapFailed => Err(ChildInjectError::BootstrapFailed),
                    ReadyWait::Exited => Err(ChildInjectError::ChildExited),
                    ReadyWait::TimedOut => Err(ChildInjectError::ReadyTimeout),
                };
            }
            Err(e) => crate::loadlog::note(&format!(
                "child {pid}: activation remote injection (in-memory import patch refused: {e:?})"
            )),
        }
        match vfs_inject::inject_dll_exit_code(process, full_shim_dll, timeout_ms) {
            Ok(code) => crate::loadlog::note(&format!(
                "child {pid}: remote LoadLibrary({full_shim_dll}) returned, thread exit {code:#x}"
            )),
            Err(e) => {
                crate::loadlog::note(&format!(
                    "child {pid}: remote LoadLibrary({full_shim_dll}) failed: {e:?}"
                ));
                return Err(if has_exited(process) {
                    ChildInjectError::ChildExited
                } else {
                    ChildInjectError::InjectDll
                });
            }
        }
    }
    // The shim signalled one way or the other before its `LoadLibrary` returned.
    let wait = wait_ready(process, pid, 0);
    crate::loadlog::note(&format!("child {pid}: ready wait {wait:?}"));
    match wait {
        ReadyWait::Ready => Ok(()),
        ReadyWait::BootstrapFailed => Err(ChildInjectError::BootstrapFailed),
        ReadyWait::Exited => Err(ChildInjectError::ChildExited),
        ReadyWait::TimedOut => Err(ChildInjectError::ReadyTimeout),
    }
}

// ─── early-payload-activation child injection (VFS_PAYLOAD_ACTIVATION only) ───

/// Absolute path of `vfs_payload.dll` for dual-layer child inject.
/// Prefers `VFS_PAYLOAD_PATH`, then co-locates/copies beside this shim DLL.
fn payload_dll_path() -> Option<String> {
    let self_dll = self_dll_path()?;
    let preferred = vfs_env::text(vfs_env::PAYLOAD_PATH);
    vfs_inject::ensure_payload_beside_shim(&self_dll, preferred.as_deref())
}

/// Early redirect table for children: the same static-import list as the
/// parent, from `VFS_SHIM_CONFIG` (inherited when `lpEnvironment` is null).
fn child_preinit_redirects() -> Vec<PreinitRedirect> {
    const MAX: usize = 4;
    let Some(path) = vfs_env::text(vfs_env::SHIM_CONFIG) else {
        return Vec::new();
    };
    vfs_inject::merge_preinit_redirects(&path, &[])
        .into_iter()
        .take(MAX)
        .collect()
}

/// Dual-layer child inject (same vehicle as the launcher): arm the early
/// payload with a spin gate → resume → wait the install sentinel →
/// LoadLibrary the full shim → wait ready → release the spin gate.
///
/// **Fails closed.** On any `Err` the child is still parked behind the spin
/// gate or in an unknown state, and the caller must kill it.
fn inject_child_dual_layer(
    process: HANDLE,
    thread: HANDLE,
    pid: u32,
    full_shim_dll: &str,
    timeout_ms: u32,
    cfg_path: &Path,
) -> Result<(), ChildInjectError> {
    let payload = payload_dll_path().ok_or(ChildInjectError::NoPayload)?;
    let redirects = child_preinit_redirects();
    // SAFETY: `process`/`thread` come from our own `CreateProcessInternalW`
    // hook, which forced CREATE_SUSPENDED, so the child is live and suspended
    // and we hold the rights the call needs.
    let arm = unsafe { arm_preinit_payload_ex(process, thread, &payload, &redirects, true) }
        .map_err(|_| ChildInjectError::Arm)?;

    // The child's bootstrap finds the cfg via this PID file (the inherited env
    // may still hold the parent's address).
    std::fs::write(cfg_path, format!("{:x}", arm.cfg_remote))
        .map_err(|_| ChildInjectError::CfgFile)?;

    // SAFETY: a thread handle from CreateProcess force-suspend; resume to run
    // the stub.
    if unsafe { ResumeThread(thread) } == u32::MAX {
        return Err(ChildInjectError::Resume);
    }

    let deadline = Instant::now() + Duration::from_millis(timeout_ms as u64);
    // Wait for the early install sentinel (counters[7] == 0xC0DE).
    loop {
        if read_u32(process, arm.counters + 0x1C) == Some(0xC0DE) {
            break;
        }
        if has_exited(process) {
            return Err(ChildInjectError::ChildExited);
        }
        if Instant::now() >= deadline {
            return Err(ChildInjectError::SentinelTimeout);
        }
        std::thread::sleep(Duration::from_millis(1));
    }

    if !inject_dll(process, full_shim_dll, timeout_ms) {
        return Err(ChildInjectError::InjectDll);
    }

    match wait_ready(process, pid, timeout_ms) {
        ReadyWait::Ready => {}
        ReadyWait::BootstrapFailed => return Err(ChildInjectError::BootstrapFailed),
        ReadyWait::Exited => return Err(ChildInjectError::ChildExited),
        ReadyWait::TimedOut => return Err(ChildInjectError::ReadyTimeout),
    }

    if !release_spin(process, arm.release_flag) {
        return Err(ChildInjectError::Release);
    }
    Ok(())
}

/// Inject `dll_path` into `process` via `LoadLibraryW` on a remote thread and
/// wait up to `timeout_ms` for that thread (i.e. for `DllMain` to run). `false`
/// if the thread could not be started, did not finish in time, or
/// `LoadLibraryW` returned NULL. Payload path's full-shim load.
fn inject_dll(process: HANDLE, dll_path: &str, timeout_ms: u32) -> bool {
    // SAFETY: standard remote-LoadLibrary injection into a live child process.
    unsafe {
        let dll_w = wide(dll_path);
        let bytes = dll_w.len() * 2;
        let remote = VirtualAllocEx(
            process,
            core::ptr::null(),
            bytes,
            MEM_COMMIT | MEM_RESERVE,
            PAGE_READWRITE,
        );
        if remote.is_null() {
            return false;
        }
        let mut written = 0usize;
        let ok = WriteProcessMemory(
            process,
            remote,
            dll_w.as_ptr() as *const c_void,
            bytes,
            &mut written,
        );
        if ok == 0 || written != bytes {
            return false;
        }
        let k32 = GetModuleHandleW(wide("kernel32.dll").as_ptr());
        if k32.is_null() {
            return false;
        }
        let load = match GetProcAddress(k32, c"LoadLibraryW".as_ptr().cast()) {
            Some(p) => p,
            None => return false,
        };
        let start: LPTHREAD_START_ROUTINE = Some(core::mem::transmute::<
            unsafe extern "system" fn() -> isize,
            unsafe extern "system" fn(*mut c_void) -> u32,
        >(load));
        let th = CreateRemoteThread(
            process,
            core::ptr::null(),
            0,
            start,
            remote,
            0,
            core::ptr::null_mut(),
        );
        if th.is_null() || th == INVALID_HANDLE_VALUE {
            return false;
        }
        let done = WaitForSingleObject(th, timeout_ms) == 0;
        let mut code = 0u32;
        let got = done && GetExitCodeThread(th, &mut code) != 0;
        CloseHandle(th);
        got && code != 0
    }
}

fn read_u32(process: HANDLE, addr: u64) -> Option<u32> {
    let mut buf = [0u8; 4];
    let mut n = 0usize;
    // SAFETY: best-effort RPM of a known remote diagnostics word.
    unsafe {
        let ok = ReadProcessMemory(
            process,
            addr as *const c_void,
            buf.as_mut_ptr() as *mut c_void,
            4,
            &mut n,
        );
        if ok != 0 && n == 4 {
            Some(u32::from_le_bytes(buf))
        } else {
            None
        }
    }
}

fn release_spin(process: HANDLE, release_flag: u64) -> bool {
    if release_flag == 0 {
        return true;
    }
    let one = 1u32.to_le_bytes();
    // SAFETY: release_flag is in the child's address space (from arm).
    unsafe {
        let mut n = 0usize;
        WriteProcessMemory(
            process,
            release_flag as *const c_void,
            one.as_ptr() as *const c_void,
            4,
            &mut n,
        ) != 0
            && n == 4
    }
}

/// Re-suspend a child the caller originally asked to keep suspended (after the
/// payload handshake resumed it to run the stub).
fn re_suspend(thread: HANDLE) {
    // SAFETY: a thread handle from CreateProcess; best-effort.
    unsafe {
        let _ = SuspendThread(thread);
    }
}

/// Whether `process` is a 32-bit image running under WOW64. A query that
/// fails reads as "no": the arm step then decides.
fn is_32bit(process: HANDLE) -> bool {
    let (mut machine, mut native) = (0u16, 0u16);
    // SAFETY: a query of a process handle we own, into two locals.
    let ok = unsafe { IsWow64Process2(process, &mut machine, &mut native) };
    // `IMAGE_FILE_MACHINE_UNKNOWN` (0): not a WOW64 process.
    ok != 0 && machine != 0
}

fn has_exited(process: HANDLE) -> bool {
    // SAFETY: a zero-timeout poll of a process handle we own.
    unsafe { WaitForSingleObject(process, 0) == 0 }
}

/// The absolute path of the DLL this code lives in.
pub(crate) fn self_dll_path() -> Option<String> {
    // SAFETY: resolve our module by an address inside it, then read its path.
    unsafe {
        let mut hmod = core::ptr::null_mut();
        let ok = GetModuleHandleExW(
            GET_MODULE_HANDLE_EX_FLAG_FROM_ADDRESS | GET_MODULE_HANDLE_EX_FLAG_UNCHANGED_REFCOUNT,
            self_dll_path as *const u16,
            &mut hmod,
        );
        if ok == 0 || hmod.is_null() {
            return None;
        }
        let mut buf = vec![0u16; 32768];
        let n = GetModuleFileNameW(hmod, buf.as_mut_ptr(), buf.len() as u32);
        if n == 0 || n as usize >= buf.len() {
            return None;
        }
        Some(String::from_utf16_lossy(&buf[..n as usize]))
    }
}

/// Signal that the current process's shim has installed its hooks.
pub(crate) fn signal_ready() {
    // SAFETY: named-event create + set; the leaked handle is process-lifetime.
    unsafe {
        let name = ready_event_name(GetCurrentProcessId());
        let ev = CreateEventW(core::ptr::null(), 1, 0, name.as_ptr());
        if !ev.is_null() {
            SetEvent(ev);
        }
    }
}

/// Called once this process's shim has finished writing its own ready state:
/// drops `VFS_SHIM_READY`, the ready file the top-level launch used to talk to
/// *this* process, so descendants do not inherit it. A child that did would
/// write its own "ready" or failure into the top-level ready file and its boot
/// log. Children signal their spawner by named event, and refusals are logged
/// through `VFS_CHILD_REFUSED_LOG`, which is left set.
pub fn finish_ready_handshake() {
    std::env::remove_var(vfs_env::SHIM_READY);
    // The payload-activation path also published this process's own payload
    // config address here; a descendant must find its own by pid, not inherit
    // the parent's. A no-op when the payload path is off (it was never set).
    std::env::remove_var(vfs_env::PAYLOAD_CFG_FILE);
}

/// Signal that the current process's shim could not bootstrap, so a spawning
/// parent stops waiting for a ready signal that will not come and kills us.
/// The counterpart of [`signal_ready`].
pub fn signal_bootstrap_failed() {
    // SAFETY: named-event create + set; the leaked handle is process-lifetime.
    unsafe {
        let name = failed_event_name(GetCurrentProcessId());
        let ev = CreateEventW(core::ptr::null(), 1, 0, name.as_ptr());
        if !ev.is_null() {
            SetEvent(ev);
        }
    }
}

/// How a wait for a child's shim ended.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub(crate) enum ReadyWait {
    Ready,
    BootstrapFailed,
    Exited,
    TimedOut,
}

/// Wait up to `timeout_ms` for `pid`'s shim to signal readiness or failure,
/// or for the child to exit, whichever comes first.
pub(crate) fn wait_ready(process: HANDLE, pid: u32, timeout_ms: u32) -> ReadyWait {
    // SAFETY: named-event create + timed wait; handles closed before return.
    unsafe {
        let ready = CreateEventW(core::ptr::null(), 1, 0, ready_event_name(pid).as_ptr());
        let failed = CreateEventW(core::ptr::null(), 1, 0, failed_event_name(pid).as_ptr());
        if ready.is_null() || failed.is_null() {
            for h in [ready, failed] {
                if !h.is_null() {
                    CloseHandle(h);
                }
            }
            return ReadyWait::TimedOut;
        }
        // Index order is priority order when several are signalled at once.
        let handles = [ready, failed, process];
        let r = WaitForMultipleObjects(3, handles.as_ptr(), 0, timeout_ms);
        CloseHandle(ready);
        CloseHandle(failed);
        match r {
            0 => ReadyWait::Ready,
            1 => ReadyWait::BootstrapFailed,
            2 => ReadyWait::Exited,
            _ => ReadyWait::TimedOut,
        }
    }
}
