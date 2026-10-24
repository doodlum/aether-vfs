//! Win32 launch and injection: start the target, or start it suspended and
//! `LoadLibrary` the shim into it on a remote thread.
#![allow(unsafe_code)]

use core::ffi::c_void;
use std::mem::{size_of, zeroed};
use std::os::windows::ffi::OsStrExt;
use std::time::{Duration, Instant};

use windows_sys::Win32::Foundation::{CloseHandle, HANDLE, INVALID_HANDLE_VALUE};
use windows_sys::Win32::System::Diagnostics::Debug::{
    GetThreadContext, SetThreadContext, WriteProcessMemory, CONTEXT,
};

/// `CONTEXT_FULL` for this target's own `CONTEXT`: `CONTEXT_AMD64` or
/// `CONTEXT_i386`, with its control and integer registers.
#[cfg(target_arch = "x86_64")]
const CONTEXT_FULL: u32 = 0x0010_000B;
#[cfg(target_arch = "x86")]
const CONTEXT_FULL: u32 = 0x0001_0007;
use windows_sys::Win32::System::LibraryLoader::{GetModuleHandleW, GetProcAddress};

use crate::guest::{read_ptr, teb_of, Guest};
use windows_sys::Win32::System::Memory::{VirtualAllocEx, MEM_COMMIT, MEM_RESERVE, PAGE_READWRITE};
use windows_sys::Win32::System::Threading::{
    CreateProcessW, CreateRemoteThread, GetExitCodeProcess, ResumeThread, TerminateProcess,
    WaitForSingleObject, CREATE_SUSPENDED, CREATE_UNICODE_ENVIRONMENT, INFINITE,
    LPTHREAD_START_ROUTINE, PROCESS_INFORMATION, STARTUPINFOW,
};

use crate::tree::{finish_code, Job, ProcessTree};
use crate::{InjectError, RunConfig};

/// The primary thread's stack for a virtualised process. The shim adds frames
/// to every intercepted call, and the stock 1 MiB stack overflows under that
/// (`0xC00000FD`). An import-patched exe carries it in its header; an injected
/// process gets it from [`expand_primary_stack`].
pub const PRIMARY_STACK_BYTES: usize = 16 * 1024 * 1024;

/// `STACK_SIZE_PARAM_IS_A_RESERVATION`: `CreateRemoteThread`'s size is the
/// reservation, not the commit.
const STACK_SIZE_PARAM_IS_A_RESERVATION: u32 = 0x0001_0000;

/// The exit code of `process` if it has already exited.
///
/// # Safety
/// `process` must be a live process handle with `SYNCHRONIZE` and
/// `PROCESS_QUERY_LIMITED_INFORMATION` access (a `CreateProcessW` handle).
unsafe fn exited(process: HANDLE) -> Option<u32> {
    if WaitForSingleObject(process, 0) != 0 {
        return None;
    }
    let mut code = 0u32;
    (GetExitCodeProcess(process, &mut code) != 0).then_some(code)
}

fn wide(s: &str) -> Vec<u16> {
    std::ffi::OsStr::new(s)
        .encode_wide()
        .chain(std::iter::once(0))
        .collect()
}

/// Grow the *primary* suspended thread's stack to `stack_reserve` bytes.
///
/// # Safety
/// `process` and `primary` must be the handles of a suspended process that
/// has not run, and its primary thread, with the rights `CreateProcess` gives.
///
/// Keeps CreateProcess image = Steam library path (DRM) while avoiding the
/// stock 1 MiB SkyrimSE stack that overflows under VFS hooks (`0xC00000FD`).
///
/// Unlike a bare RSP pivot onto an empty region (which broke Steam DRM /
/// RtlUserThreadStart), this:
/// 1. reads the live TEB StackBase + current RSP,
/// 2. copies `[RSP, StackBase)` onto the high end of a new reservation,
/// 3. adjusts RSP by the same delta, then updates TEB stack fields.
pub unsafe fn expand_primary_stack(
    process: HANDLE,
    primary: HANDLE,
    stack_reserve: usize,
) -> Result<(), InjectError> {
    // A 32-bit target's primary thread runs on its 32-bit stack, described by
    // its TEB32 and its 32-bit context (for a WOW64 guest of this process, the
    // ones the WOW64 layer keeps); a 64-bit one's by its own TEB and context.
    let guest = Guest::of(process).map_err(|e| {
        eprintln!("vfs-inject: {e}");
        InjectError::CreateProcess
    })?;
    if guest.ptr() > size_of::<usize>() {
        eprintln!("vfs-inject: a 64-bit target's stack cannot be grown from a 32-bit process");
        return Err(InjectError::CreateProcess);
    }
    let teb = teb_of(process, primary, guest).map_err(|e| {
        eprintln!("vfs-inject: the primary thread's TEB: {e}");
        InjectError::CreateProcess
    })?;
    let field = |off: usize| {
        read_ptr(process, teb + off as u64, guest)
            .ok()
            .filter(|v| *v != 0)
            .ok_or_else(|| {
                eprintln!("vfs-inject: TEB stack field read failed off={off:#x}");
                InjectError::CreateProcess
            })
    };
    let old_base = field(guest.teb_stack_base())?;
    let old_limit = field(guest.teb_stack_limit())?;
    let old_dealloc = field(guest.teb_deallocation_stack())?;

    let mut ctx = PrimaryContext::read(primary, guest)?;
    let old_rsp = ctx.sp();
    if old_rsp == 0 || old_rsp >= old_base || old_rsp < old_limit {
        eprintln!(
            "vfs-inject: primary RSP={old_rsp:#x} outside stack [{old_limit:#x},{old_base:#x})"
        );
        return Err(InjectError::CreateProcess);
    }
    let used = (old_base - old_rsp) as usize;
    // Leave headroom: used frames + 256 KiB slack must fit in the new reserve.
    if used + 256 * 1024 > stack_reserve {
        eprintln!("vfs-inject: live stack used={used:#x} exceeds expand target");
        return Err(InjectError::Alloc);
    }

    // Anywhere in a 64-bit target; a WOW64 guest's address space ends below
    // 4 GiB, so `VirtualAllocEx` places it there.
    let stack = VirtualAllocEx(
        process,
        core::ptr::null(),
        stack_reserve,
        MEM_COMMIT | MEM_RESERVE,
        PAGE_READWRITE,
    );
    if stack.is_null() {
        return Err(InjectError::Alloc);
    }
    let mut read_n = 0usize;
    let new_base = stack as u64 + stack_reserve as u64; // high address (grows down)
                                                        // Guard-ish low page as StackLimit (committed but not used for frames).
    let new_limit = stack as u64 + 0x1000;
    let new_rsp = new_base - used as u64;

    // Copy live frames [old_rsp, old_base) → [new_rsp, new_base).
    let mut live = vec![0u8; used];
    let ok = windows_sys::Win32::System::Diagnostics::Debug::ReadProcessMemory(
        process,
        old_rsp as *const c_void,
        live.as_mut_ptr() as *mut c_void,
        used,
        &mut read_n,
    );
    if ok == 0 || read_n != used {
        eprintln!("vfs-inject: read live stack frames failed used={used:#x}");
        return Err(InjectError::CreateProcess);
    }
    let mut written = 0usize;
    let ok = WriteProcessMemory(
        process,
        new_rsp as *mut c_void,
        live.as_ptr() as *const c_void,
        used,
        &mut written,
    );
    if ok == 0 || written != used {
        eprintln!("vfs-inject: write live stack frames failed");
        return Err(InjectError::Write);
    }

    for (off, val) in [
        (guest.teb_stack_base(), new_base),
        (guest.teb_stack_limit(), new_limit),
        (guest.teb_deallocation_stack(), stack as u64),
    ] {
        // Little-endian: the low `ptr` bytes are the field at that width.
        let ok = WriteProcessMemory(
            process,
            (teb + off as u64) as usize as *mut c_void,
            &val as *const u64 as *const c_void,
            guest.ptr(),
            &mut written,
        );
        if ok == 0 || written != guest.ptr() {
            eprintln!("vfs-inject: TEB stack field write failed off={off:#x}");
            return Err(InjectError::Write);
        }
    }

    ctx.set_sp(new_rsp);
    ctx.write(primary)?;
    eprintln!(
        "vfs-inject: expanded primary stack to {stack_reserve:#x} \
         ({guest:?} TEB={teb:#x} SP {old_rsp:#x}→{new_rsp:#x} used={used:#x}; \
         old dealloc={old_dealloc:#x})"
    );
    Ok(())
}

/// The primary thread's register context, at the target's width: the native
/// `CONTEXT`, or the 32-bit `WOW64_CONTEXT` of a WOW64 guest of this process.
enum PrimaryContext {
    /// A `CONTEXT`, in a buffer over-allocated so it can be 16-byte aligned
    /// (the x64 `CONTEXT` requires it), and the aligned offset into it.
    Native(Vec<u8>, usize),
    #[cfg(target_pointer_width = "64")]
    Wow64(Box<windows_sys::Win32::System::Diagnostics::Debug::WOW64_CONTEXT>),
}

impl PrimaryContext {
    /// # Safety
    /// `thread` is a suspended thread of a process of `guest`'s width, with
    /// `THREAD_GET_CONTEXT`.
    unsafe fn read(thread: HANDLE, guest: Guest) -> Result<Self, InjectError> {
        #[cfg(target_pointer_width = "64")]
        if guest.is_wow64_from_here() {
            use windows_sys::Win32::System::Diagnostics::Debug::{
                Wow64GetThreadContext, WOW64_CONTEXT, WOW64_CONTEXT_FULL,
            };
            let mut ctx: Box<WOW64_CONTEXT> = Box::new(zeroed());
            ctx.ContextFlags = WOW64_CONTEXT_FULL;
            if Wow64GetThreadContext(thread, &mut *ctx) == 0 {
                return Err(InjectError::ThreadContext);
            }
            return Ok(PrimaryContext::Wow64(ctx));
        }
        let _ = guest;
        let mut buf = vec![0u8; size_of::<CONTEXT>() + 16];
        let at = (buf.as_ptr() as usize).next_multiple_of(16) - buf.as_ptr() as usize;
        let ctx = buf.as_mut_ptr().add(at) as *mut CONTEXT;
        (*ctx).ContextFlags = CONTEXT_FULL;
        if GetThreadContext(thread, ctx) == 0 {
            return Err(InjectError::ThreadContext);
        }
        Ok(PrimaryContext::Native(buf, at))
    }

    /// The stack pointer.
    fn sp(&self) -> u64 {
        match self {
            #[cfg(target_pointer_width = "64")]
            PrimaryContext::Wow64(c) => c.Esp as u64,
            PrimaryContext::Native(buf, at) => {
                let ctx = buf[*at..].as_ptr() as *const CONTEXT;
                // SAFETY: the aligned `CONTEXT` `read` filled.
                #[cfg(target_arch = "x86_64")]
                return unsafe { (*ctx).Rsp };
                // SAFETY: as above.
                #[cfg(target_arch = "x86")]
                return unsafe { (*ctx).Esp as u64 };
            }
        }
    }

    fn set_sp(&mut self, sp: u64) {
        match self {
            #[cfg(target_pointer_width = "64")]
            PrimaryContext::Wow64(c) => c.Esp = sp as u32,
            PrimaryContext::Native(buf, at) => {
                let ctx = buf[*at..].as_mut_ptr() as *mut CONTEXT;
                // SAFETY: the aligned `CONTEXT` `read` filled.
                #[cfg(target_arch = "x86_64")]
                unsafe {
                    (*ctx).Rsp = sp
                };
                // SAFETY: as above.
                #[cfg(target_arch = "x86")]
                unsafe {
                    (*ctx).Esp = sp as u32
                };
            }
        }
    }

    /// # Safety
    /// As [`PrimaryContext::read`], with `THREAD_SET_CONTEXT`.
    unsafe fn write(&mut self, thread: HANDLE) -> Result<(), InjectError> {
        let ok = match self {
            #[cfg(target_pointer_width = "64")]
            PrimaryContext::Wow64(c) => {
                windows_sys::Win32::System::Diagnostics::Debug::Wow64SetThreadContext(thread, &**c)
            }
            PrimaryContext::Native(buf, at) => {
                SetThreadContext(thread, buf[*at..].as_ptr() as *const CONTEXT)
            }
        };
        if ok == 0 {
            return Err(InjectError::ThreadContext);
        }
        Ok(())
    }
}

/// `LoadLibraryW(dll_path)` in `process` on a remote thread, and wait up to
/// `timeout_ms` for it to return.
///
/// In a suspended process that has not run, the remote thread first runs the
/// process's initialisation (every static import and its `DllMain`), then
/// loads the shim, whose `DllMain` bootstraps before `LoadLibrary` returns. So
/// when this returns `Ok`, the shim has finished, one way or the other: its
/// ready file or ready event says which. The primary thread is not touched.
///
/// The remote thread gets [`PRIMARY_STACK_BYTES`]: process initialisation and
/// the shim's bootstrap run on it with the hooks going live.
///
/// # Safety
/// `process` must be a live process handle with `PROCESS_CREATE_THREAD`,
/// `PROCESS_VM_OPERATION` and `PROCESS_VM_WRITE`. This writes into and runs
/// code in another process; no part of that is checkable by the type system.
pub unsafe fn inject_dll(
    process: HANDLE,
    dll_path: &str,
    timeout_ms: u32,
) -> Result<(), InjectError> {
    // SAFETY: as this function's contract.
    unsafe { inject_dll_exit_code(process, dll_path, timeout_ms) }.map(|_| ())
}

/// [`inject_dll`], returning the remote `LoadLibraryW` thread's exit code: the
/// low 32 bits of the shim's `HMODULE`, so `0` means the load failed, and an
/// `NTSTATUS` (`0xC…`) means the process's initialisation, which that thread
/// ran first, failed. For a diagnosis; whether the shim came up is its own
/// signal's to say.
///
/// # Safety
/// As [`inject_dll`].
pub unsafe fn inject_dll_exit_code(
    process: HANDLE,
    dll_path: &str,
    timeout_ms: u32,
) -> Result<u32, InjectError> {
    // A WOW64 guest needs its own `LoadLibraryW`, the 32-bit kernel32's,
    // which a 64-bit process cannot look up: the 32-bit helper does it.
    // SAFETY: a query of the live handle the contract gives.
    if unsafe { Guest::of(process) }.is_ok_and(|g| g.is_wow64_from_here()) {
        // SAFETY: as this function's contract.
        return unsafe { load_library_through_helper(process, dll_path, timeout_ms) };
    }
    // SAFETY: standard remote LoadLibrary injection; `process` is a live process
    // handle with the needed rights (per the contract above).
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
            return Err(InjectError::Alloc);
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
            return Err(InjectError::Write);
        }
        let k32 = GetModuleHandleW(wide("kernel32.dll").as_ptr());
        if k32.is_null() {
            return Err(InjectError::RemoteThread);
        }
        let load_library = match GetProcAddress(k32, c"LoadLibraryW".as_ptr().cast()) {
            Some(p) => p,
            None => return Err(InjectError::RemoteThread),
        };
        let start: LPTHREAD_START_ROUTINE = Some(core::mem::transmute::<
            unsafe extern "system" fn() -> isize,
            unsafe extern "system" fn(*mut c_void) -> u32,
        >(load_library));
        let hthread = CreateRemoteThread(
            process,
            core::ptr::null(),
            PRIMARY_STACK_BYTES,
            start,
            remote,
            STACK_SIZE_PARAM_IS_A_RESERVATION,
            core::ptr::null_mut(),
        );
        if hthread.is_null() || hthread == INVALID_HANDLE_VALUE {
            let err = windows_sys::Win32::Foundation::GetLastError();
            eprintln!("vfs-inject: CreateRemoteThread(LoadLibrary) failed last_error={err}");
            return Err(InjectError::RemoteThread);
        }
        let done = WaitForSingleObject(hthread, timeout_ms) == 0;
        // The thread's exit code is only the low 32 bits of the HMODULE, so it
        // cannot tell a load from a failure for certain; the shim's own signal
        // does. It is still the best clue when that signal never comes.
        let mut code = 0u32;
        windows_sys::Win32::System::Threading::GetExitCodeThread(hthread, &mut code);
        CloseHandle(hthread);
        if done {
            Ok(code)
        } else {
            Err(InjectError::Timeout)
        }
    }
}

/// The 32-bit build of `vfs-injector.exe`, looked for beside the 32-bit shim a
/// WOW64 guest is given: it runs that guest's remote `LoadLibrary`
/// (`--load-library`, see [`crate::LOAD_LIBRARY_FLAG`]).
pub const WOW64_HELPER_EXE: &str = "vfs-injector.exe";

/// [`inject_dll_exit_code`] for a WOW64 guest of this 64-bit process, by the
/// 32-bit [`WOW64_HELPER_EXE`] beside `dll_path`. It runs the same remote
/// `LoadLibraryW` natively, from a process whose kernel32 is the guest's, and
/// reports one line (`crate::cli::load_library_report`).
///
/// # Safety
/// As [`inject_dll`].
unsafe fn load_library_through_helper(
    process: HANDLE,
    dll_path: &str,
    timeout_ms: u32,
) -> Result<u32, InjectError> {
    let helper = std::path::Path::new(dll_path)
        .parent()
        .map(|d| d.join(WOW64_HELPER_EXE))
        .filter(|p| p.is_file())
        .ok_or_else(|| {
            InjectError::Wow64Helper(format!(
                "no {WOW64_HELPER_EXE} (32-bit) beside the 32-bit shim {dll_path}"
            ))
        })?;
    // SAFETY: a query of the live handle the contract gives.
    let pid = unsafe { windows_sys::Win32::System::Threading::GetProcessId(process) };
    let out = std::process::Command::new(&helper)
        .args([
            crate::LOAD_LIBRARY_FLAG,
            &pid.to_string(),
            dll_path,
            &timeout_ms.to_string(),
        ])
        .stdin(std::process::Stdio::null())
        .output()
        .map_err(|e| InjectError::Wow64Helper(format!("run {}: {e}", helper.display())))?;
    let stdout = String::from_utf8_lossy(&out.stdout);
    let line = stdout.lines().last().unwrap_or_default();
    eprintln!(
        "vfs-inject: WOW64 LoadLibrary through {}: {line} (exit {:?})",
        helper.display(),
        out.status.code()
    );
    crate::parse_load_library_report(line)
}

/// What the contents of the shim's ready file say.
#[derive(Debug)]
enum ReadyState {
    /// `READY_OK`: release the process.
    Ready,
    /// A failure spelling: the process must be killed with this error.
    Failed(InjectError),
    /// Empty, partial or unrecognised: keep waiting (a write in flight reads
    /// as this too). Never a reason to release.
    Pending,
}

fn classify_ready(content: &str) -> ReadyState {
    if content == vfs_env::READY_OK {
        ReadyState::Ready
    } else if let Some(msg) = content.strip_prefix(vfs_env::READY_FUSE_FAILED_PREFIX) {
        // Also how an older shim spelled a config error; its message says so.
        ReadyState::Failed(InjectError::FuseInit(msg.to_string()))
    } else if let Some(msg) = content.strip_prefix(vfs_env::READY_BOOTSTRAP_FAILED_PREFIX) {
        ReadyState::Failed(InjectError::Bootstrap(msg.to_string()))
    } else {
        ReadyState::Pending
    }
}

/// The one way a launch gives up on a target that has not been released: kill
/// it, close both handles, and hand back the error to return.
///
/// Every failure between `CreateProcess` and resuming the target ends here,
/// because the only alternative (resuming it, or leaving it parked) is a game
/// running without the shim, or a leaked process.
/// Cases where the target has already exited only close the handles.
///
/// # Safety
/// `pi` must hold the live handles from this launch's `CreateProcessW`, not
/// yet closed.
unsafe fn fail_closed(pi: &PROCESS_INFORMATION, e: InjectError) -> InjectError {
    // SAFETY: per the contract above.
    unsafe {
        let _ = TerminateProcess(pi.hProcess, 1);
        CloseHandle(pi.hThread);
        CloseHandle(pi.hProcess);
    }
    e
}

/// Launch the target with the shim, and wait for it (unless `cfg.detach`).
///
/// A target exe that imports the shim first (staging patched it) is started
/// normally ([`start_import_activated`]). Any other is injected, the
/// way SKSE injects its DLL: created suspended, its primary stack grown to
/// [`PRIMARY_STACK_BYTES`], the shim `LoadLibrary`'d on a remote thread (which
/// runs process initialisation and then the shim's bootstrap), and resumed
/// only once the shim's ready file says it is up. Any failure kills it before
/// it has run.
///
/// **Waits for the first process only.** This is what `vfs-injector.exe` runs
/// inside Wine, where the Proton launch watches the whole prefix instead; a
/// host that needs the whole process tree uses [`spawn_target_with_shim`].
pub fn run_target_with_shim(cfg: RunConfig) -> Result<i32, InjectError> {
    let env = launch_env_block(&cfg);
    let started = start_target(&cfg, None, &env)?;
    // SAFETY: `started.process` is the live handle `start_target` handed over;
    // it is closed on every path below.
    unsafe {
        if cfg.detach {
            CloseHandle(started.process);
            return Ok(0);
        }
        if WaitForSingleObject(started.process, INFINITE) != 0 {
            CloseHandle(started.process);
            return Err(InjectError::Wait);
        }
        let code = exited(started.process);
        CloseHandle(started.process);
        match code {
            Some(c) => finish_code(started.import_activated, c),
            None => Err(InjectError::ExitCode),
        }
    }
}

/// Launch the target with the shim, exactly as [`run_target_with_shim`]
/// does, inside a Job Object, and return once it is running.
///
/// The target is assigned to the job while it is still suspended, before any
/// of its code (or the shim's) has run, so everything it starts inherits the
/// job: the returned [`ProcessTree`] ends when the last of them exits, and
/// reports the first process's exit code. `cfg.detach` is ignored.
///
/// A target that cannot be put in a job (the host is in one that forbids
/// nesting) is launched anyway and tracked alone: see
/// [`ProcessTree::untracked_reason`].
pub fn spawn_target_with_shim(cfg: RunConfig) -> Result<ProcessTree, InjectError> {
    let env = launch_env_block(&cfg);
    let (mut job, no_job) = match Job::new() {
        Ok(j) => (Some(j), None),
        Err(e) => (
            None,
            Some(format!(
                "no job for the launch ({e}), so it ends when the launched process exits, \
                 not when everything it started has"
            )),
        ),
    };
    let started = start_target(&cfg, job.as_mut(), &env)?;
    // SAFETY: `started.process` is live and owned; the tree takes it over.
    Ok(unsafe {
        ProcessTree::new(
            started.process,
            started.pid,
            started.import_activated,
            job,
            no_job,
        )
    })
}

/// The environment block the target starts with, and a clean slate for its
/// ready file. This process's own environment is read, never written: see
/// `env_block`.
fn launch_env_block(cfg: &RunConfig) -> Vec<u16> {
    // Where the shim says which children it killed; descendants inherit it.
    let refused_log = format!("{}{}", cfg.ready_path, vfs_env::CHILD_REFUSED_SUFFIX);
    let _ = std::fs::remove_file(&refused_log);
    let _ = std::fs::remove_file(&cfg.ready_path);
    let mut set: Vec<(String, String)> = vec![
        (vfs_env::SHIM_CONFIG.into(), cfg.config_path.clone()),
        (vfs_env::SHIM_READY.into(), cfg.ready_path.clone()),
        (vfs_env::CHILD_REFUSED_LOG.into(), refused_log),
        // The shim waits for each child process it injects as long as this
        // launch waits for the shim: it reads the value back from its
        // environment.
        (
            vfs_env::READY_TIMEOUT_SECS.into(),
            cfg.ready_timeout.as_secs().max(1).to_string(),
        ),
    ];
    // The managed root, so the child's fuse client matches the session root —
    // unless the caller or this process already names it. The Proton launch
    // sets `VFS_VIRTUAL_DIR` to root 0 and a working directory that may be
    // below it.
    if let Some(ref d) = cfg.current_dir {
        let named = |n: &str| n.eq_ignore_ascii_case(vfs_env::VIRTUAL_DIR);
        let removed = cfg.env_remove.iter().any(|n| named(n));
        if !cfg.env_set.iter().any(|(n, _)| named(n))
            && (removed || std::env::var_os(vfs_env::VIRTUAL_DIR).is_none())
        {
            set.push((vfs_env::VIRTUAL_DIR.into(), d.clone()));
        }
    }
    set.extend(cfg.env_set.iter().cloned());
    crate::env_block::build(&crate::env_block::current(), &set, &cfg.env_remove)
}

/// A target that is running with its shim up.
struct Started {
    /// Its process handle, owned by the receiver. The thread handle is closed.
    process: HANDLE,
    pid: u32,
    /// It imported the shim itself — see [`finish_code`].
    import_activated: bool,
}

/// Start the target with the shim (see [`run_target_with_shim`]) and, when
/// `job` is given, assign it to the job before it runs.
fn start_target(
    cfg: &RunConfig,
    job: Option<&mut Job>,
    env: &[u16],
) -> Result<Started, InjectError> {
    if crate::exe_imports_shim(&cfg.target_exe) {
        return start_import_activated(cfg, job, env);
    }

    let timeout_ms = u32::try_from(cfg.ready_timeout.as_millis()).unwrap_or(u32::MAX - 1);
    // SAFETY: CreateProcessW + inject + resume.
    unsafe {
        let mut pi: PROCESS_INFORMATION = zeroed();
        // Staging puts the EXE and its import closure on disk, so the loader
        // resolves everything itself — see `vfs-director::stage`.
        let mut cmd_w = wide(&command_line(cfg));
        let app_w = wide(&cfg.target_exe);
        let cwd_w = cfg.current_dir.as_ref().map(|s| wide(s));
        let mut si: STARTUPINFOW = zeroed();
        si.cb = size_of::<STARTUPINFOW>() as u32;
        let ok = CreateProcessW(
            app_w.as_ptr(),
            cmd_w.as_mut_ptr(),
            core::ptr::null(),
            core::ptr::null(),
            0,
            CREATE_SUSPENDED | CREATE_UNICODE_ENVIRONMENT,
            env.as_ptr().cast(),
            cwd_w
                .as_ref()
                .map(|v| v.as_ptr())
                .unwrap_or(core::ptr::null()),
            &si,
            &mut pi,
        );
        if ok == 0 {
            return Err(InjectError::CreateProcess);
        }

        // A shim of the other width would only fail later, and less clearly
        // (a 32-bit program cannot load a 64-bit DLL, nor the reverse).
        if let Err(e) = check_shim_width(pi.hProcess, &cfg.dll_path) {
            return Err(fail_closed(&pi, e));
        }

        // Before anything of the target runs — the remote thread below runs
        // its process initialisation — so nothing it starts escapes the job.
        if let Some(job) = job {
            job.adopt(pi.hProcess);
        }

        // Before process initialisation reads them: kernelbase keeps its own
        // copy of the command line from then on.
        if let Err(e) = tell_where(pi.hProcess, cfg) {
            return Err(fail_closed(&pi, e));
        }

        // Best-effort — a failure resumes with the stock stack rather than
        // refusing to launch.
        if let Err(e) = expand_primary_stack(pi.hProcess, pi.hThread, PRIMARY_STACK_BYTES) {
            eprintln!(
                "vfs-inject: expand_primary_stack failed ({e:?}) — resuming with stock 1MiB stack"
            );
        }

        // Prefer the in-memory import patch: the loader then loads the shim
        // first, so its `DllMain` runs before the target's own static imports
        // initialise. Remote-thread injection runs those imports first (they
        // read the game's files, which are in the VFS, before the shim is up),
        // which is what refused `SkyrimSE.exe`. Fall back to it only when the
        // in-memory patch is refused, and say which path was taken.
        if !cfg.force_injection {
            match crate::patch_imports_in_memory(pi.hProcess) {
                Ok(()) => {
                    eprintln!("vfs-inject: activation: in-memory import patch");
                    ResumeThread(pi.hThread);
                    // The shim bootstraps as the loader processes imports, after
                    // the resume, and writes the ready file. Poll it to the
                    // deadline, as the import-activated path does.
                    let state = poll_ready(pi.hProcess, &cfg.ready_path, cfg.ready_timeout);
                    if let Err(e) = state {
                        return Err(fail_closed(&pi, e));
                    }
                    CloseHandle(pi.hThread);
                    return Ok(Started {
                        process: pi.hProcess,
                        pid: pi.dwProcessId,
                        import_activated: false,
                    });
                }
                Err(e) => eprintln!(
                "vfs-inject: activation: remote injection (in-memory import patch refused: {e:?})"
            ),
            }
        }

        if let Err(e) = inject_dll(pi.hProcess, &cfg.dll_path, timeout_ms) {
            if let Some(code) = exited(pi.hProcess) {
                CloseHandle(pi.hThread);
                CloseHandle(pi.hProcess);
                return Err(InjectError::TargetExited(code));
            }
            return Err(fail_closed(&pi, e));
        }

        // The shim wrote its ready file before its `LoadLibrary` returned. Its
        // *content*, not merely its existence, is the protocol (see
        // `vfs_env::READY_OK`, `READY_FUSE_FAILED_PREFIX`,
        // `READY_BOOTSTRAP_FAILED_PREFIX`). Anything but "ready" — a failure
        // spelling, a shim that never loaded, a target that died in process
        // initialisation — kills the target, which has not run.
        let state = std::fs::read_to_string(&cfg.ready_path)
            .map(|c| classify_ready(&c))
            .unwrap_or(ReadyState::Pending);
        match state {
            ReadyState::Ready => {}
            ReadyState::Failed(e) => return Err(fail_closed(&pi, e)),
            ReadyState::Pending => {
                if let Some(code) = exited(pi.hProcess) {
                    CloseHandle(pi.hThread);
                    CloseHandle(pi.hProcess);
                    return Err(InjectError::TargetExited(code));
                }
                return Err(fail_closed(
                    &pi,
                    InjectError::Bootstrap("the shim loaded but never wrote its ready file".into()),
                ));
            }
        }

        ResumeThread(pi.hThread);
        CloseHandle(pi.hThread);
        Ok(Started {
            process: pi.hProcess,
            pid: pi.dwProcessId,
            import_activated: false,
        })
    }
}

/// Refuse a shim built for the other width than `process`. A shim whose
/// headers cannot be read is left to fail on its own, as before.
///
/// # Safety
/// `process` is a live process handle with `PROCESS_QUERY_LIMITED_INFORMATION`.
unsafe fn check_shim_width(process: HANDLE, dll_path: &str) -> Result<(), InjectError> {
    use std::io::Read;
    let mut head = Vec::with_capacity(0x1000);
    let Some(machine) = std::fs::File::open(dll_path)
        .and_then(|f| f.take(0x1000).read_to_end(&mut head))
        .ok()
        .and_then(|_| vfs_pe::pe_machine(&head))
    else {
        return Ok(());
    };
    // SAFETY: as this function's contract.
    let guest = unsafe { Guest::of(process) }.map_err(InjectError::ShimWidth)?;
    let fits = match guest {
        Guest::X64 => machine == vfs_pe::MACHINE_AMD64,
        Guest::X86 => machine == vfs_pe::MACHINE_I386,
    };
    if fits {
        Ok(())
    } else {
        Err(InjectError::ShimWidth(format!(
            "the target is a {} process and the shim {dll_path} is for machine {machine:#x}",
            if guest == Guest::X86 {
                "32-bit"
            } else {
                "64-bit"
            }
        )))
    }
}

/// Poll `ready_path` until the shim writes its verdict, the target exits, or
/// `timeout` passes. For the in-memory import patch, where the shim runs only
/// after the primary thread is resumed.
///
/// # Safety
/// `process` is the live, resumed target.
#[allow(unsafe_code)]
unsafe fn poll_ready(
    process: HANDLE,
    ready_path: &str,
    timeout: Duration,
) -> Result<(), InjectError> {
    let began = Instant::now();
    loop {
        match std::fs::read_to_string(ready_path)
            .map(|c| classify_ready(&c))
            .unwrap_or(ReadyState::Pending)
        {
            ReadyState::Ready => return Ok(()),
            ReadyState::Failed(e) => return Err(e),
            ReadyState::Pending => {}
        }
        // SAFETY: a zero-timeout poll of a handle we own.
        if let Some(code) = exited(process) {
            return Err(InjectError::TargetExited(code));
        }
        if began.elapsed() > timeout {
            return Err(InjectError::Timeout);
        }
        std::thread::sleep(Duration::from_millis(1));
    }
}

/// The target's command line: its path and each argument, quoted where needed.
fn command_line(cfg: &RunConfig) -> String {
    command_line_for(&cfg.target_exe, cfg)
}

/// [`command_line`] with `image` as the first argument.
fn command_line_for(image: &str, cfg: &RunConfig) -> String {
    let mut cmdline = format!("\"{image}\"");
    for a in &cfg.args {
        cmdline.push(' ');
        if cfg.windows_verbatim_arguments {
            cmdline.push_str(a);
        } else {
            push_arg(&mut cmdline, a);
        }
    }
    cmdline
}

/// Append one argument as `CommandLineToArgvW` (and the CRT) read it back. Quoted only when it
/// has to be: `cmd.exe` takes a quoted switch (`"/c"`) for a command to run, and a plain quote
/// around a trailing backslash (`"C:\dir\"`) escapes the closing quote.
fn push_arg(cmdline: &mut String, arg: &str) {
    if !arg.is_empty() && !arg.contains([' ', '\t', '\n', '\u{b}', '"']) {
        cmdline.push_str(arg);
        return;
    }
    cmdline.push('"');
    let mut backslashes = 0;
    for ch in arg.chars() {
        match ch {
            '\\' => backslashes += 1,
            '"' => {
                // Each backslash before a quote is doubled, and the quote itself escaped.
                cmdline.extend(std::iter::repeat_n('\\', backslashes * 2 + 1));
                cmdline.push('"');
                backslashes = 0;
            }
            _ => {
                cmdline.extend(std::iter::repeat_n('\\', backslashes));
                cmdline.push(ch);
                backslashes = 0;
            }
        }
    }
    // Backslashes before the closing quote are doubled so that it stays a closing quote.
    cmdline.extend(std::iter::repeat_n('\\', backslashes * 2));
    cmdline.push('"');
}

/// Tell the suspended target its virtual image path and working directory,
/// if it has them (see `procparams`).
///
/// # Safety
/// `process` is the suspended target's `CreateProcessW` handle.
unsafe fn tell_where(process: HANDLE, cfg: &RunConfig) -> Result<(), InjectError> {
    let cmd = cfg
        .virtual_image
        .as_deref()
        .map(|v| command_line_for(v, cfg));
    // SAFETY: as this function's contract.
    unsafe { crate::procparams::rewrite(process, cmd.as_deref(), cfg.virtual_cwd.as_deref()) }
        .map_err(InjectError::Bootstrap)
}

/// Start a target whose exe imports the shim first. No payload, no remote
/// thread: the loader runs the shim's `DllMain` before any other import
/// initialises, and a failed bootstrap fails process start there. Returns
/// once the shim has said something in its ready file, or the target has
/// exited.
///
/// Created suspended only to be assigned to `job` before it runs, then
/// resumed at once; without a job it is started running, as it always was.
fn start_import_activated(
    cfg: &RunConfig,
    job: Option<&mut Job>,
    env: &[u16],
) -> Result<Started, InjectError> {
    let mut cmd_w = wide(&command_line(cfg));
    let app_w = wide(&cfg.target_exe);
    let cwd_w = cfg.current_dir.as_ref().map(|s| wide(s));
    let started = Instant::now();
    // Suspended to be put in `job`, or to be told where it is, before it runs.
    let rewrite = cfg.virtual_image.is_some() || cfg.virtual_cwd.is_some();
    let flags = if job.is_some() || rewrite {
        CREATE_SUSPENDED
    } else {
        0
    };
    // SAFETY: CreateProcessW with valid, NUL-terminated buffers; the thread
    // handle is closed here and the process handle handed to the caller.
    unsafe {
        let mut pi: PROCESS_INFORMATION = zeroed();
        let mut si: STARTUPINFOW = zeroed();
        si.cb = size_of::<STARTUPINFOW>() as u32;
        let ok = CreateProcessW(
            app_w.as_ptr(),
            cmd_w.as_mut_ptr(),
            core::ptr::null(),
            core::ptr::null(),
            0,
            flags | CREATE_UNICODE_ENVIRONMENT,
            env.as_ptr().cast(),
            cwd_w
                .as_ref()
                .map(|v| v.as_ptr())
                .unwrap_or(core::ptr::null()),
            &si,
            &mut pi,
        );
        if ok == 0 {
            return Err(InjectError::CreateProcess);
        }
        if let Some(job) = job {
            job.adopt(pi.hProcess);
        }
        if flags & CREATE_SUSPENDED != 0 {
            if let Err(e) = tell_where(pi.hProcess, cfg) {
                return Err(fail_closed(&pi, e));
            }
            ResumeThread(pi.hThread);
        }
        CloseHandle(pi.hThread);
        // Report when the shim said ready, for the timing comparison.
        loop {
            if let Ok(c) = std::fs::read_to_string(&cfg.ready_path) {
                if !c.is_empty() {
                    eprintln!(
                        "vfs-inject: import activation: shim ready after {} ms: {c}",
                        started.elapsed().as_millis()
                    );
                    break;
                }
            }
            if exited(pi.hProcess).is_some() {
                break;
            }
            std::thread::sleep(std::time::Duration::from_millis(1));
        }
        Ok(Started {
            process: pi.hProcess,
            pid: pi.dwProcessId,
            import_activated: true,
        })
    }
}

/// Resume a suspended thread (the child's primary, after an in-memory import
/// patch). A no-op on a thread that is already running.
///
/// # Safety
/// `thread` is a live thread handle with `THREAD_SUSPEND_RESUME`.
#[allow(unsafe_code)]
pub unsafe fn resume_primary_thread(thread: HANDLE) {
    // SAFETY: a resume of a thread handle we own.
    unsafe {
        ResumeThread(thread);
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn quoted(arg: &str) -> String {
        let mut s = String::new();
        push_arg(&mut s, arg);
        s
    }

    #[test]
    fn arguments_are_quoted_only_where_needed_and_read_back_whole() {
        // cmd.exe reads a quoted switch as a command to run.
        assert_eq!(quoted("/c"), "/c");
        assert_eq!(quoted("-epicapp=Foo"), "-epicapp=Foo");
        assert_eq!(quoted(""), "\"\"");
        assert_eq!(quoted("exit 7"), "\"exit 7\"");
        assert_eq!(quoted(r"C:\a dir\"), r#""C:\a dir\\""#);
        assert_eq!(quoted(r#"say "hi""#), r#""say \"hi\"""#);
        assert_eq!(quoted(r#"a\"b"#), r#""a\\\"b""#);
        assert_eq!(quoted(r"C:\no\spaces\"), r"C:\no\spaces\");
    }

    #[test]
    fn ready_file_contents_map_to_release_kill_or_wait() {
        assert!(matches!(classify_ready("ready"), ReadyState::Ready));
        assert!(matches!(
            classify_ready("fuse-failed:no ring"),
            ReadyState::Failed(InjectError::FuseInit(m)) if m == "no ring"
        ));
        assert!(matches!(
            classify_ready("bootstrap-failed:shim config version 3"),
            ReadyState::Failed(InjectError::Bootstrap(m)) if m == "shim config version 3"
        ));
        // Nothing here may ever read as Ready: a half-written file keeps waiting.
        for partial in ["", "rea", "ready ", "boot", "bootstrap", "Ready"] {
            assert!(
                matches!(classify_ready(partial), ReadyState::Pending),
                "{partial:?}"
            );
        }
    }
}
