//! `FILE_RENAME_INFORMATION` (and `_EX`) parsing.

/// The target of a rename, as the caller wrote it.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct RenameTarget {
    /// `RootDirectory`: 0 for an absolute target, otherwise a handle the name is relative to.
    pub root_dir: usize,
    /// `FileName`, decoded lossily from UTF-16.
    pub name: String,
}

/// Parse a `FILE_RENAME_INFORMATION` buffer laid out for this target: see
/// [`parse_rename_info_for`].
pub fn parse_rename_info(b: &[u8]) -> Option<RenameTarget> {
    parse_rename_info_for(b, core::mem::size_of::<usize>())
}

/// Parse a `FILE_RENAME_INFORMATION` buffer from a process whose pointers are `ptr` bytes wide.
///
/// `ReplaceIfExists` (or the `_EX` form's `Flags`) and padding come first, then the
/// pointer-aligned `RootDirectory` handle, `FileNameLength` (bytes) and `FileName`: 8, 16 and 20 on
/// a 64-bit target, 4, 8 and 12 on a 32-bit one. `None` for a buffer too short for the header or
/// for the name it declares, a `ptr` that is neither 4 nor 8, or a handle this target cannot hold.
pub fn parse_rename_info_for(b: &[u8], ptr: usize) -> Option<RenameTarget> {
    if ptr != 4 && ptr != 8 {
        return None;
    }
    let (root_off, len_off, name_off) = (ptr, 2 * ptr, 2 * ptr + 4);
    if b.len() < name_off {
        return None;
    }
    let mut handle = [0u8; 8];
    handle[..ptr].copy_from_slice(&b[root_off..root_off + ptr]);
    let root_dir = usize::try_from(u64::from_le_bytes(handle)).ok()?;
    let namelen = u32::from_le_bytes(b[len_off..len_off + 4].try_into().ok()?) as usize;
    let end = name_off.checked_add(namelen)?;
    let raw = b.get(name_off..end)?;
    let units: Vec<u16> = raw
        .as_chunks::<2>()
        .0
        .iter()
        .map(|c| u16::from_le_bytes(*c))
        .collect();
    Some(RenameTarget {
        root_dir,
        name: String::from_utf16_lossy(&units),
    })
}

#[cfg(test)]
mod tests {
    use super::*;

    /// A `FILE_RENAME_INFORMATION` as a process with `ptr`-byte pointers lays it out.
    fn rename_info_for(ptr: usize, root_dir: u64, name: &str) -> Vec<u8> {
        let units: Vec<u16> = name.encode_utf16().collect();
        let namelen = units.len() * 2;
        let name_off = 2 * ptr + 4;
        let mut buf = vec![0u8; name_off + namelen];
        buf[ptr..2 * ptr].copy_from_slice(&root_dir.to_le_bytes()[..ptr]);
        buf[2 * ptr..name_off].copy_from_slice(&(namelen as u32).to_le_bytes());
        for (i, u) in units.iter().enumerate() {
            buf[name_off + i * 2..name_off + 2 + i * 2].copy_from_slice(&u.to_le_bytes());
        }
        buf
    }

    fn rename_info(root_dir: usize, name: &str) -> Vec<u8> {
        rename_info_for(core::mem::size_of::<usize>(), root_dir as u64, name)
    }

    #[test]
    fn an_absolute_target_has_no_root_directory() {
        let t = parse_rename_info(&rename_info(0, r"\??\C:\root\new.esp")).unwrap();
        assert_eq!(t.root_dir, 0);
        assert_eq!(t.name, r"\??\C:\root\new.esp");
    }

    #[test]
    fn a_relative_target_carries_its_root_directory() {
        let t = parse_rename_info(&rename_info(0x4321, "new.esp")).unwrap();
        assert_eq!((t.root_dir, t.name.as_str()), (0x4321, "new.esp"));
    }

    /// Both layouts, whatever the host: the 64-bit one (handle at 8, length at 16, name at 20)
    /// and the 32-bit one (4, 8, 12).
    #[test]
    fn each_pointer_width_has_its_own_offsets() {
        let b64 = rename_info_for(8, 0x4321, "new.esp");
        assert_eq!(b64.len(), 20 + 14);
        let b32 = rename_info_for(4, 0x4321, "new.esp");
        assert_eq!(b32.len(), 12 + 14);
        for (b, ptr) in [(&b64, 8), (&b32, 4)] {
            let t = parse_rename_info_for(b, ptr).unwrap();
            assert_eq!((t.root_dir, t.name.as_str()), (0x4321, "new.esp"), "{ptr}");
        }
        // Read at the other width, the length lands on the handle or the name: never the same
        // answer.
        assert_ne!(
            parse_rename_info_for(&b32, 8).map(|t| t.name),
            Some("new.esp".into())
        );
    }

    #[test]
    fn a_short_header_or_a_name_past_the_end_is_declined() {
        for ptr in [4usize, 8] {
            assert_eq!(parse_rename_info_for(&vec![0u8; 2 * ptr + 3], ptr), None);
            let mut b = rename_info_for(ptr, 0, "abc");
            b.truncate(b.len() - 1);
            assert_eq!(parse_rename_info_for(&b, ptr), None);
            let mut huge = rename_info_for(ptr, 0, "a");
            huge[2 * ptr..2 * ptr + 4].copy_from_slice(&u32::MAX.to_le_bytes());
            assert_eq!(parse_rename_info_for(&huge, ptr), None);
        }
        assert_eq!(parse_rename_info_for(&[0u8; 64], 2), None);
    }

    #[test]
    fn an_empty_name_is_an_empty_string() {
        assert_eq!(parse_rename_info(&rename_info(0, "")).unwrap().name, "");
    }
}
