//! Load hooks: what the loader and the native process-creation path do, recorded
//! to the shim load log (`crate::loadlog`). They exist to answer one question
//! that user-mode tools outside the process cannot: when a "DLL not found"
//! hard-error dialog appears, is it *this* (shimmed) process raising it — a late
//! or dynamic load that resolves against the virtual game directory and misses —
//! or another, un-shimmed process entirely?
//!
//!  - [`raise_hard_error_hook_body`] logs every `NtRaiseHardError`, with the
//!    failing DLL name for `STATUS_DLL_NOT_FOUND`. If it fires in the primary,
//!    the dialog is the primary's own.
//!  - [`ldr_load_dll_hook_body`] logs every *failed* `LdrLoadDll`: the DLL name,
//!    the search path and the status. It is also the one hook here that changes
//!    a call: a DLL named by its path under a root's backing directory is loaded
//!    by its path under the root's location (see the function).
//!  - [`create_user_process_hook_body`] logs the image of every process created
//!    through `NtCreateUserProcess`, the syscall `CreateProcessInternalW` funnels
//!    into — catching any create that never reached `cpiw_hook_body`.
//!
//! The other two call their trampoline unchanged. See `cpiw_hook_body` for the
//! fail-closed containment on the `CreateProcessInternalW` path.
#![deny(unsafe_op_in_unsafe_fn)]

use super::{TRAMP_LDR_LOAD_DLL, TRAMP_NT_CREATE_USER_PROCESS, TRAMP_RAISE_HARD_ERROR};
use crate::ntbuf::us_string;
use crate::ntdef::{ObjectAttributes, UnicodeString, STATUS_UNSUCCESSFUL};
use core::ffi::c_void;
use windows_sys::Win32::Foundation::{HANDLE, NTSTATUS};

/// `STATUS_DLL_NOT_FOUND` — the loader's "code execution cannot proceed because
/// X was not found".
const STATUS_DLL_NOT_FOUND: NTSTATUS = 0xC000_0135u32 as i32;

/// Byte offset of `ImagePathName` (a `UNICODE_STRING`) within
/// `RTL_USER_PROCESS_PARAMETERS`, at this target's width. Stable across Windows
/// and Wine: `Reserved1[16]` then `Reserved2[10]` pointers (0x60 on x64, 0x38 on
/// x86).
const RTL_UPP_IMAGE_PATH_OFF: usize = vfs_inject::Guest::HOST.pp_image_path_name();

/// `NtRaiseHardError`: log it, then call through unchanged.
///
/// # Safety
/// NT entry point; pointers are the caller's, per the NT contract (hook/mod.rs).
pub(super) unsafe fn raise_hard_error_hook_body(
    status: NTSTATUS,
    num_params: u32,
    mask: u32,
    params: *const usize,
    response_option: u32,
    response: *mut u32,
) -> NTSTATUS {
    // SAFETY: `params` is the caller's parameter array, per the NT contract.
    let detail = unsafe { hard_error_detail(status, num_params, mask, params) };
    crate::loadlog::note(&format!(
        "hard error status={:#010x} {detail}",
        status as u32
    ));
    match TRAMP_RAISE_HARD_ERROR.get() {
        // SAFETY: the original NT function with the caller's arguments.
        Some(tramp) => unsafe {
            tramp(status, num_params, mask, params, response_option, response)
        },
        None => STATUS_UNSUCCESSFUL,
    }
}

/// Describe a hard error for the log: for a status whose first parameter is a
/// `UNICODE_STRING` (bit 0 of `mask`), the string — which for
/// `STATUS_DLL_NOT_FOUND` is the missing DLL's name.
///
/// # Safety
/// `params` is NULL or points to `num_params` `ULONG_PTR` values; a value flagged
/// by `mask` as a `UNICODE_STRING` points to one, per the NT contract.
unsafe fn hard_error_detail(
    status: NTSTATUS,
    num_params: u32,
    mask: u32,
    params: *const usize,
) -> String {
    let kind = if status == STATUS_DLL_NOT_FOUND {
        "dll-not-found"
    } else {
        "other"
    };
    if params.is_null() || num_params == 0 {
        return format!("{kind} (no params)");
    }
    if mask & 1 != 0 {
        // SAFETY: `params` points to at least one `ULONG_PTR` (num_params >= 1).
        let p0 = unsafe { *params } as *const UnicodeString;
        // SAFETY: bit 0 of the mask marks the first parameter a `UNICODE_STRING*`.
        if let Ok(Some(s)) = unsafe { us_string(p0) } {
            return format!("{kind} name=\"{s}\"");
        }
    }
    format!("{kind} num_params={num_params} mask={mask:#x}")
}

/// `LdrLoadDll`: call through, and log it when the load fails.
///
/// # Safety
/// NT entry point; pointers are the caller's, per the NT contract (hook/mod.rs).
pub(super) unsafe fn ldr_load_dll_hook_body(
    search_path: *const u16,
    characteristics: *const u32,
    dll_name: *const UnicodeString,
    base: *mut *mut c_void,
) -> NTSTATUS {
    // A DLL named by its path under a root's backing directory is loaded by its path under the
    // root's location instead: the loader keys loaded modules by full path, so the same file
    // under both spellings would be mapped twice (a plugin preloader that builds paths from its
    // own module name, loaded from the backing directory before the shim renamed it, loads what
    // SKSE later loads by the location).
    // SAFETY: the caller's `UNICODE_STRING`, per the NT contract.
    let asked = unsafe { us_string(dll_name) }.ok().flatten();
    let respelled: Option<Vec<u16>> = asked
        .as_deref()
        .and_then(crate::whereami::virtual_spelling)
        .map(|v| v.encode_utf16().collect());
    let respelled_us = respelled.as_ref().and_then(|w| {
        let bytes = u16::try_from(w.len() * 2).ok()?;
        Some(UnicodeString {
            length: bytes,
            maximum_length: bytes,
            buffer: w.as_ptr() as *mut u16,
        })
    });
    let name_arg: *const UnicodeString = match &respelled_us {
        Some(us) => us,
        None => dll_name,
    };
    let status = match TRAMP_LDR_LOAD_DLL.get() {
        // SAFETY: the original NT function with the caller's arguments (the name respelled, its
        // buffer alive until the call returns).
        Some(tramp) => unsafe { tramp(search_path, characteristics, name_arg, base) },
        None => return STATUS_UNSUCCESSFUL,
    };
    if let (Some(a), Some(_)) = (&asked, &respelled_us) {
        crate::loadlog::note(&format!(
            "LdrLoadDll respelled \"{a}\" by its location, status={:#010x}",
            status as u32
        ));
    }
    // NT error (high bit set): a failed load. Successes are the common case and
    // are not logged, so the hook stays cheap.
    if status < 0 {
        // SAFETY: the caller's `UNICODE_STRING`, per the NT contract.
        let name = unsafe { us_string(dll_name) }
            .ok()
            .flatten()
            .unwrap_or_default();
        // `search_path` is not read: kernelbase passes load flags in it as
        // often as a string (`LoadLibraryExW` ORs in bit 0), and reading
        // that as text faulted in every failed load, killing the game.
        crate::loadlog::note(&format!(
            "LdrLoadDll FAILED status={:#010x} name=\"{name}\"",
            status as u32
        ));
    }
    status
}

/// `NtCreateUserProcess`: log the image of the process being created, then call
/// through unchanged. Catches a create that bypasses `CreateProcessInternalW`.
///
/// # Safety
/// NT entry point; pointers are the caller's, per the NT contract (hook/mod.rs).
#[allow(clippy::too_many_arguments)]
pub(super) unsafe fn create_user_process_hook_body(
    process_handle: *mut HANDLE,
    thread_handle: *mut HANDLE,
    process_access: u32,
    thread_access: u32,
    process_oa: *const ObjectAttributes,
    thread_oa: *const ObjectAttributes,
    process_flags: u32,
    thread_flags: u32,
    process_params: *const c_void,
    create_info: *mut c_void,
    attribute_list: *const c_void,
) -> NTSTATUS {
    // SAFETY: `process_params` is NULL or a `RTL_USER_PROCESS_PARAMETERS*`.
    let image = unsafe { image_path_of(process_params) }.unwrap_or_else(|| "?".into());
    crate::loadlog::note(&format!(
        "NtCreateUserProcess image=\"{image}\" process_flags={process_flags:#x}"
    ));
    match TRAMP_NT_CREATE_USER_PROCESS.get() {
        // SAFETY: the original NT function with the caller's arguments.
        Some(tramp) => unsafe {
            tramp(
                process_handle,
                thread_handle,
                process_access,
                thread_access,
                process_oa,
                thread_oa,
                process_flags,
                thread_flags,
                process_params,
                create_info,
                attribute_list,
            )
        },
        None => STATUS_UNSUCCESSFUL,
    }
}

/// `ImagePathName` from a `RTL_USER_PROCESS_PARAMETERS`.
///
/// # Safety
/// `params` is NULL or points to a readable `RTL_USER_PROCESS_PARAMETERS`.
unsafe fn image_path_of(params: *const c_void) -> Option<String> {
    if params.is_null() {
        return None;
    }
    // SAFETY: ImagePathName sits at a fixed offset in the structure.
    let us = unsafe { params.cast::<u8>().add(RTL_UPP_IMAGE_PATH_OFF) }.cast::<UnicodeString>();
    // SAFETY: `us` points to the embedded `UNICODE_STRING`.
    unsafe { us_string(us) }.ok().flatten()
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::hook::test_support::us_raw;

    /// A `STATUS_DLL_NOT_FOUND` hard error with a `UNICODE_STRING` first
    /// parameter logs the missing DLL's name — the string the real-world dialog
    /// shows. This is the parse the `NtRaiseHardError` hook runs in whichever
    /// shimmed process the loader raises the error in.
    #[test]
    fn hard_error_detail_extracts_the_missing_dll_name() {
        let mut name: Vec<u16> = "XINPUT1_3.dll".encode_utf16().collect();
        let us = us_raw((name.len() * 2) as u16, name.as_mut_ptr());
        let params: [usize; 1] = [core::ptr::addr_of!(us) as usize];
        // SAFETY: params[0] points to a valid UNICODE_STRING; mask bit 0 set.
        let detail = unsafe { hard_error_detail(STATUS_DLL_NOT_FOUND, 1, 1, params.as_ptr()) };
        assert_eq!(detail, "dll-not-found name=\"XINPUT1_3.dll\"");
    }

    /// No parameters: still classified, no out-of-bounds read.
    #[test]
    fn hard_error_detail_handles_no_params() {
        // SAFETY: a null params array with count 0 is the documented empty case.
        let detail = unsafe { hard_error_detail(STATUS_DLL_NOT_FOUND, 0, 0, core::ptr::null()) };
        assert_eq!(detail, "dll-not-found (no params)");
    }
}
