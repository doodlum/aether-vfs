# Windows launches wait for the whole process tree — Design Spec

**Status:** Implemented on branch `windows-process-tree-wait` (Windows runtime behaviour
compile-checked only, see §8).
**Date:** 2026-10-13
**Consumers:** Mauvi (mod manager), any host of `vfs-embed` on Windows.

---

## 1. Goal

A host asks one question of a launch: *has everything it started exited?* A launcher chain
(`skse64_loader.exe` starts `SkyrimSE.exe` and exits) is one launch, and it ends when the game ends,
not when the loader does.

The Proton path already answers it: a launch lasts until the session's Wine prefix is quiet
(`wineserver -w`), and `Session::launch_detached` hands back a `LaunchHandle` with
`try_wait`/`is_running`/`wait`/`stop`/`stopper` (`session/proton/handle.rs`, architecture §3.10).

The Windows path does not. `Session::launch` calls `vfs_inject::run_target_with_shim`, which waits on
the first process only (`WaitForSingleObject(pi.hProcess, INFINITE)`): with SKSE it returns as soon as
the loader exits, while the game is still running. `wait: false` returns `Ok(0)` and keeps nothing,
so the launch cannot be polled or stopped at all. No Job Object is used anywhere.

This spec brings Windows to the Proton semantics, with one public API shape on both targets.

## 2. Semantics (both targets)

- **A launch runs until every process it started has exited** — the launched program and every
  descendant created through the ordinary process-creation path (which is every child the shim's
  `CreateProcessInternalW` hook injects).
- **The exit code reported is the first process's** (the program `LaunchOpts::image` named), not the
  last one's. That is what Proton reports (the injector's target's code).
- **`stop` ends the whole tree**, and the launch then reports `LaunchExit::Stopped`;
  `Session::launch` returns `STOPPED_EXIT_CODE` for it.
- **Dropping a `LaunchHandle` while the launch runs stops it**, and dropping the session stops the
  launches it holds or that are still live, before the ring goes.

## 3. Mechanism (Windows)

### 3.1 The job

`vfs-inject` gains `spawn_target_with_shim(RunConfig) -> Result<ProcessTree, InjectError>`. It creates
an unnamed Job Object per launch, configured with

- `JOB_OBJECT_LIMIT_KILL_ON_JOB_CLOSE` — the job handle lives only in the host process (never
  inherited), so if the host dies the game does not outlive the director it reads through;
- `JOB_OBJECT_LIMIT_BREAKAWAY_OK` — see §4.1;

and an I/O completion port associated with it (`JobObjectAssociateCompletionPortInformation`).

The target is created `CREATE_SUSPENDED` (it already is on the inject path; the import-activated path
now is too when a job is in use) and assigned to the job **before any of its code runs** — before the
remote `LoadLibrary` thread, which runs process initialisation, and before `ResumeThread`. Children
inherit the job, so nothing it starts can escape it by racing the assignment.

### 3.2 Waiting

The truth is `QueryInformationJobObject(JobObjectBasicAccountingInformation).ActiveProcesses == 0`.
The completion port's `JOB_OBJECT_MSG_ACTIVE_PROCESS_ZERO` is only the wake-up: Windows documents
job notifications as not guaranteed, so a wait is `GetQueuedCompletionStatus` with a bounded slice
(500 ms), then the count is re-read. A lost message costs at most one slice; it can never hang the wait.

Once the count is zero, the first process has necessarily exited and its exit code is read from its
handle. An import-activated target whose loader refused it (`0xC0000135`, `0xC0000142`) is still
reported as `InjectError::TargetExited`, as before.

### 3.3 Stopping

`TreeKiller::kill` is `TerminateJobObject` (fallback: `TerminateProcess` on the first process when
the launch has no job, §4.3). It is callable from any thread while another waits.

### 3.4 The injector is unchanged

`run_target_with_shim` keeps its behaviour — no job, wait on the first process — because its other
caller is `vfs-injector.exe`, which runs inside Wine on the Proton path, where the prefix watch
already covers the tree. Wine's job object support is not something this change should start
depending on, and changing it would alter what Proton's `LaunchHandle` observes as "the injector's
target exited" for no gain.

## 4. Edge cases

### 4.1 Breakaway

A child created with `CREATE_BREAKAWAY_FROM_JOB` fails with `ERROR_ACCESS_DENIED` unless the job
allows breakaway. Refusing would turn "the game starts a detached crash reporter or updater" into a
broken game, so the job sets `JOB_OBJECT_LIMIT_BREAKAWAY_OK` (but **not**
`JOB_OBJECT_LIMIT_SILENT_BREAKAWAY_OK`): a child that *asks* to break away does, is not counted, and
is not stopped by `stop`. Every ordinary child stays in the job. The shim still injects a broken-away
child; breakaway is about job membership, not about the hook. This is an open question for the
owner (§9): the alternative is to strip the flag in the shim's process hook.

### 4.2 Children the shim refuses

A child the shim cannot inject is terminated by the hook (`refuse_child`) and its `CreateProcess`
fails. It was in the job for that short time, and leaves it when it dies, so it never keeps the
launch alive. The refusal is still reported: in the launch error when the first process fails, and
as a `LaunchHandle::notes()` line for a running or finished launch.

### 4.3 A launch that cannot get a job

`AssignProcessToJobObject` can fail when the host itself runs in a job that forbids nesting (Windows
7, or a job with conflicting UI limits; Windows 8+ nests jobs otherwise). Failing the launch would
make the VFS unusable under such a host, so the launch degrades to first-process tracking — the old
behaviour — and says so: `ProcessTree::untracked_reason`, surfaced as the first
`LaunchHandle::notes()` line (vfs-embed writes nothing to stderr itself; the host decides whether to log it).

### 4.4 The Steam helper

Proton's Steam helper (`vfs-inject/src/steam_helper.rs`) must never keep a launch alive. On Windows
it is never started (`steam_helper_step` refuses outside Wine, and `vfs-embed` on Windows does not
ask for it). On Proton it is started by the injector *before* the target, from the injector's own
process, so even if the injector ever used a job the helper would not be in it. Nothing changes for
it here.

### 4.5 A host that is itself a job member and kills its job

If the host is in a job with kill-on-close (a terminal, an IDE), the launched tree is nested in
it and dies with it. That was already true of the first process without this change.

## 5. API (vfs-embed)

The same names on both targets:

| | Proton (unchanged) | Windows (new) |
|---|---|---|
| `Session::launch(&opts) -> Result<i32, String>` | waits for the prefix | waits for the job |
| `Session::launch_detached(&opts) -> Result<LaunchHandle, String>` | yes | **new** |
| `Session::stop_launch() -> Result<bool, String>` | yes | **new** |
| `LaunchHandle::{pid, notes, stopper, is_running, try_wait, wait, stop}` | yes | **new** |
| `LaunchStopper::{stop, was_stopped}` | yes | **new** |
| `LaunchHandle::{steam_helper, steam_helper_status}` | yes | not on Windows (Proton-only concept) |

`LaunchHandle::pid` is the launched program's pid on Windows (on Proton it is the `wine` process's;
both are documented).

**One deliberate difference:** a Proton session runs one launch at a time (the prefix is locked to
it). A Windows session never refused a second launch, and still does not; `stop_launch` stops every
launch the session started that is still running.

**Behaviour changes on Windows** a host should know about:

1. `launch(wait: true)` returns when the *tree* has exited, not the first process.
2. `launch(wait: false)` is now held by the session: `stop_launch` stops it, and dropping the session
   stops it (before, the child was left running against a ring that had gone away).
3. `LAUNCH_ENV_LOCK` is now released once the target is running, not held for the whole wait, so two
   sessions in one host can launch while another launch is being waited on.

## 6. Tests

`vfs-fixture-read` gains an orphan mode: with `VFS_FIXTURE_ORPHAN_DIR` set, after its read it starts a
copy of itself (through the shim, so injected and in the job) and exits 0 without waiting for it. The
copy waits until its parent has exited, writes `<dir>/parent-gone`, waits for `<dir>/release`, writes
`<dir>/child-released`, and exits 42 — a code the launch must not report.

`vfs-embed/tests/launch_process_tree.rs` (Windows):

1. **parent exits, child runs → not finished; child exits → finished with the parent's code**:
   `launch_detached`, wait for `parent-gone`, assert `try_wait() == None` and `is_running()`; create
   `release`; the launch ends with `LaunchExit::Exited(0)`.
2. **`Session::launch` (`wait: true`) waits for the child**: a helper thread creates `release` once
   `parent-gone` appears; when `launch` returns 0, `child-released` exists.
3. **`stop` ends the whole tree**: after `parent-gone`, `stop()` returns `Stopped`; creating `release`
   afterwards produces no `child-released`.
4. **`stop_launch` stops a `wait: false` launch the session holds.**

## 7. Docs

Architecture §3.10/§3.11/§4.3 describe launch lifetime for both targets; `CHANGELOG.md` records the
new Windows API and the behaviour changes.

## 8. Verification limits

This machine is Linux. The Windows code is checked with
`cargo check`/`cargo clippy --target x86_64-pc-windows-gnu` (lib and tests); the new tests have not
been run. The Linux suite for the affected crates is run to show nothing regressed. The Windows tests
must be run on Windows before merge.

## 9. Open questions

1. **Breakaway** (§4.1): allow explicit breakaway (current choice), or strip
   `CREATE_BREAKAWAY_FROM_JOB` in the shim's process hook so even those children are tracked and
   stopped?
2. **Concurrent Windows launches** (§5): keep allowing several per session, or refuse a second one as
   Proton does?
3. **Kill on host exit** (§3.1): `KILL_ON_JOB_CLOSE` ends the game if the host crashes. The director
   is in the host, so the game cannot work past that point anyway, but a host may prefer otherwise.

## 10. Status (2026-10-13)

Implemented on `windows-process-tree-wait` (the WIP checkpoint was squashed into one commit).
Checked on Linux:

- `cargo check --target x86_64-pc-windows-gnu -p vfs-embed -p vfs-inject --tests`: passes.
- `cargo clippy --target x86_64-pc-windows-gnu -p vfs-inject -p vfs-embed -p vfs-fixture-read
  --all-targets`: no warnings in the changed crates. The warnings left are newer-toolchain lints in
  crates this branch does not touch.
- `cargo test -p vfs-embed -p vfs-env -p vfs-proton`: passes (the live-Proton tests are `#[ignore]`d).
- `cargo clippy -p vfs-embed -p vfs-env -p vfs-proton -p vfs-fixture-read --all-targets`: as above.
  `vfs-inject` is Windows-only and is not built for Linux.
- `cargo fmt --all -- --check`: clean.
- Architecture §3.10, §3.11, §4.3 and `rust/CHANGELOG.md` updated.

**Not verified:** any Windows runtime behaviour. The four process-tree tests in
`vfs-embed/tests/launch_process_tree.rs` compile but have never run (no Windows or Wine here). Run
them on Windows before merge. The open questions in §9 are still open, and the current choices stand.
