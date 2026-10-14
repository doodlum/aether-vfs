//! A writable synthesized file under a root that also has a write layer.
//!
//! The shape a mod manager composes for each root:
//!
//! - read-only layered content (game files, mods);
//! - one writable scratch **write layer** (`set_write_layer_at`) that takes
//!   every ordinary write, with copy-up for edits of read-only files;
//! - a few writable synthesized files such as `Data/plugins.txt`, each held by
//!   a `MemoryProvider` with bytes the host rendered. The game reads and
//!   writes the file, and when the session ends the host reads the result
//!   back through `Session::read_file_at`.
//!
//! The first test pins what happens when the memory provider is merely
//! *mounted*: it sits below the write layer like every other mount, so the
//! overlay copies the file up and every write lands in the write layer. The
//! host's buffer never sees it. The rest pin the fix, a **write route**
//! (`Session::route_at`), for each way a game replaces such a file.
//!
//! `docs/superpowers/specs/2026-10-13-write-routes-design.md`.

use std::sync::Arc;

use vfs_embed::{
    InlineProvider, MemoryProvider, Provider, RootId, Session, VPath, OPEN_CREATE, OPEN_READ,
    OPEN_TRUNC, OPEN_WRITE,
};

const ROOT: RootId = RootId(1);
const PLUGINS: &str = "Data/plugins.txt";
const RENDERED: &[u8] = b"*Skyrim.esm\n*Update.esm\n";

/// The read-only content: a game file, a mod file and the `Data` directory
/// both live in.
fn base() -> Arc<dyn Provider> {
    Arc::new(InlineProvider::from_files([
        ("Data/Skyrim.esm", b"ESM".as_slice()),
        ("Data/Mod.esp", b"ESP".as_slice()),
        ("SkyrimSE.exe", b"MZ".as_slice()),
    ]))
}

/// The host's rendered `plugins.txt`, keyed by its full root-relative path.
fn plugins_buffer() -> Arc<MemoryProvider> {
    Arc::new(MemoryProvider::from_files([(PLUGINS, RENDERED)]))
}

/// The write layer. In-memory, so the test can look inside it directly.
fn write_layer() -> Arc<MemoryProvider> {
    Arc::new(MemoryProvider::from_files(
        std::iter::empty::<(&str, &[u8])>(),
    ))
}

/// What the provider itself holds at `path`, bypassing the session: the
/// host's buffer, or the write layer.
fn held(p: &MemoryProvider, path: &str) -> Option<Vec<u8>> {
    let (h, size, _) = p.open(VPath::new(ROOT, path), OPEN_READ).ok()?;
    let mut buf = vec![0u8; size as usize];
    let n = p.read_at(h, 0, &mut buf).unwrap();
    p.close(h).unwrap();
    buf.truncate(n);
    Some(buf)
}

/// Open `path` through the session's kernel, write `bytes` at offset 0 and
/// close: the same calls the director makes for the shim.
fn write(s: &Session, path: &str, flags: u32, bytes: &[u8]) {
    let (fh, _, _) = s
        .kernel()
        .open(ROOT, path, flags)
        .unwrap_or_else(|e| panic!("open {path} for write: status {e}"));
    s.kernel().write(fh, 0, bytes).unwrap();
    s.kernel().close(fh).unwrap();
}

/// The write-temp-then-rename every careful writer does.
fn write_temp_then_rename(s: &Session, bytes: &[u8]) {
    let tmp = "Data/plugins.txt.tmp";
    write(s, tmp, OPEN_WRITE | OPEN_CREATE | OPEN_TRUNC, bytes);
    s.kernel().rename(ROOT, tmp, PLUGINS).expect("rename onto");
}

fn names(s: &Session, dir: &str) -> Vec<String> {
    let mut v: Vec<String> = s
        .readdir(ROOT, dir)
        .unwrap()
        .into_iter()
        .map(|e| e.name)
        .collect();
    v.sort();
    v
}

/// The behaviour before write routes, and still the behaviour of a memory
/// provider that is only mounted: the write layer is composed **over** every
/// mount, so all three ways of writing `plugins.txt` land in the write layer
/// and the host's buffer keeps the bytes it rendered.
#[test]
fn a_mounted_memory_file_under_a_write_layer_is_copied_up_not_written() {
    for (case, op) in [
        ("in-place write", 0),
        ("truncate + write", 1),
        ("temp + rename", 2),
    ] {
        let s = Session::new();
        let mem = plugins_buffer();
        let upper = write_layer();
        s.mount_at(ROOT, "", base()).unwrap();
        s.mount_at(ROOT, "", mem.clone()).unwrap();
        s.set_write_layer_at(ROOT, upper.clone()).unwrap();

        match op {
            0 => write(&s, PLUGINS, OPEN_WRITE, b"*Skyrim.esm\n*Mod.esp\n"),
            1 => write(&s, PLUGINS, OPEN_WRITE | OPEN_TRUNC, b"*Mod.esp\n"),
            _ => write_temp_then_rename(&s, b"*Mod.esp\n"),
        }

        assert_eq!(
            held(&mem, PLUGINS).as_deref(),
            Some(RENDERED),
            "{case}: the host's buffer is untouched"
        );
        assert!(
            held(&upper, PLUGINS).is_some(),
            "{case}: the write went to the write layer instead"
        );
        assert_ne!(
            s.read_file_at(ROOT, PLUGINS).unwrap(),
            RENDERED,
            "{case}: and the session serves the write layer's copy"
        );
    }
}

/// The composition with a route: the same base and write layer, and the
/// buffer owning `Data/plugins.txt` above the write layer.
fn routed() -> (Session, Arc<MemoryProvider>, Arc<MemoryProvider>) {
    let s = Session::new();
    let mem = plugins_buffer();
    let upper = write_layer();
    s.mount_at(ROOT, "", base()).unwrap();
    s.set_write_layer_at(ROOT, upper.clone()).unwrap();
    s.route_at(ROOT, PLUGINS, mem.clone()).unwrap();
    (s, mem, upper)
}

#[test]
fn a_routed_file_is_read_and_listed_like_any_other() {
    let (s, _mem, _upper) = routed();
    assert_eq!(s.read_file_at(ROOT, PLUGINS).unwrap(), RENDERED);
    // Folded, as the shim sends it.
    assert_eq!(s.read_file_at(ROOT, "data/PLUGINS.TXT").unwrap(), RENDERED);
    assert_eq!(
        names(&s, "Data"),
        ["Mod.esp", "Skyrim.esm", "plugins.txt"],
        "the routed file is listed beside the base's files, once"
    );
    assert_eq!(names(&s, ""), ["Data", "SkyrimSE.exe"]);
    let st = s.getattr(ROOT, PLUGINS).unwrap().unwrap();
    assert_eq!(st.size, RENDERED.len() as u64);
}

#[test]
fn an_in_place_write_reaches_the_buffer() {
    let (s, mem, upper) = routed();
    write(
        &s,
        PLUGINS,
        OPEN_WRITE,
        b"*Skyrim.esm\n*Update.esm\n*Mod.esp\n",
    );
    assert_eq!(
        held(&mem, PLUGINS).as_deref(),
        Some(b"*Skyrim.esm\n*Update.esm\n*Mod.esp\n".as_slice())
    );
    assert_eq!(held(&upper, PLUGINS), None, "nothing was copied up");
    assert_eq!(
        s.read_file_at(ROOT, PLUGINS).unwrap(),
        b"*Skyrim.esm\n*Update.esm\n*Mod.esp\n"
    );
}

#[test]
fn a_truncating_write_reaches_the_buffer() {
    let (s, mem, upper) = routed();
    write(&s, PLUGINS, OPEN_WRITE | OPEN_TRUNC, b"*Mod.esp\n");
    assert_eq!(
        held(&mem, PLUGINS).as_deref(),
        Some(b"*Mod.esp\n".as_slice())
    );
    assert_eq!(held(&upper, PLUGINS), None);

    // And a set_len on an open handle (SetEndOfFile).
    let (fh, _, _) = s.kernel().open(ROOT, PLUGINS, OPEN_WRITE).unwrap();
    s.kernel().set_len(fh, 4).unwrap();
    s.kernel().close(fh).unwrap();
    assert_eq!(held(&mem, PLUGINS).as_deref(), Some(b"*Mod".as_slice()));
}

#[test]
fn write_temp_then_rename_lands_in_the_buffer_and_leaves_no_temp() {
    let (s, mem, upper) = routed();
    write_temp_then_rename(&s, b"*Mod.esp\n");
    assert_eq!(
        held(&mem, PLUGINS).as_deref(),
        Some(b"*Mod.esp\n".as_slice())
    );
    assert_eq!(held(&upper, PLUGINS), None);
    assert_eq!(
        held(&upper, "Data/plugins.txt.tmp"),
        None,
        "the temp file was moved, not copied"
    );
    assert_eq!(s.read_file_at(ROOT, PLUGINS).unwrap(), b"*Mod.esp\n");
    assert_eq!(names(&s, "Data"), ["Mod.esp", "Skyrim.esm", "plugins.txt"]);
}

/// `ReplaceFileW` with a backup: the live file is renamed to the backup name
/// (out of the route, into the write layer), then the replacement is renamed
/// onto the live name (into the route).
#[test]
fn replace_with_backup_moves_each_file_to_whoever_owns_its_new_name() {
    let (s, mem, upper) = routed();
    write(
        &s,
        "Data/plugins.new",
        OPEN_WRITE | OPEN_CREATE | OPEN_TRUNC,
        b"NEW",
    );
    s.kernel()
        .rename(ROOT, PLUGINS, "Data/plugins.bak")
        .expect("live -> backup");
    assert_eq!(held(&mem, PLUGINS), None, "the live name moved out");
    assert_eq!(held(&upper, "Data/plugins.bak").as_deref(), Some(RENDERED));
    assert_eq!(s.getattr(ROOT, PLUGINS).unwrap(), None);

    s.kernel()
        .rename(ROOT, "Data/plugins.new", PLUGINS)
        .expect("replacement -> live");
    assert_eq!(held(&mem, PLUGINS).as_deref(), Some(b"NEW".as_slice()));
    assert_eq!(held(&upper, "Data/plugins.new"), None);
    assert_eq!(
        names(&s, "Data"),
        ["Mod.esp", "Skyrim.esm", "plugins.bak", "plugins.txt"]
    );
}

#[test]
fn delete_then_create_happens_in_the_buffer() {
    let (s, mem, upper) = routed();
    s.kernel().remove(ROOT, PLUGINS).unwrap();
    assert_eq!(held(&mem, PLUGINS), None);
    assert_eq!(s.getattr(ROOT, PLUGINS).unwrap(), None);
    assert_eq!(names(&s, "Data"), ["Mod.esp", "Skyrim.esm"]);
    assert_eq!(
        upper.readdir(VPath::new(ROOT, "Data")).unwrap_or_default(),
        vec![],
        "no whiteout: the write layer never held the file"
    );

    write(&s, PLUGINS, OPEN_WRITE | OPEN_CREATE, b"FRESH");
    assert_eq!(held(&mem, PLUGINS).as_deref(), Some(b"FRESH".as_slice()));
    assert_eq!(held(&upper, PLUGINS), None);
}

/// Ordinary writes still go where they went: a new file and an edit of base
/// content both land in the write layer.
#[test]
fn other_writes_still_go_to_the_write_layer() {
    let (s, mem, upper) = routed();
    write(&s, "Data/Mod.esp", OPEN_WRITE, b"ED");
    write(&s, "Data/new.ini", OPEN_WRITE | OPEN_CREATE, b"INI");
    assert_eq!(
        held(&upper, "Data/Mod.esp").as_deref(),
        Some(b"EDP".as_slice())
    );
    assert_eq!(
        held(&upper, "Data/new.ini").as_deref(),
        Some(b"INI".as_slice())
    );
    assert_eq!(held(&mem, "Data/new.ini"), None);
}

/// A stale copy left in a persistent write layer by an earlier session (or a
/// mod that ships the file) is shadowed by the route, in reads and listings
/// alike.
#[test]
fn the_route_shadows_a_stale_copy_below_it() {
    let s = Session::new();
    let mem = plugins_buffer();
    let upper = write_layer();
    write_into(&upper, PLUGINS, b"STALE FROM LAST SESSION");
    s.mount_at(ROOT, "", base()).unwrap();
    s.set_write_layer_at(ROOT, upper.clone()).unwrap();
    s.route_at(ROOT, PLUGINS, mem.clone()).unwrap();

    assert_eq!(s.read_file_at(ROOT, PLUGINS).unwrap(), RENDERED);
    let listed = s.readdir(ROOT, "Data").unwrap();
    let entry = listed.iter().find(|e| e.name == "plugins.txt").unwrap();
    assert_eq!(
        entry.stat.size,
        RENDERED.len() as u64,
        "the listing shows the routed file, not the stale one"
    );
}

/// A root with no write layer: the route is still writable, and everything
/// else is still refused.
#[test]
fn a_route_is_writable_on_a_root_without_a_write_layer() {
    let s = Session::new();
    let mem = plugins_buffer();
    s.mount_at(ROOT, "", base()).unwrap();
    s.route_at(ROOT, PLUGINS, mem.clone()).unwrap();
    write(&s, PLUGINS, OPEN_WRITE | OPEN_TRUNC, b"X");
    assert_eq!(held(&mem, PLUGINS).as_deref(), Some(b"X".as_slice()));
    assert_eq!(
        s.kernel().open(ROOT, "Data/Mod.esp", OPEN_WRITE).err(),
        Some(vfs_embed::ST_READ_ONLY),
        "base content stays read-only"
    );
    assert!(!s.has_write_layer(ROOT));
}

/// The route survives the host rebuilding its mounts.
#[test]
fn set_root_mounts_keeps_the_routes() {
    let (s, mem, _upper) = routed();
    s.set_root_mounts(ROOT, vec![(String::new(), base())])
        .unwrap();
    write(&s, PLUGINS, OPEN_WRITE | OPEN_TRUNC, b"KEPT");
    assert_eq!(held(&mem, PLUGINS).as_deref(), Some(b"KEPT".as_slice()));
}

fn write_into(p: &MemoryProvider, path: &str, bytes: &[u8]) {
    let (h, _, _) = p
        .open(
            VPath::new(ROOT, path),
            OPEN_WRITE | OPEN_CREATE | OPEN_TRUNC,
        )
        .unwrap();
    p.write_at(h, 0, bytes).unwrap();
    p.close(h).unwrap();
}

/// The same write-temp-then-rename with a disk write layer, which is what a
/// persistent layer looks like to the overlay: the moved file is in the
/// buffer, and nothing (temp, staging file or whiteout) is left on disk.
#[test]
fn write_temp_then_rename_over_a_disk_write_layer_leaves_the_disk_clean() {
    let dir = std::path::Path::new(env!("CARGO_TARGET_TMPDIR"))
        .join(format!("write-routes-disk-{}", std::process::id()));
    let _ = std::fs::remove_dir_all(&dir);
    std::fs::create_dir_all(&dir).unwrap();

    let s = Session::new();
    let mem = plugins_buffer();
    s.mount_at(ROOT, "", base()).unwrap();
    s.set_write_layer_at(ROOT, Arc::new(vfs_embed::DiskProvider::new(&dir)))
        .unwrap();
    s.route_at(ROOT, PLUGINS, mem.clone()).unwrap();

    write_temp_then_rename(&s, b"*Mod.esp\n");
    assert_eq!(
        held(&mem, PLUGINS).as_deref(),
        Some(b"*Mod.esp\n".as_slice())
    );
    let left: Vec<_> = walk(&dir);
    assert!(
        left.iter()
            .all(|p| !p.ends_with("plugins.txt.tmp") && !p.contains(".cu.") && !p.contains(".wh.")),
        "the write layer kept {left:?}"
    );
    let _ = std::fs::remove_dir_all(&dir);
}

fn walk(dir: &std::path::Path) -> Vec<String> {
    let mut out = Vec::new();
    for e in std::fs::read_dir(dir).unwrap().flatten() {
        let p = e.path();
        if p.is_dir() {
            out.extend(walk(&p));
        } else {
            out.push(p.display().to_string());
        }
    }
    out
}

#[test]
fn a_bad_route_is_refused_and_changes_nothing() {
    let s = Session::new();
    s.mount_at(ROOT, "", base()).unwrap();
    for pattern in ["", "/", "../outside/*", "Data/../../x"] {
        assert_eq!(
            s.route_at(ROOT, pattern, plugins_buffer()).err(),
            Some(vfs_embed::ST_BAD_REQUEST),
            "{pattern:?}"
        );
    }
    // Nothing recorded: the root still composes and serves as before.
    assert_eq!(s.read_file_at(ROOT, "Data/Mod.esp").unwrap(), b"ESP");
    assert_eq!(s.getattr(ROOT, PLUGINS).unwrap(), None);
}

/// Later routes win over earlier ones, as later mounts do.
#[test]
fn a_later_route_wins() {
    let s = Session::new();
    let first = plugins_buffer();
    let second = Arc::new(MemoryProvider::from_files([(
        PLUGINS,
        b"SECOND".as_slice(),
    )]));
    s.mount_at(ROOT, "", base()).unwrap();
    s.route_at(ROOT, "Data/*.txt", first).unwrap();
    s.route_at(ROOT, PLUGINS, second).unwrap();
    assert_eq!(s.read_file_at(ROOT, PLUGINS).unwrap(), b"SECOND");
}
