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
