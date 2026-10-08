// Reassemble the already-tested binaries; never rebuild or re-sign during publication.
import { createHash } from 'node:crypto';
import { closeSync, existsSync, mkdirSync, openSync, readFileSync, readSync, statSync, writeFileSync, writeSync } from 'node:fs';
import { resolve } from 'node:path';

const [manifestPath, bundlePath, outputPath] = process.argv.slice(2);
if (!manifestPath || !bundlePath || !outputPath) throw new Error('Usage: node scripts/assemble-release.mjs manifest.json bundle-directory output-directory');
const manifest = JSON.parse(readFileSync(manifestPath, 'utf8'));
if (!/^v\d+\.\d+\.\d+$/.test(manifest.tag) || !/^[0-9a-f]{40}$/.test(manifest.bundleCommit)) throw new Error('Invalid release manifest');
if (existsSync(outputPath)) throw new Error('Output directory must be new');
mkdirSync(outputPath, { recursive: true });
const filename = value => {
  if (typeof value !== 'string' || !/^[A-Za-z0-9][A-Za-z0-9._-]*$/.test(value)) throw new Error('Unsafe artifact filename');
  return value;
};
const names = new Set();
const sums = [];
for (const asset of manifest.assets) {
  const name = filename(asset.name);
  if (names.has(name) || !/^[0-9a-f]{64}$/.test(asset.sha256) || !Number.isSafeInteger(asset.bytes) || asset.bytes <= 0 || !asset.parts?.length) throw new Error('Invalid asset metadata');
  names.add(name);
  const target = resolve(outputPath, name);
  const output = openSync(target, 'wx');
  const hash = createHash('sha256');
  let bytes = 0;
  try {
    for (const part of asset.parts) {
      const source = resolve(bundlePath, filename(part));
      if (!statSync(source).isFile()) throw new Error('Part is not a file');
      const input = openSync(source, 'r');
      try {
        const buffer = Buffer.alloc(1024 * 1024);
        let count;
        while ((count = readSync(input, buffer, 0, buffer.length, null)) > 0) {
          const block = buffer.subarray(0, count);
          hash.update(block);
          let written = 0;
          while (written < count) written += writeSync(output, block, written, count - written);
          bytes += count;
        }
      } finally { closeSync(input); }
    }
  } finally { closeSync(output); }
  if (bytes !== asset.bytes || hash.digest('hex') !== asset.sha256) throw new Error(`Integrity check failed: ${name}`);
  sums.push(`${asset.sha256}  ${name}`);
  console.log(`Verified ${name} (${bytes} bytes)`);
}
writeFileSync(resolve(outputPath, 'SHA256SUMS.txt'), `${sums.join('\n')}\n`, { flag: 'wx' });
