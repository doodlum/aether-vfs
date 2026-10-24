//! A 64-bit session launching a 32-bit program: the 32-bit `vfs-fixture-read`,
//! with the 32-bit shim from `x86\` beside the 64-bit one, reads the composed
//! root (the game folder underneath, a mod file mapped over it, the listing of
//! both) and writes through copy-on-write exactly as a 64-bit program does.
//!
//! Three activations, one per path a 32-bit program can take:
//!
//! - **in-memory import patch** (the default, for a real program outside the
//!   root): the loader of the WOW64 guest finds the 32-bit shim on its `PATH`;
//! - **remote injection** (`Activation::Inject`): the 32-bit `vfs-injector.exe`
//!   runs the guest's `LoadLibrary`;
//! - **staged and import-patched**: a 32-bit program the graph serves is
//!   staged with the 32-bit shim beside it.
//!
//! Needs the `i686-pc-windows-msvc` Rust target; without it each test prints
//! `SKIP` and passes, or fails under `VFS_TEST_REQUIRE_ALL=1`.

#![cfg_attr(not(windows), allow(dead_code, unused_imports))]

use std::collections::BTreeMap;
use std::path::{Path, PathBuf};
use std::sync::Arc;

use vfs_embed::{Activation, DiskProvider, LaunchOpts};

mod support;
use support::session::LiveSession;
use support::{artifacts::*, launch::*};

const BASE: &[u8] = b"base game bytes";
const MOD: &[u8] = b"mod bytes win";

/// The 32-bit fixture, or `None` (after saying why) when it cannot be built.
fn fixture32(test: &str) -> Option<PathBuf> {
    ensure_inject_artifacts();
    match ensure_x86_artifacts() {
        Ok(p) => Some(p),
        Err(e) => {
            let require =
                std::env::var("VFS_TEST_REQUIRE_ALL").is_ok_and(|v| !v.is_empty() && v != "0");
            if require {
                panic!("{test}: no 32-bit artifacts ({e}) and VFS_TEST_REQUIRE_ALL is set");
            }
            eprintln!("SKIP {test}: no 32-bit artifacts: {e}");
            None
        }
    }
}

/// The directories one test composes: the game folder, a mod over it, and
/// the write layer.
struct Layout {
    game: vfs_testkit::Scratch,
    mods: vfs_testkit::Scratch,
    overwrite: vfs_testkit::Scratch,
    out: vfs_testkit::Scratch,
}

fn layout(tag: &str) -> Layout {
    let dir = |n: &str| vfs_testkit::scratch_dir(&format!("vfs-x86-{tag}-{n}"));
    let l = Layout {
        game: dir("game"),
        mods: dir("mods"),
        overwrite: dir("overwrite"),
        out: dir("out"),
    };
    std::fs::create_dir_all(l.game.join("Data")).unwrap();
    std::fs::write(l.game.join("Data").join("base.esm"), BASE).unwrap();
    std::fs::write(l.game.join("Data").join("plugin.esp"), b"game's plugin").unwrap();
    std::fs::create_dir_all(l.mods.join("Data")).unwrap();
    std::fs::write(l.mods.join("Data").join("plugin.esp"), MOD).unwrap();
    std::fs::write(l.mods.join("Data").join("mod-only.esp"), MOD).unwrap();
    l
}

/// The session: the game folder at layer 0, the mod above it, the write layer.
fn session(tag: &str, l: &Layout) -> LiveSession {
    let mut s = LiveSession::create(&format!("x86-{tag}"));
    s.add_source(0, "/", 0, Arc::new(DiskProvider::new(&*l.game)))
        .expect("game layer");
    s.add_source(0, "/", 10, Arc::new(DiskProvider::new(&*l.mods)))
        .expect("mod layer");
    s.set_write_layer(0, Arc::new(DiskProvider::new(&*l.overwrite)))
        .expect("write layer");
    s
}

/// Run the ops script through the 32-bit fixture `image` and check what it saw and wrote.
fn run_and_check(s: &LiveSession, l: &Layout, image: String, activation: Activation) {
    let root = s.root();
    let data = root.join("Data");
    let p = |n: &str| data.join(n).to_string_lossy().into_owned();
    let script = l.out.join("ops.txt");
    let results = l.out.join("ops-out.txt");
    std::fs::write(
        &script,
        [
            format!("read\t{}", p("base.esm")),
            format!("read\t{}", p("plugin.esp")),
            format!("read\t{}", p("mod-only.esp")),
            format!("list\t{}", data.display()),
            format!("write\t{}\tchanged by a 32-bit program", p("base.esm")),
            format!("read\t{}", p("base.esm")),
            format!("write\t{}\tnew file", p("new.txt")),
        ]
        .join("\n"),
    )
    .unwrap();
    let env: BTreeMap<String, String> = [
        ("VFS_FIXTURE_OPS", script.to_string_lossy().into_owned()),
        (
            "VFS_FIXTURE_OPS_OUT",
            results.to_string_lossy().into_owned(),
        ),
    ]
    .into_iter()
    .map(|(k, v)| (k.to_string(), v))
    .collect();
    let code = launch_bounded(
        s.shared(),
        LaunchOpts {
            image: image.clone(),
            wait: true,
            env,
            activation,
            // As an embedding host names it: the 32-bit artifacts are looked for in
            // `x86\` beside this one.
            shim_dll: Some(profile_dir().join("vfs_shim_dll.dll")),
            ..Default::default()
        },
        "x86",
    );
    assert_eq!(code, 0, "the 32-bit fixture {image} failed");
    let got: Vec<String> = std::fs::read_to_string(&results)
        .expect("ops results")
        .lines()
        .map(|l| l.rsplit('\t').next().unwrap_or("").to_string())
        .collect();
    let expected_list = "ok base.esm|mod-only.esp|plugin.esp";
    assert_eq!(
        got,
        [
            format!("ok {}", String::from_utf8_lossy(BASE)),
            format!("ok {}", String::from_utf8_lossy(MOD)),
            format!("ok {}", String::from_utf8_lossy(MOD)),
            expected_list.to_string(),
            "ok".to_string(),
            "ok changed by a 32-bit program".to_string(),
            "ok".to_string(),
        ],
        "what the 32-bit program saw"
    );
    // Copy-on-write: the game folder is untouched; the write layer holds the change.
    assert_eq!(
        std::fs::read(l.game.join("Data").join("base.esm")).unwrap(),
        BASE
    );
    assert_eq!(
        std::fs::read(l.overwrite.join("Data").join("base.esm")).unwrap(),
        b"changed by a 32-bit program"
    );
    assert_eq!(
        std::fs::read(l.overwrite.join("Data").join("new.txt")).unwrap(),
        b"new file"
    );
    assert!(!l.game.join("Data").join("new.txt").exists());
}

fn assert_is_32bit(exe: &Path) {
    let head = std::fs::read(exe).unwrap();
    assert_eq!(
        vfs_pe::pe_machine(&head),
        Some(vfs_pe::MACHINE_I386),
        "{}",
        exe.display()
    );
}

#[cfg(windows)]
#[test]
fn a_32_bit_program_activated_by_the_in_memory_import_patch_sees_the_composed_root() {
    let _guard = lock_launches();
    let Some(fixture) = fixture32("in-memory") else {
        return;
    };
    assert_is_32bit(&fixture);
    let l = layout("mem");
    let s = session("mem", &l);
    run_and_check(
        &s,
        &l,
        fixture.to_string_lossy().into_owned(),
        Activation::Import,
    );
}

#[cfg(windows)]
#[test]
fn a_32_bit_program_injected_through_the_32_bit_injector_sees_the_composed_root() {
    let _guard = lock_launches();
    let Some(fixture) = fixture32("inject") else {
        return;
    };
    let l = layout("inj");
    let s = session("inj", &l);
    run_and_check(
        &s,
        &l,
        fixture.to_string_lossy().into_owned(),
        Activation::Inject,
    );
}

/// The 32-bit shim follows its program across `CreateProcess`: a copy of the
/// fixture it starts (running, then suspended and resumed by the parent, SKSE's
/// shape) reads a file only the graph holds, so only an injected child passes.
#[cfg(windows)]
#[test]
fn a_32_bit_program_s_32_bit_children_are_virtualised_too() {
    let _guard = lock_launches();
    let Some(fixture) = fixture32("children") else {
        return;
    };
    let l = layout("kid");
    let s = session("kid", &l);
    let path = s.root().join("Data").join("mod-only.esp");
    for suspended in [false, true] {
        let mut env: BTreeMap<String, String> = [
            ("VFS_FIXTURE_PATH", path.to_string_lossy().into_owned()),
            ("VFS_FIXTURE_EXPECT", MOD.len().to_string()),
            ("VFS_FIXTURE_SPAWN_CHILD", "1".to_string()),
        ]
        .into_iter()
        .map(|(k, v)| (k.to_string(), v))
        .collect();
        if suspended {
            env.insert("VFS_FIXTURE_SPAWN_SUSPENDED".into(), "1".into());
        }
        let code = launch_bounded(
            s.shared(),
            LaunchOpts {
                image: fixture.to_string_lossy().into_owned(),
                wait: true,
                env,
                shim_dll: Some(profile_dir().join("vfs_shim_dll.dll")),
                ..Default::default()
            },
            "x86-children",
        );
        assert_eq!(code, 0, "suspended: {suspended}");
    }
}

/// The pieces the launch applies to a suspended WOW64 guest, from this 64-bit
/// process, one at a time: its width, the in-memory import patch (PE32, through
/// the PEB32) and the 32-bit stack growth. The launches above fall back to
/// injection when the patch is refused, so this is what proves the patch itself.
#[cfg(windows)]
#[test]
fn a_suspended_32_bit_process_is_patched_in_memory_from_a_64_bit_one() {
    #![allow(unsafe_code)]
    use std::os::windows::io::AsRawHandle;
    use std::os::windows::process::CommandExt;
    let Some(fixture) = fixture32("wow64-patch") else {
        return;
    };
    const CREATE_SUSPENDED: u32 = 0x4;
    let mut child = std::process::Command::new(&fixture)
        .creation_flags(CREATE_SUSPENDED)
        .spawn()
        .expect("spawn the 32-bit fixture suspended");
    let process = child.as_raw_handle();
    // SAFETY: a suspended process this test created, with every right.
    let guest = unsafe { vfs_inject::Guest::of(process) };
    // SAFETY: as above; it has not run.
    let patched = unsafe { vfs_inject::patch_imports_in_memory(process) };
    let _ = child.kill();
    let _ = child.wait();
    assert_eq!(guest, Ok(vfs_inject::Guest::X86));
    assert!(patched.is_ok(), "{patched:?}");
}

/// A 32-bit program the graph serves (it is in the game folder, not on disk under
/// the root) is staged with the 32-bit shim beside it and patched to import it.
#[cfg(windows)]
#[test]
fn a_staged_32_bit_program_is_patched_for_the_32_bit_shim() {
    let _guard = lock_launches();
    let Some(fixture) = fixture32("staged") else {
        return;
    };
    let l = layout("stg");
    std::fs::copy(&fixture, l.game.join("Game32.exe")).unwrap();
    let s = session("stg", &l);
    run_and_check(&s, &l, "Game32.exe".into(), Activation::Import);
    assert!(
        s.staged_unpatched().is_empty(),
        "the 32-bit EXE was staged unpatched: {:?}",
        s.staged_unpatched()
    );
}
