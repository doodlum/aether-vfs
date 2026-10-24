// A separate process that knows nothing of aether-vfs: plain `fs` calls under a folder, one
// result per operation, as JSON on stdout. What any program sees of a mounted root.
//
// node fs-probe.mjs <folder> '<[{"op":"read","path":"Data/x"}, ...]>'
import fs from 'node:fs';
import path from 'node:path';

const [root, opsJson] = process.argv.slice(2);
const out = [];
for (const op of JSON.parse(opsJson)) {
  const p = path.join(root, op.path);
  try {
    switch (op.op) {
      case 'read':
        out.push(fs.readFileSync(p, 'utf8'));
        break;
      case 'write':
        fs.writeFileSync(p, op.data);
        out.push('ok');
        break;
      case 'append':
        fs.appendFileSync(p, op.data);
        out.push('ok');
        break;
      case 'mkdir':
        fs.mkdirSync(p);
        out.push('ok');
        break;
      case 'rename':
        fs.renameSync(p, path.join(root, op.to));
        out.push('ok');
        break;
      case 'delete':
        fs.unlinkSync(p);
        out.push('ok');
        break;
      case 'list':
        out.push(fs.readdirSync(p).sort());
        break;
      case 'stat': {
        const s = fs.statSync(p);
        out.push({ size: s.size, mtime: Math.floor(s.mtimeMs / 1000), dir: s.isDirectory() });
        break;
      }
      case 'exists':
        out.push(fs.existsSync(p));
        break;
      default:
        out.push(`unknown op ${op.op}`);
    }
  } catch (e) {
    out.push(`err ${e.code}`);
  }
}
process.stdout.write(JSON.stringify(out));
