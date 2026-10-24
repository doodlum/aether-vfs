//! Node.js binding for aether-vfs: a host builds a session from folders and file maps, serves it,
//! launches programs through it and watches them end. Type conversion and threading only; every
//! platform difference stays behind `vfs-embed`.
//!
//! **Errors.** Every rejection is an `Error` whose message starts with a stable code and a colon
//! (`E_CONFIG: …`), the same on synchronous calls and on Promises:
//!
//! | code | meaning |
//! |---|---|
//! | `E_CONFIG` | bad path, missing base folder, a map that is not a tree, a write folder that cannot be made |
//! | `E_ARTIFACTS` | the shim DLL or injector is missing from `artifactsDir` |
//! | `E_STATE` | a launch or root change while a launch runs, any call after `close` |
//! | `E_LAUNCH` | the program failed to start or was refused |
//! | `E_INTERNAL` | anything else, including a caught panic |

use std::path::{Path, PathBuf};
use std::sync::atomic::{AtomicBool, Ordering};
use std::sync::{Arc, Mutex};

use napi::bindgen_prelude::*;
use napi::{Env, Task};
use napi_derive::napi;
use vfs_embed::{
    DiskProvider, LaunchExit, LaunchHandle, LaunchOpts, LaunchStopper, MapProvider, Provider,
    ReadOnlyProvider, RootId, RootSources,
};

const E_CONFIG: &str = "E_CONFIG";
const E_ARTIFACTS: &str = "E_ARTIFACTS";
const E_STATE: &str = "E_STATE";
const E_LAUNCH: &str = "E_LAUNCH";
const E_INTERNAL: &str = "E_INTERNAL";

fn error(code: &str, message: impl std::fmt::Display) -> Error {
    Error::new(Status::GenericFailure, format!("{code}: {message}"))
}

/// One file of a root: where the program sees it, and the host file that holds its bytes.
#[napi(object)]
#[derive(Clone)]
pub struct FileEntry {
    /// Relative to the root's location, `/` or `\` separated.
    pub path: String,
    /// Absolute host path.
    pub source: String,
}

/// A virtualised directory.
#[napi(object)]
#[derive(Clone)]
pub struct RootSpec {
    /// Where launched programs see the root. May be a real, non-empty directory (a game
    /// folder): programs see only what the root serves there, and the directory is not changed.
    pub location: String,
    /// A real directory served read-only underneath `files`, usually the same as `location`.
    pub base: Option<String>,
    /// Files served on top of `base`; a later entry for the same path wins.
    pub files: Vec<FileEntry>,
    /// Where new and changed files land. Without it the root is read-only.
    pub write_to: Option<String>,
}

#[napi(object)]
pub struct SessionOptions {
    /// The session's own directory (ring, staging, backing directories). Created if missing.
    pub state_dir: String,
    /// The directory holding `vfs_shim_dll.dll` (and, under Proton, `vfs-injector.exe`). For
    /// 32-bit games its `x86` folder holds the 32-bit `vfs_shim_dll.dll` and `vfs-injector.exe`
    /// (`vfs_embed::X86_ARTIFACTS_DIR`; see the top-level README's "32-bit games").
    pub artifacts_dir: String,
    pub roots: Vec<RootSpec>,
}

#[napi(object)]
pub struct LaunchOptions {
    /// The program: absolute, or relative to root 0's location.
    pub image: String,
    pub args: Option<Vec<String>>,
    /// Extra environment variables for the program only.
    pub env: Option<std::collections::HashMap<String, String>>,
    /// Variables the program does not inherit from this process.
    pub env_remove: Option<Vec<String>>,
    /// Programs the launched one starts by path, staged beside it (a loader's game).
    pub stage_also: Option<Vec<String>>,
}

#[napi(object)]
pub struct LaunchEnd {
    /// `"exited"` or `"stopped"`.
    pub kind: String,
    /// The program's exit code, when it exited by itself.
    pub code: Option<i32>,
}

#[napi(object)]
pub struct RootInfo {
    pub id: u32,
    pub location: String,
    /// How many files the root's map serves.
    pub files: u32,
}

struct Inner {
    session: vfs_embed::Session,
    roots: Vec<RootSpec>,
    file_counts: Vec<usize>,
    serving: bool,
    closed: bool,
}

/// The session's current launch, shared with its `Launch` object.
#[derive(Clone)]
pub struct Active {
    /// Taken by `wait()` while it waits; `None` then means "being waited for".
    handle: Arc<Mutex<Option<LaunchHandle>>>,
    stopper: LaunchStopper,
    /// Set once the launch has been seen to end.
    done: Arc<AtomicBool>,
}

impl Active {
    fn running(&self) -> bool {
        if self.done.load(Ordering::Acquire) {
            return false;
        }
        match self.handle.lock() {
            Ok(mut guard) => match guard.as_mut() {
                Some(h) => {
                    let running = h.is_running();
                    if !running {
                        self.done.store(true, Ordering::Release);
                    }
                    running
                }
                // `wait()` holds it, so the launch has not ended yet.
                None => true,
            },
            Err(_) => false,
        }
    }
}

/// One aether-vfs session.
#[napi]
pub struct Session {
    inner: Arc<Mutex<Inner>>,
    artifacts: PathBuf,
    current: Arc<Mutex<Option<Active>>>,
}

fn lock(inner: &Mutex<Inner>) -> Result<std::sync::MutexGuard<'_, Inner>> {
    inner
        .lock()
        .map_err(|_| error(E_INTERNAL, "a previous call panicked"))
}

/// Compose root `id` from its spec: `base` read-only, the file map above it, and `write_to` as
/// the copy-on-write upper. Replaces whatever the root served before.
fn compose(session: &vfs_embed::Session, id: u32, spec: &RootSpec) -> Result<usize> {
    let mut sources = RootSources::new();
    if let Some(base) = &spec.base {
        if !Path::new(base).is_dir() {
            return Err(error(
                E_CONFIG,
                format!("base folder {base:?} does not exist"),
            ));
        }
        let disk: Arc<dyn Provider> = Arc::new(DiskProvider::new(base));
        sources.add("", 0, Arc::new(ReadOnlyProvider::new(disk)));
    }
    let map = MapProvider::new(
        spec.files
            .iter()
            .map(|f| (f.path.as_str(), PathBuf::from(&f.source))),
    )
    .map_err(|e| error(E_CONFIG, e))?;
    let count = map.file_count();
    sources.add("", 1, Arc::new(map));
    let mounts = sources.mounts().map_err(|e| error(E_CONFIG, e))?;
    session.set_root_mounts(RootId(id), mounts).map_err(|st| {
        error(
            E_CONFIG,
            format!("root {id} could not be composed (status {st})"),
        )
    })?;
    if let Some(dir) = &spec.write_to {
        std::fs::create_dir_all(dir)
            .map_err(|e| error(E_CONFIG, format!("write folder {dir:?}: {e}")))?;
        session
            .set_write_layer_at(RootId(id), Arc::new(DiskProvider::new(dir)))
            .map_err(|st| {
                error(
                    E_CONFIG,
                    format!("write folder for root {id} (status {st})"),
                )
            })?;
    }
    Ok(count)
}

fn check_location(location: &str) -> Result<()> {
    if !Path::new(location).is_absolute() {
        return Err(error(
            E_CONFIG,
            format!("root location {location:?} is not absolute"),
        ));
    }
    Ok(())
}

#[napi]
impl Session {
    #[napi(constructor)]
    pub fn new(opts: SessionOptions) -> Result<Self> {
        if opts.roots.is_empty() {
            return Err(error(E_CONFIG, "a session needs at least one root"));
        }
        let state = PathBuf::from(&opts.state_dir);
        if !state.is_absolute() {
            return Err(error(E_CONFIG, "stateDir must be absolute"));
        }
        let mut session = vfs_embed::Session::new();
        session.set_state_dir(&state);
        // A reused overlay hides writes that bypassed the director, so it starts empty.
        let overlay = state.join("overlay");
        let _ = std::fs::remove_dir_all(&overlay);
        session.set_overlay(&overlay);
        let backing = state.join("root0");
        std::fs::create_dir_all(&backing).map_err(|e| error(E_CONFIG, e))?;
        session.set_root(&backing);
        let mut counts = Vec::with_capacity(opts.roots.len());
        for (id, spec) in opts.roots.iter().enumerate() {
            check_location(&spec.location)?;
            session.declare_root(id as u32, &spec.location);
            counts.push(compose(&session, id as u32, spec)?);
        }
        Ok(Session {
            inner: Arc::new(Mutex::new(Inner {
                session,
                roots: opts.roots,
                file_counts: counts,
                serving: false,
                closed: false,
            })),
            artifacts: PathBuf::from(opts.artifacts_dir),
            current: Arc::new(Mutex::new(None)),
        })
    }

    /// Start serving. Idempotent.
    #[napi]
    pub fn start(&self) -> Result<()> {
        let mut inner = lock(&self.inner)?;
        if inner.closed {
            return Err(error(E_STATE, "the session is closed"));
        }
        if !inner.serving {
            inner.session.serve().map_err(|e| error(E_INTERNAL, e))?;
            inner.serving = true;
        }
        Ok(())
    }

    /// The roots, as programs see them.
    #[napi]
    pub fn roots(&self) -> Result<Vec<RootInfo>> {
        let inner = lock(&self.inner)?;
        Ok(inner
            .session
            .root_locations()
            .into_iter()
            .map(|loc| RootInfo {
                id: loc.id,
                files: inner.file_counts.get(loc.id as usize).copied().unwrap_or(0) as u32,
                location: loc.location,
            })
            .collect())
    }

    /// Replace one root's content (its location stays), also while a launch runs: the director
    /// swaps the root's provider and moves its mount generation, so a running program's next
    /// open sees the new content and its read cache drops the old bytes. Handles already open
    /// keep reading what they opened.
    #[napi]
    pub fn set_root(&self, id: u32, spec: RootSpec) -> Result<()> {
        let mut inner = lock(&self.inner)?;
        if inner.closed {
            return Err(error(E_STATE, "the session is closed"));
        }
        let Some(existing) = inner.roots.get(id as usize) else {
            return Err(error(E_CONFIG, format!("no root {id}")));
        };
        if !existing.location.eq_ignore_ascii_case(&spec.location) {
            return Err(error(
                E_CONFIG,
                "a root's location cannot change; make a new session",
            ));
        }
        let count = compose(&inner.session, id, &spec)?;
        inner.file_counts[id as usize] = count;
        inner.roots[id as usize] = spec;
        Ok(())
    }

    /// Launch a program through the session. Resolves once it is running.
    #[napi(ts_return_type = "Promise<Launch>")]
    pub fn launch(&self, opts: LaunchOptions) -> Result<AsyncTask<LaunchTask>> {
        self.refuse_while_running()?;
        let shim = self.artifacts.join("vfs_shim_dll.dll");
        if !shim.is_file() {
            return Err(error(E_ARTIFACTS, format!("{} is missing", shim.display())));
        }
        let injector = self.artifacts.join("vfs-injector.exe");
        let launch = LaunchOpts {
            image: opts.image,
            args: opts.args.unwrap_or_default(),
            wait: false,
            env: opts.env.unwrap_or_default().into_iter().collect(),
            env_remove: opts.env_remove.unwrap_or_default(),
            stage_also: opts.stage_also.unwrap_or_default(),
            shim_dll: Some(shim),
            injector: injector.is_file().then_some(injector),
            ..LaunchOpts::default()
        };
        Ok(AsyncTask::new(LaunchTask {
            inner: Arc::clone(&self.inner),
            current: Arc::clone(&self.current),
            opts: Some(launch),
            started: None,
        }))
    }

    /// Stop a running launch, stop serving and release everything. Idempotent.
    #[napi]
    pub fn close(&self) -> Result<()> {
        if let Some(active) = self.current.lock().ok().and_then(|mut c| c.take()) {
            let _ = active.stopper.stop();
        }
        let mut inner = lock(&self.inner)?;
        if !inner.closed {
            inner.session.stop_serve();
            inner.serving = false;
            inner.closed = true;
        }
        Ok(())
    }

    fn refuse_while_running(&self) -> Result<()> {
        let current = self
            .current
            .lock()
            .map_err(|_| error(E_INTERNAL, "a previous call panicked"))?;
        match current.as_ref() {
            Some(active) if active.running() => Err(error(
                E_STATE,
                "a launch is running in this session; wait for it or stop it first",
            )),
            _ => Ok(()),
        }
    }
}

impl Drop for Session {
    fn drop(&mut self) {
        let _ = self.close();
    }
}

pub struct LaunchTask {
    inner: Arc<Mutex<Inner>>,
    current: Arc<Mutex<Option<Active>>>,
    opts: Option<LaunchOpts>,
    started: Option<(u32, Vec<String>)>,
}

impl Task for LaunchTask {
    type Output = Active;
    type JsValue = Launch;

    fn compute(&mut self) -> Result<Self::Output> {
        let opts = self
            .opts
            .take()
            .ok_or_else(|| error(E_INTERNAL, "launch reused"))?;
        let inner = lock(&self.inner)?;
        if inner.closed {
            return Err(error(E_STATE, "the session is closed"));
        }
        if !inner.serving {
            return Err(error(E_STATE, "start() the session before launching"));
        }
        let handle = std::panic::catch_unwind(std::panic::AssertUnwindSafe(|| {
            inner.session.launch_detached(&opts)
        }))
        .map_err(|_| error(E_INTERNAL, "the launch panicked"))?
        .map_err(|e| {
            if e.contains("already running") {
                error(E_STATE, e)
            } else {
                error(E_LAUNCH, e)
            }
        })?;
        let pid = handle.pid();
        let notes = handle.notes();
        let active = Active {
            stopper: handle.stopper(),
            handle: Arc::new(Mutex::new(Some(handle))),
            done: Arc::new(AtomicBool::new(false)),
        };
        *self
            .current
            .lock()
            .map_err(|_| error(E_INTERNAL, "a previous call panicked"))? = Some(active.clone());
        self.started = Some((pid, notes));
        Ok(active)
    }

    fn resolve(&mut self, _env: Env, active: Self::Output) -> Result<Self::JsValue> {
        let (pid, notes) = self.started.take().unwrap_or_default();
        Ok(Launch { pid, notes, active })
    }
}

/// A running launch: the program and everything it starts.
#[napi]
pub struct Launch {
    pid: u32,
    notes: Vec<String>,
    active: Active,
}

#[napi]
impl Launch {
    /// The launched program's process id (the `wine` process under Proton).
    #[napi(getter)]
    pub fn pid(&self) -> u32 {
        self.pid
    }

    /// Fallbacks, refused children and other warnings; never failures.
    #[napi(getter)]
    pub fn notes(&self) -> Vec<String> {
        self.notes.clone()
    }

    /// Whether anything the launch started still runs.
    #[napi]
    pub fn is_running(&self) -> bool {
        self.active.running()
    }

    /// Resolves when the program and everything it started have ended.
    #[napi(ts_return_type = "Promise<LaunchEnd>")]
    pub fn wait(&self) -> AsyncTask<WaitTask> {
        AsyncTask::new(WaitTask {
            active: self.active.clone(),
        })
    }

    /// End the whole launch. `wait()` then resolves with `"stopped"`.
    #[napi]
    pub fn stop(&self) -> Result<()> {
        self.active.stopper.stop().map_err(|e| error(E_INTERNAL, e))
    }
}

pub struct WaitTask {
    active: Active,
}

impl Task for WaitTask {
    type Output = LaunchExit;
    type JsValue = LaunchEnd;

    fn compute(&mut self) -> Result<Self::Output> {
        let handle = self
            .active
            .handle
            .lock()
            .map_err(|_| error(E_INTERNAL, "a previous call panicked"))?
            .take()
            .ok_or_else(|| error(E_STATE, "this launch is already being waited for"))?;
        let end = handle.wait().map_err(|e| error(E_INTERNAL, e));
        self.active.done.store(true, Ordering::Release);
        end
    }

    fn resolve(&mut self, _env: Env, end: Self::Output) -> Result<Self::JsValue> {
        Ok(match end {
            LaunchExit::Exited(code) => LaunchEnd {
                kind: "exited".into(),
                code: Some(code),
            },
            LaunchExit::Stopped => LaunchEnd {
                kind: "stopped".into(),
                code: None,
            },
        })
    }
}
