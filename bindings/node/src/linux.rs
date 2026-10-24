//! Linux: each root is a FUSE mount at its location (`vfs_embed::fuse`), so every process sees
//! it — Steam's own Proton, a native game, this host — and nothing is launched through the
//! session. `start()` mounts, `setRoot()` remounts in place, `close()` unmounts.
//!
//! The rules a mount imposes on a spec, refused with `E_CONFIG`:
//! * locations may not nest (an inner mount would be detached by the outer one's invalidation);
//! * no mapped file's source and no write folder may lie inside a location: the mount would read
//!   or write through itself;
//! * a base folder inside a location is read through a directory opened before the mount, so the
//!   real folder stays readable underneath it.

use std::path::{Path, PathBuf};
use std::sync::Arc;

use napi::bindgen_prelude::*;
use vfs_embed::fuse::{
    mount_state, recover_stale_mount, BaseDir, FuseMount, MountOpts, MountState,
};
use vfs_embed::{Director, Provider, RootId};

use crate::{error, RootSpec, E_CONFIG, E_MOUNT, E_STATE};

pub(crate) struct Linux {
    locations: Vec<PathBuf>,
    /// Each location, opened before anything is mounted on it; `None` until it exists.
    bases: Vec<Option<BaseDir>>,
    mounts: Vec<Option<FuseMount>>,
}

fn inside(path: &Path, locations: &[PathBuf]) -> Option<usize> {
    locations.iter().position(|l| path.starts_with(l))
}

impl Linux {
    /// Recover what a crashed session left mounted at each location, refuse a live mount there,
    /// and open each existing location before anything covers it.
    pub(crate) fn new(roots: &[RootSpec]) -> Result<Linux> {
        let locations: Vec<PathBuf> = roots.iter().map(|r| PathBuf::from(&r.location)).collect();
        for (i, a) in locations.iter().enumerate() {
            for (j, b) in locations.iter().enumerate() {
                if i != j && a.starts_with(b) {
                    return Err(error(
                        E_CONFIG,
                        format!(
                            "root location {:?} is inside {:?}; nested roots are not supported on Linux",
                            a, b
                        ),
                    ));
                }
            }
        }
        let mut bases = Vec::with_capacity(locations.len());
        for loc in &locations {
            match mount_state(loc).map_err(|e| error(E_MOUNT, format!("{loc:?}: {e}")))? {
                MountState::Stale => {
                    recover_stale_mount(loc).map_err(|e| {
                        error(
                            E_MOUNT,
                            format!("stale mount at {loc:?} could not be removed: {e}"),
                        )
                    })?;
                }
                MountState::Live => {
                    return Err(error(
                        E_STATE,
                        format!("{loc:?} is already mounted by another session"),
                    ))
                }
                MountState::None => {}
            }
            bases.push(if loc.is_dir() {
                Some(BaseDir::open(loc).map_err(|e| error(E_CONFIG, format!("{loc:?}: {e}")))?)
            } else {
                None
            });
        }
        let mounts = locations.iter().map(|_| None).collect();
        Ok(Linux {
            locations,
            bases,
            mounts,
        })
    }

    /// Refuse a spec whose sources or write folder lie inside a mounted location.
    pub(crate) fn check_spec(&self, spec: &RootSpec) -> Result<()> {
        for f in &spec.files {
            if let Some(i) = inside(Path::new(&f.source), &self.locations) {
                return Err(error(
                    E_CONFIG,
                    format!(
                        "file source {:?} is inside root location {:?}; keep staging outside the game folder",
                        f.source, self.locations[i]
                    ),
                ));
            }
        }
        if let Some(dir) = &spec.write_to {
            if let Some(i) = inside(Path::new(dir), &self.locations) {
                return Err(error(
                    E_CONFIG,
                    format!(
                        "write folder {dir:?} is inside root location {:?}",
                        self.locations[i]
                    ),
                ));
            }
        }
        Ok(())
    }

    /// The base layer for a base folder inside a location: read through that location's
    /// descriptor. `None` for a base folder elsewhere (served by path as on Windows).
    pub(crate) fn base_provider(&self, base: &str) -> Option<Result<Arc<dyn Provider>>> {
        let i = inside(Path::new(base), &self.locations)?;
        let Some(dir) = &self.bases[i] else {
            return Some(Err(error(
                E_CONFIG,
                format!("base folder {base:?} does not exist"),
            )));
        };
        Some(match dir.resolve_host_path(Path::new(base))? {
            Ok(sub) => Ok(Arc::new(sub.provider()) as Arc<dyn Provider>),
            Err(_) => Err(error(
                E_CONFIG,
                format!("base folder {base:?} does not exist"),
            )),
        })
    }

    /// Mount every root not yet mounted. On a failure, the roots this call mounted are
    /// unmounted again.
    pub(crate) fn mount_all(&mut self, director: &Arc<Director>) -> Result<()> {
        let mut mounted_now = Vec::new();
        for id in 0..self.locations.len() {
            if self.mounts[id].is_some() {
                continue;
            }
            let result = self.mount_one(director, id);
            match result {
                Ok(()) => mounted_now.push(id),
                Err(e) => {
                    for id in mounted_now {
                        if let Some(m) = self.mounts[id].take() {
                            let _ = m.unmount();
                        }
                    }
                    return Err(e);
                }
            }
        }
        Ok(())
    }

    fn mount_one(&mut self, director: &Arc<Director>, id: usize) -> Result<()> {
        let loc = self.locations[id].clone();
        if self.bases[id].is_none() {
            // A purely virtual location: an empty real folder to mount on.
            std::fs::create_dir_all(&loc)
                .map_err(|e| error(E_MOUNT, format!("create {loc:?}: {e}")))?;
            self.bases[id] =
                Some(BaseDir::open(&loc).map_err(|e| error(E_MOUNT, format!("{loc:?}: {e}")))?);
        }
        let mount = FuseMount::mount(
            Arc::clone(director),
            RootId(id as u32),
            &loc,
            MountOpts::default(),
        )
        .map_err(|e| error(E_MOUNT, format!("mount {loc:?}: {e}")))?;
        self.mounts[id] = Some(mount);
        Ok(())
    }

    /// Root `id`'s content changed: the next lookup and open see the new content.
    pub(crate) fn invalidate(&self, id: usize) {
        if let Some(Some(m)) = self.mounts.get(id) {
            m.invalidate();
        }
    }

    /// Unmount every root (lazily where a program still holds files open).
    pub(crate) fn unmount_all(&mut self) {
        for slot in self.mounts.iter_mut().rev() {
            if let Some(m) = slot.take() {
                let _ = m.unmount();
            }
        }
    }

    pub(crate) fn is_mounted(&self, id: usize) -> bool {
        matches!(self.mounts.get(id), Some(Some(_)))
    }
}
