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
| `src/aether/director/ring.clj` | Section, header, events, worker threads, the opcode handlers. Requests are read in place and replies written over them; bulk reads go straight into the slot's arena bank. |
| `src/aether/director/main.clj` | Serves the benchmark fixture: prints `SECTION <name> <map_bytes>`, serves until stdin closes. |

Read-only so far: HEARTBEAT, GETATTR, READDIR, OPEN, READ (inline and bulk),
CLOSE. Write opcodes answer `ST_READ_ONLY`.

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

**Cold**, the first 2,000 GETATTRs after start:

| | p50 | p90 | p99 | max |
|---|---|---|---|---|
| Rust | 1.4 µs | 2.0 µs | 4–5 µs | 74–86 µs |
| Clojure (jar) | 3.3 µs | 36 µs | 108 µs | 0.53–0.91 ms |
| Clojure (jar + AOT cache) | **1.9 µs** | **12 µs** | 51–77 µs | 0.34–0.47 ms |

**What this does and doesn't show:**
- **It isn't a language comparison alone.** Warm, the Clojure director is faster
  because it does less per request: one lookup in a prebuilt index, against
  the Rust director's provider stack (a mutex and a lookup per layer, a `Vec`
  per reply, a global stats mutex). It does show that the ring mechanics
  (atomics, events, arena copies) cost no more on the JVM.
- **Cold is the JVM's real cost.** The first few thousand requests run before
  the JIT has compiled the handlers. That is exactly a game's startup. The AOT
  cache, trained on this workload, brings p50 close to Rust; the tail stays
  higher. The next mitigation is a warm-up pass: run synthetic requests
  through the handlers while launch is staging and injecting (~0.7 s that
  happens anyway).
- **The content is in memory.** A real director reads disk and Stored zips;
  positional reads (`FileChannel.read(dst, pos)`) or mapped zips, and no
  provider-wide lock, are the plan there.
- **No GC pressure yet.** The hot path allocates only a request's path string,
  so ZGC has little to do. A real tree, writes and a long session need a
  measurement under load.
