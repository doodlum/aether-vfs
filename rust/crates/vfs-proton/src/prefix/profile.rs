//! The prefix user's profile and known folders, as the programs in the
//! prefix see them.
//!
//! A game keeps its saves and settings under the user's Documents, Local
//! AppData or Saved Games, and a host that wants to serve them declares roots
//! there. Where those are depends on the Wine that made the prefix, not on
//! anything the host knows: GE-Proton's Wine names its user `steamuser`
//! whatever the Unix user is (in a [`PrefixInit::Proton`] prefix and a
//! `wineboot` one alike), and other Wines use their own names. So the answer
//! is read from the prefix itself, the way Wine's `shell32` answers
//! `SHGetKnownFolderPath`:
//!
//! 1. the profile is the `ProfileImagePath` of the user's `ProfileList` entry
//!    in `system.reg` (`C:\users\steamuser`);
//! 2. a known folder is its `User Shell Folders` value in `user.reg`
//!    (`%USERPROFILE%\Documents`), with the profile put in.
//!
//! Wine writes its registry back to those files lazily, so each step falls
//! back to what Wine creates when the files do not say yet: the one user
//! directory under `drive_c/users` besides `Public`, and the folder's
//! default place under the profile.
//!
//! [`PrefixInit::Proton`]: super::PrefixInit::Proton

use std::path::Path;

use super::{Prefix, PrefixError};

/// A per-user folder a game keeps its files in.
#[derive(Debug, Clone, Copy, PartialEq, Eq, Hash)]
#[non_exhaustive]
pub enum KnownFolder {
    /// `FOLDERID_Documents` (`CSIDL_PERSONAL`): `…\Documents`.
    Documents,
    /// `FOLDERID_LocalAppData` (`CSIDL_LOCAL_APPDATA`): `…\AppData\Local`.
    LocalAppData,
    /// `FOLDERID_SavedGames`: `…\Saved Games`.
    SavedGames,
}

impl KnownFolder {
    /// The folder's value name under `User Shell Folders`.
    fn shell_folder_value(self) -> &'static str {
        match self {
            KnownFolder::Documents => "Personal",
            KnownFolder::LocalAppData => "Local AppData",
            KnownFolder::SavedGames => "{4C5C32FF-BB9D-43B0-B5B4-2D72E54EAAA4}",
        }
    }

    /// Where Windows and Wine put the folder under the profile by default.
    fn default_under_profile(self) -> &'static str {
        match self {
            KnownFolder::Documents => "Documents",
            KnownFolder::LocalAppData => r"AppData\Local",
            KnownFolder::SavedGames => "Saved Games",
        }
    }
}

/// `HKLM` key (in `system.reg`) whose subkeys are the user profiles.
const PROFILE_LIST: &str = r"Software\Microsoft\Windows NT\CurrentVersion\ProfileList";

/// `HKCU` key (in `user.reg`) naming each known folder, unexpanded.
const USER_SHELL_FOLDERS: &str =
    r"Software\Microsoft\Windows\CurrentVersion\Explorer\User Shell Folders";

/// Directories under `drive_c/users` that are not a user's profile.
const NOT_A_PROFILE: [&str; 4] = ["Public", "Default", "Default User", "All Users"];

impl Prefix {
    /// The prefix user's profile directory as a program in the prefix sees it:
    /// `C:\users\steamuser` in a GE-Proton prefix. Read from the prefix (see
    /// the module docs), so the prefix must have been set up.
    pub fn user_profile(&self) -> Result<String, PrefixError> {
        let bad = |why: String| PrefixError::UserProfile(format!("{}: {why}", self.dir.display()));
        let system = read_optional(&self.dir.join("system.reg")).map_err(|e| bad(e.to_string()))?;
        let mut found: Vec<String> = Vec::new();
        for (key, values) in reg_sections(&system) {
            let Some(sid) = strip_prefix_ci(&key, PROFILE_LIST).and_then(|r| r.strip_prefix('\\'))
            else {
                continue;
            };
            // The interactive user: S-1-5-21-…; well-known service accounts
            // (S-1-5-18 and friends) are not who runs a game.
            if !sid.to_ascii_uppercase().starts_with("S-1-5-21-") || sid.contains('\\') {
                continue;
            }
            if let Some((_, path)) = values
                .iter()
                .find(|(n, _)| n.eq_ignore_ascii_case("ProfileImagePath"))
            {
                if !found.iter().any(|f| f.eq_ignore_ascii_case(path)) {
                    found.push(path.clone());
                }
            }
        }
        match found.len() {
            1 => return Ok(found.remove(0)),
            0 => {}
            _ => {
                return Err(bad(format!(
                    "system.reg lists more than one user profile ({}); which one a program \
                     runs as cannot be told from the prefix",
                    found.join(", ")
                )))
            }
        }
        // Not written back yet: the one user directory Wine made.
        let users = self.drive_c().join("users");
        let mut names: Vec<String> = Vec::new();
        let entries = std::fs::read_dir(&users).map_err(|e| {
            bad(format!(
                "no user profile in system.reg, and {} cannot be read ({e}); has the prefix \
                 been set up?",
                users.display()
            ))
        })?;
        for e in entries.flatten() {
            let name = e.file_name().to_string_lossy().into_owned();
            let is_dir = e.file_type().is_ok_and(|t| t.is_dir());
            if is_dir && !NOT_A_PROFILE.iter().any(|n| n.eq_ignore_ascii_case(&name)) {
                names.push(name);
            }
        }
        names.sort();
        match names.as_slice() {
            [one] => Ok(format!(r"C:\users\{one}")),
            [] => Err(bad(format!(
                "no user profile in system.reg and no user directory in {}",
                users.display()
            ))),
            many => Err(bad(format!(
                "no user profile in system.reg and several user directories in {} ({}); which \
                 one a program runs as cannot be told",
                users.display(),
                many.join(", ")
            ))),
        }
    }

    /// `folder` for the prefix's user as a program in the prefix sees it:
    /// `C:\users\steamuser\Documents` in a GE-Proton prefix. A host declares a
    /// root at the game's own subfolder of it (`…\Documents\My Games\Skyrim
    /// Special Edition`) and never spells the user's name itself. Read from
    /// the prefix (see the module docs), so the prefix must have been set up.
    pub fn known_folder(&self, folder: KnownFolder) -> Result<String, PrefixError> {
        let profile = self.user_profile()?;
        let bad = |why: String| PrefixError::UserProfile(format!("{}: {why}", self.dir.display()));
        let user = read_optional(&self.dir.join("user.reg")).map_err(|e| bad(e.to_string()))?;
        let configured = reg_sections(&user)
            .into_iter()
            .find(|(k, _)| k.eq_ignore_ascii_case(USER_SHELL_FOLDERS))
            .and_then(|(_, values)| {
                values
                    .into_iter()
                    .find(|(n, _)| n.eq_ignore_ascii_case(folder.shell_folder_value()))
                    .map(|(_, v)| v)
            });
        match configured {
            Some(v) => expand(&v, &profile).map_err(bad),
            None => Ok(format!(r"{profile}\{}", folder.default_under_profile())),
        }
    }
}

/// A `.reg` file's text, or empty when there is none.
fn read_optional(path: &Path) -> std::io::Result<String> {
    match std::fs::read(path) {
        // Wine writes these files in ASCII, escaping anything else.
        Ok(bytes) => Ok(String::from_utf8_lossy(&bytes).into_owned()),
        Err(e) if e.kind() == std::io::ErrorKind::NotFound => Ok(String::new()),
        Err(e) => Err(e),
    }
}

/// `s` without a leading `prefix`, compared ASCII case-insensitively.
fn strip_prefix_ci<'a>(s: &'a str, prefix: &str) -> Option<&'a str> {
    (s.len() >= prefix.len()
        && s.is_char_boundary(prefix.len())
        && s[..prefix.len()].eq_ignore_ascii_case(prefix))
    .then(|| &s[prefix.len()..])
}

/// Replaces the environment variables Wine's default folders use:
/// `%USERPROFILE%`, `%SystemDrive%`, `%SystemRoot%` and `%windir%`. Any other
/// is an error: the result would not be the folder a program sees.
fn expand(value: &str, profile: &str) -> Result<String, String> {
    let mut out = String::new();
    let mut rest = value;
    while let Some(start) = rest.find('%') {
        out.push_str(&rest[..start]);
        let after = &rest[start + 1..];
        let end = after
            .find('%')
            .ok_or_else(|| format!("unbalanced % in the folder {value:?}"))?;
        let name = &after[..end];
        let with = if name.eq_ignore_ascii_case("USERPROFILE") {
            profile
        } else if name.eq_ignore_ascii_case("SystemDrive") {
            "C:"
        } else if name.eq_ignore_ascii_case("SystemRoot") || name.eq_ignore_ascii_case("windir") {
            r"C:\windows"
        } else {
            return Err(format!(
                "the folder {value:?} names %{name}%, which aether-vfs does not expand"
            ));
        };
        out.push_str(with);
        rest = &after[end + 1..];
    }
    out.push_str(rest);
    Ok(out)
}

/// Every key in a Wine `.reg` file, unescaped, with its string values
/// (`"name"="value"`, `"name"=str(N):"value"`), unescaped. Other value types,
/// and the default value (`@=`), are skipped.
fn reg_sections(text: &str) -> Vec<(String, Vec<(String, String)>)> {
    let mut out: Vec<(String, Vec<(String, String)>)> = Vec::new();
    for line in text.lines() {
        if let Some(header) = line.strip_prefix('[') {
            // `[key\\path] <timestamp>`; the key itself may contain `]` only
            // escaped, so the last `]` closes it.
            if let Some(end) = header.rfind(']') {
                out.push((unescape(&header[..end]), Vec::new()));
            }
            continue;
        }
        let (Some(section), Some(rest)) = (out.last_mut(), line.strip_prefix('"')) else {
            continue;
        };
        let Some((name, after)) = split_quoted(rest) else {
            continue;
        };
        let Some(after) = after.strip_prefix('=') else {
            continue;
        };
        let after = match after.strip_prefix("str(") {
            Some(typed) => match typed.split_once("):") {
                Some((_, v)) => v,
                None => continue,
            },
            None => after,
        };
        let Some((value, _)) = after.strip_prefix('"').and_then(split_quoted) else {
            continue;
        };
        section.1.push((unescape(name), unescape(value)));
    }
    out
}

/// Splits `s` (just after an opening `"`) at its closing, unescaped `"`:
/// the quoted text still escaped, and what follows the quote.
fn split_quoted(s: &str) -> Option<(&str, &str)> {
    let mut escaped = false;
    for (i, c) in s.char_indices() {
        match c {
            _ if escaped => escaped = false,
            '\\' => escaped = true,
            '"' => return Some((&s[..i], &s[i + 1..])),
            _ => {}
        }
    }
    None
}

/// Undoes Wine's `.reg` escaping: `\\`, `\"`, `\n`, `\r`, `\t`, `\0` and
/// `\xHHHH` (one to four hex digits, a UTF-16 unit).
fn unescape(s: &str) -> String {
    let mut units: Vec<u16> = Vec::with_capacity(s.len());
    let mut chars = s.chars().peekable();
    while let Some(c) = chars.next() {
        if c != '\\' {
            let mut buf = [0u16; 2];
            units.extend_from_slice(c.encode_utf16(&mut buf));
            continue;
        }
        match chars.next() {
            Some('n') => units.push(u16::from(b'\n')),
            Some('r') => units.push(u16::from(b'\r')),
            Some('t') => units.push(u16::from(b'\t')),
            Some('0') => units.push(0),
            Some('x') => {
                let mut v: u32 = 0;
                let mut digits = 0;
                while digits < 4 {
                    match chars.peek().and_then(|d| d.to_digit(16)) {
                        Some(d) => {
                            v = v * 16 + d;
                            chars.next();
                            digits += 1;
                        }
                        None => break,
                    }
                }
                units.push(v as u16);
            }
            Some(other) => {
                let mut buf = [0u16; 2];
                units.extend_from_slice(other.encode_utf16(&mut buf));
            }
            None => units.push(u16::from(b'\\')),
        }
    }
    String::from_utf16_lossy(&units)
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::prefix::scratch;

    /// The parts of a GE-Proton 11 prefix's registry this reads, as Wine
    /// writes them (a `wineboot` prefix's SID is random; Proton's is fixed).
    const SYSTEM_REG: &str = r#"WINE REGISTRY Version 2
;; All keys relative to \\Machine

[Software\\Microsoft\\Windows NT\\CurrentVersion\\ProfileList] 1789520096
#time=1dd4575fe20f6d2
"ProfilesDirectory"=str(2):"C:\\users"
"Public"=str(2):"C:\\users\\Public"

[Software\\Microsoft\\Windows NT\\CurrentVersion\\ProfileList\\S-1-5-18] 1789520096
"ProfileImagePath"=str(2):"%systemroot%\\system32\\config\\systemprofile"

[Software\\Microsoft\\Windows NT\\CurrentVersion\\ProfileList\\S-1-5-21-0-0-0-1000] 1789520096
#time=1dd4575fe21164e
"Flags"=dword:00000000
"ProfileImagePath"="C:\\users\\steamuser"
"#;

    const USER_REG: &str = r#"WINE REGISTRY Version 2
;; All keys relative to \\User\\S-1-5-21-0-0-0-1000

[Software\\Microsoft\\Windows\\CurrentVersion\\Explorer\\User Shell Folders] 1789520095
#time=1dd4575fd70d1c6
"__WineVistaPaths"=dword:00000001
"Local AppData"=str(2):"%USERPROFILE%\\AppData\\Local"
"Personal"=str(2):"%USERPROFILE%\\Documents"
"{4C5C32FF-BB9D-43B0-B5B4-2D72E54EAAA4}"=str(2):"%USERPROFILE%\\Saved Games"
"#;

    fn prefix(tag: &str, system: Option<&str>, user: Option<&str>, users: &[&str]) -> Prefix {
        let p = Prefix { dir: scratch(tag) };
        if let Some(s) = system {
            std::fs::write(p.dir.join("system.reg"), s).unwrap();
        }
        if let Some(u) = user {
            std::fs::write(p.dir.join("user.reg"), u).unwrap();
        }
        for d in users {
            std::fs::create_dir_all(p.drive_c().join("users").join(d)).unwrap();
        }
        p
    }

    #[test]
    fn a_proton_prefixs_folders_are_steamusers() {
        let p = prefix(
            "kf-proton",
            Some(SYSTEM_REG),
            Some(USER_REG),
            &["Public", "steamuser"],
        );
        assert_eq!(p.user_profile().unwrap(), r"C:\users\steamuser");
        assert_eq!(
            p.known_folder(KnownFolder::Documents).unwrap(),
            r"C:\users\steamuser\Documents"
        );
        assert_eq!(
            p.known_folder(KnownFolder::LocalAppData).unwrap(),
            r"C:\users\steamuser\AppData\Local"
        );
        assert_eq!(
            p.known_folder(KnownFolder::SavedGames).unwrap(),
            r"C:\users\steamuser\Saved Games"
        );
    }

    /// The user is whoever the prefix says, never the Unix user or a name
    /// this crate assumes; and a moved folder is where the registry says.
    #[test]
    fn the_user_and_the_folders_come_from_the_prefix() {
        let system = SYSTEM_REG.replace(r"users\\steamuser", r"users\\crossover");
        let user = USER_REG.replace(
            r#""%USERPROFILE%\\Documents""#,
            r#""%SystemDrive%\\users\\crossover\\My Documents""#,
        );
        let p = prefix("kf-other", Some(&system), Some(&user), &["crossover"]);
        assert_eq!(p.user_profile().unwrap(), r"C:\users\crossover");
        assert_eq!(
            p.known_folder(KnownFolder::Documents).unwrap(),
            r"C:\users\crossover\My Documents"
        );
    }

    #[test]
    fn before_wine_writes_its_registry_back_the_user_directory_is_used() {
        let p = prefix("kf-dirs", None, None, &["Public", "tim"]);
        assert_eq!(p.user_profile().unwrap(), r"C:\users\tim");
        assert_eq!(
            p.known_folder(KnownFolder::SavedGames).unwrap(),
            r"C:\users\tim\Saved Games"
        );
        assert_eq!(
            p.known_folder(KnownFolder::LocalAppData).unwrap(),
            r"C:\users\tim\AppData\Local"
        );
    }

    #[test]
    fn an_ambiguous_or_missing_profile_is_an_error_not_a_guess() {
        let p = prefix("kf-two", None, None, &["Public", "a", "b"]);
        let e = p.user_profile().unwrap_err().to_string();
        assert!(
            e.contains("several user directories") && e.contains("a, b"),
            "{e}"
        );

        let p = prefix("kf-none", None, None, &[]);
        let e = p.known_folder(KnownFolder::Documents).unwrap_err();
        assert!(matches!(e, PrefixError::UserProfile(_)), "{e}");

        let two = format!(
            "{SYSTEM_REG}\n[Software\\\\Microsoft\\\\Windows NT\\\\CurrentVersion\\\\ProfileList\\\\S-1-5-21-1-2-3-1001] 1\n\"ProfileImagePath\"=\"C:\\\\users\\\\other\"\n"
        );
        let p = prefix("kf-two-reg", Some(&two), None, &[]);
        let e = p.user_profile().unwrap_err().to_string();
        assert!(e.contains("more than one user profile"), "{e}");
    }

    #[test]
    fn a_folder_naming_a_variable_this_cannot_expand_is_refused() {
        let user = USER_REG.replace("%USERPROFILE%\\\\Documents", "%ONEDRIVE%\\\\Documents");
        let p = prefix("kf-var", Some(SYSTEM_REG), Some(&user), &[]);
        let e = p
            .known_folder(KnownFolder::Documents)
            .unwrap_err()
            .to_string();
        assert!(e.contains("%ONEDRIVE%"), "{e}");
        // The others are unaffected.
        assert_eq!(
            p.known_folder(KnownFolder::SavedGames).unwrap(),
            r"C:\users\steamuser\Saved Games"
        );
    }

    #[test]
    fn reg_escapes_are_undone() {
        assert_eq!(unescape(r"C:\\users\\J\x00f6rg"), r"C:\users\Jörg");
        assert_eq!(unescape(r#"a\"b\\c"#), r#"a"b\c"#);
        let s = reg_sections("[A\\\\B] 1\n\"n\\\"x\"=str(2):\"v\\\\w\"\n\"d\"=dword:1\n@=\"z\"\n");
        assert_eq!(
            s,
            [(
                r"A\B".to_string(),
                vec![(r#"n"x"#.to_string(), r"v\w".to_string())]
            )]
        );
    }
}
