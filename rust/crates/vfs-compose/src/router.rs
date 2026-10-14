//! Glob-based routing to providers.
//!
//! Every path has exactly one **owner**: the first route whose pattern
//! matches it, else the default. Single-path operations go to the owner.
//! Two operations involve more than one child, and both are defined by that
//! ownership rule (`docs/superpowers/specs/2026-10-13-write-routes-design.md`):
//!
//! - `readdir` is a union of the children's listings, keeping each entry only
//!   from the child that owns its full path — so a listing agrees with
//!   `getattr` for every name in it.
//! - `rename` between two owners is a move: the file is copied into the
//!   destination's owner (staged, then renamed into place) and removed from
//!   the source's.

use std::collections::HashMap;
use std::sync::atomic::{AtomicU64, Ordering};
use std::sync::Arc;

use vfs_core::fold;
use vfs_provider::{
    bad_request, copy_up_name, exists, not_a_dir, not_found, read_only, Access, Capabilities,
    DirEntry, Handle, HandleTable, Provider, SetAttr, Stat, VPath, KIND_DIR, OPEN_CREATE,
    OPEN_READ, OPEN_TRUNC, OPEN_WRITE,
};

use crate::glob;

/// One route: a glob pattern and the provider that owns matching paths.
pub struct Route {
    pub pattern: String,
    pub provider: Arc<dyn Provider>,
}

/// One open handle: the provider that answered, and the handle it returned.
type OpenEntry = (Arc<dyn Provider>, Handle);

/// Routes by glob pattern to a provider: first matching route wins,
/// otherwise `default`. See the module docs for `readdir` and `rename`.
pub struct RouterProvider {
    default: Arc<dyn Provider>,
    routes: Vec<Route>,
    opens: HandleTable<OpenEntry>,
    /// Numbers the `.cu.` staging files of cross-owner renames.
    next: AtomicU64,
}

impl RouterProvider {
    pub fn new(default: Arc<dyn Provider>, routes: Vec<Route>) -> Self {
        Self {
            default,
            routes,
            opens: HandleTable::new(),
            next: AtomicU64::new(1),
        }
    }

    /// The owner of `path`.
    pub fn provider_for(&self, path: &str) -> Arc<dyn Provider> {
        Arc::clone(self.owner(path))
    }

    fn owner(&self, path: &str) -> &Arc<dyn Provider> {
        self.routes
            .iter()
            .find(|r| glob::matches(&r.pattern, path))
            .map(|r| &r.provider)
            .unwrap_or(&self.default)
    }

    /// The default, then each route's provider once (one provider may serve
    /// several patterns).
    fn children(&self) -> Vec<&Arc<dyn Provider>> {
        let mut out: Vec<&Arc<dyn Provider>> = vec![&self.default];
        for r in &self.routes {
            if !out.iter().any(|c| Arc::ptr_eq(c, &r.provider)) {
                out.push(&r.provider);
            }
        }
        out
    }

    /// A rename whose two ends have different owners: copy `from` into
    /// `dst` under a `.cu.` staging name beside `to`, rename it onto `to`
    /// inside `dst` (so `to` is replaced whole, as overlay copy-up does), then
    /// remove `from` from `src`.
    ///
    /// The staging name is never visible. The overlay hides `.cu.` names, and
    /// in any other provider the name is not one `dst` owns, so `readdir`'s
    /// ownership filter drops it.
    fn move_between(
        &self,
        src: &Arc<dyn Provider>,
        dst: &Arc<dyn Provider>,
        from: VPath,
        to: VPath,
    ) -> Result<(), i32> {
        let st = src.getattr(from)?.ok_or_else(not_found)?;
        if st.kind == KIND_DIR {
            // A directory cannot move between owners, for the reason
            // `MountGraph` refuses a cross-mount rename: neither side can
            // move the other's subtree.
            return Err(bad_request());
        }
        if matches!(dst.getattr(to)?, Some(s) if s.kind == KIND_DIR) {
            return Err(exists());
        }
        for (side, p) in [(src, from), (dst, to)] {
            if side.capabilities().access < Access::ReadWrite {
                crate::record_rejected_write(p.rel);
                return Err(read_only());
            }
        }
        let n = self.next.fetch_add(1, Ordering::Relaxed);
        let to_rel = vfs_core::trim_rel(to.rel);
        let tmp_rel = match vfs_core::split_parent(&to_rel) {
            ("", name) => copy_up_name(n, name),
            (parent, name) => format!("{parent}/{}", copy_up_name(n, name)),
        };
        let tmp = VPath::new(to.root, &tmp_rel);
        let placed =
            copy_file(src.as_ref(), from, dst.as_ref(), tmp).and_then(|_| dst.rename(tmp, to));
        if placed.is_err() {
            let _ = dst.remove(tmp);
            return placed;
        }
        src.remove(from)
    }
}

/// Copy the whole of `src`'s file `from` into `dst` at `to`, created or
/// truncated. The first error wins; handles are always closed.
fn copy_file(src: &dyn Provider, from: VPath, dst: &dyn Provider, to: VPath) -> Result<(), i32> {
    let (sh, size, _) = src.open(from, OPEN_READ)?;
    let copied = (|| {
        let (dh, _, _) = dst.open(to, OPEN_WRITE | OPEN_CREATE | OPEN_TRUNC)?;
        let mut buf = vec![0u8; 65536];
        let mut off = 0u64;
        let mut result = Ok(());
        while off < size {
            match src.read_at(sh, off, &mut buf) {
                Ok(0) => break,
                Ok(n) => {
                    if let Err(e) = dst.write_at(dh, off, &buf[..n]) {
                        result = Err(e);
                        break;
                    }
                    off += n as u64;
                }
                Err(e) => {
                    result = Err(e);
                    break;
                }
            }
        }
        let result = result.and_then(|_| dst.flush(dh));
        let closed = dst.close(dh);
        result.and(closed)
    })();
    let _ = src.close(sh);
    copied
}

impl Provider for RouterProvider {
    /// Strongest access, weakest everything else — `MountGraph`'s rule, for
    /// the same reason: a write is answered by the path's owner, so the
    /// router can serve one whenever *some* child can. A write to a path
    /// whose owner cannot take it is refused per path, in `open`. (The
    /// weakest access, which this used to report, made a writable route
    /// unreachable under a read-only default: the director refused every
    /// write before asking.)
    fn capabilities(&self) -> Capabilities {
        let caps: Vec<Capabilities> = std::iter::once(self.default.capabilities())
            .chain(self.routes.iter().map(|r| r.provider.capabilities()))
            .collect();
        let access = caps.iter().map(|c| c.access).max().unwrap_or(Access::Read);
        Capabilities {
            access,
            ..Capabilities::weakest(caps)
        }
    }

    fn getattr(&self, p: VPath) -> Result<Option<Stat>, i32> {
        self.owner(p.rel).getattr(p)
    }

    /// The union of every child's listing of `p`, each entry kept only from
    /// the child that owns its full path. One exception: a directory entry
    /// from a route that no owner lists is kept as it is, so a route like
    /// `Data/Config/*.ini` leaves `Config` discoverable when nothing else
    /// has that directory.
    fn readdir(&self, p: VPath) -> Result<Vec<DirEntry>, i32> {
        if self.routes.is_empty() {
            return self.default.readdir(p);
        }
        let dir = vfs_core::trim_rel(p.rel);
        let mut owned: HashMap<String, DirEntry> = HashMap::new();
        let mut orphans: HashMap<String, DirEntry> = HashMap::new();
        let (mut saw_dir, mut not_dir) = (false, false);
        for (i, child) in self.children().into_iter().enumerate() {
            let entries = match child.readdir(p) {
                Ok(entries) => entries,
                Err(e) if e == not_found() => continue,
                Err(e) if e == not_a_dir() => {
                    not_dir = true;
                    continue;
                }
                Err(e) => return Err(e),
            };
            saw_dir = true;
            for e in entries {
                let full = if dir.is_empty() {
                    e.name.clone()
                } else {
                    format!("{dir}/{}", e.name)
                };
                if Arc::ptr_eq(self.owner(&full), child) {
                    owned.insert(fold(&e.name), e);
                } else if i > 0 && e.stat.kind == KIND_DIR {
                    orphans.entry(fold(&e.name)).or_insert(e);
                }
            }
        }
        if !saw_dir {
            return Err(if not_dir { not_a_dir() } else { not_found() });
        }
        for (key, e) in orphans {
            owned.entry(key).or_insert(e);
        }
        Ok(crate::sorted_by_folded_name(owned))
    }

    fn stored_name(&self, p: VPath) -> Result<Option<String>, i32> {
        self.owner(p.rel).stored_name(p)
    }

    fn open(&self, p: VPath, flags: u32) -> Result<(Handle, u64, bool), i32> {
        let provider = self.provider_for(p.rel);
        if flags & OPEN_WRITE != 0 && provider.capabilities().access < Access::ReadWrite {
            // The per-path half of `capabilities`' strongest-access rule;
            // recorded, as `MountGraph` and the director record theirs.
            crate::record_rejected_write(p.rel);
            return Err(read_only());
        }
        let (inner, size, is_dir) = provider.open(p, flags)?;
        let h = self.opens.insert((provider, inner))?;
        Ok((h, size, is_dir))
    }

    fn read_at(&self, h: Handle, offset: u64, buf: &mut [u8]) -> Result<usize, i32> {
        let (provider, inner) = self.opens.get(h)?;
        provider.read_at(inner, offset, buf)
    }

    /// The route that opened the handle answers.
    fn is_immutable(&self, h: Handle) -> bool {
        let routed = self.opens.get(h).ok();
        routed.is_some_and(|(provider, inner)| provider.is_immutable(inner))
    }

    fn close(&self, h: Handle) -> Result<(), i32> {
        let (provider, inner) = self.opens.remove(h)?;
        provider.close(inner)
    }

    fn write_at(&self, h: Handle, offset: u64, buf: &[u8]) -> Result<usize, i32> {
        let (provider, inner) = self.opens.get(h)?;
        provider.write_at(inner, offset, buf)
    }

    fn set_len(&self, h: Handle, len: u64) -> Result<(), i32> {
        let (provider, inner) = self.opens.get(h)?;
        provider.set_len(inner, len)
    }

    fn flush(&self, h: Handle) -> Result<(), i32> {
        let (provider, inner) = self.opens.get(h)?;
        provider.flush(inner)
    }

    fn mkdir(&self, p: VPath) -> Result<(), i32> {
        self.owner(p.rel).mkdir(p)
    }

    fn remove(&self, p: VPath) -> Result<(), i32> {
        self.owner(p.rel).remove(p)
    }

    /// Forwarded when both ends have one owner; a move between owners
    /// otherwise (see [`RouterProvider::move_between`]). It used to route both
    /// ends by `from`, which put a write-temp-then-rename onto a routed name
    /// into the temp file's owner instead.
    fn rename(&self, from: VPath, to: VPath) -> Result<(), i32> {
        if from.root != to.root {
            return Err(bad_request());
        }
        let (src, dst) = (self.owner(from.rel), self.owner(to.rel));
        if Arc::ptr_eq(src, dst) {
            return src.rename(from, to);
        }
        self.move_between(src, dst, from, to)
    }

    fn set_attr(&self, p: VPath, attr: SetAttr) -> Result<(), i32> {
        self.owner(p.rel).set_attr(p, attr)
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::InlineProvider;
    use vfs_provider::OPEN_READ;

    fn tag(name: &'static str) -> Arc<dyn Provider> {
        Arc::new(InlineProvider::from_files([("tag", name.as_bytes())]))
    }

    fn tag_of(p: &dyn Provider) -> String {
        let (h, _, _) = p.open(VPath::at_default("tag"), OPEN_READ).unwrap();
        let mut buf = [0u8; 32];
        let n = p.read_at(h, 0, &mut buf).unwrap();
        p.close(h).unwrap();
        String::from_utf8_lossy(&buf[..n]).into_owned()
    }

    #[test]
    fn a_router_with_only_a_default_passes_conformance() {
        let default: Arc<dyn vfs_provider::Provider> = Arc::new(InlineProvider::from_files(
            vfs_provider::FIXTURE_FILES.iter().copied(),
        ));
        let r: Arc<dyn vfs_provider::Provider> = Arc::new(RouterProvider::new(default, vec![]));
        vfs_provider::assert_conformance(r);
    }

    /// The systematic guard for this module's write-forwarding bug:
    /// `RouterProvider::open` already forwarded `OPEN_WRITE` straight to the
    /// default/routed provider and succeeded, but `write_at`/`set_len`/
    /// `mkdir`/`remove`/`rename`/`set_attr` all fell through to the trait's
    /// `ST_NOT_SUPPORTED` default — invisible to every prior conformance test
    /// here because they all wrapped a read-only `InlineProvider`, so
    /// `assert_writable`'s cases never ran.
    #[test]
    fn a_router_with_a_writable_default_passes_conformance() {
        let default: Arc<dyn vfs_provider::Provider> = Arc::new(vfs_provider::RwMemFixture::new());
        let r: Arc<dyn vfs_provider::Provider> = Arc::new(RouterProvider::new(default, vec![]));
        vfs_provider::assert_conformance(r);
    }

    #[test]
    fn matched_path_routes_to_provider() {
        let r = RouterProvider::new(
            tag("default"),
            vec![Route {
                pattern: "/game/**".into(),
                provider: tag("game"),
            }],
        );
        assert_eq!(tag_of(r.provider_for("/game/a.dat").as_ref()), "game");
    }

    #[test]
    fn unmatched_path_routes_to_default() {
        let r = RouterProvider::new(
            tag("default"),
            vec![Route {
                pattern: "/game/**".into(),
                provider: tag("game"),
            }],
        );
        assert_eq!(
            tag_of(r.provider_for("/windows/system32").as_ref()),
            "default"
        );
    }

    #[test]
    fn first_matching_route_wins() {
        let r = RouterProvider::new(
            tag("default"),
            vec![
                Route {
                    pattern: "/game/*.exe".into(),
                    provider: tag("exe"),
                },
                Route {
                    pattern: "/game/**".into(),
                    provider: tag("game"),
                },
            ],
        );
        assert_eq!(tag_of(r.provider_for("/game/app.exe").as_ref()), "exe");
    }

    #[test]
    fn open_read_through_router_provider_trait() {
        // Routes select the provider; the path is passed through unchanged.
        let game = Arc::new(InlineProvider::from_files([(
            "game/a.dat",
            b"GAME".as_slice(),
        )]));
        let def = Arc::new(InlineProvider::from_files([(
            "other.txt",
            b"DEF".as_slice(),
        )]));
        let r = RouterProvider::new(
            def,
            vec![Route {
                pattern: "/game/**".into(),
                provider: game,
            }],
        );
        let (h, size, _) = r.open(VPath::at_default("game/a.dat"), OPEN_READ).unwrap();
        assert_eq!(size, 4);
        let mut buf = [0u8; 8];
        assert_eq!(r.read_at(h, 0, &mut buf).unwrap(), 4);
        assert_eq!(&buf[..4], b"GAME");
        r.close(h).unwrap();

        let (h, size, _) = r.open(VPath::at_default("other.txt"), OPEN_READ).unwrap();
        assert_eq!(size, 3);
        assert_eq!(r.read_at(h, 0, &mut buf).unwrap(), 3);
        assert_eq!(&buf[..3], b"DEF");
        r.close(h).unwrap();
    }

    // ---- ownership: listings and renames across routes -------------------

    use crate::MemoryProvider;
    use vfs_provider::{KIND_FILE, OPEN_CREATE, OPEN_TRUNC, OPEN_WRITE};

    fn mem(files: &[(&str, &[u8])]) -> Arc<MemoryProvider> {
        Arc::new(MemoryProvider::from_files(files.iter().copied()))
    }

    fn held(p: &dyn Provider, path: &str) -> Option<Vec<u8>> {
        let (h, size, _) = p.open(VPath::at_default(path), OPEN_READ).ok()?;
        let mut buf = vec![0u8; size as usize];
        let n = p.read_at(h, 0, &mut buf).unwrap();
        p.close(h).unwrap();
        buf.truncate(n);
        Some(buf)
    }

    fn put(p: &dyn Provider, path: &str, bytes: &[u8]) {
        let (h, _, _) = p
            .open(
                VPath::at_default(path),
                OPEN_WRITE | OPEN_CREATE | OPEN_TRUNC,
            )
            .unwrap();
        p.write_at(h, 0, bytes).unwrap();
        p.close(h).unwrap();
    }

    fn listing(r: &RouterProvider, dir: &str) -> Vec<(String, u64)> {
        r.readdir(VPath::at_default(dir))
            .unwrap()
            .into_iter()
            .map(|e| (e.name, e.stat.size))
            .collect()
    }

    /// One routed file and a default holding its own (stale) copy of it.
    fn routed() -> (RouterProvider, Arc<MemoryProvider>, Arc<MemoryProvider>) {
        let def = mem(&[("Data/a.esp", b"A"), ("Data/plugins.txt", b"STALE COPY")]);
        let route = mem(&[("Data/plugins.txt", b"LIVE")]);
        let r = RouterProvider::new(
            def.clone(),
            vec![Route {
                pattern: "Data/plugins.txt".into(),
                provider: route.clone(),
            }],
        );
        (r, def, route)
    }

    #[test]
    fn readdir_lists_each_name_from_its_owner() {
        let (r, _def, _route) = routed();
        assert_eq!(
            listing(&r, "Data"),
            [("a.esp".to_string(), 1), ("plugins.txt".to_string(), 4)],
            "the routed file once, with the route's size, not the default's stale copy"
        );
        assert_eq!(listing(&r, "data"), listing(&r, "Data"));
        let root = listing(&r, "");
        assert_eq!(root.len(), 1, "Data is listed once: {root:?}");
    }

    #[test]
    fn readdir_drops_a_name_the_owner_does_not_have() {
        let (r, _def, route) = routed();
        route.remove(VPath::at_default("Data/plugins.txt")).unwrap();
        assert_eq!(listing(&r, "Data"), [("a.esp".to_string(), 1)]);
        assert_eq!(
            r.getattr(VPath::at_default("Data/plugins.txt")).unwrap(),
            None
        );
    }

    #[test]
    fn readdir_keeps_a_route_directory_nothing_else_has() {
        let def = mem(&[("Data/a.esp", b"A")]);
        let route = mem(&[("Data/Config/x.ini", b"X")]);
        let r = RouterProvider::new(
            def,
            vec![Route {
                pattern: "Data/Config/*.ini".into(),
                provider: route,
            }],
        );
        let names: Vec<String> = listing(&r, "Data").into_iter().map(|e| e.0).collect();
        assert_eq!(names, ["a.esp", "Config"]);
        assert_eq!(listing(&r, "Data/Config"), [("x.ini".to_string(), 1)]);
    }

    #[test]
    fn readdir_of_a_directory_nobody_has_is_not_found() {
        let (r, _def, _route) = routed();
        assert_eq!(
            r.readdir(VPath::at_default("nope")),
            Err(vfs_provider::ST_NOT_FOUND)
        );
    }

    #[test]
    fn rename_onto_a_routed_name_moves_the_file_into_the_route() {
        let (r, def, route) = routed();
        put(&r, "Data/plugins.tmp", b"NEW BYTES");
        assert_eq!(
            held(def.as_ref(), "Data/plugins.tmp").as_deref(),
            Some(b"NEW BYTES".as_slice())
        );
        r.rename(
            VPath::at_default("Data/plugins.tmp"),
            VPath::at_default("Data/plugins.txt"),
        )
        .unwrap();
        assert_eq!(
            held(route.as_ref(), "Data/plugins.txt").as_deref(),
            Some(b"NEW BYTES".as_slice())
        );
        assert_eq!(
            held(def.as_ref(), "Data/plugins.tmp"),
            None,
            "moved, not copied"
        );
        let route_names: Vec<String> = route
            .readdir(VPath::at_default("Data"))
            .unwrap()
            .into_iter()
            .map(|e| e.name)
            .collect();
        assert_eq!(
            route_names,
            ["plugins.txt"],
            "no staging file is left behind"
        );
    }

    #[test]
    fn rename_out_of_a_route_moves_the_file_to_the_default() {
        let (r, def, route) = routed();
        r.rename(
            VPath::at_default("Data/plugins.txt"),
            VPath::at_default("Data/plugins.bak"),
        )
        .unwrap();
        assert_eq!(held(route.as_ref(), "Data/plugins.txt"), None);
        assert_eq!(
            held(def.as_ref(), "Data/plugins.bak").as_deref(),
            Some(b"LIVE".as_slice())
        );
    }

    #[test]
    fn rename_within_one_owner_is_forwarded() {
        let (r, def, _route) = routed();
        r.rename(
            VPath::at_default("Data/a.esp"),
            VPath::at_default("Data/b.esp"),
        )
        .unwrap();
        assert_eq!(
            held(def.as_ref(), "Data/b.esp").as_deref(),
            Some(b"A".as_slice())
        );
    }

    #[test]
    fn a_directory_cannot_move_between_owners() {
        let def = mem(&[("Data/sub/f", b"F")]);
        let route = mem(&[]);
        let r = RouterProvider::new(
            def,
            vec![Route {
                pattern: "Routed/**".into(),
                provider: route,
            }],
        );
        assert_eq!(
            r.rename(
                VPath::at_default("Data/sub"),
                VPath::at_default("Routed/sub")
            ),
            Err(vfs_provider::ST_BAD_REQUEST)
        );
    }

    #[test]
    fn a_file_cannot_replace_a_directory_in_another_owner() {
        let def = mem(&[("f", b"F")]);
        let route = mem(&[("Routed/d/x", b"X")]);
        let r = RouterProvider::new(
            def.clone(),
            vec![Route {
                pattern: "Routed/**".into(),
                provider: route,
            }],
        );
        assert_eq!(
            r.rename(VPath::at_default("f"), VPath::at_default("Routed/d")),
            Err(vfs_provider::ST_EXISTS)
        );
        assert!(held(def.as_ref(), "f").is_some(), "the source is untouched");
    }

    /// A writable route under a read-only default is reachable: the router
    /// reports the strongest access and refuses per path.
    #[test]
    fn a_writable_route_under_a_read_only_default_takes_writes() {
        let def: Arc<dyn Provider> =
            Arc::new(InlineProvider::from_files([("ro.txt", b"RO".as_slice())]));
        let route = mem(&[("rw.txt", b"RW")]);
        let r = RouterProvider::new(
            def,
            vec![Route {
                pattern: "rw.txt".into(),
                provider: route.clone(),
            }],
        );
        assert_eq!(r.capabilities().access, vfs_provider::Access::ReadWrite);
        put(&r, "rw.txt", b"NEW");
        assert_eq!(
            held(route.as_ref(), "rw.txt").as_deref(),
            Some(b"NEW".as_slice())
        );
        assert_eq!(
            r.open(VPath::at_default("ro.txt"), OPEN_WRITE).err(),
            Some(vfs_provider::ST_READ_ONLY)
        );
        assert_eq!(
            r.getattr(VPath::at_default("ro.txt"))
                .unwrap()
                .unwrap()
                .kind,
            KIND_FILE
        );
    }

    /// The whole provider contract, with a route in play: both children
    /// hold the conformance tree, and the route owns everything under `sub`
    /// — so listings union across the two, and the suite's renames between
    /// the root and `sub` cross owners.
    #[test]
    fn a_router_with_a_route_passes_conformance() {
        let default: Arc<dyn vfs_provider::Provider> = Arc::new(vfs_provider::RwMemFixture::new());
        let r: Arc<dyn vfs_provider::Provider> = Arc::new(RouterProvider::new(
            default,
            vec![Route {
                pattern: "sub/**".into(),
                provider: Arc::new(vfs_provider::RwMemFixture::new()),
            }],
        ));
        vfs_provider::assert_conformance(r);
    }
}
