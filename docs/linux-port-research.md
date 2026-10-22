# Porting the Linux / Proton path to the Clojure director: research

Branch `clj-director` at `09f89535`. Read only, except for this file. All `file:line` references are
relative to the repo root unless they start with `cairn2/`, which means `C:\work\cairn2`.

## 0. Summary

- **The Rust Linux path is a thin launcher over the same shim.** The Windows shim DLL and
  `vfs-injector.exe` run unchanged inside GE-Proton's Wine (`files/bin/wine`, run directly: no
  `proton` per launch, no umu, no pressure-vessel, no Steam Linux Runtime). The shim gets in the same
  way as on native Windows, by remote-thread `LoadLibrary`, because Wine refuses the in-memory
  import patch (`docs/shim-invariants.md:883-895`). The ring is a **real file** that both sides map
  by path: Wine's `CreateFileMappingW(hFile)` on one side, native `mmap(MAP_SHARED)` on the other.
  There are **no events**: the shim's wake call is a no-op, and the director's workers poll with an
  adaptive spin then sleep.
- **What `vfs-unix` actually is.** It does no Unix/Wine path mapping. It is 300 lines that do one
  thing, a grow-only, file-backed `mmap` exposed as a `SharedSeg`
  (`rust/crates/vfs-unix/src/mapping.rs:16-176`). Unix↔Wine paths are handled in `vfs-proton`'s
  prefix module (`Prefix::windows_path`, `parse_location`, `link_location`). It uses symlinks inside
  `drive_c`, never drive letters, and `Z:` stays mapped to `/`.
- **The JVM needs no new native code for the core path.** `FileChannel.map(READ_WRITE, 0, n,
  arena)` already returns a shared `MemorySegment` over a file, and the existing `Native` VarHandle
  atomics work on it unchanged. Waking is `LockSupport.parkNanos`. Process control uses
  `ProcessBuilder` plus `wineserver -k/-w`. Only process-group isolation (`setpgid`) needs a
  workaround: the `setsid` binary, or an FFM call to `posix_spawn`.
- **Most of the Clojure director carries over unchanged:** the ring protocol, dispatch, every
  handler, providers, storage, stores, sources, registry and staging logic. What has to change is
  `ring/start`/`stop` (section and events → file and park), `launch/session` and `launch/launch` (env,
  path forms, spawn), and a new `aether.director.proton.*` family (runtime, prefix, env, Steam,
  ring home, launch lifecycle).
- **Cost.** About 1.5–2.5k lines of new Clojure. About 60% of it can be written and tested on this
  Windows box, and the file-backed ring transport can be tested end to end here against the real
  Windows shim. The rest needs a Linux machine with GE-Proton (WSL2 was used for the original
  measurements) and, for the Steam pieces, a running Steam client.

---

## 1. The Linux path end to end, in Rust

### 1.1 Crate map

| Crate / file | Lines | Role |
|---|---|---|
| `rust/crates/vfs-proton/` (src) | ~5.9k (incl. tests in-file) | GE-Proton acquisition, verification, prefix, launch env/argv, Steam helper, NVAPI |
| `rust/crates/vfs-unix/src/mapping.rs` | 289 | file-backed `mmap` → `vfs_ipc::SharedSeg` (the only `libc` user) |
| `rust/crates/vfs-embed/src/session/proton/{mod,handle,ring}.rs` | 1433 + 345 + 302 | the orchestrator: `Session::serve`/`launch` on unix, prefix links, lifecycle, ring home |
| `rust/crates/vfs-director/src/ipc.rs` | 497 (unix part ~110) | `IpcServe::start_file_backed*`, chooses `FileMapping` vs `SharedMapping` |
| `rust/crates/vfs-ipc/src/notifier.rs` | `AdaptiveNotifier` ~60 | the event-free server wait |
| `rust/crates/vfs-shim/src/director.rs` | file-ring client ~50 | `RingSource::File`, null event, `WakeServerSpinClient` |
| `rust/crates/vfs-env/src/lib.rs` | 1408 (shared) | every `VFS_*` name + the handshake lists |

`vfs-embed` gates the two delivery mechanisms in its manifest. On Windows it pulls in
`vfs-inject`/`vfs-shim`. On unix it pulls in `vfs-proton` with `default-features = false`, which
drops the downloader (`rust/crates/vfs-embed/Cargo.toml`, `[target.'cfg(unix)'.dependencies]`). The
design rationale is in `docs/superpowers/specs/2026-09-01-wine-hosted-shim-design.md` §1–§5. The
design measured remote injection, ntdll detours and cross-boundary ring coherence under
GE-Proton11-6, with `wine` run directly.

### 1.2 Step by step

**(a) Runtime: find or install GE-Proton.** Nothing in the code base touches Steam's own Proton or
the Steam Linux Runtime. Grep finds no `pressure-vessel`, `steam-runtime`, `sniper` or `soldier`
outside the specs that defer them (`2026-09-01-wine-hosted-shim-design.md:203-217`).

| Piece | Where | What | Size |
|---|---|---|---|
| Home layout | `vfs-proton/src/layout.rs:32-61` (`Root::from_env`) | `$VFS_HOME` → `$XDG_DATA_HOME/aether-vfs` → `~/.local/share/aether-vfs` (→ `%LOCALAPPDATA%` on Windows for tests); `runtimes/`, `downloads/`, `sessions/` | 174 |
| Traversal guard | `layout.rs:98-117` (`try_runtime_dir`, `try_session_dir`, `validate_component`) | tags and session ids must be one plain component | in above |
| GE gate | `vfs-proton/src/runtime.rs:60-77` (`verify_ge`) | `<rt>/version` must contain a `GE-Proton…` token; **a hard error, never a fallback**, because an unset or relative `PROTONPATH` silently means stock UMU-Proton | 435 |
| FFmpeg check | `runtime.rs:82-100` (`check_ffmpeg`) | if `winedmo.so` exists, `files/lib/x86_64-linux-gnu/libavformat.so.*` must too | |
| Lib env | `runtime.rs:109-139` (`runtime_lib_env`) | what `proton`'s `init_wine` sets: `LD_LIBRARY_PATH = files/lib/{x86_64,i386}-linux-gnu:<inherited>`, `ORIG_LD_LIBRARY_PATH`, `WINEDLLPATH = files/lib/vkd3d:files/lib/wine:<inherited>` | |
| Ordering / discovery | `runtime.rs:155-224` (`cmp_tags`, `installed_dirs`, `newest_installed`) | numeric `(N,M)` order; follows symlinks (a runtime linked in from `compatibilitytools.d` counts) | |
| Release query | `vfs-proton/src/release.rs:47,59-136` (`RELEASES_URL`, `parse_releases`, `pick`, `fetch_releases`) | GitHub API for `GloriousEggroll/proton-ge-custom`, `-x86_64.tar.gz` + `.sha512sum` pairs, optional `GITHUB_TOKEN` | 219 |
| Install | `vfs-proton/src/install.rs:122,139,191,414-520` | digest first, stream body to `.partial`, sha512 verify, extract to `.tmp-…`, **gate on `verify_ge` again**, rename last (old one moved aside) | 883 |
| CLI | `vfs-proton/src/bin/vfs-proton.rs` (`install`/`list`/`path`) | the only acquisition front end; `Session::launch` never downloads | 338 |

`Session::launch` on unix takes the **newest verified runtime under the home**
(`vfs-embed/src/session/proton/mod.rs:700-704`). If there is none, the launch fails and tells the user to run
`vfs-proton install` (`mod.rs:574-585`).

**(b) The prefix.**

| Piece | Where | What | Size |
|---|---|---|---|
| Kinds | `vfs-proton/src/prefix/init.rs:36-63` (`PrefixInit`) | `Wineboot` (`wine wineboot -u` into `sessions/<id>/prefix`) or `Proton {steam_client, app_id}` (`<rt>/proton run cmd /c exit` with `STEAM_COMPAT_DATA_PATH=sessions/<id>/compat` → prefix `compat/pfx`; brings in DXVK, vkd3d, redistributables, `lsteamclient.dll` and `steam.exe`; needs `python3`) | 510 |
| Idempotence / upgrade | `init.rs:98-160` (`ensure_with`, `set_up_from`, `proton_prefix_version`) | re-run Proton setup when `compat/version` names another tag or `config_info` names another runtime path (a moved home) | |
| Proton setup env | `init.rs:162-231` (`run_proton_init`, `inherited_proton_env`) | strips `PROTON_*` and `WINEDLLOVERRIDES`; sets `PROTONFIXES_DISABLE=1`, `WINEDEBUG=-all`, `SteamAppId`/`SteamGameId`; bounded at 300 s; log `compat/aether-proton-init.log` | |
| Wineboot | `init.rs:249-278` (`run_wineboot`) | `mscoree=d;mshtml=d`, lib env, own process group; `ld-linux.so.2` in output → `Missing32Bit` | |
| DXVK overrides constant | `init.rs:68-69` (`PROTON_GRAPHICS_OVERRIDES`) | `d3d11=n;d3d10core=n;d3d9=n;dxgi=n;d3d8=n;d3d12=n;d3d12core=n`, which a host passes because a direct `wine` run does not apply Proton's per-launch overrides | |
| Lock | `prefix/mod.rs:126-154` (`Prefix::lock`) | `flock` on `<prefix>/.aether-vfs.lock`, 500 ms retry budget | 257 |
| Path form | `prefix/mod.rs:164-177` (`Prefix::windows_path`) | host path under `drive_c` → `C:\a\b`; `None` outside `drive_c` (never invented) | |
| Root links | `prefix/links.rs:62-81,124-208` (`parse_location`, `link_location`, `unlink_location`) | `C:\…` location → components under `drive_c`, matched ASCII case-insensitively (`find_entry_ci` `links.rs:26-44`); symlink to the host backing dir; refuses real files/dirs and foreign symlinks; manifest `.aether-vfs-links` (`links.rs:51`) | 451 |
| wineserver | `prefix/wineserver.rs:26-52,61-69` | `stop_wineserver` = `wineserver -k` then `-w`, each bounded at 30 s (needed before deleting a prefix: wineserver writes `*.reg` back on exit); `spawn_wineserver_wait` = unbounded `wineserver -w`, the liveness signal | 70 |
| Removal | `prefix/mod.rs:113-121` (`remove_session`) | `rm -rf sessions/<id>` | |

The orchestrator (`vfs-embed/src/session/proton/mod.rs`) uses the session's anonymous prefix
(`session-<hash(state_dir)>`, `mod.rs:374-379`, deleted on drop after `stop_wineserver`,
`mod.rs:935-951`) or a named persistent one (`set_prefix_name`, `mod.rs:276-282`). It locks the
prefix **before** `ensure_with` (`mod.rs:730-738`).

**(c) Path forms: Unix ↔ Wine.** There is no `Z:\` translation layer and no `dosdevices` editing.
The 09-01 spec's plan to remove `z:` (`2026-09-01-wine-hosted-shim-design.md:219-228`) was **not
implemented**: grep finds no `dosdevices` code in `vfs-proton`, and `launch/mod.rs:155-157` relies on
`Z:` for the default cwd. The model is:

- **Host paths for things the injector opens and `wine` resolves.** The injector, the shim DLL, the
  config file and the ready file go to `wine` as host paths. Wine resolves a rooted `/…` path on the
  current drive, which is `Z:` (`launch/mod.rs:90-96`, `LaunchFiles` `:58-67`).
- **`C:\…` for anything the shim or the target reads inside Wine:** the target, `VFS_VIRTUAL_DIR`,
  `VFS_VIRTUAL_ROOTS`, `VFS_RING_PATH` and `VFS_INJECT_CWD`. They get a `C:` name by being
  symlinked into `drive_c`:
  - the state dir → `C:\vfs-session\state` (`link_into_prefix`, `mod.rs:396-419`;
    `WINE_LINK_DIR` `:1031`);
  - each root's host backing dir → its declared location, shallowest first (`link_roots`,
    `mod.rs:431-454`);
  - root 0's default location is `C:\vfs-session\root` (`mod.rs:1037`).
- `join_wine` (`mod.rs:1046-1064`) builds `C:\…\ring.bin` and refuses `..` or rooted components.
  `wine_cwd` (`mod.rs:1003-1020`) defaults to the target's directory.
- A host path outside every root is refused with "no drive names it" (`session/stage.rs:173-197,
  303-309`).

**(d) The ring.**

- `Session::serve` on unix (`vfs-embed/src/session/proton/mod.rs:315-353`) unlinks
  `state_dir/ring.bin` and tries to put the file on a private tmpfs. `ring_in_memory`
  (`session/proton/ring.rs:101`) checks that `$XDG_RUNTIME_DIR` is a tmpfs/ramfs by parsing
  `/proc/self/mountinfo` (`ring.rs:13-49`) and is owned 0700 by this uid (`ring.rs:55-65`). It then
  creates `aether-vfs/ring-<hash>/ring.bin` 0600, exclusively, and makes `state_dir/ring.bin` a
  symlink to it. Otherwise it uses a plain file. It finally calls
  `IpcServe::start_file_backed_with_workers`.
- `IpcServe::start_file_backed_with_workers` (`vfs-director/src/ipc.rs:286-357`) uses the **same
  geometry arithmetic** as the section path. It calls `FileMapping::create`, which is grow-only and
  sized before mapping because `mmap` past EOF raises SIGBUS (`vfs-unix/src/mapping.rs:45-64`). Then
  come `ring::init`, the worker hint, the registry publish, and N workers on `AdaptiveNotifier`.
- `AdaptiveNotifier` (`vfs-ipc/src/notifier.rs:69-125`) spins 64× during a 400 µs window after a
  completed request (`VFS_RING_SPIN_US`). Otherwise it sleeps 100 µs, 500 µs after 5 ms of quiet,
  or 2 ms after 50 ms of quiet (`idle_sleep` `:99-109`). It replaces the earlier pure-spin design
  (rationale at `ipc.rs:228-260`).
- On the shim side (`vfs-shim/src/director.rs:356-393`), `VFS_RING_PATH` **wins** over
  `VFS_RING_SECTION`. `SharedMapping::open_file_backed` (`vfs-win/src/mapping.rs:133`) maps the
  file, and the server event is **null on purpose**, so `notify_server` is a no-op
  (`director.rs:216-245`). The client spins, yields, then `Sleep(1)`.
- `check_geometry` (`launch/mod.rs:471-496`) refuses `arena_offset+arena_len > ring_bytes` and a ring
  file shorter than `ring_bytes`. Both are "attaches fine, fails under load" bugs.

**(e) Environment.** `launch_env` (`vfs-proton/src/launch/env.rs:54-168`) is pure and builds:

- **Wine and Proton:** `WINEPREFIX`, `PROTONPATH` (absolutized), the lib env above,
  `WINEDLLOVERRIDES` = `mscoree=d;mshtml=d` (`env.rs:12`) [+ `steam.exe=b`] [+ NVAPI's] merged
  per-DLL with the caller's (`merge_dll_overrides` `env.rs:213-238`), and `WINEDEBUG=-all`.
- **The shim:** `VFS_RING_PATH` (C: form), `VFS_RING_BYTES`, `VFS_RING_PAYLOAD_CAP`,
  `VFS_ARENA_OFFSET`, `VFS_ARENA_LEN`, `VFS_VIRTUAL_DIR`, `VFS_VIRTUAL_ROOTS` (`id=C:\…;…`), and
  `VFS_REGISTRY=1` when a registry layer is attached.
- **The injector:** `VFS_INJECT_CWD`, `VFS_READY_TIMEOUT_SECS` and `VFS_INJECT_STEAM_HELPER`.
- **Steam** (Helper mode only): `SteamAppId`, `SteamGameId` and
  `STEAM_COMPAT_CLIENT_INSTALL_PATH`.
- **Deliberately unset:** `VFS_RING_SECTION`, `VFS_SERVER_EV`, `VFS_CLIENT_EV`, and `STEAM_COMPAT_*`
  beyond the one above (`env.rs:26-48`).

Reserved names are refused case-insensitively, because Wine's env is case-insensitive
(`is_reserved_env` `env.rs:191-204`). Stale handshake names inherited from the host are removed, and
so is anything in `remove_env` in every spelling (`clear_env` `launch/mod.rs:412-428`,
`stale_env` `env.rs:245-247`).

**(f) The launch.**

- `command_line` (`launch/mod.rs:318-335`) builds `<rt>/files/bin/wine vfs-injector.exe <C:\target>
  vfs_shim_dll.dll <config> <ready> [-- args…]`. The argv is positional: swap two and everything
  starts, but the shim silently never attaches.
- `spawn` (`launch/mod.rs:350-404`) checks the reserved env, runs `verify_ge` and `check_geometry`,
  removes stale `<ready>.injector-error` and `<ready>.steam-helper` reports, then spawns through
  `hostenv::command`, which strips an AppImage's `$APPDIR` pollution (`hostenv.rs:24-76`). It puts
  the child in its own process group and retries on ETXTBSY (`process.rs:47-79`). stdout and stderr
  go to one log fd.
- `finish` (`launch/mod.rs:441-456`) treats exit 2 or 3 as the injector's own failure and reads the
  `.injector-error` report (`launch/injector.rs` `describe_injector_error`). Any other code is the
  target's.
- Artifacts are located by `locate_wine_artifacts` (`mod.rs:1082-1130`): `LaunchOpts` paths, then
  `VFS_WINDOWS_ARTIFACTS`, then beside `current_exe`. They are **the same Windows binaries**,
  cross-built with `bin/build-windows` (cargo-xwin). The canonical list is in
  `vfs-proton/src/artifacts.rs:13-34`.
- **Lifecycle** (`session/proton/handle.rs`): liveness follows **the prefix, not the first
  process**. Once the injector's `wine` exits, `wineserver -w` is spawned, and the launch ends when it
  returns (`handle.rs:258-290`). This covers `skse64_loader.exe` → `SkyrimSE.exe`. Stopping is
  `wineserver -k`, gated by an `ended` flag so a stale stopper cannot kill a later launch
  (`handle.rs:76-130`). `Session::stop_launch` and `drop_proton` are at `mod.rs:856-952`.

**(g) Injection.** This is the same mechanism as Windows. `vfs-injector.exe` runs as a Wine process,
creates the target suspended, writes the DLL path, then `CreateRemoteThread(LoadLibrary)` and waits
for the ready file. This was measured at `2026-09-01-wine-hosted-shim-design.md:30-44`. Under Wine
the in-memory import patch is refused (`UnderWine`), so Proton and Windows run the same remote
activation (`docs/shim-invariants.md:883-895`). The injector detects Wine with
`running_under_wine` (`vfs-inject/src/steam_helper.rs:98-105`). There are **no Wine DLL overrides
for the shim**. Overrides are used only for Mono/Gecko, `steam.exe=b`, NVAPI and, at the host's
choice, DXVK and proxy DLLs (see `staged_proxies`, `session/stage.rs`;
`vfs-director/src/stage.rs:110-150` `PROXY_DLL_NAMES`). Wine prefers its builtin `dinput8`,
`version`, `d3d11` and so on, so a mod's proxy DLL loads only with `name=n,b`.

**(h) Steam.** `vfs-proton/src/steam.rs` (230 lines, docs `:1-65`):

- **Detecting the client.** A host Steam client is detected from `~/.steam/steam.pid` plus
  `/proc/<pid>/comm == "steam"` (`steam.rs:194-220`).
- **Choosing a mode.** In a Proton prefix with an app id and a running client, the injector starts
  Proton's helper before the target (`STEAM_HELPER = C:\windows\system32\steam.exe
  C:\windows\system32\rundll32.exe`, `steam.rs:77`; override `steam.exe=b` `:81`). The helper writes
  `HKCU\Software\Valve\Steam\ActiveProcess` so `SteamAPI_IsSteamRunning` is true.
  `steam_api64.dll` → `lsteamclient` → `~/.steam/sdk64/steamclient.so` → the host client.
  Otherwise the mode is `Off`: the injector clears the stale pid. Mode selection is
  `Session::steam_launch` (`mod.rs:210-252`).
- **Reporting.** The injector writes `<ready>.steam-helper` (`helper_report_path`, `helper_status`,
  `steam.rs:118-178`).

**(i) NVAPI.** `vfs-proton/src/nvapi/` (detect 404, install 390, status 213, mod 124) mirrors the
`proton` script. It copies dxvk-nvapi DLLs into `system32`/`syswow64` and the driver's
`nvidia/wine/*nvngx.dll`, and sets `DXVK_ENABLE_NVAPI=1`, `NVIDIA_WINE_DLL_DIR` and the wine-nvml
`WINEDLLPATH`. It is optional per launch (`nvapi_for_launch`, `mod.rs:969-997`).

---

## 2. What is the same on both platforms, and what the Clojure director already has

**The same binaries and the same bytes on the wire.** The ring layout and geometry, the opcodes,
`shim.cfg` v2, the `VFS_VIRTUAL_ROOTS` format, the ready and injector-error files and the
`VFS_FORCE_INJECTION` activation are identical. The protocol descriptor does not change
(`2026-09-01-wine-hosted-shim-design.md:283-294`).

Reused unchanged from `clj/src/aether/director` (8,146 src lines; 10,630 with tests):

| Namespace | Lines | Why it carries over |
|---|---|---|
| `proto.clj` | 113 | `geometry` (`proto.clj:52-65`) is exactly `start_file_backed`'s arithmetic (`ipc.rs:292-296`); opcodes and statuses |
| `ring.clj` handlers, dispatch, `serve-pass`, `serve-slot` CAS, `warm-up!` | ~470 of 585 | operate on a `MemorySegment`; nothing knows how it was mapped (`ring.clj:55-63,389-420,486-530`) |
| `java/aether/director/Native.java` atomics | lines 29-52 | `VarHandle` get/set/CAS/fetchMax on any segment; the kernel32 holder class is lazy (`Native.java:58-88`), so the class loads on Linux |
| `path.clj` | 55 | vpath fold/normalize (the shim sends root-relative `/` paths on both) |
| `provider*.clj`, `storage*.clj`, `store*.clj`, `source/**` | ~5.5k | pure JVM; `NativeLib` already ships `natives/linux-x86_64/libaether_native.so` (`NativeLib.java:44-60`, `resources/natives/`) |
| `registry.clj` | 606 | the director stores only the overlay; the shim merges it over "the real registry", which under Wine is the prefix's (`registry.clj:1-20`) |
| `stage.clj` PE parsing, closure walk, `system-dll?` | 157 | pure; only *where* it writes changes |
| `launch.clj` `shim-config` | `launch.clj:29-43` | the format is the same; only the root string changes (C: form) |

The **Windows remote-thread injection is the Linux injection too.** On Linux, `VFS_FORCE_INJECTION=1`
is still the right setting, and the Clojure director already sets it (`launch.clj:105-110`).

---

## 3. What differs, and the Clojure seams

### Recommended shape

There is one platform value, chosen once, and two kinds of seam:

```clojure
(ns aether.director.platform)
(defn current [] (if (str/starts-with? (str/lower-case (System/getProperty "os.name")) "windows")
                   :platform/windows :platform/linux))
```

- **Hot-path waking: a Java interface**, because it is called per request per worker and must
  not box. It goes in `java/aether/director/Waker.java`:
  ```java
  public interface Waker {
    void completed();              // after a CAS to COMPLETED (Windows: SetEvent(cli))
    void idle(long quietNanos);    // nothing to serve (Windows: spin or WaitForSingleObject(srv,1); Linux: spin or parkNanos)
    void wakeAll();                // on stop
    void close();
  }
  ```
  `Ring`'s `srv-event cli-event spin-ns` fields (`ring.clj:59`) become one `^Waker waker` field.
  `worker` (`ring.clj:422-433`) and `serve-slot` (`ring.clj:403-405`) call it through a type-hinted
  interface call. A protocol would also work, but an interface keeps `*unchecked-math*` and
  primitive locals honest.
- **Everything else: multimethods on platform for construction, returning plain maps of
  values and fns.** These calls happen a handful of times per launch, so dispatch cost is
  irrelevant. Maps keep the Linux logic as pure, testable functions in `aether.director.proton.*`,
  which is the property that made the Rust tests possible (`launch/mod.rs:1-34`). A protocol would
  only be worth it for a long-lived, stateful launch handle (see 3.1).

### 3.1 Process spawn and injection

| | Windows (today) | Linux |
|---|---|---|
| program | `vfs-injector.exe` directly (`launch.clj:89`) | `<rt>/files/bin/wine vfs-injector.exe …` |
| argv | positional, the same (`launch.clj:89-92`) | the same; target in C: form, the rest host paths |
| process group | n/a | own group (`setsid` or `posix_spawn`, see §4) |
| liveness | the injector waits on its job tree (commit `3e28b3df`; `vfs-inject/src/tree.rs`) | injector exit, **then** `wineserver -w` (`handle.rs:258-290`) |
| stop | kill descendants (`cairn2/src/cairn/launch/run.clj:70-79`) | `wineserver -k` with `WINEPREFIX` (`wineserver.rs:26-52`); Wine's `CreateProcess` double-forks (to verify), so the game is not a JVM descendant |
| exit decode | code (3 = injector) | 2 or 3 → read `<ready>.injector-error` (`launch/mod.rs:441-456`) |

Seam: a multimethod plus a launch-handle protocol.

```clojure
(defmulti spawn! (fn [platform _session _opts] platform))
;; => a Launch
(defprotocol Launch
  (pid [l])
  (running? [l])                ; Linux: wine alive OR wineserver -w alive
  (await-exit [l])              ; blocks; returns {:exit code} | {:stopped true} | {:error msg}
  (stop! [l])                   ; idempotent; Linux: wineserver -k gated by :ended (handle.rs:76-130)
  (notes [l]))                  ; Steam-helper status, child refusals (launch/injector.rs)
```

`launch/launch` becomes `(await-exit (spawn! (platform/current) session opts))`. The Windows Launch
wraps the `Process`. The Linux Launch holds the `wine` `Process`, the lazily started `wineserver -w`
`Process`, the prefix lock and the `ended` atom. The protocol earns its place here because the object
is stateful and Cairn's UI polls and stops it.

### 3.2 Shared memory and ring transport

| | Windows | Linux |
|---|---|---|
| backing | named page-file section `Local\vfs_ring_clj_<pid>` (`Native.java:102-116`, `run.clj:201`) | real file `state/ring.bin` (symlink into `$XDG_RUNTIME_DIR/aether-vfs/ring-<h>/` when private tmpfs: `ring.rs:101-140`) |
| map | `CreateFileMappingW(INVALID_HANDLE_VALUE)` + `MapViewOfFile` | `FileChannel.map(READ_WRITE, 0, map-bytes, arena)`, file grown first, **never truncated** (`mapping.rs:30-64`) |
| wake | `_srv`/`_cli` events + 400 µs spin | none: `AdaptiveNotifier` schedule via `Thread.onSpinWait` + `LockSupport.parkNanos` |
| shim env | `VFS_RING_SECTION`, `VFS_SERVER_EV`, `VFS_CLIENT_EV` | `VFS_RING_PATH` (C: form); clear the other three |

This is not memfd or `shm_open`. A Wine process cannot open a memfd or POSIX shm object by name;
it can only open a file path. The ring must therefore be a path-named file that Wine reaches through
`drive_c` or `Z:` (`ipc.rs:228-237`).

Seam: a multimethod that returns a backing map.

```clojure
(defmulti open-backing (fn [platform _geometry _opts] platform))
;; => {:seg MemorySegment :waker Waker :close (fn []) :shim-env {"VFS_RING_…" …}
;;     :host-path "/…/state/ring.bin"  ; Linux only, for the length check
;;     :fuse-cfg {…}}                  ; what launch.clj:61-63 writes today
(defmethod open-backing :platform/windows [_ g {:keys [name]}] …)          ; today's section + EventWaker
(defmethod open-backing :platform/linux   [_ g {:keys [state-dir]}] …)     ; file + ParkWaker
```

`ring/start` (`ring.clj:545-567`) calls `open-backing`, then `init-header!` and the workers exactly as
now. `ring/stop` (`ring.clj:569-575`) calls `:close`. Windows could also use the `:file` transport
(see §5.2, a test lever), so it is worth letting `:transport :file` override the platform default.

### 3.3 Path forms

| | Windows | Linux |
|---|---|---|
| root 0 the shim sees | canonical host path of `session/root` (`launch.clj:55-56`) | a `C:\` location, default `C:\vfs-session\root`, backed by `session/root` via symlink |
| more roots | a real Windows path the program uses (`run.clj:207`) | a `C:\` location (Cairn: `C:\users\steamuser\Documents\My Games\…`), each with a host backing dir linked in |
| ring, stage, ready | host paths | ring in C: form via the linked state dir; config/ready/injector/DLL as host paths (`Z:`) |
| staged EXE | `session/stage/…`, aliased via `VFS_LAUNCH_IMAGE` (`launch.clj:115`, `run.clj:197-209`) | the same dir, but it must have a C: name: link `session/` at `C:\vfs-session` (one link covers `state/`, `stage/`, `root/`) |
| cwd | `root/<cwd>` (`launch.clj:104`) | `wine-cwd` (`mod.rs:1003-1020`) |

Seam: a plain map of pure fns, built per launch once the prefix is known.

```clojure
(defmulti path-forms (fn [platform _ctx] platform))
;; => {:program-path (fn [host-path] "C:\\…" | host-path)   ; Linux: proton.prefix/windows-path, throws outside drive_c
;;     :join         (fn [location rel] …)                  ; join-wine: refuses .. and rooted parts (mod.rs:1046)
;;     :valid-location? (fn [s] …)}                         ; parse-location (links.rs:62)
```

Windows returns identity and `io/file` join. These are the pieces that are cheapest to get
exhaustively right with unit tests.

### 3.4 Native program location

| | Windows | Linux |
|---|---|---|
| natives | `vfs-injector.exe`, `vfs_shim_dll.dll` (+ `vcruntime140.dll` in Cairn's bundle, `cairn2/build.clj:93-126`), put on `PATH` (`launch.clj:114`) | **the same two Windows files** plus a GE-Proton runtime (`<home>/runtimes/GE-Proton*`) and, for Proton prefixes, `python3` on the host |
| missing | throws per file (`launch.clj:86-88`) | list all missing at once (`mod.rs:1113-1128`); no runtime → actionable error |

Seam: a multimethod returning a map.

```clojure
(defmulti natives (fn [platform _opts] platform))
;; => {:injector f :shim f :runtime dir-or-nil :wine f :wineserver f}
```

The `PATH` prepend is Windows-only and should stay so. Under Wine the host `PATH` does not drive
DLL search, and Wine has builtin `vcruntime140`. Note also that the comment at `launch.clj:111-113`
(import-patch activation) is stale now that remote injection is forced.

### 3.5 Staging, registry and prefix setup

- **Staging:** `stage/stage` is unchanged. On Linux the staged dir must be under a linked location
  (above). On Linux, collect the proxy DLL names staged beside the EXE (`PROXY_DLL_NAMES`,
  `vfs-director/src/stage.rs:110`) and add `name=n,b` overrides. Cairn's `:also` stages a loader's
  folder DLLs (`cairn2/src/cairn/launch.clj:173-179`).
- **Registry:** unchanged on the director side. `VFS_REGISTRY=1` is the same switch. The
  difference is that the "real registry" the shim reads is the prefix's `system.reg`/`user.reg`, so
  an anonymous prefix starts empty.
- **Prefix:** Linux only. A multimethod with a no-op Windows method:
  ```clojure
  (defmulti prepare! (fn [platform _opts] platform))
  ;; Linux opts: {:home :prefix-name|nil :prefix-init :wineboot|{:proton {:steam-client :app-id}}
  ;;              :roots [{:id :location :backing}] :session-dir}
  ;; => {:runtime :prefix :lock :links [[link target]…] :cleanup (fn [])}
  ```
  It is built on pure helpers in `aether.director.proton.prefix`: `prefix-dir`, `initialised?`,
  `needs-setup?` (version/config_info), `parse-location`, `windows-path`, `link-plan`; and
  side-effecting `ensure!`, `lock!`, `link!` and `unlink!` (manifest), and `stop-wineserver!`.

### 3.6 Steam integration

| | Windows | Linux |
|---|---|---|
| app id | Cairn's game sources carry `:steam/app-id` (`cairn2/src/cairn/launch/serve.clj:94`); a streamed game run directly needs `steam_appid.txt` or `SteamAppId` (not done today) | `SteamAppId`/`SteamGameId` + `STEAM_COMPAT_CLIENT_INSTALL_PATH`, and the injector's helper via `VFS_INJECT_STEAM_HELPER` (`env.rs:121-142`) |
| steamclient | the real Steam client DLL path from the registry | Proton prefix only: `lsteamclient.dll` → host `~/.steam/sdk64/steamclient.so`; needs a **running** host client (`steam.rs:194-220`) |
| relaunch hazard | inherited `SteamAppId` makes a `steam://` launched game relaunch itself; host clears it (`launch/mod.rs:150-154`) | the same, plus the helper turns in-prefix `steam://rungameid` into a no-op (`steam.rs:42-48`) |

Seam: a multimethod returning env plus notes, with the pure parts in `aether.director.proton.steam`.

```clojure
(defmulti steam-side (fn [platform _opts] platform))
;; => {:mode :untouched|:off|:helper :env {…} :remove-env […] :notes […]}
```

The pure parts are `running-client` (with a fake `/proc` dir), `helper-report->status` and
`helper-note`.

### 3.7 Environment building

This is not in your list, but it is the largest single piece of pure logic. `aether.director.proton.env/launch-env`
should be a direct port of `env.rs:54-168`, `merge-dll-overrides` (`:213-238`), `reserved?`
(`:191-196`), `stale` (`:245`) and `runtime-lib-env` (`runtime.rs:109-139`). Windows keeps
`launch/session`'s `:env` (`launch.clj:66-76`). Both need the same rule for clearing stale `VFS_*`
names in the child. The handshake lists in `vfs-env` (`handshake::TRANSPORT`/`INJECT`) should be
mirrored once in `proto.clj` or a new `env.clj`. Today `launch.clj:101-102` removes only two names.

---

## 4. JVM-side native access the Linux port needs

| Need | JVM answer | Native? |
|---|---|---|
| Shared file mapping | `FileChannel.open(p, READ, WRITE, CREATE)`; grow with `RandomAccessFile.setLength` only if shorter; `.map(MapMode.READ_WRITE, 0, n, Arena.ofShared())` → `MemorySegment` (MAP_SHARED, page-aligned, so the `JAVA_INT` VarHandle aligned atomics in `Native.java:29-52` work) | no |
| Coherence with Wine's mapping | the same inode, both `MAP_SHARED`: measured coherent (`2026-09-01-wine-hosted-shim-design.md:46-57`) | no |
| Truncation fault | the JVM turns SIGBUS on a mapped segment into `InternalError` rather than dying; still keep grow-only and unlink-not-truncate (`mod.rs:321-327`) | no |
| Waking | `Thread.onSpinWait` + `LockSupport.parkNanos(100_000…2_000_000)` (`notifier.rs:99-125`) | no |
| Private ring dir checks | `/proc/self/mountinfo` text; `Files.getAttribute(p, "unix:uid")` vs owner of `/proc/self`; `PosixFilePermissions` 0700/0600; `CREATE_NEW` + `NOFOLLOW_LINKS` | no |
| `posix_fallocate` (reserve tmpfs pages) | not available (`FileChannel` has no fallocate); Rust skips it too (`ring.rs:88-93`) | optional FFM `posix_fallocate` via `Linker.nativeLinker().defaultLookup()` |
| Symlinks, readlink | `Files.createSymbolicLink`, `Files.readSymbolicLink`, `LinkOption/NOFOLLOW_LINKS` | no |
| Prefix lock | `FileChannel.tryLock()` | no, but it is **`fcntl`, not `flock`**: it does not exclude a Rust director using `flock` on the same file (independent lock types on Linux), and a second `tryLock` in one JVM throws `OverlappingFileLockException`; keep an in-JVM registry too |
| fork/exec | `ProcessBuilder` (posix_spawn via `jspawnhelper`); explicit env (`.environment()` clear/put); `redirectOutput/Error` to one `File` with `Redirect.appendTo` | no |
| Env case-insensitivity | `ProcessBuilder.environment()` is case-sensitive on Linux: remove every case-variant spelling (`launch/mod.rs:412-428`) | no |
| Own process group (`process_group(0)`, `process.rs:47-55`) | **not available in `ProcessBuilder`**. Options: (1) prefix argv with `setsid` (util-linux; also drops the controlling TTY); (2) FFM `posix_spawn` with `POSIX_SPAWN_SETPGROUP`, which loses `Process`, so pidfd/`waitpid` would have to be done by hand; (3) accept the risk: Cairn is a GUI and Ctrl-C reaches only the CLI. `setpgid` after `start()` races the exec. | (1) none; (2) FFM |
| ETXTBSY retry | catch `IOException` whose message has `error=26` and retry (`process.rs:65-79`) | no |
| Signals | `ProcessHandle.destroy` (SIGTERM) and `destroyForcibly` (SIGKILL); process-group signals (`kill(-pgid)`) need FFM `kill`. In practice `wineserver -k` replaces signals | optional FFM |
| Seeing double-forked Wine children | `ProcessHandle.descendants()` misses them if Wine double-forks; FFM `prctl(PR_SET_CHILD_SUBREAPER, 1)` makes the JVM their reaper so `descendants()` works | optional FFM |
| Steam client check | read `~/.steam/steam.pid`, `/proc/<pid>/comm` | no |
| Proton download | `java.net.http` (or `source/net.clj`), `MessageDigest "SHA-512"`, `GZIPInputStream`; **no tar in the JDK**: either commons-compress (symlinks, modes) or `tar -xzf` (always present on Linux), then the `verify-ge` gate and rename | no (a dependency or the `tar` binary) |
| Exec bits after extraction | `Files.setPosixFilePermissions` (if extracting in-JVM) | no |

**Nothing the JVM fundamentally cannot do.** The one real gap is process-group creation. The other
FFM extras (fallocate, kill(-pgid), subreaper) are conveniences. All of them are libc calls through
`Linker.nativeLinker().defaultLookup()`. That fits in a `LinuxNative.java` next to `Native.java`,
with lazy holder classes as now, so the class still loads on Windows. No C helper is required.

---

## 5. Porting plan

### 5.1 Ordered tasks

| # | Task | New/changed Clojure | Size | Test on this Windows box? |
|---|---|---|---|---|
| 1 | `platform` ns; `Waker` interface; `EventWaker` (today's behaviour) and `ParkWaker`; refactor `Ring`, `worker` and `serve-slot` to use them | `ring.clj`, `Waker.java`, `Native.java` | S (~120) | yes: the existing `ring_test` must stay green |
| 2 | `open-backing` multimethod: `:section` (today) and `:file` (FileChannel map, grow-only, unlink first); `ring/start :transport` option; `fuse.cfg`/env from the backing | `ring.clj`, new `ring/backing.clj` | S–M (~150) | **yes, end to end**: the Windows shim supports `VFS_RING_PATH` (`vfs-win/src/mapping.rs:133`, `director.rs:379`). Run a real Windows launch over a file ring with `ParkWaker` to prove the Linux transport and its latency |
| 3 | Handshake env as data: the transport, injector and reserved lists from `vfs-env`; `stale` clearing; case-insensitive removal | new `env.clj` | S (~80) | yes |
| 4 | `proton.runtime`: `verify-ge`, `check-ffmpeg`, `cmp-tags`, `installed-dirs`, `newest`, `runtime-lib-env`; `proton.layout` (`Root`, traversal guard) | new | S (~150) | yes (fixture dirs with `version` files) |
| 5 | `proton.env`: `launch-env`, `merge-dll-overrides`, `reserved?`, the proxy-DLL overrides, the DXVK constant | new | M (~200) | yes (port the Rust tests in `env.rs:249-610`) |
| 6 | `proton.prefix` pure: `prefix-dir`, `initialised?`, `needs-setup?`, `parse-location`, `windows-path`, `join-wine`, `wine-cwd`, `link-plan` (ordering, case-insensitive matching against a listing) | new | M (~200) | yes for string logic; symlinks need Developer Mode or admin on Windows, so test link creation on Linux |
| 7 | `proton.prefix` effects: `ensure!` (wineboot / `proton run`), `lock!`, `link!`/`unlink!` + manifest, `stop-wineserver!`, `wineserver-wait` | new | M (~250) | Linux (fake `proton` shell script, as in `init.rs:286-310`) |
| 8 | `proton.ring-home`: mountinfo parse, private-dir check, `ring-in-memory`, remove | new | S (~100) | parse yes; ownership and permissions on Linux |
| 9 | `proton.steam`: state dir, `running-client` (fake `/proc`), helper report parse and notes, `steam-side` | new | S (~120) | yes (fixtures) |
| 10 | `proton.hostenv`: AppImage `$APPDIR` cleaning | new | S (~60) | yes |
| 11 | `proton.launch`: `command-line`, `check-geometry`, the `spawn!` Linux method (setsid, log file, notes, ETXTBSY), `finish` (injector-error decode), the `Launch` record (wineserver-wait liveness, gated stop) | new + `launch.clj` | M–L (~300) | argv, geometry and finish decoding yes; the rest on Linux |
| 12 | `launch/session` and `launch/launch` become platform-dispatching; `run/launch!` passes `:platform` and the Proton opts, links `session/` at `C:\vfs-session`, and returns or holds a `Launch` (needed for Cairn's Stop) | `launch.clj`, `run.clj` | M (~150) | Windows path yes (no regression) |
| 13 | `proton.release` + `proton.install` (GitHub, sha512, tar, gate, rename) + a CLI `-m aether.director.proton install/list/path` | new | M (~300) | parse, digest, pick and traversal guard yes; real download and extract on Linux |
| 14 | NVAPI (`detect`/`install`/`env`) | new | M (~300) | GPU detection fixtures yes; real on an NVIDIA Linux box. **Defer**: optional per launch |
| 15 | End-to-end: a fixture exe (`vfs-fixture-read.exe`), then a Steam game (`vfs-fixture-steam.exe`), then Skyrim+SKSE under GE-Proton | tests | M | Linux + Steam |

Total: about 2.0–2.5k lines with tests, or about 1.5k without NVAPI. Tasks 1–6, 8–10 and 13 (parse
half) are pure or Windows-runnable: roughly 60% of the work, done before touching a Linux box. Task 2
is the one that de-risks the transport early.

### 5.2 Windows box vs Linux box

**Testable here (Windows):**

- ring refactor regression (`clj/test/aether/director/ring_test.clj` creates real sections);
- the **file-backed transport against the real Windows shim and injector**, which is the
  Linux code path minus Wine;
- geometry and length checks;
- env building, override merging, reserved and stale lists;
- `parse-location`, `windows-path` and `join-wine` (string-based: avoid `java.nio.file.Path` for
  Linux-shaped fixtures, because `/srv/x` is not absolute on Windows; the Rust tests hit the same
  issue, `launch/mod.rs:563-572`);
- GE discovery from fixture `runtimes/` dirs;
- tag ordering, release JSON parse, sha512sum parse;
- mountinfo parse, Steam report parse, fake `/proc` `comm`;
- AppImage env cleaning;
- injector-error decoding.

**Needs Linux (WSL2 Arch was enough for the original measurements,
`2026-09-01-wine-hosted-shim-design.md:16-18`):**

- symlinks into `drive_c`;
- POSIX permissions and uid checks;
- `flock` vs `fcntl` behaviour;
- `wineboot` and `proton run` prefix creation;
- the `wine`/`wineserver` lifecycle, stop and quiet detection;
- ETXTBSY;
- `setsid`;
- the real GE-Proton download and extract;
- 32-bit loader detection;
- the shim attaching across Wine.

**Needs Linux + a running Steam client:** the helper and `SteamAPI_IsSteamRunning`, `lsteamclient`,
and a DRM'd game (`SkyrimSE.exe` SteamStub). **Needs an NVIDIA GPU:** NVAPI/NGX.

### 5.3 How Cairn selects the platform

- **Detection:** the director decides by default (`aether.director.platform/current`). Cairn
  passes `:platform` only to override it, for tests.
- **`cairn.launch.run/natives`** (`cairn2/src/cairn/launch/run.clj:14-21`): on both platforms it
  points at a folder holding `vfs-injector.exe` and `vfs_shim_dll.dll`. On Linux, add `:proton
  {:home … :prefix-name (str "cairn-" loadout-id) :prefix-init {:proton {:steam-client
  "~/.local/share/Steam" :app-id app}}}` resolved from `AETHER_VFS_HOME`/XDG. Use a **named**
  prefix per loadout or game, so the Proton setup (DXVK, redistributables) and the game's registry
  survive between runs. The anonymous per-session prefix would rerun `proton run` every launch.
- **Roots and locations** (`cairn2/src/cairn/game/locations.clj:110-120`, `cairn.launch/roots`
  `cairn2/src/cairn/launch.clj:106-135`):
  - On Linux, `host-roots` must return **program-side** paths, for example `:os/documents
    "C:\\users\\steamuser\\Documents"`, `:os/local-app-data
    "C:\\users\\steamuser\\AppData\\Local"`, and `:store/install-dir` set to a C: location such as
    `C:\vfs-session\root` (or `C:\Games\<game>`). Proton prefixes use the user `steamuser`; wineboot
    prefixes use `$USER`.
  - `resolve-locations` currently calls `fs/absolutize`/`fs/normalize` (`locations.clj:70-88`),
    which would turn `C:\…` into `<cwd>/C:\…` on Linux. It needs a Windows-string join when the
    platform is Linux.
  - The director then gives every non-0 root a host backing dir (`session/roots/<id>`) and links it
    at the location.
- **Plan targets** (`cairn2/src/cairn/launch.clj:145-180`) stay platform-neutral. `:target/exe` is
  still a game path. Two additions are useful:
  - `:target/env` (for example `WINEDLLOVERRIDES` for a mod's `dinput8`/`version` proxy, or
    `PROTON_GRAPHICS_OVERRIDES`), possibly per platform: `:target/env {:platform/linux {…}}`;
  - Steam: pass the plan's `:steam/app-id` through `:env {"SteamAppId" …}` or the Proton
    `:app-id`, so the helper starts.
- **Stopping:** `cairn.launch.run/stop!` kills JVM descendants (`run.clj:70-110`), which does not
  reach Wine processes that double-forked away. On Linux it must call the director's `Launch`
  `stop!` (`wineserver -k`). That means `run/launch!` should hand back the `Launch`, or accept a
  `:on-launch` callback, instead of only returning the exit code (`aether.director.run` `:185-219`).
- **Cairn's own Windows-only bits:** `org.openjfx/javafx-*$win` classifiers (`cairn2/deps.edn`) and
  the Windows bundle (`cairn2/build.clj:93-126`) are Cairn's problem, not the director's. They
  block the Cairn UI on Linux, not `cairn run`.

### 5.4 Cairn's pinned dependency

`cairn2/deps.edn:26-28` pins `io.github.halgari/aether-vfs {:git/url …aether-vfs.git :git/sha
"09f8953512fea041615992e3cfe2d284c7938600" :deps/root "clj"}`. To pick up the port:

1. Land the port on `clj-director` and push it to `halgari/aether-vfs`.
2. Bump `:git/sha` to that commit. `:deps/root "clj"` is unchanged.
3. On the Linux machine, run `clojure -X:deps prep` once per sha. `:deps/prep-lib`
   (`clj/deps.edn:3`) compiles `java/` with `--release 26`, so the Linux box needs **JDK 26+**. The
   new `Waker.java` and `LinuxNative.java` must not touch kernel32 or libc at class init (keep the
   lazy holders, `Native.java:55-58`).
4. Keep `--enable-native-access=ALL-UNNAMED`, which is already in Cairn's `:run` alias. Cairn also
   needs the GE runtime in place (via the director's `install` CLI) and the two Windows natives on
   Linux. They are the same files Cairn's Windows bundle ships, so a Linux bundle can copy them from
   a Windows release build, with no cargo-xwin needed.
5. If the port adds a dependency (commons-compress for tar), it comes in transitively through the
   git dep. Shelling out to `tar` avoids adding one.

---

## 6. Risks and unknowns

1. **The file ring's idle cost and latency from the JVM.** Rust's `AdaptiveNotifier` was tuned with
   4 workers. The Clojure director defaults to **16** (`ring.clj:546`), so at the 2 ms idle cap
   that is about 8,000 wakeups a second while idle. Measure it, and consider fewer workers on Linux.
   `parkNanos` granularity on Linux is about 50–100 µs, which is fine. The shim side spins and then
   sleeps 1 ms (`director.rs:231-245`), whichever director serves it.
2. **The identity gap under Wine.** `GetFinalPathNameByHandleW` returns the backing path
   (`2026-09-01-wine-hosted-shim-design.md:238-253`). `shim-invariants.md:828-840` records part of
   the fix. Whether every case is closed for real games is a shim question, not a director one, but
   it will surface during the Clojure port's end-to-end tests.
3. **The injector's job-object tree wait under Wine.** Commit `3e28b3df` made `vfs-injector`
   wait on its job tree. The 10-13 design kept Wine on "first process plus prefix watch"
   (`2026-10-13-windows-process-tree-wait-design.md:76-78`). Check what the current injector does
   under Wine (`tree.untracked_reason`), and still use `wineserver -w` for liveness.
4. **Wine process parentage.** The claim that Wine's `CreateProcess` double-forks, so the game is
   not a JVM descendant, is from memory of Wine's `ntdll/unix/process.c`. Verify it. It decides
   whether Cairn's descendant-kill can work at all on Linux. It is moot if stop is
   `wineserver -k`.
5. **Locks.** Java `FileLock` is `fcntl`, while Rust's is `flock`. A Rust and a Clojure director
   on one prefix would not exclude each other. They are also per-process in Java. Document a named
   prefix as single-director, or add an FFM `flock`.
6. **Process groups.** Without `setsid` or FFM, Ctrl-C in `cairn run` reaches `wine` and the
   `proton` Python script directly. An interrupted `proton run` leaves a half-built prefix
   (`process.rs:38-46`).
7. **Prefix contents vs root locations.** A Proton prefix has real `drive_c/users/steamuser/Documents`
   and so on. `link_location` refuses a real directory at the leaf (`links.rs:143-151`), so a root
   at `…\Documents\My Games\Skyrim Special Edition` works only while that leaf does not already
   exist as a real directory. A game run in that prefix outside the VFS would create it. It is also
   unverified whether non-0 roots need a linked backing dir at all, since the shim intercepts before
   Wine touches the disk. Rust links them, so the port should too, but confirm the reason.
8. **`Z:` stays mapped to `/`.** Host paths reach Windows code through it, for example config,
   ready and the shim DLL argv (`launch/mod.rs:90-96`). Removing it, as the 09-01 spec once planned,
   would break the launch as currently written. Leaving it is a containment gap, not a correctness
   one.
9. **Proxy DLLs.** Wine's builtin `dinput8`, `version`, `d3d11`, `dxgi`, `winmm`, `winhttp` and so
   on win unless overridden `n,b`. SKSE does not need this; ENB, ReShade and Address-Library-style
   proxies do. The Clojure stage does not collect proxies today (`stage.clj` walks imports only), so
   a `staged-proxies` equivalent is needed.
10. **DXVK.** A direct `wine` launch in a Proton prefix does not get Proton's per-launch overrides.
    Without `PROTON_GRAPHICS_OVERRIDES` (`init.rs:68-69`) a D3D11 game runs on wined3d, which is
    slow or broken.
11. **Steam.** The helper path needs a running host client and a Proton-initialized prefix. A
    streamed game (Cairn's model: the game is never installed) has no `steamapps/appmanifest`, so
    whether `lsteamclient` and the client accept an app that is not installed locally is
    **unknown**. It worked in Rust's tests with real installs (`vfs-proton/tests/steam_client.rs`,
    `vfs-embed/tests/proton_steam.rs`).
12. **Runtime drift.** The Rust observations are pinned to GE-Proton11-6/11-7: the 18-of-20 ntdll
    exports, `version` format, `config_info` contents and the NVAPI script steps. A new GE-Proton
    can move any of them. The `verify_ge` gate catches only the "not GE" case.
13. **Distro breadth.** Running Wine directly, with no Steam Linux Runtime container, depends on
    host libraries, including 32-bit glibc (`init.rs:274-276`). umu and pressure-vessel are
    explicitly deferred, and would need injection re-proven inside the container
    (`2026-09-01-wine-hosted-shim-design.md:327-329`).
14. **tmpfs quota.** The ring file is sparse. A nearly full `$XDG_RUNTIME_DIR` can fault on page
    touch (`ring.rs:88-93`). In the JVM this surfaces as `InternalError` in a worker, which needs a
    handler (`Guard` covers dispatch, but not necessarily the CAS and state writes).
15. **Path fidelity.** A ring path, root or cwd containing non-ASCII or very long components goes
    through Wine's Unix→DOS conversion. This is untested from the Clojure side.
