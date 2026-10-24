//! CLI argument parsing for the `vfs-injector` binary, extracted here so it's
//! unit-testable (a `[[bin]]` target isn't importable by integration tests).

/// What `vfs-injector` needs from its command line.
#[derive(Debug, PartialEq, Eq)]
pub struct InjectorArgs {
    pub target: String,
    pub shim_dll: String,
    pub config: String,
    pub ready: String,
    pub target_args: Vec<String>,
}

/// The first argument that makes `vfs-injector` load a DLL into a running,
/// suspended process instead of launching one: `vfs-injector --load-library
/// <pid> <dll> <timeout_ms>`. A 64-bit launch runs the 32-bit build of it for a
/// WOW64 guest, whose `LoadLibraryW` only a 32-bit process can look up.
pub const LOAD_LIBRARY_FLAG: &str = "--load-library";

/// What `vfs-injector --load-library` needs.
#[derive(Debug, PartialEq, Eq)]
pub struct LoadLibraryArgs {
    pub pid: u32,
    pub dll: String,
    pub timeout_ms: u32,
}

/// `Some` when argv asks for `--load-library`: its arguments, or `Err(usage)`.
pub fn parse_load_library_args(a: &[String]) -> Option<Result<LoadLibraryArgs, String>> {
    if a.get(1).map(String::as_str) != Some(LOAD_LIBRARY_FLAG) {
        return None;
    }
    let usage = || format!("usage: vfs-injector {LOAD_LIBRARY_FLAG} <pid> <dll> <timeout_ms>");
    Some(match a {
        [_, _, pid, dll, timeout] => match (pid.parse(), timeout.parse()) {
            (Ok(pid), Ok(timeout_ms)) => Ok(LoadLibraryArgs {
                pid,
                dll: dll.clone(),
                timeout_ms,
            }),
            _ => Err(usage()),
        },
        _ => Err(usage()),
    })
}

/// The one line `--load-library` prints: `loaded:<thread exit code>` or
/// `failed:<error>`.
pub fn load_library_report(result: &Result<u32, crate::InjectError>) -> String {
    match result {
        Ok(code) => format!("loaded:{code:#x}"),
        Err(e) => format!("failed:{e:?}"),
    }
}

/// [`load_library_report`]'s line read back: the thread's exit code, or the
/// error it names (one it cannot name is [`crate::InjectError::Wow64Helper`]).
pub fn parse_load_library_report(line: &str) -> Result<u32, crate::InjectError> {
    use crate::InjectError as E;
    let line = line.trim();
    if let Some(code) = line.strip_prefix("loaded:") {
        let hex = code.trim_start_matches("0x");
        return u32::from_str_radix(hex, 16)
            .map_err(|_| E::Wow64Helper(format!("unreadable report {line:?}")));
    }
    match line.strip_prefix("failed:") {
        Some("Alloc") => Err(E::Alloc),
        Some("Write") => Err(E::Write),
        Some("RemoteThread") => Err(E::RemoteThread),
        Some("Timeout") => Err(E::Timeout),
        Some(other) => Err(E::Wow64Helper(other.to_string())),
        None => Err(E::Wow64Helper(format!("unreadable report {line:?}"))),
    }
}

/// Parse argv into [`InjectorArgs`], or `Err(usage)`.
pub fn parse_injector_args(a: &[String]) -> Result<InjectorArgs, String> {
    if a.len() < 5 {
        return Err("usage: vfs-injector <target> <shim_dll> <config> <ready> [-- args...]".into());
    }
    let target_args = if a.len() > 5 && a[5] == "--" {
        a[6..].to_vec()
    } else {
        a[5..].to_vec()
    };
    Ok(InjectorArgs {
        target: a[1].clone(),
        shim_dll: a[2].clone(),
        config: a[3].clone(),
        ready: a[4].clone(),
        target_args,
    })
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::InjectError;

    fn argv(a: &[&str]) -> Vec<String> {
        a.iter().map(|s| s.to_string()).collect()
    }

    #[test]
    fn load_library_is_its_own_command_line() {
        let a = argv(&[
            "vfs-injector",
            "--load-library",
            "1234",
            r"C:\x86\vfs_shim_dll.dll",
            "500",
        ]);
        assert_eq!(
            parse_load_library_args(&a),
            Some(Ok(LoadLibraryArgs {
                pid: 1234,
                dll: r"C:\x86\vfs_shim_dll.dll".into(),
                timeout_ms: 500
            }))
        );
        assert!(matches!(
            parse_load_library_args(&argv(&["vfs-injector", "--load-library", "x", "d", "1"])),
            Some(Err(_))
        ));
        assert!(matches!(
            parse_load_library_args(&argv(&["vfs-injector", "--load-library", "1"])),
            Some(Err(_))
        ));
        // A launch's argv is not a `--load-library` one.
        assert_eq!(
            parse_load_library_args(&argv(&["vfs-injector", "game.exe", "s", "c", "r"])),
            None
        );
    }

    #[test]
    fn the_report_line_round_trips() {
        assert_eq!(load_library_report(&Ok(0x7400_0000)), "loaded:0x74000000");
        assert_eq!(
            parse_load_library_report("loaded:0x74000000\r\n").unwrap(),
            0x7400_0000
        );
        for e in [
            InjectError::Alloc,
            InjectError::Write,
            InjectError::RemoteThread,
            InjectError::Timeout,
        ] {
            let line = load_library_report(&Err(e));
            let back = format!("{:?}", parse_load_library_report(&line).unwrap_err());
            assert_eq!(line, format!("failed:{back}"));
        }
        assert!(matches!(
            parse_load_library_report(""),
            Err(InjectError::Wow64Helper(_))
        ));
        assert!(matches!(
            parse_load_library_report("failed:Ntdll"),
            Err(InjectError::Wow64Helper(m)) if m == "Ntdll"
        ));
    }
}
