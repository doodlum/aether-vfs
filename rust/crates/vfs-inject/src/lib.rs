#![deny(unsafe_code)]

//! Launch a target process with the VFS shim: [`run_target_with_shim`] starts
//! an import-patched exe normally, or injects the shim into an unpatched one,
//! and waits for that process. [`spawn_target_with_shim`] does the same inside
//! a Job Object and hands back a [`ProcessTree`], which ends only when every
//! process the target started has exited.

use std::time::Duration;

mod artifacts;
mod cli;
mod env_block;
mod guest;
mod inject;
mod map;
mod mem_inject;
mod pe;
mod procparams;
mod steam_helper;
mod tree;

/// A target's environment block, built from another (see `env_block`).
pub use env_block::{build as build_env_block, current as current_env_block};
/// In-memory import patch of a suspended image (`mem_inject`).
pub use mem_inject::{patch_imports_in_memory, MemPatchRefused};
pub use pe::map_image_from_pe_bytes_local;
/// Tell a suspended process its virtual command line and working directory.
pub use procparams::rewrite as rewrite_process_parameters;

/// Parameters for [`run_target_with_shim`].
pub struct RunConfig {
    pub target_exe: String,
    pub args: Vec<String>,
    /// Append arguments without CRT quoting (Windows command-shell syntax).
    /// The caller supplies all required quoting, as with Node's windowsVerbatimArguments.
    pub windows_verbatim_arguments: bool,
    /// Working directory for the child (typically the managed game root).
    pub current_dir: Option<String>,
    /// The shim DLL (`vfs_shim_dll.dll`).
    pub dll_path: String,
    pub config_path: String,
    pub ready_path: String,
    pub ready_timeout: Duration,
    /// When true, return as soon as the target is running (do not wait for exit).
    /// [`run_target_with_shim`] only; [`spawn_target_with_shim`] never waits.
    pub detach: bool,
    /// Variables the target gets on top of this process's environment, which
    /// is never itself changed: the launch builds the target's block and hands
    /// it to `CreateProcessW`. A later entry wins; names compare without
    /// regard to case. The launch's own variables (`VFS_SHIM_CONFIG`,
    /// `VFS_SHIM_READY`, ...) are added first, so an entry here overrides them.
    pub env_set: Vec<(String, String)>,
    /// The path the target is told its image has, in its command line's
    /// first argument, when it is not `target_exe`: an image staged into a
    /// root's backing directory is named by its path under the root's
    /// (virtual) location. The target is created suspended and rewritten
    /// before any of its code runs.
    pub virtual_image: Option<String>,
    /// The directory the target is told it starts in, when it is not
    /// `current_dir` (a root's virtual location, where nothing real exists
    /// to start in). Rewritten like [`Self::virtual_image`].
    pub virtual_cwd: Option<String>,
    /// Variables the target does not inherit from this process.
    pub env_remove: Vec<String>,
    /// Skip the in-memory import patch and inject the shim on a remote thread.
    /// For tests of the injection path itself (where `dll_path` is a full path,
    /// which the loader's name-based import resolution cannot use); production
    /// launches leave it `false` and prefer the in-memory patch.
    pub force_injection: bool,
}

/// Failure points in launch + inject + run.
#[derive(Debug)]
pub enum InjectError {
    CreateProcess,
    Alloc,
    Write,
    RemoteThread,
    /// The shim did not report ready within `ready_timeout` (or the early
    /// payload never reached its install sentinel). Once the process is past
    /// the payload and parked at the spin gate, the process is **killed**
    /// rather than released: a bootstrap that never says anything must not
    /// leave the target running un-virtualised.
    Timeout,
    Wait,
    ExitCode,
    Ntdll,
    PayloadRead,
    PeParse,
    ThreadContext,
    Config,
    /// The shim's hooks came up, but a director was configured (a ring was
    /// named) and its FUSE client failed to attach — the process would have
    /// run completely un-virtualised. It is killed before ever being
    /// released past the pre-init spin gate, i.e. before a byte of game code
    /// runs. Distinct from [`Timeout`], which also covers a shim that never
    /// loaded at all (no injection happened) — that is not a FUSE failure and
    /// must not be reported as one.
    FuseInit(String),
    /// The shim loaded but could not bootstrap — a config from another build
    /// or a damaged one, an unreadable config, a hook that would not install —
    /// and said so in the ready file. Killed before release, like
    /// [`FuseInit`](InjectError::FuseInit).
    Bootstrap(String),
    /// The target exited — with this exit code, an `NTSTATUS` such as
    /// `0xC0000135` (a DLL it imports is missing) when the loader killed it —
    /// before the shim reported ready. Reported as soon as it is seen rather
    /// than after the ready timeout, which is what a dead target used to cost.
    TargetExited(u32),
    /// A [`TreeKiller`] could not terminate the launch's job (or, for a
    /// launch without one, its process).
    Terminate,
    /// The 32-bit `vfs-injector.exe` a WOW64 guest's remote `LoadLibrary`
    /// goes through (beside the 32-bit shim) is missing or failed: why.
    Wow64Helper(String),
    /// The shim cannot load into the target: it was built for the other
    /// width (a 64-bit shim and a 32-bit program, or the reverse).
    ShimWidth(String),
}

pub use artifacts::{exe_imports_shim, find_near};
pub use cli::{
    load_library_report, parse_injector_args, parse_load_library_args, parse_load_library_report,
    InjectorArgs, LoadLibraryArgs, LOAD_LIBRARY_FLAG,
};
/// A target process's width, and the layouts that go with it.
pub use guest::{peb_of, Guest};
pub use inject::{
    expand_primary_stack, inject_dll, inject_dll_exit_code, resume_primary_thread,
    run_target_with_shim, spawn_target_with_shim, PRIMARY_STACK_BYTES, WOW64_HELPER_EXE,
};
pub use steam_helper::{
    active_process_pid, check_helper_command, clear_active_process_pid, running_under_wine,
    start_steam_helper, SteamHelper, SteamHelperError, ACTIVE_PROCESS_KEY,
};
pub use tree::{ProcessTree, TreeKiller};
