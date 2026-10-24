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
    // A program that lives in the game folder, as a game does: the shim is not beside it, so
    // the in-memory import patch must find it on the launch's PATH.
    const inGame = path.join(game, 'game.exe');
    fs.copyFileSync(fixture, inGame);
    before['game.exe'] = fs.readFileSync(inGame, 'utf8');
    const launch = await session.launch({
      image: inGame,
      env: {
        VFS_FIXTURE_PATH: path.join(game, 'Data', 'plugin.bin'),
        VFS_FIXTURE_EXPECT: '7',
        VFS_FIXTURE_FILL: String('m'.charCodeAt(0)),
      },
    });
    assert.deepEqual(await launch.wait(), { kind: 'exited', code: 0 });
    // Writes went to the write folder; the game folder is untouched.
    assert.equal(fs.readFileSync(path.join(overwrite, 'Data', 'written.txt'), 'utf8'), 'from the game');
    assert.deepEqual(snapshot(game), before);
  } finally {
    session.close();
    session.close();
  }
});

// The same session launching a 32-bit program (Fallout 3, New Vegas and Oblivion are): the
// 32-bit shim and injector sit in `x86\` beside the 64-bit ones, built for i686-pc-windows-msvc
// (see the README). Skipped when they, or the 32-bit fixture, have not been built.
const x86Dir = path.join(artifacts, 'x86');
const fixture32 = path.resolve(here, '../../../rust/target/i686-pc-windows-msvc', profile, 'vfs-fixture-read.exe');
const x86Missing =
  process.platform !== 'win32'
    ? 'Windows only'
    : [path.join(x86Dir, 'vfs_shim_dll.dll'), path.join(x86Dir, 'vfs-injector.exe'), fixture32].find(
        (p) => !fs.existsSync(p),
      );

test('a 32-bit program sees the mapped mod file and the game file, and writes copy-on-write', { skip: x86Missing ? `no ${x86Missing}` : false }, async () => {
  const root = scratch('x86');
  const game = path.join(root, 'Game');
  const mods = path.join(root, 'staging', 'Some Mod');
  const overwrite = path.join(root, 'overwrite');
  fs.mkdirSync(game, { recursive: true });
  fs.mkdirSync(mods, { recursive: true });
  fs.writeFileSync(path.join(game, 'base.txt'), 'bbbbb');
  fs.writeFileSync(path.join(mods, 'plugin.bin'), 'mmmmmmm');
  // A 32-bit program in the game folder, as a 32-bit game is.
  const inGame = path.join(game, 'Game32.exe');
  fs.copyFileSync(fixture32, inGame);
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
  const out = path.join(root, 'ops.out');
  const script = path.join(root, 'ops.txt');
  fs.writeFileSync(
    script,
    [
      `read\t${path.join(game, 'Data', 'plugin.bin')}`,
      `read\t${path.join(game, 'base.txt')}`,
      `write\t${path.join(game, 'base.txt')}\tfrom a 32-bit game`,
      `write\t${path.join(game, 'Data', 'written.txt')}\tnew`,
    ].join('\n'),
  );
  try {
    session.start();
    const launch = await session.launch({
      image: inGame,
      env: { VFS_FIXTURE_OPS: script, VFS_FIXTURE_OPS_OUT: out },
    });
    assert.deepEqual(await launch.wait(), { kind: 'exited', code: 0 });
    const results = fs
      .readFileSync(out, 'utf8')
      .trim()
      .split('\n')
      .map((line) => line.split('\t').at(-1));
    assert.deepEqual(results, ['ok mmmmmmm', 'ok bbbbb', 'ok', 'ok']);
    assert.equal(fs.readFileSync(path.join(overwrite, 'base.txt'), 'utf8'), 'from a 32-bit game');
    assert.equal(fs.readFileSync(path.join(overwrite, 'Data', 'written.txt'), 'utf8'), 'new');
    assert.deepEqual(snapshot(game), before);
  } finally {
    session.close();
  }
});

test('a root changed while its program runs is what the program sees next', { skip: process.platform !== 'win32' }, async () => {
  const root = scratch('live');
  const game = path.join(root, 'Game');
  const mods = path.join(root, 'staging');
  fs.mkdirSync(game, { recursive: true });
  fs.mkdirSync(mods, { recursive: true });
  fs.writeFileSync(path.join(mods, 'plugin.bin'), 'mmmmmmm');
  fs.writeFileSync(path.join(mods, 'late.txt'), 'deployed later');
  const ready = path.join(root, 'ready');
  const go = path.join(root, 'go');
  const out = path.join(root, 'ops.out');
  const script = path.join(root, 'ops.txt');
  const data = path.join(game, 'Data');
  fs.writeFileSync(
    script,
    [
      `read\t${path.join(data, 'plugin.bin')}`,
      `write\t${ready}\tx`,
      `waitfor\t${go}`,
      `read\t${path.join(data, 'late.txt')}`,
      `stat\t${path.join(data, 'plugin.bin')}`,
      `list\t${data}`,
    ].join('\n'),
  );
  const spec = (files) => ({ location: game, base: game, files });
  const session = new Session({
    stateDir: path.join(root, 'state'),
    artifactsDir: artifacts,
    roots: [spec([{ path: 'Data/plugin.bin', source: path.join(mods, 'plugin.bin') }])],
  });
  try {
    session.start();
    const launch = await session.launch({
      image: fixture,
      env: { VFS_FIXTURE_OPS: script, VFS_FIXTURE_OPS_OUT: out },
    });
    const deadline = Date.now() + 60_000;
    while (!fs.existsSync(ready)) {
      assert.ok(Date.now() < deadline, 'the program never got past its first read');
      await new Promise((resolve) => setTimeout(resolve, 20));
    }
    // A deployment changes while the program runs: one file purged, another added.
    session.setRoot(0, spec([{ path: 'Data/late.txt', source: path.join(mods, 'late.txt') }]));
    assert.deepEqual(session.roots(), [{ id: 0, location: game, files: 1 }]);
    fs.writeFileSync(go, '');
    assert.deepEqual(await launch.wait(), { kind: 'exited', code: 0 });
    const results = fs
      .readFileSync(out, 'utf8')
      .trim()
      .split('\n')
      .map((line) => line.split('\t').at(-1));
    assert.deepEqual(results, [
      'ok mmmmmmm',
      'ok',
      'ok',
      'ok deployed later',
      'err NotFound 2',
      'ok late.txt',
    ]);
  } finally {
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
