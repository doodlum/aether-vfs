//! Freezing a layer's files into live files ([`crate::live`]).
//!
//! A freeze runs under the durability gate (shared) and the layer's `ns`
//! lock, so no namespace change, no new open and no durable point lands
//! between resolving the rows and cloning their store files. A selected file
//! that is open is pinned with a **non-blocking** `try_read` of its cell's
//! state: the documented order takes `state` before `gate` and `ns`, but a
//! `try_read` never waits, so it adds no wait-for edge. A file whose state
//! is busy or holds unwritten blocks sends the freeze back out to commit the
//! layer's open files (as a `flush` would) and try again.
//!
//! Keys are derived, not drawn ([`LiveKey::derive`]): a source whose key
//! already has a row is not frozen again (idempotence). The `live` lock,
//! taken before the gate, keeps `delete_live` out of the row-and-clone step.

use std::collections::HashSet;
use std::sync::TryLockError;

use super::*;
use crate::ids::live_file_id;
use crate::live::{EntryKind, FrozenFile, LiveInfo, LiveKey, SourceState, entries_of};
use crate::util::lock;

/// Rounds of "commit the open files, then try again" before a freeze gives
/// up with `LayerInUse`.
const MAX_ROUNDS: usize = 8;

/// What a freeze takes.
pub(crate) enum Select<'a> {
    /// These paths, each a file.
    Paths(&'a [&'a str]),
    /// Every regular file (no directories or overlay markers).
    All,
}

/// A provider status, for an API that reports `StorageError`.
fn status_err(what: &str, st: i32) -> StorageError {
    StorageError::Io(std::io::Error::other(format!(
        "freeze: {what}: provider status {st}"
    )))
}

impl LayerProvider {
    /// Freezes the files `sel` names (see [`crate::Storage::freeze`]) and
    /// runs a durable point before returning their keys.
    pub(crate) fn freeze(&self, sel: Select<'_>) -> Result<Vec<FrozenFile>, StorageError> {
        for _ in 0..MAX_ROUNDS {
            if let Some(out) = self.try_freeze(&sel)? {
                self.storage.durable_point()?;
                return Ok(out);
            }
            self.commit_open_files()?;
        }
        Err(StorageError::LayerInUse(format!(
            "{} (its files kept being written during a freeze)",
            self.name
        )))
    }

    /// Commits every open file of this layer that has unwritten blocks or
    /// an uncommitted length. Holds nothing on entry.
    fn commit_open_files(&self) -> Result<(), StorageError> {
        let open: Vec<Arc<FileCell>> = lock_status(&self.cells)
            .map_err(|st| status_err("cells", st))?
            .values()
            .filter_map(Weak::upgrade)
            .collect();
        for cell in open {
            let mut st = write_state(&cell.state).map_err(|e| status_err("file state", e))?;
            if st.is_dirty() {
                self.commit(&cell, &mut st)
                    .map_err(|e| status_err("commit", e))?;
            }
        }
        Ok(())
    }

    /// One attempt, without the durable point: `None` if a selected open
    /// file is busy or dirty. (`pub(crate)` for tests of a freeze cut short.)
    pub(crate) fn try_freeze(
        &self,
        sel: &Select<'_>,
    ) -> Result<Option<Vec<FrozenFile>>, StorageError> {
        let _live = lock(&self.storage.live_lock);
        let _gate = self.storage.gate_shared();
        let _ns = lock_status(&self.ns).map_err(|st| status_err("namespace", st))?;
        let catalog = &self.storage.catalog;
        let picked: Vec<(String, EntryRec)> = match sel {
            Select::Paths(paths) => {
                let mut out = Vec::with_capacity(paths.len());
                for p in *paths {
                    let folded = folded_path(p)
                        .map_err(|_| StorageError::BadRequest(format!("bad path {p:?}")))?;
                    let rec = catalog
                        .get(self.id, &folded)?
                        .ok_or_else(|| StorageError::NotFound(folded.clone()))?;
                    if rec.kind != KIND_FILE {
                        return Err(StorageError::BadRequest(format!(
                            "freeze of {p:?}, which is not a file"
                        )));
                    }
                    out.push(((*p).to_owned(), rec));
                }
                out
            }
            Select::All => entries_of(catalog.layer_rows(self.id)?)
                .into_iter()
                .filter(|(e, _)| e.kind == EntryKind::File)
                .map(|(e, guid)| {
                    let rec = EntryRec {
                        name: String::new(),
                        kind: KIND_FILE,
                        guid,
                        len: e.len,
                        mtime: e.mtime,
                    };
                    (e.path, rec)
                })
                .collect(),
        };
        if picked.is_empty() {
            return Ok(Some(Vec::new()));
        }

        // Pin the open ones: no write or commit may run on them until the
        // clone is in. `cells` outlives `pins`, which borrow from it.
        let mut seen = HashSet::new();
        let cells: Vec<Arc<FileCell>> = picked
            .iter()
            .filter(|(_, r)| seen.insert(r.guid))
            .filter_map(|(_, r)| self.live_cell(&r.guid))
            .collect();
        let mut pins = Vec::with_capacity(cells.len());
        for c in &cells {
            let st = match c.state.try_read() {
                Ok(st) => st,
                Err(TryLockError::WouldBlock) => return Ok(None),
                Err(TryLockError::Poisoned(_)) => {
                    return Err(status_err("file state", map_io_err()));
                }
            };
            if st.is_dirty() {
                return Ok(None);
            }
            pins.push(st);
        }

        // Each key from its source's identity and state. The store's length
        // is what a clone holds (a row that lagged it after a crash between
        // a store flush and a durable catalog commit, see `acquire`, does not
        // count). The sources cannot change while `ns` and the pins are held.
        let store = &self.storage.store;
        let srcs: Vec<[u8; 17]> = picked.iter().map(|(_, r)| layer_file_id(&r.guid)).collect();
        let src_refs: Vec<&[u8]> = srcs.iter().map(|s| s.as_slice()).collect();
        let mut keys = Vec::with_capacity(picked.len());
        let mut infos = Vec::with_capacity(picked.len());
        for ((_, r), d) in picked.iter().zip(store.content_digests(&src_refs)?) {
            let (len, digest) = d.ok_or(aether_block_store::Error::NotFound)?;
            let state = SourceState {
                len,
                mtime: r.mtime,
                digest,
            };
            keys.push(LiveKey::derive(self.id, &r.guid, &state));
            infos.push(LiveInfo {
                len,
                mtime: r.mtime,
            });
        }

        // Keys with a row already name exactly these bytes: nothing to do
        // (idempotence). The rest, each once, get a row and then a clone
        // (invariant 3), under the gate.
        let raw: Vec<[u8; 16]> = keys.iter().map(|k| *k.as_bytes()).collect();
        let mut fresh: Vec<usize> = Vec::new();
        let mut seen_keys = HashSet::new();
        for (i, row) in catalog.live_get_many(&raw)?.into_iter().enumerate() {
            match row {
                Some((len, mtime)) => debug_assert_eq!(
                    LiveInfo { len, mtime },
                    infos[i],
                    "a live key derived for other bytes"
                ),
                None if seen_keys.insert(keys[i]) => fresh.push(i),
                None => {}
            }
        }
        if !fresh.is_empty() {
            self.freeze_new(&fresh, &srcs, &keys, &infos)?;
        }
        drop(pins);
        Ok(Some(
            picked
                .into_iter()
                .zip(keys)
                .zip(infos)
                .map(|(((path, _), key), info)| FrozenFile { path, key, info })
                .collect(),
        ))
    }

    /// Rows, then clones, for the sources at `fresh` (indexes into `srcs`,
    /// `keys` and `infos`), whose keys have no row. Called by `try_freeze`
    /// under the `live` lock, the gate and `ns`.
    ///
    /// A store file that already exists for such a key is an orphan: a
    /// row-less `b'F'` id can only be a clone whose row is gone (a
    /// `delete_live` whose store delete failed; one in progress holds the
    /// `live` lock) or never became durable (deleted at the next open). It is
    /// deleted first, so the clone recreates it from the source.
    fn freeze_new(
        &self,
        fresh: &[usize],
        srcs: &[[u8; 17]],
        keys: &[LiveKey],
        infos: &[LiveInfo],
    ) -> Result<(), StorageError> {
        let s = &self.storage;
        let dsts: Vec<[u8; 17]> = fresh
            .iter()
            .map(|&i| live_file_id(keys[i].as_bytes()))
            .collect();
        for d in &dsts {
            if s.store.stat(d)?.is_some() {
                tracing::warn!("freeze: deleting an orphan live file before recreating it");
                s.ram.invalidate_file(d);
                match s.store_delete(d) {
                    Ok(()) | Err(aether_block_store::Error::NotFound) => {}
                    Err(e) => return Err(e.into()),
                }
            }
        }
        let rows: Vec<crate::catalog::LiveRow> = fresh
            .iter()
            .map(|&i| (*keys[i].as_bytes(), (infos[i].len, infos[i].mtime)))
            .collect();
        s.catalog.live_put_many(&rows)?;
        let pairs: Vec<(&[u8], &[u8])> = fresh
            .iter()
            .zip(&dsts)
            .map(|(&i, d)| (srcs[i].as_slice(), d.as_slice()))
            .collect();
        match s.store.clone_files(&pairs) {
            Ok(lens) => {
                debug_assert!(
                    fresh.iter().zip(&lens).all(|(&i, l)| infos[i].len == *l),
                    "a pinned source changed length"
                );
                Ok(())
            }
            Err(e) => {
                let raw: Vec<[u8; 16]> = rows.iter().map(|r| r.0).collect();
                if let Err(r) = s.catalog.live_remove_many(&raw) {
                    tracing::error!(error = %r, "freeze: removing the rows of a failed clone failed");
                    s.needs_reconcile("a failed freeze left live rows without store files");
                }
                Err(e.into())
            }
        }
    }
}
