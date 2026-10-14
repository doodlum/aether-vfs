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

use std::collections::HashSet;
use std::sync::TryLockError;

use super::*;
use crate::ids::live_file_id;
use crate::live::{EntryKind, FrozenFile, LiveInfo, LiveKey, entries_of};

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

    /// One attempt: `None` if a selected open file is busy or dirty.
    fn try_freeze(&self, sel: &Select<'_>) -> Result<Option<Vec<FrozenFile>>, StorageError> {
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
        let mut committed = HashMap::new();
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
            committed.insert(c.guid, st.committed_len);
            pins.push(st);
        }

        // Rows first, then the store files (invariant 3), under the gate.
        let keys: Vec<LiveKey> = picked.iter().map(|_| LiveKey::fresh()).collect();
        let mut rows: Vec<crate::catalog::LiveRow> = picked
            .iter()
            .zip(&keys)
            .map(|((_, r), k)| {
                let len = committed.get(&r.guid).copied().unwrap_or(r.len);
                (*k.as_bytes(), (len, r.mtime))
            })
            .collect();
        catalog.live_put_many(&rows)?;
        let srcs: Vec<[u8; 17]> = picked.iter().map(|(_, r)| layer_file_id(&r.guid)).collect();
        let dsts: Vec<[u8; 17]> = keys.iter().map(|k| live_file_id(k.as_bytes())).collect();
        let pairs: Vec<(&[u8], &[u8])> = srcs
            .iter()
            .zip(&dsts)
            .map(|(s, d)| (s.as_slice(), d.as_slice()))
            .collect();
        let lens = match self.storage.store.clone_files(&pairs) {
            Ok(lens) => lens,
            Err(e) => {
                let raw: Vec<[u8; 16]> = keys.iter().map(|k| *k.as_bytes()).collect();
                if let Err(r) = catalog.live_remove_many(&raw) {
                    tracing::error!(error = %r, "freeze: removing the rows of a failed clone failed");
                    self.storage
                        .needs_reconcile("a failed freeze left live rows without store files");
                }
                return Err(e.into());
            }
        };
        // The store's length is what the clone holds. A row that lagged it
        // (a crash between a store flush and a durable catalog commit, see
        // `acquire`) takes it.
        if rows.iter().zip(&lens).any(|(r, l)| r.1.0 != *l) {
            for (r, l) in rows.iter_mut().zip(&lens) {
                r.1.0 = *l;
            }
            catalog.live_put_many(&rows)?;
        }
        drop(pins);
        Ok(Some(
            picked
                .into_iter()
                .zip(keys)
                .zip(rows)
                .map(|(((path, _), key), (_, (len, mtime)))| FrozenFile {
                    path,
                    key,
                    info: LiveInfo { len, mtime },
                })
                .collect(),
        ))
    }
}
