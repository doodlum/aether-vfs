//! A director root mounted over a real "game" folder, read and written with
//! plain `std::fs` calls (and a child process), the way every program on the
//! machine sees it. Needs `/dev/fuse` and `fusermount3`; skips without them.

#![cfg(target_os = "linux")]

use std::collections::BTreeMap;
use std::io::{Read, Write};
use std::path::{Path, PathBuf};
use std::process::Command;
use std::sync::Arc;
use std::time::{Duration, Instant};

use vfs_compose::{DiskProvider, MapProvider, MountGraph, OverlayProvider, ReadOnlyProvider};
use vfs_director::Director;
use vfs_fuse::{
    mount_state, recover_stale_mount, BaseDir, FuseMount, MountOpts, MountState, Unmounted,
};
use vfs_provider::{Provider, RootId};

const ROOT: RootId = RootId(0);

fn fuse_available() -> bool {
    let helper = ["fusermount3", "fusermount"].iter().any(|b| {
        Command::new(b)
            .arg("-V")
            .output()
            .map(|o| o.status.success())
            .unwrap_or(false)
    });
    if !(Path::new("/dev/fuse").exists() && helper) {
        eprintln!("skipped: no /dev/fuse or fusermount3");
        return false;
    }
    true
}

fn scratch(name: &str) -> PathBuf {
    let dir = vfs_testkit::scratch_path(&format!("vfs-fuse-it-{name}"));
    if mount_state(dir.join("Game")).ok() == Some(MountState::Stale) {
        let _ = recover_stale_mount(dir.join("Game"));
    }
    let _ = std::fs::remove_dir_all(&dir);
    std::fs::create_dir_all(&dir).unwrap();
    dir
}

/// Every file under `dir` and its content: what "the real folder is unchanged"
/// compares.
fn snapshot(dir: &Path) -> BTreeMap<String, Vec<u8>> {
    fn walk(base: &Path, dir: &Path, out: &mut BTreeMap<String, Vec<u8>>) {
        for e in std::fs::read_dir(dir).unwrap() {
            let e = e.unwrap();
            let p = e.path();
            let rel = p.strip_prefix(base).unwrap().to_string_lossy().into_owned();
            if e.file_type().unwrap().is_dir() {
                out.insert(format!("{rel}/"), Vec::new());
                walk(base, &p, out);
            } else {
                out.insert(rel, std::fs::read(&p).unwrap());
            }
        }
    }
    let mut out = BTreeMap::new();
    walk(dir, dir, &mut out);
    out
}

/// What `vfs-embed`'s `compose_root` builds for a root with a base folder,
/// a file map and a write folder.
fn compose(base: &BaseDir, files: &[(&str, PathBuf)], write: &Path) -> Arc<dyn Provider> {
    let base: Arc<dyn Provider> = Arc::new(ReadOnlyProvider::new(Arc::new(base.provider())));
    let map: Arc<dyn Provider> =
        Arc::new(MapProvider::new(files.iter().map(|(p, s)| (*p, s.clone()))).unwrap());
    let graph = MountGraph::new(vec![(String::new(), base), (String::new(), map)]).unwrap();
    std::fs::create_dir_all(write).unwrap();
    Arc::new(
        OverlayProvider::from_arcs(Arc::new(graph), Arc::new(DiskProvider::new(write))).unwrap(),
    )
}

struct Fixture {
    dir: PathBuf,
    game: PathBuf,
    staging: PathBuf,
    write: PathBuf,
    base: BaseDir,
    director: Arc<Director>,
}

fn fixture(name: &str) -> Fixture {
    let dir = scratch(name);
    let game = dir.join("Game");
    let staging = dir.join("staging");
    let write = dir.join("overwrite");
    std::fs::create_dir_all(game.join("Data")).unwrap();
    std::fs::create_dir_all(&staging).unwrap();
    std::fs::write(game.join("base.txt"), b"bbbbb").unwrap();
    std::fs::write(game.join("Data/Existing.esm"), b"eee").unwrap();
    std::fs::write(staging.join("plugin.bin"), b"mmmmmmm").unwrap();
    std::fs::write(staging.join("late.txt"), b"deployed later").unwrap();
    // Opened before anything covers the folder.
    let base = BaseDir::open(&game).unwrap();
    Fixture {
        dir,
        game,
        staging,
        write,
        base,
        director: Arc::new(Director::new()),
    }
}

fn names(dir: &Path) -> Vec<String> {
    let mut v: Vec<String> = std::fs::read_dir(dir)
        .unwrap()
        .map(|e| e.unwrap().file_name().to_string_lossy().into_owned())
        .collect();
    v.sort();
    v
}

#[test]
fn serves_the_composed_root_at_the_real_location() {
    if !fuse_available() {
        return;
    }
    let f = fixture("serve");
    let before = snapshot(&f.game);
    let base_mtime = std::fs::metadata(f.game.join("base.txt"))
        .unwrap()
        .modified()
        .unwrap();
    f.director
        .mount(
            ROOT,
            compose(
                &f.base,
                &[("Data/plugin.bin", f.staging.join("plugin.bin"))],
                &f.write,
            ),
        )
        .unwrap();
    let mount = FuseMount::mount(f.director.clone(), ROOT, &f.game, MountOpts::default()).unwrap();
    assert_eq!(mount_state(&f.game).unwrap(), MountState::Live);

    // The mapped file, the real file under it, and both under other spellings.
    assert_eq!(
        std::fs::read(f.game.join("Data/plugin.bin")).unwrap(),
        b"mmmmmmm"
    );
    assert_eq!(std::fs::read(f.game.join("base.txt")).unwrap(), b"bbbbb");
    assert_eq!(
        std::fs::read(f.game.join("data/PLUGIN.BIN")).unwrap(),
        b"mmmmmmm"
    );
    assert_eq!(
        std::fs::read(f.game.join("DATA/existing.ESM")).unwrap(),
        b"eee"
    );
    assert_eq!(std::fs::read(f.game.join("BASE.TXT")).unwrap(), b"bbbbb");

    // Sizes, and the real file's own modification time.
    let meta = std::fs::metadata(f.game.join("base.txt")).unwrap();
    assert_eq!(meta.len(), 5);
    assert_eq!(
        meta.modified()
            .unwrap()
            .duration_since(std::time::UNIX_EPOCH)
            .unwrap()
            .as_secs(),
        base_mtime
            .duration_since(std::time::UNIX_EPOCH)
            .unwrap()
            .as_secs()
    );
    assert_eq!(
        std::fs::metadata(f.game.join("Data/plugin.bin"))
            .unwrap()
            .len(),
        7
    );

    // Listings merge both layers.
    assert_eq!(names(&f.game), ["Data", "base.txt"]);
    assert_eq!(names(&f.game.join("Data")), ["Existing.esm", "plugin.bin"]);

    // Another process sees the same thing.
    let out = Command::new("cat")
        .arg(f.game.join("data/plugin.bin"))
        .output()
        .unwrap();
    assert_eq!(out.stdout, b"mmmmmmm");

    // New files, changes to real files, new folders, renames and deletes
    // all land in the write folder.
    std::fs::write(f.game.join("Data/written.txt"), b"from the game").unwrap();
    {
        let mut log = std::fs::OpenOptions::new()
            .append(true)
            .open(f.game.join("base.txt"))
            .unwrap();
        log.write_all(b"+more").unwrap();
    }
    assert_eq!(
        std::fs::read(f.game.join("base.txt")).unwrap(),
        b"bbbbb+more"
    );
    std::fs::create_dir(f.game.join("Saves")).unwrap();
    std::fs::write(f.game.join("saves/one.sav"), b"save").unwrap();
    std::fs::rename(
        f.game.join("Data/written.txt"),
        f.game.join("Data/renamed.txt"),
    )
    .unwrap();
    assert!(!f.game.join("Data/written.txt").exists());
    assert_eq!(
        std::fs::read(f.game.join("Data/renamed.txt")).unwrap(),
        b"from the game"
    );
    std::fs::remove_file(f.game.join("Data/Existing.esm")).unwrap();
    assert!(!f.game.join("Data/Existing.esm").exists());
    assert_eq!(
        std::fs::remove_dir(f.game.join("Saves"))
            .unwrap_err()
            .raw_os_error(),
        Some(39), // ENOTEMPTY
    );
    assert_eq!(
        std::fs::read(f.write.join("Data/renamed.txt")).unwrap(),
        b"from the game"
    );
    assert_eq!(
        std::fs::read(f.write.join("base.txt")).unwrap(),
        b"bbbbb+more"
    );
    assert_eq!(
        std::fs::read(f.write.join("Saves/one.sav")).unwrap(),
        b"save"
    );
    // Truncation through the mount.
    std::fs::write(f.game.join("Data/renamed.txt"), b"x").unwrap();
    assert_eq!(
        std::fs::read(f.game.join("data/renamed.txt")).unwrap(),
        b"x"
    );

    // The real folder, read underneath the mount, is untouched.
    let real = f.base.provider();
    let st = real
        .getattr(vfs_provider::VPath::at_default("base.txt"))
        .unwrap()
        .unwrap();
    assert_eq!(st.size, 5);

    assert_eq!(mount.unmount().unwrap(), Unmounted::Clean);
    assert_eq!(mount_state(&f.game).unwrap(), MountState::None);
    assert_eq!(snapshot(&f.game), before, "the real game folder changed");
    let _ = std::fs::remove_dir_all(&f.dir);
}

#[test]
fn a_remount_takes_effect_at_the_next_lookup() {
    if !fuse_available() {
        return;
    }
    let f = fixture("remount");
    f.director
        .mount(
            ROOT,
            compose(
                &f.base,
                &[("Data/plugin.bin", f.staging.join("plugin.bin"))],
                &f.write,
            ),
        )
        .unwrap();
    // A long TTL, so only the invalidation can make the change visible.
    let mount = FuseMount::mount(
        f.director.clone(),
        ROOT,
        &f.game,
        MountOpts {
            ttl: Duration::from_secs(600),
            ..MountOpts::default()
        },
    )
    .unwrap();
    assert_eq!(
        std::fs::read(f.game.join("Data/plugin.bin")).unwrap(),
        b"mmmmmmm"
    );
    assert!(!f.game.join("Data/late.txt").exists());
    let mut held = std::fs::File::open(f.game.join("Data/plugin.bin")).unwrap();

    // A deployment changes: one file purged, another added.
    f.director
        .mount(
            ROOT,
            compose(
                &f.base,
                &[("Data/late.txt", f.staging.join("late.txt"))],
                &f.write,
            ),
        )
        .unwrap();
    mount.invalidate();

    assert!(!f.game.join("Data/plugin.bin").exists());
    assert_eq!(
        std::fs::read(f.game.join("Data/late.txt")).unwrap(),
        b"deployed later"
    );
    assert_eq!(names(&f.game.join("Data")), ["Existing.esm", "late.txt"]);
    // A handle opened before keeps reading what it opened.
    let mut buf = String::new();
    held.read_to_string(&mut buf).unwrap();
    assert_eq!(buf, "mmmmmmm");
    drop(held);

    assert_eq!(mount.unmount().unwrap(), Unmounted::Clean);
    let _ = std::fs::remove_dir_all(&f.dir);
}

#[test]
fn a_busy_mount_unmounts_lazily_and_a_second_mount_is_refused() {
    if !fuse_available() {
        return;
    }
    let f = fixture("busy");
    f.director
        .mount(
            ROOT,
            compose(
                &f.base,
                &[("Data/plugin.bin", f.staging.join("plugin.bin"))],
                &f.write,
            ),
        )
        .unwrap();
    let mount = FuseMount::mount(f.director.clone(), ROOT, &f.game, MountOpts::default()).unwrap();
    let again = FuseMount::mount(f.director.clone(), ROOT, &f.game, MountOpts::default());
    assert_eq!(again.unwrap_err().kind(), std::io::ErrorKind::AlreadyExists);

    let mut held = std::fs::File::open(f.game.join("Data/plugin.bin")).unwrap();
    assert_eq!(mount.unmount().unwrap(), Unmounted::Lazy);
    // The folder is the real one again at once...
    assert_eq!(mount_state(&f.game).unwrap(), MountState::None);
    assert!(!f.game.join("Data/plugin.bin").exists());
    // ...while what was open keeps working.
    let mut buf = String::new();
    held.read_to_string(&mut buf).unwrap();
    assert_eq!(buf, "mmmmmmm");
    drop(held);
    let _ = std::fs::remove_dir_all(&f.dir);
}

/// Not a test on its own: the child [`a_stale_mount_is_recovered`] starts and
/// kills, so that a mount is left behind with no process serving it.
#[test]
#[ignore = "run by a_stale_mount_is_recovered"]
fn stale_mount_child() {
    let Some(game) = std::env::var_os("VFS_TEST_FUSE_CHILD_GAME") else {
        return;
    };
    let game = PathBuf::from(game);
    let director = Arc::new(Director::new());
    let base = BaseDir::open(&game).unwrap();
    director
        .mount(
            ROOT,
            Arc::new(ReadOnlyProvider::new(Arc::new(base.provider()))),
        )
        .unwrap();
    let mount = FuseMount::mount(director, ROOT, &game, MountOpts::default()).unwrap();
    std::fs::write(std::env::var_os("VFS_TEST_FUSE_CHILD_READY").unwrap(), b"").unwrap();
    std::thread::sleep(Duration::from_secs(120));
    drop(mount);
}

#[test]
fn a_stale_mount_is_recovered() {
    if !fuse_available() {
        return;
    }
    let f = fixture("stale");
    let ready = f.dir.join("ready");
    let mut child = Command::new(std::env::current_exe().unwrap())
        .args(["--ignored", "--exact", "stale_mount_child", "--nocapture"])
        .env("VFS_TEST_FUSE_CHILD_GAME", &f.game)
        .env("VFS_TEST_FUSE_CHILD_READY", &ready)
        .spawn()
        .unwrap();
    let deadline = Instant::now() + Duration::from_secs(30);
    while !ready.exists() {
        assert!(Instant::now() < deadline, "the child never mounted");
        std::thread::sleep(Duration::from_millis(20));
    }
    assert_eq!(mount_state(&f.game).unwrap(), MountState::Live);
    // A crash: SIGKILL, no unmount.
    child.kill().unwrap();
    child.wait().unwrap();
    assert_eq!(mount_state(&f.game).unwrap(), MountState::Stale);
    assert_eq!(
        std::fs::read_dir(&f.game).unwrap_err().raw_os_error(),
        Some(107) // ENOTCONN
    );

    // The next start recovers it on the way to mounting.
    f.director
        .mount(
            ROOT,
            compose(
                &f.base,
                &[("Data/plugin.bin", f.staging.join("plugin.bin"))],
                &f.write,
            ),
        )
        .unwrap();
    let mount = FuseMount::mount(f.director.clone(), ROOT, &f.game, MountOpts::default()).unwrap();
    assert_eq!(
        std::fs::read(f.game.join("Data/plugin.bin")).unwrap(),
        b"mmmmmmm"
    );
    assert_eq!(mount.unmount().unwrap(), Unmounted::Clean);
    assert_eq!(mount_state(&f.game).unwrap(), MountState::None);
    assert!(
        !recover_stale_mount(&f.game).unwrap(),
        "nothing left to recover"
    );
    assert_eq!(std::fs::read(f.game.join("base.txt")).unwrap(), b"bbbbb");
    let _ = std::fs::remove_dir_all(&f.dir);
}
