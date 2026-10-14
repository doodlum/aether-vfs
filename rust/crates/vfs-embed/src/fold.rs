//! **Path identity: are these two paths the same file to the VFS?**
//!
//! A host that keeps its own records of vpaths (a mod manager detecting
//! conflicts, capturing a session's writes, addressing a file in its database)
//! has to answer that question exactly the way the provider graph does, or it
//! will disagree with the VFS about what is one file and what is two. This
//! module is that answer, and it is **not a reimplementation**: every function
//! here is a thin composition of the two functions the engine itself uses —
//!
//! * `vfs_compose::path::normalize`, which [`Director`](crate::Director) runs
//!   on every host-side path before it reaches a provider, and
//! * `vfs_core::fold`, which every provider and merge uses to compare a name
//!   (and which the injected shim applies to every vpath before it crosses
//!   the ring).
//!
//! Spec: `docs/superpowers/specs/2026-10-13-export-case-fold-design.md`.
//!
//! ## The definition
//!
//! Two paths name the same file if and only if [`fold_path`] gives the same
//! string for both. `fold_path` is:
//!
//! 1. **Normalise** — `\` and `/` are both separators; empty and `.`
//!    components are dropped (so leading, trailing and doubled separators do
//!    not matter); `..` removes the previous component, and a `..` that would
//!    climb above the root is an error ([`PathEscapesRoot`]). The result is
//!    `/`-joined with no leading or trailing `/`; the root is `""`.
//! 2. **Fold** every character with Rust's `char::to_lowercase` — Unicode
//!    *simple lowercase mapping*, not Unicode case folding (`CaseFolding.txt`)
//!    and not locale-aware. [`fold_segment`] is this step alone.
//!
//! What that means for the cases people get wrong:
//!
//! | input pair | equal? | why |
//! |---|---|---|
//! | `Data/ÜBER/A.esp`, `data\über\a.ESP` | yes | Unicode lowercase, either separator |
//! | `ẞ` (U+1E9E), `ß` | yes | capital sharp s lowercases to `ß` |
//! | `STRASSE`, `straße` | **no** | no full folding: `ß` is not `ss` |
//! | `I`, `ı` (dotless) | **no** | not Turkish-aware: `I` → `i` |
//! | `İ` (U+0130), `i` | **no** | `İ` → `i` + U+0307, a different name |
//! | `ΟΔΟΣ`, `οδος` | **no** | per-character: `Σ` → `σ`, never final `ς` |
//! | `é` (U+00E9), `e` + U+0301 | **no** | no Unicode normalisation (NFC/NFD) |
//! | `a.esp.`, `a.esp` | **no** | see below |
//!
//! **Not part of identity: Win32 name cleanup.** Trailing dots and spaces,
//! `\\?\` and `\??\` prefixes, drive letters, alternate data streams and
//! device aliases are stripped by the shim's canonicaliser
//! (`vfs-redirect`'s `canon`) when a *Windows program* opens a file, before
//! any vpath exists. A Windows program opening `a.esp.` therefore reaches the
//! vpath `a.esp`; but by the time a path is a vpath — which is what a host
//! stores and what these functions take — that cleanup has already happened,
//! and the graph treats `a.esp.` and `a.esp` as different names.
//!
//! ## Stability
//!
//! A host that **stores folded keys** has made the fold part of its on-disk
//! identity: if the fold changes, a stored key may stop matching the file it
//! was written for. Two things can change it, and both are observable:
//!
//! * **The definition** (the two steps above). Changing it is already a
//!   wire-visible change for the engine (`vfs_core::fold`'s docs), so it is
//!   not done casually; when it is done, [`FOLD_VERSION`] is bumped and the
//!   change is recorded in `rust/CHANGELOG.md`. The ASCII fast path inside
//!   `vfs_core::fold` is not a definition change — its output is identical.
//! * **The Unicode tables.** `char::to_lowercase` uses the Unicode version of
//!   the Rust toolchain that compiled this crate ([`fold_unicode_version`]);
//!   the workspace does not pin a toolchain. A newer Unicode version adds
//!   mappings for newly assigned characters and, very rarely, changes an
//!   existing one. ASCII and the Latin, Greek and Cyrillic ranges game paths
//!   actually use have been stable for decades.
//!
//! The safe pattern: store the **original spelling** and fold when comparing
//! (or index on a fold you can rebuild), and record
//! `(FOLD_VERSION, fold_unicode_version())` beside any persisted folded keys
//! so a change is detected and the keys rebuilt rather than silently missed.
//! The host and the VFS it embeds must be built with the same toolchain for
//! their folds to agree on new characters; building them together (as one
//! cargo graph) guarantees it.
//!
//! ## Cost
//!
//! Each call allocates the result. An all-ASCII path — nearly every path a
//! game asks for — is one pass over its bytes for the normalise and one for
//! the fold; any non-ASCII character switches the fold to a per-character
//! Unicode lookup. [`paths_equal`] folds both arguments (two allocations
//! each); a host comparing one path against many should fold once with
//! [`fold_path`] and compare the strings.

/// A path whose `..` components climb above the root. It names no file in
/// the VFS: the Director refuses it with `ST_BAD_REQUEST`.
pub use vfs_compose::path::PathError as PathEscapesRoot;

/// Version of the fold's **definition**. Bumped, with a `rust/CHANGELOG.md`
/// entry, whenever [`fold_path`] or [`fold_segment`] would give a different
/// answer for some input under the same Unicode version. Store it beside any
/// persisted folded key — see the module docs' *Stability* section.
pub const FOLD_VERSION: u32 = 1;

/// The Unicode version whose lowercase mappings the fold uses: the one built
/// into the Rust standard library that compiled this crate, as
/// `(major, minor, update)`. Store it beside any persisted folded key, with
/// [`FOLD_VERSION`].
pub fn fold_unicode_version() -> (u8, u8, u8) {
    char::UNICODE_VERSION
}

/// The canonical folded form of a vpath: normalised (separators, empty, `.`
/// and `..` components) and then case-folded. Two paths name the same file
/// in the VFS if and only if this returns the same `Ok` string for both.
///
/// Exactly `vfs_core::fold(&vfs_compose::path::normalize(path)?)` — the
/// Director's own normalisation, then the engine's own fold; see the module
/// docs for the definition, the tricky cases, stability and cost.
///
/// ```
/// use vfs_embed::fold_path;
/// assert_eq!(fold_path(r"\Data\Textures\..\ÜBER.esp/").unwrap(), "data/über.esp");
/// assert_eq!(fold_path("").unwrap(), "");
/// assert!(fold_path("../outside").is_err());
/// ```
pub fn fold_path(path: &str) -> Result<String, PathEscapesRoot> {
    // `fold` maps characters one by one and no character lowercases to `/`,
    // so folding the joined string equals folding each component — which is
    // what the providers and merges below the Director do.
    Ok(vfs_core::fold(&vfs_compose::path::normalize(path)?))
}

/// The fold of one name, with no path handling at all: separators, `.` and
/// `..` are ordinary characters here. This is `vfs_core::fold` itself, the
/// comparison every provider applies to a single directory entry's name.
///
/// ```
/// assert_eq!(vfs_embed::fold_segment("SkyUI_SE.ESP"), "skyui_se.esp");
/// ```
pub fn fold_segment(name: &str) -> String {
    vfs_core::fold(name)
}

/// Whether `a` and `b` name the same file in the VFS: `fold_path(a) ==
/// fold_path(b)`.
///
/// A path that escapes the root names no file, so it is equal to nothing —
/// not even to itself. A host that needs to tell "different" from "invalid"
/// calls [`fold_path`].
///
/// ```
/// use vfs_embed::paths_equal;
/// assert!(paths_equal("Data/Meshes/A.NIF", r"data\meshes\a.nif"));
/// assert!(!paths_equal("STRASSE.esp", "straße.esp"));
/// ```
pub fn paths_equal(a: &str, b: &str) -> bool {
    match (fold_path(a), fold_path(b)) {
        (Ok(a), Ok(b)) => a == b,
        _ => false,
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::{MemoryProvider, Session};
    use std::sync::Arc;

    /// Pairs whose equality is easy to get wrong, with the VFS's answer.
    /// The second half of each test below holds the answer to the *graph's*
    /// behaviour, not just to this module's.
    const TRICKY: &[(&str, &str, bool)] = &[
        // Plain ASCII case, and both separators, doubled and outer.
        ("Data/Meshes/A.NIF", "data/meshes/a.nif", true),
        ("Data/Meshes/A.NIF", r"\data\\meshes/a.nif\", true),
        ("Data/./Meshes/x/../A.NIF", "data/meshes/a.nif", true),
        // Non-ASCII: Latin-1, Cyrillic.
        ("Data/ÜBER/A.esp", "data/über/a.ESP", true),
        ("Мод/ФАЙЛ.esp", "мод/файл.esp", true),
        // German sharp s: capital ẞ lowercases to ß, but ß is not "ss".
        ("ẞ.esp", "ß.esp", true),
        ("STRASSE.esp", "straße.esp", false),
        // Turkish i: not locale-aware. I -> i, never dotless ı; İ -> i + U+0307.
        ("I.esp", "i.esp", true),
        ("I.esp", "ı.esp", false),
        ("İ.esp", "i.esp", false),
        ("İ.esp", "i\u{307}.esp", true),
        // Greek sigma: per character, so Σ -> σ, never final ς.
        ("ΟΔΟΣ.esp", "οδοσ.esp", true),
        ("ΟΔΟΣ.esp", "οδος.esp", false),
        // No Unicode normalisation: precomposed é is not e + combining acute.
        ("\u{e9}.esp", "e\u{301}.esp", false),
        // Win32 trailing-dot/space cleanup is the shim's, not identity's.
        ("a.esp.", "a.esp", false),
        ("a.esp ", "a.esp", false),
    ];

    #[test]
    fn tricky_cases_are_pinned() {
        for &(a, b, want) in TRICKY {
            assert_eq!(paths_equal(a, b), want, "paths_equal({a:?}, {b:?})");
            assert_eq!(paths_equal(b, a), want, "paths_equal({b:?}, {a:?})");
        }
        assert_eq!(fold_path("İ").unwrap(), "i\u{307}");
        assert_eq!(fold_path(r"\Data\ÜBER\").unwrap(), "data/über");
        assert_eq!(fold_path("/").unwrap(), "");
    }

    /// The pins above, held to what the provider graph actually does: seed a
    /// file under one spelling, read it through a `Session` under the other.
    /// Fold-equal must resolve; fold-unequal must not.
    #[test]
    fn tricky_cases_match_what_the_vfs_resolves() {
        for &(a, b, want) in TRICKY {
            let seeded = vfs_compose::path::normalize(a).unwrap();
            let s = Session::new();
            s.mount(
                "",
                Arc::new(MemoryProvider::from_files([(seeded.as_str(), &b"x"[..])])),
            )
            .unwrap();
            assert!(s.read_file(a).is_ok(), "{a:?} must resolve as seeded");
            assert_eq!(
                s.read_file(b).is_ok(),
                want,
                "seeded {a:?}, read {b:?}: the VFS disagrees with paths_equal"
            );
        }
    }

    #[test]
    fn escaping_paths_are_errors_and_equal_nothing() {
        assert_eq!(fold_path(".."), Err(PathEscapesRoot));
        assert_eq!(fold_path("a/../../b"), Err(PathEscapesRoot));
        assert!(!paths_equal("..", ".."));
        assert!(!paths_equal("..", ""));
        // The Director refuses the same path, so "invalid" agrees too.
        let s = Session::new();
        s.mount("", Arc::new(MemoryProvider::new())).unwrap();
        assert!(s.read_file("../x").is_err());
    }

    /// `fold_segment` is the engine's fold, and `fold_path` is the Director's
    /// normalise then that fold — not a copy of either.
    #[test]
    fn is_the_engines_code() {
        for &(a, b, _) in TRICKY {
            for p in [a, b] {
                assert_eq!(fold_segment(p), vfs_core::fold(p));
                assert_eq!(
                    fold_path(p).unwrap(),
                    vfs_core::fold(&vfs_compose::path::normalize(p).unwrap())
                );
                // Folding the joined path equals folding each component, the
                // way providers compare (`vfs_compose::path::strip_prefix`).
                let n = vfs_compose::path::normalize(p).unwrap();
                let per: Vec<String> = n.split('/').map(vfs_core::fold).collect();
                assert_eq!(fold_path(p).unwrap(), per.join("/"));
            }
        }
    }

    /// Idempotent over every scalar value, so a stored folded key can itself
    /// be passed back through `fold_path` (or arrive pre-folded from the
    /// shim) and still match. And no character folds to a separator, which
    /// is what makes whole-string folding equal per-component folding.
    #[test]
    fn idempotent_and_separator_free_for_every_char() {
        for c in (0..=0x10FFFFu32).filter_map(char::from_u32) {
            let once = fold_segment(&c.to_string());
            assert_eq!(fold_segment(&once), once, "{c:?} (U+{:04X})", c as u32);
            if c != '/' && c != '\\' {
                assert!(!once.contains(['/', '\\']), "U+{:04X}", c as u32);
            }
        }
    }

    #[test]
    fn version_markers() {
        assert_eq!(FOLD_VERSION, 1);
        assert_eq!(fold_unicode_version(), char::UNICODE_VERSION);
    }
}
