// End to end on a real process: a game folder with one real file, a mod file mapped in from
// elsewhere, and a write folder. The fixture must read both through the session, and what it
// writes must land in the write folder while the game folder stays exactly as it was.
//
// node --test bindings/node/test/  (after `cargo build` here and the fixture/shim in rust/target)
import { test } from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import { createRequire } from 'node:module';
import { fileURLToPath } from 'node:url';

const here = path.dirname(fileURLToPath(import.meta.url));
const require = createRequire(import.meta.url);
const profile = process.env.AETHER_PROFILE ?? 'debug';
const artifacts = path.resolve(here, '../../../rust/target', profile);
const addonPath = path.join(here, '..', 'aether_vfs_node.node');
fs.copyFileSync(path.resolve(here, '../target', profile, 'aether_vfs_node.dll'), addonPath);
const { Session } = require(addonPath);
const fixture = path.join(artifacts, 'vfs-fixture-read.exe');

function scratch(name) {
  const dir = fs.mkdtempSync(path.join(os.tmpdir(), `aether-node-${name}-`));
  return dir;
}

function snapshot(dir) {
  const out = {};
  for (const entry of fs.readdirSync(dir, { recursive: true, withFileTypes: true })) {
    if (entry.isFile()) {
      const full = path.join(entry.parentPath ?? entry.path, entry.name);
      out[path.relative(dir, full)] = fs.readFileSync(full, 'utf8');
    }
  }
  return out;
}

test('a mapped mod file, the real game file under it and copy-on-write writes', { skip: process.platform !== 'win32' }, async () => {
  const root = scratch('smoke');
  const game = path.join(root, 'Game');
  const mods = path.join(root, 'staging', 'Some Mod');
  const overwrite = path.join(root, 'overwrite');
  fs.mkdirSync(game, { recursive: true });
  fs.mkdirSync(mods, { recursive: true });
  fs.writeFileSync(path.join(game, 'base.txt'), 'bbbbb');
  fs.writeFileSync(path.join(mods, 'plugin.bin'), 'mmmmmmm');
  const before = snapshot(game);

  const session = new Session({
    stateDir: path.join(root, 'state'),
    artifactsDir: artifacts,
    roots: [
      {
        location: game,
        base: game,
        files: [{ path: 'Data/plugin.bin', source: path.join(mods, 'plugin.bin') }],
        writeTo: overwrite,
      },
    ],
  });
  try {
    assert.deepEqual(session.roots(), [{ id: 0, location: game, files: 1 }]);
    session.start();
    const read = async (file, length, fill, extraEnv = {}) => {
      const launch = await session.launch({
        image: fixture,
        env: {
          VFS_FIXTURE_PATH: file,
          VFS_FIXTURE_EXPECT: String(length),
          VFS_FIXTURE_FILL: String(fill.charCodeAt(0)),
          ...extraEnv,
        },
      });
      assert.ok(launch.pid > 0);
      return launch.wait();
    };
    // The mod file exists only in the session's view.
    assert.equal(fs.existsSync(path.join(game, 'Data')), false);
    assert.deepEqual(
      await read(path.join(game, 'Data', 'plugin.bin'), 7, 'm', {
        VFS_FIXTURE_WRITE_PATH: path.join(game, 'Data', 'written.txt'),
        VFS_FIXTURE_WRITE_DATA: 'from the game',
      }),
      { kind: 'exited', code: 0 },
    );
    // The real game file is still visible underneath.
    assert.deepEqual(await read(path.join(game, 'base.txt'), 5, 'b'), { kind: 'exited', code: 0 });
    // Writes went to the write folder; the game folder is untouched.
    assert.equal(fs.readFileSync(path.join(overwrite, 'Data', 'written.txt'), 'utf8'), 'from the game');
    assert.deepEqual(snapshot(game), before);
  } finally {
    session.close();
    session.close();
  }
});

test('configuration errors carry their code', () => {
  const root = scratch('config');
  assert.throws(
    () => new Session({ stateDir: path.join(root, 's'), artifactsDir: root, roots: [] }),
    /^Error: E_CONFIG: /,
  );
  assert.throws(
    () =>
      new Session({
        stateDir: path.join(root, 's'),
        artifactsDir: root,
        roots: [{ location: path.join(root, 'g'), files: [{ path: '../escape', source: 'x' }] }],
      }),
    /E_CONFIG: not a relative path/,
  );
  assert.throws(
    () =>
      new Session({
        stateDir: path.join(root, 's'),
        artifactsDir: root,
        roots: [{ location: path.join(root, 'g'), base: path.join(root, 'missing'), files: [] }],
      }),
    /E_CONFIG: base folder/,
  );
});

test('a launch without the shim is refused by name, and calls after close are refused', async () => {
  const root = scratch('state');
  const session = new Session({
    stateDir: path.join(root, 's'),
    artifactsDir: path.join(root, 'no-artifacts'),
    roots: [{ location: path.join(root, 'g'), files: [] }],
  });
  session.start();
  assert.throws(() => session.launch({ image: 'x.exe' }), /^Error: E_ARTIFACTS: /);
  session.close();
  assert.throws(() => session.start(), /^Error: E_STATE: /);
});
