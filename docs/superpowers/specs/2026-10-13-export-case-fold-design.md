# aether-vfs — exporting path identity from `vfs-embed`

**Status:** implemented (2026-10-13).
**Consumer:** Mauvi (decision DEC-051: path equality for conflict detection,
session-end capture and addressing must match the VFS exactly; registered as
a DuckDB scalar). Upstream work item AE-10.

## 1. Why

A host that stores vpaths must answer "are these two paths the same file?"
exactly as the provider graph does. The answer lives in two engine functions —
`vfs_compose::path::normalize` (run by the `Director` on every host-side path)
and `vfs_core::fold` (used by every provider, merge and the shim) — and
`vfs-embed`, the only crate a host may name, exported neither. A host that
wrote its own would drift: this project has already shipped the
`to_ascii_lowercase`-versus-Unicode split once (`casefold.rs`).

## 2. API (`vfs_embed`, module `fold`)

| item | meaning |
|---|---|
| `fold_path(&str) -> Result<String, PathEscapesRoot>` | `vfs_core::fold(&normalize(path)?)`: the canonical key. Equal keys ⇔ same file. |
| `paths_equal(a, b) -> bool` | `fold_path(a) == fold_path(b)`; a root-escaping path equals nothing. |
| `fold_segment(&str) -> String` | `vfs_core::fold` itself, for one name; no path handling. |
| `PathEscapesRoot` | re-export of `vfs_compose::path::PathError`. |
| `FOLD_VERSION: u32` | definition version, `1`. |
| `fold_unicode_version() -> (u8, u8, u8)` | `char::UNICODE_VERSION` of the compiling toolchain. |

No copy of either engine function exists; the functions are compositions, and
a test asserts each equals the engine call it wraps.

## 3. The definition

1. **Normalise:** `\` and `/` separate; empty and `.` components drop; `..`
   pops, and popping past the root is `PathEscapesRoot`; joined with `/`.
2. **Fold:** `char::to_lowercase` per character — Unicode simple lowercase
   mapping. Not `CaseFolding.txt` full folding, not locale-aware, no NFC/NFD.

Consequences pinned by tests (and checked against a `Session` reading a
`MemoryProvider`): `ẞ`≡`ß` but `STRASSE`≢`straße`; `I`≡`i`, `I`≢`ı`,
`İ`≢`i` (`İ` → `i`+U+0307); `ΟΔΟΣ`≢`οδος`; precomposed `é`≢`e`+U+0301.

**Win32 cleanup is not identity.** Trailing dots/spaces, `\\?\`, drives,
streams and device aliases are stripped by the shim's canonicaliser before a
vpath exists; on vpaths, `a.esp.` and `a.esp` are different names.

## 4. Stability

A host storing folded keys has made the fold part of its identity.

- **Definition changes** are already wire-visible for the engine; when one
  happens `FOLD_VERSION` is bumped and `rust/CHANGELOG.md` records it.
- **Unicode tables** follow the compiling toolchain (not pinned). New versions
  add mappings for new characters and very rarely change old ones.

Hosts should store original spellings and fold at compare time, or record
`(FOLD_VERSION, fold_unicode_version())` with persisted keys and rebuild on
change; host and VFS should be built in one cargo graph so they share tables.

## 5. Cost

One allocation per `fold_path`; ASCII paths are two byte passes, non-ASCII a
per-character table lookup. `paths_equal` folds both sides; fold once and
compare strings when matching one path against many.

## 6. Out of scope

Registry key folding (`regpath::fold`, a separate namespace), and any change
to the fold itself.
