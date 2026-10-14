# aether-vfs — Write routes: paths a provider owns above the write layer

Date: 2026-10-13
Status: implemented on branch `memory-write-routing`.

## 1. Problem

A mod manager (Mauvi) composes each root from three kinds of thing:

- read-only layered content: game files, mods, the host's own read-only providers;
- one writable **write layer** (`Session::set_write_layer_at`, typically an
  `aether-storage` named layer) that takes every ordinary write, with copy-up
  for edits of read-only files;
- a few **writable synthesized files**, such as `Data/plugins.txt`. Each is a
  `MemoryProvider` holding bytes the host rendered. The game reads and writes
  the file, and when the session ends the host reads it back
  (`Session::read_file_at`) and compares it with what it rendered.

`compose_root` builds `overlay(MountGraph(mounts), write_layer)`. The write
layer sits **over every mount**, so a memory provider mounted with
`mount_at` is just more base content: the first write to `plugins.txt`
copies it up into the write layer, and the write lands there. The host's
buffer keeps the bytes it rendered, and the session serves the write layer's
copy. `vfs-embed/tests/write_routes.rs`
(`a_mounted_memory_file_under_a_write_layer_is_copied_up_not_written`) pins
this for an in-place write, a truncating write, and write-temp-then-rename.
All three go to the write layer. A persistent write layer then keeps that
copy across sessions and shadows every later render.

## 2. Options considered

**A. The overlay learns "claimed" paths** that it neither copies up nor
whiteouts, forwarding every operation on them to the base. This puts routing
into the overlay, the most intricate combinator in the crate (whiteout index,
copy-up lock, staging), and it still has to solve listings (the upper may hold
a stale copy) and renames between a claimed and an unclaimed name. Those are
routing problems, and the crate already has a router.

**B. A router above the overlay.** `router({pattern: provider}, default =
overlay(graph, write_layer))`. This is the shape the pluggable-providers spec
already uses for exactly this case (§8: `router({"*.ini": inis},
default=overlay(...))`). Two gaps in `RouterProvider` stood in the way, both
recorded in that spec as unimplemented:

1. `readdir` is single-dispatch: a listing of `Data` comes from whichever
   child owns `Data`, so a routed file is invisible to enumeration (and a
   stale copy below it is listed instead).
2. `rename` routes both ends by the source. Write-temp-then-rename creates
   the temp file in the write layer and renames it there, so the result lands
   in the write layer again.

And `RouterProvider::capabilities` reported the *weakest* access of its
children, so on a root without a write layer a writable route was refused by
the director's coarse `ReadWrite` gate before it was reached.

**Chosen: B.** It is the composition the spec already describes, it keeps the
overlay unchanged, and fixing the router's gaps is useful to any router, not
only this one.

## 3. Design

### 3.1 `RouterProvider` (vfs-compose)

- **Ownership.** Every path has exactly one owner: the first route whose
  pattern matches it, else the default. `getattr`, `open`, `mkdir`, `remove`,
  `set_attr` and `stored_name` go to the owner, unchanged.
- **`readdir` is a union filtered by ownership** (spec §6 "Router
  asymmetry"). The default and each distinct route provider are listed; an
  entry is kept only from the child that owns its full path, so a listing
  agrees with `getattr` for every name in it. A stale copy of a routed file
  in the write layer is dropped; the routed file is listed. One exception: a
  *directory* entry from a route provider that no owner lists is kept as it
  is, so a route like `Data/Config/*.ini` keeps `Config` discoverable when
  nothing else has that directory. With no routes, `readdir` is the
  default's, untouched.
- **`rename` between owners is a move.** Same owner: forwarded, as before.
  Different owners: the source file is copied into the destination's owner
  under a `.cu.<n>.<name>` staging name, renamed onto the destination there
  (so the destination is replaced whole, never seen half-written), and then
  the source is removed from its owner. The staging name is invisible: the
  overlay hides `.cu.` names, and in any other provider the name is not owned
  by that provider, so the union drops it. A directory cannot move between
  owners (`ST_BAD_REQUEST`, the answer `MountGraph` gives for a cross-mount
  rename); a file cannot replace a directory (`ST_EXISTS`).
- **Capabilities.** Strongest access, weakest of everything else, as
  `MountGraph` already does. A write is then refused per path: `open` for
  write on a path whose owner is not `ReadWrite` records a rejected write and
  returns `ST_READ_ONLY`.

### 3.2 `Session::route_at` (vfs-embed)

```rust
pub fn route_at(&self, root: RootId, pattern: &str, provider: Arc<dyn Provider>) -> Result<(), i32>
```

Gives `provider` every path in `root` matching `pattern` (the router's glob:
`*`, `**`, `?`, case-insensitive with `vfs_core::fold`). Routes accumulate, and
a later route wins over an earlier one on a path both match, as mounts do.
They survive `set_root_mounts` and `set_write_layer_at`, and `clear_root`
drops them. The provider sees paths **unchanged** (root-relative), so a memory
provider for `Data/plugins.txt` holds the key `Data/plugins.txt`. A
`SeqRead` provider is refused like a mount; a pattern that is empty or escapes
with `..` is `ST_BAD_REQUEST`. A read-only route is allowed: it pins a path
the write layer must not shadow, and writes to it are refused.

`compose_root` becomes `compose_root_routed(mounts, write_layer, routes)`,
which builds `RouterProvider(default = overlay-or-graph, routes)` when there
are routes and exactly what it built before when there are none.
`compose_root(mounts, write_layer)` stays as the no-routes form, so its
existing caller (`skyrim-live`) is unchanged.

### 3.3 What a game sees

| Operation on `Data/plugins.txt` | Result |
|---|---|
| read, `getattr`, listing `Data` | the buffer's bytes and size; listed once |
| open for write, in place | written in the buffer |
| `OPEN_TRUNC`, `set_len` | the buffer is truncated |
| write `plugins.tmp`, rename onto it | the temp goes to the write layer; the rename moves it into the buffer and removes it from the write layer |
| rename it away (`ReplaceFile` backup) | moved into the write layer under the new name; the buffer no longer holds it |
| delete | removed from the buffer; no whiteout is written |
| create after delete | created in the buffer |

The host reads the result with `Session::read_file_at(root,
"Data/plugins.txt")`. `ST_NOT_FOUND` there means the game deleted the file
(or moved it away) and did not recreate it.

## 4. Invariants

- **No bypass.** Nothing changes in the shim or the director: every
  operation still reaches the director and is answered by the one provider
  the root serves. The route is inside that provider.
- **Copy-up atomicity** is kept for the cross-owner move: the destination is
  replaced by a rename inside its owner, as overlay copy-up does.
- **One answer per name.** A listing never shows a name whose `getattr` comes
  from a different child.

## 5. Limits

- A cross-owner rename is a copy followed by a remove, not one atomic step.
  If the remove fails, both names exist and the rename reports the error.
- A rename of a **directory** that contains a routed path (`Data` →
  `Data.old`) moves the default's directory only; the routed file stays at
  its own path. Games do not do this to their data directory.
- The router's `**` and the director's rename both work within one root, as
  before.
