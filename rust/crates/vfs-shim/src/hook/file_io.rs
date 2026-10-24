//! `NtReadFile`, `NtWriteFile`, `NtLockFile`, `NtUnlockFile`, `NtFlushBuffersFile`.
#![deny(unsafe_op_in_unsafe_fn)]

use super::{
    open_synth, synth_path, TRAMP_COPY_CHUNK, TRAMP_FLUSH, TRAMP_FSCTL, TRAMP_IOCTL, TRAMP_LOCK,
    TRAMP_QEA, TRAMP_READ, TRAMP_UNLOCK, TRAMP_WRITE,
};
use crate::ntdef::{
    STATUS_END_OF_FILE, STATUS_INVALID_DEVICE_REQUEST, STATUS_INVALID_HANDLE,
    STATUS_NOT_A_REPARSE_POINT, STATUS_NO_EAS_ON_FILE, STATUS_SUCCESS, STATUS_UNSUCCESSFUL,
};
use crate::synth_file::FileView;
use core::ffi::c_void;
use windows_sys::Win32::Foundation::{HANDLE, NTSTATUS};

/// `NtLockFile` hook: grants byte-range locks on synthetic handles locally.
///
/// A synthetic handle is a tagged value in `synth_file`'s table, not a kernel file
/// object, so an unhooked lock call gets `STATUS_INVALID_HANDLE` back, and
/// `GetPrivateProfileStringW` (how Skyrim loads `SkyrimPrefs.ini`) then returns the
/// caller's default for every key.
///
/// **The grant is fake, on purpose.** Nothing is recorded and nothing conflicts:
/// two callers asking for the same exclusive range both get `STATUS_SUCCESS`. There
/// is no cross-process lock table to consult, and refusing would leave the profile
/// APIs as broken as an unhooked call. The cost is real: two injected writers on
/// one INI can lose each other's update. `hookstats::note_synthetic_lock` counts
/// every grant by path, so contention shows up in a report.
///
/// Only handles [`open_synth`] resolves are answered (the tag test alone would also
/// catch `INVALID_HANDLE_VALUE` and closed handles). Answered synchronously, like
/// `read_hook`: `STATUS_SUCCESS`, a completed `IO_STATUS_BLOCK`, and `SetEvent` if
/// given. The caller's APC is not run; `note_read_completion` counts that case.
/// See docs/shim-invariants.md, "Lock semantics".
#[allow(clippy::too_many_arguments)]
pub(super) unsafe fn lock_hook_body(
    handle: HANDLE,
    event: HANDLE,
    apc: *const c_void,
    apc_ctx: *const c_void,
    iosb: *mut c_void,
    byte_offset: *const i64,
    length: *const i64,
    key: u32,
    fail_immediately: u8,
    exclusive: u8,
) -> NTSTATUS {
    let _hs = crate::hookstats::Timed::new(crate::hookstats::Hook::Lock);
    let tramp = match TRAMP_LOCK.get() {
        Some(t) => t,
        None => return STATUS_UNSUCCESSFUL,
    };
    if crate::synth_file::is_fuse_synth(handle as isize) {
        if !open_synth(handle) {
            return STATUS_INVALID_HANDLE;
        }
        // Classified the same way `read_hook`/`write_hook` classify theirs: an
        // APC-supplied lock is a completion we accept and never deliver, and
        // that is the one caller shape here that can actually hang. Counting
        // it is what makes it visible in the async section instead of looking
        // like an ordinary synchronous grant.
        crate::hookstats::note_read_completion(!apc.is_null(), !event.is_null());
        crate::hookstats::note_synthetic_lock(
            if exclusive != 0 {
                "lock-exclusive"
            } else {
                "lock-shared"
            },
            synth_path(handle).as_deref(),
        );
        // SAFETY: same NT-pointer contract as this fn (hook/mod.rs).
        unsafe { crate::ntbuf::iosb_set(iosb, STATUS_SUCCESS, 0) };
        if !event.is_null() {
            // SAFETY: FFI call with valid arguments.
            unsafe { windows_sys::Win32::System::Threading::SetEvent(event) };
        }
        return STATUS_SUCCESS;
    }
    // SAFETY: the original NT function, called with valid NT arguments.
    unsafe {
        tramp(
            handle,
            event,
            apc,
            apc_ctx,
            iosb,
            byte_offset,
            length,
            key,
            fail_immediately,
            exclusive,
        )
    }
}

/// `NtUnlockFile` hook — the release half of [`lock_hook`], and success for
/// the same reason: a lock that was never recorded cannot fail to be released.
pub(super) unsafe fn unlock_hook_body(
    handle: HANDLE,
    iosb: *mut c_void,
    byte_offset: *const i64,
    length: *const i64,
    key: u32,
) -> NTSTATUS {
    let _hs = crate::hookstats::Timed::new(crate::hookstats::Hook::Unlock);
    let tramp = match TRAMP_UNLOCK.get() {
        Some(t) => t,
        None => return STATUS_UNSUCCESSFUL,
    };
    if crate::synth_file::is_fuse_synth(handle as isize) {
        if !open_synth(handle) {
            return STATUS_INVALID_HANDLE;
        }
        crate::hookstats::note_synthetic_lock("unlock", synth_path(handle).as_deref());
        // SAFETY: same NT-pointer contract as this fn (hook/mod.rs).
        unsafe { crate::ntbuf::iosb_set(iosb, STATUS_SUCCESS, 0) };
        return STATUS_SUCCESS;
    }
    // SAFETY: the original NT function, called with valid NT arguments.
    unsafe { tramp(handle, iosb, byte_offset, length, key) }
}

/// `NtFlushBuffersFile` hook. Success on a synthetic handle: the director owns
/// durability for everything behind one, and there is no user-mode buffer here
/// to push — `write_hook` forwards each write over the ring as it happens.
///
/// Unlike the lock pair this is not a lie about state, but it is still weaker
/// than what the caller asked for: it promises the bytes are durable, and what
/// it can actually guarantee is that they reached the director.
pub(super) unsafe fn flush_hook_body(handle: HANDLE, iosb: *mut c_void) -> NTSTATUS {
    let _hs = crate::hookstats::Timed::new(crate::hookstats::Hook::FlushBuffers);
    let tramp = match TRAMP_FLUSH.get() {
        Some(t) => t,
        None => return STATUS_UNSUCCESSFUL,
    };
    if crate::synth_file::is_fuse_synth(handle as isize) {
        if !open_synth(handle) {
            return STATUS_INVALID_HANDLE;
        }
        crate::hookstats::note_synthetic_lock("flush", synth_path(handle).as_deref());
        // SAFETY: same NT-pointer contract as this fn (hook/mod.rs).
        unsafe { crate::ntbuf::iosb_set(iosb, STATUS_SUCCESS, 0) };
        return STATUS_SUCCESS;
    }
    // SAFETY: the original NT function, called with valid NT arguments.
    unsafe { tramp(handle, iosb) }
}

/// `STATUS_PENDING`: an asynchronous handle's transfer has not finished yet.
const STATUS_PENDING: NTSTATUS = 0x103;

/// The status and count of a transfer through [`read_hook_body`] / [`write_hook_body`] into a
/// local `IO_STATUS_BLOCK`, waited for on `handle` if it is an asynchronous real handle.
fn settle(handle: HANDLE, status: NTSTATUS, io: &[usize; 2]) -> (NTSTATUS, usize) {
    if status == STATUS_PENDING {
        // SAFETY: waiting on a real file handle until its transfer completes.
        unsafe { windows_sys::Win32::System::Threading::WaitForSingleObject(handle, u32::MAX) };
        return (io[0] as u32 as NTSTATUS, io[1]);
    }
    (status, io[1])
}

/// `NtCopyFileChunk` hook. The kernel cannot copy from or to a synthetic handle (it fails
/// "invalid handle", and `CopyFile` with it), so with one on either side the chunk is read and
/// written through the shim's own paths: the source's served bytes, then the destination,
/// synchronously, as the call is for a synchronous handle. Real handles on both sides pass
/// straight through.
#[allow(clippy::too_many_arguments)]
pub(super) unsafe fn copy_chunk_hook_body(
    src: HANDLE,
    dst: HANDLE,
    event: HANDLE,
    iosb: *mut c_void,
    length: u32,
    src_offset: *const i64,
    dst_offset: *const i64,
    src_key: *const u32,
    dst_key: *const u32,
    flags: u32,
) -> NTSTATUS {
    let _hs = crate::hookstats::Timed::new(crate::hookstats::Hook::CopyChunk);
    let Some(tramp) = TRAMP_COPY_CHUNK.get() else {
        return STATUS_UNSUCCESSFUL;
    };
    let synth = |h: HANDLE| crate::synth_file::is_fuse_synth(h as isize);
    if !synth(src) && !synth(dst) {
        // SAFETY: the original NT function, called with the caller's NT arguments.
        return unsafe {
            tramp(
                src, dst, event, iosb, length, src_offset, dst_offset, src_key, dst_key, flags,
            )
        };
    }
    let mut buf = vec![0u8; length as usize];
    let mut io = [0usize; 2];
    // SAFETY: our own buffer and IO_STATUS_BLOCK; the caller's offset and key pointers.
    let read = unsafe {
        read_hook_body(
            src,
            core::ptr::null_mut(),
            core::ptr::null(),
            core::ptr::null(),
            io.as_mut_ptr().cast(),
            buf.as_mut_ptr().cast(),
            length,
            src_offset,
            src_key,
        )
    };
    let (status, count) = settle(src, read, &io);
    let (status, written) = if status < 0 {
        (status, 0)
    } else {
        let mut io = [0usize; 2];
        // SAFETY: as above, writing the bytes just read.
        let write = unsafe {
            write_hook_body(
                dst,
                core::ptr::null_mut(),
                core::ptr::null(),
                core::ptr::null(),
                io.as_mut_ptr().cast(),
                buf.as_mut_ptr().cast(),
                count as u32,
                dst_offset,
                dst_key,
            )
        };
        settle(dst, write, &io)
    };
    // SAFETY: the caller's IO_STATUS_BLOCK (hook/mod.rs).
    unsafe { crate::ntbuf::iosb_set(iosb, status, written) };
    if !event.is_null() {
        // SAFETY: signalling the caller's own event, as a completed transfer does.
        unsafe { windows_sys::Win32::System::Threading::SetEvent(event) };
    }
    status
}
/// `FSCTL_GET_REPARSE_POINT`: a virtual file is never a reparse point.
const FSCTL_GET_REPARSE_POINT: u32 = 0x0009_00A8;

/// What a control code on a synthetic handle gets: the answer of a file system without the
/// feature. The kernel must never see a synthetic handle, where it fails "invalid handle":
/// `CopyFile` probes the source with several codes and abandons the copy on that error.
fn synthetic_control_status(code: u32) -> NTSTATUS {
    if code == FSCTL_GET_REPARSE_POINT {
        STATUS_NOT_A_REPARSE_POINT
    } else {
        STATUS_INVALID_DEVICE_REQUEST
    }
}

/// `NtFsControlFile` hook. Synthetic handles get [`synthetic_control_status`]; real handles pass
/// straight through.
#[allow(clippy::too_many_arguments)]
pub(super) unsafe fn fsctl_hook_body(
    handle: HANDLE,
    event: HANDLE,
    apc: *const c_void,
    apc_ctx: *const c_void,
    iosb: *mut c_void,
    code: u32,
    input: *const c_void,
    input_len: u32,
    output: *mut c_void,
    output_len: u32,
) -> NTSTATUS {
    let _hs = crate::hookstats::Timed::new(crate::hookstats::Hook::FsControl);
    let Some(tramp) = TRAMP_FSCTL.get() else {
        return STATUS_UNSUCCESSFUL;
    };
    if crate::synth_file::is_fuse_synth(handle as isize) {
        return synthetic_control_status(code);
    }
    // SAFETY: the original NT function, called with the caller's NT arguments.
    unsafe {
        tramp(
            handle, event, apc, apc_ctx, iosb, code, input, input_len, output, output_len,
        )
    }
}

/// `NtDeviceIoControlFile` hook: as [`fsctl_hook_body`]. No stats row: every socket operation
/// passes through here.
#[allow(clippy::too_many_arguments)]
pub(super) unsafe fn ioctl_hook_body(
    handle: HANDLE,
    event: HANDLE,
    apc: *const c_void,
    apc_ctx: *const c_void,
    iosb: *mut c_void,
    code: u32,
    input: *const c_void,
    input_len: u32,
    output: *mut c_void,
    output_len: u32,
) -> NTSTATUS {
    let Some(tramp) = TRAMP_IOCTL.get() else {
        return STATUS_UNSUCCESSFUL;
    };
    if crate::synth_file::is_fuse_synth(handle as isize) {
        return synthetic_control_status(code);
    }
    // SAFETY: the original NT function, called with the caller's NT arguments.
    unsafe {
        tramp(
            handle, event, apc, apc_ctx, iosb, code, input, input_len, output, output_len,
        )
    }
}

/// `NtQueryEaFile` hook. A virtual file has no extended attributes (`FileEaInformation` says
/// so too); real handles pass straight through.
#[allow(clippy::too_many_arguments)]
pub(super) unsafe fn qea_hook_body(
    handle: HANDLE,
    iosb: *mut c_void,
    buffer: *mut c_void,
    length: u32,
    single: u8,
    ea_list: *const c_void,
    ea_list_len: u32,
    ea_index: *const u32,
    restart: u8,
) -> NTSTATUS {
    let _hs = crate::hookstats::Timed::new(crate::hookstats::Hook::QueryEa);
    let Some(tramp) = TRAMP_QEA.get() else {
        return STATUS_UNSUCCESSFUL;
    };
    if crate::synth_file::is_fuse_synth(handle as isize) {
        return STATUS_NO_EAS_ON_FILE;
    }
    // SAFETY: the original NT function, called with the caller's NT arguments.
    unsafe {
        tramp(
            handle,
            iosb,
            buffer,
            length,
            single,
            ea_list,
            ea_list_len,
            ea_index,
            restart,
        )
    }
}

/// `NtWriteFile` hook. For synthetic (fuse) write handles, forward the game's
/// buffer to the director overlay over the ring and complete the IRP; real handles
/// pass straight through. `ByteOffset` NULL / negative sentinel = current pos.
#[allow(clippy::too_many_arguments)]
pub(super) unsafe fn write_hook_body(
    handle: HANDLE,
    event: HANDLE,
    apc: *const c_void,
    apc_ctx: *const c_void,
    iosb: *mut c_void,
    buffer: *mut c_void,
    length: u32,
    byte_offset: *const i64,
    key: *const u32,
) -> NTSTATUS {
    let _hs = crate::hookstats::Timed::new(crate::hookstats::Hook::Write);
    let tramp = match TRAMP_WRITE.get() {
        Some(t) => t,
        None => return STATUS_UNSUCCESSFUL,
    };
    if crate::synth_file::is_fuse_synth(handle as isize) {
        crate::hookstats::note_read_completion(!apc.is_null(), !event.is_null());
        // SAFETY: same NT-pointer contract as this fn (hook/mod.rs).
        let explicit = unsafe { crate::ntbuf::explicit_offset(byte_offset) };
        if let Some(FileView {
            fh,
            size,
            position: pos,
            append_only,
            ..
        }) = crate::synth_file::lookup(handle as isize)
        {
            // Append-only access (FILE_APPEND_DATA without FILE_WRITE_DATA)
            // forces every write to the current end of file at the kernel
            // level, ignoring any offset the caller supplies — a real handle
            // enforces this itself; ours has to do it here.
            let off = if append_only {
                pos
            } else {
                explicit.unwrap_or(pos)
            };
            let want = length as usize;
            // The file is changing: the read cache drops it. (A write handle
            // already dropped it at open; this keeps the rule local.)
            if want > 0 {
                if let Some(f) = crate::synth_file::cache(handle as isize) {
                    crate::read_cache::invalidate(&f);
                }
            }
            let n = if want == 0 || buffer.is_null() {
                0usize
            } else {
                // SAFETY: NtWriteFile contract — buffer is readable for `length` bytes.
                let slice = unsafe { core::slice::from_raw_parts(buffer as *const u8, want) };
                match crate::director::global()
                    .ok_or(vfs_protocol::ST_IO_ERROR)
                    .and_then(|c| c.write(fh, off, slice))
                {
                    Ok(n) => n,
                    Err(_) => {
                        // SAFETY: same NT-pointer contract as this fn (hook/mod.rs).
                        unsafe { crate::ntbuf::iosb_set(iosb, STATUS_UNSUCCESSFUL, 0) };
                        return STATUS_UNSUCCESSFUL;
                    }
                }
            };
            // Append-only always tracks position (every write moved EOF
            // forward regardless of what the caller passed); otherwise only
            // an implicit-offset write consumes the file pointer.
            if append_only || explicit.is_none() {
                crate::synth_file::set_position(handle as isize, off + n as u64);
            }
            // The synthetic size was set once at open and never touched
            // since — a write that extends the file must bump it too, or
            // `read_hook`'s EOF check and `fuse_query_information`'s
            // `metadata().len()` keep reporting the pre-write length forever.
            // Only reachable now that writes actually reach the director
            // instead of falling through to a real file (whose kernel FCB
            // would have tracked this for free).
            let end = off + n as u64;
            if end > size {
                crate::synth_file::grow_size(handle as isize, end);
            }
            // SAFETY: same NT-pointer contract as this fn (hook/mod.rs).
            unsafe { crate::ntbuf::iosb_set(iosb, STATUS_SUCCESS, n) };
            if !event.is_null() {
                // SAFETY: FFI call with valid arguments.
                unsafe { windows_sys::Win32::System::Threading::SetEvent(event) };
            }
            return STATUS_SUCCESS;
        }
        // Tagged synth handle with no table entry — never hand it to the real
        // NtWriteFile (mirrors read_hook).
        return STATUS_UNSUCCESSFUL;
    }
    // SAFETY: the original NT function, called with valid NT arguments.
    unsafe {
        tramp(
            handle,
            event,
            apc,
            apc_ctx,
            iosb,
            buffer,
            length,
            byte_offset,
            key,
        )
    }
}

/// `NtReadFile` hook. Synthetic (fuse) handles are answered from the director
/// over the ring; real handles pass straight through. `ByteOffset` of NULL or
/// the "use current position" sentinel (-1/-2) means "current position".
#[allow(clippy::too_many_arguments)]
pub(super) unsafe fn read_hook_body(
    handle: HANDLE,
    event: HANDLE,
    apc: *const c_void,
    apc_ctx: *const c_void,
    iosb: *mut c_void,
    buffer: *mut c_void,
    length: u32,
    byte_offset: *const i64,
    key: *const u32,
) -> NTSTATUS {
    let _hs = crate::hookstats::Timed::new(crate::hookstats::Hook::Read);
    let tramp = match TRAMP_READ.get() {
        Some(t) => t,
        None => return STATUS_UNSUCCESSFUL,
    };
    if crate::synth_file::is_fuse_synth(handle as isize) {
        // SAFETY: same NT-pointer contract as this fn (hook/mod.rs).
        let explicit = unsafe { crate::ntbuf::explicit_offset(byte_offset) };
        if let Some(view) = crate::synth_file::lookup_read(handle as isize) {
            let (fh, size, pos) = (view.fh, view.size, view.position);
            let off = explicit.unwrap_or(pos);
            let want = length as usize;
            if off >= size {
                // SAFETY: same NT-pointer contract as this fn (hook/mod.rs).
                unsafe { crate::ntbuf::iosb_set(iosb, STATUS_END_OF_FILE, 0) };
                return STATUS_END_OF_FILE;
            }
            // Phase 1: fill the game's NtReadFile buffer in place (no intermediate tmp).
            let max = want.min((size - off) as usize);
            let n = if max == 0 || buffer.is_null() {
                0usize
            } else {
                // SAFETY: NtReadFile contract — buffer is writable for `length` bytes.
                let slice = unsafe { core::slice::from_raw_parts_mut(buffer as *mut u8, max) };
                // A small synchronous read of an immutable file is offered to
                // the read cache first. Not one that asked for completion by
                // APC or event (those keep exactly the path they had), and not
                // one whose handle's size has moved from what the cache was
                // told at open. A cache answer is `max` bytes, as the ring's
                // would be; `None` is the uncached read below, unchanged.
                let cached = match &view.cache {
                    Some(f) if apc.is_null() && event.is_null() && f.size() == Some(size) => {
                        crate::director::global().and_then(|c| c.read_cached(f, fh, off, slice))
                    }
                    _ => None,
                };
                match cached.ok_or(()).or_else(|()| {
                    crate::director::global()
                        .ok_or(vfs_protocol::ST_IO_ERROR)
                        .and_then(|c| c.read_fragmented(fh, off, slice))
                }) {
                    Ok(n) => n,
                    Err(_) => {
                        // SAFETY: same NT-pointer contract as this fn (hook/mod.rs).
                        unsafe { crate::ntbuf::iosb_set(iosb, STATUS_UNSUCCESSFUL, 0) };
                        return STATUS_UNSUCCESSFUL;
                    }
                }
            };
            {
                if explicit.is_none() {
                    crate::synth_file::set_position(handle as isize, off + n as u64);
                }
                let at_eof = off + n as u64 >= size;
                let status = if at_eof && n == 0 {
                    STATUS_END_OF_FILE
                } else {
                    STATUS_SUCCESS
                };
                // SAFETY: same NT-pointer contract as this fn (hook/mod.rs).
                unsafe { crate::ntbuf::iosb_set(iosb, status, n) };
                if !event.is_null() {
                    // SAFETY: FFI call with valid arguments.
                    unsafe { windows_sys::Win32::System::Threading::SetEvent(event) };
                }
                return status;
            }
        }
        return STATUS_UNSUCCESSFUL;
    }
    // SAFETY: the original NT function, called with valid NT arguments.
    unsafe {
        tramp(
            handle,
            event,
            apc,
            apc_ctx,
            iosb,
            buffer,
            length,
            byte_offset,
            key,
        )
    }
}
