# Clojure director (experiment)

A ring server for the aether-vfs shim, written in Clojure on JDK 26 with the
Foreign Function & Memory API. It speaks the shim's wire contract byte for
byte (ring version 2: `rust/crates/vfs-ipc`, `vfs-protocol`), so the in-game
shim and payload stay Rust and only the host side changes.

This is step 1 of a plan to move the director, then launch and staging, into
Clojure: **does a JVM serve the ring as fast as the Rust director?** Steps
after it: a director over a real content projection (disk, Stored zips),
launch (staging, `CreateProcessW` with an explicit environment, the existing
Rust injector), writes.

## Layout

| | |
|---|---|
| `java/aether/director/Native.java` | VarHandle atomics on the mapping, kernel32 downcalls (sections, events). These are signature-polymorphic, which Clojure can only reach by reflection. |
| `java/aether/director/Guard.java` | Calls a request handler without boxing its result: a Clojure `try` allocates a Long per request. |
| `src/aether/director/proto.clj` | The wire contract: header and slot layout, geometry, opcodes, statuses. |
| `src/aether/director/path.clj` | `fold` (exactly Rust's `char::to_lowercase`, per code point) and vpath normalization. |
| `src/aether/director/tree.clj` | The content index: folded vpath to file or directory, every directory's READDIR reply encoded once. The layering a Rust provider stack does per request has already happened by the time a tree is built. |
| `src/aether/director/ring.clj` | Section, header, events, worker threads, the opcode handlers. Requests are read in place and replies written over them; bulk reads go straight into the slot's arena bank. Also the warm-up (below). |
| `src/aether/director/main.clj` | Serves the benchmark fixture: prints `SECTION <name> <map_bytes>`, serves until stdin closes. |

Read-only so far: HEARTBEAT, GETATTR, READDIR, OPEN, READ (inline and bulk),
CLOSE. Write opcodes answer `ST_READ_ONLY`.

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
