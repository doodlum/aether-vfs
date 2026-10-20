# Clojure director (experiment)

A director for the aether-vfs shim, written in Clojure on JDK 26 with the
Foreign Function & Memory API. It speaks the shim's wire contract byte for
byte (ring version 4: `rust/crates/vfs-ipc`, `vfs-protocol`; checked against
the golden vectors), so the in-game shim stays Rust and only the host side
changes. It launches Skyrim SE from a Stored zip (below).

## Layout

| | |
|---|---|
| `java/aether/director/Native.java` | VarHandle atomics on the mapping, kernel32 downcalls (sections, events). These are signature-polymorphic, which Clojure can only reach by reflection. |
| `java/aether/director/Guard.java` | Calls a request handler without boxing its result: a Clojure `try` allocates a Long per request. |
| `src/aether/director/proto.clj` | The wire contract: header and slot layout, geometry, opcodes, flags, statuses. |
| `src/aether/director/path.clj` | `fold` (exactly Rust's `char::to_lowercase`, per code point) and vpath normalization. |
| `src/aether/director/provider.clj` | The provider protocol (below). |
| `src/aether/director/tree.clj` | Index provider: content that never changes (Stored zip entries, mapped files), indexed once; READDIR replies encoded once. |
| `src/aether/director/disk.clj` | Disk provider: a real folder, readable and writable. |
| `src/aether/director/compose.clj` | `layered` (read-only, later wins) and `overlay` (copy-on-write). |
| `src/aether/director/zip.clj` | Stored ZIP/ZIP64 archives, mapped once; entries are slices. |
| `src/aether/director/ring.clj` | Section, header, events, worker threads, every opcode. Requests are read in place and replies written over them; bulk reads go straight into the slot's arena bank. Also the warm-up. |
| `src/aether/director/stage.clj` | Staging a launch image and its PE import closure. |
| `src/aether/director/launch.clj`, `run.clj` | Sessions, `shim.cfg`, the child's environment, the injector; the command line. |

## The wire: what's served

All of ring version 4 except the registry. HEARTBEAT, GETATTR, READDIR, OPEN
(read and write, with CREATE/EXCL/TRUNC), READ (inline and bulk), WRITE,
SETATTR (truncate/extend), RENAME, DELETE, MKDIR, CLOSE, STORED_NAMES,
cross-root RENAME_X (files move by copy; a directory is ST_CROSS_ROOT), and
STAGE_EXEC (a VFS-only child EXE, staged on demand). OPEN replies are
immutable with mount_gen 1 for index content, so the shim may cache small
reads; writable handles never are. The registry ops (15-22) answer
NOT_SUPPORTED, and VFS_REGISTRY is never set.

## Providers

Each root is served by one provider, usually composed. A provider is two
protocols (`aether.director.provider`):

```clojure
(defprotocol Provider
  (stat [p vpath])            ; Stat or nil       (folded vpath)
  (list-dir [p vpath])        ; [Entry …], nil, or a status
  (spelling [p vpath])        ; how the provider spells the path, or nil
  (open [p vpath flags])      ; a Handle or a status (path as the caller spelled it)
  (mkdir! [p vpath]) (delete! [p vpath]) (rename! [p from to]))   ; statuses

(defprotocol Handle
  (handle-stat [h]) (immutable? [h])
  (read-into [h dst dst-off pos len])     ; straight into a MemorySegment: the ring's arena
  (write-from [h src src-off pos len]) (truncate! [h size]) (close! [h]))
```

Failures are status codes, never exceptions; a missing path is nil (a game
probes missing files constantly). Reads go straight into the arena, so a
provider backed by memory or a mapping costs one copy. A provider that keeps
its READDIR replies encoded can also implement `Listing`.

| Provider | |
|---|---|
| `tree/build`, `tree/from-dir` | Index of [path MemorySegment] pairs: zip slices (`zip/open`), mapped files. Read-only. |
| `disk/disk` | A real folder. Creating a file makes missing parents; MKDIR is OK on an existing directory. |
| `compose/layered` | Read-only stack; later layers win; listings are unioned. |
| `compose/overlay` | A writable upper over a read-only lower: copy-up on write (via a `.cu.` temp and a rename), `.wh.<name>` whiteouts for deletes, listings merged with the lower's spelling kept. It indexes the upper, so reading lower content costs no disk stat, and a later session sees what the last one left. |

**Adding one:** implement the two protocols (and `Listing` if listings are
precomputed), then add it to `every-provider-conforms` in
`test/aether/director/provider_test.clj`. That's the conformance suite, after
vfs-provider's `conformance.rs`: stat, listing, case-insensitive lookups and
spellings, positional reads, and for writable providers create/EXCL/TRUNC,
positional writes, truncate/extend, MKDIR, rename, delete (non-empty
directories), names created as spelled. Semantics follow the Rust providers,
with three deliberate differences in the overlay: a directory holding only
whiteout markers can be removed, a rename into a folder only the lower has
creates it, and a base-only directory still can't be renamed (as upstream).

`rust/`'s `vfs-fixture-writepath` passes end to end under the Clojure
director with a write layer: copy-on-write of a read-only file, write/append
round trips, rename and delete, same-handle write-then-read, CREATE_NEW
exclusivity.

## Launching a game

`aether.director.run` serves layered content (Stored zips and folders, later
wins), stages the launch image with its import closure, and starts it through
rust/'s `vfs-injector` with **remote-thread injection** (`VFS_FORCE_INJECTION`):
the shim is loaded into the suspended process by full path. The injector's
other mode patches the image's import table in memory, and a self-verifying
SteamStub wrapper (SkyrimSE.exe) refuses to run under it ("Application load
error 3:0000065432").

Skyrim SE from the Stored zip, with the profile folders (INIs, plugins.txt)
served from a clean copy as roots 1 and 2, so nothing real is read or changed:

```bash
clojure -M:run --session 'C:\tmp\skyrim-clj' --natives <rust/target/release> \
  --zip 'C:\tmp\skyrimse.zip' --layer 'C:\tmp\skyrim-data\dx-redist' \
  --layer 'C:\tmp\skyrim-data\overrides\root-0' \
  --stage SkyrimSE.exe --fallback 'C:\tmp\skyrim-data\dx-redist' \
  --root '1=C:\Users\<you>\Documents\My Games\Skyrim Special Edition' --root-layer '1=<profile>\mygames' \
  --root '2=C:\Users\<you>\AppData\Local\Skyrim Special Edition' --root-layer '2=<profile>\localappdata'
```

Add `--write-layer <dir>` (root 0) and `--root-write <id>=<dir>` to give a
root a copy-on-write overlay: with them, the game creates its `Saves` folder
and rewrites `plugins.txt` in those folders, and nothing real is touched.

Result (2026-10-19): the game window in 4.1 s from the `clojure` command
(Clojure CLI and JVM start included; the director itself is serving 0.32 s
after its JVM starts), 9,383 requests, and a stable game. `--trace` counts
requests by op, outcome and path and prints the top 25 at exit: that is how a
stale 1,907-plugin Vortex `plugins.txt` in the real profile was found behind
3.2 million failed opens.

## Running

```bash
clojure -T:build javac       # java/ -> target/classes (once, and after editing java/)
clojure -M:test              # the pure parts: geometry, folding, normalization, the index
clojure -M:serve             # serve the fixture; then, from rust/:
cargo build --release -p vfs-ring-harness
target/release/vfs-ring-bench.exe 'Local\vfs_ring_clj_<pid>' 67174400
```

`vfs-ring-serve` serves the same fixture through the production Rust
director (`IpcServe`, the in-memory provider), for comparison.
`vfs-ring-bench` is one client for both. It runs in another process and wakes
the server the way the shim does (`SetEvent` on submit, then spin). It checks
every answer against the fixture before timing anything.

For the AOT-cache numbers, build the jar (`clojure -T:build jar`), run it once
with `-XX:AOTCacheOutput=target/director.aot` while the benchmark drives it,
then run it with `-XX:AOTCache=target/director.aot`.

## Results (2026-10-19, Ryzen 9 8945HS, JDK 26.0.2, ZGC)

**Warm**, after 5,000 warm-up requests; 50,000 measured (5,000 for 1 MiB):

| p50 / p99 | Rust director | Clojure director |
|---|---|---|
| HEARTBEAT | 0.70 / 1.6 µs | 0.60 / 4.7 µs |
| GETATTR | 1.3–1.4 / 3–6 µs | **0.70** / 3–6 µs |
| READDIR | 2.3 / 11 µs | **0.70** / 3.7 µs |
| OPEN+CLOSE | 3.1 / 18 µs | **1.5** / 10 µs |
| READ 4 KiB | 2.3 / 7–9 µs | **1.5** / 5–8 µs |
| BULK 64 KiB | 10.6 / 18 µs | 10.2 / 19 µs |
| BULK 1 MiB | 97–100 / 190 µs | 95–99 / 195 µs |
| sequential, 1 MiB x4 in flight | 8.9–9.9 GiB/s | **12.2–13.1 GiB/s** |
| GETATTR, 8 client threads | 2.1 M ops/s | **4.4–4.5 M ops/s** |

Neither server had a stall over 1 ms. Max latencies of ~300–500 µs are about
the same for both.

**Cold**, the first 2,000 requests after start (GETATTR, then 4 KiB READ):

| | GETATTR p50 / p90 / max | READ 4 KiB p50 / p90 |
|---|---|---|
| Rust | 1.4 / 2.0 / 74–86 µs | 2.6 / 3.1–3.4 µs |
| Clojure (jar) | 3.3 / 36 / 530–910 µs | 4.9–5.9 / 9–12 µs |
| Clojure (jar + AOT cache) | 1.9 / 12 / 340–470 µs | 3.4–3.6 / 6–7 µs |
| Clojure (jar + warm-up) | **1.0–1.5 / 1.9–2.7 / 70–240 µs** | **2.2–2.9 / 4–6 µs** |
| Clojure (jar + warm-up + AOT cache) | **0.8–1.2 / 1.2–1.9 / 90–150 µs** | **1.6 / 2.0–2.1 µs** |

**The warm-up** (`ring/warm-up!`, run by `ring/start` unless `:warm-up 0`)
drives 3,000 rounds of synthetic requests through the real code before the
section exists: the slot scan and CAS, dispatch, and every read-side handler,
on a private copy of the ring, over paths from the real tree. That is 24,000
requests in 120–170 ms, inside the ~0.7 s that staging and injection take
anyway. Each reply is checked, so it's also a self-test of the index. With
it, a cold Clojure director answers its first requests about as fast as the
Rust one.

**What this does and doesn't show:**
- **It isn't a language comparison alone.** Warm, the Clojure director is faster
  because it does less per request: one lookup in a prebuilt index, against
  the Rust director's provider stack (a mutex and a lookup per layer, a `Vec`
  per reply, a global stats mutex). It does show that the ring mechanics
  (atomics, events, arena copies) cost no more on the JVM.
- **Cold was the JVM's real cost, and the warm-up removes it.** Without it,
  the first few thousand requests (a game's startup, exactly) ran before the
  JIT had compiled the handlers. The warm-up compiles them first; the AOT
  cache then trims what's left.
- **The content is in memory.** A real director reads disk and Stored zips;
  positional reads (`FileChannel.read(dst, pos)`) or mapped zips, and no
  provider-wide lock, are the plan there.
- **No GC pressure yet.** The hot path allocates only a request's path string,
  so ZGC has little to do. A real tree, writes and a long session need a
  measurement under load.
