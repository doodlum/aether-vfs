//! Make a mapped image import one more DLL first, without touching any byte
//! the image already occupies.
//!
//! The file patcher ([`crate::add_first_import`]) appends a section and edits
//! the import directory. It cannot be used on a program whose file carries an
//! overlay (data after the last section, which SteamStub's wrapper does), and
//! rewriting the file at all breaks a DRM wrapper that hashes it. So for such
//! a program the shim is added **after it is mapped**, in the suspended
//! process, before its first thread runs:
//!
//! - a fresh region is allocated near the image and filled with a copy of the
//!   import descriptor array, one new descriptor (the shim) prepended, plus
//!   its lookup table, address table and names;
//! - the image's import directory is pointed at that region, and the
//!   bound-import directory cleared, by editing only the PE **header**.
//!
//! No existing section byte moves. The one risk is a wrapper that also hashes
//! its headers; the caller gates on that and falls back to injection.
//!
//! PE32+ (x64) and PE32 (x86, a WOW64 guest) images are both patched: they
//! differ only in where the data directories sit and in the thunk width.
//!
//! This module is pure: it parses header bytes and builds the region and the
//! header edits. Reading the mapped image, allocating and writing it back is
//! the caller's (`vfs-inject`, Windows only), because in a mapped image an
//! RVA is simply `base + rva` — the sections are already at their virtual
//! addresses — so there is no file-offset translation to do here.

use crate::import_patch::{SHIM_IMPORT_DLL, SHIM_IMPORT_SYMBOL};
use crate::{rd_u16, rd_u32};

/// What the caller reads from, allocates for, and writes to the mapped image.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct MemPatchPlan {
    /// Where the existing import descriptor array is (`base + this`), to read
    /// the originals from. Zero when the image imports nothing.
    pub import_rva: u32,
    /// `SizeOfImage`: the caller allocates its region at or after `base +
    /// this`, so the region's RVA is past the image and contiguous-ish.
    pub size_of_image: u32,
    /// Section alignment, to round the region's RVA.
    pub section_alignment: u32,
    /// The image has a SteamStub `.bind` section. The caller must decide
    /// whether the wrapper verifies before patching in memory.
    pub has_bind_section: bool,
    /// Header offset of the import directory entry (RVA u32, size u32).
    pub import_dir_off: u32,
    /// Header offset of the bound-import directory entry, if the image has that
    /// slot; cleared so the loader does not use a now-wrong bound table.
    pub bound_dir_off: Option<u32>,
    /// Bytes in one import thunk: 8 for a PE32+ (x64) image, 4 for a PE32
    /// (x86) one.
    pub thunk: u32,
}

/// The region to write and the header bytes to change, both ready to apply.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct MemPatch {
    /// Bytes to write at `base + region_rva`.
    pub region: Vec<u8>,
    /// `(header offset, bytes)` edits, each within `SizeOfHeaders`.
    pub header_edits: Vec<(u32, Vec<u8>)>,
}

/// Parse `headers` (at least `SizeOfHeaders` bytes, read from the mapped
/// image at its base) for an in-memory import patch. The same validation the
/// file patcher does, minus what only a file layout has.
pub fn plan_mem_patch(headers: &[u8]) -> Result<MemPatchPlan, &'static str> {
    if headers.len() < 0x40 || &headers[..2] != b"MZ" {
        return Err("not an MZ image");
    }
    let e_lfanew = rd_u32(headers, 0x3C) as usize;
    if headers.get(e_lfanew..e_lfanew + 4) != Some(&b"PE\0\0"[..]) {
        return Err("bad PE signature");
    }
    if headers.len() < e_lfanew + 24 + 2 {
        return Err("headers shorter than the PE and optional headers");
    }
    let n_sections = rd_u16(headers, e_lfanew + 6) as usize;
    let size_opt = rd_u16(headers, e_lfanew + 20) as usize;
    let opt = e_lfanew + 24;
    // A PE32 (x86) image differs only in where the data directories start (it
    // has `BaseOfData` and 4-byte stack and heap sizes) and in its thunk size.
    let (n_dirs_off, dd, thunk) = match rd_u16(headers, opt) {
        0x20B => (opt + 108, opt + 112, 8),
        0x10B => (opt + 92, opt + 96, 4),
        _ => return Err("unknown optional header magic"),
    };
    let section_alignment = rd_u32(headers, opt + 32);
    let size_of_image = rd_u32(headers, opt + 56);
    if headers.len() < dd + 16 * 8 {
        return Err("headers shorter than the data directories");
    }
    let n_dirs = rd_u32(headers, n_dirs_off) as usize;
    if n_dirs <= IMPORT {
        return Err("no import directory slot");
    }
    if n_dirs > 14 && rd_u32(headers, dd + 14 * 8) != 0 {
        return Err("a .NET image (CLR header present) is not supported");
    }
    let sect_base = opt + size_opt;
    let end = sect_base + n_sections * 40;
    if headers.len() < end {
        return Err("headers shorter than the section table");
    }
    let has_bind_section = (0..n_sections)
        .any(|i| &headers[sect_base + i * 40..sect_base + i * 40 + 8] == b".bind\0\0\0");
    Ok(MemPatchPlan {
        import_rva: rd_u32(headers, dd + IMPORT * 8),
        size_of_image,
        section_alignment,
        has_bind_section,
        import_dir_off: (dd + IMPORT * 8) as u32,
        bound_dir_off: (n_dirs > BOUND).then_some((dd + BOUND * 8) as u32),
        thunk,
    })
}

const IMPORT: usize = 1;
const BOUND: usize = 11;
/// One `IMAGE_IMPORT_DESCRIPTOR`.
const DESC: usize = 20;

/// The first RVA the caller should try to place the region at: just past the
/// image, section-aligned, so it is close enough that `base + rva` still fits
/// a 32-bit RVA. The caller may try later candidates (this plus multiples of
/// the alignment) if that address is taken, rebuilding with the one it gets.
pub fn first_region_rva(plan: &MemPatchPlan) -> u32 {
    align_up(plan.size_of_image, plan.section_alignment.max(1))
}

/// Build the region and header edits that make the image import the shim
/// first, with the region placed at `region_rva` (`base + region_rva` in the
/// process). `old_descriptors` is the existing descriptor array as read from
/// `base + plan.import_rva` (through, but not including, the zero terminator;
/// empty when the image imports nothing).
pub fn build_mem_patch(
    plan: &MemPatchPlan,
    old_descriptors: &[u8],
    region_rva: u32,
) -> Result<MemPatch, &'static str> {
    if !old_descriptors.len().is_multiple_of(DESC) {
        return Err("import descriptor array is not a whole number of descriptors");
    }
    let dll = SHIM_IMPORT_DLL;
    let func = SHIM_IMPORT_SYMBOL;
    let n_old = old_descriptors.len() / DESC;

    let thunk = plan.thunk as usize;
    let desc_len = (n_old + 2) * DESC;
    let ilt_off = align_up(desc_len as u32, 8) as usize;
    let iat_off = ilt_off + 2 * thunk;
    let hint_off = iat_off + 2 * thunk;
    let name_off = align_up((hint_off + 2 + func.len() + 1) as u32, 2) as usize;
    let body_len = name_off + dll.len() + 1;
    let r = region_rva as usize;
    let mut body = vec![0u8; body_len];
    // Our descriptor, then the originals, then the all-zero terminator.
    wr_u32(&mut body, 0, (r + ilt_off) as u32); // OriginalFirstThunk
    wr_u32(&mut body, 12, (r + name_off) as u32); // Name
    wr_u32(&mut body, 16, (r + iat_off) as u32); // FirstThunk
    body[DESC..DESC + old_descriptors.len()].copy_from_slice(old_descriptors);
    // One import by name; the high bit clear means "by name".
    wr_u32(&mut body, ilt_off, (r + hint_off) as u32);
    wr_u32(&mut body, iat_off, (r + hint_off) as u32);
    body[hint_off + 2..hint_off + 2 + func.len()].copy_from_slice(func.as_bytes());
    body[name_off..name_off + dll.len()].copy_from_slice(dll.as_bytes());

    let mut header_edits = Vec::new();
    let mut dir = vec![0u8; 8];
    wr_u32(&mut dir, 0, region_rva);
    wr_u32(&mut dir, 4, desc_len as u32);
    header_edits.push((plan.import_dir_off, dir));
    if let Some(b) = plan.bound_dir_off {
        header_edits.push((b, vec![0u8; 8]));
    }
    Ok(MemPatch {
        region: body,
        header_edits,
    })
}

fn align_up(v: u32, a: u32) -> u32 {
    if a == 0 {
        v
    } else {
        v.div_ceil(a) * a
    }
}

fn wr_u32(b: &mut [u8], o: usize, v: u32) {
    b[o..o + 4].copy_from_slice(&v.to_le_bytes());
}

#[cfg(test)]
mod tests {
    use super::*;

    /// A minimal PE32+ header with one section and `n_dirs` data directories,
    /// the import directory pointing at `imp_rva`.
    fn headers(imp_rva: u32, n_dirs: u32, bind: bool, size_of_image: u32) -> Vec<u8> {
        headers_of(true, imp_rva, n_dirs, bind, size_of_image)
    }

    /// [`headers`], PE32+ or PE32 (whose data directories start 16 bytes
    /// earlier, in a 224-byte optional header).
    fn headers_of(
        plus: bool,
        imp_rva: u32,
        n_dirs: u32,
        bind: bool,
        size_of_image: u32,
    ) -> Vec<u8> {
        let e_lfanew = 0x80usize;
        let opt = e_lfanew + 24;
        let size_opt = if plus { 240usize } else { 224 };
        let sect = opt + size_opt;
        let mut h = vec![0u8; sect + 40];
        h[..2].copy_from_slice(b"MZ");
        wr_u32(&mut h, 0x3C, e_lfanew as u32);
        h[e_lfanew..e_lfanew + 4].copy_from_slice(b"PE\0\0");
        h[e_lfanew + 6..e_lfanew + 8].copy_from_slice(&1u16.to_le_bytes()); // sections
        h[e_lfanew + 20..e_lfanew + 22].copy_from_slice(&(size_opt as u16).to_le_bytes());
        let magic: u16 = if plus { 0x20B } else { 0x10B };
        h[opt..opt + 2].copy_from_slice(&magic.to_le_bytes());
        wr_u32(&mut h, opt + 32, 0x1000); // section alignment
        wr_u32(&mut h, opt + 56, size_of_image); // SizeOfImage
        let (n_dirs_off, dd) = if plus {
            (opt + 108, opt + 112)
        } else {
            (opt + 92, opt + 96)
        };
        wr_u32(&mut h, n_dirs_off, n_dirs);
        wr_u32(&mut h, dd + IMPORT * 8, imp_rva);
        wr_u32(&mut h, dd + IMPORT * 8 + 4, 40);
        if bind {
            h[sect..sect + 6].copy_from_slice(b".bind\0");
        } else {
            h[sect..sect + 6].copy_from_slice(b".text\0");
        }
        h
    }

    #[test]
    fn plan_reads_the_import_dir_bind_and_size() {
        let p = plan_mem_patch(&headers(0x2000, 16, true, 0x9000)).unwrap();
        assert_eq!(p.import_rva, 0x2000);
        assert_eq!(p.size_of_image, 0x9000);
        assert_eq!(p.section_alignment, 0x1000);
        assert!(p.has_bind_section);
        assert_eq!(p.bound_dir_off, Some((0x80 + 24 + 112 + BOUND * 8) as u32));
    }

    /// A PE32 (32-bit) image reads its directories 16 bytes earlier and gets
    /// 4-byte thunks: the lookup table, the address table and the hint/name
    /// follow each other at that width.
    #[test]
    fn a_pe32_image_is_planned_and_built_with_4_byte_thunks() {
        let p = plan_mem_patch(&headers_of(false, 0x2000, 16, false, 0x9000)).unwrap();
        assert_eq!(p.thunk, 4);
        assert_eq!(p.import_rva, 0x2000);
        assert_eq!(p.import_dir_off, (0x80 + 24 + 96 + IMPORT * 8) as u32);
        assert_eq!(p.bound_dir_off, Some((0x80 + 24 + 96 + BOUND * 8) as u32));
        assert_eq!(
            plan_mem_patch(&headers(0x2000, 16, false, 0x9000))
                .unwrap()
                .thunk,
            8
        );

        let rva = first_region_rva(&p);
        let patch = build_mem_patch(&p, &[0u8; DESC], rva).unwrap();
        let ilt = u32::from_le_bytes(patch.region[0..4].try_into().unwrap()) - rva;
        let iat = u32::from_le_bytes(patch.region[16..20].try_into().unwrap()) - rva;
        assert_eq!(iat - ilt, 2 * 4, "two 4-byte thunks between ILT and IAT");
        let hint = u32::from_le_bytes(
            patch.region[ilt as usize..ilt as usize + 4]
                .try_into()
                .unwrap(),
        );
        assert_eq!(hint - rva, iat + 2 * 4, "the hint/name follows the IAT");
        let name = &patch.region[(hint - rva) as usize + 2..];
        assert!(name.starts_with(SHIM_IMPORT_SYMBOL.as_bytes()));
    }

    #[test]
    fn an_unknown_magic_is_refused() {
        let mut h = headers(0x2000, 16, false, 0x9000);
        h[0x80 + 24..0x80 + 26].copy_from_slice(&0x107u16.to_le_bytes());
        assert!(plan_mem_patch(&h).is_err());
    }

    #[test]
    fn a_small_dir_count_has_no_bound_slot() {
        let p = plan_mem_patch(&headers(0x2000, 2, false, 0x9000)).unwrap();
        assert!(!p.has_bind_section);
        assert_eq!(p.bound_dir_off, None);
    }

    #[test]
    fn the_region_prepends_our_descriptor_and_keeps_the_originals() {
        let plan = plan_mem_patch(&headers(0x2000, 16, false, 0x9000)).unwrap();
        // Two existing descriptors.
        let mut old = vec![0u8; 2 * DESC];
        wr_u32(&mut old, 12, 0x3000); // first original's Name RVA, a marker
        wr_u32(&mut old, DESC + 12, 0x3100);
        let rva = first_region_rva(&plan);
        let patch = build_mem_patch(&plan, &old, rva).unwrap();
        assert_eq!(rva, 0x9000, "region goes at SizeOfImage, section-aligned");

        // The directory entry now points at the region, sized for 4 descriptors
        // (ours + 2 + terminator).
        let (off, bytes) = &patch.header_edits[0];
        assert_eq!(*off, plan.import_dir_off);
        assert_eq!(u32::from_le_bytes(bytes[..4].try_into().unwrap()), 0x9000);
        assert_eq!(u32::from_le_bytes(bytes[4..].try_into().unwrap()), 4 * 20);
        // The bound-import entry is cleared.
        assert_eq!(
            patch.header_edits[1],
            (plan.bound_dir_off.unwrap(), vec![0u8; 8])
        );

        // Descriptor 0 is ours; its Name points inside the region.
        let name_rva = u32::from_le_bytes(patch.region[12..16].try_into().unwrap());
        let name_off = (name_rva - rva) as usize;
        assert_eq!(
            &patch.region[name_off..name_off + SHIM_IMPORT_DLL.len()],
            SHIM_IMPORT_DLL.as_bytes()
        );
        // Descriptors 1 and 2 are the originals, in order.
        assert_eq!(
            u32::from_le_bytes(patch.region[DESC + 12..DESC + 16].try_into().unwrap()),
            0x3000
        );
        assert_eq!(
            u32::from_le_bytes(
                patch.region[2 * DESC + 12..2 * DESC + 16]
                    .try_into()
                    .unwrap()
            ),
            0x3100
        );
        // The terminator is all zero.
        assert!(patch.region[3 * DESC..4 * DESC].iter().all(|&b| b == 0));

        // The ILT and IAT both name the one import by name (high bit clear).
        let ilt = u32::from_le_bytes(
            patch.region[align_up((4 * 20) as u32, 8) as usize..][..4]
                .try_into()
                .unwrap(),
        );
        assert_ne!(ilt, 0);
        assert_eq!(ilt & 0x8000_0000, 0);
    }

    #[test]
    fn an_image_with_no_imports_still_gets_ours() {
        let plan = plan_mem_patch(&headers(0, 16, false, 0x4000)).unwrap();
        let patch = build_mem_patch(&plan, &[], first_region_rva(&plan)).unwrap();
        // Just ours plus the terminator.
        assert_eq!(
            u32::from_le_bytes(patch.header_edits[0].1[4..].try_into().unwrap()),
            2 * 20
        );
        let name_rva = u32::from_le_bytes(patch.region[12..16].try_into().unwrap());
        assert_ne!(name_rva, 0);
    }

    #[test]
    fn a_ragged_descriptor_array_is_refused() {
        let plan = plan_mem_patch(&headers(0x2000, 16, false, 0x9000)).unwrap();
        assert!(build_mem_patch(&plan, &[0u8; 19], first_region_rva(&plan)).is_err());
    }
}
