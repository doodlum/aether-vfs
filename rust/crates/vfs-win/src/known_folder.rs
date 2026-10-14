//! The shell's known folders (`SHGetKnownFolderPath`), for a host that
//! declares roots under the user's Documents, Local AppData or Saved Games.
#![allow(unsafe_code)]

use std::path::PathBuf;

use windows_sys::core::{GUID, PWSTR};
use windows_sys::Win32::System::Com::CoTaskMemFree;
use windows_sys::Win32::UI::Shell::{
    FOLDERID_Documents, FOLDERID_LocalAppData, FOLDERID_SavedGames, SHGetKnownFolderPath,
    KF_FLAG_DEFAULT,
};

/// A known folder [`known_folder`] resolves.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum KnownFolderId {
    /// `FOLDERID_Documents`.
    Documents,
    /// `FOLDERID_LocalAppData`.
    LocalAppData,
    /// `FOLDERID_SavedGames`.
    SavedGames,
}

impl KnownFolderId {
    fn guid(self) -> GUID {
        match self {
            KnownFolderId::Documents => FOLDERID_Documents,
            KnownFolderId::LocalAppData => FOLDERID_LocalAppData,
            KnownFolderId::SavedGames => FOLDERID_SavedGames,
        }
    }
}

/// `folder` for the user this process runs as, as the shell resolves it for
/// every program: a folder the user (or OneDrive) moved is where it now is.
/// `KF_FLAG_DEFAULT`, so nothing is created and the path is not verified.
pub fn known_folder(folder: KnownFolderId) -> std::io::Result<PathBuf> {
    let guid = folder.guid();
    let mut path: PWSTR = std::ptr::null_mut();
    // SAFETY: `guid` outlives the call and `path` is a valid out-pointer. The
    // buffer the shell returns, on success or failure, is the caller's to
    // free with `CoTaskMemFree`, which happens below on every path.
    let hr = unsafe {
        SHGetKnownFolderPath(
            &guid,
            KF_FLAG_DEFAULT as u32,
            std::ptr::null_mut(),
            &mut path,
        )
    };
    let result = if hr < 0 || path.is_null() {
        Err(std::io::Error::other(format!(
            "SHGetKnownFolderPath({folder:?}) failed: HRESULT {hr:#010x}"
        )))
    } else {
        // SAFETY: on success `path` is a NUL-terminated UTF-16 string the
        // shell allocated; `len` stops at its terminator.
        let wide = unsafe {
            let mut len = 0;
            while *path.add(len) != 0 {
                len += 1;
            }
            std::slice::from_raw_parts(path, len)
        };
        // Lossy only for an unpaired surrogate, which no shell folder has.
        Ok(PathBuf::from(String::from_utf16_lossy(wide)))
    };
    // SAFETY: `path` is null or the shell's allocation, freed exactly once.
    unsafe { CoTaskMemFree(path as *const core::ffi::c_void) };
    result
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn known_folders_are_absolute_and_distinct() {
        let docs = known_folder(KnownFolderId::Documents).unwrap();
        let local = known_folder(KnownFolderId::LocalAppData).unwrap();
        let saves = known_folder(KnownFolderId::SavedGames).unwrap();
        for p in [&docs, &local, &saves] {
            assert!(p.is_absolute(), "{}", p.display());
        }
        assert_ne!(docs, local);
        assert_ne!(docs, saves);
        assert!(
            local.ends_with(r"AppData\Local"),
            "{} (a redirected LocalAppData would be unusual)",
            local.display()
        );
        assert!(saves.ends_with("Saved Games"), "{}", saves.display());
    }
}
