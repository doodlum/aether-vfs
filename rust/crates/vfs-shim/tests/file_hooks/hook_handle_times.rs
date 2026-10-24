//! Runs in its own process: every way of asking a served file's times gives the same answer.
//!
//! `GetFileInformationByHandle` reads them through `FileAllInformation`;
//! `GetFileInformationByHandleEx(FileBasicInfo)` through `FileBasicInformation`, and
//! `GetFileAttributesExW` by name. The .NET binder opens a mixed-mode DLL, reads its times both
//! ways and refuses the image when they differ (`COR_E_FILELOAD`): the .NET Script Framework
//! failed to load under Skyrim exactly this way, because `FileAllInformation` reported zero
//! times while `FileBasicInformation` reported the synthetic ones.

use crate::fakedirector;
use fakedirector::{Fake, ReadStyle};
use std::ffi::c_void;
use std::os::windows::io::AsRawHandle;
use vfs_shim::install;
use windows_sys::Win32::Foundation::FILETIME;
use windows_sys::Win32::Storage::FileSystem::{
    FileBasicInfo, GetFileAttributesExW, GetFileExInfoStandard, GetFileInformationByHandle,
    GetFileInformationByHandleEx, BY_HANDLE_FILE_INFORMATION, FILE_BASIC_INFO,
    WIN32_FILE_ATTRIBUTE_DATA,
};

fn wide(s: &str) -> Vec<u16> {
    s.encode_utf16().chain(std::iter::once(0)).collect()
}

fn ft(t: FILETIME) -> i64 {
    ((u64::from(t.dwHighDateTime) << 32) | u64::from(t.dwLowDateTime)) as i64
}

#[test]
fn every_time_query_agrees_for_a_served_file() {
    isolate!();
    let pid = std::process::id();
    let base = std::env::temp_dir().join(format!("vfs-shim-handletimes-{pid}"));
    let _ = std::fs::remove_dir_all(&base);
    let root = base.join("root");
    std::fs::create_dir_all(&root).unwrap();
    let vfile = root.join("Runtime.dll");
    fakedirector::install(
        &root,
        Fake::new().with("Runtime.dll", vec![0u8; 4096], ReadStyle::Whole),
        0,
    );
    let _guard = install().expect("install");

    let f = std::fs::File::open(&vfile).expect("open a served file");
    let h = f.as_raw_handle() as _;

    let mut all: BY_HANDLE_FILE_INFORMATION = unsafe { std::mem::zeroed() };
    assert_ne!(
        unsafe { GetFileInformationByHandle(h, &mut all) },
        0,
        "GetFileInformationByHandle must succeed for a served file"
    );
    let mut basic: FILE_BASIC_INFO = unsafe { std::mem::zeroed() };
    assert_ne!(
        unsafe {
            GetFileInformationByHandleEx(
                h,
                FileBasicInfo,
                &mut basic as *mut _ as *mut c_void,
                std::mem::size_of::<FILE_BASIC_INFO>() as u32,
            )
        },
        0,
        "GetFileInformationByHandleEx(FileBasicInfo) must succeed for a served file"
    );
    let mut data: WIN32_FILE_ATTRIBUTE_DATA = unsafe { std::mem::zeroed() };
    assert_ne!(
        unsafe {
            GetFileAttributesExW(
                wide(vfile.to_str().unwrap()).as_ptr(),
                GetFileExInfoStandard,
                &mut data as *mut _ as *mut c_void,
            )
        },
        0,
        "GetFileAttributesExW must succeed for a served file"
    );

    let by_handle = [
        ft(all.ftCreationTime),
        ft(all.ftLastAccessTime),
        ft(all.ftLastWriteTime),
    ];
    let by_basic = [
        basic.CreationTime,
        basic.LastAccessTime,
        basic.LastWriteTime,
    ];
    let by_name = [
        ft(data.ftCreationTime),
        ft(data.ftLastAccessTime),
        ft(data.ftLastWriteTime),
    ];
    assert!(
        by_basic.iter().all(|&t| t != 0),
        "a served file has times: {by_basic:?}"
    );
    assert_eq!(
        by_handle, by_basic,
        "FileAllInformation (GetFileInformationByHandle) and FileBasicInformation must agree"
    );
    assert_eq!(
        by_name, by_basic,
        "the by-name times must agree with the handle's"
    );

    drop(f);
    let _ = std::fs::remove_dir_all(&base);
}

/// A served file with a time of its own (a mod file, a plugin whose time sets its place in a
/// file-time load order) reports that time every way it can be asked, as a hard link to it did.
#[test]
fn a_served_files_own_time_is_reported_every_way() {
    isolate!();
    let pid = std::process::id();
    let base = std::env::temp_dir().join(format!("vfs-shim-owntime-{pid}"));
    let _ = std::fs::remove_dir_all(&base);
    let root = base.join("root");
    std::fs::create_dir_all(&root).unwrap();
    let vfile = root.join("Plugin.esp");
    // 2006-03-20, Oblivion's release: a time a file-time load order would set.
    const MTIME: i64 = 1_142_812_800;
    let want = vfs_ntlayout::filetime_of(MTIME);
    fakedirector::install(
        &root,
        Fake::new()
            .with("Plugin.esp", vec![0u8; 64], ReadStyle::Whole)
            .with_mtime("Plugin.esp", MTIME),
        0,
    );
    let _guard = install().expect("install");

    let f = std::fs::File::open(&vfile).expect("open a served file");
    let h = f.as_raw_handle() as _;
    let mut all: BY_HANDLE_FILE_INFORMATION = unsafe { std::mem::zeroed() };
    assert_ne!(unsafe { GetFileInformationByHandle(h, &mut all) }, 0);
    let mut basic: FILE_BASIC_INFO = unsafe { std::mem::zeroed() };
    assert_ne!(
        unsafe {
            GetFileInformationByHandleEx(
                h,
                FileBasicInfo,
                &mut basic as *mut _ as *mut c_void,
                std::mem::size_of::<FILE_BASIC_INFO>() as u32,
            )
        },
        0
    );
    let mut data: WIN32_FILE_ATTRIBUTE_DATA = unsafe { std::mem::zeroed() };
    assert_ne!(
        unsafe {
            GetFileAttributesExW(
                wide(vfile.to_str().unwrap()).as_ptr(),
                GetFileExInfoStandard,
                &mut data as *mut _ as *mut c_void,
            )
        },
        0
    );
    assert_eq!(ft(all.ftLastWriteTime), want, "by handle");
    assert_eq!(basic.LastWriteTime, want, "FileBasicInformation");
    assert_eq!(ft(data.ftLastWriteTime), want, "by name");

    // And in a listing of its folder.
    let listed = std::fs::read_dir(&root)
        .expect("list the root")
        .flatten()
        .find(|e| e.file_name().eq_ignore_ascii_case("plugin.esp"))
        .expect("the served file is listed");
    let secs = listed
        .metadata()
        .expect("listed metadata")
        .modified()
        .expect("a time")
        .duration_since(std::time::UNIX_EPOCH)
        .expect("after the epoch")
        .as_secs();
    assert_eq!(secs as i64, MTIME, "in a directory listing");

    drop(f);
    let _ = std::fs::remove_dir_all(&base);
}
