//! The Windows launch handle: a launched program and everything it starts,
//! held in a Job Object by `vfs_inject::spawn_target_with_shim`.

use std::path::PathBuf;
use std::sync::atomic::{AtomicBool, Ordering};
use std::sync::{Arc, Mutex};
use std::time::Duration;

use vfs_inject::{ProcessTree, TreeKiller};

use crate::session::{LaunchExit, STOPPED_EXIT_CODE};
#[cfg(doc)]
use crate::session::{LaunchOpts, Session};

/// How long [`LaunchHandle::stop`] waits for the job to empty after
/// terminating it.
const STOP_WAIT: Duration = Duration::from_secs(30);

/// A running Windows launch, from [`Session::launch_detached`].
///
/// **Liveness follows the process tree, not the first process.** The program
/// is put in a Job Object before any of its code runs, and every process it
/// starts inherits the job; a launch runs until the job has no process left.
/// A launcher that starts the game and exits (`skse64_loader.exe` starts
/// `SkyrimSE.exe`) therefore stays running for as long as the game does:
/// [`LaunchHandle::try_wait`] and [`LaunchHandle::is_running`] (non-blocking)
/// and [`LaunchHandle::wait`] (blocking) all see the job, and
/// [`LaunchHandle::stop`] and [`Session::stop_launch`] end everything in it.
/// The exit code reported is the launched program's (the first process's);
/// [`LaunchExit::Stopped`] when a stop was requested. This is the Proton
/// handle's contract, with the job in place of the Wine prefix.
///
/// No process leaves the job: the job does not allow breakaway, and the
/// shim's process hook drops `CREATE_BREAKAWAY_FROM_JOB` from every child it
/// creates, so a child that asks to break away is waited for and stopped like
/// any other (an escaped one could go on doing file I/O through the VFS after
/// the launch has ended). A child the shim refuses
/// (it could not inject it) is killed at once and never holds the launch
/// open; [`LaunchHandle::notes`] lists it. A program that could not be put in
/// a job at all is tracked alone, and the notes say so.
///
/// Dropping it while the program runs stops the program.
pub struct LaunchHandle {
    pub(super) tree: ProcessTree,
    pub(super) stopper: LaunchStopper,
    /// Where the shim lists the children it refused, one per line.
    pub(super) refused_log: PathBuf,
    /// How the launch ended, once it has.
    pub(super) outcome: Option<Result<LaunchExit, String>>,
}

/// Stops a running Windows launch from any thread: see
/// [`LaunchHandle::stopper`].
#[derive(Clone, Debug)]
pub struct LaunchStopper(pub(super) Arc<StopInner>);

#[derive(Debug)]
pub(super) struct StopInner {
    pub(super) killer: TreeKiller,
    pub(super) requested: AtomicBool,
    /// Set once the launch has been reaped (concluded or dropped). A stop
    /// after that point is a no-op and does not count as a request: the
    /// launch ended by itself. Shares one mutex with [`LaunchStopper::stop`]'s
    /// check-then-act, as on the Proton side.
    pub(super) ended: Mutex<bool>,
}

impl LaunchStopper {
    pub(super) fn new(killer: TreeKiller) -> Self {
        LaunchStopper(Arc::new(StopInner {
            killer,
            requested: AtomicBool::new(false),
            ended: Mutex::new(false),
        }))
    }

    /// Whether the launch has ended — see [`StopInner::ended`].
    pub(super) fn has_ended(&self) -> bool {
        self.0.ended.lock().map(|e| *e).unwrap_or(true)
    }

    /// Stops the launch: terminates its job, which ends the program and
    /// every process it started. Does not wait; [`LaunchHandle::stop`] does.
    ///
    /// A no-op, `Ok(())`, once the launch has already ended.
    pub fn stop(&self) -> Result<(), String> {
        let ended = self
            .0
            .ended
            .lock()
            .map_err(|_| "stop: launch-ended lock poisoned".to_string())?;
        if *ended {
            return Ok(());
        }
        self.0.requested.store(true, Ordering::SeqCst);
        let result = self
            .0
            .killer
            .kill(STOPPED_EXIT_CODE as u32)
            .map_err(|e| format!("stop: {e:?}"));
        drop(ended);
        result
    }

    /// Whether [`LaunchStopper::stop`] has been called.
    pub fn was_stopped(&self) -> bool {
        self.0.requested.load(Ordering::SeqCst)
    }

    /// Marks the launch ended — see [`StopInner::ended`]. Idempotent.
    fn mark_ended(&self) {
        if let Ok(mut ended) = self.0.ended.lock() {
            *ended = true;
        }
    }
}

impl LaunchHandle {
    /// The launched program's process id: the first process of the tree.
    /// (On Proton, `LaunchHandle::pid` is the `wine` process's.)
    pub fn pid(&self) -> u32 {
        self.tree.pid()
    }

    /// What the launch has to say, one line each: that it tracks only the
    /// program and not what it starts (it could not be put in a job), then
    /// one line per child process the shim refused (killed because it could
    /// not inject it; its `CreateProcess` failed). Read again on each call.
    pub fn notes(&self) -> Vec<String> {
        let mut notes: Vec<String> = self
            .tree
            .untracked_reason()
            .map(str::to_string)
            .into_iter()
            .collect();
        notes.extend(refusal_notes(
            &std::fs::read_to_string(&self.refused_log).unwrap_or_default(),
        ));
        notes
    }

    /// A stopper for this launch, to stop it from another thread while this
    /// handle is being waited on.
    pub fn stopper(&self) -> LaunchStopper {
        self.stopper.clone()
    }

    /// Whether the launch is still running: a process is left in its job.
    /// Never blocks.
    pub fn is_running(&mut self) -> bool {
        matches!(self.poll(), Ok(None))
    }

    /// How the launch ended, if it has — see [`LaunchHandle`]. Never blocks.
    pub fn try_wait(&mut self) -> Result<Option<LaunchExit>, String> {
        self.poll()
    }

    /// Waits for the launch to end: for the program and everything it
    /// started to exit.
    pub fn wait(mut self) -> Result<LaunchExit, String> {
        self.block()
    }

    /// Stops the launch ([`LaunchStopper::stop`]) and waits for its job to
    /// empty, up to 30 s.
    pub fn stop(mut self) -> Result<LaunchExit, String> {
        let stopped = self.stopper.stop();
        let exit = match &self.outcome {
            Some(outcome) => outcome.clone(),
            None => match self.tree.wait_timeout(STOP_WAIT) {
                Ok(Some(code)) => self.conclude(Ok(code)),
                Ok(None) => Err(format!(
                    "stop: the launch still has processes running {} s after its job was \
                     terminated",
                    STOP_WAIT.as_secs()
                )),
                Err(e) => self.conclude(Err(e)),
            },
        };
        stopped?;
        exit
    }

    /// One non-blocking step.
    fn poll(&mut self) -> Result<Option<LaunchExit>, String> {
        if let Some(outcome) = &self.outcome {
            return outcome.clone().map(Some);
        }
        match self.tree.try_wait() {
            Ok(None) => Ok(None),
            Ok(Some(code)) => self.conclude(Ok(code)).map(Some),
            Err(e) => self.conclude(Err(e)).map(Some),
        }
    }

    /// [`Self::poll`], blocking until the launch has ended.
    pub(super) fn block(&mut self) -> Result<LaunchExit, String> {
        if let Some(outcome) = &self.outcome {
            return outcome.clone();
        }
        let result = self.tree.wait();
        self.conclude(result)
    }

    /// The job is empty: decide how the launch ended, record it, and mark it
    /// ended.
    fn conclude(
        &mut self,
        result: Result<i32, vfs_inject::InjectError>,
    ) -> Result<LaunchExit, String> {
        let was_stopped = self.stopper.was_stopped();
        self.stopper.mark_ended();
        let outcome = if was_stopped {
            Ok(LaunchExit::Stopped)
        } else {
            result
                .map(LaunchExit::Exited)
                .map_err(|e| format!("launch: {e:?}"))
        };
        self.outcome = Some(outcome.clone());
        outcome
    }
}

impl Drop for LaunchHandle {
    /// A handle dropped while its launch runs stops it: nothing could stop
    /// it afterwards. Marks the launch ended either way.
    fn drop(&mut self) {
        if matches!(self.poll(), Ok(None)) {
            let _ = self.stopper.stop();
        }
        self.stopper.mark_ended();
    }
}

/// The shim's refused-children log (`<image> <reason>` per line) as notes.
pub(super) fn refusal_notes(log: &str) -> Vec<String> {
    log.lines()
        .map(str::trim)
        .filter(|l| !l.is_empty())
        .map(|l| {
            format!(
                "a child process was refused and killed because the shim could not inject it \
                 (`<image> <reason>`): {l}"
            )
        })
        .collect()
}
