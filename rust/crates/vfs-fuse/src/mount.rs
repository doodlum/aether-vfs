//! Mount lifecycle: mount a root, invalidate after a remount, unmount, and
//! recover a mount a dead process left behind.

use std::ffi::{OsStr, OsString};
use std::io::{self, IoSliceMut};
use std::mem::MaybeUninit;
use std::os::fd::{AsRawFd, OwnedFd};
use std::os::unix::net::UnixStream;
use std::path::{Path, PathBuf};
use std::process::{Child, Command, Stdio};
use std::sync::Arc;
use std::time::Duration;

use fuser::{BackgroundSession, Config, INodeNo, MountOption, Notifier};
use rustix::net::{recvmsg, RecvAncillaryBuffer, RecvAncillaryMessage, RecvFlags};
use vfs_director::Director;
use vfs_provider::RootId;

use crate::errno::libc_errno;
use crate::fs::{AetherFs, Shared};

/// The mount's source name and subtype: `/proc/self/mountinfo` shows its
/// type as `fuse.aether-vfs`.
pub const FS_NAME: &str = "aether-vfs";

/// How a root is mounted.
#[derive(Clone, Debug)]
pub struct MountOpts {
    /// How long the kernel may cache an entry or attributes without asking.
    /// [`FuseMount::invalidate`] drops them early, so this only bounds what a
    /// change the mount was not told about takes to show.
    pub ttl: Duration,
    /// FUSE request threads. A slow provider holds one thread per request in
    /// flight; the rest keep serving.
    pub threads: usize,
}

impl Default for MountOpts {
    fn default() -> Self {
        MountOpts {
            ttl: Duration::from_secs(1),
            threads: 4,
        }
    }
}

/// What is at a path, as far as mounting over it goes.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum MountState {
    /// No FUSE mount is there (the path may not exist).
    None,
    /// A FUSE mount whose process is gone: every access fails `ENOTCONN`.
    Stale,
    /// A live FUSE mount, this process's or another's.
    Live,
}

/// How [`FuseMount::unmount`] went.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum Unmounted {
    /// Unmounted; nothing held it.
    Clean,
    /// Something held a file or directory open on it: the mount is detached
    /// from the folder now (new lookups see the real folder), and keeps
    /// serving what is already open until the last of it closes.
    Lazy,
}

/// A director root served by FUSE at a folder. Dropping it unmounts.
pub struct FuseMount {
    session: Option<BackgroundSession>,
    notifier: Notifier,
    shared: Arc<Shared>,
    mountpoint: PathBuf,
}

impl std::fmt::Debug for FuseMount {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        f.debug_struct("FuseMount")
            .field("mountpoint", &self.mountpoint)
            .field("root", &self.shared.root)
            .finish()
    }
}

impl FuseMount {
    /// Mount `root` of `director` over the folder `mountpoint`, which must
    /// exist. A stale mount there is recovered first; a live one is refused
    /// (`AlreadyExists`).
    ///
    /// Whatever was in the folder is hidden from the moment this returns: a
    /// root that should show it composes a [`crate::BaseDir`] opened on it
    /// **before** this call.
    pub fn mount(
        director: Arc<Director>,
        root: RootId,
        mountpoint: impl AsRef<Path>,
        opts: MountOpts,
    ) -> io::Result<FuseMount> {
        let mountpoint = mountpoint.as_ref();
        match mount_state(mountpoint)? {
            MountState::Stale => {
                recover_stale_mount(mountpoint)?;
            }
            MountState::Live => {
                return Err(io::Error::new(
                    io::ErrorKind::AlreadyExists,
                    format!("{} is already a FUSE mount", mountpoint.display()),
                ))
            }
            MountState::None => {}
        }
        let mountpoint = mountpoint.canonicalize()?;
        let shared = Arc::new(Shared::new(
            director,
            root,
            opts.ttl,
            mountpoint.parent().map(Path::to_path_buf),
        ));
        let mut config = Config::default();
        config.mount_options = vec![
            MountOption::FSName(FS_NAME.into()),
            MountOption::Subtype(FS_NAME.into()),
            MountOption::DefaultPermissions,
            MountOption::NoAtime,
        ];
        config.n_threads = Some(opts.threads.max(1));
        let fs = AetherFs {
            shared: Arc::clone(&shared),
        };
        let session = if std::env::var_os("FUSERMOUNT_PATH").is_none() && dev_fuse_usable() {
            config.clone_fd = true;
            fuser::Session::new(fs, &mountpoint, &config)?
        } else {
            // There is no `/dev/fuse` here to give each thread a descriptor of its own.
            config.clone_fd = false;
            let fd = fusermount_receive(&[
                OsStr::new("-o"),
                OsStr::new(MOUNT_OPTIONS),
                OsStr::new("--"),
                mountpoint.as_os_str(),
            ])?;
            let acl = config.acl;
            fuser::Session::from_fd(fs, fd, acl, config).inspect_err(|_| {
                let _ = fusermount(&[
                    OsStr::new("-u"),
                    OsStr::new("-z"),
                    OsStr::new("--"),
                    mountpoint.as_os_str(),
                ]);
            })?
        };
        let session = session.spawn()?;
        let notifier = session.notifier();
        tracing::info!(mountpoint = %mountpoint.display(), root = root.0, "mounted");
        Ok(FuseMount {
            session: Some(session),
            notifier,
            shared,
            mountpoint,
        })
    }

    /// The folder this is mounted at.
    pub fn mountpoint(&self) -> &Path {
        &self.mountpoint
    }

    /// The root's content changed (its provider was replaced): drop
    /// everything the kernel cached about it — entries, attributes, pages —
    /// so the next lookup and open see the new content. Files already open
    /// keep reading what they opened.
    pub fn invalidate(&self) {
        let (aliases, inos) = self.shared.inodes().reset();
        // Notifications are sent outside every lock the request threads take:
        // the kernel may wait on a directory a request is working in.
        for (parent, name) in aliases {
            let _ = self
                .notifier
                .inval_entry(INodeNo(parent), OsStr::new(&name));
        }
        for ino in inos {
            let _ = self.notifier.inval_inode(INodeNo(ino), 0, 0);
        }
    }

    /// Unmount. If something still holds the mount open, it is detached
    /// lazily instead ([`Unmounted::Lazy`]) and its threads keep serving what
    /// is open until that closes.
    pub fn unmount(mut self) -> io::Result<Unmounted> {
        self.unmount_inner()
    }

    fn unmount_inner(&mut self) -> io::Result<Unmounted> {
        let Some(session) = self.session.take() else {
            return Ok(Unmounted::Clean);
        };
        let mp = self.mountpoint.as_os_str();
        // A process forked anywhere in this host holds a copy of every open
        // descriptor until it execs, so "busy" can be momentary: retry briefly
        // before settling for a lazy unmount.
        let mut clean = fusermount(&[OsStr::new("-u"), OsStr::new("--"), mp]);
        for _ in 0..4 {
            if clean.is_ok() {
                break;
            }
            std::thread::sleep(Duration::from_millis(50));
            clean = fusermount(&[OsStr::new("-u"), OsStr::new("--"), mp]);
        }
        let how = match clean {
            Ok(()) => Unmounted::Clean,
            Err(busy) => {
                tracing::warn!(mountpoint = %self.mountpoint.display(), error = %busy, "unmount refused; detaching lazily");
                fusermount(&[OsStr::new("-u"), OsStr::new("-z"), OsStr::new("--"), mp])?;
                Unmounted::Lazy
            }
        };
        match how {
            // The kernel ended the connection: the request threads have
            // returned or are about to.
            Unmounted::Clean => {
                let _ = session.join();
            }
            // Open files keep the connection, and the threads serving it,
            // alive; joining would wait for the program holding them.
            Unmounted::Lazy => {
                std::thread::spawn(move || {
                    let _ = session.join();
                });
            }
        }
        tracing::info!(mountpoint = %self.mountpoint.display(), ?how, "unmounted");
        Ok(how)
    }
}

impl Drop for FuseMount {
    fn drop(&mut self) {
        let _ = self.unmount_inner();
    }
}

/// The mount options a mount through [`fusermount_receive`] passes: the ones
/// fuser is given.
const MOUNT_OPTIONS: &str = "fsname=aether-vfs,subtype=aether-vfs,default_permissions,noatime";

/// Whether this process can open `/dev/fuse` itself. A sandbox (Flatpak) has
/// none, and fuser then fails instead of falling back to the setuid helper.
fn dev_fuse_usable() -> bool {
    std::fs::OpenOptions::new()
        .read(true)
        .write(true)
        .open("/dev/fuse")
        .is_ok()
}

/// The setuid FUSE helpers to try, in order: `$FUSERMOUNT_PATH`,
/// `fusermount3`, `fusermount`.
fn fusermount_candidates() -> Vec<OsString> {
    let mut candidates: Vec<OsString> = Vec::new();
    if let Some(p) = std::env::var_os("FUSERMOUNT_PATH") {
        candidates.push(p);
    }
    candidates.push("fusermount3".into());
    candidates.push("fusermount".into());
    candidates
}

/// Start the first helper there is.
fn spawn_fusermount(build: impl Fn(&OsStr) -> Command) -> io::Result<Child> {
    let mut last = io::Error::new(io::ErrorKind::NotFound, "fusermount3 is not installed");
    for bin in fusermount_candidates() {
        match build(&bin).spawn() {
            Ok(child) => return Ok(child),
            Err(e) if e.kind() == io::ErrorKind::NotFound => last = e,
            Err(e) => return Err(e),
        }
    }
    Err(last)
}

/// Run the setuid helper with `args` (a mount: `-o <options> -- <folder>`) and
/// take the FUSE descriptor it sends back over `_FUSE_COMMFD`, as libfuse
/// does: for a process that cannot open `/dev/fuse`, or is told which helper
/// to use (a Flatpak's `$FUSERMOUNT_PATH`, through the host's
/// `fusermount-relay`).
pub fn fusermount_receive(args: &[&OsStr]) -> io::Result<OwnedFd> {
    let (ours, theirs) = UnixStream::pair()?;
    // The helper inherits its end of the socket; ours stays close-on-exec.
    rustix::io::fcntl_setfd(&theirs, rustix::io::FdFlags::empty())?;
    let child = spawn_fusermount(|bin| {
        let mut command = Command::new(bin);
        command
            .args(args)
            .env("_FUSE_COMMFD", theirs.as_raw_fd().to_string())
            .stdin(Stdio::null())
            .stdout(Stdio::piped())
            .stderr(Stdio::piped());
        command
    })?;
    drop(theirs);
    let mut byte = [0u8; 1];
    let mut space = [MaybeUninit::uninit(); rustix::cmsg_space!(ScmRights(1))];
    let mut control = RecvAncillaryBuffer::new(&mut space);
    let received = recvmsg(
        &ours,
        &mut [IoSliceMut::new(&mut byte)],
        &mut control,
        RecvFlags::CMSG_CLOEXEC,
    );
    let fd = match received {
        Ok(_) => control.drain().find_map(|message| match message {
            RecvAncillaryMessage::ScmRights(mut fds) => fds.next(),
            _ => None,
        }),
        Err(_) => None,
    };
    let output = child.wait_with_output()?;
    match fd {
        Some(fd) if output.status.success() => Ok(fd),
        _ => Err(io::Error::other(format!(
            "fusermount {}: {}",
            args.iter()
                .map(|a| a.to_string_lossy())
                .collect::<Vec<_>>()
                .join(" "),
            String::from_utf8_lossy(&output.stderr).trim()
        ))),
    }
}

/// Run the setuid FUSE helper: `$FUSERMOUNT_PATH`, else `fusermount3`, else
/// `fusermount`.
pub fn fusermount(args: &[&OsStr]) -> io::Result<()> {
    let mut last = io::Error::new(io::ErrorKind::NotFound, "fusermount3 is not installed");
    for bin in fusermount_candidates() {
        match Command::new(&bin).args(args).output() {
            Ok(out) if out.status.success() => return Ok(()),
            Ok(out) => {
                return Err(io::Error::other(format!(
                    "{} {}: {}",
                    bin.to_string_lossy(),
                    args.iter()
                        .map(|a| a.to_string_lossy())
                        .collect::<Vec<_>>()
                        .join(" "),
                    String::from_utf8_lossy(&out.stderr).trim()
                )))
            }
            Err(e) if e.kind() == io::ErrorKind::NotFound => last = e,
            Err(e) => return Err(e),
        }
    }
    Err(last)
}

/// `path` made absolute without touching it (a stale mount fails every
/// lookup of the path itself): its parent canonicalized, its name kept.
fn absolute_without_following(path: &Path) -> io::Result<PathBuf> {
    let abs = if path.is_absolute() {
        path.to_path_buf()
    } else {
        std::env::current_dir()?.join(path)
    };
    match (abs.parent(), abs.file_name()) {
        (Some(parent), Some(name)) => Ok(parent.canonicalize()?.join(name)),
        _ => Ok(abs),
    }
}

/// Undo `/proc/self/mountinfo`'s octal escapes (`\040` is a space).
fn unescape_mountinfo(field: &str) -> PathBuf {
    let bytes = field.as_bytes();
    let mut out = Vec::with_capacity(bytes.len());
    let mut i = 0;
    while i < bytes.len() {
        if bytes[i] == b'\\' && i + 3 < bytes.len() {
            let oct = &bytes[i + 1..i + 4];
            if oct.iter().all(|b| (b'0'..=b'7').contains(b)) {
                let v = oct
                    .iter()
                    .fold(0u32, |acc, b| acc * 8 + u32::from(b - b'0'));
                if let Ok(v) = u8::try_from(v) {
                    out.push(v);
                    i += 4;
                    continue;
                }
            }
        }
        out.push(bytes[i]);
        i += 1;
    }
    use std::os::unix::ffi::OsStringExt;
    PathBuf::from(std::ffi::OsString::from_vec(out))
}

/// The filesystem type of the topmost mount at `path`, if `path` is a mount
/// point.
fn mount_type_at(path: &Path) -> io::Result<Option<String>> {
    let info = std::fs::read_to_string("/proc/self/mountinfo")?;
    let mut found = None;
    for line in info.lines() {
        let fields: Vec<&str> = line.split(' ').collect();
        let Some(sep) = fields.iter().position(|f| *f == "-") else {
            continue;
        };
        if fields.len() < 5 || sep + 1 >= fields.len() {
            continue;
        }
        if unescape_mountinfo(fields[4]) == path {
            // Later lines are mounted on top of earlier ones.
            found = Some(fields[sep + 1].to_string());
        }
    }
    Ok(found)
}

/// Whether a FUSE mount is at `path`, and whether its process is alive.
pub fn mount_state(path: impl AsRef<Path>) -> io::Result<MountState> {
    let path = path.as_ref();
    match std::fs::metadata(path) {
        Err(e) if e.raw_os_error() == Some(libc_errno::ENOTCONN) => Ok(MountState::Stale),
        Err(e) if e.kind() == io::ErrorKind::NotFound => Ok(MountState::None),
        Err(e) => Err(e),
        Ok(_) => {
            let canon = path.canonicalize()?;
            match mount_type_at(&canon)? {
                Some(t) if t == "fuse" || t.starts_with("fuse.") => {}
                _ => return Ok(MountState::None),
            }
            // The stat above can be answered from cached attributes even when
            // nothing serves the mount any more; `statfs` always asks.
            match rustix::fs::statvfs(&canon) {
                Err(e) if e.raw_os_error() == libc_errno::ENOTCONN => Ok(MountState::Stale),
                _ => Ok(MountState::Live),
            }
        }
    }
}

/// Lazily unmount a stale FUSE mount at `path` (`fusermount3 -uz`), as a
/// crashed host leaves behind. `Ok(true)` if there was one. Never touches a
/// live mount.
pub fn recover_stale_mount(path: impl AsRef<Path>) -> io::Result<bool> {
    let path = path.as_ref();
    let mut recovered = false;
    // Two crashed runs can leave two mounts stacked on the same folder.
    for _ in 0..8 {
        if mount_state(path)? != MountState::Stale {
            return Ok(recovered);
        }
        let abs = absolute_without_following(path)?;
        fusermount(&[
            OsStr::new("-u"),
            OsStr::new("-z"),
            OsStr::new("--"),
            abs.as_os_str(),
        ])?;
        tracing::info!(path = %abs.display(), "recovered a stale mount");
        recovered = true;
    }
    Ok(recovered)
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn mountinfo_fields_unescape() {
        assert_eq!(
            unescape_mountinfo(r"/home/dev/My\040Games/x"),
            PathBuf::from("/home/dev/My Games/x")
        );
        assert_eq!(unescape_mountinfo(r"/a\b"), PathBuf::from(r"/a\b"));
    }
}
