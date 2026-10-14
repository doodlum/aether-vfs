# `aether-storage`: freezing layer files into immutable live keys — design

Date: 2026-10-13
Status: implemented (`aether-storage` module `live`, `aether-block-store`
`BlockStore::clone_files`). Requested by Mauvi (its `D01-live-content.md`, item U1, and
DEC-014: "AE-2").

## 1. Goal

A host that gives each game session a fresh, empty **scratch layer** as its write overlay
wants, at session end, to turn every file the session wrote into an **immutable live key**:
a handle to exactly those bytes that never changes afterwards, whatever later happens to the
layer (overwritten, written in place, renamed, deleted, or reused by another session). The
host then serves live content through a provider of its own, so it must be able to open a
key and read it positionally.

Constraints:

- **No byte copy.** The block store already deduplicates 64 KiB blocks; freezing must cost
  index metadata, not data.
- **Batched.** One call freezes many files (Mauvi's rule: never one round trip per item).
  So does the existence/size query.
- **Crash safe and consistent with `DURABILITY.md`.** A key the host has been given is
  durable; a crash never leaves a key that names other bytes, or half a file.
- **Enumerable.** The host must list a layer's files and its `.wh.` whiteouts.

**Done means:** the API below exists; a frozen file reads back unchanged after its source
is rewritten in place, replaced, or its layer deleted, and after a reopen of the storage
(clean or killed); a batch freeze makes one store commit, one catalog commit and one durable
point; `cargo test`, clippy and `cargo fmt --check` pass for the add-ons workspace.

## 2. Where things stand

- A layer is `(layer id, folded path) → EntryRec { name, kind, guid, len, mtime }`; a file's
  data is the store file `b'L' + guid`. Writes to an existing file go to the **same** store
  file (block-level copy-on-write inside the store, but the file id is reused). So the GUID
  is **not** an immutable handle: a later session that reuses the layer, or the overlay's
  in-place writes, change what it names.
- The block store refcounts blocks per manifest slot. Two manifests that list the same block
  id hold two references; `write_blocks` over a slot and `delete` drop only that manifest's
  reference. So a second manifest that shares the blocks is already an independent,
  copy-on-write snapshot. The store has no call that makes one.
- `Storage` has no listing of a layer's rows other than per-directory `readdir` through a
  provider, which is one call per directory.

## 3. Shape

### 3.1 Block store: `clone_files`

```rust
impl BlockStore {
    /// Makes each `dst` a file with `src`'s length whose manifest names the same blocks
    /// (each one's refcount + 1). All pairs in one non-durable index commit.
    /// Returns each source's length.
    pub fn clone_files(&self, pairs: &[(&[u8], &[u8])]) -> Result<Vec<u64>>;
}
```

- A missing `src` is `Error::NotFound`; an existing `dst`, or a `dst` listed twice, is the new
  `Error::AlreadyExists`. Either fails the whole batch (nothing is committed).
- Copies manifest segments; cost is one segment read and write per 4096 blocks plus one
  `blocks` row update per block. No pack I/O.
- A slot whose block row is gone (a healed block) is copied as missing, never increfed.
- It is the block store's second API addition after `file_ids` (block-store spec §3 lists
  the public API; §14 is amended).

### 3.2 Storage: live keys

A **live key** is 16 random bytes (UUID v4). Its store file is `b'F' + key` (17 bytes;
`ids.rs` gains `StoreIdKind::Live`). Its string form is `live-` followed by 32 lowercase hex
digits; that string is what a host stores. Keys are never reused.

The catalog gains a table `live`: `key → (len, mtime)`. `mtime` is the source row's.

```rust
pub struct LiveKey(/* [u8; 16] */);          // Copy, Eq, Hash, Ord; Display / FromStr: "live-<hex>"
pub struct LiveInfo { pub len: u64, pub mtime: i64 }
pub struct FrozenFile { pub path: String, pub key: LiveKey, pub info: LiveInfo }
pub enum EntryKind { File, Dir, Whiteout, CopyUp }
pub struct LayerEntry { pub path: String, pub kind: EntryKind, pub len: u64, pub mtime: i64 }
pub struct LiveFile { /* key, info, Arc<Storage> */ }

impl Storage {
    pub fn layer_entries(&self, layer: &str) -> Result<Vec<LayerEntry>, StorageError>;
    pub fn freeze(self: &Arc<Self>, layer: &str, paths: &[&str]) -> Result<Vec<FrozenFile>, StorageError>;
    pub fn freeze_layer(self: &Arc<Self>, layer: &str) -> Result<Vec<FrozenFile>, StorageError>;
    pub fn live_info(&self, keys: &[LiveKey]) -> Result<Vec<Option<LiveInfo>>, StorageError>;
    pub fn open_live(self: &Arc<Self>, key: LiveKey) -> Result<LiveFile, StorageError>;
    pub fn live_keys(&self) -> Result<Vec<(LiveKey, LiveInfo)>, StorageError>;
    pub fn delete_live(&self, keys: &[LiveKey]) -> Result<u64, StorageError>;
}
impl LiveFile {
    pub fn key(&self) -> LiveKey;
    pub fn info(&self) -> LiveInfo;
    pub fn read_at(&self, offset: u64, buf: &mut [u8]) -> Result<usize, StorageError>;
}
```

- **`layer_entries`**: every row of the layer but its root, in one catalog read transaction
  (a consistent snapshot), in key order, each path rebuilt in its original case
  (`/`-separated). A file whose name starts with `.wh.` is `Whiteout`
  (`LayerEntry::hidden_path` gives the path it hides), `.cu.` is `CopyUp` (an overlay's
  copy-up staging file a crash left), else `File`. Lengths are as last committed to the
  catalog.
- **`freeze(layer, paths)`**: freezes each named file, in order, and returns one
  `FrozenFile` per path (`path` echoes the argument). A path that is missing or a directory
  fails the whole call with `NotFound` / `BadRequest`; nothing is frozen. Freezing a path
  twice gives two keys. `NoSuchLayer` for a missing layer. An empty `paths` does nothing.
- **`freeze_layer(layer)`**: freezes every `File` entry (not whiteouts, copy-up leftovers
  or directories) under one namespace lock, so the listing and the freeze agree; `path` is
  the original-case path. This is the session-end call.
- **`live_info`**: one catalog read transaction; `None` for an unknown (or deleted) key.
- **`open_live`**: `NotFound` for an unknown key. `LiveFile` is `Send + Sync`;
  `read_at` reads `min(buf.len(), len - offset)` bytes, 0 at or past the end, through the
  RAM tier (keyed by the live store id, so it is shared by every reader and never stale:
  the bytes never change). A missing block is corruption: `StorageError::Store(Corrupt)`,
  logged, never zeros (as for layer files, spec §5). A `LiveFile` holds the `Storage`, as
  a layer provider does.
- **`live_keys`**: every live key, for a host's mark-and-sweep (§5).
- **`delete_live`**: removes the keys' rows, runs a durable point, then deletes their store
  files (catalog first, store second, as `delete_layer`), and returns how many existed.
  Unknown keys are ignored. A `LiveFile` still open on a deleted key fails its next reads.

## 4. Freeze: what it does

Under the layer's provider (the live one if a session has the layer open, else a new one;
`delete_layer` is refused while it exists, so the layer cannot vanish mid-freeze):

1. Take the durability gate **shared**, then the layer's `ns` lock. Resolve the rows.
2. For each selected file that has an open cell (a handle is open on it), `try_read` its
   state lock and keep the guard. If a `try_read` fails (a write or commit holds it), or
   the cell has dirty blocks, release everything, commit dirty open cells (each under its
   own state lock, as a `flush` would), and go back to 1. After 8 rounds the call fails with
   `LayerInUse`.
3. Put the `live` rows (one non-durable catalog commit), then `clone_files` every
   `(b'L' + guid, b'F' + key)` pair (one non-durable store commit). If the clone fails the
   rows are removed again (a failed removal marks the session for reconciliation).
4. Release the locks and run a **durable point**. Only then are the keys returned.

So a frozen file is the file **as readers of the layer saw it** at the freeze: dirty blocks
were committed first, and no write or commit can run on a selected open file between the
dirty check and the clone (the state read guards exclude them). Files nobody has open
cannot be opened meanwhile (`ns` is held), so cannot be written.

**Lock order.** The documented order is a cell's `state`, then `gate`, then `ns`. Freeze
takes `state` after `ns`, but only with `try_read`, which never waits, so it adds no
wait-for edge. `DURABILITY.md` records this exception.

## 5. Lifetime and GC

- A live file is an ordinary store file with its own manifest. Its blocks are referenced
  by it, independently of the layer file it came from. Rewriting the source (in place or
  by replacement), renaming it, removing it, `delete_layer`, `compact`, and evicting the
  pull-through cache drop only **their** references; the live file's bytes stay. Blocks
  shared with the source are stored once.
- Nothing in `aether-storage` deletes a live file except `delete_live`. Live files are not
  counted against the cache budget, are never evicted, and `clear_cache` ignores them.
- **Leaks.** A host that crashes after `freeze` returned and before it recorded the keys
  leaves live files nobody references. `live_keys` lists every key so the host can sweep
  (delete what its database does not name). Mauvi v1 deletes nothing; the sweep is for
  later.
- `space_usage` reports live files as a new `live` bucket; `stats` gains `live_files`.

## 6. Durability and crash behaviour

Follows `DURABILITY.md`'s invariants, with live rows playing the part of layer rows:

1. **Row before store file** (invariant 3): the live rows are committed before the clone,
   both under the shared gate, so no durable point can publish a row ahead of its clone.
2. **Durable on return**: freeze runs a durable point before returning keys. A crash before
   it returns loses the freeze entirely (rows were never durable; a clone the store's own
   auto-flush made durable is an orphan `b'F'` id, which reconciliation deletes). A crash
   after it returns loses nothing.
3. **Delete** (invariant 2): rows first, durably, then the store delete. A crash between
   leaves an orphan, deleted at the next open.
4. **Reconciliation** at open, extended: a `b'F'` store id with no `live` row is an orphan
   and is deleted; a `live` row whose store file is missing (which no ordering above can
   produce) is dropped and reported in `ReconcileReport::lost_live` (dropping it is the
   repair, so nothing is left for the next open). The "catalog lost" refusal also counts
   `b'F'` ids as data the catalog must know about.
5. Freeze does not change the source layer, so the source's own durability is untouched;
   the durable point it runs publishes the whole storage's pending work, as any does.
6. Older builds classify `b'F'` ids as foreign and leave them alone (their reconciliation
   never deletes what it does not recognise), so opening a store with live files in an older
   build loses nothing.

## 7. Tests

Unit tests in `src/live/tests.rs`, in the style of `layer/tests`:

| test | proves |
|---|---|
| freeze, rewrite the source in place, and replace it by rename; the live bytes are unchanged | snapshot independence |
| freeze, `delete_layer`, `compact`; still readable | lifetime |
| freeze a batch of many files: one durable point (`DurableClock::points`) and one key each | batching |
| freeze, close, reopen (clean); freeze, crash after return (`snapshot_as_killed`), reopen | durability |
| kill between the clone and the durable point: the reopened store has no row and no orphan | crash safety |
| a live row whose store file is gone is dropped at open and reported | reconciliation |
| a freeze racing a writer that never stops freezes a whole state of the file, or gives up | pinning |
| freeze of a file with a handle open and dirty blocks freezes the dirty bytes | consistency |
| freeze of a missing path or a directory freezes nothing | batch atomicity |
| `layer_entries` lists files, directories, whiteouts with original case | enumeration |
| `live_info`, `live_keys`, `delete_live`, key string round trip, bad key strings | the rest |
| `read_at` past the end, unaligned ranges across blocks | reads |

`aether-block-store` gains unit tests for `clone_files`: refcounts (via `verify`), writing
the source or the clone leaves the other unchanged, deleting either keeps the other,
`NotFound` / `AlreadyExists` commit nothing, multi-segment files.

## 8. Out of scope

- A read-only `Provider` over `(path → live key)` entries (Mauvi's U2, which DEC-014
  replaced with a Mauvi-side provider).
- Content-addressed keys (freezing equal bytes twice gives two keys). The manifests are a
  few bytes per block and the blocks are shared, so the cost is small; see §9.
- Freezing directories or whiteouts: a whiteout carries no bytes; the host records it from
  `layer_entries`.

## 9. Open questions for the owner

- Should keys be **content-derived** (a hash of the block hashes and length) so that a
  re-run of session end after a crash returns the same keys instead of leaking the first
  set? It needs a per-block index read at freeze and a refcount per key (two freezes of the
  same bytes share one store file).
- Should `delete_live` defer the store delete while a `LiveFile` is open, as layer files do
  for open handles? Not needed for Mauvi v1, which never deletes.
