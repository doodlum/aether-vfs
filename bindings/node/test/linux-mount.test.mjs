// Linux: a session mounts each root with FUSE at its location, so a separate process that knows
// nothing of aether-vfs sees the composed folder with plain `fs` calls. A game folder with real
// files, a mod file mapped in from a staging folder, and a write folder; the game folder itself
// must be exactly as it was once the session closes.
//
// Needs /dev/fuse and fusermount3 (package fuse3). node --test bindings/node/test/
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { execFile, spawn } from 'node:child_process';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { promisify } from 'node:util';

const linux = process.platform === 'linux';
const skip = linux ? false : 'Linux only: roots are mounted with FUSE';
const { Session } = linux ? await import('./addon.mjs') : {};
const here = path.dirname(fileURLToPath(import.meta.url));
const run = promisify(execFile);

function scratch(name) {
  return fs.mkdtempSync(path.join(os.tmpdir(), `aether-node-${name}-`));
}

function snapshot(dir) {
  const out = {};
  for (const entry of fs.readdirSync(dir, { recursive: true, withFileTypes: true })) {
    const full = path.join(entry.parentPath ?? entry.path, entry.name);
    out[path.relative(dir, full)] = entry.isFile() ? fs.readFileSync(full, 'utf8') : '<dir>';
  }
  return out;
}

/** Run `ops` in a separate Node process against `root`; one result per op. */
async function probe(root, ops) {
  const { stdout } = await run(process.execPath, [path.join(here, 'fs-probe.mjs'), root, JSON.stringify(ops)]);
  return JSON.parse(stdout);
}

function layout(name) {
  const root = scratch(name);
  const game = path.join(root, 'Game');
  const mods = path.join(root, 'staging', 'Some Mod');
  const overwrite = path.join(root, 'overwrite');
  fs.mkdirSync(path.join(game, 'Data'), { recursive: true });
  fs.mkdirSync(mods, { recursive: true });
  fs.writeFileSync(path.join(game, 'base.txt'), 'bbbbb');
  fs.writeFileSync(path.join(game, 'Data', 'Existing.esm'), 'eee');
  fs.writeFileSync(path.join(mods, 'plugin.bin'), 'mmmmmmm');
  fs.writeFileSync(path.join(mods, 'late.txt'), 'deployed later');
  return { root, game, mods, overwrite };
}

test('a mounted root: mapped files over the real folder, writes in the write folder, live setRoot', { skip }, async () => {
  const { root, game, mods, overwrite } = layout('mount');
  const before = snapshot(game);
  const baseMtime = Math.floor(fs.statSync(path.join(game, 'base.txt')).mtimeMs / 1000);
  const spec = (files) => ({ location: game, base: game, files, writeTo: overwrite });
  const session = new Session({
    stateDir: path.join(root, 'state'),
    artifactsDir: path.join(root, 'state'),
    roots: [spec([{ path: 'Data/plugin.bin', source: path.join(mods, 'plugin.bin') }])],
  });
  try {
    assert.deepEqual(session.mounted(), []);
    session.start();
    session.start();
    assert.deepEqual(session.mounted(), [0]);
    assert.deepEqual(session.roots(), [{ id: 0, location: game, files: 1 }]);

    assert.deepEqual(
      await probe(game, [
        { op: 'read', path: 'Data/plugin.bin' },
        { op: 'read', path: 'data/PLUGIN.BIN' },
        { op: 'read', path: 'base.txt' },
        { op: 'read', path: 'DATA/existing.esm' },
        { op: 'list', path: '.' },
        { op: 'list', path: 'Data' },
        { op: 'stat', path: 'base.txt' },
        { op: 'stat', path: 'Data/plugin.bin' },
        { op: 'write', path: 'Data/written.txt', data: 'from the game' },
        { op: 'append', path: 'base.txt', data: '+log' },
        { op: 'mkdir', path: 'Saves' },
        { op: 'write', path: 'saves/one.sav', data: 'save' },
        { op: 'rename', path: 'Saves/one.sav', to: 'Saves/two.sav' },
        { op: 'delete', path: 'Data/Existing.esm' },
        { op: 'list', path: 'Data' },
        { op: 'read', path: 'base.txt' },
      ]),
      [
        'mmmmmmm',
        'mmmmmmm',
        'bbbbb',
        'eee',
        ['Data', 'base.txt'],
        ['Existing.esm', 'plugin.bin'],
        { size: 5, mtime: baseMtime, dir: false },
        { size: 7, mtime: Math.floor(fs.statSync(path.join(mods, 'plugin.bin')).mtimeMs / 1000), dir: false },
        'ok',
        'ok',
        'ok',
        'ok',
        'ok',
        'ok',
        ['plugin.bin', 'written.txt'],
        'bbbbb+log',
      ],
    );
    // Every change is in the write folder.
    assert.equal(fs.readFileSync(path.join(overwrite, 'Data', 'written.txt'), 'utf8'), 'from the game');
    assert.equal(fs.readFileSync(path.join(overwrite, 'base.txt'), 'utf8'), 'bbbbb+log');
    assert.equal(fs.readFileSync(path.join(overwrite, 'Saves', 'two.sav'), 'utf8'), 'save');

    // A deployment changes while mounted: one file purged, another added.
    session.setRoot(0, spec([{ path: 'Data/late.txt', source: path.join(mods, 'late.txt') }]));
    assert.deepEqual(
      await probe(game, [
        { op: 'exists', path: 'Data/plugin.bin' },
        { op: 'read', path: 'Data/late.txt' },
        { op: 'list', path: 'Data' },
        { op: 'read', path: 'Data/written.txt' },
      ]),
      [false, 'deployed later', ['late.txt', 'written.txt'], 'from the game'],
    );

    assert.throws(() => session.launch({ image: path.join(game, 'game.exe') }), /^Error: E_PLATFORM: /);
  } finally {
    session.close();
    session.close();
  }
  assert.deepEqual(session.mounted(), []);
  // The real game folder is exactly as it was.
  assert.deepEqual(snapshot(game), before);
});

test('a mount left by a crashed host is recovered by the next session', { skip }, async () => {
  const { root, game, mods } = layout('crash');
  const ready = path.join(root, 'ready');
  const child = spawn(process.execPath, [path.join(here, 'crash-mount.mjs'), path.join(root, 'crashed'), game, ready], {
    stdio: 'inherit',
  });
  const signal = await new Promise((resolve) => child.on('exit', (_code, sig) => resolve(sig)));
  assert.equal(signal, 'SIGKILL');
  assert.ok(fs.existsSync(ready), 'the crashing host never mounted');
  assert.throws(() => fs.readdirSync(game), { code: 'ENOTCONN' });

  const session = new Session({
    stateDir: path.join(root, 'state'),
    artifactsDir: path.join(root, 'state'),
    roots: [{ location: game, base: game, files: [{ path: 'Data/plugin.bin', source: path.join(mods, 'plugin.bin') }] }],
  });
  try {
    session.start();
    assert.deepEqual(await probe(game, [{ op: 'read', path: 'Data/plugin.bin' }, { op: 'read', path: 'base.txt' }]), [
      'mmmmmmm',
      'bbbbb',
    ]);
  } finally {
    session.close();
  }
  assert.deepEqual(fs.readdirSync(game).sort(), ['Data', 'base.txt']);
});

test('what a mount cannot serve is refused with its code', { skip }, async () => {
  const { root, game, mods, overwrite } = layout('refuse');
  const make = (roots) => new Session({ stateDir: path.join(root, 's'), artifactsDir: root, roots });
  assert.throws(
    () => make([{ location: game, files: [{ path: 'x', source: path.join(game, 'base.txt') }] }]),
    /^Error: E_CONFIG: file source .* is inside root location/,
  );
  assert.throws(
    () => make([{ location: game, files: [], writeTo: path.join(game, 'overwrite') }]),
    /^Error: E_CONFIG: write folder .* is inside root location/,
  );
  assert.throws(
    () => make([{ location: game, files: [] }, { location: path.join(game, 'Data'), files: [] }]),
    /^Error: E_CONFIG: .*nested roots are not supported on Linux/,
  );
  const first = make([{ location: game, base: game, files: [], writeTo: overwrite }]);
  try {
    first.start();
    assert.throws(() => make([{ location: game, files: [] }]), /^Error: E_STATE: .*already mounted/);
  } finally {
    first.close();
  }
  assert.equal(fs.readFileSync(path.join(game, 'base.txt'), 'utf8'), 'bbbbb');
  void mods;
});
