//! Which Wine a launch runs under: a GE-Proton runtime, or CrossOver.
//!
//! Everything else about a launch — the injector and its argv, the shim's
//! environment handshake, the file-backed ring, the prefix with its root
//! links and lock, `wineserver` control — is the same Wine on both, so it is
//! written once against [`WineHost`], which answers only the questions whose
//! answers differ: where `wine` and `wineserver` are, what puts a run in a
//! prefix, how the install is verified, and which environment the install
//! itself needs. See [`crate::crossover`] for why CrossOver's answers are what
//! they are.

use std::path::{Path, PathBuf};

/// The Wine install a launch runs under.
#[derive(Debug, Clone, PartialEq, Eq)]
pub enum WineHost {
    /// A GE-Proton runtime directory (`…/GE-ProtonN-M-x86_64`), verified as
    /// GE before use ([`crate::verify_ge`]).
    Proton(PathBuf),
    /// A CrossOver install: the directory holding `bin/wine`
    /// (`CrossOver.app/Contents/SharedSupport/CrossOver`), as
    /// [`crate::crossover::verify`] returns it.
    CrossOver(PathBuf),
}

/// A bare directory is a Proton runtime, as it was before CrossOver existed.
impl From<PathBuf> for WineHost {
    fn from(dir: PathBuf) -> Self {
        WineHost::Proton(dir)
    }
}

impl From<&Path> for WineHost {
    fn from(dir: &Path) -> Self {
        WineHost::Proton(dir.to_path_buf())
    }
}

impl From<&PathBuf> for WineHost {
    fn from(dir: &PathBuf) -> Self {
        WineHost::Proton(dir.clone())
    }
}

impl WineHost {
    /// The install's directory.
    pub fn dir(&self) -> &Path {
        match self {
            WineHost::Proton(d) | WineHost::CrossOver(d) => d,
        }
    }

    /// Whether this is a GE-Proton runtime: Proton's prefix setup, its Steam
    /// bridge and NVAPI exist only there.
    pub fn is_proton(&self) -> bool {
        matches!(self, WineHost::Proton(_))
    }

    /// The `wine` a launch runs.
    pub fn wine(&self) -> PathBuf {
        match self {
            WineHost::Proton(d) => crate::launch::wine_binary(d),
            WineHost::CrossOver(d) => crate::crossover::wine(d),
        }
    }

    /// The `wineserver` that stops a prefix and reports it quiet.
    pub fn wineserver(&self) -> PathBuf {
        match self {
            WineHost::Proton(d) => d.join("files").join("bin").join("wineserver"),
            WineHost::CrossOver(d) => crate::crossover::wineserver(d),
        }
    }

    /// Arguments that go between `wine` and the program to run it in
    /// `prefix`: none for Proton (`WINEPREFIX` does it); for CrossOver, whose
    /// front end overwrites `WINEPREFIX`, `--bottle <prefix>` and
    /// `--no-convert` ([`crate::crossover::bottle_args`]).
    pub fn prefix_args(&self, prefix: &Path) -> Vec<String> {
        match self {
            WineHost::Proton(_) => Vec::new(),
            WineHost::CrossOver(_) => crate::crossover::bottle_args(prefix),
        }
    }

    /// Checks the install is one a launch may use. Proton: the GE gate,
    /// whose tag is returned. CrossOver: its parts, and its version (or
    /// `"CrossOver"` when the bundle does not say).
    pub fn verify(&self) -> Result<String, String> {
        match self {
            WineHost::Proton(d) => crate::verify_ge(d).map_err(|e| e.to_string()),
            WineHost::CrossOver(d) => {
                let dir = crate::crossover::verify(d).map_err(|e| e.to_string())?;
                Ok(crate::crossover::version(&dir)
                    .map_or_else(|| "CrossOver".into(), |v| format!("CrossOver {v}")))
            }
        }
    }

    /// One line naming the install, for errors and logs.
    pub fn describe(&self) -> String {
        match self {
            WineHost::Proton(d) => format!("GE-Proton runtime {}", d.display()),
            WineHost::CrossOver(d) => match crate::crossover::version(d) {
                Some(v) => format!("CrossOver {v} at {}", d.display()),
                None => format!("CrossOver at {}", d.display()),
            },
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn a_path_is_a_proton_runtime() {
        let h: WineHost = PathBuf::from("/rt/GE-Proton11-6").into();
        assert_eq!(h, WineHost::Proton("/rt/GE-Proton11-6".into()));
        assert_eq!(h.wine(), Path::new("/rt/GE-Proton11-6/files/bin/wine"));
        assert_eq!(
            h.wineserver(),
            Path::new("/rt/GE-Proton11-6/files/bin/wineserver")
        );
        assert!(h.prefix_args(Path::new("/p")).is_empty());
        assert!(h.is_proton());
    }

    #[test]
    fn crossover_runs_its_front_end_in_a_named_bottle() {
        let h = WineHost::CrossOver("/cx".into());
        assert_eq!(h.wine(), Path::new("/cx/bin/wine"));
        assert_eq!(h.wineserver(), Path::new("/cx/bin/wineserver"));
        assert_eq!(
            h.prefix_args(Path::new("/p")),
            ["--bottle", "/p", "--no-convert"]
        );
        assert!(!h.is_proton());
    }
}
