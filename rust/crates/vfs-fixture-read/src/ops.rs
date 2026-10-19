//! The ops phase: a script of filesystem operations, and what each did.
//!
//! With `VFS_FIXTURE_OPS` naming a script file, the fixture runs it before
//! anything else and writes one result line per operation to
//! `VFS_FIXTURE_OPS_OUT`, then exits 0 (unless `VFS_FIXTURE_PATH` is set too,
//! when it goes on to the read). Every operation goes through `std::fs`, so
//! the injected shim's hooks see it as a program's ordinary file access.
//!
//! Script lines are tab-separated, `#` starts a comment:
//!
//! | line                    | does                                   | result            |
//! |-------------------------|----------------------------------------|-------------------|
//! | `list\tDIR`             | `read_dir`                             | `ok a\|b\|c` (sorted) |
//! | `read\tFILE`            | `read_to_string`                       | `ok <content>`    |
//! | `write\tFILE\tTEXT`     | `write`                                | `ok`              |
//! | `mkdir\tDIR`            | `create_dir`                           | `ok`              |
//! | `mkdirs\tDIR`           | `create_dir_all` (a folder chain)      | `ok`              |
//! | `rename\tFROM\tTO`      | `rename`                               | `ok`              |
//! | `stat\tPATH`            | `metadata`                             | `ok dir` / `ok file <len>` |
//! | `delete\tPATH`          | `remove_file`, or `remove_dir` for a directory | `ok`      |
//!
//! A failure is `err <ErrorKind> <raw os error>`. Each result line is the
//! operation's line, a tab, and the result.

use std::io::Write;
use std::process::exit;

/// Called first. Returns only when the ops phase is not asked for, or when a
/// read follows it.
pub fn run() {
    let Some(script) = std::env::var_os("VFS_FIXTURE_OPS") else {
        return;
    };
    let Some(out) = std::env::var_os("VFS_FIXTURE_OPS_OUT") else {
        eprintln!("FIXTURE FAIL: VFS_FIXTURE_OPS without VFS_FIXTURE_OPS_OUT");
        exit(2);
    };
    let text = match std::fs::read_to_string(&script) {
        Ok(t) => t,
        Err(e) => {
            eprintln!("FIXTURE FAIL: ops script: {e}");
            exit(2);
        }
    };
    let mut results = String::new();
    for line in text.lines() {
        let line = line.trim_end_matches('\r');
        if line.trim().is_empty() || line.starts_with('#') {
            continue;
        }
        let f: Vec<&str> = line.split('\t').collect();
        results.push_str(line);
        results.push('\t');
        results.push_str(&one(&f));
        results.push('\n');
    }
    let written = std::fs::File::create(&out).and_then(|mut f| f.write_all(results.as_bytes()));
    if let Err(e) = written {
        eprintln!("FIXTURE FAIL: ops output: {e}");
        exit(1);
    }
    if std::env::var_os("VFS_FIXTURE_PATH").is_none() {
        exit(0);
    }
}

/// One operation's result.
fn one(f: &[&str]) -> String {
    let err = |e: std::io::Error| format!("err {:?} {}", e.kind(), e.raw_os_error().unwrap_or(0));
    let arg = |i: usize| f.get(i).copied().unwrap_or("");
    match f.first().copied().unwrap_or("") {
        "list" => match std::fs::read_dir(arg(1)) {
            Ok(rd) => {
                let mut names: Vec<String> = rd
                    .flatten()
                    .map(|e| e.file_name().to_string_lossy().into_owned())
                    .collect();
                names.sort_by_key(|n| n.to_lowercase());
                format!("ok {}", names.join("|"))
            }
            Err(e) => err(e),
        },
        "read" => std::fs::read_to_string(arg(1)).map_or_else(err, |t| format!("ok {t}")),
        "write" => std::fs::write(arg(1), arg(2)).map_or_else(err, |()| "ok".into()),
        "mkdir" => std::fs::create_dir(arg(1)).map_or_else(err, |()| "ok".into()),
        "mkdirs" => std::fs::create_dir_all(arg(1)).map_or_else(err, |()| "ok".into()),
        "rename" => std::fs::rename(arg(1), arg(2)).map_or_else(err, |()| "ok".into()),
        "stat" => match std::fs::metadata(arg(1)) {
            Ok(m) if m.is_dir() => "ok dir".into(),
            Ok(m) => format!("ok file {}", m.len()),
            Err(e) => err(e),
        },
        "delete" => {
            let p = arg(1);
            let r = match std::fs::metadata(p) {
                Ok(m) if m.is_dir() => std::fs::remove_dir(p),
                _ => std::fs::remove_file(p),
            };
            r.map_or_else(err, |()| "ok".into())
        }
        other => format!("err unknown-op {other}"),
    }
}
