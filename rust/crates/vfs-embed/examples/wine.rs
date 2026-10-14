//! A session's Wine prefix, by hand.
//!
//! ```text
//! wine prefix <name>                     set up (or find) the named prefix; print its path
//! wine run <name> <program> [args...]    run a Windows program in it, nothing injected
//! ```
//!
//! The prefix is the one a [`Session`] with `set_prefix_name(<name>)` launches
//! into, under the aether-vfs home (`VFS_HOME`, else the platform default), on
//! this platform's Wine host (CrossOver on macOS, GE-Proton elsewhere). `run`
//! is for what a launch does not do: installers, and clients such as Steam
//! that a game expects to find running in its prefix. A `<program>` that is a
//! host path is passed as one; Wine resolves it through `Z:`.

#[cfg(unix)]
fn main() {
    let args: Vec<String> = std::env::args().skip(1).collect();
    let code = match args
        .iter()
        .map(String::as_str)
        .collect::<Vec<_>>()
        .as_slice()
    {
        ["prefix", name] => prefix(name).map(|p| {
            println!("{}", p.display());
            0
        }),
        ["run", name, program, rest @ ..] => run(name, program, rest),
        _ => {
            eprintln!("usage: wine prefix <name>\n       wine run <name> <program> [args...]");
            Ok(2)
        }
    };
    match code {
        Ok(c) => std::process::exit(c),
        Err(e) => {
            eprintln!("wine: {e}");
            std::process::exit(1)
        }
    }
}

#[cfg(unix)]
fn prefix(name: &str) -> Result<std::path::PathBuf, String> {
    let mut s = vfs_embed::Session::new();
    s.set_prefix_name(name)?;
    s.prepare_prefix()
}

#[cfg(unix)]
fn run(name: &str, program: &str, args: &[&str]) -> Result<i32, String> {
    let prefix = prefix(name)?;
    let root = vfs_embed::proton::Root::from_env().map_err(|e| e.to_string())?;
    let host = vfs_proton::host::default_host(&root)?;
    eprintln!("wine: {} in {}", host.describe(), prefix.display());
    let status = host
        .wine_command(&prefix)
        .arg(program)
        .args(args)
        .status()
        .map_err(|e| format!("{}: {e}", host.wine().display()))?;
    Ok(status.code().unwrap_or(1))
}

#[cfg(not(unix))]
fn main() {
    eprintln!("wine: unix only (on Windows a launch needs no Wine)");
    std::process::exit(2);
}
