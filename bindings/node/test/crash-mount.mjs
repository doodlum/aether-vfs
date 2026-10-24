// A host that mounts a root and dies without unmounting it (SIGKILL), leaving a stale mount the
// next session must recover. Run by linux-mount.test.mjs.
//
// node crash-mount.mjs <stateDir> <location> <readyFile>
import fs from 'node:fs';
import { Session } from './addon.mjs';

const [stateDir, location, ready] = process.argv.slice(2);
const session = new Session({
  stateDir,
  artifactsDir: stateDir,
  roots: [{ location, base: location, files: [] }],
});
session.start();
fs.writeFileSync(ready, '');
process.kill(process.pid, 'SIGKILL');
