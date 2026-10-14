# Changelog

Changes to the host-facing API of `vfs-embed`, `vfs-proton`, `vfs-provider`
and `vfs-env` that a host (Haskill) has to adapt to or can start using. Newest
first. Internal refactors are in the git history, not here.

## Unreleased (host death, known folders, Steam and runtime re-exports)

### Added

- Unix: `vfs_embed::proton::steam::{state_dir, running_client}`, the Steam
  liveness test a launch makes, so a host's own check (a Play gate) needs no
  `vfs-proton` dependency. `Session::set_steam_state_dir` (Flatpak Steam) was
  already there.
- Unix, feature `acquire` (off by default): `vfs_embed::proton::install`, with
  `install(&Root, Option<&str>, force) -> Result<Installed, String>` (what
  `vfs-proton install` does: a named tag already installed needs no network)
  and the pieces under it: `install_release`, `resolve`, `fetch_releases`,
  `pick`, `partial_path` (for progress), `Agent`, `Installed`, `InstallError`,
  `Release`, `DEFAULT_MAJOR`. `vfs-proton` gains `install`, `resolve` and
  `DEFAULT_MAJOR` (moved from its CLI).

## Unreleased (registry fidelity on Windows 11 26H2)

Registry hooks now follow the host where Windows and Wine differ, as found by
probing the real ntdll on Windows 11 26H2 (build 26300). Behaviour changes a
host can observe through the shim:

- `NtDuplicateObject` on a synthetic registry handle with a NULL target
  process is a close-only call: success with `DUPLICATE_CLOSE_SOURCE`,
  `STATUS_INVALID_PARAMETER` without, and `*TargetHandle` is zeroed.
- A registry value name with an odd `UNICODE_STRING.Length` is
  `STATUS_INVALID_PARAMETER` on Windows (a NULL buffer is
  `STATUS_ACCESS_VIOLATION` first); under Wine it is still truncated.
  `vfs_shim::host_is_wine()` is exported for tests.

## Unreleased (Windows process-tree wait)

Windows launches now last as long as their whole process tree, as Proton
launches already did. A launcher chain (`skse64_loader.exe` starts
`SkyrimSE.exe` and exits) is one launch that ends when the game ends. Design:
`docs/superpowers/specs/2026-10-13-windows-process-tree-wait-design.md`.
The Windows behaviour is compile-checked only so far: the new Windows tests
have not been run on Windows.

### Added

- Windows: `Session::launch_detached(&LaunchOpts) -> Result<LaunchHandle, String>`,
  `Session::stop_launch() -> Result<bool, String>`, and `vfs_embed::LaunchHandle`
  (`pid`, `notes`, `stopper`, `is_running`, `try_wait`, `wait`, `stop`) and
  `vfs_embed::LaunchStopper` (`stop`, `was_stopped`), with the Proton handle's
  contract: the launch runs until every process it started has exited, the
  exit code is the first process's, and `stop` ends the whole tree
  (`LaunchExit::Stopped`). `LaunchHandle::pid` is the launched program's pid.
  The Steam helper accessors stay Proton-only.
- `vfs_inject::spawn_target_with_shim(RunConfig) -> Result<ProcessTree, InjectError>`
  with `ProcessTree` and `TreeKiller`: the target in a Job Object before any of
  its code runs. `run_target_with_shim` (used by `vfs-injector.exe` under Wine)
  is unchanged.

### Changed (Windows)

- `Session::launch` with `wait: true` returns when the whole process tree has
  exited, not when the first process has. The exit code is still the first
  process's.
- `Session::launch` with `wait: false` is held by the session: `stop_launch`
  stops it, and dropping the session stops it before the ring goes. Before,
  the child was left running against a ring that had gone away.
- The process-wide launch environment lock is released once the target is
  running, not held for the whole wait, so another session in the same host
  can launch while one launch is being waited on.
- **One launch per session, as on Proton.** While a launch runs (waited on,
  held for `wait: false`, or a `LaunchHandle` from `launch_detached` that has
  not been seen to end), `launch` and `launch_detached` return
  `Err("launch: a launch is already running in this session — stop_launch() first")`.
  Before, a Windows session allowed several launches at once. `stop_launch`
  stops the one running launch, and for a held `wait: false` launch waits up
  to 30 s for its job to empty.
- **Every child stays in the launch.** The shim removes
  `CREATE_BREAKAWAY_FROM_JOB` from every child it creates and the job allows no
  breakaway, so a child that asks to break away (a crash reporter, an updater)
  is waited for and stopped like any other, and its `CreateProcess` does not
  fail on that account. If the host's own job forbids nesting, the
  launch falls back to first-process tracking and says so in
  `LaunchHandle::notes()`. The job is killed when its handle closes, so a
  crashed host takes the game with it.

## Unreleased (path identity)

### Added

- `vfs_embed::{fold_path, paths_equal, fold_segment}`: "are these two paths
  the same file?", answered by the Director's own normalisation and the
  engine's own fold (`vfs_core::fold`), for a host that stores vpaths.
  `PathEscapesRoot` is the error for a `..` above the root.
  `FOLD_VERSION` (now `1`) and `fold_unicode_version()` let a host that
  persists folded keys detect a change in the fold. Contract:
  `docs/superpowers/specs/2026-10-13-export-case-fold-design.md`.

## Unreleased (add-ons)

### Removed

- The daemon stack: `vfs-directord` (the `vfs` CLI and gRPC daemon),
  `vfs-control` (its gRPC contract and TOML config schema) and `vfs-source`
  (`SourceSpec` → provider, and the out-of-process gRPC `RemoteProvider`).
  No host used them; a host composes sessions with `vfs_embed::Session`, and
  one that adds sources one at a time uses `vfs_embed::RootSources` with
  `Session::set_root_mounts`. `tools/python_source_plugin` went with
  `vfs-source`'s proto.

## Unreleased (cleanup pass)

### Breaking

- `vfs_embed::LaunchOpts::shim_dll` and `payload_dll` are `Option<PathBuf>`
  (were `Option<String>`). `cwd` stays a `String`: it names a place inside the
  Wine prefix (`C:\...`), not a host path.
- `vfs_embed::LaunchOpts` has a new field `injector: Option<PathBuf>`. Left
  `None`, `vfs-injector.exe` is looked for as before (beside `shim_dll`, else in
  `VFS_WINDOWS_ARTIFACTS`, else beside the executable). A host that builds
  `LaunchOpts` with `..LaunchOpts::default()` needs no change for this field.
- `vfs_provider`'s conformance suite (`conformance`, `assert_conformance`,
  `write_fixture_tree`, `RwMemFixture`, `FIXTURE_FILES`, the fixture providers)
  is behind the `conformance` feature. Enable it in `[dev-dependencies]`.
  `vfs_embed::{assert_conformance, write_fixture_tree, FIXTURE_FILES}` and
  `vfs_source::{assert_conformance, write_fixture_tree}` are behind a feature of
  the same name that forwards to it.
- `vfs_proton::launch::WineLaunch` is `#[non_exhaustive]`. Build one with
  `WineLaunch::new(runtime, prefix, target, virtual_dir, LaunchFiles, RingGeometry)`
  and set the public fields it needs. The literal-struct form, with
  `injector`, `shim_dll`, `payload_dll`, `config_file`, `ready_file` and the
  ring numbers as fields of `WineLaunch`, no longer compiles outside the crate.
- `vfs_proton::prefix::Prefix::map_drive` and `unmap_drive` are removed (nothing
  used them).
- The `#[doc(hidden)]` flat re-exports in `vfs_proton`'s root are removed
  (`InstallError`, `Installed`, `LaunchError`, `BASE_DLL_OVERRIDES`,
  `PrefixError`, `VerifyError`, `runtime_lib_env`, `STEAM_HELPER`, ...). Use the
  module paths (`vfs_proton::launch::LaunchError`, ...) or `vfs_embed::proton`.
- The errors of `Session::launch` are unchanged in text. `Session` no longer
  puts "launch: " inside the prefix step's messages; `launch` adds it.

### Added

- `Session::prepare_prefix(&self) -> Result<PathBuf, String>` (unix): the
  launch's prefix step on its own (newest runtime of the session's home, the
  session's prefix name and `PrefixInit`, under the prefix lock). The session
  need not be serving.
- `vfs_proton::runtime::newest_installed(&Root) -> io::Result<Option<PathBuf>>`.
- `vfs_embed::proton` (unix): `Root`, `Prefix`, `PrefixLock`, `PrefixInit`,
  `prefix_dir`, `nvapi::{status, Capability, GpuModel, NvapiStatus}`,
  `runtime::{cmp_tags, installed_dirs, newest_installed, verify_ge}` and
  `artifacts`. A host names only `vfs-embed`.
- `vfs_proton::artifacts`: the file names of the Windows build artefacts
  (`INJECTOR`, `SHIM_DLL`, `PAYLOAD_DLL`, `LAUNCH`, the `FIXTURE_*` names and
  `WINDOWS_ARTIFACTS`). A test asserts the list equals `bin/build-windows`'s.
