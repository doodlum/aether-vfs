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

/// With `VFS_FIXTURE_DLL_BACKING` and `VFS_FIXTURE_DLL_LOCATION` set (one DLL
/// named by its path under a root's backing directory and under the root's
/// location), loads it by both and adds `dll_loaded` (both loads succeeded),
/// `dll_same` (they returned one module) and `dll_path` (that module's
/// `GetModuleFileNameW`), each `1`/`0` or a path.
#[cfg(windows)]
fn dll_facts(text: &mut String) {
    use std::os::windows::ffi::OsStrExt;
    use windows_sys::Win32::System::LibraryLoader::{GetModuleFileNameW, LoadLibraryW};
    let (Some(backing), Some(location)) = (
        std::env::var_os("VFS_FIXTURE_DLL_BACKING"),
        std::env::var_os("VFS_FIXTURE_DLL_LOCATION"),
    ) else {
        return;
    };
    let wide = |s: &std::ffi::OsStr| s.encode_wide().chain(Some(0)).collect::<Vec<u16>>();
    // SAFETY: NUL-terminated wide paths; loading a test DLL with no initialisation of note.
    let (by_backing, by_location) = unsafe {
        (
            LoadLibraryW(wide(&backing).as_ptr()),
            LoadLibraryW(wide(&location).as_ptr()),
        )
    };
    let mut buf = [0u16; 1024];
    // SAFETY: a module handle (or null, which names the executable) and a buffer of its length.
    let n = unsafe { GetModuleFileNameW(by_backing, buf.as_mut_ptr(), buf.len() as u32) } as usize;
    text.push_str(&format!(
        "dll_loaded {}\ndll_same {}\ndll_path {}\n",
        u8::from(!by_backing.is_null() && !by_location.is_null()),
        u8::from(by_backing == by_location),
        String::from_utf16_lossy(&buf[..n])
    ));
}

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
    #[cfg(windows)]
    dll_facts(&mut text);
    let written = std::fs::File::create(&out).and_then(|mut f| f.write_all(text.as_bytes()));
    if let Err(e) = written {
        eprintln!("FIXTURE FAIL: probe write {}: {e}", out.to_string_lossy());
        exit(1);
    }
    if std::env::var_os("VFS_FIXTURE_PATH").is_none() {
        exit(0);
    }
}
