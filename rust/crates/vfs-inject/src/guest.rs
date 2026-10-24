//! Which layouts a suspended target's process structures have, and where they are.
//!
//! The launch reads and writes a target's PEB, process parameters, TEB and primary thread
//! context before it runs. A 64-bit process has the 64-bit layouts. A 32-bit process has the
//! 32-bit ones, whether this process is 32-bit too (the 32-bit shim creating a child) or 64-bit
//! (the host launching a WOW64 guest): then the structures that matter are the guest's own, the
//! PEB32 and TEB32 the WOW64 layer keeps beside the 64-bit ones, and the thread's 32-bit context.
//! Every offset here is the documented-by-layout one for its width and is pinned by the tests.
#![allow(unsafe_code)]

use core::ffi::c_void;
use std::mem::size_of;

use windows_sys::Win32::Foundation::HANDLE;
use windows_sys::Win32::System::Diagnostics::Debug::ReadProcessMemory;
use windows_sys::Win32::System::LibraryLoader::{GetModuleHandleW, GetProcAddress};

/// The pointer width of a target process.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum Guest {
    /// A 64-bit process.
    X64,
    /// A 32-bit process: native on a 32-bit host, a WOW64 guest of a 64-bit one.
    X86,
}

impl Guest {
    /// This process's own width.
    pub const HOST: Guest = if size_of::<usize>() == 8 {
        Guest::X64
    } else {
        Guest::X86
    };

    /// The width of `process`.
    ///
    /// # Safety
    /// `process` is a live process handle with `PROCESS_QUERY_LIMITED_INFORMATION`.
    pub unsafe fn of(process: HANDLE) -> Result<Guest, String> {
        // SAFETY: as this function's contract.
        let target = unsafe { is_wow64(process) }?;
        if Self::HOST == Guest::X64 {
            return Ok(if target { Guest::X86 } else { Guest::X64 });
        }
        // A 32-bit host is itself WOW64 on a 64-bit OS; a target that is not is 64-bit.
        // SAFETY: the pseudo-handle of this process.
        let here = unsafe { is_wow64(windows_sys::Win32::System::Threading::GetCurrentProcess()) }?;
        Ok(if here && !target {
            Guest::X64
        } else {
            Guest::X86
        })
    }

    /// Whether this process reaches the guest's structures through the WOW64 layer: a 32-bit
    /// guest of a 64-bit host.
    pub fn is_wow64_from_here(self) -> bool {
        self == Guest::X86 && Self::HOST == Guest::X64
    }

    /// Bytes in a pointer.
    pub const fn ptr(self) -> usize {
        match self {
            Guest::X64 => 8,
            Guest::X86 => 4,
        }
    }

    /// `PEB.ImageBaseAddress`.
    pub const fn peb_image_base(self) -> usize {
        match self {
            Guest::X64 => 0x10,
            Guest::X86 => 0x08,
        }
    }

    /// `PEB.ProcessParameters`.
    pub const fn peb_process_parameters(self) -> usize {
        match self {
            Guest::X64 => 0x20,
            Guest::X86 => 0x10,
        }
    }

    /// `PEB.Ldr`.
    pub const fn peb_ldr(self) -> usize {
        match self {
            Guest::X64 => 0x18,
            Guest::X86 => 0x0C,
        }
    }

    /// `PEB.LoaderLock`.
    pub const fn peb_loader_lock(self) -> usize {
        match self {
            Guest::X64 => 0x110,
            Guest::X86 => 0xA0,
        }
    }

    /// `PEB_LDR_DATA.InLoadOrderModuleList`.
    pub const fn ldr_in_load_order(self) -> usize {
        match self {
            Guest::X64 => 0x10,
            Guest::X86 => 0x0C,
        }
    }

    /// `LDR_DATA_TABLE_ENTRY.FullDllName`.
    pub const fn ldr_entry_full_dll_name(self) -> usize {
        match self {
            Guest::X64 => 0x48,
            Guest::X86 => 0x24,
        }
    }

    /// `RTL_USER_PROCESS_PARAMETERS.CurrentDirectory.Handle`, after its `DosPath`.
    pub const fn pp_curdir_handle(self) -> usize {
        self.pp_curdir_dos_path() + 2 * self.ptr()
    }

    /// `RTL_USER_PROCESS_PARAMETERS.ImagePathName`.
    pub const fn pp_image_path_name(self) -> usize {
        match self {
            Guest::X64 => 0x60,
            Guest::X86 => 0x38,
        }
    }

    /// `RTL_USER_PROCESS_PARAMETERS.CurrentDirectory.DosPath`.
    pub const fn pp_curdir_dos_path(self) -> usize {
        match self {
            Guest::X64 => 0x38,
            Guest::X86 => 0x24,
        }
    }

    /// `RTL_USER_PROCESS_PARAMETERS.CommandLine`.
    pub const fn pp_command_line(self) -> usize {
        match self {
            Guest::X64 => 0x70,
            Guest::X86 => 0x40,
        }
    }

    /// `TEB.NtTib.StackBase`.
    pub const fn teb_stack_base(self) -> usize {
        self.ptr()
    }

    /// `TEB.NtTib.StackLimit`.
    pub const fn teb_stack_limit(self) -> usize {
        2 * self.ptr()
    }

    /// `TEB.NtTib.Self`, the TEB's own address: how a TEB32 found by offset is checked.
    pub const fn teb_self(self) -> usize {
        6 * self.ptr()
    }

    /// `TEB.DeallocationStack`.
    pub const fn teb_deallocation_stack(self) -> usize {
        match self {
            Guest::X64 => 0x1478,
            Guest::X86 => 0xE0C,
        }
    }

    /// A `UNICODE_STRING` as this width lays it out: `Length`, `MaximumLength`, padding to the
    /// pointer, `Buffer`.
    pub fn unicode_string(self, length: u16, maximum_length: u16, buffer: u64) -> Vec<u8> {
        let mut b = vec![0u8; 2 * self.ptr()];
        b[0..2].copy_from_slice(&length.to_le_bytes());
        b[2..4].copy_from_slice(&maximum_length.to_le_bytes());
        let p = self.ptr();
        b[p..2 * p].copy_from_slice(&buffer.to_le_bytes()[..p]);
        b
    }

    /// The `(Length, MaximumLength, Buffer)` of a `UNICODE_STRING` read as this width lays it out.
    pub fn parse_unicode_string(self, b: &[u8]) -> Option<(u16, u16, u64)> {
        let p = self.ptr();
        let b = b.get(..2 * p)?;
        let mut buffer = [0u8; 8];
        buffer[..p].copy_from_slice(&b[p..2 * p]);
        Some((
            u16::from_le_bytes([b[0], b[1]]),
            u16::from_le_bytes([b[2], b[3]]),
            u64::from_le_bytes(buffer),
        ))
    }
}

/// The offset of a WOW64 thread's TEB32 from its 64-bit TEB. The 32-bit TEB sits two pages
/// above the 64-bit one on every WOW64 release; [`teb_of`] checks it through `NtTib.Self`
/// rather than trusting it.
const WOW64_TEB32_OFFSET: u64 = 0x2000;

/// `ProcessWow64Information`: the address of a WOW64 process's PEB32, or 0.
const PROCESS_WOW64_INFORMATION: u32 = 26;
/// `ProcessBasicInformation`.
const PROCESS_BASIC_INFORMATION: u32 = 0;
/// `ThreadBasicInformation`.
const THREAD_BASIC_INFORMATION: u32 = 0;

type NtQueryInformationFn =
    unsafe extern "system" fn(HANDLE, u32, *mut c_void, u32, *mut u32) -> i32;

/// `PROCESS_BASIC_INFORMATION`, at this process's own width (the call is answered in it).
#[repr(C)]
#[derive(Default)]
struct BasicInformation {
    exit_status: i32,
    peb_base_address: usize,
    affinity_mask: usize,
    base_priority: i32,
    unique_process_id: usize,
    inherited_from_unique_process_id: usize,
}

/// `THREAD_BASIC_INFORMATION`, at this process's own width.
#[repr(C)]
#[derive(Default)]
struct ThreadBasicInformation {
    exit_status: i32,
    teb_base: usize,
    client_id: [usize; 2],
    affinity: usize,
    priority: i32,
    base_priority: i32,
}

/// `name` out of this process's ntdll, as an `NtQuery*Information*` function.
fn nt_query(name: &core::ffi::CStr) -> Result<NtQueryInformationFn, String> {
    let ntdll: Vec<u16> = "ntdll.dll".encode_utf16().chain(Some(0)).collect();
    // SAFETY: ntdll is always loaded; the names are NUL-terminated.
    unsafe {
        let module = GetModuleHandleW(ntdll.as_ptr());
        let f = GetProcAddress(module, name.as_ptr().cast())
            .ok_or_else(|| format!("ntdll has no {}", name.to_string_lossy()))?;
        Ok(core::mem::transmute::<
            unsafe extern "system" fn() -> isize,
            NtQueryInformationFn,
        >(f))
    }
}

/// Whether `process` runs under WOW64.
unsafe fn is_wow64(process: HANDLE) -> Result<bool, String> {
    let mut wow = 0;
    // SAFETY: a query of a live process handle into a local.
    if unsafe { windows_sys::Win32::System::Threading::IsWow64Process(process, &mut wow) } == 0 {
        return Err("IsWow64Process failed".into());
    }
    Ok(wow != 0)
}

/// The address of `process`'s PEB in `guest`'s layout: the PEB32 of a WOW64 guest, the
/// process's own PEB otherwise.
///
/// # Safety
/// `process` is a live process handle with `PROCESS_QUERY_INFORMATION`.
pub unsafe fn peb_of(process: HANDLE, guest: Guest) -> Result<u64, String> {
    let query = nt_query(c"NtQueryInformationProcess")?;
    if guest.is_wow64_from_here() {
        let mut peb32 = 0usize;
        // SAFETY: the documented query into a pointer-sized local.
        let st = unsafe {
            query(
                process,
                PROCESS_WOW64_INFORMATION,
                (&mut peb32 as *mut usize).cast(),
                size_of::<usize>() as u32,
                core::ptr::null_mut(),
            )
        };
        if st < 0 || peb32 == 0 {
            return Err(format!("NtQueryInformationProcess(Wow64): {st:#x}"));
        }
        return Ok(peb32 as u64);
    }
    let mut info = BasicInformation::default();
    // SAFETY: the documented query into a struct of its size.
    let st = unsafe {
        query(
            process,
            PROCESS_BASIC_INFORMATION,
            (&mut info as *mut BasicInformation).cast(),
            size_of::<BasicInformation>() as u32,
            core::ptr::null_mut(),
        )
    };
    if st < 0 || info.peb_base_address == 0 {
        return Err(format!("NtQueryInformationProcess: {st:#x}"));
    }
    Ok(info.peb_base_address as u64)
}

/// The address of `thread`'s TEB in `guest`'s layout: a WOW64 thread's TEB32, checked through
/// its `NtTib.Self`; the thread's own TEB otherwise.
///
/// # Safety
/// `process` and `thread` are live handles of one process and one of its threads, with
/// `PROCESS_VM_READ` and `THREAD_QUERY_INFORMATION`.
pub(crate) unsafe fn teb_of(process: HANDLE, thread: HANDLE, guest: Guest) -> Result<u64, String> {
    let query = nt_query(c"NtQueryInformationThread")?;
    let mut tbi = ThreadBasicInformation::default();
    // SAFETY: the documented query into a struct of its size.
    let st = unsafe {
        query(
            thread,
            THREAD_BASIC_INFORMATION,
            (&mut tbi as *mut ThreadBasicInformation).cast(),
            size_of::<ThreadBasicInformation>() as u32,
            core::ptr::null_mut(),
        )
    };
    if st < 0 || tbi.teb_base == 0 {
        return Err(format!("NtQueryInformationThread: {st:#x}"));
    }
    let teb = tbi.teb_base as u64;
    if !guest.is_wow64_from_here() {
        return Ok(teb);
    }
    let teb32 = teb + WOW64_TEB32_OFFSET;
    // SAFETY: as this function's contract.
    let own = unsafe { read_ptr(process, teb32 + guest.teb_self() as u64, guest) }?;
    if own != teb32 {
        return Err(format!(
            "no TEB32 at {teb32:#x} (TEB {teb:#x}; NtTib.Self there is {own:#x})"
        ));
    }
    Ok(teb32)
}

/// A pointer-sized field of `guest`'s width at `at` in `process`, zero-extended.
///
/// # Safety
/// `process` is a live process handle with `PROCESS_VM_READ`.
pub(crate) unsafe fn read_ptr(process: HANDLE, at: u64, guest: Guest) -> Result<u64, String> {
    let mut b = [0u8; 8];
    let mut got = 0usize;
    let len = guest.ptr();
    // SAFETY: a read of `len` bytes into a local of at least that size.
    let ok = unsafe {
        ReadProcessMemory(
            process,
            at as usize as *const c_void,
            b.as_mut_ptr().cast(),
            len,
            &mut got,
        )
    };
    if ok == 0 || got != len {
        return Err(format!("ReadProcessMemory at {at:#x}"));
    }
    Ok(u64::from_le_bytes(b))
}

#[cfg(test)]
mod tests {
    use super::*;

    /// The offsets are the published layouts': `PEB`, `RTL_USER_PROCESS_PARAMETERS`, `NT_TIB`
    /// and `TEB` for x64 and for x86 (the WOW64 guest's PEB32 and TEB32).
    #[test]
    fn each_width_has_its_layouts() {
        let x64 = (
            Guest::X64.peb_image_base(),
            Guest::X64.peb_process_parameters(),
            Guest::X64.pp_curdir_dos_path(),
            Guest::X64.pp_command_line(),
            Guest::X64.teb_stack_base(),
            Guest::X64.teb_stack_limit(),
            Guest::X64.teb_self(),
            Guest::X64.teb_deallocation_stack(),
        );
        assert_eq!(x64, (0x10, 0x20, 0x38, 0x70, 0x08, 0x10, 0x30, 0x1478));
        let x86 = (
            Guest::X86.peb_image_base(),
            Guest::X86.peb_process_parameters(),
            Guest::X86.pp_curdir_dos_path(),
            Guest::X86.pp_command_line(),
            Guest::X86.teb_stack_base(),
            Guest::X86.teb_stack_limit(),
            Guest::X86.teb_self(),
            Guest::X86.teb_deallocation_stack(),
        );
        assert_eq!(x86, (0x08, 0x10, 0x24, 0x40, 0x04, 0x08, 0x18, 0xE0C));
        let loader = |g: Guest| {
            (
                g.peb_ldr(),
                g.peb_loader_lock(),
                g.ldr_in_load_order(),
                g.ldr_entry_full_dll_name(),
                g.pp_curdir_handle(),
                g.pp_image_path_name(),
            )
        };
        assert_eq!(loader(Guest::X64), (0x18, 0x110, 0x10, 0x48, 0x48, 0x60));
        assert_eq!(loader(Guest::X86), (0x0C, 0xA0, 0x0C, 0x24, 0x2C, 0x38));
    }

    /// This process's own PEB, read through the offsets for its own width, names a parameter
    /// block whose image path is this test binary's.
    #[test]
    fn this_process_s_peb_names_its_image() {
        // SAFETY: the current-process pseudo-handle; reads of this process's own PEB.
        unsafe {
            let me = windows_sys::Win32::System::Threading::GetCurrentProcess();
            let peb = peb_of(me, Guest::HOST).unwrap() as usize;
            let params = *((peb + Guest::HOST.peb_process_parameters()) as *const usize);
            let image = &*((params + Guest::HOST.pp_image_path_name())
                as *const windows_sys::Win32::Foundation::UNICODE_STRING);
            let name = String::from_utf16_lossy(core::slice::from_raw_parts(
                image.Buffer,
                image.Length as usize / 2,
            ));
            let exe = std::env::current_exe().unwrap();
            let file = exe.file_name().unwrap().to_string_lossy().to_lowercase();
            assert!(name.to_lowercase().ends_with(&file), "{name}");
        }
    }

    #[test]
    fn a_unicode_string_round_trips_at_each_width() {
        let s64 = Guest::X64.unicode_string(6, 8, 0x1122_3344_5566_7788);
        assert_eq!(s64.len(), 16);
        assert_eq!(&s64[4..8], &[0; 4], "padding before the pointer");
        assert_eq!(
            Guest::X64.parse_unicode_string(&s64),
            Some((6, 8, 0x1122_3344_5566_7788))
        );
        let s32 = Guest::X86.unicode_string(6, 8, 0x5566_7788);
        assert_eq!(s32.len(), 8);
        assert_eq!(&s32[4..8], &0x5566_7788u32.to_le_bytes());
        assert_eq!(
            Guest::X86.parse_unicode_string(&s32),
            Some((6, 8, 0x5566_7788))
        );
        assert_eq!(Guest::X64.parse_unicode_string(&s32), None);
    }

    /// This process is its own width, and only a 32-bit guest of a 64-bit host goes through
    /// WOW64.
    #[test]
    fn this_process_is_its_own_guest() {
        // SAFETY: the current-process pseudo-handle.
        let me = unsafe { Guest::of(windows_sys::Win32::System::Threading::GetCurrentProcess()) };
        assert_eq!(me, Ok(Guest::HOST));
        assert!(!Guest::X64.is_wow64_from_here());
        assert_eq!(
            Guest::X86.is_wow64_from_here(),
            cfg!(target_pointer_width = "64")
        );
    }
}
