import fs from 'node:fs';
import path from 'node:path';
import crypto from 'node:crypto';
import { pipeline } from 'node:stream/promises';
import { Writable } from 'node:stream';

const usage = `Usage:
  node scripts/device-fixtures.mjs generate <source-directory> [1,20,100,512]
  node scripts/device-fixtures.mjs verify <source-directory> <received-directory>
Copy received files from Download/OptiFerry into a separate directory before verifying.
Existing fixture files are never overwritten. Node.js is needed only for testing.`;

async function hash(file) {
  const digest = crypto.createHash('sha256');
  await pipeline(fs.createReadStream(file), new Writable({
    write(chunk, encoding, callback) { digest.update(chunk); callback(); },
  }));
  return digest.digest('hex');
}

async function main() {
  const [command, directory, argument, ...extra] = process.argv.slice(2);
  if (command === '--help') { console.log(usage); return; }
  if (!directory || extra.length || !['generate', 'verify'].includes(command)) throw new Error(usage);
  const source = path.resolve(directory);
  const manifestPath = path.join(source, 'manifest.json');
  if (command === 'generate') {
    const sizes = (argument ?? '1,20,100,512').split(',').map(Number);
    if (!sizes.length || sizes.some(n => !Number.isInteger(n) || n < 1 || n > 512) || new Set(sizes).size !== sizes.length)
      throw new Error('Sizes must be distinct whole MiB values from 1 through 512.');
    fs.mkdirSync(source, { recursive: true });
    const files = sizes.map(size => ({ name: `OptiFerry-test-${size}MiB.bin`, bytes: size * 1024 * 1024 }));
    for (const name of ['manifest.json', ...files.map(f => f.name)]) {
      if (fs.existsSync(path.join(source, name))) throw new Error(`Already exists: ${path.join(source, name)}. Choose a fresh directory.`);
    }
    for (const file of files) {
      const fd = fs.openSync(path.join(source, file.name), 'wx');
      const digest = crypto.createHash('sha256');
      try {
        for (let offset = 0; offset < file.bytes; offset += 1024 * 1024) {
          const block = crypto.randomBytes(Math.min(1024 * 1024, file.bytes - offset));
          let written = 0;
          while (written < block.length) {
            const n = fs.writeSync(fd, block, written, block.length - written);
            if (n <= 0) throw new Error('File write made no progress');
            written += n;
          }
          digest.update(block);
        }
        fs.fsyncSync(fd);
      } finally { fs.closeSync(fd); }
      file.sha256 = digest.digest('hex');
      console.log(`${file.sha256}  ${file.name}`);
    }
    fs.writeFileSync(manifestPath, JSON.stringify({ version: 1, createdAt: new Date().toISOString(), files }, null, 2) + '\n', { flag: 'wx' });
    return;
  }
  if (!argument) throw new Error(usage);
  const received = path.resolve(argument);
  if (fs.realpathSync(source) === fs.realpathSync(received)) throw new Error('Source and received directories must be different.');
  const manifest = JSON.parse(fs.readFileSync(manifestPath, 'utf8'));
  if (manifest.version !== 1 || !Array.isArray(manifest.files) || !manifest.files.length) throw new Error('Invalid manifest');
  const results = [];
  for (const file of manifest.files) {
    if (!/^OptiFerry-test-[1-9][0-9]*MiB\.bin$/.test(file.name) || !Number.isSafeInteger(file.bytes) || file.bytes <= 0 || !/^[a-f0-9]{64}$/.test(file.sha256))
      throw new Error('Invalid manifest entry');
    try {
      const original = path.join(source, file.name);
      const target = path.join(received, file.name);
      if (fs.realpathSync(original) === fs.realpathSync(target)) throw new Error('Received file resolves to source file');
      const a = fs.statSync(original), b = fs.statSync(target);
      if (a.dev === b.dev && a.ino === b.ino) throw new Error('Received file is a hard link to source file');
      if (a.size !== file.bytes || await hash(original) !== file.sha256) throw new Error('Source changed since generation');
      if (b.size !== file.bytes) throw new Error(`Size mismatch: expected ${file.bytes}, got ${b.size}`);
      const actual = await hash(target);
      if (actual !== file.sha256) throw new Error(`SHA-256 mismatch: ${actual}`);
      results.push({ name: file.name, status: 'PASS', bytes: b.size, sha256: actual });
    } catch (error) { results.push({ name: file.name, status: 'FAIL', reason: error.message }); }
  }
  console.log(JSON.stringify({ checkedAt: new Date().toISOString(), source, received, results }, null, 2));
  if (results.some(r => r.status !== 'PASS')) process.exitCode = 1;
}

main().catch(error => { console.error(error.message); process.exitCode = 1; });
