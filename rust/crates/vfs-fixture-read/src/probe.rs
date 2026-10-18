//! The probe phase: what the process believes about itself.
//!
//! With `VFS_FIXTURE_PROBE_OUT` set, the fixture writes one line per fact to
//! that file before anything else — `env NAME=VALUE` for each environment
//! variable, then `cwd`, `exe` (`GetModuleFileNameW(NULL)`, via
//! `current_exe`) and `argv0` — and exits 0, unless `VFS_FIXTURE_PATH` is set
//! too, in which case it goes on to the read. The host names a file outside
//! every root, so the write lands on real disk where the test can read it.

use std::io::Write;
use std::process::exit;

/// Called first. Returns only when the probe phase is not asked for, or when
/// a read follows it.
pub fn run() {
    let Some(out) = std::env::var_os("VFS_FIXTURE_PROBE_OUT") else {
        return;
    };
    let mut text = String::new();
    for (k, v) in std::env::vars_os() {
        text.push_str(&format!(
            "env {}={}\n",
            k.to_string_lossy(),
            v.to_string_lossy()
        ));
    }
    let cwd = std::env::current_dir()
        .map(|p| p.to_string_lossy().into_owned())
        .unwrap_or_else(|e| format!("<error {e}>"));
    let exe = std::env::current_exe()
        .map(|p| p.to_string_lossy().into_owned())
        .unwrap_or_else(|e| format!("<error {e}>"));
    let argv0 = std::env::args_os()
        .next()
        .map(|a| a.to_string_lossy().into_owned())
        .unwrap_or_default();
    text.push_str(&format!("cwd {cwd}\nexe {exe}\nargv0 {argv0}\n"));
    let written = std::fs::File::create(&out).and_then(|mut f| f.write_all(text.as_bytes()));
    if let Err(e) = written {
        eprintln!("FIXTURE FAIL: probe write {}: {e}", out.to_string_lossy());
        exit(1);
    }
    if std::env::var_os("VFS_FIXTURE_PATH").is_none() {
        exit(0);
    }
}
