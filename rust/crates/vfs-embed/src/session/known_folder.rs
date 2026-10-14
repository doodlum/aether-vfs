//! Where the user's own folders are for the programs a session launches —
//! see [`Session::known_folder`].

use std::path::PathBuf;

use super::Session;

/// A per-user folder a game keeps its files in, for
/// [`Session::known_folder`].
#[derive(Debug, Clone, Copy, PartialEq, Eq, Hash)]
#[non_exhaustive]
pub enum KnownFolder {
    /// The user's Documents (`FOLDERID_Documents`), where games keep
    /// `My Games\<game>`.
    Documents,
    /// The user's Local AppData (`FOLDERID_LocalAppData`), `…\AppData\Local`.
    LocalAppData,
    /// The user's Saved Games (`FOLDERID_SavedGames`).
    SavedGames,
}

impl Session {
    /// `folder` as the programs this session launches see it, for a host
    /// that declares a root under it: append the game's own subfolder and
    /// pass the result to [`Session::declare_root`]. A host never spells a
    /// user name or a profile layout itself.
    ///
    /// - **Unix (Proton, Wine, CrossOver):** the folder of the prefix's user
    ///   as a `C:\…` location, for example `C:\users\steamuser\Documents`
    ///   under GE-Proton, whose Wine names its user `steamuser` in every
    ///   prefix it makes. It is read from the prefix — its registry, as Wine's
    ///   own shell answers, else its `drive_c\users` directory — so it is
    ///   right for whatever Wine made the prefix. A prefix that is not set up
    ///   yet is set up first, exactly as [`Session::prepare_prefix`] does
    ///   (same runtime, prefix name and [`PrefixInit`](crate::PrefixInit));
    ///   one that is set up is only read, so this can be asked while a launch
    ///   runs there.
    /// - **Windows:** the shell's path for the user this process runs as
    ///   (`SHGetKnownFolderPath`), which follows a folder the user or OneDrive
    ///   moved, as the game itself will. A host directory, as `declare_root`
    ///   takes there.
    ///
    /// The returned path joins like any other (`Path::join`); on unix the
    /// location rules accept either separator ([`Session::check_root_location`]).
    pub fn known_folder(&self, folder: KnownFolder) -> Result<PathBuf, String> {
        #[cfg(unix)]
        {
            self.prefix_known_folder(folder)
        }
        #[cfg(windows)]
        {
            let id = match folder {
                KnownFolder::Documents => vfs_win::KnownFolderId::Documents,
                KnownFolder::LocalAppData => vfs_win::KnownFolderId::LocalAppData,
                KnownFolder::SavedGames => vfs_win::KnownFolderId::SavedGames,
            };
            vfs_win::known_folder(id).map_err(|e| format!("known folder {folder:?}: {e}"))
        }
    }
}
