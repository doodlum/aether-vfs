//! Inode numbers for a path-addressed director.
//!
//! FUSE speaks inodes; the director speaks paths. One inode per **folded**
//! path, so every spelling a case-insensitive lookup accepts (`Data`, `DATA`)
//! names the same file, as it does on Windows. Numbers are never reused:
//! a path whose entry is removed, or every path after a remount
//! ([`Inodes::reset`]), gets a fresh number at its next lookup, so the kernel
//! never pairs a cached inode with different content or a different type.

use std::collections::HashMap;
use std::hash::{Hash, Hasher};

pub(crate) const ROOT: u64 = 1;

struct Node {
    /// The path as first looked up (any spelling the director accepts).
    path: String,
    /// `(parent inode, name)` for each dentry the kernel holds for it: what an
    /// invalidation names.
    aliases: Vec<(u64, String)>,
    /// Lookups the kernel holds; the node goes when `forget` drops it to 0.
    lookups: u64,
}

pub(crate) struct Inodes {
    nodes: HashMap<u64, Node>,
    by_key: HashMap<String, u64>,
    next: u64,
}

pub(crate) fn join(parent: &str, name: &str) -> String {
    if parent.is_empty() {
        name.to_string()
    } else {
        format!("{parent}/{name}")
    }
}

fn key(path: &str) -> String {
    vfs_core::fold(path)
}

impl Inodes {
    pub(crate) fn new() -> Self {
        let mut nodes = HashMap::new();
        nodes.insert(
            ROOT,
            Node {
                path: String::new(),
                aliases: Vec::new(),
                lookups: 1,
            },
        );
        let mut by_key = HashMap::new();
        by_key.insert(String::new(), ROOT);
        Inodes {
            nodes,
            by_key,
            next: ROOT + 1,
        }
    }

    pub(crate) fn path(&self, ino: u64) -> Option<String> {
        self.nodes.get(&ino).map(|n| n.path.clone())
    }

    /// The kernel now holds one more lookup of `path`, reached as `name` in
    /// `parent`: its inode, created if the path has none.
    pub(crate) fn looked_up(&mut self, parent: u64, name: &str, path: &str) -> u64 {
        let k = key(path);
        if let Some(&ino) = self.by_key.get(&k) {
            if let Some(node) = self.nodes.get_mut(&ino) {
                node.lookups += 1;
                if !node.aliases.iter().any(|(p, n)| *p == parent && n == name) {
                    node.aliases.push((parent, name.to_string()));
                }
                return ino;
            }
        }
        let ino = self.next;
        self.next += 1;
        self.nodes.insert(
            ino,
            Node {
                path: path.to_string(),
                aliases: vec![(parent, name.to_string())],
                lookups: 1,
            },
        );
        self.by_key.insert(k, ino);
        ino
    }

    /// The inode a listing reports for `path`: the live one if the kernel
    /// holds it, else a stable number outside the allocated range (a listing
    /// takes no lookup, so it must not allocate).
    pub(crate) fn peek(&self, path: &str) -> u64 {
        let k = key(path);
        if let Some(&ino) = self.by_key.get(&k) {
            return ino;
        }
        let mut h = std::collections::hash_map::DefaultHasher::new();
        k.hash(&mut h);
        h.finish() | (1 << 63)
    }

    pub(crate) fn forget(&mut self, ino: u64, n: u64) {
        if ino == ROOT {
            return;
        }
        let gone = match self.nodes.get_mut(&ino) {
            Some(node) => {
                node.lookups = node.lookups.saturating_sub(n);
                node.lookups == 0
            }
            None => false,
        };
        if gone {
            if let Some(node) = self.nodes.remove(&ino) {
                let k = key(&node.path);
                if self.by_key.get(&k) == Some(&ino) {
                    self.by_key.remove(&k);
                }
            }
        }
    }

    /// `path` was removed: the next entry there is a new file.
    pub(crate) fn detach(&mut self, path: &str) {
        let k = key(path);
        if !k.is_empty() {
            self.by_key.remove(&k);
        }
    }

    /// `from` was renamed to `to`: it and everything beneath it keep their
    /// inodes under the new paths, and whatever `to` was is detached.
    pub(crate) fn renamed(&mut self, from: &str, to: &str) {
        self.detach(to);
        let from_key = key(from);
        let prefix = format!("{from_key}/");
        let depth = from.split('/').count();
        let moved: Vec<(String, u64)> = self
            .by_key
            .iter()
            .filter(|(k, _)| **k == from_key || k.starts_with(&prefix))
            .map(|(k, &ino)| (k.clone(), ino))
            .collect();
        for (k, ino) in moved {
            self.by_key.remove(&k);
            if let Some(node) = self.nodes.get_mut(&ino) {
                let rest: Vec<&str> = node.path.split('/').skip(depth).collect();
                node.path = if rest.is_empty() {
                    to.to_string()
                } else {
                    format!("{to}/{}", rest.join("/"))
                };
                node.aliases.clear();
                self.by_key.insert(key(&node.path), ino);
            }
        }
    }

    /// The content behind every path may have changed: every path gets a
    /// fresh inode at its next lookup. Returns what to invalidate in the
    /// kernel — each dentry it holds, and each inode (for attributes and
    /// cached pages).
    pub(crate) fn reset(&mut self) -> (Vec<(u64, String)>, Vec<u64>) {
        let mut aliases = Vec::new();
        let mut inos = Vec::with_capacity(self.nodes.len());
        for (&ino, node) in self.nodes.iter_mut() {
            inos.push(ino);
            aliases.append(&mut node.aliases);
        }
        self.by_key.clear();
        self.by_key.insert(String::new(), ROOT);
        (aliases, inos)
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn spellings_share_an_inode_and_forget_frees_it() {
        let mut t = Inodes::new();
        let a = t.looked_up(ROOT, "Data", "Data");
        let b = t.looked_up(ROOT, "DATA", "DATA");
        assert_eq!(a, b);
        assert_eq!(t.peek("data"), a);
        t.forget(a, 2);
        assert!(t.path(a).is_none());
        assert_ne!(t.peek("data"), a);
    }

    #[test]
    fn a_rename_moves_the_subtree_and_a_reset_renumbers() {
        let mut t = Inodes::new();
        let d = t.looked_up(ROOT, "Dir", "Dir");
        let f = t.looked_up(d, "f.txt", "Dir/f.txt");
        t.renamed("dir", "New");
        assert_eq!(t.path(f).as_deref(), Some("New/f.txt"));
        assert_eq!(t.peek("new/F.TXT"), f);
        let (_, inos) = t.reset();
        assert!(inos.contains(&f));
        let again = t.looked_up(ROOT, "New", "New");
        assert_ne!(again, d, "a reset path gets a new inode");
    }
}
