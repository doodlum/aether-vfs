//! Stopping a launch whose host died: [`HostWatch`].
//!
//! A Wine launch outlives the process that started it. `wine` runs in a
//! process group of its own (so a terminal's Ctrl-C does not tear a prefix
//! down mid-step, see `crate::process`), every Windows process it starts is
//! a Unix process Wine forks off on its own, and `wineserver` is a daemon.
//! When the host is killed (`SIGKILL`, the OOM killer, a crash) none of them
//! is told, and the game runs on without the VFS server it reads through.
//! On Windows the launch's Job Object, killed when its last handle closes,
//! prevents that; this is the Unix counterpart.
//!
//! # The mechanism
//!
//! A small watcher process — `/bin/sh` running [`WATCHER`] — is started with
//! the read end of a pipe as its stdin; the host keeps the write end, and
//! writes nothing to it. The write end is close-on-exec, so no program the
//! host starts holds a copy: it closes exactly when the host process is gone,
//! however that happens, and the watcher's `read` returns. The watcher then
//! stops the launch:
//!
//! - a launch with its prefix to itself: `wineserver -k` in the prefix, which
//!   ends every Wine process there — what [`Prefix::stop_wineserver`] does;
//! - a launch in a **shared** prefix ([`LaunchProcesses`]), whose
//!   `wineserver` is not the launch's to stop: `SIGTERM`, then `SIGKILL` two
//!   seconds later, to the pids of the launch's own processes, which the host
//!   keeps current in a file once a second ([`PID_REFRESH`]), and to `wine`'s
//!   process group while `wine` is unreaped, which reaches a process started
//!   since the last refresh unless Wine gave it a session of its own.
//!
//! The watcher runs in a process group of its own, ignoring `SIGHUP` and
//! `SIGINT`, so whatever takes down the host's group does not take it too.
//! When the launch ends normally the host disarms the watch
//! ([`HostWatch::disarm`]): it kills and reaps the watcher, which until then
//! has done nothing but wait.
//!
//! The watcher also holds the prefix's launch lock (a second descriptor for
//! the same open file, as its stdout), so after the host dies the prefix
//! stays locked until the watcher has stopped what ran there: a host
//! restarted at once cannot start a launch in the prefix and see it killed.
//!
//! # Why not `PR_SET_PDEATHSIG`
//!
//! Linux's parent-death signal fires when the **thread** that started the
//! child exits, not the process: a host that launches from a worker thread
//! that later ends (any thread pool) would have its game killed while it is
//! alive and well. It is not inherited across `fork`, and Wine double-forks
//! every Windows process it starts, so it would reach `wine` and the injector
//! but never the game. And macOS has no such signal (its counterpart,
//! `kqueue`'s `NOTE_EXIT`, needs a process to wait on it — which is what the
//! watcher is). A pipe closes when the process is gone, on both systems.

use std::io;
use std::path::{Path, PathBuf};
use std::process::{Child, Command, Stdio};
use std::sync::atomic::{AtomicBool, Ordering};
use std::sync::Arc;
use std::time::Duration;

use super::LaunchProcesses;
use crate::host::WineHost;
use crate::prefix::{Prefix, PrefixLock};

/// The watcher: waits for its stdin to close, then stops the launch. `$1` is
/// `prefix` (then `$2` is `wineserver`, run with the inherited `WINEPREFIX`)
/// or `pids` (then `$2` is the pid file).
pub const WATCHER: &str = r#"trap '' HUP INT
read -r _
case "$1" in
  prefix)
    "$2" -k </dev/null >/dev/null 2>&1 ;;
  pids)
    pids=$(cat "$2" 2>/dev/null)
    [ -n "$pids" ] || exit 0
    kill -TERM -- $pids </dev/null >/dev/null 2>&1
    i=0
    while [ "$i" -lt 20 ]; do
      left=
      for p in $pids; do
        kill -0 -- "$p" 2>/dev/null && left="$left $p"
      done
      [ -n "$left" ] || exit 0
      pids=$left
      i=$((i + 1))
      sleep 0.1
    done
    kill -KILL -- $pids </dev/null >/dev/null 2>&1 ;;
esac
"#;

/// How often a shared-prefix launch's pid file is brought up to date.
pub const PID_REFRESH: Duration = Duration::from_secs(1);

/// How long [`HostWatch::disarm`] waits for the killed watcher to be reaped.
const REAP_WAIT: Duration = Duration::from_secs(5);

/// Stops a launch if the host process dies before it ends it: see the
/// module docs. Disarmed by [`HostWatch::disarm`] or by dropping it — both of
/// which only happen while the host is alive, and so only when the host is
/// ending the launch itself.
#[derive(Debug)]
pub struct HostWatch {
    watcher: Option<Child>,
    /// The write end of the watcher's stdin. Never written; held until the
    /// watcher is gone.
    pipe: Option<io::PipeWriter>,
    /// Shared prefix: the pid file and the thread keeping it current.
    pids: Option<PidFile>,
}

#[derive(Debug)]
struct PidFile {
    path: PathBuf,
    /// Set once the host has reaped `wine`, whose pid may then be reused.
    wine_reaped: Arc<AtomicBool>,
    stop: Arc<AtomicBool>,
    refresher: Option<std::thread::JoinHandle<()>>,
}

impl HostWatch {
    /// A watch for a launch that has `prefix` to itself: if the host dies, the
    /// prefix's `wineserver` (`host`'s own) is stopped with `-k`. Start it
    /// before `wine`, so no moment of the launch is unwatched. `lock` is the
    /// prefix's launch lock, which the watcher then holds too.
    pub fn prefix(host: &WineHost, prefix: &Prefix, lock: Option<&PrefixLock>) -> io::Result<Self> {
        let mut cmd = watcher_command(lock)?;
        cmd.arg("prefix")
            .arg(host.wineserver())
            .env("WINEPREFIX", &prefix.dir);
        Self::start(cmd, None)
    }

    /// A watch for a launch in a shared prefix: if the host dies, `wine_pid`
    /// (until [`HostWatch::wine_reaped`]) and the processes `procs` finds are
    /// signalled, from `pid_file`, which this keeps current every
    /// [`PID_REFRESH`] and removes when disarmed.
    pub fn processes(
        procs: LaunchProcesses,
        wine_pid: u32,
        pid_file: PathBuf,
        lock: Option<&PrefixLock>,
    ) -> io::Result<Self> {
        let wine_reaped = Arc::new(AtomicBool::new(false));
        write_pids(&pid_file, &procs, wine_pid, &wine_reaped)?;
        let mut cmd = watcher_command(lock)?;
        cmd.arg("pids").arg(&pid_file);
        let stop = Arc::new(AtomicBool::new(false));
        let refresher = {
            let (path, reaped, stop) = (pid_file.clone(), wine_reaped.clone(), stop.clone());
            std::thread::Builder::new()
                .name("aether-launch-pids".into())
                .spawn(move || {
                    while !stop.load(Ordering::SeqCst) {
                        std::thread::park_timeout(PID_REFRESH);
                        if stop.load(Ordering::SeqCst) {
                            break;
                        }
                        let _ = write_pids(&path, &procs, wine_pid, &reaped);
                    }
                })?
        };
        let pids = PidFile {
            path: pid_file,
            wine_reaped,
            stop,
            refresher: Some(refresher),
        };
        Self::start(cmd, Some(pids))
    }

    fn start(mut cmd: Command, pids: Option<PidFile>) -> io::Result<Self> {
        let (reader, writer) = io::pipe()?;
        cmd.stdin(reader);
        crate::process::own_process_group(&mut cmd);
        let mut watch = HostWatch {
            watcher: None,
            pipe: Some(writer),
            pids,
        };
        // On error `watch` drops here, which stops the refresher.
        watch.watcher = Some(cmd.spawn()?);
        // `cmd` still holds the read end; drop it so the watcher's is the only one.
        drop(cmd);
        Ok(watch)
    }

    /// The host has reaped `wine`: its pid may be reused from now on, so it
    /// leaves the pid file. Nothing for a prefix watch.
    pub fn wine_reaped(&self) {
        if let Some(p) = &self.pids {
            p.wine_reaped.store(true, Ordering::SeqCst);
        }
    }

    /// The launch has ended (or is being stopped by the host): kills and
    /// reaps the watcher, which has done nothing yet, and stops keeping the
    /// pid file. Idempotent.
    pub fn disarm(&mut self) {
        if let Some(mut w) = self.watcher.take() {
            let _ = w.kill();
            let deadline = std::time::Instant::now() + REAP_WAIT;
            while matches!(w.try_wait(), Ok(None)) && std::time::Instant::now() < deadline {
                std::thread::sleep(Duration::from_millis(5));
            }
        }
        // After the watcher: it must never see the pipe close while alive.
        self.pipe = None;
        if let Some(mut p) = self.pids.take() {
            p.stop.store(true, Ordering::SeqCst);
            if let Some(t) = p.refresher.take() {
                t.thread().unpark();
                let _ = t.join();
            }
            let _ = std::fs::remove_file(&p.path);
        }
    }

    /// Whether the watcher is still waiting: false once it has been disarmed,
    /// or if it has exited.
    pub fn is_armed(&mut self) -> bool {
        self.watcher
            .as_mut()
            .is_some_and(|w| matches!(w.try_wait(), Ok(None)))
    }

    /// The host's death, short of dying: closes the pipe without disarming,
    /// so the watcher acts, and hands it back to be waited for. Like a real
    /// death it leaves the pid file in place.
    #[cfg(test)]
    fn host_gone(mut self) -> Child {
        if let Some(mut p) = self.pids.take() {
            p.stop.store(true, Ordering::SeqCst);
            if let Some(t) = p.refresher.take() {
                t.thread().unpark();
                let _ = t.join();
            }
        }
        self.pipe = None;
        self.watcher.take().expect("armed")
    }
}

impl Drop for HostWatch {
    fn drop(&mut self) {
        self.disarm();
    }
}

/// `/bin/sh -c WATCHER watcher …`, its stdout the prefix's lock (when given)
/// and its stderr discarded.
fn watcher_command(lock: Option<&PrefixLock>) -> io::Result<Command> {
    let mut cmd = Command::new("/bin/sh");
    // `$0` is "aether-launch-watch", so `ps` says what it is.
    cmd.args(["-c", WATCHER, "aether-launch-watch"])
        .stderr(Stdio::null());
    match lock {
        Some(l) => cmd.stdout(l.file().try_clone()?),
        None => cmd.stdout(Stdio::null()),
    };
    Ok(cmd)
}

/// Writes the pids a shared-prefix launch's watcher would signal now, whole:
/// written beside and renamed over `path`, so the watcher never reads half.
fn write_pids(
    path: &Path,
    procs: &LaunchProcesses,
    wine_pid: u32,
    wine_reaped: &AtomicBool,
) -> io::Result<()> {
    let mut text: String = procs.running().iter().map(|p| format!("{p}\n")).collect();
    if !wine_reaped.load(Ordering::SeqCst) {
        // `wine` and its process group (it leads one: `own_process_group`),
        // which holds what it started that Wine did not move to a session of
        // its own — so a game started since the last refresh is reached
        // too. Only while `wine` is unreaped: until then its pid, and so the
        // group's id, cannot be reused.
        if !text.lines().any(|l| l == wine_pid.to_string()) {
            text.push_str(&format!("{wine_pid}\n"));
        }
        text.push_str(&format!("-{wine_pid}\n"));
    }
    let tmp = path.with_extension("tmp");
    std::fs::write(&tmp, text)?;
    std::fs::rename(&tmp, path)
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::os::unix::fs::PermissionsExt;
    use std::time::Instant;

    fn scratch(tag: &str) -> PathBuf {
        let d = crate::test_tmp::dir().join(format!("vfs-watch-{}-{tag}", std::process::id()));
        let _ = std::fs::remove_dir_all(&d);
        std::fs::create_dir_all(&d).unwrap();
        d
    }

    /// A Wine install whose `wineserver -k` records its `WINEPREFIX` in
    /// `<prefix>/killed`, after `delay` seconds.
    fn fake_host(dir: &Path, delay: &str) -> WineHost {
        let bin = dir.join("files").join("bin");
        std::fs::create_dir_all(&bin).unwrap();
        let tmp = bin.join("wineserver.tmp");
        std::fs::write(
            &tmp,
            format!(
                "#!/bin/sh\nsleep {delay}\n[ \"$1\" = -k ] && echo \"$WINEPREFIX\" >> \"$WINEPREFIX/killed\"\nexit 0\n"
            ),
        )
        .unwrap();
        std::fs::set_permissions(&tmp, std::fs::Permissions::from_mode(0o755)).unwrap();
        std::fs::rename(&tmp, bin.join("wineserver")).unwrap();
        WineHost::Proton(dir.to_path_buf())
    }

    fn wait_exit(mut c: Child) {
        wait_child(&mut c);
    }

    /// Waits for `c` to exit, failing (not hanging) after ten seconds.
    fn wait_child(c: &mut Child) {
        let deadline = Instant::now() + Duration::from_secs(10);
        while c.try_wait().unwrap().is_none() {
            assert!(Instant::now() < deadline, "pid {} never exited", c.id());
            std::thread::sleep(Duration::from_millis(10));
        }
    }

    /// Kills what a test started if it fails before reaping it.
    struct KillOnDrop(Vec<u32>);

    impl Drop for KillOnDrop {
        fn drop(&mut self) {
            for &p in &self.0 {
                vfs_unix::signal(p, vfs_unix::Signal::Kill);
            }
        }
    }

    #[test]
    fn a_disarmed_watch_does_nothing() {
        let dir = scratch("disarm");
        let host = fake_host(&dir.join("rt"), "0");
        let prefix = Prefix {
            dir: dir.join("pfx"),
        };
        std::fs::create_dir_all(&prefix.dir).unwrap();
        let mut w = HostWatch::prefix(&host, &prefix, None).unwrap();
        assert!(w.is_armed());
        w.disarm();
        assert!(!w.is_armed());
        w.disarm();
        drop(w);
        std::thread::sleep(Duration::from_millis(300));
        assert!(!prefix.dir.join("killed").exists());
    }

    #[test]
    fn when_the_host_is_gone_the_prefix_is_stopped() {
        let dir = scratch("gone");
        let host = fake_host(&dir.join("rt"), "0");
        let prefix = Prefix {
            dir: dir.join("pfx"),
        };
        std::fs::create_dir_all(&prefix.dir).unwrap();
        let w = HostWatch::prefix(&host, &prefix, None).unwrap();
        std::thread::sleep(Duration::from_millis(100));
        assert!(!prefix.dir.join("killed").exists(), "nothing before");
        wait_exit(w.host_gone());
        assert_eq!(
            std::fs::read_to_string(prefix.dir.join("killed")).unwrap(),
            format!("{}\n", prefix.dir.display()),
            "wineserver -k ran in this prefix"
        );
    }

    /// The prefix stays locked until the watcher has stopped it, so nothing
    /// new starts there in between.
    #[test]
    fn the_watcher_holds_the_prefix_lock_until_it_has_stopped_the_prefix() {
        let dir = scratch("lock");
        let host = fake_host(&dir.join("rt"), "2");
        let prefix = Prefix {
            dir: dir.join("pfx"),
        };
        let lock = prefix.lock().unwrap();
        let w = HostWatch::prefix(&host, &prefix, Some(&lock)).unwrap();
        drop(lock);
        let watcher = w.host_gone();
        assert!(
            matches!(prefix.lock(), Err(crate::prefix::PrefixError::Busy(_))),
            "still locked while wineserver -k runs"
        );
        wait_exit(watcher);
        assert!(prefix.dir.join("killed").exists());
        prefix.lock().expect("free once the watcher is done");
    }

    #[test]
    fn a_disarmed_watch_releases_the_prefix_lock() {
        let dir = scratch("lock-disarm");
        let host = fake_host(&dir.join("rt"), "0");
        let prefix = Prefix {
            dir: dir.join("pfx"),
        };
        let lock = prefix.lock().unwrap();
        let mut w = HostWatch::prefix(&host, &prefix, Some(&lock)).unwrap();
        w.disarm();
        drop(lock);
        prefix.lock().expect("free");
        assert!(!prefix.dir.join("killed").exists());
    }

    #[cfg(any(target_os = "linux", target_os = "macos"))]
    #[test]
    fn when_the_host_is_gone_a_shared_launchs_processes_are_signalled() {
        let dir = std::fs::canonicalize(scratch("pids")).unwrap();
        // The game, as Wine shows one: its image path as argv[0], working in
        // the session's directory. It ends on SIGTERM.
        let mut game = Command::new("bash")
            .args(["-c", r#"exec -a "C:\Games\X\game.exe" sleep 60"#])
            .current_dir(&dir)
            .spawn()
            .unwrap();
        // `wine`, which here ignores SIGTERM and needs the SIGKILL.
        let mut wine = Command::new("sh")
            .args(["-c", "trap '' TERM; while :; do sleep 0.05; done"])
            .spawn()
            .unwrap();
        // Not the launch's: the same image elsewhere.
        let mut other = Command::new("bash")
            .args(["-c", r#"exec -a "C:\Games\X\game.exe" sleep 60"#])
            .current_dir(scratch("pids-other"))
            .spawn()
            .unwrap();
        let _guard = KillOnDrop(vec![game.id(), wine.id(), other.id()]);
        let procs = LaunchProcesses::new(vec![r"C:\Games\X".into()], vec![dir.clone()]);
        let pid_file = dir.join("launch.pids");
        let w = HostWatch::processes(procs, wine.id(), pid_file.clone(), None).unwrap();
        // The game may not have exec'd at the first write; the refresher
        // finds it within a refresh or two.
        let want = format!("{}\n{}\n-{}\n", game.id(), wine.id(), wine.id());
        let deadline = Instant::now() + PID_REFRESH * 5;
        while std::fs::read_to_string(&pid_file).unwrap() != want {
            assert!(
                Instant::now() < deadline,
                "{:?}",
                std::fs::read_to_string(&pid_file)
            );
            std::thread::sleep(Duration::from_millis(50));
        }
        let t = Instant::now();
        wait_exit(w.host_gone());
        assert!(t.elapsed() < Duration::from_secs(8), "{:?}", t.elapsed());
        wait_child(&mut game);
        wait_child(&mut wine);
        assert!(
            other.try_wait().unwrap().is_none(),
            "a process that is not the launch's is left alone"
        );
        let _ = other.kill();
        let _ = other.wait();
    }

    #[cfg(any(target_os = "linux", target_os = "macos"))]
    #[test]
    fn a_reaped_wine_leaves_the_pid_file_and_disarming_removes_it() {
        let dir = scratch("pids-reaped");
        let procs = LaunchProcesses::new(vec![r"C:\nowhere".into()], vec![dir.clone()]);
        let pid_file = dir.join("launch.pids");
        let mut w = HostWatch::processes(procs, 4_000_000, pid_file.clone(), None).unwrap();
        assert_eq!(
            std::fs::read_to_string(&pid_file).unwrap(),
            "4000000\n-4000000\n"
        );
        w.wine_reaped();
        let deadline = Instant::now() + PID_REFRESH * 5;
        while std::fs::read_to_string(&pid_file).unwrap() != "" {
            assert!(Instant::now() < deadline, "the refresher never dropped it");
            std::thread::sleep(Duration::from_millis(50));
        }
        w.disarm();
        assert!(!pid_file.exists());
    }
}
