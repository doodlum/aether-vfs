//! Tests of live keys: freeze, read, list, delete, and what survives what.

use std::collections::HashSet;
use std::sync::Arc;

use vfs_provider::{OPEN_CREATE, OPEN_READ, OPEN_TRUNC, OPEN_WRITE, Provider, SetAttr, VPath};

use super::*;
use crate::config::{Durability, StorageConfig};
use crate::ids::{StoreIdKind, classify_store_id};

const BS: u64 = 4096;

fn cfg() -> StorageConfig {
    let mut c = StorageConfig::default();
    c.store.block_size = BS as u32;
    c
}

fn open(dir: &std::path::Path) -> Arc<Storage> {
    Storage::open(dir, cfg()).unwrap()
}

fn at(p: &str) -> VPath<'_> {
    VPath::at_default(p)
}

/// Deterministic bytes that do not dedup against each other across seeds.
fn bytes(seed: u8, len: usize) -> Vec<u8> {
    let mut out = vec![0u8; len];
    blake3::Hasher::new()
        .update(&[seed])
        .finalize_xof()
        .fill(&mut out);
    out
}

fn write_file(p: &Arc<dyn Provider>, rel: &str, body: &[u8]) {
    let (h, _, _) = p
        .open(at(rel), OPEN_WRITE | OPEN_CREATE | OPEN_TRUNC)
        .unwrap();
    assert_eq!(p.write_at(h, 0, body).unwrap(), body.len());
    p.close(h).unwrap();
}

fn read_layer(p: &Arc<dyn Provider>, rel: &str) -> Vec<u8> {
    let (h, size, _) = p.open(at(rel), OPEN_READ).unwrap();
    let mut out = vec![0u8; size as usize];
    let mut done = 0;
    while done < out.len() {
        let n = p.read_at(h, done as u64, &mut out[done..]).unwrap();
        assert!(n > 0);
        done += n;
    }
    p.close(h).unwrap();
    out
}

fn read_live(s: &Arc<Storage>, key: LiveKey) -> Vec<u8> {
    let f = s.open_live(key).unwrap();
    let mut out = vec![0u8; f.info().len as usize];
    assert_eq!(f.read_at(0, &mut out).unwrap(), out.len());
    out
}

fn live_store_ids(s: &Storage) -> usize {
    s.store
        .file_ids()
        .unwrap()
        .iter()
        .filter(|id| matches!(classify_store_id(id), StoreIdKind::Live(_)))
        .count()
}

#[test]
fn a_frozen_file_survives_rewrites_of_its_source() {
    let dir = vfs_testkit::tempdir().unwrap();
    let s = open(dir.path());
    let p = s.layer("scratch").unwrap();
    let a = bytes(1, 3 * BS as usize + 100);
    write_file(&p, "Saves/one.ess", &a);
    let frozen = s.freeze("scratch", &["saves/ONE.ess"]).unwrap();
    assert_eq!(frozen.len(), 1);
    assert_eq!(frozen[0].path, "saves/ONE.ess");
    assert_eq!(frozen[0].info.len, a.len() as u64);
    let key = frozen[0].key;

    // Rewritten in place (same GUID): a write in the middle, then a shrink.
    let (h, _, _) = p.open(at("Saves/one.ess"), OPEN_WRITE).unwrap();
    p.write_at(h, BS, &bytes(2, BS as usize)).unwrap();
    p.close(h).unwrap();
    p.set_attr(
        at("Saves/one.ess"),
        SetAttr {
            size: Some(10),
            ..Default::default()
        },
    )
    .unwrap();
    assert_eq!(read_layer(&p, "Saves/one.ess").len(), 10);
    assert_eq!(read_live(&s, key), a);

    // Replaced by a rename over it, then removed.
    write_file(&p, "Saves/new.ess", b"replacement");
    p.rename(at("Saves/new.ess"), at("Saves/one.ess")).unwrap();
    p.remove(at("Saves/one.ess")).unwrap();
    s.sync().unwrap();
    assert_eq!(read_live(&s, key), a);
    assert_eq!(
        s.live_info(&[key]).unwrap(),
        vec![Some(frozen[0].info)],
        "the row is unchanged too"
    );
}

#[test]
fn a_frozen_file_survives_deleting_its_layer() {
    let dir = vfs_testkit::tempdir().unwrap();
    let s = open(dir.path());
    let a = bytes(3, 2 * BS as usize + 1);
    s.put_files("scratch", &[("a.bin", &a), ("b.bin", b"small")])
        .unwrap();
    let frozen = s.freeze_layer("scratch").unwrap();
    assert_eq!(frozen.len(), 2);
    s.delete_layer("scratch").unwrap();
    s.store.compact(Default::default()).unwrap();
    let by_path: HashMap<_, _> = frozen.iter().map(|f| (f.path.as_str(), f.key)).collect();
    assert_eq!(read_live(&s, by_path["a.bin"]), a);
    assert_eq!(read_live(&s, by_path["b.bin"]), b"small");
    // A new layer of the same name does not touch them.
    s.put_files("scratch", &[("a.bin", b"other")]).unwrap();
    assert_eq!(read_live(&s, by_path["a.bin"]), a);
    assert!(s.store.verify().unwrap().is_ok());
}

#[test]
fn a_batch_freeze_makes_one_durable_point() {
    let dir = vfs_testkit::tempdir().unwrap();
    let s = open(dir.path());
    let names: Vec<String> = (0..200).map(|i| format!("d{}/f{i}.txt", i % 7)).collect();
    let bodies: Vec<Vec<u8>> = (0..200).map(|i| format!("body {i}").into_bytes()).collect();
    let files: Vec<(&str, &[u8])> = names
        .iter()
        .zip(&bodies)
        .map(|(n, b)| (n.as_str(), b.as_slice()))
        .collect();
    s.put_files("scratch", &files).unwrap();
    let before = s.clock.points();
    let paths: Vec<&str> = names.iter().map(String::as_str).collect();
    let frozen = s.freeze("scratch", &paths).unwrap();
    assert_eq!(s.clock.points() - before, 1, "one fsynced durable point");
    assert_eq!(frozen.len(), 200);
    let keys: HashSet<LiveKey> = frozen.iter().map(|f| f.key).collect();
    assert_eq!(keys.len(), 200, "a key per file");
    for (f, body) in frozen.iter().zip(&bodies) {
        assert_eq!(read_live(&s, f.key), *body, "{}", f.path);
    }
    let infos = s
        .live_info(&frozen.iter().map(|f| f.key).collect::<Vec<_>>())
        .unwrap();
    assert!(
        infos
            .iter()
            .zip(&bodies)
            .all(|(i, b)| i.unwrap().len == b.len() as u64)
    );
    assert_eq!(s.live_keys().unwrap().len(), 200);
    assert_eq!(s.stats().live_files, 200);
}

#[test]
fn freeze_layer_takes_files_and_leaves_markers_and_directories() {
    let dir = vfs_testkit::tempdir().unwrap();
    let s = open(dir.path());
    s.put_files(
        "scratch",
        &[
            ("Data/Plugin.esp", b"esp"),
            ("Data/.wh.Old.esp", b""),
            ("Data/.cu.3.x.ini", b"partial"),
            ("top.ini", b"ini"),
        ],
    )
    .unwrap();
    let p = s.layer("scratch").unwrap();
    p.mkdir(at("Empty")).unwrap();
    let mut got: Vec<(String, Vec<u8>)> = s
        .freeze_layer("scratch")
        .unwrap()
        .into_iter()
        .map(|f| (f.path, read_live(&s, f.key)))
        .collect();
    got.sort();
    assert_eq!(
        got,
        vec![
            ("Data/Plugin.esp".to_string(), b"esp".to_vec()),
            ("top.ini".to_string(), b"ini".to_vec()),
        ]
    );
}

#[test]
fn layer_entries_list_files_directories_and_whiteouts_in_original_case() {
    let dir = vfs_testkit::tempdir().unwrap();
    let s = open(dir.path());
    s.put_files(
        "scratch",
        &[
            ("Data/Meshes/Rock.NIF", b"nif"),
            ("Data/.wh.Gone.esp", b""),
            (".wh.root.txt", b""),
            ("Data/.cu.1.a.ini", b"x"),
            ("Saves-old/x", b"1"),
        ],
    )
    .unwrap();
    let entries = s.layer_entries("scratch").unwrap();
    let view: Vec<(&str, EntryKind)> = entries.iter().map(|e| (e.path.as_str(), e.kind)).collect();
    assert_eq!(
        view,
        vec![
            (".wh.root.txt", EntryKind::Whiteout),
            ("Data", EntryKind::Dir),
            ("Data/.cu.1.a.ini", EntryKind::CopyUp),
            ("Data/.wh.Gone.esp", EntryKind::Whiteout),
            ("Data/Meshes", EntryKind::Dir),
            ("Data/Meshes/Rock.NIF", EntryKind::File),
            ("Saves-old", EntryKind::Dir),
            ("Saves-old/x", EntryKind::File),
        ]
    );
    let hidden: Vec<String> = entries.iter().filter_map(LayerEntry::hidden_path).collect();
    assert_eq!(hidden, vec!["root.txt", "Data/Gone.esp"]);
    assert_eq!(entries[5].len, 3);
    assert!(matches!(
        s.layer_entries("nope"),
        Err(StorageError::NoSuchLayer(_))
    ));
    assert!(matches!(
        s.freeze_layer("nope"),
        Err(StorageError::NoSuchLayer(_))
    ));
}

#[test]
fn a_failed_freeze_freezes_nothing() {
    let dir = vfs_testkit::tempdir().unwrap();
    let s = open(dir.path());
    s.put_files("scratch", &[("d/a.txt", b"a")]).unwrap();
    for bad in [
        &["d/a.txt", "missing.txt"][..],
        &["d/a.txt", "d"][..],
        &["../x"][..],
    ] {
        let e = s.freeze("scratch", bad).unwrap_err();
        assert!(
            matches!(e, StorageError::NotFound(_) | StorageError::BadRequest(_)),
            "{bad:?}: {e}"
        );
    }
    assert!(s.live_keys().unwrap().is_empty());
    assert_eq!(live_store_ids(&s), 0);
    assert!(s.freeze("scratch", &[]).unwrap().is_empty());
    // The same path twice is two keys.
    let f = s.freeze("scratch", &["d/a.txt", "D/A.TXT"]).unwrap();
    assert_ne!(f[0].key, f[1].key);
}

/// A file a handle is still writing is frozen as readers see it: its
/// unwritten blocks are committed first. Later writes do not reach the key.
#[test]
fn freezing_an_open_file_freezes_what_readers_see() {
    let dir = vfs_testkit::tempdir().unwrap();
    let s = open(dir.path());
    let p = s.layer("scratch").unwrap();
    let (h, _, _) = p.open(at("save.ess"), OPEN_WRITE | OPEN_CREATE).unwrap();
    let a = bytes(4, BS as usize + 7);
    p.write_at(h, 0, &a).unwrap();
    // A second, clean, open file in the same batch is pinned, not committed.
    write_file(&p, "other.ess", b"other");
    let (h2, _, _) = p.open(at("other.ess"), OPEN_READ).unwrap();
    let f = s.freeze("scratch", &["save.ess", "other.ess"]).unwrap();
    assert_eq!(read_live(&s, f[0].key), a);
    assert_eq!(read_live(&s, f[1].key), b"other");
    p.write_at(h, 3, b"later").unwrap();
    p.close(h).unwrap();
    p.close(h2).unwrap();
    assert_eq!(read_live(&s, f[0].key), a);
    assert_ne!(read_layer(&p, "save.ess"), a);
}

/// A writer that never stops: each freeze returns some state the file was
/// in, whole, or gives up with `LayerInUse`.
#[test]
fn a_freeze_racing_a_writer_freezes_a_whole_state() {
    let dir = vfs_testkit::tempdir().unwrap();
    let s = open(dir.path());
    let p = s.layer("scratch").unwrap();
    let len = 3 * BS as usize;
    write_file(&p, "f", &vec![0u8; len]);
    let stop = Arc::new(std::sync::atomic::AtomicBool::new(false));
    let writer = {
        let (p, stop) = (Arc::clone(&p), Arc::clone(&stop));
        std::thread::spawn(move || {
            let mut n = 1u8;
            while !stop.load(std::sync::atomic::Ordering::Relaxed) {
                let (h, _, _) = p.open(at("f"), OPEN_WRITE).unwrap();
                p.write_at(h, 0, &vec![n; len]).unwrap();
                p.close(h).unwrap();
                n = n.wrapping_add(1).max(1);
            }
        })
    };
    let mut frozen = 0;
    for _ in 0..20 {
        match s.freeze("scratch", &["f"]) {
            Ok(f) => {
                let got = read_live(&s, f[0].key);
                assert_eq!(got.len(), len);
                assert!(got.iter().all(|&b| b == got[0]), "a torn freeze");
                frozen += 1;
            }
            Err(StorageError::LayerInUse(_)) => {}
            Err(e) => panic!("{e}"),
        }
    }
    stop.store(true, std::sync::atomic::Ordering::Relaxed);
    writer.join().unwrap();
    assert!(frozen > 0, "no freeze got through");
}

#[test]
fn live_files_survive_a_reopen() {
    let dir = vfs_testkit::tempdir().unwrap();
    let a = bytes(5, 5 * BS as usize);
    let key = {
        let s = open(dir.path());
        s.put_files("scratch", &[("a.bin", &a)]).unwrap();
        let key = s.freeze("scratch", &["a.bin"]).unwrap()[0].key;
        s.close().unwrap();
        key
    };
    let s = open(dir.path());
    assert!(s.last_reconcile().skipped_after_clean_close);
    assert_eq!(read_live(&s, key), a);
    // A reopen that reconciles (no clean close) keeps them as well.
    s.close_unclean();
    let s = open(dir.path());
    let r = s.last_reconcile();
    assert!(!r.skipped_after_clean_close);
    assert_eq!((r.orphans_deleted, r.lost_live.len()), (0, 0), "{r:?}");
    assert_eq!(read_live(&s, key), a);
    assert_eq!(s.space_usage().unwrap().live.logical_bytes, a.len() as u64);
}

/// Killed right after `freeze` returned (under the default deferred policy,
/// with nothing else making a durable point): the key is there.
#[cfg(not(windows))]
#[test]
fn a_kill_after_freeze_returns_keeps_the_key() {
    let dir = vfs_testkit::tempdir().unwrap();
    let s = open(dir.path());
    assert_eq!(s.durability(), Durability::default());
    let p = s.layer("scratch").unwrap();
    write_file(&p, "save.ess", b"frozen bytes");
    let key = s.freeze("scratch", &["save.ess"]).unwrap()[0].key;
    let killed = vfs_testkit::tempdir().unwrap();
    crate::test_util::snapshot_as_killed(dir.path(), killed.path()).unwrap();
    let k = open(killed.path());
    assert!(!k.last_reconcile().skipped_after_clean_close);
    assert_eq!(read_live(&k, key), b"frozen bytes");
}

/// Killed between a freeze's clone and its durable point, after the store's
/// own auto-flush made the clone durable: no row, and the orphan is deleted.
#[cfg(not(windows))]
#[test]
fn a_kill_before_the_durable_point_leaves_nothing() {
    let dir = vfs_testkit::tempdir().unwrap();
    let s = open(dir.path());
    s.put_files("scratch", &[("a.bin", b"data")]).unwrap();
    s.sync().unwrap();
    let lid = s.catalog.layer_id("scratch").unwrap().unwrap();
    let guid = s.catalog.get(lid, "a.bin").unwrap().unwrap().guid;
    let key = LiveKey::fresh();
    s.catalog.live_put_many(&[(key.0, (4, 0))]).unwrap();
    let src = crate::ids::layer_file_id(&guid);
    let dst = live_file_id(&key.0);
    s.store.clone_files(&[(&src, &dst)]).unwrap();
    s.store.flush().unwrap();
    let killed = vfs_testkit::tempdir().unwrap();
    crate::test_util::snapshot_as_killed(dir.path(), killed.path()).unwrap();
    let k = open(killed.path());
    assert_eq!(k.last_reconcile().orphans_deleted, 1);
    assert_eq!(k.live_info(&[key]).unwrap(), vec![None]);
    assert_eq!(live_store_ids(&k), 0);
    assert!(matches!(k.open_live(key), Err(StorageError::NotFound(_))));
}

/// A live row whose store file is gone is dropped at open and reported.
#[test]
fn a_live_row_without_store_data_is_dropped_and_reported() {
    let dir = vfs_testkit::tempdir().unwrap();
    let key = {
        let s = open(dir.path());
        s.put_files("scratch", &[("a.bin", b"data")]).unwrap();
        let key = s.freeze("scratch", &["a.bin"]).unwrap()[0].key;
        s.store.delete(&live_file_id(&key.0)).unwrap();
        s.close_unclean();
        key
    };
    let s = open(dir.path());
    assert_eq!(s.last_reconcile().lost_live, vec![key.to_string()]);
    assert_eq!(s.live_info(&[key]).unwrap(), vec![None]);
}

#[test]
fn delete_live_removes_rows_and_data() {
    let dir = vfs_testkit::tempdir().unwrap();
    let s = open(dir.path());
    s.put_files("scratch", &[("a", b"a"), ("b", b"b")]).unwrap();
    let f = s.freeze_layer("scratch").unwrap();
    let (a, b) = (f[0].key, f[1].key);
    let reader = s.open_live(a).unwrap();
    let unknown = LiveKey::fresh();
    assert_eq!(s.delete_live(&[a, unknown]).unwrap(), 1);
    assert_eq!(s.delete_live(&[a]).unwrap(), 0);
    assert_eq!(s.live_info(&[a, b]).unwrap()[0], None);
    assert!(matches!(s.open_live(a), Err(StorageError::NotFound(_))));
    assert!(
        reader.read_at(0, &mut [0u8; 1]).is_err(),
        "deleted under it"
    );
    assert_eq!(live_store_ids(&s), 1);
    assert_eq!(read_live(&s, b), b"b");
    // The layer is untouched.
    assert_eq!(read_layer(&s.layer("scratch").unwrap(), "a"), b"a");
    drop(reader);
    s.close().unwrap();
    let s = open(dir.path());
    assert!(s.last_reconcile().skipped_after_clean_close);
    assert_eq!(s.live_keys().unwrap().len(), 1);
}

#[test]
fn reads_are_positional_and_clamped() {
    let dir = vfs_testkit::tempdir().unwrap();
    let s = open(dir.path());
    let a = bytes(6, 3 * BS as usize - 5);
    s.put_files("scratch", &[("a", &a)]).unwrap();
    let key = s.freeze("scratch", &["a"]).unwrap()[0].key;
    let f = s.open_live(key).unwrap();
    assert_eq!(f.key(), key);
    for (off, n) in [(0, 1), (BS - 3, 10), (BS + 1, 2 * BS as usize), (5, 0)] {
        let mut buf = vec![0u8; n];
        let got = f.read_at(off, &mut buf).unwrap();
        let end = (off as usize + n).min(a.len());
        assert_eq!(got, end - off as usize);
        assert_eq!(&buf[..got], &a[off as usize..end]);
    }
    assert_eq!(f.read_at(a.len() as u64, &mut [0u8; 8]).unwrap(), 0);
    assert_eq!(f.read_at(u64::MAX, &mut [0u8; 8]).unwrap(), 0);
    // Readers share the file across threads.
    let f = Arc::new(f);
    let hs: Vec<_> = (0..4)
        .map(|i| {
            let f = Arc::clone(&f);
            let a = a.clone();
            std::thread::spawn(move || {
                let mut buf = vec![0u8; 1000];
                let off = i * 2000;
                f.read_at(off, &mut buf).unwrap();
                assert_eq!(buf, a[off as usize..off as usize + 1000]);
            })
        })
        .collect();
    hs.into_iter().for_each(|h| h.join().unwrap());
    // An empty file freezes and reads as empty.
    s.put_files("scratch", &[("e", b"")]).unwrap();
    let e = s.freeze("scratch", &["e"]).unwrap()[0].key;
    assert_eq!(read_live(&s, e), b"");
}

#[test]
fn keys_round_trip_through_strings() {
    let k = LiveKey::from_bytes([0xab; 16]);
    let s = k.to_string();
    assert_eq!(s, format!("live-{}", "ab".repeat(16)));
    assert_eq!(s.parse::<LiveKey>().unwrap(), k);
    assert_eq!(
        s.to_uppercase()
            .replace("LIVE-", "live-")
            .parse::<LiveKey>()
            .unwrap(),
        k
    );
    assert_eq!(*k.as_bytes(), [0xab; 16]);
    for bad in [
        "",
        "live-",
        "abababababababababababababababab",
        "live-abababababababababababababab",
        "live-ababababababababababababababababab",
        "live-zzababababababababababababababab",
        "dead-abababababababababababababababab",
        "live-+bababababababababababababababab",
    ] {
        assert!(
            matches!(bad.parse::<LiveKey>(), Err(StorageError::BadRequest(_))),
            "{bad:?}"
        );
    }
    let fresh = LiveKey::fresh();
    assert_eq!(fresh.to_string().parse::<LiveKey>().unwrap(), fresh);
}
