//! Mounting through `fusermount-relay` as `FUSERMOUNT_PATH`, what a Flatpak
//! does through `flatpak-spawn --host`: the relay runs `fusermount3` with a
//! socket of its own and passes the FUSE descriptor on. Its own test binary,
//! because it sets the environment. Needs `fusermount3`; skips without it.

#![cfg(target_os = "linux")]

use std::process::Command;
use std::sync::Arc;

use vfs_compose::{MapProvider, MountGraph, ReadOnlyProvider};
use vfs_director::Director;
use vfs_fuse::{mount_state, BaseDir, FuseMount, MountOpts, MountState, Unmounted};
use vfs_provider::{Provider, RootId};

const ROOT: RootId = RootId(0);

#[test]
fn mounts_and_unmounts_through_the_relay() {
    let helper = Command::new("sh")
        .args(["-c", "command -v fusermount3"])
        .output()
        .map(|o| o.status.success())
        .unwrap_or(false);
    if !helper {
        eprintln!("skipped: no fusermount3");
        return;
    }
    std::env::set_var("FUSERMOUNT_PATH", env!("CARGO_BIN_EXE_fusermount-relay"));

    let dir = vfs_testkit::scratch_path("vfs-fuse-relay");
    let _ = std::fs::remove_dir_all(&dir);
    let game = dir.join("Game");
    let staging = dir.join("staging");
    std::fs::create_dir_all(&game).unwrap();
    std::fs::create_dir_all(&staging).unwrap();
    std::fs::write(game.join("base.txt"), b"base").unwrap();
    std::fs::write(staging.join("mod.txt"), b"served").unwrap();

    let base = BaseDir::open(&game).unwrap();
    let base: Arc<dyn Provider> = Arc::new(ReadOnlyProvider::new(Arc::new(base.provider())));
    let map: Arc<dyn Provider> =
        Arc::new(MapProvider::new([("mod.txt", staging.join("mod.txt"))]).unwrap());
    let graph = MountGraph::new(vec![(String::new(), base), (String::new(), map)]).unwrap();
    let director = Arc::new(Director::new());
    director.mount(ROOT, Arc::new(graph)).unwrap();

    let mount = FuseMount::mount(director, ROOT, &game, MountOpts::default()).unwrap();
    assert_eq!(mount_state(&game).unwrap(), MountState::Live);
    assert_eq!(std::fs::read(game.join("mod.txt")).unwrap(), b"served");
    assert_eq!(std::fs::read(game.join("base.txt")).unwrap(), b"base");

    assert_eq!(mount.unmount().unwrap(), Unmounted::Clean);
    assert_eq!(mount_state(&game).unwrap(), MountState::None);
    let _ = std::fs::remove_dir_all(&dir);
}
