//! Nothing real at or under a root's location: the canary tests.
//!
//! A program (`vfs-fixture-read`'s ops phase, launched from root 0) lists,
//! reads, writes, creates folders and renames at a root's location and in its
//! ancestors. It must see only what the root's providers serve, its writes
//! must land in the root's write layer, and real disk must be exactly as it
//! was:
//!
//! - with a real directory at the location, holding a canary file, which the
//!   program must not see and which stays byte-for-byte unchanged;
//! - with nothing real there, where afterwards nothing exists — not the
//!   location, not any ancestor the program "created";
//! - with a root nested inside another root's location, where writes outside
//!   the child but inside the parent land in the parent's write layer.

// Every test here injects a real Windows process, so on other hosts the helpers are unused.
#![cfg_attr(not(windows), allow(dead_code, unused_imports))]

use std::collections::BTreeMap;
use std::path::Path;
use std::sync::Arc;

use vfs_embed::{DiskProvider, LaunchOpts};

mod support;
use support::session::LiveSession;
use support::{artifacts::*, launch::*};

/// Every file and directory under `dir`, relative, with each file's bytes.
fn tree(dir: &Path) -> BTreeMap<String, Option<Vec<u8>>> {
    let mut out = BTreeMap::new();
    fn walk(base: &Path, dir: &Path, out: &mut BTreeMap<String, Option<Vec<u8>>>) {
        let Ok(rd) = std::fs::read_dir(dir) else {
            return;
        };
        for e in rd.flatten() {
            let p = e.path();
            let rel = p.strip_prefix(base).unwrap().to_string_lossy().into_owned();
            if p.is_dir() {
                out.insert(rel, None);
                walk(base, &p, out);
            } else {
                out.insert(rel, Some(std::fs::read(&p).unwrap_or_default()));
            }
        }
    }
    walk(dir, dir, &mut out);
    out
}

/// Root 0: a location holding the program (`game.exe`) and `hello.txt`
/// (`virtual`) and `sub\inner.txt`, from a provider; writes go to `upper`.
struct Game {
    session: LiveSession,
    upper: vfs_testkit::Scratch,
    _content: vfs_testkit::Scratch,
}

fn game(name: &str, location: &Path) -> Game {
    let content = vfs_testkit::scratch_dir(&format!("{name}-content"));
    std::fs::copy(
        locate_artifact("vfs-fixture-read.exe"),
        content.join("game.exe"),
    )
    .unwrap();
    std::fs::write(content.join("hello.txt"), b"virtual").unwrap();
    std::fs::create_dir_all(content.join("sub")).unwrap();
    std::fs::write(content.join("sub").join("inner.txt"), b"inner").unwrap();
    let upper = vfs_testkit::scratch_dir(&format!("{name}-upper"));
    let mut session = LiveSession::create(name);
    session.declare_root(0, location);
    session
        .add_source(0, "/", 0, Arc::new(DiskProvider::new(&*content)))
        .expect("add source");
    session
        .set_write_layer(0, Arc::new(DiskProvider::new(&*upper)))
        .expect("write layer");
    Game {
        session,
        upper,
        _content: content,
    }
}

/// Run `ops` (one `op\targ…` per entry) in the program, and return each
/// operation's result by its line.
fn run_ops(session: &LiveSession, name: &str, ops: &[String]) -> BTreeMap<String, String> {
    let io = vfs_testkit::scratch_dir(&format!("{name}-ops"));
    let script = io.join("ops.txt");
    let out = io.join("out.txt");
    std::fs::write(&script, ops.join("\n")).unwrap();
    let env: BTreeMap<String, String> = [
        ("VFS_FIXTURE_OPS", script.to_string_lossy().into_owned()),
        ("VFS_FIXTURE_OPS_OUT", out.to_string_lossy().into_owned()),
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
        name,
    );
    assert_eq!(code, 0, "the ops fixture must run to the end");
    std::fs::read_to_string(&out)
        .expect("ops output")
        .lines()
        .filter_map(|l| {
            let (op, result) = l.rsplit_once('\t')?;
            Some((op.to_string(), result.to_string()))
        })
        .collect()
}

fn op(parts: &[&dyn AsRef<std::ffi::OsStr>]) -> String {
    parts
        .iter()
        .map(|p| p.as_ref().to_string_lossy().into_owned())
        .collect::<Vec<_>>()
        .join("\t")
}

/// What `results` says `line` did.
fn did<'a>(results: &'a BTreeMap<String, String>, line: &str) -> &'a str {
    results
        .get(line)
        .unwrap_or_else(|| panic!("no result for {line:?} in {results:#?}"))
}

/// The operations both location tests run, at `loc`.
fn location_ops(loc: &Path) -> Vec<String> {
    vec![
        op(&[&"list", &loc]),
        op(&[&"read", &loc.join("hello.txt")]),
        op(&[&"read", &loc.join("canary.txt")]),
        op(&[&"stat", &loc.join("canary.txt")]),
        op(&[&"list", &loc.join("sub")]),
        op(&[&"write", &loc.join("new.txt"), &"written"]),
        op(&[&"read", &loc.join("new.txt")]),
        op(&[&"mkdir", &loc.join("newdir")]),
        op(&[&"mkdirs", &loc.join("a").join("b").join("c")]),
        op(&[
            &"rename",
            &loc.join("new.txt"),
            &loc.join("newdir").join("moved.txt"),
        ]),
        op(&[&"read", &loc.join("newdir").join("moved.txt")]),
        op(&[&"stat", &loc.join("new.txt")]),
        op(&[&"list", &loc]),
    ]
}

/// The results both location tests expect of [`location_ops`]: only the
/// providers' content, and every write landing.
fn assert_location_results(r: &BTreeMap<String, String>, loc: &Path) {
    let list = op(&[&"list", &loc]);
    // The two `list`s of the root are the same line; the map keeps the last.
    // Staging's own files (the shim DLL beside the staged image, which the
    // loader needs) are served at the root too; nothing real is.
    let names: Vec<&str> = did(r, &list)
        .strip_prefix("ok ")
        .expect("the root lists")
        .split('|')
        .filter(|n| !n.eq_ignore_ascii_case("vfs_shim_dll.dll"))
        .collect();
    assert_eq!(
        names,
        ["a", "game.exe", "hello.txt", "newdir", "sub"],
        "the root lists only the providers' content and the program's writes"
    );
    assert_eq!(
        did(r, &op(&[&"read", &loc.join("hello.txt")])),
        "ok virtual"
    );
    assert!(did(r, &op(&[&"read", &loc.join("canary.txt")])).starts_with("err NotFound"));
    assert!(did(r, &op(&[&"stat", &loc.join("canary.txt")])).starts_with("err NotFound"));
    assert_eq!(did(r, &op(&[&"list", &loc.join("sub")])), "ok inner.txt");
    assert_eq!(
        did(r, &op(&[&"write", &loc.join("new.txt"), &"written"])),
        "ok"
    );
    assert_eq!(did(r, &op(&[&"read", &loc.join("new.txt")])), "ok written");
    assert_eq!(did(r, &op(&[&"mkdir", &loc.join("newdir")])), "ok");
    assert_eq!(
        did(r, &op(&[&"mkdirs", &loc.join("a").join("b").join("c")])),
        "ok"
    );
    assert_eq!(
        did(
            r,
            &op(&[
                &"rename",
                &loc.join("new.txt"),
                &loc.join("newdir").join("moved.txt")
            ])
        ),
        "ok"
    );
    assert_eq!(
        did(r, &op(&[&"read", &loc.join("newdir").join("moved.txt")])),
        "ok written"
    );
    assert!(did(r, &op(&[&"stat", &loc.join("new.txt")])).starts_with("err NotFound"));
}

/// The program's writes, as they landed in the write layer.
fn assert_upper_holds_the_writes(upper: &Path) {
    let t = tree(upper);
    assert_eq!(
        t.get("newdir\\moved.txt"),
        Some(&Some(b"written".to_vec())),
        "the write and the rename landed in the write layer: {t:#?}"
    );
    assert!(
        t.contains_key("a\\b\\c"),
        "mkdirs landed in the write layer: {t:#?}"
    );
}

/// A real directory at the location, with a canary file and folder in it,
/// is invisible to the program and unchanged afterwards; so are its real
/// ancestors.
#[cfg(windows)]
#[test]
fn a_real_directory_at_a_location_is_invisible_and_unchanged() {
    ensure_inject_artifacts();
    let _launches = lock_launches();
    let world = vfs_testkit::scratch_dir("vcanary-real-world");
    let loc = world.join("Haskill").join("list").join("game");
    std::fs::create_dir_all(loc.join("canary-dir")).unwrap();
    std::fs::write(loc.join("canary.txt"), b"REAL").unwrap();
    std::fs::write(loc.join("canary-dir").join("x.txt"), b"REAL X").unwrap();
    let before = tree(&world);

    let g = game("vcanary-real", &loc);
    let ops = location_ops(&loc);
    let r = run_ops(&g.session, "vcanary-real", &ops);
    assert_location_results(&r, &loc);
    assert_upper_holds_the_writes(&g.upper);
    assert_eq!(
        tree(&world),
        before,
        "real disk at and above the location must be unchanged"
    );
}

/// With nothing real at the location, nothing real appears: not the
/// location, not an ancestor the program "creates", and a write off the
/// folder chain in an ancestor that exists only virtually is refused.
#[cfg(windows)]
#[test]
fn nothing_appears_at_or_above_a_location_with_nothing_real_there() {
    ensure_inject_artifacts();
    let _launches = lock_launches();
    let world = vfs_testkit::scratch_dir("vcanary-none-world");
    let haskill = world.join("Haskill");
    let loc = haskill.join("list").join("game");

    let g = game("vcanary-none", &loc);
    let mut ops = location_ops(&loc);
    let ancestor_ops = [
        op(&[&"list", &haskill]),
        op(&[&"stat", &haskill]),
        op(&[&"stat", &haskill.join("list")]),
        op(&[&"mkdirs", &loc]),
        op(&[&"mkdir", &haskill.join("list")]),
        op(&[&"write", &haskill.join("other.txt"), &"no"]),
        op(&[&"mkdir", &haskill.join("other")]),
    ];
    ops.extend(ancestor_ops.iter().cloned());
    let r = run_ops(&g.session, "vcanary-none", &ops);
    assert_location_results(&r, &loc);
    assert_upper_holds_the_writes(&g.upper);

    assert_eq!(
        did(&r, &ancestor_ops[0]),
        "ok list",
        "an ancestor lists the chain to the root"
    );
    assert_eq!(did(&r, &ancestor_ops[1]), "ok dir");
    assert_eq!(did(&r, &ancestor_ops[2]), "ok dir");
    assert_eq!(
        did(&r, &ancestor_ops[3]),
        "ok",
        "a folder chain to the root succeeds"
    );
    assert!(
        did(&r, &ancestor_ops[4]).starts_with("err AlreadyExists"),
        "an ancestor exists: {}",
        did(&r, &ancestor_ops[4])
    );
    assert!(
        did(&r, &ancestor_ops[5]).starts_with("err PermissionDenied"),
        "a write off the chain in a virtual ancestor is refused: {}",
        did(&r, &ancestor_ops[5])
    );
    assert!(
        did(&r, &ancestor_ops[6]).starts_with("err PermissionDenied"),
        "a folder off the chain in a virtual ancestor is refused: {}",
        did(&r, &ancestor_ops[6])
    );

    assert!(
        !haskill.exists(),
        "nothing may appear at or above a virtual location: {}",
        haskill.display()
    );
}

/// A root nested inside another root's location: the parent lists the
/// folder chain to the child, writes outside the child but inside the parent
/// land in the parent's write layer, a move from the parent into the child
/// lands in the child's, and real disk is unchanged.
#[cfg(windows)]
#[test]
fn a_nested_root_and_its_parent_each_keep_their_own_writes() {
    ensure_inject_artifacts();
    let _launches = lock_launches();
    let world = vfs_testkit::scratch_dir("vcanary-nest-world");
    let game_loc = world.join("Haskill").join("game");
    let profile = world.join("Users").join("u");
    let my_games = profile.join("Documents").join("My Games");
    // Real canaries at both locations.
    std::fs::create_dir_all(&my_games).unwrap();
    std::fs::write(profile.join("canary.txt"), b"REAL PROFILE").unwrap();
    std::fs::write(my_games.join("canary.txt"), b"REAL MY GAMES").unwrap();
    let before = tree(&world.join("Users"));

    let mut g = game("vcanary-nest", &game_loc);
    let empty = vfs_testkit::scratch_dir("vcanary-nest-empty");
    let profile_upper = vfs_testkit::scratch_dir("vcanary-nest-profile-upper");
    let child_upper = vfs_testkit::scratch_dir("vcanary-nest-child-upper");
    g.session.declare_root(4, &profile);
    g.session.declare_root(1, &my_games);
    for (id, upper) in [(4, &profile_upper), (1, &child_upper)] {
        g.session
            .add_source(id, "/", 0, Arc::new(DiskProvider::new(&*empty)))
            .expect("add empty source");
        g.session
            .set_write_layer(id, Arc::new(DiskProvider::new(&**upper)))
            .expect("write layer");
    }

    let temp = profile.join("AppData").join("Local").join("Temp");
    let ops = vec![
        op(&[&"list", &profile]),
        op(&[&"list", &profile.join("Documents")]),
        op(&[&"read", &profile.join("canary.txt")]),
        op(&[&"read", &my_games.join("canary.txt")]),
        op(&[
            &"write",
            &profile.join("Documents").join("stray.txt"),
            &"stray",
        ]),
        op(&[&"mkdirs", &temp]),
        op(&[&"write", &temp.join("t.tmp"), &"tmp"]),
        op(&[&"write", &my_games.join("Skyrim.ini"), &"[General]"]),
        op(&[&"rename", &temp.join("t.tmp"), &my_games.join("moved.tmp")]),
        op(&[&"read", &my_games.join("moved.tmp")]),
        op(&[&"stat", &temp.join("t.tmp")]),
    ];
    let r = run_ops(&g.session, "vcanary-nest", &ops);
    assert_eq!(
        did(&r, &ops[0]),
        "ok Documents",
        "the parent shows only the chain to the child"
    );
    assert_eq!(did(&r, &ops[1]), "ok My Games");
    assert!(
        did(&r, &ops[2]).starts_with("err NotFound"),
        "{}",
        did(&r, &ops[2])
    );
    assert!(
        did(&r, &ops[3]).starts_with("err NotFound"),
        "{}",
        did(&r, &ops[3])
    );
    for (i, line) in ops.iter().enumerate().skip(4).take(5) {
        assert_eq!(did(&r, line), "ok", "operation {i}: {line}");
    }
    assert_eq!(
        did(&r, &ops[9]),
        "ok tmp",
        "a move from the parent into the child"
    );
    assert!(did(&r, &ops[10]).starts_with("err NotFound"));

    let p = tree(&profile_upper);
    let c = tree(&child_upper);
    assert_eq!(
        p.get("Documents\\stray.txt"),
        Some(&Some(b"stray".to_vec())),
        "{p:#?}"
    );
    assert!(p.contains_key("AppData\\Local\\Temp"), "{p:#?}");
    assert!(
        !p.contains_key("AppData\\Local\\Temp\\t.tmp"),
        "moved away: {p:#?}"
    );
    assert_eq!(
        c.get("Skyrim.ini"),
        Some(&Some(b"[General]".to_vec())),
        "{c:#?}"
    );
    assert_eq!(c.get("moved.tmp"), Some(&Some(b"tmp".to_vec())), "{c:#?}");
    assert!(
        !c.contains_key("Documents"),
        "the parent's writes stay in the parent: {c:#?}"
    );
    assert_eq!(
        tree(&world.join("Users")),
        before,
        "real disk under the profile must be unchanged"
    );
    assert!(!world.join("Haskill").exists());
}
