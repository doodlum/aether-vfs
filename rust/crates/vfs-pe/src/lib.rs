//! Pure PE byte parsing.

mod import_patch;
pub mod mem_patch;
pub use import_patch::{
    add_first_import, decode_steamstub_header, first_import_is, raise_stack_reserve,
    steamstub_flags, SHIM_IMPORT_DLL, SHIM_IMPORT_SYMBOL, STEAMSTUB_NO_MODULE_VERIFICATION,
};

fn rd_u16(b: &[u8], o: usize) -> u16 {
    u16::from_le_bytes([b[o], b[o + 1]])
}
fn rd_u32(b: &[u8], o: usize) -> u32 {
    u32::from_le_bytes(b[o..o + 4].try_into().unwrap())
}
fn rd_u64(b: &[u8], o: usize) -> u64 {
    u64::from_le_bytes(b[o..o + 8].try_into().unwrap())
}

/// `IMAGE_FILE_MACHINE_I386`.
pub const MACHINE_I386: u16 = 0x014C;
/// `IMAGE_FILE_MACHINE_AMD64`.
pub const MACHINE_AMD64: u16 = 0x8664;

/// The COFF header's `Machine` of the PE in `raw` (its first bytes are
/// enough), or `None` if it is not a PE.
pub fn pe_machine(raw: &[u8]) -> Option<u16> {
    let e_lfanew = u32::from_le_bytes(raw.get(0x3C..0x40)?.try_into().ok()?) as usize;
    if raw.get(..2)? != b"MZ" || raw.get(e_lfanew..e_lfanew.checked_add(4)?)? != b"PE\0\0" {
        return None;
    }
    let m = raw.get(e_lfanew + 4..e_lfanew + 6)?;
    Some(u16::from_le_bytes([m[0], m[1]]))
}

/// Whether the program in `raw` (its headers are enough) runs as a 32-bit
/// process on 64-bit Windows: a WOW64 guest, which needs the 32-bit shim.
///
/// An x86 image does, unless it is a .NET image the runtime starts 64-bit: an
/// IL-only assembly marked neither `32BITREQUIRED` nor `32BITPREFERRED`
/// (AnyCPU) carries an x86 header but runs as a 64-bit process. `None` if
/// `raw` is not a PE, or the CLR header lies past the bytes given.
pub fn runs_as_32bit(raw: &[u8]) -> Option<bool> {
    const COMIMAGE_FLAGS_ILONLY: u32 = 0x1;
    const COMIMAGE_FLAGS_32BITREQUIRED: u32 = 0x2;
    const COMIMAGE_FLAGS_32BITPREFERRED: u32 = 0x2_0000;
    if pe_machine(raw)? != MACHINE_I386 {
        return Some(false);
    }
    let e_lfanew = rd_u32(raw, 0x3C) as usize;
    if raw.len() < e_lfanew + 24 + 2 {
        return None;
    }
    let dd = dd_base(raw, e_lfanew);
    let n_dirs = raw
        .get(dd - 4..dd)
        .map(|b| u32::from_le_bytes(b.try_into().unwrap()))?;
    let clr_rva = if n_dirs > 14 {
        raw.get(dd + 14 * 8..dd + 14 * 8 + 4)
            .map(|b| u32::from_le_bytes(b.try_into().unwrap()))?
    } else {
        0
    };
    if clr_rva == 0 {
        return Some(true);
    }
    // The CLR header's `Flags` (at 16), found through the section table.
    let n_sections = rd_u16(raw, e_lfanew + 6) as usize;
    let sect = e_lfanew + 24 + rd_u16(raw, e_lfanew + 20) as usize;
    let off = (0..n_sections).find_map(|i| {
        let s = raw.get(sect + i * 40..sect + i * 40 + 40)?;
        let (va, vsize) = (rd_u32(s, 12) as usize, rd_u32(s, 8) as usize);
        let (raw_size, raw_ptr) = (rd_u32(s, 16) as usize, rd_u32(s, 20) as usize);
        let rva = clr_rva as usize;
        (rva >= va && rva < va + vsize.max(raw_size)).then(|| rva - va + raw_ptr)
    })?;
    let flags = raw
        .get(off + 16..off + 20)
        .map(|b| u32::from_le_bytes(b.try_into().unwrap()))?;
    let any_cpu = flags & COMIMAGE_FLAGS_ILONLY != 0
        && flags & (COMIMAGE_FLAGS_32BITREQUIRED | COMIMAGE_FLAGS_32BITPREFERRED) == 0;
    Some(!any_cpu)
}

/// True for a PE32+ (64-bit) optional header, false for PE32 (32-bit).
///
/// Both appear in a .NET application. Native and ReadyToRun images are PE32+,
/// but a **pure-IL assembly is emitted as PE32 even on x64** — the CLR consumes
/// it as metadata, not as native code, so the 32-bit header is not a statement
/// about the machine. `System.Runtime.dll` and the other framework facades are
/// exactly this.
pub fn is_pe32_plus(img: &[u8], e_lfanew: usize) -> bool {
    let opt = e_lfanew + 24;
    opt + 2 <= img.len() && rd_u16(img, opt) == 0x20B
}

/// Offset of the optional header's `DataDirectory` array.
///
/// The two formats differ only in the fixed part that precedes it: 112 bytes
/// for PE32+, 96 for PE32. Assuming 112 on a PE32 image reads a neighbouring
/// field as a directory RVA and quietly produces nonsense.
pub fn dd_base(img: &[u8], e_lfanew: usize) -> usize {
    let opt = e_lfanew + 24;
    if is_pe32_plus(img, e_lfanew) {
        opt + 112
    } else {
        opt + 96
    }
}

/// Build a flat in-memory image (SizeOfImage bytes) from a PE file: copy
/// headers, place each section at its VirtualAddress. Returns
/// `(image, preferred_image_base, e_lfanew)`.
pub fn build_image(raw: &[u8]) -> Result<(Vec<u8>, u64, usize), &'static str> {
    if raw.len() < 0x40 {
        return Err("PE too small");
    }
    let e_lfanew = rd_u32(raw, 0x3C) as usize;
    // 96, not 112: a PE32 optional header is shorter, and demanding the PE32+
    // length here rejected small IL assemblies before the magic was even read.
    // Checked: a hostile `e_lfanew` near `u32::MAX` overflows a 32-bit `usize`.
    if e_lfanew
        .checked_add(24 + 96)
        .is_none_or(|end| end > raw.len())
    {
        return Err("bad e_lfanew");
    }
    if &raw[e_lfanew..e_lfanew + 4] != b"PE\0\0" {
        return Err("bad PE sig");
    }
    let opt = e_lfanew + 24;
    let magic = rd_u16(raw, opt);
    if magic != 0x20B && magic != 0x10B {
        return Err("not a PE32 or PE32+ optional header");
    }
    let pe32_plus = magic == 0x20B;
    if pe32_plus && e_lfanew + 24 + 112 > raw.len() {
        return Err("bad e_lfanew");
    }
    // SizeOfImage and SizeOfHeaders sit at the same offsets in both formats;
    // ImageBase does not — PE32+ has 8 bytes at 24, PE32 has 4 at 28, because
    // PE32 spends 24..28 on BaseOfData.
    let size_of_image = rd_u32(raw, opt + 56) as usize;
    let size_of_headers = rd_u32(raw, opt + 60) as usize;
    let image_base = if pe32_plus {
        rd_u64(raw, opt + 24)
    } else {
        rd_u32(raw, opt + 28) as u64
    };
    let num_sections = rd_u16(raw, e_lfanew + 6) as usize;
    let size_opt = rd_u16(raw, e_lfanew + 20) as usize;
    let sect_base = opt + size_opt;

    let mut img = vec![0u8; size_of_image];
    if size_of_headers > raw.len() || size_of_headers > size_of_image {
        return Err("bad SizeOfHeaders");
    }
    img[..size_of_headers].copy_from_slice(&raw[..size_of_headers]);
    for i in 0..num_sections {
        let s = sect_base + i * 40;
        if s + 40 > raw.len() {
            return Err("section table OOB");
        }
        let va = rd_u32(raw, s + 12) as usize;
        let raw_sz = rd_u32(raw, s + 16) as usize;
        let raw_ptr = rd_u32(raw, s + 20) as usize;
        if raw_sz == 0 {
            continue;
        }
        if raw_ptr + raw_sz > raw.len() || va + raw_sz > size_of_image {
            return Err("section data OOB");
        }
        img[va..va + raw_sz].copy_from_slice(&raw[raw_ptr..raw_ptr + raw_sz]);
    }
    Ok((img, image_base, e_lfanew))
}

/// Apply `IMAGE_REL_BASED_DIR64` base relocations in-place for a new load base.
///
/// **PE32 images are left alone, deliberately.** Their fixups are
/// `IMAGE_REL_BASED_HIGHLOW`, a 32-bit field, and the only PE32 images that
/// reach this mapper are pure-IL assemblies that get placed wherever
/// `VirtualAlloc` finds room — far above 4 GiB. The delta does not fit, and
/// applying it truncated corrupts the image: doing exactly that turned a clean
/// load failure into an access violation inside the CLR. Nothing executes a
/// PE32 image here — the runtime reads it as metadata by RVA — so leaving it
/// unrelocated is correct, and is what Windows does when mapping an image for
/// data rather than for execution.
pub fn apply_relocs(img: &mut [u8], e_lfanew: usize, image_base: u64, new_base: u64) {
    if !is_pe32_plus(img, e_lfanew) {
        return;
    }
    let reloc_dir = dd_base(img, e_lfanew) + 5 * 8; // DataDirectory[BASERELOC]
    if reloc_dir + 8 > img.len() {
        return;
    }
    let reloc_rva = rd_u32(img, reloc_dir) as usize;
    let reloc_size = rd_u32(img, reloc_dir + 4) as usize;
    if reloc_rva == 0 || reloc_size == 0 {
        return;
    }
    let delta = new_base.wrapping_sub(image_base);
    let mut off = reloc_rva;
    let end = reloc_rva + reloc_size;
    while off + 8 <= end && off + 8 <= img.len() {
        let block_va = rd_u32(img, off) as usize;
        let block_sz = rd_u32(img, off + 4) as usize;
        if block_sz < 8 {
            break;
        }
        let count = (block_sz - 8) / 2;
        for i in 0..count {
            let eoff = off + 8 + i * 2;
            if eoff + 2 > img.len() {
                break;
            }
            let e = rd_u16(img, eoff);
            let typ = e >> 12;
            let o = (e & 0xFFF) as usize;
            if typ == 10 {
                let p = block_va + o;
                if p + 8 <= img.len() {
                    let v = rd_u64(img, p).wrapping_add(delta);
                    img[p..p + 8].copy_from_slice(&v.to_le_bytes());
                }
            }
        }
        off += block_sz;
    }
}

/// Collect import DLL names from a flat PE image (ANSI, no path).
pub fn import_dll_names(img: &[u8], e_lfanew: usize) -> Vec<String> {
    let imp_dir = dd_base(img, e_lfanew) + 8;
    let mut out = Vec::new();
    if imp_dir + 8 > img.len() {
        return out;
    }
    let mut desc = rd_u32(img, imp_dir) as usize;
    let imp_size = rd_u32(img, imp_dir + 4) as usize;
    if desc == 0 || imp_size == 0 {
        return out;
    }
    let desc_end = desc + imp_size;
    while desc + 20 <= img.len() && desc < desc_end {
        let name_rva = rd_u32(img, desc + 12) as usize;
        if name_rva == 0 {
            break;
        }
        if name_rva >= img.len() {
            break;
        }
        let mut end = name_rva;
        while end < img.len() && img[end] != 0 {
            end += 1;
        }
        out.push(String::from_utf8_lossy(&img[name_rva..end]).into_owned());
        desc += 20;
    }
    out
}

/// Find an export's RVA by name (RVAs index into the flat image).
pub fn export_rva(img: &[u8], e_lfanew: usize, name: &[u8]) -> Result<u32, &'static str> {
    let exp_dir = dd_base(img, e_lfanew); // DataDirectory[IMAGE_DIRECTORY_ENTRY_EXPORT]
    if exp_dir + 8 > img.len() {
        return Err("no export dir");
    }
    let exp_rva = rd_u32(img, exp_dir) as usize;
    if exp_rva == 0 || exp_rva + 40 > img.len() {
        return Err("empty export dir");
    }
    let num_names = rd_u32(img, exp_rva + 24) as usize;
    let addr_funcs = rd_u32(img, exp_rva + 28) as usize;
    let addr_names = rd_u32(img, exp_rva + 32) as usize;
    let addr_ords = rd_u32(img, exp_rva + 36) as usize;
    for i in 0..num_names {
        let name_rva = rd_u32(img, addr_names + i * 4) as usize;
        if name_rva >= img.len() {
            continue;
        }
        let mut e = name_rva;
        while e < img.len() && img[e] != 0 {
            e += 1;
        }
        if &img[name_rva..e] == name {
            let ord = rd_u16(img, addr_ords + i * 2) as usize;
            return Ok(rd_u32(img, addr_funcs + ord * 4));
        }
    }
    Err("export not found")
}

/// File name for a cached on-disk copy of a VFS-served PE image (the shim
/// backs image sections with such a copy, because the kernel builds an image
/// section only from a real file).
///
/// It depends on the vpath (ASCII case folded), the length **and every byte**:
/// two different builds of a DLL of the same size (a one-byte patch, an update
/// that kept the size) must never share a copy, or the process silently runs
/// the stale one. The content hash is not cryptographic; it only has to tell
/// files apart, and reads 8 bytes per step so hashing the plugin DLLs of a
/// large load order costs milliseconds.
pub fn image_cache_name(vpath: &str, pe: &[u8]) -> String {
    let mut path_hash: u64 = 0xcbf2_9ce4_8422_2325;
    for b in vpath.bytes().map(|b| b.to_ascii_lowercase()) {
        path_hash ^= b as u64;
        path_hash = path_hash.wrapping_mul(0x1000_0000_01b3);
    }
    let mut content: u64 = 0x9e37_79b9_7f4a_7c15 ^ pe.len() as u64;
    let (words, rest) = pe.as_chunks::<8>();
    for w in words {
        let v = u64::from_le_bytes(*w);
        content = (content ^ v)
            .wrapping_mul(0xff51_afd7_ed55_8ccd)
            .rotate_left(29);
    }
    let mut tail = [0u8; 8];
    tail[..rest.len()].copy_from_slice(rest);
    content = (content ^ u64::from_le_bytes(tail) ^ ((rest.len() as u64) << 56))
        .wrapping_mul(0xc4ce_b9fe_1a85_ec53);
    content ^= content >> 33;
    format!("{path_hash:016x}-{content:016x}-{:x}.bin", pe.len())
}

/// Whether `name` (an import as a PE names it) is part of Windows, so the
/// staging walk leaves it to the loader. Not the DirectX June 2010 runtime
/// (`xinput1_3.dll`, `x3daudio1_7.dll`, `xaudio2_7.dll`, the `d3dx*`):
/// Windows 10 and 11 do not have it, so a game importing one gets it staged
/// from the VFS or a host's fallback folder, or fails to start. (Wine has
/// builtins of them, so a Proton launch with neither still starts.)
pub fn is_system_import_dll(name: &str) -> bool {
    let n = name.to_ascii_lowercase();
    // Not `Path::file_name()`: that treats `\` as a separator only on Windows,
    // so the same import table would classify differently per host. A PE names
    // its imports with Windows conventions no matter who reads the file, so
    // both separators are split here explicitly.
    let base = n.rsplit(['/', '\\']).next().unwrap_or(&n);
    base.starts_with("api-ms-")
        || base.starts_with("ext-ms-")
        || matches!(
            base,
            "kernel32.dll"
                | "kernelbase.dll"
                | "ntdll.dll"
                | "user32.dll"
                | "gdi32.dll"
                | "gdi32full.dll"
                | "advapi32.dll"
                | "shell32.dll"
                | "ole32.dll"
                | "oleaut32.dll"
                | "ws2_32.dll"
                | "winhttp.dll"
                | "winmm.dll"
                | "setupapi.dll"
                | "hid.dll"
                | "d3d11.dll"
                | "dxgi.dll"
                | "dinput8.dll"
                | "xinput1_4.dll"
                | "msvcp140.dll"
                | "vcruntime140.dll"
                | "vcruntime140_1.dll"
                | "ucrtbase.dll"
                | "sechost.dll"
                | "rpcrt4.dll"
                | "combase.dll"
                | "shlwapi.dll"
                | "version.dll"
                | "imm32.dll"
                | "dwmapi.dll"
                | "uxtheme.dll"
                | "bcrypt.dll"
                | "bcryptprimitives.dll"
                | "crypt32.dll"
                | "wintrust.dll"
                | "psapi.dll"
                | "userenv.dll"
                | "dbghelp.dll"
        )
}

pub fn pe_looks_like_image(pe: &[u8]) -> bool {
    pe.len() >= 0x40 && pe[0] == b'M' && pe[1] == b'Z'
}

/// Import DLL names of a raw PE, flattening the image first.
pub fn import_dll_names_of_pe(raw: &[u8]) -> Option<Vec<String>> {
    let (img, _base, e_lfanew) = build_image(raw).ok()?;
    Some(import_dll_names(&img, e_lfanew))
}

#[cfg(test)]
mod tests {
    #[test]
    fn image_cache_names_follow_every_byte_and_the_path() {
        let a = vec![7u8; 4096 + 5];
        let mut b = a.clone();
        b[1234] ^= 1;
        let mut c = a.clone();
        c[4096 + 4] ^= 0x80;
        let p = r"C:\Game\Data\SKSE\Plugins\X.dll";
        assert_eq!(image_cache_name(p, &a), image_cache_name(p, &a));
        assert_ne!(
            image_cache_name(p, &a),
            image_cache_name(p, &b),
            "one byte in a word"
        );
        assert_ne!(
            image_cache_name(p, &a),
            image_cache_name(p, &c),
            "one byte in the tail"
        );
        assert_ne!(
            image_cache_name(p, &a),
            image_cache_name(p, &a[..4096]),
            "length"
        );
        assert_ne!(
            image_cache_name(p, &a),
            image_cache_name(r"C:\Game\Y.dll", &a),
            "path"
        );
        assert_eq!(
            image_cache_name(p, &a),
            image_cache_name(&p.to_uppercase(), &a),
            "case"
        );
        assert!(image_cache_name(p, &a).ends_with(".bin"));
    }

    use super::*;

    /// A 64-byte buffer starting "MZ" is the minimum this predicate accepts.
    /// It is a cheap gate, not validation: `build_image` does the real checks.
    #[test]
    fn mz_magic_and_minimum_length_gate_the_image() {
        let mut buf = vec![0u8; 0x40];
        buf[0] = b'M';
        buf[1] = b'Z';
        assert!(pe_looks_like_image(&buf));

        assert!(!pe_looks_like_image(&buf[..0x3F]), "under 0x40 is rejected");

        buf[0] = b'X';
        assert!(!pe_looks_like_image(&buf), "wrong magic is rejected");
    }

    /// Classification is by file name only and is case- and path-insensitive,
    /// because import tables spell system DLLs inconsistently.
    ///
    /// The backslash case is the one that matters here: it must hold on Linux
    /// too, which is why the implementation splits separators explicitly instead
    /// of asking `std::path` — `Path::file_name()` would return the whole string
    /// on a non-Windows host and classify a system DLL as a game-local one.
    #[test]
    fn system_dll_classification_ignores_case_and_directory() {
        assert!(is_system_import_dll("KERNEL32.dll"));
        assert!(is_system_import_dll("kernel32.dll"));
        assert!(is_system_import_dll("C:\\Windows\\System32\\kernel32.dll"));
        assert!(is_system_import_dll("System32/kernel32.dll"));
        assert!(!is_system_import_dll("steam_api64.dll"));
        // Windows 10 and 11 lack the DirectX June 2010 runtime.
        for redist in [
            "XINPUT1_3.dll",
            "X3DAudio1_7.dll",
            "xaudio2_7.dll",
            "d3dx11_43.dll",
        ] {
            assert!(!is_system_import_dll(redist), "{redist}");
        }
        assert!(!is_system_import_dll("C:\\game\\steam_api64.dll"));
    }

    /// A truncated buffer must return Err, never panic: `build_image` is the
    /// first thing that touches attacker-influenced bytes on the staging path.
    #[test]
    fn build_image_rejects_a_truncated_header_without_panicking() {
        assert!(build_image(&[]).is_err());
        assert!(build_image(b"MZ").is_err());

        let mut buf = vec![0u8; 0x40];
        buf[0] = b'M';
        buf[1] = b'Z';
        // e_lfanew points past the end of the buffer.
        buf[0x3C..0x40].copy_from_slice(&0xFFFF_FFFFu32.to_le_bytes());
        assert!(build_image(&buf).is_err());
    }

    /// A PE32 header with one section at RVA 0x1000 (file offset 0x200), and a
    /// CLR header there with `clr_flags` when given.
    fn pe32(machine: u16, clr_flags: Option<u32>) -> Vec<u8> {
        let mut b = vec![0u8; 0x400];
        b[..2].copy_from_slice(b"MZ");
        b[0x3C..0x40].copy_from_slice(&0x80u32.to_le_bytes());
        b[0x80..0x84].copy_from_slice(b"PE\0\0");
        b[0x84..0x86].copy_from_slice(&machine.to_le_bytes());
        b[0x86..0x88].copy_from_slice(&1u16.to_le_bytes());
        b[0x94..0x96].copy_from_slice(&224u16.to_le_bytes());
        let opt = 0x98;
        b[opt..opt + 2].copy_from_slice(&0x10Bu16.to_le_bytes());
        b[opt + 92..opt + 96].copy_from_slice(&16u32.to_le_bytes());
        let sect = opt + 224;
        b[sect + 8..sect + 12].copy_from_slice(&0x100u32.to_le_bytes()); // VirtualSize
        b[sect + 12..sect + 16].copy_from_slice(&0x1000u32.to_le_bytes()); // VirtualAddress
        b[sect + 16..sect + 20].copy_from_slice(&0x100u32.to_le_bytes()); // SizeOfRawData
        b[sect + 20..sect + 24].copy_from_slice(&0x200u32.to_le_bytes()); // PointerToRawData
        if let Some(flags) = clr_flags {
            let clr = opt + 96 + 14 * 8;
            b[clr..clr + 4].copy_from_slice(&0x1000u32.to_le_bytes());
            b[0x200 + 16..0x200 + 20].copy_from_slice(&flags.to_le_bytes());
        }
        b
    }

    #[test]
    fn the_machine_and_the_process_width_come_from_the_headers() {
        assert_eq!(pe_machine(&pe32(MACHINE_I386, None)), Some(MACHINE_I386));
        assert_eq!(pe_machine(b"MZ"), None);
        assert_eq!(runs_as_32bit(&pe32(MACHINE_I386, None)), Some(true));
        assert_eq!(runs_as_32bit(&pe32(MACHINE_AMD64, None)), Some(false));
        assert_eq!(runs_as_32bit(b"not a pe"), None);
        // .NET: AnyCPU runs 64-bit; 32BITREQUIRED and 32BITPREFERRED run 32-bit.
        assert_eq!(runs_as_32bit(&pe32(MACHINE_I386, Some(0x1))), Some(false));
        assert_eq!(runs_as_32bit(&pe32(MACHINE_I386, Some(0x3))), Some(true));
        assert_eq!(
            runs_as_32bit(&pe32(MACHINE_I386, Some(0x2_0003))),
            Some(true)
        );
        // Mixed-mode (not IL-only) x86 is 32-bit.
        assert_eq!(runs_as_32bit(&pe32(MACHINE_I386, Some(0x0))), Some(true));
        // Headers cut short before the optional header: unknown, not a panic.
        assert_eq!(runs_as_32bit(&pe32(MACHINE_I386, None)[..0xA0]), None);
    }

    /// An import directory of zero yields no names rather than an error.
    #[test]
    fn import_dll_names_is_empty_when_there_is_no_import_directory() {
        let img = vec![0u8; 0x400];
        assert!(import_dll_names(&img, 0x80).is_empty());
    }
}
