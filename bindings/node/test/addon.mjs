// Loads the binding the way a host does: the cargo build output copied to `aether_vfs_node.node`.
// The copy is written beside it and renamed into place, so a test file loading it while another
// replaces it never maps a half-written library.
import fs from 'node:fs';
import path from 'node:path';
import { createRequire } from 'node:module';
import { fileURLToPath } from 'node:url';

const here = path.dirname(fileURLToPath(import.meta.url));
const require = createRequire(import.meta.url);
export const profile = process.env.AETHER_PROFILE ?? 'debug';

const built = {
  win32: 'aether_vfs_node.dll',
  darwin: 'libaether_vfs_node.dylib',
}[process.platform] ?? 'libaether_vfs_node.so';

export const addonPath = path.join(here, '..', 'aether_vfs_node.node');
const staged = `${addonPath}.${process.pid}.tmp`;
fs.copyFileSync(path.resolve(here, '../target', profile, built), staged);
fs.renameSync(staged, addonPath);

export const { Session } = require(addonPath);
