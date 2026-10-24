//! The tag bits that mark the shim's synthetic handles, in one place.
//!
//! Three families of handle are handed to the game that no kernel object stands behind. Each is a
//! slot number OR-ed with a tag, and the tags must never overlap, or a handle of one family reads
//! as another's:
//!
//! | family | tag (64-bit) | tag (32-bit) | module |
//! |---|---|---|---|
//! | synthetic *section* (an `NtCreateSection` result) | [`SYNTH_SECTION_TAG`], 2^45 | top nibble `3` | `synth_section` |
//! | synthetic *file* (a director file handle) | [`SYNTH_FILE_TAG`], 2^47 | top nibble `2` | `synth_file` |
//! | synthetic registry *key* | [`REG_TAG`], `0x6000_0000` | the same | `regkeys` |
//!
//! 2^46 belonged to the zip-window file handles that gate 4 task 7 removed, and is unassigned.
//!
//! **A 32-bit shim** has 31 bits below the sign, and real handles use the low 26 of them:
//! Windows' handle values are an index times 4 with fewer than 2^24 entries, Wine's local ones
//! are the same, and Wine's global ones are a local one XOR `0x544a4def`, whose top nibble is
//! `5`. So the families are told apart by the top nibble as a whole rather than by one bit: `0`
//! (a real local handle), `2` (file), `3` (section), `5` (a Wine global handle), `6`/`7` (a key:
//! [`REG_TAG`] is bits 29 and 30), `8` and up (negative: pseudo-handles). Each payload stays
//! below 2^28 ([`FILE_PAYLOAD`], [`SECTION_PAYLOAD`]) so it never reaches the nibble.

/// Tag bit (2^45) marking a synthetic *section* handle (an `NtCreateSection` result); real kernel
/// handles never reach this magnitude. The sign bit (2^63) stays clear so the value is a positive
/// handle, never confused with pseudo-handles (-1..-6) or `INVALID_HANDLE_VALUE`.
#[cfg(target_pointer_width = "64")]
pub(crate) const SYNTH_SECTION_TAG: usize = 0x0000_2000_0000_0000;
/// The 32-bit section tag: top nibble `3` (see the module docs).
#[cfg(target_pointer_width = "32")]
pub(crate) const SYNTH_SECTION_TAG: usize = 0x3000_0000;

/// Tag bit (2^47) marking a synthetic *file* handle: a director FUSE file handle.
#[cfg(target_pointer_width = "64")]
pub(crate) const SYNTH_FILE_TAG: usize = 0x0000_8000_0000_0000;
/// The 32-bit file tag: top nibble `2` (see the module docs).
#[cfg(target_pointer_width = "32")]
pub(crate) const SYNTH_FILE_TAG: usize = 0x2000_0000;

/// The bits a synthetic file handle's slot may use: everything below the lowest tag bit.
#[cfg(target_pointer_width = "64")]
pub(crate) const FILE_PAYLOAD: usize = SYNTH_SECTION_TAG - 1;
#[cfg(target_pointer_width = "32")]
pub(crate) const FILE_PAYLOAD: usize = 0x0FFF_FFFF;

/// The bits a synthetic section handle's slot (shifted) may use.
#[cfg(target_pointer_width = "64")]
pub(crate) const SECTION_PAYLOAD: usize = SYNTH_SECTION_TAG - 1;
#[cfg(target_pointer_width = "32")]
pub(crate) const SECTION_PAYLOAD: usize = 0x0FFF_FFFF;

/// Whether `h` carries the synthetic file tag: the tag bit and nothing above it. A pseudo-handle
/// (`-1` this process, `-2` this thread, ...) has every high bit set, the tag's among them, and
/// is not a file: `DuplicateHandle(GetCurrentProcess(), GetCurrentThread(), ...)` took it for
/// one.
pub(crate) fn is_file_tagged(h: usize) -> bool {
    #[cfg(target_pointer_width = "64")]
    return h >> SYNTH_FILE_TAG.trailing_zeros() == 1;
    #[cfg(target_pointer_width = "32")]
    return h >> 28 == SYNTH_FILE_TAG >> 28;
}

/// Whether `h` carries the synthetic section tag.
pub(crate) fn is_section_tagged(h: usize) -> bool {
    #[cfg(target_pointer_width = "64")]
    return h >> SYNTH_SECTION_TAG.trailing_zeros() == 1;
    #[cfg(target_pointer_width = "32")]
    return h >> 28 == SYNTH_SECTION_TAG >> 28;
}

/// Tag bits of a synthetic key handle: bits 29 and 30, nothing above them.
///
/// **Below `0x80000000`, on purpose.** Wine's `RegCloseKey` (kernelbase) returns
/// `ERROR_SUCCESS` without calling `NtClose` for any `hkey >= (HKEY)0x80000000`, taking it for a
/// predefined key. A synthetic handle up there (they were once tagged 2^46) never reached the
/// close hook through `RegCloseKey`, so every key a program opened and closed through advapi32
/// leaked its record and its private real handle: 180k of them in ten minutes of a game that
/// writes a key every frame. Kernelbase's other predefined-key tests take the low 32 bits
/// (`HandleToUlong`), which here never fall in `0x80000000..=0x80000006` either, and a handle
/// truncated to 32 bits stays itself.
///
/// **Clear of real handles.** Wine's process-local handles are `(index + 1) << 2` with fewer
/// than 2^24 entries, so below `0x0400_0000`; its global handles are a local one XOR
/// `0x544a4def`, whose bit 29 is clear. The sign bit is clear, so the value is never a
/// pseudo-handle, and none of [`SYNTH_SECTION_TAG`]'s (2^45) or [`SYNTH_FILE_TAG`]'s (2^47) bits is set.
pub(crate) const REG_TAG: usize = 0x6000_0000;

#[cfg(test)]
mod pseudo_handle_tests {
    use super::*;

    #[test]
    fn pseudo_handles_are_neither_files_nor_sections() {
        for pseudo in 1..=6usize {
            let h = 0usize.wrapping_sub(pseudo);
            assert!(!is_file_tagged(h), "{h:#x}");
            assert!(!is_section_tagged(h), "{h:#x}");
        }
        assert!(is_file_tagged(SYNTH_FILE_TAG | 5));
        assert!(is_section_tagged(SYNTH_SECTION_TAG | 5));
        assert!(!is_section_tagged(SYNTH_FILE_TAG | 5));
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    /// A handle of each family, at its largest payload.
    fn largest() -> [(&'static str, usize); 3] {
        [
            ("section", SYNTH_SECTION_TAG | (SECTION_PAYLOAD & !7)),
            ("file", SYNTH_FILE_TAG | FILE_PAYLOAD),
            ("key", REG_TAG | (((1usize << 27) - 1) << 2)),
        ]
    }

    #[test]
    fn the_tags_do_not_overlap() {
        for (name, h) in largest() {
            assert_eq!(is_section_tagged(h), name == "section", "{name} {h:#x}");
            assert_eq!(is_file_tagged(h), name == "file", "{name} {h:#x}");
        }
        #[cfg(target_pointer_width = "64")]
        {
            let tags = [SYNTH_SECTION_TAG, SYNTH_FILE_TAG, REG_TAG];
            for (i, a) in tags.iter().enumerate() {
                for b in &tags[i + 1..] {
                    assert_eq!(a & b, 0, "tags {a:#x} and {b:#x} share a bit");
                }
            }
        }
    }

    #[test]
    fn every_tag_leaves_the_sign_bit_clear() {
        for (name, h) in largest() {
            assert_eq!(
                h >> (usize::BITS - 1),
                0,
                "{name} {h:#x} would read as a pseudo-handle"
            );
        }
    }

    /// Real handles are never tagged: Windows' and Wine's local ones (below 2^26) and Wine's
    /// global ones (a local one XOR `0x544a4def`); at 32 bits, the pseudo-handles too (at 64 the
    /// hooks never ask about one).
    #[test]
    fn real_handles_carry_no_tag() {
        let mut real = vec![
            4usize,
            0x0400_0000 - 4,
            0x544a_4def ^ 4,
            0x544a_4def ^ (0x00ff_ffff << 2),
        ];
        if cfg!(target_pointer_width = "32") {
            real.extend([usize::MAX, usize::MAX - 5]);
        }
        for h in real {
            assert!(!is_file_tagged(h), "{h:#x}");
            assert!(!is_section_tagged(h), "{h:#x}");
        }
    }
}
