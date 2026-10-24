//! This process's PEB and the fields of it the shim reads: found by `NtQueryInformationProcess`
//! (`ProcessBasicInformation`) once, and read at this target's own layout
//! ([`vfs_inject::Guest::HOST`]), so the 64-bit and the 32-bit shim share one implementation.
//!
//! The PEB does not move for the life of a process, so its address is asked for once and kept.
//! [`warm`] asks during the bootstrap, so no hook ever makes the query itself.
#![allow(unsafe_code)]

use std::sync::OnceLock;

use vfs_inject::Guest;

use crate::ntdef::UnicodeString;

const HOST: Guest = Guest::HOST;

/// The address of this process's PEB, or `None` if the query failed.
pub(crate) fn peb() -> Option<usize> {
    static PEB: OnceLock<Option<usize>> = OnceLock::new();
    *PEB.get_or_init(|| {
        // SAFETY: the current-process pseudo-handle has every right the query needs.
        let me = unsafe { windows_sys::Win32::System::Threading::GetCurrentProcess() };
        // SAFETY: as above.
        unsafe { vfs_inject::peb_of(me, HOST) }
            .ok()
            .map(|p| p as usize)
            .filter(|p| *p != 0)
    })
}

/// Look the PEB up now, so the first hook that needs it does not.
pub(crate) fn warm() {
    let _ = peb();
}

/// A pointer-sized field `off` bytes into the structure at `base`; `None` for a null result.
///
/// # Safety
/// `base + off` is a readable, pointer-aligned field of this process.
unsafe fn ptr_at(base: usize, off: usize) -> Option<usize> {
    // SAFETY: as this function's contract.
    let v = unsafe { *((base + off) as *const usize) };
    (v != 0).then_some(v)
}

/// `PEB.ProcessParameters`.
pub(crate) fn process_parameters() -> Option<usize> {
    // SAFETY: the PEB lives as long as the process; the field is pointer-aligned.
    unsafe { ptr_at(peb()?, HOST.peb_process_parameters()) }
}

/// `PEB.Ldr`.
pub(crate) fn ldr() -> Option<usize> {
    // SAFETY: as `process_parameters`.
    unsafe { ptr_at(peb()?, HOST.peb_ldr()) }
}

/// `PEB.LoaderLock`: the loader's critical section.
pub(crate) fn loader_lock() -> Option<usize> {
    // SAFETY: as `process_parameters`.
    unsafe { ptr_at(peb()?, HOST.peb_loader_lock()) }
}

/// `RTL_USER_PROCESS_PARAMETERS.CurrentDirectory`: its `DosPath` string and its handle.
pub(crate) fn current_directory() -> Option<(*const UnicodeString, isize)> {
    let params = process_parameters()?;
    let dos = (params + HOST.pp_curdir_dos_path()) as *const UnicodeString;
    // SAFETY: the parameter block lives as long as the process; the handle is pointer-aligned.
    let handle = unsafe { *((params + HOST.pp_curdir_handle()) as *const isize) };
    Some((dos, handle))
}

/// `RTL_USER_PROCESS_PARAMETERS.ImagePathName`.
pub(crate) fn image_path_name() -> Option<*mut UnicodeString> {
    Some((process_parameters()? + HOST.pp_image_path_name()) as *mut UnicodeString)
}

/// The head of `PEB_LDR_DATA.InLoadOrderModuleList`.
pub(crate) fn load_order_head() -> Option<usize> {
    Some(ldr()? + HOST.ldr_in_load_order())
}

/// `LDR_DATA_TABLE_ENTRY.FullDllName` of the loader entry at `entry` (its `InLoadOrderLinks`).
pub(crate) fn full_dll_name(entry: usize) -> *mut UnicodeString {
    (entry + HOST.ldr_entry_full_dll_name()) as *mut UnicodeString
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn the_parameters_name_this_image_and_its_directory() {
        let image = image_path_name().expect("ImagePathName");
        // SAFETY: this process's own parameter block.
        let name = unsafe {
            String::from_utf16_lossy(core::slice::from_raw_parts(
                (*image).buffer,
                (*image).length as usize / 2,
            ))
        };
        let exe = std::env::current_exe().unwrap();
        let file = exe.file_name().unwrap().to_string_lossy().to_lowercase();
        assert!(name.to_lowercase().ends_with(&file), "{name}");

        let (dos, handle) = current_directory().expect("CurrentDirectory");
        assert_ne!(handle, 0);
        // SAFETY: as above.
        let cwd = unsafe {
            String::from_utf16_lossy(core::slice::from_raw_parts(
                (*dos).buffer,
                (*dos).length as usize / 2,
            ))
        };
        let real = std::env::current_dir().unwrap();
        assert_eq!(
            cwd.trim_end_matches('\\').to_lowercase(),
            real.to_string_lossy().trim_end_matches('\\').to_lowercase()
        );
    }

    /// The loader list's first entry is the executable.
    #[test]
    fn the_first_loader_entry_is_the_executable() {
        let head = load_order_head().expect("Ldr");
        // SAFETY: the loader's own list, read while it is stable (the test holds no loads).
        let name = unsafe {
            let first = *(head as *const usize);
            let s = full_dll_name(first);
            String::from_utf16_lossy(core::slice::from_raw_parts(
                (*s).buffer,
                (*s).length as usize / 2,
            ))
        };
        let exe = std::env::current_exe().unwrap();
        let file = exe.file_name().unwrap().to_string_lossy().to_lowercase();
        assert!(name.to_lowercase().ends_with(&file), "{name}");
        assert!(loader_lock().is_some());
    }
}
