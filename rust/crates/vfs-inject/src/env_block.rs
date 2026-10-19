//! The environment block a launched target starts with, built explicitly.
//!
//! A launch never writes this process's environment: `std::env::set_var` is
//! unsound in a multi-threaded process, and a host embedding a launch always
//! has other threads. Instead the target gets a block made from this
//! process's own (read once, as `GetEnvironmentStringsW` returns it, so the
//! hidden `=C:` per-drive directories come along), minus the names asked to
//! be removed, with the names asked to be set put in, and is created with
//! `CREATE_UNICODE_ENVIRONMENT`.
//!
//! Names compare without regard to case, as Windows' own do.

/// `name=value` entries of a `CreateProcessW` environment block, which is
/// entries separated by NUL and ended by an empty one.
fn entries(block: &[u16]) -> Vec<&[u16]> {
    block
        .split(|&u| u == 0)
        .take_while(|e| !e.is_empty())
        .collect()
}

/// The name of `entry`: everything before the first `=` that is not its
/// first character (`=C:=C:\dir` is named `=C:`).
fn name_of(entry: &[u16]) -> &[u16] {
    let eq = b'=' as u16;
    match entry.iter().skip(1).position(|&u| u == eq) {
        Some(i) => &entry[..=i],
        None => entry,
    }
}

/// `name` folded for comparison.
fn key(name: &[u16]) -> String {
    String::from_utf16_lossy(name).to_uppercase()
}

/// The block for a target: `parent`'s entries, without any named in
/// `remove` or `set`, plus each of `set` (a later duplicate wins), sorted by
/// name as Windows keeps a block — the `=`-prefixed entries first, in their
/// own order.
pub fn build(parent: &[u16], set: &[(String, String)], remove: &[String]) -> Vec<u16> {
    let mut dropped: Vec<String> = remove.iter().map(|n| n.to_uppercase()).collect();
    dropped.extend(set.iter().map(|(n, _)| n.to_uppercase()));
    let mut hidden: Vec<Vec<u16>> = Vec::new();
    let mut named: Vec<(String, Vec<u16>)> = Vec::new();
    for e in entries(parent) {
        let k = key(name_of(e));
        if dropped.contains(&k) {
            continue;
        }
        if e.first() == Some(&(b'=' as u16)) {
            hidden.push(e.to_vec());
        } else {
            named.push((k, e.to_vec()));
        }
    }
    for (i, (n, v)) in set.iter().enumerate() {
        let k = n.to_uppercase();
        if set[i + 1..].iter().any(|(m, _)| m.to_uppercase() == k) {
            continue;
        }
        named.push((k, format!("{n}={v}").encode_utf16().collect()));
    }
    named.sort_by(|a, b| a.0.cmp(&b.0));
    let mut out = Vec::new();
    for e in hidden.iter().chain(named.iter().map(|(_, e)| e)) {
        out.extend_from_slice(e);
        out.push(0);
    }
    out.push(0);
    if out.len() == 1 {
        // An empty block is two NULs.
        out.push(0);
    }
    out
}

/// This process's environment block, as `GetEnvironmentStringsW` returns
/// it, through the final empty entry.
#[allow(unsafe_code)]
pub fn current() -> Vec<u16> {
    use windows_sys::Win32::System::Environment::{
        FreeEnvironmentStringsW, GetEnvironmentStringsW,
    };
    // SAFETY: the block is NUL-separated and ends with an empty entry, so the
    // scan stops inside it; it is copied out before being freed.
    unsafe {
        let p = GetEnvironmentStringsW();
        if p.is_null() {
            return vec![0, 0];
        }
        let mut n = 0usize;
        while !(*p.add(n) == 0 && *p.add(n + 1) == 0) {
            n += 1;
        }
        let v = std::slice::from_raw_parts(p, n + 2).to_vec();
        FreeEnvironmentStringsW(p);
        v
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn block(entries: &[&str]) -> Vec<u16> {
        let mut v = Vec::new();
        for e in entries {
            v.extend(e.encode_utf16());
            v.push(0);
        }
        v.push(0);
        v
    }

    fn strings(b: &[u16]) -> Vec<String> {
        entries(b)
            .into_iter()
            .map(String::from_utf16_lossy)
            .collect()
    }

    #[test]
    fn sets_replace_without_regard_to_case_and_removals_drop() {
        let parent = block(&[
            "=C:=C:\\dir",
            "Path=C:\\Windows",
            "SteamAppId=489830",
            "VFS_RING_PATH=stale",
        ]);
        let out = build(
            &parent,
            &[
                ("PATH".into(), "C:\\mine".into()),
                ("VFS_RING_SECTION".into(), "Local\\r".into()),
            ],
            &["steamappid".into(), "VFS_RING_PATH".into()],
        );
        assert_eq!(
            strings(&out),
            ["=C:=C:\\dir", "PATH=C:\\mine", "VFS_RING_SECTION=Local\\r"]
        );
        assert_eq!(&out[out.len() - 2..], &[0, 0]);
    }

    #[test]
    fn a_later_set_wins_and_the_block_is_sorted() {
        let out = build(
            &block(&["B=1"]),
            &[
                ("A".into(), "first".into()),
                ("C".into(), "3".into()),
                ("a".into(), "second".into()),
            ],
            &[],
        );
        assert_eq!(strings(&out), ["a=second", "B=1", "C=3"]);
    }

    #[test]
    fn an_empty_result_is_two_nuls() {
        assert_eq!(build(&block(&["X=1"]), &[], &["x".into()]), [0, 0]);
    }

    #[test]
    fn the_current_block_parses_and_holds_path() {
        let cur = current();
        assert!(strings(&cur)
            .iter()
            .any(|e| e.to_uppercase().starts_with("PATH=")));
    }
}
