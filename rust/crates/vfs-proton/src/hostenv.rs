//! The host environment as Proton, Wine and `wineserver` should see it.
//!
//! An AppImage's `AppRun` points `PYTHONHOME`, `PYTHONPATH`, `PATH`,
//! `LD_LIBRARY_PATH`, GTK's module paths and more into the AppImage
//! (`$APPDIR`) before it starts the app, and every program the app starts
//! inherits them. GE-Proton's `proton` is a Python script, and with
//! `PYTHONHOME` inside the AppImage the host's Python cannot find its own
//! standard library ("No module named 'encodings'"); Wine would load the
//! AppImage's libraries. So every host program this crate starts goes
//! through [`command`], and every search path it inherits through
//! [`var_os`]: both take out what lies inside `$APPDIR`. Outside an
//! AppImage (no `APPDIR`) they are `Command::new` and `std::env::var_os`.

use std::ffi::{OsStr, OsString};
use std::path::{Path, PathBuf};
use std::process::Command;

/// Variables an AppImage's `AppRun` (or its GTK hook) sets to values that do
/// not name a path inside the AppImage, so [`clean`] cannot tell them apart.
const APPIMAGE_ONLY: &[&str] = &["PYTHONDONTWRITEBYTECODE", "GTK_THEME"];

/// `Command::new(program)`, with an AppImage's additions taken out of the
/// environment it inherits. Set variables on it afterwards as usual; they win.
pub fn command(program: impl AsRef<OsStr>) -> Command {
    let mut cmd = Command::new(program);
    if let Some(appdir) = appdir() {
        for (name, value) in std::env::vars_os() {
            if APPIMAGE_ONLY.iter().any(|n| OsStr::new(n) == name) {
                cmd.env_remove(&name);
                continue;
            }
            match clean(&appdir, &value) {
                Cleaned::Unchanged => {}
                Cleaned::Changed(v) => {
                    cmd.env(&name, v);
                }
                Cleaned::Empty => {
                    cmd.env_remove(&name);
                }
            }
        }
    }
    cmd
}

/// This process's `name`, without the entries an AppImage put in it: what a
/// host program would inherit through [`command`].
pub fn var_os(name: &str) -> Option<OsString> {
    let value = std::env::var_os(name)?;
    let Some(appdir) = appdir() else {
        return Some(value);
    };
    if APPIMAGE_ONLY.contains(&name) {
        return None;
    }
    match clean(&appdir, &value) {
        Cleaned::Unchanged => Some(value),
        Cleaned::Changed(v) => Some(v),
        Cleaned::Empty => None,
    }
}

/// The AppImage's mount point, when this process runs from one. A relative
/// path or `/` would match too much, so neither counts.
fn appdir() -> Option<PathBuf> {
    let dir = PathBuf::from(std::env::var_os("APPDIR")?);
    (dir.is_absolute() && dir.parent().is_some()).then_some(dir)
}

#[derive(Debug, PartialEq, Eq)]
enum Cleaned {
    Unchanged,
    Changed(OsString),
    /// Every entry was inside the AppImage: the variable should not be set.
    Empty,
}

/// `value` as a `:`-separated list without its entries inside `appdir`. Once
/// one is taken out, empty entries go too: `AppRun` appends the original
/// value after a `:`, which leaves a trailing empty entry when there was
/// none.
fn clean(appdir: &Path, value: &OsStr) -> Cleaned {
    let text = value.to_string_lossy();
    let entries: Vec<&str> = text.split(':').collect();
    if !entries.iter().any(|e| inside(appdir, e)) {
        return Cleaned::Unchanged;
    }
    let kept: Vec<&str> = entries
        .into_iter()
        .filter(|e| !e.is_empty() && !inside(appdir, e))
        .collect();
    if kept.is_empty() {
        Cleaned::Empty
    } else {
        Cleaned::Changed(kept.join(":").into())
    }
}

fn inside(appdir: &Path, entry: &str) -> bool {
    !entry.is_empty() && Path::new(entry).starts_with(appdir)
}

#[cfg(test)]
mod tests {
    use super::*;

    const APPDIR: &str = "/run/appimage/.mount_HaskilXYZ";

    fn c(value: &str) -> Cleaned {
        clean(Path::new(APPDIR), OsStr::new(value))
    }

    #[test]
    fn a_value_wholly_inside_the_appimage_is_dropped() {
        assert_eq!(c("/run/appimage/.mount_HaskilXYZ/usr/"), Cleaned::Empty);
        // AppRun's PYTHONPATH with nothing of the host's after it.
        assert_eq!(
            c("/run/appimage/.mount_HaskilXYZ/usr/share/pyshared/:"),
            Cleaned::Empty
        );
    }

    #[test]
    fn the_hosts_entries_are_kept_in_order() {
        assert_eq!(
            c("/run/appimage/.mount_HaskilXYZ/usr/bin/:/run/appimage/.mount_HaskilXYZ/bin/:/usr/local/bin:/usr/bin"),
            Cleaned::Changed("/usr/local/bin:/usr/bin".into())
        );
        assert_eq!(
            c("/run/appimage/.mount_HaskilXYZ/usr/share:/usr/share:/usr/local/share"),
            Cleaned::Changed("/usr/share:/usr/local/share".into())
        );
    }

    #[test]
    fn values_outside_the_appimage_are_untouched() {
        assert_eq!(c("/usr/bin:/bin"), Cleaned::Unchanged);
        assert_eq!(c("/usr/bin::/bin"), Cleaned::Unchanged);
        assert_eq!(c("Adwaita:light"), Cleaned::Unchanged);
        assert_eq!(c(""), Cleaned::Unchanged);
        // A sibling whose name only starts the same is not inside it.
        assert_eq!(c("/run/appimage/.mount_HaskilXYZ2/lib"), Cleaned::Unchanged);
    }
}
