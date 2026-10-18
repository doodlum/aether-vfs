//! The Windows serve and launch: a named-section ring, the shim injected by
//! `vfs-inject`, and the child's ring coordinates inherited through the
//! process environment.

use std::path::PathBuf;
use std::sync::atomic::{AtomicBool, Ordering};
use std::sync::{Arc, Mutex, Weak};
use std::time::Duration;

use vfs_director::ipc::IpcServe;

use super::stage::ResolvedImage;
use super::{check_image, LaunchExit, LaunchOpts, Session, StartingGuard, STOPPED_EXIT_CODE};

mod handle;

use handle::{refusal_notes, StopInner};
pub use handle::{LaunchHandle, LaunchStopper};

/// What a session holds for its Windows launch. A session runs one launch at
/// a time, as on Proton (`ProtonState`, whose fields these mirror).
#[derive(Default)]
pub(in crate::session) struct WindowsState {
    /// The launch a `wait: false` [`Session::launch`] started, held because
    /// dropping a [`LaunchHandle`] stops its launch: until it is stopped,
    /// replaced by a later launch after it ended, or the session drops (which
    /// stops it).
    detached: Mutex<Option<LaunchHandle>>,
    /// Set for the span of a [`Session::launch_detached`] call, from just
    /// after it refuses a second launch to just before it returns, when no
    /// one is yet seen to hold the handle. A `launch_detached` that finds it
    /// set (another one is mid-flight) refuses too.
    starting: AtomicBool,
    /// Set by [`Session::stop_launch`] when it finds `starting` set and no
    /// launch recorded yet; `launch_detached` honours it the moment it has a
    /// handle, so the request is not lost to that race.
    stop_pending: AtomicBool,
    /// The most recent launch's stop state, weakly: whoever holds the launch
    /// — `detached`, a waiting `launch`, or a caller of
    /// [`Session::launch_detached`] that kept its [`LaunchHandle`] — this
    /// sees it while it runs. A new launch is refused while it has not
    /// ended, [`Session::stop_launch`] stops it, and dropping the session
    /// stops it before the ring goes.
    latest: Mutex<Weak<StopInner>>,
}

/// How long the injector waits for the shim to report ready when neither
/// [`LaunchOpts::ready_timeout`] nor `VFS_READY_TIMEOUT_SECS` says: the default
/// `vfs-injector.exe` itself uses.
const DEFAULT_READY_TIMEOUT: Duration = Duration::from_secs(vfs_env::DEFAULT_READY_TIMEOUT_SECS);

impl Session {
    /// The declared roots beyond root 0, as `IpcServe::env_roots` wants them.
    ///
    /// No `id != 0` filter: [`Session::declare_root`] routes id 0 to
    /// `virtual_root`, which `env_roots` is handed separately, so
    /// nothing here can be root 0; filtering id 0 here would silently discard a
    /// `declare_root(0, …)`, so the invariant is enforced in one place instead
    /// of compensated for.
    ///
    /// Windows-only: its one caller is `launch`'s `env_roots`, which is
    /// itself `#[cfg(windows)]` — the named-section handshake
    /// (`VFS_RING_SECTION` plus the two event names). The file-backed ring has
    /// its own env protocol, published into the **child's** environment by
    /// `vfs_proton::launch::launch_env` rather than into this process's, and
    /// the unix `launch` hands the child its roots' **locations** through
    /// `WineLaunch::virtual_roots` instead.
    #[cfg(windows)]
    fn extra_roots_env(&self) -> Vec<(u32, String)> {
        debug_assert!(
            !self.extra_roots.iter().any(|(id, _)| *id == 0),
            "root 0 belongs in virtual_root, not extra_roots"
        );
        self.extra_roots
            .iter()
            .map(|(id, p)| (*id, p.to_string_lossy().into_owned()))
            .collect()
    }

    /// Start the control ring + workers so an injected child can remap I/O.
    /// Idempotent if already serving.
    ///
    /// This body is Windows-only because of *what it does*, not because the
    /// ring is missing elsewhere: it creates a named section, an event pair and
    /// a thin config, and publishes `VFS_RING_SECTION` — the named-section half
    /// of `vfs_director::ipc`. The unix body below starts the file-backed ring
    /// instead. The signature is identical on both targets so a host compiles
    /// unchanged.
    #[cfg(windows)]
    pub fn serve(&mut self) -> Result<(), String> {
        if !self.begin_serve()? {
            return Ok(());
        }

        let section = format!(
            "Local\\vfs_ring_{}_{}",
            std::process::id(),
            std::time::SystemTime::now()
                .duration_since(std::time::UNIX_EPOCH)
                .map(|d| d.as_millis())
                .unwrap_or(0)
        );
        let ipc =
            IpcServe::start_with_workers(Arc::clone(&self.kernel), section, self.io_workers())?;
        let root_s = self.virtual_root.to_string_lossy().into_owned();
        let thin = self.state_dir.join("fuse.cfg");
        ipc.write_thin_config(&thin, &root_s)?;

        // No `shim.cfg` here, and nothing in this process's environment:
        // `launch` writes the config, and builds the child's environment
        // block, from the roots as they are then.

        self.ipc = Some(ipc);
        Ok(())
    }

    /// Launch `opts.image` under the virtual root with dual-layer inject.
    /// Child sees remapped I/O for paths under `virtual_root`.
    ///
    /// Requires [`serve`] first.
    ///
    /// ## How long a launch lasts
    ///
    /// Until the program **and every process it started** have exited: the
    /// program is put in a Job Object before it runs, and its children
    /// inherit the job (see [`LaunchHandle`]). A launcher that starts the game
    /// and exits keeps the launch running until the game exits. The exit code
    /// returned is the program's own (the first process's).
    ///
    /// `wait: false` returns `Ok(0)` once the program is running, and the
    /// session holds the launch: [`Session::stop_launch`] stops it, and
    /// dropping the session stops it. A launch that is waited on can be
    /// stopped from another thread with [`Session::stop_launch`] too; it then
    /// returns `Ok(`[`STOPPED_EXIT_CODE`]`)`. A host that wants the handle
    /// itself calls [`Session::launch_detached`].
    ///
    /// ## One launch at a time
    ///
    /// As on Proton, a session runs one launch at a time: while one is
    /// running (waited on, held for `wait: false`, or a [`LaunchHandle`] a
    /// caller kept that has not been seen to end), `launch` and
    /// [`Session::launch_detached`] are refused with "a launch is already
    /// running in this session — stop_launch() first". Refused before
    /// anything is resolved or staged, so the running launch's staged image
    /// is left alone.
    ///
    /// ## How `image` is resolved
    ///
    /// By [`crate::image::classify_image`] against [`Session::root_locations`]
    /// (see [`LaunchOpts::image`]), in one of three forms:
    ///
    /// - **Relative**: a vpath in root 0. The real file under the virtual
    ///   root is launched if there is one (a host whose root is a real game
    ///   directory — fixtures, a vanilla install); otherwise, if root 0's
    ///   **provider graph** serves the vpath, it is staged — see below.
    /// - **Absolute, inside a root's location**: that root's vpath, resolved
    ///   the same way — the real file in the root's directory, else staged
    ///   (root 0 only; another root's graph-only image is refused by name).
    /// - **Absolute, outside every root**: launched as given.
    ///
    /// An in-root image that is neither a real file nor served is refused by
    /// name, because `CreateProcess` would only fail later and less clearly.
    ///
    /// ## Launching an image that is VFS content
    ///
    /// `CreateProcess` reads the image off the filesystem before any hook of
    /// ours is installed in the child, and the Windows loader resolves its
    /// static imports in the same window. So an exe that only the provider
    /// graph holds — a game served out of archives into a deliberately empty
    /// managed root — is written to disk first, with its PE import closure,
    /// and the staging directory is mounted back into the graph *underneath*
    /// the curated content so the same bytes stay answerable at their vpath.
    /// [`Session::stage_launch`] is that sequence and this method calls it;
    /// [`LaunchOpts::stage_also`] and [`LaunchOpts::stage_fallback_dirs`] are
    /// its two knobs.
    ///
    /// The staged directory is held by the session (`CreateProcess` keeps the
    /// image mapped for the child's lifetime), so a detached launch
    /// (`wait: false`) requires the session to outlive the child — which it
    /// already did for the ring.
    ///
    /// ## The child's environment
    ///
    /// Built for the child and handed to `CreateProcessW`; this process's
    /// own environment is never written, during the launch or after it. The
    /// block is this process's environment with the ring's `VFS_*` variables
    /// set (any the host carries are replaced or cleared), then
    /// [`LaunchOpts::env`], and without [`LaunchOpts::env_remove`]. So two
    /// sessions in one process each hand their own child their own ring, and
    /// a host's other threads never see the environment change. The shim
    /// reads the same names out of the child's own environment.
    #[cfg(windows)]
    pub fn launch(&self, opts: &LaunchOpts) -> Result<i32, String> {
        let mut handle = self.launch_detached(opts)?;
        if !opts.wait {
            *self
                .windows
                .detached
                .lock()
                .map_err(|_| "detached launch lock poisoned".to_string())? = Some(handle);
            return Ok(0);
        }
        let exit = handle.block();
        drop(handle);
        Ok(match exit? {
            LaunchExit::Exited(code) => code,
            LaunchExit::Stopped => STOPPED_EXIT_CODE,
        })
    }

    /// Windows: [`Session::launch`] without waiting, handing the running
    /// launch back once the program is running with its shim up.
    /// `opts.wait` is ignored. Dropping the handle while the program runs
    /// stops it — keep it (and this session, whose ring the program reads
    /// through) for as long as the program should run.
    ///
    /// The same call, with the same handle API, as on Proton, and refused
    /// the same way while another launch of this session runs (see
    /// [`Session::launch`]).
    #[cfg(windows)]
    pub fn launch_detached(&self, opts: &LaunchOpts) -> Result<LaunchHandle, String> {
        let ipc = self.require_serving()?;
        check_image(opts)?;
        let _starting = self.begin_launch()?;

        // Root 0 may have been declared after `serve`, which created the
        // managed root it had then; resolving (and staging) needs this one.
        std::fs::create_dir_all(&self.virtual_root)
            .map_err(|e| format!("launch: create root {}: {e}", self.virtual_root.display()))?;
        let target = match self.resolve_launch_image(opts)? {
            ResolvedImage::InRoot { host, .. } => host,
            ResolvedImage::Outside(p) => PathBuf::from(p),
        };
        // `serve` wrote the thin config from root 0's location as it was then;
        // root 0 may have been declared since. Write `shim.cfg` and rewrite the
        // thin config from the current one so the shim is told the root this
        // child sees.
        let root_s = self.virtual_root.to_string_lossy().into_owned();
        let config_path = self.write_shim_config(&root_s)?;
        let thin = self.state_dir.join("fuse.cfg");
        ipc.write_thin_config(&thin, &root_s)?;
        let ready_path = self.fresh_ready_flag();

        let dll = locate_shim(opts)?;
        // Remote LoadLibrary resolves relative to the *child* cwd (managed root,
        // which is intentionally empty). Always use absolute DLL paths.
        // Strip the `\\?\` verbatim prefix — some LoadLibrary paths reject it.
        let strip_verbatim =
            |s: String| s.strip_prefix(r"\\?\").map(|t| t.to_string()).unwrap_or(s);
        let dll = strip_verbatim(
            std::fs::canonicalize(&dll)
                .map(|p| p.to_string_lossy().into_owned())
                .unwrap_or(dll),
        );
        let config_path_s = strip_verbatim(
            std::fs::canonicalize(&config_path)
                .unwrap_or(config_path.clone())
                .to_string_lossy()
                .into_owned(),
        );
        let ready_path_s = ready_path.to_string_lossy().into_owned();

        // The child's environment: this process's own, which is never
        // written, with the ring's variables set or cleared (so nothing the
        // host carries can repoint the child), then `opts.env`, and without
        // `opts.env_remove`. Built per launch, so a root declared after
        // `serve`, or a second session in this process, cannot leak into it.
        let mut env_set: Vec<(String, String)> = Vec::new();
        let mut env_remove: Vec<String> = opts.env_remove.clone();
        for (name, value) in ipc.env_roots(
            &root_s,
            &self.extra_roots_env(),
            &thin,
            self.registry_attached(),
        ) {
            match value {
                Some(v) => env_set.push((name.to_string(), v)),
                None => env_remove.push(name.to_string()),
            }
        }
        env_set.extend(opts.env.iter().map(|(k, v)| (k.clone(), v.clone())));

        let ready_timeout = opts.ready_timeout.unwrap_or_else(|| {
            vfs_env::text(vfs_env::READY_TIMEOUT_SECS)
                .and_then(|s| s.parse().ok())
                .map(Duration::from_secs)
                .unwrap_or(DEFAULT_READY_TIMEOUT)
        });

        let spawned = vfs_inject::spawn_target_with_shim(vfs_inject::RunConfig {
            target_exe: target.to_string_lossy().into_owned(),
            args: opts.args.clone(),
            current_dir: Some(root_s),
            dll_path: dll,
            config_path: config_path_s,
            ready_path: ready_path_s.clone(),
            ready_timeout,
            detach: true,
            env_set,
            env_remove,
        });

        // Children the shim killed because it could not inject them, one
        // `<image> <reason>` line each, beside the ready file.
        let refused_log = PathBuf::from(format!("{ready_path_s}{}", vfs_env::CHILD_REFUSED_SUFFIX));
        let tree = spawned.map_err(|e| {
            let refused = std::fs::read_to_string(&refused_log).unwrap_or_default();
            let mut msg = format!("launch: {e:?}");
            for note in refusal_notes(&refused) {
                msg.push('\n');
                msg.push_str(&note);
            }
            msg
        })?;

        let stopper = LaunchStopper::new(tree.killer());
        let handle = LaunchHandle {
            tree,
            stopper,
            refused_log,
            outcome: None,
        };
        // Before `starting` clears (the guard drops on return), so there is
        // no moment in which a second launch sees neither.
        *self
            .windows
            .latest
            .lock()
            .map_err(|_| "latest launch lock poisoned".to_string())? =
            Arc::downgrade(&handle.stopper.0);
        if self.windows.stop_pending.swap(false, Ordering::SeqCst) {
            // `stop_launch` ran while this launch was between spawning and
            // being recorded, found nothing to act on, and left this instead
            // of losing the request.
            let _ = handle.stop();
            return Err(
                "launch: stopped during startup (stop_launch was called before the launch \
                 finished starting)"
                    .to_string(),
            );
        }
        Ok(handle)
    }

    /// Refuses a second launch while one runs, and claims "a
    /// `launch_detached` is in flight" until the returned guard drops — the
    /// Proton session's `begin_launch`, without its prefix.
    ///
    /// Refused up front, before `resolve_launch_image` can stage anything:
    /// staging replaces `self.staged`, and the old `StagedDir`'s `Drop`
    /// deletes the running launch's staged files out from under it.
    /// `starting.swap` both checks and claims in one step, so two calls
    /// racing each other can't both pass.
    #[cfg(windows)]
    fn begin_launch(&self) -> Result<StartingGuard<'_>, String> {
        self.reap_detached();
        if self
            .windows
            .detached
            .lock()
            .map_err(|_| "detached launch lock poisoned".to_string())?
            .is_some()
            // A waiting `launch`, or a handle `launch_detached` handed back
            // and its caller kept: `latest` sees both.
            || self.latest_running().is_some()
            || self.windows.starting.swap(true, Ordering::SeqCst)
        {
            return Err(
                "launch: a launch is already running in this session — stop_launch() first"
                    .to_string(),
            );
        }
        Ok(StartingGuard {
            starting: &self.windows.starting,
            stop_pending: &self.windows.stop_pending,
        })
    }

    /// Windows: stops the session's running launch — the one a `wait: false`
    /// [`Session::launch`] started, the one a waiting `launch` on another
    /// thread is blocked on (it then returns `Ok(`[`STOPPED_EXIT_CODE`]`)`),
    /// or a handle a caller of [`Session::launch_detached`] kept — by
    /// terminating its job, which ends the program and everything it started.
    /// A held `wait: false` launch is also waited for, up to 30 s, so a launch
    /// that follows does not overlap it. `Ok(false)` when nothing was running.
    ///
    /// A launch between spawning and being recorded (inside
    /// [`Session::launch_detached`], before it returns) has no handle yet for
    /// this to reach: that window returns `Ok(false)` too, but leaves a
    /// pending-stop flag `launch_detached` checks right after spawning, so the
    /// request still lands.
    #[cfg(windows)]
    pub fn stop_launch(&self) -> Result<bool, String> {
        let detached = self
            .windows
            .detached
            .lock()
            .map_err(|_| "detached launch lock poisoned".to_string())?
            .take();
        if let Some(mut h) = detached {
            if h.is_running() {
                h.stop()?;
                return Ok(true);
            }
        }
        // A waiting `launch`, or a handle the caller of `launch_detached` kept.
        if let Some(stopper) = self.latest_running() {
            return stopper.stop().map(|()| true);
        }
        if self.windows.starting.load(Ordering::SeqCst) {
            self.windows.stop_pending.store(true, Ordering::SeqCst);
        }
        Ok(false)
    }

    /// The most recent launch, if it has not ended — see
    /// [`WindowsState::latest`].
    #[cfg(windows)]
    fn latest_running(&self) -> Option<LaunchStopper> {
        let inner = self.windows.latest.lock().ok()?.upgrade()?;
        let stopper = LaunchStopper(inner);
        (!stopper.has_ended()).then_some(stopper)
    }

    /// Drops the held `wait: false` launch if it has ended.
    #[cfg(windows)]
    fn reap_detached(&self) {
        if let Ok(mut d) = self.windows.detached.lock() {
            if d.as_mut().is_some_and(|h| !h.is_running()) {
                *d = None;
            }
        }
    }
}

// Only `launch`'s Windows body calls this, so it is gated alongside it.
#[cfg(windows)]
pub(super) fn locate_shim(opts: &LaunchOpts) -> Result<String, String> {
    let text = |p: &std::path::Path| p.to_string_lossy().into_owned();
    if let Some(d) = &opts.shim_dll {
        return Ok(text(d));
    }
    let exe = std::env::current_exe().map_err(|e| e.to_string())?;
    vfs_inject::find_near(&exe, "vfs_shim_dll.dll")
        .map(|p| p.to_string_lossy().into_owned())
        .ok_or_else(|| "vfs_shim_dll.dll not found (set LaunchOpts.shim_dll)".to_string())
}
