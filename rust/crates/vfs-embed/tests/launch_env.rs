//! A Windows launch hands its child an environment block built for it, and
//! never writes this process's environment.
//!
//! `vfs-fixture-read`'s probe phase (`VFS_FIXTURE_PROBE_OUT`) writes its own
//! environment to a file outside every root, which is how these tests see
//! what the child got.

// Every test here injects a real Windows process, so on other hosts the helpers are unused.
#![cfg_attr(not(windows), allow(dead_code, unused_imports))]

use std::collections::BTreeMap;
use std::ffi::OsString;
use std::path::{Path, PathBuf};
use std::sync::Arc;

use vfs_embed::{DiskProvider, LaunchOpts};

mod support;
use support::session::LiveSession;
use support::{artifacts::*, launch::*};

/// This process's environment, sorted, for comparing before and after.
fn host_env() -> Vec<(OsString, OsString)> {
    let mut v: Vec<_> = std::env::vars_os().collect();
    v.sort();
    v
}

/// A served session whose root 0 serves one file, so its child has a ring to
/// attach to.
fn session(name: &str, content: &Path) -> LiveSession {
    std::fs::write(content.join("hello.txt"), b"hello").unwrap();
    let mut s = LiveSession::create(name);
    s.add_source(0, "/", 0, Arc::new(DiskProvider::new(content)))
        .expect("add source");
    s
}

/// The probe's options: write what it sees to `out`, and nothing else.
fn probe_opts(out: &Path, env: &[(&str, &str)], remove: &[&str]) -> LaunchOpts {
    let mut map: BTreeMap<String, String> = env
        .iter()
        .map(|(k, v)| (k.to_string(), v.to_string()))
        .collect();
    map.insert(
        "VFS_FIXTURE_PROBE_OUT".into(),
        out.to_string_lossy().into_owned(),
    );
    LaunchOpts {
        image: locate_artifact("vfs-fixture-read.exe")
            .to_string_lossy()
            .into_owned(),
        env: map,
        env_remove: remove.iter().map(|s| s.to_string()).collect(),
        ..Default::default()
    }
}

/// The child's environment, from its probe file, by upper-cased name.
fn child_env(out: &Path) -> BTreeMap<String, String> {
    let text = std::fs::read_to_string(out)
        .unwrap_or_else(|e| panic!("probe output {}: {e}", out.display()));
    text.lines()
        .filter_map(|l| l.strip_prefix("env "))
        .filter_map(|l| l.split_once('='))
        .map(|(k, v)| (k.to_uppercase(), v.to_string()))
        .collect()
}

/// The child gets the ring's variables, `env`, and none of `env_remove`; this
/// process's environment is the same during the launch and after it as it
/// was before serving.
#[cfg(windows)]
#[test]
fn a_launch_builds_the_childs_environment_and_leaves_the_hosts_alone() {
    ensure_inject_artifacts();
    let _launches = lock_launches();
    // A variable the host has, which the child must not inherit (the shape of
    // `SteamAppId`), and one it has that the child should.
    std::env::set_var("VFS_TEST_ENV_INHERITED_DROPPED", "host-value");
    std::env::set_var("VFS_TEST_ENV_INHERITED_KEPT", "host-value");
    let before = host_env();

    let content = vfs_testkit::scratch_dir("launch-env-content");
    let out_dir = vfs_testkit::scratch_dir("launch-env-out");
    let s = session("launch-env", &content);
    assert_eq!(host_env(), before, "serve wrote this process's environment");

    let out = out_dir.join("probe.txt");
    let opts = probe_opts(
        &out,
        &[("VFS_TEST_ENV_SET", "for-the-child")],
        &["vfs_test_env_inherited_dropped"],
    );
    let handle = s.launch_detached(&opts).expect("launch");
    assert_eq!(
        host_env(),
        before,
        "the launch wrote this process's environment while running"
    );
    let exit = handle.wait().expect("wait");
    assert_eq!(exit, vfs_embed::LaunchExit::Exited(0));
    assert_eq!(
        host_env(),
        before,
        "the launch left this process's environment changed"
    );

    let env = child_env(&out);
    assert_eq!(
        env.get("VFS_TEST_ENV_SET").map(String::as_str),
        Some("for-the-child")
    );
    assert_eq!(
        env.get("VFS_TEST_ENV_INHERITED_KEPT").map(String::as_str),
        Some("host-value")
    );
    assert!(
        !env.contains_key("VFS_TEST_ENV_INHERITED_DROPPED"),
        "env_remove (any case) must keep a host variable from the child"
    );
    assert!(
        env.contains_key("VFS_RING_SECTION"),
        "the ring reached the child"
    );
    assert_eq!(
        env.get("VFS_VIRTUAL_DIR").map(PathBuf::from),
        Some(s.root()),
        "the child's root is its session's"
    );
    assert!(
        env.contains_key("PATH"),
        "the host's environment is the base"
    );

    std::env::remove_var("VFS_TEST_ENV_INHERITED_DROPPED");
    std::env::remove_var("VFS_TEST_ENV_INHERITED_KEPT");
}

/// Two sessions serving in one process: each launch hands its child its own
/// session's ring and root, whichever served last. With the ring in this
/// process's environment, the second `serve` repointed the first session's
/// next child.
#[cfg(windows)]
#[test]
fn two_sessions_in_one_process_each_give_their_child_its_own_ring() {
    ensure_inject_artifacts();
    let _launches = lock_launches();
    let content1 = vfs_testkit::scratch_dir("launch-env-two-1");
    let content2 = vfs_testkit::scratch_dir("launch-env-two-2");
    let out_dir = vfs_testkit::scratch_dir("launch-env-two-out");
    let first = session("launch-env-two-1", &content1);
    let second = session("launch-env-two-2", &content2);

    // The first session launches after the second one served.
    let out1 = out_dir.join("first.txt");
    let code = launch_bounded(first.shared(), probe_opts(&out1, &[], &[]), "first");
    assert_eq!(code, 0);
    let out2 = out_dir.join("second.txt");
    let code = launch_bounded(second.shared(), probe_opts(&out2, &[], &[]), "second");
    assert_eq!(code, 0);

    let (e1, e2) = (child_env(&out1), child_env(&out2));
    assert_ne!(
        e1.get("VFS_RING_SECTION"),
        e2.get("VFS_RING_SECTION"),
        "each child must get its own session's ring"
    );
    assert_eq!(
        e1.get("VFS_VIRTUAL_DIR").map(PathBuf::from),
        Some(first.root())
    );
    assert_eq!(
        e2.get("VFS_VIRTUAL_DIR").map(PathBuf::from),
        Some(second.root())
    );
}
