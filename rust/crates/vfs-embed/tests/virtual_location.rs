//! A root whose location is purely virtual: nothing exists there on real disk,
//! and the launched program still believes it runs from it.
//!
//! Root 0 is declared at a scratch path that is never created. The program —
//! `vfs-fixture-read`, served only by the root's provider — is staged into the
//! root's backing directory and really started there; the launch then tells
//! it (before any of its code runs) that its image and working directory are
//! under the location. Its probe phase reports what it believes; its read
//! goes through a **relative** path, which only resolves if the virtual
//! working directory does.

// Every test here injects a real Windows process, so on other hosts the helpers are unused.
#![cfg_attr(not(windows), allow(dead_code, unused_imports))]

use std::collections::BTreeMap;
use std::path::Path;
use std::sync::Arc;

use vfs_embed::{DiskProvider, LaunchOpts};

mod support;
use support::session::LiveSession;
use support::{artifacts::*, launch::*};

/// The probe's facts, by name (`cwd`, `exe`, `argv0`, `env NAME`).
fn probe_facts(out: &Path) -> BTreeMap<String, String> {
    let text = std::fs::read_to_string(out)
        .unwrap_or_else(|e| panic!("probe output {}: {e}", out.display()));
    text.lines()
        .filter_map(|l| {
            if let Some(env) = l.strip_prefix("env ") {
                let (k, v) = env.split_once('=')?;
                return Some((format!("env {}", k.to_uppercase()), v.to_string()));
            }
            let (k, v) = l.split_once(' ')?;
            Some((k.to_string(), v.to_string()))
        })
        .collect()
}

#[cfg(windows)]
#[test]
fn a_program_runs_from_a_location_that_does_not_exist_and_believes_it_is_there() {
    ensure_inject_artifacts();
    let _launches = lock_launches();

    let content = vfs_testkit::scratch_dir("vloc-content");
    std::fs::copy(
        locate_artifact("vfs-fixture-read.exe"),
        content.join("game.exe"),
    )
    .expect("copy the fixture into the provider's content");
    std::fs::write(content.join("hello.txt"), b"hello").unwrap();
    let out_dir = vfs_testkit::scratch_dir("vloc-out");
    let world = vfs_testkit::scratch_dir("vloc-world");
    // The location and its two new ancestors: none of them exists, before or after.
    let ancestor = world.join("Haskill");
    let location = ancestor.join("list").join("game");

    let mut session = LiveSession::create("vloc");
    session.declare_root(0, &location);
    session
        .add_source(0, "/", 0, Arc::new(DiskProvider::new(&*content)))
        .expect("add source");
    assert_eq!(session.root(), location);

    let out = out_dir.join("probe.txt");
    let env: BTreeMap<String, String> = [
        ("VFS_FIXTURE_PROBE_OUT", out.to_string_lossy().into_owned()),
        // Relative: it resolves against the working directory the program
        // was told it has, which exists only virtually.
        ("VFS_FIXTURE_PATH", "hello.txt".to_string()),
        ("VFS_FIXTURE_EXPECT", "5".to_string()),
    ]
    .into_iter()
    .map(|(k, v)| (k.to_string(), v))
    .collect();
    let code = launch_bounded(
        session.shared(),
        LaunchOpts {
            image: "game.exe".into(),
            env,
            ..Default::default()
        },
        "virtual location",
    );
    assert_eq!(
        code,
        0,
        "the program must read hello.txt through its virtual cwd; probe said: {:?}",
        std::fs::read_to_string(&out).map(|t| t
            .lines()
            .filter(|l| !l.starts_with("env "))
            .collect::<Vec<_>>()
            .join(" | "))
    );

    let facts = probe_facts(&out);
    let exe = location.join("game.exe").to_string_lossy().into_owned();
    let fact = |k: &str| facts.get(k).map(String::as_str);
    assert_eq!(
        fact("cwd"),
        Some(&*location.to_string_lossy()),
        "GetCurrentDirectory"
    );
    assert_eq!(fact("exe"), Some(&*exe), "GetModuleFileName(NULL)");
    assert_eq!(fact("argv0"), Some(&*exe), "argv[0]");
    assert_eq!(
        fact("env VFS_VIRTUAL_DIR"),
        Some(&*location.to_string_lossy()),
        "the shim's root 0 is the location"
    );

    assert!(
        !ancestor.exists(),
        "nothing may be created at or above a virtual location: {} exists",
        ancestor.display()
    );
}

/// A program the launched one starts — by its virtual path, the way a
/// launcher starts the game — runs from its virtual location too: it is
/// staged, started from the backing directory, and told its cwd, module path
/// and argv[0] are the virtual ones; its relative read goes through the
/// virtual cwd it inherited. `vfs-fixture-read`'s child mode starts a copy of
/// itself by `current_exe()` (the virtual path, after the rename) with an
/// environment of its own (one variable removed), and the copy's probe
/// replaces the parent's.
#[cfg(windows)]
#[test]
fn a_child_of_a_program_at_a_virtual_location_runs_there_too() {
    ensure_inject_artifacts();
    let _launches = lock_launches();

    let content = vfs_testkit::scratch_dir("vloc-child-content");
    std::fs::copy(
        locate_artifact("vfs-fixture-read.exe"),
        content.join("game.exe"),
    )
    .unwrap();
    std::fs::write(content.join("hello.txt"), b"hello").unwrap();
    let out_dir = vfs_testkit::scratch_dir("vloc-child-out");
    let world = vfs_testkit::scratch_dir("vloc-child-world");
    let ancestor = world.join("Haskill");
    let location = ancestor.join("list").join("game");

    let mut session = LiveSession::create("vloc-child");
    session.declare_root(0, &location);
    session
        .add_source(0, "/", 0, Arc::new(DiskProvider::new(&*content)))
        .expect("add source");

    let out = out_dir.join("probe.txt");
    let env: BTreeMap<String, String> = [
        ("VFS_FIXTURE_PROBE_OUT", out.to_string_lossy().into_owned()),
        ("VFS_FIXTURE_PATH", "hello.txt".to_string()),
        ("VFS_FIXTURE_EXPECT", "5".to_string()),
        ("VFS_FIXTURE_SPAWN_CHILD", "1".to_string()),
    ]
    .into_iter()
    .map(|(k, v)| (k.to_string(), v))
    .collect();
    let code = launch_bounded(
        session.shared(),
        LaunchOpts {
            image: "game.exe".into(),
            env,
            ..Default::default()
        },
        "virtual location child",
    );
    assert_eq!(
        code, 0,
        "parent and child must both read hello.txt through their virtual cwd"
    );

    let facts = probe_facts(&out);
    let fact = |k: &str| facts.get(k).map(String::as_str);
    assert_eq!(
        fact("env VFS_FIXTURE_SPAWN_CHILD"),
        None,
        "this is the child's probe"
    );
    let exe = location.join("game.exe").to_string_lossy().into_owned();
    assert_eq!(
        fact("cwd"),
        Some(&*location.to_string_lossy()),
        "the child's cwd"
    );
    assert_eq!(fact("exe"), Some(&*exe), "the child's module path");
    assert_eq!(fact("argv0"), Some(&*exe), "the child's argv[0]");
    assert!(!ancestor.exists(), "nothing may appear above the location");
}

/// The SKSE shape: an import-patched launcher at a virtual location starts a
/// program that **cannot** be import-patched (`SkyrimSE.exe` carries Steam's
/// DRM wrapper, data after its last section, which the patcher refuses), by
/// its virtual path spelled with a doubled separator (`<dir>\SkyrimSE.exe`,
/// as SKSE joins it). That child is staged unpatched and the shim is
/// injected into it; it must come up virtualised like any other: its cwd,
/// module path at the location, and its relative read through the virtual
/// cwd.
#[cfg(windows)]
#[test]
fn an_unpatchable_child_at_a_virtual_location_is_injected_and_runs_there() {
    ensure_inject_artifacts();
    let _launches = lock_launches();

    let content = vfs_testkit::scratch_dir("vloc-unpatch-content");
    let fixture = std::fs::read(locate_artifact("vfs-fixture-read.exe")).unwrap();
    std::fs::write(content.join("game.exe"), &fixture).unwrap();
    // Data after the last section: the import patcher refuses such an image.
    let mut child = fixture.clone();
    child.extend(std::iter::repeat_n(0xA5u8, 4096));
    std::fs::write(content.join("Child.exe"), &child).unwrap();
    std::fs::write(content.join("hello.txt"), b"hello").unwrap();
    let out_dir = vfs_testkit::scratch_dir("vloc-unpatch-out");
    let world = vfs_testkit::scratch_dir("vloc-unpatch-world");
    let ancestor = world.join("Haskill");
    let location = ancestor.join("list").join("game");

    let mut session = LiveSession::create("vloc-unpatch");
    session.declare_root(0, &location);
    session
        .add_source(0, "/", 0, Arc::new(DiskProvider::new(&*content)))
        .expect("add source");

    let doubled = format!("{}\\Child.exe", location.display());
    let out = out_dir.join("probe.txt");
    let env: BTreeMap<String, String> = [
        ("VFS_FIXTURE_PROBE_OUT", out.to_string_lossy().into_owned()),
        ("VFS_FIXTURE_PATH", "hello.txt".to_string()),
        ("VFS_FIXTURE_EXPECT", "5".to_string()),
        ("VFS_FIXTURE_SPAWN_CHILD", doubled.clone()),
    ]
    .into_iter()
    .map(|(k, v)| (k.to_string(), v))
    .collect();
    let code = launch_bounded(
        session.shared(),
        LaunchOpts {
            image: "game.exe".into(),
            env,
            ..Default::default()
        },
        "virtual location unpatchable child",
    );
    let refused = std::fs::read_to_string(session.state_dir().join("ready.flag.child-refused"))
        .unwrap_or_default();
    assert_eq!(
        code, 0,
        "the unpatchable child must be injected and read hello.txt; refused: {refused:?}"
    );

    let facts = probe_facts(&out);
    let fact = |k: &str| facts.get(k).map(String::as_str);
    assert_eq!(
        fact("env VFS_FIXTURE_SPAWN_CHILD"),
        None,
        "this is the child's probe"
    );
    let exe = location.join("Child.exe").to_string_lossy().into_owned();
    assert_eq!(
        fact("cwd"),
        Some(&*location.to_string_lossy()),
        "the child's cwd"
    );
    assert_eq!(fact("exe"), Some(&*exe), "the child's module path");
    assert_eq!(
        fact("argv0"),
        Some(&*doubled),
        "argv[0] as the launcher spelled it"
    );
    assert!(!ancestor.exists(), "nothing may appear above the location");

    // The load log says what happened to the child, step by step: it could not
    // be import-patched on disk, so it was patched in memory (not remote
    // injection), its shim's DllMain ran and it came up ready.
    let log = std::fs::read_to_string(session.state_dir().join("shim-load.log"))
        .expect("the shim's load log in the state directory");
    for want in [
        "import-patched false",
        "activation in-memory import patch",
        "ready wait Ready",
        "DllMain attach: image",
        "bootstrap: hooks installed",
        "bootstrap: ready",
    ] {
        assert!(log.contains(want), "load log lacks {want:?}:\n{log}");
    }
    assert!(
        !log.contains("remote injection"),
        "the child should take the in-memory patch, not remote injection:\n{log}"
    );
}

/// What sank the first TPF launch: an unpatchable child (`SkyrimSE.exe`) whose
/// **static imports** need the game's files when the loader initialises them
/// (ENB's `d3d11.dll` reads `enblocal.ini` beside the exe), before any code of
/// the program runs. Injecting the shim after process initialisation is too
/// late: those imports ran first, un-virtualised, in the bare backing
/// directory, failed, and took process start with them. The child here is
/// `vfs-staticimp` (data appended, so the patcher refuses it), statically
/// importing `vproxy.dll`, whose load fails unless `needs.txt` — served only
/// by the VFS — is beside the program.
#[cfg(windows)]
#[test]
fn an_unpatchable_childs_static_imports_see_the_vfs_when_they_initialise() {
    ensure_inject_artifacts();
    let _launches = lock_launches();

    let content = vfs_testkit::scratch_dir("vloc-staticimp-content");
    std::fs::copy(
        locate_artifact("vfs-fixture-read.exe"),
        content.join("game.exe"),
    )
    .unwrap();
    let mut child = std::fs::read(locate_artifact("vfs-staticimp.exe")).unwrap();
    child.extend(std::iter::repeat_n(0xA5u8, 4096));
    std::fs::write(content.join("Child.exe"), &child).unwrap();
    std::fs::copy(locate_artifact("vproxy.dll"), content.join("vproxy.dll")).unwrap();
    std::fs::write(content.join("needs.txt"), b"only in the VFS").unwrap();
    std::fs::write(content.join("hello.txt"), b"hello").unwrap();
    let world = vfs_testkit::scratch_dir("vloc-staticimp-world");
    let location = world.join("Haskill").join("list").join("game");

    let mut session = LiveSession::create("vloc-staticimp");
    session.declare_root(0, &location);
    session
        .add_source(0, "/", 0, Arc::new(DiskProvider::new(&*content)))
        .expect("add source");

    let env: BTreeMap<String, String> = [
        ("VFS_FIXTURE_PATH", "hello.txt".to_string()),
        ("VFS_FIXTURE_EXPECT", "5".to_string()),
        (
            "VFS_FIXTURE_SPAWN_CHILD",
            format!("{}\\Child.exe", location.display()),
        ),
        ("VFS_FIXTURE_VPROXY_NEEDS", "needs.txt".to_string()),
    ]
    .into_iter()
    .map(|(k, v)| (k.to_string(), v))
    .collect();
    let code = launch_bounded(
        session.shared(),
        LaunchOpts {
            image: "game.exe".into(),
            env,
            ..Default::default()
        },
        "unpatchable child with static imports",
    );
    let log =
        std::fs::read_to_string(session.state_dir().join("shim-load.log")).unwrap_or_default();
    assert_eq!(
        code, 0,
        "the child's static import must see needs.txt as it initialises; load log:\n{log}"
    );
}

/// The exact SKSE/Skyrim shape: an import-patched launcher at a virtual
/// location starts an unpatchable child **suspended** (as SKSE creates
/// SkyrimSE.exe) and resumes it itself. The in-memory import patch must make
/// the loader load the shim first on that resume — so the child's static
/// imports (needing a VFS-only file) see the VFS — without the hook resuming
/// the child or waiting for a signal that only comes after the caller's
/// resume. This is what the first two TPF launches got wrong.
#[cfg(windows)]
#[test]
fn a_suspended_unpatchable_child_is_import_patched_in_memory_and_runs() {
    ensure_inject_artifacts();
    let _launches = lock_launches();

    let content = vfs_testkit::scratch_dir("vloc-susp-content");
    std::fs::copy(
        locate_artifact("vfs-fixture-read.exe"),
        content.join("game.exe"),
    )
    .unwrap();
    let mut child = std::fs::read(locate_artifact("vfs-staticimp.exe")).unwrap();
    child.extend(std::iter::repeat_n(0xA5u8, 4096));
    std::fs::write(content.join("Child.exe"), &child).unwrap();
    std::fs::copy(locate_artifact("vproxy.dll"), content.join("vproxy.dll")).unwrap();
    std::fs::write(content.join("needs.txt"), b"only in the VFS").unwrap();
    std::fs::write(content.join("hello.txt"), b"hello").unwrap();
    let world = vfs_testkit::scratch_dir("vloc-susp-world");
    let location = world.join("Haskill").join("list").join("game");

    let mut session = LiveSession::create("vloc-susp");
    session.declare_root(0, &location);
    session
        .add_source(0, "/", 0, Arc::new(DiskProvider::new(&*content)))
        .expect("add source");

    let env: BTreeMap<String, String> = [
        ("VFS_FIXTURE_PATH", "hello.txt".to_string()),
        ("VFS_FIXTURE_EXPECT", "5".to_string()),
        (
            "VFS_FIXTURE_SPAWN_CHILD",
            format!("{}\\Child.exe", location.display()),
        ),
        ("VFS_FIXTURE_SPAWN_SUSPENDED", "1".to_string()),
        ("VFS_FIXTURE_VPROXY_NEEDS", "needs.txt".to_string()),
    ]
    .into_iter()
    .map(|(k, v)| (k.to_string(), v))
    .collect();
    let code = launch_bounded(
        session.shared(),
        LaunchOpts {
            image: "game.exe".into(),
            env,
            ..Default::default()
        },
        "suspended unpatchable child",
    );
    let log =
        std::fs::read_to_string(session.state_dir().join("shim-load.log")).unwrap_or_default();
    assert_eq!(
        code, 0,
        "a suspended unpatchable child must be in-memory import-patched and run; load log:\n{log}"
    );
    assert!(
        log.contains("left suspended for the caller"),
        "the suspended child should take the in-memory patch and be left for the caller:\n{log}"
    );
    assert!(
        !log.contains("remote injection"),
        "the suspended child must not fall back to remote injection:\n{log}"
    );
}
