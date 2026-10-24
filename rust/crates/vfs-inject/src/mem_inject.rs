//! Add the shim to a mapped image's imports, in the suspended process, before
//! its first thread runs.
//!
//! For a program whose file cannot be import-patched — an overlay after the
//! last section, as SteamStub's wrapper leaves — the shim is added here
//! instead: a fresh region is allocated near the image, filled with the import
//! descriptor array plus the shim prepended, and the image's import directory
//! (in its header only) is pointed at it. The loader then loads the shim
//! first, so its `DllMain` runs before any of the program's own static imports
//! initialise — which is the whole point, since those imports (ENB's
//! `d3d11.dll`, say) read the game's files, and the game's files are in the
//! VFS. Nothing of the image's sections is rewritten (`vfs_pe::mem_patch`).
//!
//! Refused (so the caller falls back to remote-thread injection) when the
//! image is a SteamStub wrapper that verifies itself: editing even the header
//! would fail its check. The caller reports which path was taken.

use core::ffi::c_void;

use windows_sys::Win32::Foundation::HANDLE;
use windows_sys::Win32::System::Diagnostics::Debug::{ReadProcessMemory, WriteProcessMemory};
use windows_sys::Win32::System::Memory::{VirtualAllocEx, MEM_COMMIT, MEM_RESERVE, PAGE_READWRITE};

use crate::guest::{peb_of, read_ptr, Guest};

/// Why an in-memory import patch did not happen. The caller treats any of
/// these as "fall back to remote-thread injection".
#[derive(Debug)]
pub enum MemPatchRefused {
    /// Running under Wine (the Proton path). The import this adds names the
    /// shim by filename, and Wine's loader does not find it beside the target
    /// there; remote injection, which loads the shim by full path, is the
    /// known-good path under Wine and makes the Linux TPF launch work. So the
    /// in-memory patch is native-Windows only.
    UnderWine,
    /// The launch asked for remote injection only (`VFS_FORCE_INJECTION`,
    /// from `Activation::Inject`): not tried.
    Forced,
    /// The image's version of SteamStub could not be read.
    SteamStubUnknown,
    /// The headers, PEB or descriptors could not be read or parsed.
    Unreadable(String),
    /// No free address near the image for the new region (within 4 GiB, so an
    /// RVA still fits). Carries the first candidate and the last allocation
    /// error, for diagnosis.
    NoRoomNearImage(String),
    /// A remote allocation or write failed.
    RemoteMemory(String),
}

/// Patch the suspended `process`'s mapped image so it imports the shim first.
///
/// # Safety
/// `process` is a suspended process this code created, with
/// `PROCESS_VM_READ | PROCESS_VM_WRITE | PROCESS_VM_OPERATION |
/// PROCESS_QUERY_INFORMATION`, whose first thread has not run.
#[allow(unsafe_code)]
pub unsafe fn patch_imports_in_memory(process: HANDLE) -> Result<(), MemPatchRefused> {
    use MemPatchRefused::{NoRoomNearImage, RemoteMemory, Unreadable};
    if crate::running_under_wine() {
        return Err(MemPatchRefused::UnderWine);
    }
    // A WOW64 guest's image is named in its PEB32, in the 32-bit layout; the
    // headers say PE32 and `plan_mem_patch` builds 4-byte thunks for it.
    // SAFETY: as this function's contract.
    let guest = unsafe { Guest::of(process) }.map_err(Unreadable)?;
    // SAFETY: as this function's contract.
    let base = unsafe { peb_of(process, guest) }
        .and_then(|peb| {
            // SAFETY: reading the PEB field of a process we own.
            unsafe { read_ptr(process, peb + guest.peb_image_base() as u64, guest) }
        })
        .map_err(|e| Unreadable(format!("read ImageBaseAddress: {e}")))?;
    let base = base as usize;

    // The headers: one page is more than SizeOfHeaders for any real image.
    // SAFETY: reading the image's own header page.
    let headers = unsafe { read_bytes(process, base, 0x1000) }
        .ok_or_else(|| Unreadable("read headers".into()))?;
    let plan = vfs_pe::mem_patch::plan_mem_patch(&headers).map_err(|e| Unreadable(e.into()))?;

    // A SteamStub wrapper (`.bind` section) is NOT a reason to refuse the
    // in-memory patch. SteamStub verifies the on-disk FILE (which is why file
    // import-patching trips it, and this patch never touches the file); only a
    // rare version that also hashes its live headers would notice, and then
    // the stub aborts on its own — no worse than the injection fallback, which
    // fails for this exe anyway. Patching in memory is in fact how a shim
    // coexists with SteamStub: the loader loads the shim first, then the stub
    // unpacks and resolves the real game imports through it. So we proceed, and
    // only note what we found for the record.
    if plan.has_bind_section {
        let verify = match steamstub_verifies(process, base, &headers) {
            Some(true) => "verifies-its-file",
            Some(false) => "no-module-verification",
            None => "unreadable-header",
        };
        eprintln!("vfs-inject: in-memory patch over a SteamStub wrapper ({verify})");
    }

    // The existing descriptors, read at their RVA (base + rva: the image is
    // mapped, so sections are at their virtual addresses), to the terminator.
    let mut old = Vec::new();
    if plan.import_rva != 0 {
        let mut at = base + plan.import_rva as usize;
        loop {
            // SAFETY: reading the image's own import descriptors.
            let d = unsafe { read_bytes(process, at, 20) }
                .ok_or_else(|| Unreadable("read import descriptors".into()))?;
            if d.iter().all(|&b| b == 0) {
                break;
            }
            old.extend_from_slice(&d);
            at += 20;
            if old.len() > 20 * 4096 {
                return Err(Unreadable(
                    "import descriptor array has no terminator".into(),
                ));
            }
        }
    }

    // Reserve the region near the image so its address, as an RVA (`got -
    // base`), still fits 32 bits. The region's RVA is baked into the
    // descriptors it holds, so the patch is built for whichever candidate the
    // OS grants: ask for one exact address at a time (a null preferred base
    // would land anywhere in the 128 TiB space, too far for an RVA), stepping
    // past the image until one is free.
    // A reservation's base must be on the 64 KiB allocation granularity, and
    // the image base already is, so the region's RVA must be a multiple of it.
    const GRAN: usize = 0x1_0000;
    let step = GRAN;
    let after = base + vfs_pe::mem_patch::first_region_rva(&plan) as usize;
    let first = after.div_ceil(GRAN) * GRAN;
    let patch;
    let want;
    let mut candidate = first;
    let mut last_err = 0u32;
    loop {
        if candidate < base || (candidate - base) > u32::MAX as usize - 0x10_0000 {
            return Err(NoRoomNearImage(format!(
                "first {first:#x} base {base:#x}: RVA out of range"
            )));
        }
        let built = vfs_pe::mem_patch::build_mem_patch(&plan, &old, (candidate - base) as u32)
            .map_err(|e| Unreadable(e.into()))?;
        // SAFETY: allocating at an exact address in a process we own; a taken
        // address returns null rather than relocating.
        let got = unsafe {
            VirtualAllocEx(
                process,
                candidate as *const c_void,
                built.region.len(),
                MEM_COMMIT | MEM_RESERVE,
                PAGE_READWRITE,
            )
        };
        if got as usize == candidate {
            patch = built;
            want = candidate;
            break;
        }
        if got.is_null() {
            // SAFETY: reads this thread's last error.
            last_err = unsafe { windows_sys::Win32::Foundation::GetLastError() };
        } else {
            // SAFETY: free the wrong-address reservation we just made.
            unsafe {
                windows_sys::Win32::System::Memory::VirtualFreeEx(
                    process,
                    got,
                    0,
                    windows_sys::Win32::System::Memory::MEM_RELEASE,
                );
            }
        }
        // Up to 64 steps (a few MiB past the image) before giving up to the
        // fallback.
        candidate += step;
        if candidate >= first + 64 * step {
            return Err(NoRoomNearImage(format!(
                "first {first:#x} base {base:#x} region {} bytes: last error {last_err}",
                old.len() + 200
            )));
        }
    }

    // SAFETY: writing the region we just allocated.
    unsafe { write_bytes(process, want, &patch.region) }
        .map_err(|e| RemoteMemory(format!("write region: {e}")))?;
    // The image's header page is mapped read-only; make it writable (a private
    // copy-on-write change, which is all the loader needs and leaves the file
    // untouched) for the directory edits, then restore its protection.
    for (off, bytes) in &patch.header_edits {
        // SAFETY: protect-write-restore within the image's header page.
        unsafe { write_protected(process, base + *off as usize, bytes) }
            .map_err(|e| RemoteMemory(format!("write header: {e}")))?;
    }
    Ok(())
}

/// Whether the image at `base` is a SteamStub wrapper that verifies itself:
/// `Some(true)`/`Some(false)` per its `NoModuleVerification` flag, `None` when
/// the header cannot be decoded.
#[allow(unsafe_code)]
unsafe fn steamstub_verifies(process: HANDLE, base: usize, headers: &[u8]) -> Option<bool> {
    // AddressOfEntryPoint is at optional-header + 16.
    let e_lfanew = u32::from_le_bytes(headers[0x3C..0x40].try_into().ok()?) as usize;
    let opt = e_lfanew + 24;
    let entry = u32::from_le_bytes(headers.get(opt + 16..opt + 20)?.try_into().ok()?) as usize;
    // The 0xF0-byte encoded header sits just before the entry point.
    // SAFETY: reading the image's own bytes before its entry point.
    let hdr = unsafe { read_bytes(process, base + entry - 0xF0, 0xF0) }?;
    let flags = vfs_pe::decode_steamstub_header(&hdr)?;
    Some(flags & vfs_pe::STEAMSTUB_NO_MODULE_VERIFICATION == 0)
}

#[allow(unsafe_code)]
unsafe fn read_bytes(process: HANDLE, at: usize, len: usize) -> Option<Vec<u8>> {
    let mut buf = vec![0u8; len];
    let mut got = 0usize;
    // SAFETY: ReadProcessMemory into a buffer of `len`.
    let ok = unsafe {
        ReadProcessMemory(
            process,
            at as *const c_void,
            buf.as_mut_ptr().cast(),
            len,
            &mut got,
        )
    };
    (ok != 0 && got == len).then_some(buf)
}

/// Write `bytes` at `at`, making the page writable first and restoring its
/// protection after: for the image's read-only header, which
/// `WriteProcessMemory` cannot change on its own for an image mapping.
///
/// # Safety
/// `at..at+bytes.len()` is committed memory of a process we own.
#[allow(unsafe_code)]
unsafe fn write_protected(process: HANDLE, at: usize, bytes: &[u8]) -> Result<(), String> {
    use windows_sys::Win32::System::Memory::{VirtualProtectEx, PAGE_READWRITE};
    let mut old = 0u32;
    // SAFETY: a protection change on a process we own.
    let ok = unsafe {
        VirtualProtectEx(
            process,
            at as *const c_void,
            bytes.len(),
            PAGE_READWRITE,
            &mut old,
        )
    };
    if ok == 0 {
        return Err(format!("VirtualProtectEx at {at:#x}"));
    }
    // SAFETY: the range is now writable.
    let r = unsafe { write_bytes(process, at, bytes) };
    let mut restored = 0u32;
    // SAFETY: restore the saved protection; a failure here does not undo the write.
    unsafe {
        VirtualProtectEx(
            process,
            at as *const c_void,
            bytes.len(),
            old,
            &mut restored,
        );
    }
    r
}

#[allow(unsafe_code)]
unsafe fn write_bytes(process: HANDLE, at: usize, bytes: &[u8]) -> Result<(), String> {
    let mut put = 0usize;
    // SAFETY: WriteProcessMemory of `bytes.len()` into a process we own.
    let ok = unsafe {
        WriteProcessMemory(
            process,
            at as *const c_void,
            bytes.as_ptr().cast(),
            bytes.len(),
            &mut put,
        )
    };
    if ok != 0 && put == bytes.len() {
        Ok(())
    } else {
        Err(format!("at {at:#x}"))
    }
}
