//! Live keys: layer files frozen into immutable, addressable files.
//!
//! [`Storage::freeze`] turns files of a layer into **live files**: each gets a
//! [`LiveKey`] derived from the file's identity and state (layer id, GUID,
//! length, mtime and content digest), and a store file of its own (`b'F'` +
//! key) whose
//! manifest names the same blocks as the layer file's
//! (`aether_block_store::BlockStore::clone_files`), so no byte is copied and
//! nothing that later happens to the layer — a rewrite in place, a
//! replacement, a removal, `delete_layer` — changes what the key reads. Only
//! [`Storage::delete_live`] removes a live file.
//!
//! **Freeze is idempotent.** Freezing a file whose state a live key already
//! covers returns that key and writes nothing, so a host that re-runs its
//! session end after a crash gets the same keys and leaks none. A key always
//! names the same bytes: a file rewritten since (a new GUID, or new bytes,
//! length or mtime) freezes to a new key.
//!
//! The catalog's `live` table holds each key's length and mtime. A freeze
//! commits the rows before the clone, under the shared durability gate, and
//! makes both durable (a durable point) before it returns the keys:
//! `DURABILITY.md` and the design,
//! `docs/superpowers/specs/2026-10-13-live-freeze-design.md`.

use std::collections::HashMap;
use std::fmt;
use std::str::FromStr;
use std::sync::Arc;

use vfs_provider::{COPY_UP_PREFIX, KIND_DIR, KIND_FILE, WHITEOUT_PREFIX};

use crate::catalog::EntryRec;
use crate::ids::{Guid, live_file_id};
use crate::layer::Select;
use crate::storage::{Storage, StorageError};

/// The handle to one frozen file's bytes. Stable (it names the same bytes
/// for as long as it exists, and if a freeze of the identical source state
/// recreates it after [`Storage::delete_live`], the same bytes again) and
/// serializable: its string form is `live-` followed by 32 lowercase hex
/// digits ([`fmt::Display`] and [`FromStr`]).
#[derive(Debug, Clone, Copy, PartialEq, Eq, Hash, PartialOrd, Ord)]
pub struct LiveKey([u8; 16]);

const KEY_PREFIX: &str = "live-";

impl LiveKey {
    /// The key with these 16 bytes (the inverse of [`LiveKey::as_bytes`]).
    pub fn from_bytes(b: [u8; 16]) -> Self {
        LiveKey(b)
    }

    /// The key's 16 bytes, for a host that stores them in binary form.
    pub fn as_bytes(&self) -> &[u8; 16] {
        &self.0
    }

    /// The key of one source state: layer `layer_id`'s file `guid`, with
    /// this length, mtime and store content digest
    /// (`aether_block_store::BlockStore::content_digests`). A keyed BLAKE3
    /// hash (its derive-key mode, under a context string of its own)
    /// truncated to 128 bits, so a freeze of the same unchanged file always
    /// gives the same key, and any change of layer, file, bytes, length or
    /// mtime gives another.
    pub(crate) fn derive(layer_id: u64, guid: &Guid, src: &SourceState) -> Self {
        let mut h = blake3::Hasher::new_derive_key(KEY_CONTEXT);
        h.update(&layer_id.to_le_bytes())
            .update(guid)
            .update(&src.len.to_le_bytes())
            .update(&src.mtime.to_le_bytes())
            .update(&src.digest);
        let mut out = [0u8; 16];
        out.copy_from_slice(&h.finalize().as_bytes()[..16]);
        LiveKey(out)
    }

    /// A random key, which no freeze derives (tests' unknown keys).
    #[cfg(test)]
    pub(crate) fn fresh() -> Self {
        LiveKey(crate::ids::new_guid())
    }
}

/// The derive-key context of [`LiveKey::derive`]. Changing it changes every
/// key a freeze derives, which breaks idempotence across versions: never.
const KEY_CONTEXT: &str = "aether-storage 2026-10-13 live key v1";

/// What a frozen key covers besides the file's identity.
pub(crate) struct SourceState {
    /// The store file's length (the clone's).
    pub len: u64,
    /// The catalog row's mtime.
    pub mtime: i64,
    /// The store file's content digest.
    pub digest: [u8; 32],
}

impl fmt::Display for LiveKey {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        f.write_str(KEY_PREFIX)?;
        for b in self.0 {
            write!(f, "{b:02x}")?;
        }
        Ok(())
    }
}

impl FromStr for LiveKey {
    type Err = StorageError;

    /// Parses `live-<32 hex digits>` (either case). Anything else is
    /// `BadRequest`.
    fn from_str(s: &str) -> Result<Self, StorageError> {
        let bad = || StorageError::BadRequest(format!("not a live key: {s:?}"));
        let hex = s.strip_prefix(KEY_PREFIX).ok_or_else(bad)?;
        if hex.len() != 32 || !hex.bytes().all(|b| b.is_ascii_hexdigit()) {
            return Err(bad());
        }
        let mut out = [0u8; 16];
        for (i, b) in out.iter_mut().enumerate() {
            *b = u8::from_str_radix(&hex[2 * i..2 * i + 2], 16).map_err(|_| bad())?;
        }
        Ok(LiveKey(out))
    }
}

/// A live file's length and modification time (the source row's mtime, in
/// seconds since the Unix epoch).
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct LiveInfo {
    pub len: u64,
    pub mtime: i64,
}

/// One file frozen by [`Storage::freeze`] or [`Storage::freeze_layer`].
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct FrozenFile {
    /// The path as passed to [`Storage::freeze`], or the original-case path
    /// for [`Storage::freeze_layer`].
    pub path: String,
    pub key: LiveKey,
    pub info: LiveInfo,
}

/// What a [`LayerEntry`] is.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum EntryKind {
    /// A regular file: content.
    File,
    Dir,
    /// A whiteout marker (`.wh.<name>`): the overlay above this layer hides
    /// `<name>` of the layers below. See [`LayerEntry::hidden_path`].
    Whiteout,
    /// A copy-up staging file (`.cu.<n>.<name>`) an overlay left behind
    /// (a crash mid copy-up). Never content.
    CopyUp,
}

/// One row of a layer, as [`Storage::layer_entries`] lists it.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct LayerEntry {
    /// `/`-separated, relative to the layer root, in its original case.
    pub path: String,
    pub kind: EntryKind,
    /// A file's length as last committed to the catalog; 0 for a directory.
    pub len: u64,
    pub mtime: i64,
}

impl LayerEntry {
    /// For a whiteout, the path it hides (its marker prefix removed); `None`
    /// for any other entry.
    pub fn hidden_path(&self) -> Option<String> {
        if self.kind != EntryKind::Whiteout {
            return None;
        }
        Some(match self.path.rsplit_once('/') {
            Some((dir, name)) => format!("{dir}/{}", &name[WHITEOUT_PREFIX.len()..]),
            None => self.path[WHITEOUT_PREFIX.len()..].to_owned(),
        })
    }
}

/// `rows` (one layer's, in key order, as `Catalog::layer_rows` returns them)
/// as entries with their original-case paths, root excluded, each with its
/// GUID (zero for a directory).
pub(crate) fn entries_of(rows: Vec<(String, EntryRec)>) -> Vec<(LayerEntry, Guid)> {
    // Folded directory path → original-case path. A directory's row sorts
    // before every row under it, so a parent is always known first (a row
    // whose parent has no row keeps the parent's folded spelling).
    let mut dirs: HashMap<String, String> = HashMap::new();
    let mut out = Vec::with_capacity(rows.len());
    for (folded, rec) in rows {
        if folded.is_empty() {
            continue;
        }
        let path = match folded.rsplit_once('/') {
            Some((parent, _)) => {
                let p = dirs.get(parent).map_or(parent, String::as_str);
                format!("{p}/{}", rec.name)
            }
            None => rec.name.clone(),
        };
        let kind = if rec.kind == KIND_DIR {
            dirs.insert(folded, path.clone());
            EntryKind::Dir
        } else if rec.kind != KIND_FILE {
            continue;
        } else if rec.name.starts_with(WHITEOUT_PREFIX) {
            EntryKind::Whiteout
        } else if rec.name.starts_with(COPY_UP_PREFIX) {
            EntryKind::CopyUp
        } else {
            EntryKind::File
        };
        out.push((
            LayerEntry {
                path,
                kind,
                len: rec.len,
                mtime: rec.mtime,
            },
            rec.guid,
        ));
    }
    out
}

impl Storage {
    /// Every file, directory and overlay marker of layer `layer`, root
    /// excluded, from one catalog snapshot, in key order (a directory before
    /// what it holds). `NoSuchLayer` if there is none.
    pub fn layer_entries(&self, layer: &str) -> Result<Vec<LayerEntry>, StorageError> {
        let id = self
            .catalog
            .layer_id(layer)?
            .ok_or_else(|| StorageError::NoSuchLayer(layer.to_owned()))?;
        Ok(entries_of(self.catalog.layer_rows(id)?)
            .into_iter()
            .map(|(e, _)| e)
            .collect())
    }

    /// Freezes the files at `paths` of layer `layer` into live files, one
    /// [`FrozenFile`] per path, in order. All of them, or (on any error) none:
    /// a missing path is `NotFound`, a directory `BadRequest`, a missing
    /// layer `NoSuchLayer`. One catalog commit, one store commit and one
    /// durable point for the whole batch, and no data copied.
    ///
    /// Each file is frozen as readers of the layer see it: blocks a handle
    /// still holds unwritten are committed first. The keys are **durable**
    /// when this returns; a crash before it returns loses the whole freeze.
    /// The layer itself is not changed. `LayerInUse` if writes keep racing
    /// the freeze (see the design doc).
    ///
    /// **Idempotent**: the key is derived from the file's layer, GUID,
    /// length, mtime and content, so freezing an unchanged file again (in a
    /// later call, or twice in one batch, under any spelling of its path)
    /// returns the same key and creates nothing; only files without a live
    /// file yet cost a row and a clone. A file rewritten since (in place or
    /// by replacement) or merely touched gets a new key. After
    /// [`Storage::delete_live`], freezing the unchanged file recreates the
    /// same key over the same bytes.
    pub fn freeze(
        self: &Arc<Self>,
        layer: &str,
        paths: &[&str],
    ) -> Result<Vec<FrozenFile>, StorageError> {
        if paths.is_empty() {
            return Ok(Vec::new());
        }
        self.layer_provider(layer, false)?
            .freeze(Select::Paths(paths))
    }

    /// [`Storage::freeze`] of every [`EntryKind::File`] of layer `layer`
    /// (not directories, whiteouts or copy-up leftovers), listed and frozen
    /// under one namespace lock. Each [`FrozenFile::path`] is the file's
    /// original-case path. The session-end call.
    pub fn freeze_layer(self: &Arc<Self>, layer: &str) -> Result<Vec<FrozenFile>, StorageError> {
        self.layer_provider(layer, false)?.freeze(Select::All)
    }

    /// Each key's [`LiveInfo`], or `None` for a key that does not exist (never
    /// did, or was deleted), from one catalog snapshot.
    pub fn live_info(&self, keys: &[LiveKey]) -> Result<Vec<Option<LiveInfo>>, StorageError> {
        let raw: Vec<[u8; 16]> = keys.iter().map(|k| k.0).collect();
        Ok(self
            .catalog
            .live_get_many(&raw)?
            .into_iter()
            .map(|r| r.map(|(len, mtime)| LiveInfo { len, mtime }))
            .collect())
    }

    /// Every live key, in key order: what a host sweeps against its own
    /// records to delete keys it never recorded (a crash between a freeze
    /// and the host's commit leaves such keys).
    pub fn live_keys(&self) -> Result<Vec<(LiveKey, LiveInfo)>, StorageError> {
        Ok(self
            .catalog
            .live_all()?
            .into_iter()
            .map(|(k, (len, mtime))| (LiveKey(k), LiveInfo { len, mtime }))
            .collect())
    }

    /// Opens live file `key` for positional reads. `NotFound` if it does not
    /// exist. The [`LiveFile`] holds this storage, as a layer provider does.
    pub fn open_live(self: &Arc<Self>, key: LiveKey) -> Result<LiveFile, StorageError> {
        let Some((len, mtime)) = self.catalog.live_get_many(&[key.0])?.remove(0) else {
            return Err(StorageError::NotFound(key.to_string()));
        };
        Ok(LiveFile {
            storage: Arc::clone(self),
            key,
            id: live_file_id(&key.0),
            info: LiveInfo { len, mtime },
        })
    }

    /// Deletes the live files of `keys` and returns how many existed (unknown
    /// keys are ignored). Their rows go first and durably (a durable point),
    /// then their store files: a crash in between leaves orphans that
    /// reconciliation at the next open deletes. A [`LiveFile`] still open on
    /// a deleted key fails its later reads (until a freeze of the identical
    /// source state recreates the key, when they read the same bytes again).
    ///
    /// Runs under the storage's `live` lock, which a freeze also takes, so a
    /// freeze never sees a key's row gone with its store file still there
    /// and mistakes it for an orphan.
    pub fn delete_live(&self, keys: &[LiveKey]) -> Result<u64, StorageError> {
        let _live = crate::util::lock(&self.live_lock);
        let raw: Vec<[u8; 16]> = keys.iter().map(|k| k.0).collect();
        let gone = self.catalog.live_remove_many(&raw)?;
        if gone.is_empty() {
            return Ok(0);
        }
        if let Err(e) = self.durable_point() {
            self.needs_reconcile("delete_live: durable point failed after the rows were dropped");
            return Err(e);
        }
        for k in &gone {
            let id = live_file_id(k);
            self.ram.invalidate_file(&id);
            match self.store_delete(&id) {
                Ok(()) | Err(aether_block_store::Error::NotFound) => {}
                Err(e) => {
                    tracing::warn!(error = %e, "live file delete failed; reconciliation will retry");
                    self.needs_reconcile("a deleted live file's store delete failed");
                }
            }
        }
        Ok(gone.len() as u64)
    }
}

/// An open live file: positional reads of bytes that never change.
/// `Send + Sync`; any number of threads may read at once.
pub struct LiveFile {
    storage: Arc<Storage>,
    key: LiveKey,
    id: [u8; 17],
    info: LiveInfo,
}

impl fmt::Debug for LiveFile {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        f.debug_struct("LiveFile")
            .field("key", &self.key)
            .field("info", &self.info)
            .finish()
    }
}

impl LiveFile {
    pub fn key(&self) -> LiveKey {
        self.key
    }

    pub fn info(&self) -> LiveInfo {
        self.info
    }

    /// Reads up to `buf.len()` bytes at `offset` and returns how many: all of
    /// them, unless the file ends first (0 at or past the end).
    ///
    /// Blocks come from the RAM tier, else the store (and then enter the RAM
    /// tier: the bytes never change, so an entry is never stale). A block the
    /// store does not hold is corruption, `Store(Corrupt)`, never zeros.
    pub fn read_at(&self, offset: u64, buf: &mut [u8]) -> Result<usize, StorageError> {
        let len = self.info.len;
        if offset >= len || buf.is_empty() {
            return Ok(0);
        }
        let bs = self.storage.block_size();
        let end = len.min(offset.saturating_add(buf.len() as u64));
        let mut pos = offset;
        while pos < end {
            let b = pos / bs;
            let skip = (pos - b * bs) as usize;
            let to = end.min((b + 1) * bs);
            let block = self.block(b)?;
            let n = (to - pos) as usize;
            buf[(pos - offset) as usize..(to - offset) as usize]
                .copy_from_slice(&block[skip..skip + n]);
            pos = to;
        }
        Ok((end - offset) as usize)
    }

    /// Block `b`, whole.
    fn block(&self, b: u64) -> Result<Arc<[u8]>, StorageError> {
        let s = &self.storage;
        if let Some(hit) = s.ram.get(&self.id, b) {
            return Ok(hit);
        }
        let bs = s.block_size();
        let want = (self.info.len - b * bs).min(bs) as usize;
        let mut data = crate::ram::zeroed_block(want);
        let buf = Arc::get_mut(&mut data).expect("a new block has one owner");
        let r = s.store.read(&self.id, b * bs, buf)?;
        if !r.missing.is_empty() || r.bytes != want {
            tracing::error!(
                key = %self.key, block = b, got = r.bytes, want,
                "live file block missing: corruption"
            );
            return Err(StorageError::Store(aether_block_store::Error::Corrupt(
                format!("live file {} is missing block {b}", self.key),
            )));
        }
        // A delete racing this read may leave the block in the tier after
        // its invalidate: harmless, as a key only ever names one content
        // (a freeze that recreates it recreates the same bytes).
        Ok(s.ram.fill(&self.id, b, data))
    }
}

#[cfg(test)]
mod tests;
