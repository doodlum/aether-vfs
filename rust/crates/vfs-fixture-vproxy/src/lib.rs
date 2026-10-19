//! Stand-in for a game's statically-imported d3d/dxgi proxy DLL.
//! The BACKING copy returns 4242 so the harness can prove the import bound
//! to our redirected image rather than any on-disk file in the app dir.
#![allow(unsafe_code)]

#[no_mangle]
pub extern "C" fn vproxy_value() -> i32 {
    4242
}

/// With `VFS_FIXTURE_VPROXY_NEEDS` naming a file, the load fails (`FALSE`)
/// unless that file is beside the program — the way a game's proxy DLL (ENB's
/// `d3d11.dll` reading `enblocal.ini`) needs the game's files when the loader
/// initialises it, which is before the program's own code runs. A program
/// whose files are only in the VFS passes only if the shim is up by then.
#[no_mangle]
pub extern "system" fn DllMain(
    _module: *mut core::ffi::c_void,
    reason: u32,
    _reserved: *mut core::ffi::c_void,
) -> i32 {
    const DLL_PROCESS_ATTACH: u32 = 1;
    if reason == DLL_PROCESS_ATTACH {
        if let Some(need) = std::env::var_os("VFS_FIXTURE_VPROXY_NEEDS") {
            let here = std::env::current_exe()
                .ok()
                .and_then(|e| e.parent().map(|d| d.join(&need)));
            if !here.is_some_and(|p| p.is_file()) {
                return 0;
            }
        }
    }
    1
}
