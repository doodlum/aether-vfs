//! A Windows launch lasts as long as its whole process tree.
//!
//! `vfs-fixture-read` in its orphan mode (`VFS_FIXTURE_ORPHAN_DIR`) is a
//! launcher in miniature: after its read it starts a copy of itself (injected
//! by the shim's process hook, and so in the launch's job) and exits 0 without
//! waiting. The copy waits for its parent to be gone, writes `parent-gone`,
//! waits for the test to create `release`, writes `child-released` and exits
//! 42. That is `skse64_loader.exe` starting `SkyrimSE.exe` and leaving, and
//! the launch must end with the game, reporting the loader's exit code.
//!
//! The same contract holds on Proton (the prefix going quiet), where
//! `proton_fake_runtime.rs` covers it with a fake runtime.

// Every test here injects a real Windows process, so on other hosts the helpers are unused.
#![cfg_attr(not(windows), allow(dead_code, unused_imports))]

use std::collections::BTreeMap;
use std::path::{Path, PathBuf};
use std::sync::Arc;
use std::time::{Duration, Instant};

use vfs_embed::{DiskProvider, LaunchExit, LaunchOpts};

mod support;
use support::session::LiveSession;
use support::{artifacts::*, launch::*};

/// How long the parent's exit, or the child's, may take before the test calls
/// it wedged.
const PATIENCE: Duration = Duration::from_secs(120);

/// A served session whose root 0 serves `hello.txt` (5 bytes) from a disk
/// source, the orphan fixture's options, and the directory it signals in
/// (outside every root, so the fixture reaches it on real disk).
struct Orphan {
    session: LiveSession,
    opts: LaunchOpts,
    signals: vfs_testkit::Scratch,
    _content: vfs_testkit::Scratch,
}

impl Orphan {
    fn new(name: &str) -> Self {
        let content = vfs_testkit::scratch_dir(&format!("{name}-content"));
        std::fs::write(content.join("hello.txt"), b"hello").unwrap();
        let signals = vfs_testkit::scratch_dir(&format!("{name}-signals"));

        let mut session = LiveSession::create(name);
        session
            .add_source(0, "/", 0, Arc::new(DiskProvider::new(&*content)))
            .expect("add source");

        let env: BTreeMap<String, String> = [
            (
                "VFS_FIXTURE_PATH",
                session
                    .root()
                    .join("hello.txt")
                    .to_string_lossy()
                    .into_owned(),
            ),
            ("VFS_FIXTURE_EXPECT", "5".to_string()),
            (
                "VFS_FIXTURE_ORPHAN_DIR",
                signals.to_string_lossy().into_owned(),
            ),
        ]
        .into_iter()
        .map(|(k, v)| (k.to_string(), v))
        .collect();
        let opts = LaunchOpts {
            image: locate_artifact("vfs-fixture-read.exe")
                .to_string_lossy()
                .into_owned(),
            env,
            ..Default::default()
        };
        Orphan {
            session,
            opts,
            signals,
            _content: content,
        }
    }

    fn signal(&self, name: &str) -> PathBuf {
        self.signals.join(name)
    }

    /// Waits for the child to say its parent, the launched process, is gone.
    fn await_parent_gone(&self) {
        await_file(
            &self.signal("parent-gone"),
            "the orphan's parent-gone signal",
        );
    }

    fn release(&self) {
        std::fs::write(self.signal("release"), b"1").unwrap();
    }
}

fn await_file(path: &Path, what: &str) {
    let started = Instant::now();
    while !path.exists() {
        assert!(
            started.elapsed() < PATIENCE,
            "{what} ({}) never appeared",
            path.display()
        );
        std::thread::sleep(Duration::from_millis(20));
    }
}

/// Polls `handle` until its launch ends, bounded.
fn await_end(handle: &mut vfs_embed::LaunchHandle) -> LaunchExit {
    let started = Instant::now();
    loop {
        if let Some(exit) = handle.try_wait().expect("try_wait") {
            return exit;
        }
        assert!(
            started.elapsed() < PATIENCE,
            "the launch never ended: {:?}",
            handle.notes()
        );
        std::thread::sleep(Duration::from_millis(20));
    }
}

/// **The decisive case.** The launched process has exited and its child is
/// still running: the launch has not ended. Once the child exits it has, with
/// the launched process's exit code (0), not the child's (42).
#[cfg(windows)]
#[test]
fn a_launch_runs_until_the_child_its_launcher_left_behind_exits() {
    let _guard = lock_launches();
    ensure_inject_artifacts();
    let o = Orphan::new("tree-orphan");

    let mut handle = o.session.launch_detached(&o.opts).expect("launch");
    o.await_parent_gone();
    // The parent is gone; give the job a moment in which a launch that
    // tracked only it would certainly have been seen to end.
    std::thread::sleep(Duration::from_millis(500));
    assert_eq!(
        handle.try_wait().expect("try_wait"),
        None,
        "the launcher has exited but its child runs: the launch must not have ended"
    );
    assert!(handle.is_running());
    assert!(!o.signal("child-released").exists());

    o.release();
    assert_eq!(await_end(&mut handle), LaunchExit::Exited(0));
    assert!(
        o.signal("child-released").exists(),
        "the launch ended before the child did"
    );
    assert!(!handle.is_running());
    assert!(handle.notes().is_empty(), "notes: {:?}", handle.notes());
}

/// `Session::launch` with `wait: true` blocks for the same span: it returns
/// only once the child has exited, and returns the launcher's code.
#[cfg(windows)]
#[test]
fn a_waited_launch_returns_only_once_the_child_has_exited() {
    let _guard = lock_launches();
    ensure_inject_artifacts();
    let o = Orphan::new("tree-wait");

    let parent_gone = o.signal("parent-gone");
    let release = o.signal("release");
    let releaser = std::thread::spawn(move || {
        await_file(&parent_gone, "the orphan's parent-gone signal");
        std::thread::sleep(Duration::from_millis(500));
        std::fs::write(release, b"1").unwrap();
    });
    let code = launch_bounded(o.session.shared(), o.opts.clone(), "tree-wait");
    releaser.join().unwrap();
    assert_eq!(code, 0, "the launcher's exit code, not the child's 42");
    assert!(
        o.signal("child-released").exists(),
        "launch returned while the child was still running"
    );
}

/// `stop` ends the whole tree: the child the launcher left behind as well.
#[cfg(windows)]
#[test]
fn stop_ends_the_child_its_launcher_left_behind() {
    let _guard = lock_launches();
    ensure_inject_artifacts();
    let o = Orphan::new("tree-stop");

    let handle = o.session.launch_detached(&o.opts).expect("launch");
    o.await_parent_gone();
    assert_eq!(handle.stop().expect("stop"), LaunchExit::Stopped);

    // A child that survived the stop would now write `child-released`.
    o.release();
    std::thread::sleep(Duration::from_secs(1));
    assert!(
        !o.signal("child-released").exists(),
        "the child outlived stop()"
    );
}

/// A `wait: false` launch is held by the session, and `stop_launch` stops its
/// whole tree.
#[cfg(windows)]
#[test]
fn stop_launch_stops_a_detached_launchs_whole_tree() {
    let _guard = lock_launches();
    ensure_inject_artifacts();
    let mut o = Orphan::new("tree-stop-launch");
    o.opts.wait = false;

    assert!(!o.session.stop_launch().unwrap(), "nothing running yet");
    assert_eq!(o.session.launch(&o.opts).expect("launch"), 0);
    o.await_parent_gone();
    assert!(o.session.stop_launch().unwrap(), "the child was running");
    assert!(!o.session.stop_launch().unwrap(), "already stopped");

    o.release();
    std::thread::sleep(Duration::from_secs(1));
    assert!(
        !o.signal("child-released").exists(),
        "the child outlived stop_launch()"
    );
}

/// One launch per session, as on Proton: while a launch runs (here only its
/// launcher's child is left), every way of starting another is refused, and
/// once it has been stopped the session launches again.
#[cfg(windows)]
#[test]
fn a_second_launch_is_refused_while_the_first_runs() {
    let _guard = lock_launches();
    ensure_inject_artifacts();
    let o = Orphan::new("tree-one-launch");

    let mut handle = o.session.launch_detached(&o.opts).expect("launch");
    o.await_parent_gone();
    for wait in [true, false] {
        let opts = LaunchOpts {
            wait,
            ..o.opts.clone()
        };
        let e = o.session.launch(&opts).unwrap_err();
        assert!(e.contains("already running"), "wait: {wait}: {e}");
    }
    let e = o
        .session
        .launch_detached(&o.opts)
        .err()
        .expect("a second launch_detached is refused");
    assert!(e.contains("already running"), "{e}");
    assert_eq!(handle.try_wait().expect("try_wait"), None, "still running");

    // A kept handle is the session's running launch too: `stop_launch`
    // reaches it.
    assert!(o.session.stop_launch().unwrap(), "the child was running");
    assert_eq!(await_end(&mut handle), LaunchExit::Stopped);
    drop(handle);

    for f in ["parent-gone", "release", "child-released"] {
        let _ = std::fs::remove_file(o.signal(f));
    }
    let again = o
        .session
        .launch_detached(&o.opts)
        .expect("the session launches again once the first launch has ended");
    assert_eq!(again.stop().expect("stop"), LaunchExit::Stopped);
}

/// The handle is part of the surface on every target, and can be moved to
/// (and stopped from) another thread.
#[test]
fn launch_handles_cross_threads() {
    fn send<T: Send>() {}
    fn send_sync<T: Send + Sync>() {}
    send::<vfs_embed::LaunchHandle>();
    send_sync::<vfs_embed::LaunchStopper>();
    send_sync::<vfs_embed::Session>();
}
