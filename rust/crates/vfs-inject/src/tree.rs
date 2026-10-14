//! A launched target and every process it starts, held in a Job Object.
//!
//! [`spawn_target_with_shim`](crate::spawn_target_with_shim) assigns the
//! target to a fresh job while it is still suspended, so every process it
//! creates afterwards inherits the job: a launcher that starts the game and
//! exits (`skse64_loader.exe` → `SkyrimSE.exe`) is one [`ProcessTree`], and
//! it has ended only when the job has no active process left. The exit code
//! reported is still the first process's.
//!
//! The job's completion port only wakes a waiter. Windows does not guarantee
//! delivery of job notifications, so the job's `ActiveProcesses` count is the
//! truth, re-read after every wake-up and at least every [`WAIT_SLICE`].
#![allow(unsafe_code)]

use core::ffi::c_void;
use std::mem::{size_of, zeroed};
use std::sync::Arc;
use std::time::{Duration, Instant};

use windows_sys::Win32::Foundation::{
    CloseHandle, GetLastError, HANDLE, INVALID_HANDLE_VALUE, WAIT_OBJECT_0,
};
use windows_sys::Win32::System::JobObjects::{
    AssignProcessToJobObject, CreateJobObjectW, JobObjectAssociateCompletionPortInformation,
    JobObjectBasicAccountingInformation, JobObjectExtendedLimitInformation,
    QueryInformationJobObject, SetInformationJobObject, TerminateJobObject,
    JOBOBJECT_ASSOCIATE_COMPLETION_PORT, JOBOBJECT_BASIC_ACCOUNTING_INFORMATION,
    JOBOBJECT_EXTENDED_LIMIT_INFORMATION, JOB_OBJECT_LIMIT_BREAKAWAY_OK,
    JOB_OBJECT_LIMIT_KILL_ON_JOB_CLOSE,
};
use windows_sys::Win32::System::Threading::{
    GetExitCodeProcess, TerminateProcess, WaitForSingleObject,
};
use windows_sys::Win32::System::IO::{
    CreateIoCompletionPort, GetQueuedCompletionStatus, OVERLAPPED,
};

use crate::InjectError;

/// The longest a waiter sleeps on the completion port before it re-reads the
/// job's process count, so a lost notification costs at most this much.
const WAIT_SLICE: Duration = Duration::from_millis(500);

/// How long a finished tree's first process may take to become signalled
/// once the job reports no active process.
const EXIT_CODE_WAIT_MS: u32 = 5_000;

/// An unnamed job with a completion port, configured for a launch.
pub(crate) struct Job {
    job: HANDLE,
    port: HANDLE,
    /// Set by [`Job::adopt`] when the process could not be assigned: why.
    unassigned: Option<String>,
}

impl Job {
    /// A job that kills what is left in it when its last handle closes (the
    /// handle never leaves this process, so that is when the host is gone),
    /// that lets a child which asks for `CREATE_BREAKAWAY_FROM_JOB` break
    /// away rather than fail to start, and that posts its notifications to a
    /// completion port of its own.
    pub(crate) fn new() -> Result<Job, String> {
        // SAFETY: plain Win32 object creation; every handle is checked and
        // owned by the returned `Job` (closed in `Drop`), or closed here.
        unsafe {
            let job = CreateJobObjectW(core::ptr::null(), core::ptr::null());
            if job.is_null() {
                return Err(format!(
                    "CreateJobObjectW failed (error {})",
                    GetLastError()
                ));
            }
            let port = CreateIoCompletionPort(INVALID_HANDLE_VALUE, core::ptr::null_mut(), 0, 1);
            if port.is_null() {
                let err = GetLastError();
                CloseHandle(job);
                return Err(format!("CreateIoCompletionPort failed (error {err})"));
            }
            let job = Job {
                job,
                port,
                unassigned: None,
            };

            let mut limits: JOBOBJECT_EXTENDED_LIMIT_INFORMATION = zeroed();
            limits.BasicLimitInformation.LimitFlags =
                JOB_OBJECT_LIMIT_KILL_ON_JOB_CLOSE | JOB_OBJECT_LIMIT_BREAKAWAY_OK;
            if SetInformationJobObject(
                job.job,
                JobObjectExtendedLimitInformation,
                &limits as *const _ as *const c_void,
                size_of::<JOBOBJECT_EXTENDED_LIMIT_INFORMATION>() as u32,
            ) == 0
            {
                return Err(format!(
                    "setting the job's limits failed (error {})",
                    GetLastError()
                ));
            }

            let assoc = JOBOBJECT_ASSOCIATE_COMPLETION_PORT {
                CompletionKey: job.job,
                CompletionPort: job.port,
            };
            if SetInformationJobObject(
                job.job,
                JobObjectAssociateCompletionPortInformation,
                &assoc as *const _ as *const c_void,
                size_of::<JOBOBJECT_ASSOCIATE_COMPLETION_PORT>() as u32,
            ) == 0
            {
                return Err(format!(
                    "associating the job's completion port failed (error {})",
                    GetLastError()
                ));
            }
            Ok(job)
        }
    }

    /// Assigns `process` (created suspended, not yet run) to the job. A
    /// failure is recorded rather than returned: the launch goes on, tracking
    /// only its first process — see [`ProcessTree::untracked_reason`].
    ///
    /// # Safety
    /// `process` must be a live process handle with `PROCESS_SET_QUOTA` and
    /// `PROCESS_TERMINATE` access (a `CreateProcessW` handle).
    pub(crate) unsafe fn adopt(&mut self, process: HANDLE) {
        // SAFETY: per the contract above; `self.job` is live.
        if unsafe { AssignProcessToJobObject(self.job, process) } == 0 {
            // SAFETY: no arguments.
            let err = unsafe { GetLastError() };
            self.unassigned = Some(format!(
                "the launched process could not be put in a job (AssignProcessToJobObject \
                 error {err}; is this host in a job that forbids nesting?), so the launch \
                 ends when that process exits, not when everything it started has"
            ));
        }
    }

    /// Why [`Job::adopt`] failed, if it did.
    pub(crate) fn unassigned(&self) -> Option<&str> {
        self.unassigned.as_deref()
    }

    fn active_processes(&self) -> Result<u32, InjectError> {
        // SAFETY: a correctly sized out-buffer for this information class.
        unsafe {
            let mut info: JOBOBJECT_BASIC_ACCOUNTING_INFORMATION = zeroed();
            let ok = QueryInformationJobObject(
                self.job,
                JobObjectBasicAccountingInformation,
                &mut info as *mut _ as *mut c_void,
                size_of::<JOBOBJECT_BASIC_ACCOUNTING_INFORMATION>() as u32,
                core::ptr::null_mut(),
            );
            if ok == 0 {
                return Err(InjectError::Wait);
            }
            Ok(info.ActiveProcesses)
        }
    }

    /// Sleeps until the job posts a notification (any: a process started or
    /// exited, or none left) or `ms` passes. The caller re-reads the count.
    fn wake_or_timeout(&self, ms: u32) {
        let mut bytes = 0u32;
        let mut key = 0usize;
        let mut overlapped: *mut OVERLAPPED = core::ptr::null_mut();
        // SAFETY: valid out-pointers; `self.port` is live. The message itself
        // (`bytes`) is not needed: the count is re-read either way.
        unsafe {
            GetQueuedCompletionStatus(self.port, &mut bytes, &mut key, &mut overlapped, ms);
        }
    }
}

impl Drop for Job {
    fn drop(&mut self) {
        // SAFETY: both handles are owned by this `Job` and closed only here.
        // Closing the job's last handle kills anything still in it
        // (`KILL_ON_JOB_CLOSE`).
        unsafe {
            CloseHandle(self.port);
            CloseHandle(self.job);
        }
    }
}

/// The handles a [`ProcessTree`] and its [`TreeKiller`]s share.
struct Shared {
    /// The first process: the launched target.
    process: HANDLE,
    job: Option<Job>,
}

// SAFETY: kernel handles are process-wide, not thread-affine. Every use here
// is a Win32 call that is safe to make concurrently on the same handle
// (waiting, querying, terminating); the handles are closed only in `Drop`,
// when no other reference is left.
unsafe impl Send for Shared {}
// SAFETY: see `Send` above.
unsafe impl Sync for Shared {}

impl Drop for Shared {
    fn drop(&mut self) {
        // SAFETY: owned since `CreateProcessW`, closed only here. The job (if
        // any) closes after it, in its own `Drop`.
        unsafe { CloseHandle(self.process) };
    }
}

/// A running launch: the target [`spawn_target_with_shim`](crate::spawn_target_with_shim)
/// started and everything it starts.
///
/// [`ProcessTree::try_wait`] and friends report the launch finished only
/// when no process is left in its job, with the **first** process's exit
/// code. Dropping it closes this side's handles; once every [`TreeKiller`]
/// is gone too, the job closes and kills what is left in it.
pub struct ProcessTree {
    shared: Arc<Shared>,
    pid: u32,
    /// The target imported the shim itself (staging patched it), so a loader
    /// refusal reads as an exit code — see [`finish_code`].
    import_activated: bool,
    untracked: Option<String>,
}

/// Ends a [`ProcessTree`] from any thread: see [`ProcessTree::killer`].
#[derive(Clone)]
pub struct TreeKiller(Arc<Shared>);

impl std::fmt::Debug for TreeKiller {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        f.debug_struct("TreeKiller")
            .field("tracks_tree", &self.0.job.is_some())
            .finish()
    }
}

impl TreeKiller {
    /// Terminates every process in the tree with `exit_code`: the whole job,
    /// or the first process alone when the launch has no job
    /// ([`ProcessTree::untracked_reason`]). Harmless once the tree has ended.
    pub fn kill(&self, exit_code: u32) -> Result<(), InjectError> {
        // SAFETY: live handles owned by `Shared`.
        unsafe {
            match &self.0.job {
                Some(job) => {
                    if TerminateJobObject(job.job, exit_code) == 0 {
                        return Err(InjectError::Terminate);
                    }
                }
                None => {
                    if TerminateProcess(self.0.process, exit_code) == 0
                        && WaitForSingleObject(self.0.process, 0) != WAIT_OBJECT_0
                    {
                        return Err(InjectError::Terminate);
                    }
                }
            }
        }
        Ok(())
    }
}

impl ProcessTree {
    /// Takes ownership of `process` (the launched target, already resumed;
    /// its thread handle closed) and of `job`, which holds it unless
    /// [`Job::adopt`] failed, in which case the job is dropped and only the
    /// first process is tracked.
    ///
    /// # Safety
    /// `process` must be a live process handle owned by the caller, with
    /// `SYNCHRONIZE`, `PROCESS_QUERY_LIMITED_INFORMATION` and
    /// `PROCESS_TERMINATE` access; it is closed when the tree drops.
    pub(crate) unsafe fn new(
        process: HANDLE,
        pid: u32,
        import_activated: bool,
        job: Option<Job>,
        untracked: Option<String>,
    ) -> ProcessTree {
        let (job, untracked) = match job {
            Some(j) if j.unassigned().is_some() => (None, j.unassigned().map(str::to_string)),
            Some(j) => (Some(j), untracked),
            None => (None, untracked),
        };
        if let Some(why) = &untracked {
            eprintln!("vfs-inject: {why}");
        }
        ProcessTree {
            shared: Arc::new(Shared { process, job }),
            pid,
            import_activated,
            untracked,
        }
    }

    /// The launched target's process id (the first process of the tree).
    pub fn pid(&self) -> u32 {
        self.pid
    }

    /// Whether the whole tree is tracked: `false` only when the target could
    /// not be put in a job, and then [`ProcessTree::untracked_reason`] says why.
    pub fn tracks_tree(&self) -> bool {
        self.shared.job.is_some()
    }

    /// Why the launch tracks only its first process, when it does.
    pub fn untracked_reason(&self) -> Option<&str> {
        self.untracked.as_deref()
    }

    /// A killer for this tree, to end it from another thread while this one
    /// waits.
    pub fn killer(&self) -> TreeKiller {
        TreeKiller(Arc::clone(&self.shared))
    }

    /// The first process's exit code, if every process in the tree has
    /// exited. Never blocks (beyond a finished process becoming signalled).
    pub fn try_wait(&mut self) -> Result<Option<i32>, InjectError> {
        let empty = match &self.shared.job {
            Some(job) => job.active_processes()? == 0,
            // SAFETY: a live process handle owned by `Shared`.
            None => (unsafe { WaitForSingleObject(self.shared.process, 0) }) == WAIT_OBJECT_0,
        };
        if !empty {
            return Ok(None);
        }
        // SAFETY: a live process handle owned by `Shared`. The job has no
        // active process, so the first one has exited (or is just becoming
        // signalled).
        let code = unsafe {
            if WaitForSingleObject(self.shared.process, EXIT_CODE_WAIT_MS) != WAIT_OBJECT_0 {
                return Err(InjectError::Wait);
            }
            let mut code = 0u32;
            if GetExitCodeProcess(self.shared.process, &mut code) == 0 {
                return Err(InjectError::ExitCode);
            }
            code
        };
        finish_code(self.import_activated, code).map(Some)
    }

    /// [`ProcessTree::try_wait`], waiting up to `timeout` for the tree to end.
    pub fn wait_timeout(&mut self, timeout: Duration) -> Result<Option<i32>, InjectError> {
        self.wait_until(Instant::now().checked_add(timeout))
    }

    /// Waits for every process in the tree to exit, and returns the first
    /// process's exit code.
    pub fn wait(&mut self) -> Result<i32, InjectError> {
        match self.wait_until(None)? {
            Some(code) => Ok(code),
            None => Err(InjectError::Wait),
        }
    }

    /// `deadline: None` waits forever.
    fn wait_until(&mut self, deadline: Option<Instant>) -> Result<Option<i32>, InjectError> {
        loop {
            if let Some(code) = self.try_wait()? {
                return Ok(Some(code));
            }
            let slice = match deadline {
                Some(d) => {
                    let left = d.saturating_duration_since(Instant::now());
                    if left.is_zero() {
                        return Ok(None);
                    }
                    left.min(WAIT_SLICE)
                }
                None => WAIT_SLICE,
            };
            let ms = u32::try_from(slice.as_millis()).unwrap_or(u32::MAX).max(1);
            match &self.shared.job {
                Some(job) => job.wake_or_timeout(ms),
                // SAFETY: a live process handle owned by `Shared`.
                None => unsafe {
                    WaitForSingleObject(self.shared.process, ms);
                },
            }
        }
    }
}

/// How a target's exit code reads as a launch result.
///
/// An import-activated target that the loader refused to start (the shim
/// missing, `0xC0000135`, or its `DllMain` failing, `0xC0000142`) ran nothing
/// of the program: that is a failed launch, not an exit code.
pub(crate) fn finish_code(import_activated: bool, code: u32) -> Result<i32, InjectError> {
    match code {
        0xC000_0135 | 0xC000_0142 if import_activated => Err(InjectError::TargetExited(code)),
        c => Ok(c as i32),
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn a_loader_refusal_is_an_error_only_for_an_import_activated_target() {
        assert!(matches!(
            finish_code(true, 0xC000_0135),
            Err(InjectError::TargetExited(0xC000_0135))
        ));
        assert!(matches!(
            finish_code(true, 0xC000_0142),
            Err(InjectError::TargetExited(0xC000_0142))
        ));
        assert_eq!(
            finish_code(false, 0xC000_0135).unwrap(),
            0xC000_0135u32 as i32
        );
        assert_eq!(finish_code(true, 0).unwrap(), 0);
        assert_eq!(finish_code(true, 7).unwrap(), 7);
    }

    #[test]
    fn a_tree_and_its_killer_cross_threads() {
        fn send_sync<T: Send + Sync>() {}
        fn send<T: Send>() {}
        send::<ProcessTree>();
        send_sync::<TreeKiller>();
    }
}
