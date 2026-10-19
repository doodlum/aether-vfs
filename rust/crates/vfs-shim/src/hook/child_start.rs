//! Where a child of a purely virtual location really starts, and what it is
//! told.
//!
//! A program the launched program starts (`skse64_loader.exe` starting
//! `SkyrimSE.exe`) has the same problem the launched program had: the kernel
//! opens its image, and the loader its static imports, before any hook runs
//! in it, and `CreateProcess` needs a real working directory. When roots have
//! backing directories (`vfs_env::ROOT_BACKINGS`), the process hook therefore
//!
//! - starts a child whose image only the VFS holds from a copy the director
//!   stages into the root's backing directory (`OP_STAGE_EXEC`), leaving the
//!   command line, with its virtual `argv[0]`, as the caller wrote it;
//! - starts a child whose working directory is under a root in that root's
//!   backing directory, and tells it the virtual one before it runs;
//! - gives a child the caller hands its own environment block the session's
//!   `VFS_*` variables too, so its shim finds the ring.
//!
//! The child's shim then renames its image path, as the launched program's
//! did (`whereami`).

use vfs_protocol::RootId;

use crate::director::{backings_from_env, global, FuseClient};

/// `CREATE_UNICODE_ENVIRONMENT`.
const CREATE_UNICODE_ENVIRONMENT: u32 = 0x400;

/// What the process hook changes about a child's creation.
#[derive(Default)]
pub(super) struct ChildStart {
    /// The image to create the child from, NUL-terminated, when it is a
    /// staged copy rather than what the caller named.
    pub app: Option<Vec<u16>>,
    /// The real working directory to create it in, NUL-terminated.
    pub cwd: Option<Vec<u16>>,
    /// The virtual working directory to tell it, once created.
    pub virtual_cwd: Option<String>,
    /// The environment block to create it with (Unicode), when the caller
    /// handed one in.
    pub env: Option<Vec<u16>>,
    /// The caller named an image under a root that the VFS does not have:
    /// refused rather than started from whatever is really on disk there.
    pub refuse: bool,
}

/// A NUL-terminated UTF-16 string, or `None` for a null pointer.
///
/// # Safety
/// `p` is null or a NUL-terminated UTF-16 string.
pub(super) unsafe fn wide_opt(p: *const u16) -> Option<String> {
    if p.is_null() {
        return None;
    }
    let mut n = 0;
    // SAFETY: NUL-terminated per the contract.
    unsafe {
        while *p.add(n) != 0 {
            n += 1;
        }
        Some(String::from_utf16_lossy(core::slice::from_raw_parts(p, n)))
    }
}

fn nul(s: &str) -> Vec<u16> {
    s.encode_utf16().chain(Some(0)).collect()
}

/// The program a command line starts: its first argument.
fn argv0(cmd: &str) -> String {
    let cmd = cmd.trim_start();
    match cmd.strip_prefix('"') {
        Some(rest) => rest.split('"').next().unwrap_or("").to_string(),
        None => cmd.split([' ', '\t']).next().unwrap_or("").to_string(),
    }
}

fn is_absolute(p: &str) -> bool {
    let b = p.as_bytes();
    (b.len() >= 3 && b[1] == b':' && (b[2] == b'\\' || b[2] == b'/')) || p.starts_with(r"\\")
}

fn join(dir: &str, name: &str) -> String {
    format!("{}\\{name}", dir.trim_end_matches(['\\', '/']))
}

/// `path`'s root and vpath when it is under one.
fn routed(client: &FuseClient, path: &str) -> Option<(RootId, String)> {
    client.route(&format!(r"\??\{path}"))
}

/// The process's current directory as it was told it (the PEB's).
fn current_dir() -> Option<String> {
    // SAFETY: reads this process's own PEB.
    unsafe { super::path::cwd_from_peb() }.map(|(_, dos)| dos)
}

/// Plan a child's creation. `None` when nothing about it changes (no root
/// has a backing directory, no director, or nothing the child names is
/// under a root).
///
/// # Safety
/// `app`, `cmd` and `cur_dir` are null or NUL-terminated UTF-16 strings;
/// `env` is null or an environment block, Unicode when `flags` says so.
pub(super) unsafe fn plan(
    app: *const u16,
    cmd: *const u16,
    env: *const core::ffi::c_void,
    flags: u32,
    cur_dir: Option<String>,
) -> Option<ChildStart> {
    let backings = backings_from_env();
    if backings.is_empty() {
        return None;
    }
    let client = global()?;
    let mut out = ChildStart::default();

    // The image. Named by the application name, or by the command line's
    // first argument, searched the way `CreateProcess` searches: beside the
    // parent's image, then in the current directory (both as the parent was
    // told them), with `.exe` added when the name has no extension.
    // SAFETY: per this function's contract.
    let (named, by_app) = match unsafe { wide_opt(app) } {
        Some(a) => (a, true),
        // SAFETY: per this function's contract.
        None => (argv0(&unsafe { wide_opt(cmd) }.unwrap_or_default()), false),
    };
    if !named.is_empty() {
        let mut names = vec![named.clone()];
        if std::path::Path::new(&named).extension().is_none() {
            names.push(format!("{named}.exe"));
        }
        let mut candidates = Vec::new();
        for n in &names {
            if is_absolute(n) {
                candidates.push(n.clone());
                continue;
            }
            if !by_app {
                if let Some(dir) = std::env::current_exe()
                    .ok()
                    .and_then(|e| e.parent().map(|d| d.to_string_lossy().into_owned()))
                {
                    candidates.push(join(&dir, n));
                }
            }
            if let Some(dir) = cur_dir.clone().or_else(current_dir) {
                candidates.push(join(&dir, n));
            }
        }
        let mut under_root = false;
        for c in &candidates {
            let Some((root, vp)) = routed(client, c) else {
                continue;
            };
            under_root = true;
            if matches!(client.getattr(root, &vp), Ok(a) if a.found && !a.is_dir) {
                if let Ok(real) = client.stage_exec(root, &vp) {
                    out.app = Some(nul(&real));
                }
                break;
            }
        }
        // An absolute image under a root that the VFS does not have would be
        // opened by the kernel from whatever is really there.
        if out.app.is_none() && under_root && candidates.iter().all(|c| is_absolute(c)) {
            out.refuse = true;
            return Some(out);
        }
    }

    // The working directory.
    if let Some(dir) = cur_dir.or_else(current_dir) {
        if let Some((root, _)) = routed(client, &dir) {
            if let Some((_, backing)) = backings.iter().find(|(id, _)| *id == root) {
                let _ = std::fs::create_dir_all(backing);
                out.cwd = Some(nul(backing));
                out.virtual_cwd = Some(dir);
            }
        }
    }

    // The environment, when the caller handed one in: theirs, with each of
    // this process's handshake variables (the ring, the roots, the shim's
    // config) that it lacks. Only those, and only where missing: everything
    // else in the block is the caller's choice, removals included.
    if !env.is_null() {
        // SAFETY: per this function's contract.
        let parent = unsafe { caller_block(env, flags & CREATE_UNICODE_ENVIRONMENT != 0) };
        let theirs: Vec<String> = block_entries(&parent)
            .into_iter()
            .filter_map(|e| e.split_once('=').map(|(k, _)| k.to_ascii_uppercase()))
            .collect();
        let handshake = |k: &str| {
            vfs_env::ALL
                .iter()
                .any(|v| v.kind == vfs_env::Kind::Handshake && v.name.eq_ignore_ascii_case(k))
        };
        let ours: Vec<(String, String)> = block_entries(&vfs_inject::current_env_block())
            .into_iter()
            .filter_map(|e| {
                let (k, v) = e.split_once('=')?;
                (handshake(k) && !theirs.contains(&k.to_ascii_uppercase()))
                    .then(|| (k.to_string(), v.to_string()))
            })
            .collect();
        if !ours.is_empty() {
            out.env = Some(vfs_inject::build_env_block(&parent, &ours, &[]));
        }
    }

    let changed = out.app.is_some() || out.cwd.is_some() || out.env.is_some();
    changed.then_some(out)
}

/// The caller's environment block as UTF-16, through its final empty entry.
///
/// # Safety
/// `env` is an environment block: UTF-16 when `unicode`, else 8-bit.
unsafe fn caller_block(env: *const core::ffi::c_void, unicode: bool) -> Vec<u16> {
    let mut out = Vec::new();
    let mut n = 0usize;
    // SAFETY: the block ends with two NULs; the scan stops there.
    unsafe {
        if unicode {
            let p = env as *const u16;
            while !(*p.add(n) == 0 && *p.add(n + 1) == 0) {
                n += 1;
            }
            out.extend_from_slice(core::slice::from_raw_parts(p, n + 2));
        } else {
            let p = env as *const u8;
            while !(*p.add(n) == 0 && *p.add(n + 1) == 0) {
                n += 1;
            }
            out.extend(
                core::slice::from_raw_parts(p, n + 2)
                    .iter()
                    .map(|&b| b as u16),
            );
        }
    }
    out
}

/// The `name=value` entries of an environment block (the `=C:` ones too).
fn block_entries(block: &[u16]) -> Vec<String> {
    block
        .split(|&u| u == 0)
        .take_while(|e| !e.is_empty())
        .map(String::from_utf16_lossy)
        .collect()
}

/// The flags to create the child with, given the caller's: Unicode
/// environment when the plan built one.
pub(super) fn flags_for(plan: Option<&ChildStart>, flags: u32) -> u32 {
    match plan {
        Some(p) if p.env.is_some() => flags | CREATE_UNICODE_ENVIRONMENT,
        _ => flags,
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn the_first_argument_is_the_program() {
        assert_eq!(argv0(r#""C:\a b\x.exe" -arg"#), r"C:\a b\x.exe");
        assert_eq!(argv0(r"SkyrimSE.exe -x"), "SkyrimSE.exe");
        assert_eq!(argv0("  plain"), "plain");
        assert!(is_absolute(r"C:\x") && is_absolute(r"\\srv\s") && !is_absolute("x.exe"));
    }
}
