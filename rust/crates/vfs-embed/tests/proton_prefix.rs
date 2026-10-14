//! A session's prefix under a **real** GE-Proton runtime, without launching
//! anything: Proton's own prefix setup with a placeholder Steam client
//! directory, and the prefix user's known folders. Needs the runtime only —
//! no Windows artefacts — so it is the cheapest of the Proton tests.
//!
//! `#[ignore]`d like the other Proton tests: run with
//! `cargo test -p vfs-embed --test proton_prefix -- --ignored`. A missing
//! runtime is a `SKIP` (a failure under `VFS_TEST_REQUIRE_ALL=1`); see
//! `support/proton.rs`.
#![cfg(unix)]

mod support;

use std::path::{Path, PathBuf};

use vfs_embed::{KnownFolder, PrefixInit, Session};

/// A session in its own throwaway aether home whose prefix is set up with
/// `init`, or `None` after printing why the test is skipped.
fn session(test: &str, tag: &str, init: PrefixInit) -> Option<Session> {
    let home = match support::throwaway_home(&format!("prefix-{tag}")) {
        Ok(h) => h,
        Err(e) => {
            support::skip(test, e);
            return None;
        }
    };
    let mut s = Session::new();
    s.set_home(&home);
    s.set_state_dir(support::scratch(
        "vfs-proton-prefix",
        &format!("{tag}-state"),
    ));
    s.set_prefix_init(init);
    // No `set_prefix_name`: an anonymous prefix, deleted when `s` drops.
    Some(s)
}

/// `C:\users\…` as the host directory in `pfx` it names.
fn in_prefix(pfx: &Path, location: &Path) -> PathBuf {
    let loc = location.to_string_lossy().replace('\\', "/");
    let rest = loc
        .strip_prefix("C:/")
        .unwrap_or_else(|| panic!("{loc} is not on C:"));
    pfx.join("drive_c").join(rest)
}

/// Every known folder is under the prefix user's profile and exists in the
/// prefix, and the user is the one the prefix's own `drive_c\users` holds.
fn check_known_folders(s: &Session, pfx: &Path, user: &str) {
    for (folder, tail) in [
        (KnownFolder::Documents, "Documents"),
        (KnownFolder::LocalAppData, r"AppData\Local"),
        (KnownFolder::SavedGames, "Saved Games"),
    ] {
        let got = s.known_folder(folder).unwrap();
        assert_eq!(
            got,
            Path::new(&format!(r"C:\users\{user}\{tail}")),
            "{folder:?}"
        );
        assert!(
            in_prefix(pfx, &got).is_dir(),
            "{folder:?}: {} is not a directory in the prefix",
            got.display()
        );
        Session::check_root_location(&got.join("Game").to_string_lossy()).unwrap();
    }
}

/// AE-24: `PrefixInit::Proton` needs only that `steam_client` exists. An
/// empty placeholder — a GOG install, CI — gives a whole Proton prefix
/// (`lsteamclient.dll` included), and nothing is written into the
/// placeholder.
///
/// AE-21: GE-Proton's Wine names its user `steamuser`, and the session reads
/// that from the prefix.
#[test]
#[ignore = "needs a GE-Proton runtime: cargo run -p vfs-proton -- install"]
fn a_proton_prefix_set_up_with_a_placeholder_steam_client_is_steamusers() {
    const TEST: &str = "a_proton_prefix_set_up_with_a_placeholder_steam_client_is_steamusers";
    if support::not_on_this_wine_host(TEST) {
        return;
    }
    let placeholder = support::scratch("vfs-proton-prefix", "steam-placeholder");
    let Some(s) = session(
        TEST,
        "placeholder",
        PrefixInit::Proton {
            steam_client: placeholder.clone(),
            app_id: None,
        },
    ) else {
        return;
    };
    let pfx = s.prepare_prefix().unwrap();
    assert!(pfx.join("drive_c/windows/system32").is_dir());
    assert!(
        pfx.join("drive_c/windows/system32/lsteamclient.dll")
            .is_file(),
        "Proton's Steam bridge is in the prefix"
    );
    let tag = std::fs::read_to_string(pfx.parent().unwrap().join("version")).unwrap();
    assert!(tag.trim().starts_with("GE-Proton"), "{tag}");
    assert_eq!(
        std::fs::read_dir(&placeholder).unwrap().count(),
        0,
        "Proton wrote nothing into the placeholder"
    );
    check_known_folders(&s, &pfx, "steamuser");
}

/// AE-21 under `wineboot`: still GE-Proton's Wine, so still `steamuser`, not
/// the Unix user — the reason the user is read from the prefix rather than
/// assumed per prefix kind.
#[test]
#[ignore = "needs a GE-Proton runtime: cargo run -p vfs-proton -- install"]
fn a_wineboot_prefixs_user_is_read_from_the_prefix() {
    const TEST: &str = "a_wineboot_prefixs_user_is_read_from_the_prefix";
    let Some(s) = session(TEST, "wineboot", PrefixInit::Wineboot) else {
        return;
    };
    let pfx = s.prepare_prefix().unwrap();
    let users: Vec<String> = std::fs::read_dir(pfx.join("drive_c/users"))
        .unwrap()
        .map(|e| e.unwrap().file_name().to_string_lossy().into_owned())
        .filter(|n| n != "Public")
        .collect();
    assert_eq!(users.len(), 1, "{users:?}");
    if cfg!(target_os = "linux") {
        assert_eq!(users[0], "steamuser", "GE-Proton's Wine names its user");
    }
    check_known_folders(&s, &pfx, &users[0]);
}

/// Names the directory a re-executed test binary acts as a host in.
const HOST_CHILD: &str = "AETHER_TEST_PREFIX_HOST_CHILD";

/// The host processes of this test's Wine `cmd /c ping -n 613 …` running
/// now (Linux: read from `/proc`).
fn pings() -> Vec<u32> {
    vfs_unix::all_pids()
        .into_iter()
        .filter(|&p| {
            std::fs::read(format!("/proc/{p}/cmdline"))
                .map(|c| String::from_utf8_lossy(&c).replace('\0', " "))
                .is_ok_and(|c| c.contains("cmd.exe") && c.contains("-n 613"))
        })
        .collect()
}

/// Not a test of its own: the host `a_killed_host_stops_real_wine_in_its_prefix`
/// starts and kills. With [`HOST_CHILD`] set, it sets a prefix up, arms the
/// host watch the way a launch does, starts a long `ping` under real Wine,
/// writes `<dir>/ready` and waits to be killed.
#[test]
#[ignore = "run by a_killed_host_stops_real_wine_in_its_prefix"]
fn host_process_for_the_real_wine_host_death_test() {
    let Some(out) = std::env::var_os(HOST_CHILD).map(PathBuf::from) else {
        return;
    };
    let Some(s) = session("host child", "host-death", PrefixInit::Wineboot) else {
        panic!("no runtime");
    };
    let pfx = vfs_proton::prefix::Prefix {
        dir: s.prepare_prefix().unwrap(),
    };
    let runtime = support::runtime_dir().unwrap();
    let host = vfs_proton::WineHost::Proton(runtime);
    let lock = pfx.lock().unwrap();
    let watch = vfs_proton::launch::HostWatch::prefix(&host, &pfx, Some(&lock)).unwrap();
    let wine = host
        .wine_command(&pfx.dir)
        .args(["cmd", "/c", "ping", "-n", "613", "127.0.0.1"])
        .stdin(std::process::Stdio::null())
        .stdout(std::process::Stdio::null())
        .stderr(std::process::Stdio::null())
        .spawn()
        .unwrap();
    let deadline = std::time::Instant::now() + std::time::Duration::from_secs(120);
    while pings().is_empty() {
        assert!(std::time::Instant::now() < deadline, "ping never started");
        std::thread::sleep(std::time::Duration::from_millis(100));
    }
    std::fs::write(out.join("prefix"), pfx.dir.to_string_lossy().as_bytes()).unwrap();
    std::fs::write(out.join("ready"), b"").unwrap();
    let _keep = (s, lock, watch, wine);
    loop {
        std::thread::sleep(std::time::Duration::from_secs(1));
    }
}

/// AE-27 against real Wine: a host killed outright takes the Wine programs
/// it started with it, through the prefix's own `wineserver -k`.
#[test]
#[ignore = "needs a GE-Proton runtime: cargo run -p vfs-proton -- install"]
fn a_killed_host_stops_real_wine_in_its_prefix() {
    const TEST: &str = "a_killed_host_stops_real_wine_in_its_prefix";
    if support::not_on_this_wine_host(TEST) {
        return;
    }
    if let Err(e) = support::runtime_dir() {
        support::skip(TEST, e);
        return;
    }
    assert!(
        pings().is_empty(),
        "this test's ping is already running here"
    );
    let out = support::scratch("vfs-proton-prefix", "host-death-out");
    let mut child = std::process::Command::new(std::env::current_exe().unwrap())
        .args([
            "--exact",
            "host_process_for_the_real_wine_host_death_test",
            "--ignored",
            "--nocapture",
        ])
        .env(HOST_CHILD, &out)
        .stdout(std::process::Stdio::null())
        .spawn()
        .unwrap();
    let deadline = std::time::Instant::now() + std::time::Duration::from_secs(240);
    while !out.join("ready").exists() {
        if let Some(st) = child.try_wait().unwrap() {
            panic!("the host exited first: {st}");
        }
        assert!(
            std::time::Instant::now() < deadline,
            "the host never got ready"
        );
        std::thread::sleep(std::time::Duration::from_millis(100));
    }
    assert!(!pings().is_empty());
    child.kill().unwrap();
    child.wait().unwrap();
    let deadline = std::time::Instant::now() + std::time::Duration::from_secs(30);
    while !pings().is_empty() {
        assert!(
            std::time::Instant::now() < deadline,
            "the Wine program outlived its killed host: {:?}",
            pings()
        );
        std::thread::sleep(std::time::Duration::from_millis(100));
    }
    // The anonymous prefix's session is gone with the host; remove it here.
    let pfx = PathBuf::from(std::fs::read_to_string(out.join("prefix")).unwrap());
    let _ = std::fs::remove_dir_all(pfx);
}
