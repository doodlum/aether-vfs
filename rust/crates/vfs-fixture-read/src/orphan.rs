//! The orphan phase: a launcher that starts a child and exits before it.
//!
//! With `VFS_FIXTURE_ORPHAN_DIR` set, the fixture (after its read) starts a
//! copy of itself, which the shim injects like any child, and exits 0 at
//! once without waiting for it. The copy (it sees `VFS_FIXTURE_ORPHAN_OF`,
//! its parent's pid, and does not spawn again) repeats the read, waits for
//! its parent to exit, writes `<dir>/parent-gone`, waits for `<dir>/release`
//! to exist, writes `<dir>/child-released`, and exits 42: a code the launch
//! must not report, since a launch reports its first process's.
//!
//! That is the shape of `skse64_loader.exe` starting `SkyrimSE.exe`, and how a
//! test proves a launch lasts as long as the whole process tree.

use std::path::Path;
use std::process::exit;
use std::time::{Duration, Instant};

/// The copy's exit code.
const CHILD_EXIT: i32 = 42;
/// How long the copy waits for its parent to exit, and for the release.
const PATIENCE: Duration = Duration::from_secs(600);

/// Called after the read. Returns only when the orphan phase is not asked for.
pub fn run() {
    let Ok(dir) = std::env::var("VFS_FIXTURE_ORPHAN_DIR") else {
        return;
    };
    let dir = Path::new(&dir);
    match std::env::var("VFS_FIXTURE_ORPHAN_OF") {
        Ok(parent) => be_the_child(dir, parent.parse().unwrap_or(0)),
        Err(_) => be_the_parent(),
    }
}

fn be_the_parent() -> ! {
    let me = std::env::current_exe().unwrap_or_else(|e| {
        eprintln!("FIXTURE FAIL: current_exe: {e}");
        exit(1);
    });
    match std::process::Command::new(me)
        .env("VFS_FIXTURE_ORPHAN_OF", std::process::id().to_string())
        .env_remove("VFS_FIXTURE_SPAWN_CHILD")
        .spawn()
    {
        // Not waited for: the child outlives this process.
        Ok(child) => println!("FIXTURE ORPHANED: child {}", child.id()),
        Err(e) => {
            eprintln!("FIXTURE FAIL: could not spawn the orphan: {e}");
            exit(1);
        }
    }
    exit(0);
}

fn be_the_child(dir: &Path, parent: u32) -> ! {
    wait_for_exit(parent);
    write(&dir.join("parent-gone"));
    let started = Instant::now();
    while !dir.join("release").exists() {
        if started.elapsed() > PATIENCE {
            eprintln!("FIXTURE FAIL: orphan: never released");
            exit(1);
        }
        std::thread::sleep(Duration::from_millis(10));
    }
    write(&dir.join("child-released"));
    exit(CHILD_EXIT);
}

fn write(path: &Path) {
    if let Err(e) = std::fs::write(path, b"1") {
        eprintln!("FIXTURE FAIL: orphan: write {}: {e}", path.display());
        exit(1);
    }
}

/// Returns once process `pid` has exited (at once if it cannot be opened,
/// which is how an exited process with no handle left looks).
#[cfg(windows)]
fn wait_for_exit(pid: u32) {
    type Handle = *mut core::ffi::c_void;
    const SYNCHRONIZE: u32 = 0x0010_0000;
    const WAIT_OBJECT_0: u32 = 0;
    extern "system" {
        fn OpenProcess(access: u32, inherit: i32, pid: u32) -> Handle;
        fn WaitForSingleObject(h: Handle, ms: u32) -> u32;
        fn CloseHandle(h: Handle) -> i32;
    }
    // SAFETY: plain Win32 calls; the handle is closed before returning.
    unsafe {
        let h = OpenProcess(SYNCHRONIZE, 0, pid);
        if h.is_null() {
            return;
        }
        let ms = u32::try_from(PATIENCE.as_millis()).unwrap_or(u32::MAX - 1);
        let r = WaitForSingleObject(h, ms);
        CloseHandle(h);
        if r != WAIT_OBJECT_0 {
            eprintln!("FIXTURE FAIL: orphan: parent {pid} never exited");
            exit(1);
        }
    }
}

/// Elsewhere the fixture only builds; it is a Windows program.
#[cfg(not(windows))]
fn wait_for_exit(_pid: u32) {}
