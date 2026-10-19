//! Host session: configure mounts + paths, serve IPC, **launch a process** with
//! all NT I/O under the virtual root remapped through this director.

use std::collections::BTreeMap;
use std::path::{Path, PathBuf};
use std::sync::{Arc, Mutex};

use crate::image::RootLocation;
// `vfs_director::ipc` is portable, and `Session` uses **both** of its halves:
// the named-section handshake on Windows (`IpcServe::start`, the event pair,
// `write_thin_config`, `env_roots`) and the file-backed ring on unix
// (`IpcServe::start_file_backed`), which is how a shim inside Wine reaches a
// native Linux director. So neither this import nor the `ipc` field below is
// gated; only the two bodies that pick a transport are.
use vfs_director::ipc::IpcServe;
use vfs_director::stage::StagedDir;
use vfs_director::Director;
use vfs_provider::{overlay_layer_dir, RootId};

mod compose;
mod opts;
#[cfg(unix)]
mod proton;
mod read;
mod registry;
mod stage;
#[cfg(windows)]
mod windows;

pub use compose::compose_root;
pub use opts::{Activation, LaunchOpts, StageOpts};
#[cfg(unix)]
pub use proton::{LaunchHandle, LaunchStopper};
pub use registry::RegistrySync;
#[cfg(windows)]
pub use windows::{LaunchHandle, LaunchStopper};

use compose::RootComposition;
#[cfg(unix)]
use proton::ProtonState;
#[cfg(windows)]
use windows::WindowsState;

/// Host entrypoint: one configured director + optional IPC + launch.
///
/// Typical use:
/// 1. `Session::new` + `set_root` / `set_overlay` / `set_state_dir`
/// 2. `mount` backends (zip/disk/C)
/// 3. `serve` — start ring so the child shim can talk to us
/// 4. `launch` — CreateProcess + inject; child I/O under root is remapped
pub struct Session {
    kernel: Arc<Director>,
    virtual_root: PathBuf,
    overlay: PathBuf,
    state_dir: PathBuf,
    /// The live ring, on both targets: a named section on Windows, a real file
    /// under `state_dir` on unix. One field rather than a `bool` beside it,
    /// because [`Session::is_serving`] and [`Session::stop_serve`] then have
    /// one thing to consult and cannot disagree with each other by target.
    ipc: Option<IpcServe>,
    /// Per-root composition inputs, keyed by the raw `u32` a `RootId` wraps.
    /// `Director` holds exactly one provider per root rather than a mergeable
    /// list, so every change to a root's inputs recomposes that root whole
    /// (see [`Session::recompose`]).
    ///
    /// **This is the one place a session's provider graph is composed**, for
    /// every root and for every host: `Session::mount`'s single-root
    /// convenience and [`Session::set_root_mounts`] (which a host adding
    /// sources one at a time drives, root by root) both land here. Composing anywhere else
    /// — calling `kernel().mount` with a hand-built graph — silently drops
    /// whatever the *other* half of the composition contributed (copy-on-write,
    /// in practice).
    roots: Mutex<BTreeMap<u32, RootComposition>>,
    /// The session's roots **beyond root 0**, as declared — see
    /// [`Session::declare_root`]. On Windows each is the host directory the
    /// root virtualizes; on unix it is the root's **location** inside the Wine
    /// prefix (`C:\…`), backed by `state_dir/roots/<id>`.
    extra_roots: Vec<(u32, PathBuf)>,
    /// Ring serve threads [`Session::serve`] starts; `None` is
    /// `vfs_director::ipc::DEFAULT_IO_WORKERS`.
    io_workers: Option<usize>,
    /// Everything the session holds only for the Proton (Wine) delivery.
    #[cfg(unix)]
    proton: ProtonState,
    /// Root 0's **location** on Windows, when declared apart from its backing
    /// directory (`virtual_root`): the path the launched program sees it at,
    /// which nothing creates, opens or writes on real disk. `None`: root 0's
    /// location is `virtual_root` itself, as before roots could be purely
    /// virtual.
    #[cfg(windows)]
    root0_location: Option<PathBuf>,
    /// The launch a Windows session runs (one at a time), for `stop_launch` and `Drop`.
    #[cfg(windows)]
    windows: WindowsState,
    /// The most recent staged launch directory, held here because
    /// [`StagedDir`]'s `Drop` removes the staged files — not the virtual root
    /// they now live in — and Windows keeps the image file mapped for as long
    /// as the child runs. Dropped with the session, or replaced by the next
    /// staged launch.
    staged: Mutex<Option<StagedDir>>,
}

impl Session {
    /// A session with default `root`/`overlay`/`state` directories under
    /// `%TEMP%`, which a host normally replaces via
    /// [`Session::set_root`] / [`Session::set_overlay`] /
    /// [`Session::set_state_dir`].
    ///
    /// The defaults are unique per session **and cleared on the way in**, for
    /// the reason spelled out on [`Session::set_overlay`]: every component of
    /// a temp name repeats across runs (the OS recycles pids; the counter
    /// below restarts at zero in each process) and nothing deletes a session's
    /// directory when its owner dies, so an inherited `overlay/root-0` breaks
    /// the "the overlay is empty afterwards" check this project uses to detect
    /// a write that bypassed the director. Warning hosts about that while
    /// shipping a default that has it would be advice this crate does not take
    /// itself. The path is this session's alone, so clearing it cannot destroy
    /// anything a caller put there — it has had no chance to.
    pub fn new() -> Self {
        static SEQ: std::sync::atomic::AtomicU64 = std::sync::atomic::AtomicU64::new(0);
        let seq = SEQ.fetch_add(1, std::sync::atomic::Ordering::Relaxed);
        let tmp = std::env::temp_dir().join(format!("vfs-session-{}-{seq}", std::process::id()));
        let _ = std::fs::remove_dir_all(&tmp);
        Session {
            kernel: Arc::new(Director::new()),
            virtual_root: tmp.join("root"),
            overlay: tmp.join("overlay"),
            state_dir: tmp.join("state"),
            ipc: None,
            roots: Mutex::new(BTreeMap::new()),
            extra_roots: Vec::new(),
            io_workers: None,
            #[cfg(unix)]
            proton: ProtonState::default(),
            #[cfg(windows)]
            windows: WindowsState::default(),
            #[cfg(windows)]
            root0_location: None,
            staged: Mutex::new(None),
        }
    }

    /// The number of ring serve threads [`Session::serve`] starts, from the
    /// next `serve` on. The default is `vfs_director::ipc::DEFAULT_IO_WORKERS`
    /// (4); the count is clamped to 1..=32 (the ring's slot count).
    ///
    /// A serve thread answers one request at a time, so a request whose
    /// provider blocks — a cold miss behind a network-backed cache — holds a
    /// thread until it returns, and once every thread is held the program's
    /// I/O stops, cache hits included. Raise this above the number of slow
    /// misses a host expects at once. Each idle thread costs a little CPU:
    /// it spins briefly after activity before it sleeps.
    pub fn set_io_workers(&mut self, n: usize) {
        self.io_workers = Some(n);
    }

    /// The serve thread count the next [`Session::serve`] uses, clamped.
    pub fn io_workers(&self) -> usize {
        vfs_director::ipc::clamp_workers(
            self.io_workers
                .unwrap_or(vfs_director::ipc::DEFAULT_IO_WORKERS),
        )
    }

    pub fn kernel(&self) -> &Arc<Director> {
        &self.kernel
    }

    pub fn set_root(&mut self, path: impl Into<PathBuf>) {
        self.virtual_root = path.into();
    }

    /// Where the injected shim's local write overlay lands on disk.
    ///
    /// [`Session::serve`] creates this directory; it does **not** empty it,
    /// and nothing removes it when the process that owned it dies. A host that
    /// derives the path from anything repeatable — a pid, a counter that
    /// restarts at zero, a fixed name — will eventually hand a new session a
    /// previous run's overlay.
    ///
    /// That is not housekeeping. "The overlay is empty afterwards" is how this
    /// project detects a write that bypassed the director, so inherited
    /// content fails that check with nothing having actually fallen through —
    /// and, worse in the other direction, a real bypass gets dismissed as
    /// leftovers. [`Session::new`]'s defaults are cleared on the way in for
    /// exactly this reason. A host picking
    /// its own directories inherits the hazard along with the choice.
    pub fn set_overlay(&mut self, path: impl Into<PathBuf>) {
        self.overlay = path.into();
    }

    pub fn set_state_dir(&mut self, path: impl Into<PathBuf>) {
        self.state_dir = path.into();
    }

    pub fn virtual_root(&self) -> &Path {
        &self.virtual_root
    }

    /// The physical subdirectory of this session's overlay that `root`'s
    /// writes actually land in — see `vfs_provider::overlay_layer_dir`, which
    /// this delegates to.
    ///
    /// A host mounting its *own* read layer over the overlay directory
    /// (e.g. a `DiskProvider`, so the director sees content the shim's
    /// overlay has written — see `vfs-bench/src/bin/skyrim-live.rs`)
    /// must mount exactly this path, not [`Session::set_overlay`]'s bare
    /// path: the shim's overlay is root-scoped on disk, so
    /// mounting the bare overlay directory would show nothing the overlay
    /// has actually written, and any writer/reader pair that disagrees on
    /// this path silently desyncs.
    pub fn overlay_layer_dir(&self, root: RootId) -> PathBuf {
        overlay_layer_dir(&self.overlay, root)
    }

    /// Declare a managed root: the **location** `RootId(id)` is seen at by the
    /// launched program — a path it names, and nothing more.
    ///
    /// This is the *shim-facing* half of a multi-root session and it is
    /// separate from mounting a provider on that root
    /// (`kernel().mount(RootId(id), …)`) on purpose, because they answer
    /// different questions: the mount says what root `n` serves, this says
    /// which path the injected process should recognise *as* root `n`.
    /// Declare without mounting and the root serves nothing; mount without
    /// declaring and the shim never classifies any path into that root.
    ///
    /// **A location is purely virtual**, on both targets. Nothing is created,
    /// opened, read, listed or written on real disk at or under it: if a real
    /// directory is there, the program cannot see it and it stays as it is.
    /// What the root holds comes from its providers. Each root also has a real
    /// **backing directory** the host owns: root 0's is [`Session::set_root`]'s
    /// directory, root N's is `state_dir/roots/N`. Staging writes a launched
    /// image there, and on Windows the program is really started there
    /// (`CreateProcess` needs a real image and directory) and then told it is
    /// at its location. Locations may nest — a root inside another root's
    /// location — and the most specific (longest) one wins for every path.
    ///
    /// Root 0 not declared: its location is [`Session::set_root`]'s
    /// directory, which is then both its location and its backing directory,
    /// as before locations could be virtual. On unix, root 0's location is
    /// then `C:\vfs-session\root`.
    ///
    /// Re-declaring an id replaces its location. Takes effect at the next
    /// [`Session::serve`] or [`Session::launch`].
    ///
    /// **On unix a location is a `C:\…` path** inside the Wine prefix, where
    /// `launch` symlinks the backing directory. See [`Session::root_locations`].
    pub fn declare_root(&mut self, id: u32, path: impl Into<PathBuf>) {
        let path = path.into();
        if id == 0 {
            #[cfg(unix)]
            {
                self.proton
                    .set_root0_location(path.to_string_lossy().into_owned());
            }
            #[cfg(windows)]
            {
                self.root0_location = Some(path);
            }
            return;
        }
        match self.extra_roots.iter_mut().find(|(r, _)| *r == id) {
            Some(slot) => slot.1 = path,
            None => self.extra_roots.push((id, path)),
        }
    }

    /// The roots declared beyond root 0, in declaration order. For
    /// diagnostics and for tests that need to prove a config's `[[root]]`
    /// table actually reached the session rather than being parsed and
    /// dropped.
    pub fn declared_roots(&self) -> &[(u32, PathBuf)] {
        &self.extra_roots
    }

    /// Every root's **location** — the path the launched program sees it at —
    /// root 0 first, then the extra roots in declaration order.
    ///
    /// Root 0's is its declared location, else (Windows) `set_root`'s
    /// directory or (unix) `C:\vfs-session\root`; each extra root's is its
    /// declared location. [`crate::image::classify_image`] resolves launch
    /// images against exactly this list.
    pub fn root_locations(&self) -> Vec<RootLocation> {
        #[cfg(unix)]
        let root0 = self.proton.root0_location();
        #[cfg(windows)]
        let root0 = self
            .root0_location
            .as_ref()
            .unwrap_or(&self.virtual_root)
            .to_string_lossy()
            .into_owned();
        std::iter::once(RootLocation {
            id: 0,
            location: root0,
        })
        .chain(self.extra_roots.iter().map(|(id, p)| RootLocation {
            id: *id,
            location: p.to_string_lossy().into_owned(),
        }))
        .collect()
    }

    /// Root 0's location, as [`Session::root_locations`] gives it.
    #[cfg(windows)]
    pub(crate) fn root0_location(&self) -> String {
        self.root_locations().swap_remove(0).location
    }

    /// The real host directory that backs `root`: root 0's is `virtual_root`
    /// ([`Session::set_root`]), a declared extra root's is
    /// `state_dir/roots/<id>`. `None` for an undeclared root.
    pub(super) fn root_backing_dir(&self, root: u32) -> Option<PathBuf> {
        if root == 0 {
            return Some(self.virtual_root.clone());
        }
        self.extra_roots.iter().find(|(id, _)| *id == root)?;
        Some(self.state_dir.join("roots").join(root.to_string()))
    }

    /// Each root's backing directory where it differs from its location —
    /// the second spelling of the root the shim must recognise, since the
    /// launched program is really started there (Windows). Root 0 first.
    #[cfg(windows)]
    pub(crate) fn root_backings(&self) -> Vec<(u32, String)> {
        let locations = self.root_locations();
        locations
            .iter()
            .filter_map(|loc| {
                let backing = self.root_backing_dir(loc.id)?;
                let b = backing.to_string_lossy().into_owned();
                (!b.eq_ignore_ascii_case(&loc.location)).then_some((loc.id, b))
            })
            .collect()
    }

    pub fn state_dir(&self) -> &Path {
        &self.state_dir
    }

    /// Whether IPC workers are running (required before [`Session::launch`]).
    pub fn is_serving(&self) -> bool {
        self.ipc.is_some()
    }

    /// Access the live IPC server (after [`Session::serve`]) for probes / diagnostics.
    ///
    /// Portable, now that both transports are wired: `IpcServe` exists on unix
    /// too, holding a file-backed ring. This is also the honest way to read a
    /// session's ring **geometry** — `map_bytes`, `arena_offset`, `arena_len`,
    /// `payload_cap` are exactly what a child must be told, and a default in
    /// their place is silent at attach and fatal under load (see
    /// `vfs_proton::launch`). A host that needs only *whether* a session is
    /// serving should use [`Session::is_serving`].
    pub fn ipc(&self) -> Option<&IpcServe> {
        self.ipc.as_ref()
    }

    pub fn stop_serve(&mut self) {
        if let Some(ipc) = self.ipc.take() {
            ipc.stop();
        }
        // The workers are gone, so nothing writes registry state any more:
        // the session-end durable point (save, then the layer's sync hook).
        self.flush_registry();
        // After the workers are gone. A child that still maps the ring keeps
        // its pages until it exits; the name is what goes.
        #[cfg(unix)]
        self.proton.release_ring(&self.state_dir);
    }
}

// The steps `serve` and `launch` repeat on both targets. What differs is the
// transport (a named section on Windows, a file-backed ring on unix) and the
// environment the child gets, and those stay in `windows` and `proton`.
impl Session {
    /// The start of both `serve` bodies. `Ok(false)`: the session is already
    /// serving, and `serve` is idempotent. Otherwise the root, overlay and
    /// state directories exist and the caller starts its ring.
    fn begin_serve(&self) -> Result<bool, String> {
        if self.ipc.is_some() {
            return Ok(false);
        }
        std::fs::create_dir_all(&self.virtual_root).map_err(|e| format!("create root: {e}"))?;
        std::fs::create_dir_all(&self.overlay).map_err(|e| format!("create overlay: {e}"))?;
        std::fs::create_dir_all(&self.state_dir).map_err(|e| format!("create state: {e}"))?;
        Ok(true)
    }

    /// The live ring, or the refusal every `launch` gives before `serve`.
    fn require_serving(&self) -> Result<&IpcServe, String> {
        self.ipc
            .as_ref()
            .ok_or_else(|| "serve() before launch()".to_string())
    }

    /// Writes `state_dir/shim.cfg` for the shim: the `root` as the shim sees it (the
    /// versioned `vfs_protocol::shimcfg` format). Returns the file's path.
    fn write_shim_config(&self, root: &str) -> Result<PathBuf, String> {
        let path = self.state_dir.join("shim.cfg");
        std::fs::write(&path, vfs_protocol::shimcfg::encode_config(root))
            .map_err(|e| format!("launch: write {}: {e}", path.display()))?;
        Ok(path)
    }

    /// `state_dir/ready.flag`, removed if an earlier launch left it, so the
    /// injector waits for this launch's shim and not for a stale one.
    fn fresh_ready_flag(&self) -> PathBuf {
        let path = self.state_dir.join("ready.flag");
        let _ = std::fs::remove_file(&path);
        path
    }
}

/// The refusal for a launch that names no image.
fn check_image(opts: &LaunchOpts) -> Result<(), String> {
    if opts.image.trim().is_empty() {
        return Err("LaunchOpts.image is empty — name the image to launch".to_string());
    }
    Ok(())
}

impl Default for Session {
    fn default() -> Self {
        Self::new()
    }
}

impl Drop for Session {
    /// On unix: stop serving, remove the root links `launch` placed in a
    /// prefix (only those still symlinks to what this session linked), and
    /// delete the anonymous prefix this session booted — after stopping its
    /// `wineserver`, which would otherwise write the registry back into it. A named prefix
    /// ([`Session::set_prefix_name`]) is persistent and left alone. Best
    /// effort — a destructor has nowhere to report a failure.
    ///
    /// On Windows: stop the launch if it is still running (the program and
    /// everything it started, by terminating its job), then stop serving.
    fn drop(&mut self) {
        // Unix flushes inside `stop_serve`, below.
        #[cfg(windows)]
        {
            let _ = self.stop_launch();
            self.stop_serve();
        }
        #[cfg(unix)]
        self.drop_proton();
    }
}

/// Resets a session's "a `launch_detached` is in flight" flag to `false` when
/// a [`Session::launch_detached`] call ends, on every path — success or an
/// early `?` return alike — on both targets (`ProtonState::starting`,
/// `WindowsState::starting`). Clears the matching `stop_pending` flag the same
/// way: a `stop_launch` landing while this call is in flight but before it
/// reaches the spawn checkpoint (an early error — a bad image, a prefix that
/// won't `ensure`) would otherwise leave that flag set with nothing left to
/// consume it, and the *next*, unrelated `launch_detached` would spawn its
/// program only to stop it immediately.
pub(in crate::session) struct StartingGuard<'a> {
    pub(in crate::session) starting: &'a std::sync::atomic::AtomicBool,
    pub(in crate::session) stop_pending: &'a std::sync::atomic::AtomicBool,
}

impl Drop for StartingGuard<'_> {
    fn drop(&mut self) {
        self.starting
            .store(false, std::sync::atomic::Ordering::SeqCst);
        self.stop_pending
            .store(false, std::sync::atomic::Ordering::SeqCst);
    }
}

/// What a waited-on [`Session::launch`] returns when
/// [`Session::stop_launch`] ended it: 128 + `SIGKILL`, the shell's
/// convention. Stopping kills the prefix's processes (Proton) or the launch's
/// job (Windows), and the program's own exit code then says nothing reliable
/// (`wine` can exit 0), so the session says so instead.
pub const STOPPED_EXIT_CODE: i32 = 128 + 9;

/// How a launch ended: the whole process tree, on either target.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum LaunchExit {
    /// The program exited by itself, with this code.
    Exited(i32),
    /// [`LaunchStopper::stop`] (or [`LaunchHandle::stop`]) ended it.
    Stopped,
}

#[cfg(test)]
mod tests {
    #[cfg(unix)]
    use super::*;

    #[cfg(unix)]
    #[test]
    fn unix_extra_roots_follow_root_zero_in_declaration_order() {
        let mut s = Session::new();
        s.declare_root(2, r"C:\b");
        s.declare_root(1, r"C:\a");
        let ids: Vec<u32> = s.root_locations().iter().map(|r| r.id).collect();
        assert_eq!(ids, [0, 2, 1]);
        assert_eq!(
            s.root_backing_dir(2),
            Some(s.state_dir().join("roots").join("2"))
        );
        assert_eq!(s.root_backing_dir(0).as_deref(), Some(s.virtual_root()));
        assert_eq!(s.root_backing_dir(7), None);
    }

    #[test]
    fn io_workers_default_clamp_and_reach_the_ring() {
        let mut s = crate::test_scratch::session_in_scratch("workers");
        assert_eq!(s.io_workers(), 4, "the default is unchanged");
        s.set_io_workers(0);
        assert_eq!(s.io_workers(), 1);
        s.set_io_workers(500);
        assert_eq!(s.io_workers(), 32);
        s.set_io_workers(12);
        s.serve().unwrap();
        assert_eq!(s.ipc().unwrap().worker_count(), 12);
        s.stop_serve();
    }
}
