import test from 'node:test';
import assert from 'node:assert/strict';
import { join } from 'node:path';
import { tmpdir } from 'node:os';
import { pathToFileURL } from 'node:url';
import { canonicalRendererFile, isTrustedRendererUrl } from './trust';

const renderer = join(tmpdir(), 'Lens Translate', 'resources', 'app.asar', 'dist', 'index.html');
const alias = join(tmpdir(), 'LENS~1', 'resources', 'app.asar', 'dist', 'index.html');
const canonicalize = (value: string) => {
  if (value === renderer || value === alias) return renderer;
  if (value === join(tmpdir(), 'Lens Translate', 'resources', 'app.asar') || value === join(tmpdir(), 'LENS~1', 'resources', 'app.asar')) return join(tmpdir(), 'Lens Translate', 'resources', 'app.asar');
  return value;
};
test('accepts exact renderer file and Windows short/long aliases after filesystem canonicalization', () => {
  assert.equal(isTrustedRendererUrl(pathToFileURL(renderer).href, renderer, undefined, canonicalize, process.platform, canonicalize), true);
  assert.equal(isTrustedRendererUrl(`${pathToFileURL(renderer).href}#/capture`, alias, undefined, canonicalize, process.platform, canonicalize), true);
  assert.equal(isTrustedRendererUrl(pathToFileURL(alias).href, renderer, undefined, canonicalize, process.platform, canonicalize), true);
});
test('resolves only physical ASAR file and preserves exact validated inner entry', () => {
  const calls: string[] = [];
  const physical = (value: string) => { calls.push(value); return '/canonical/app.asar'; };
  assert.equal(canonicalRendererFile(renderer, () => { throw new Error('Virtual realpath must not be used'); }, physical), '/canonical/app.asar/dist/index.html');
  assert.equal(calls.length, 1);
  assert.match(calls[0], /app\.asar$/);
  assert.throws(() => canonicalRendererFile('C:\\fixture\\app.asar\\dist\\..\\index.html', canonicalize, physical), /Invalid archive entry/);
  assert.notEqual(canonicalRendererFile('C:\\fixture\\app.asar\\DIST\\index.html', canonicalize, physical), canonicalRendererFile(renderer, canonicalize, physical));
});
test('canonicalization does not permit another local file, remote origin, query or missing file', () => {
  assert.equal(isTrustedRendererUrl(pathToFileURL(join(tmpdir(), 'evil.html')).href, renderer, undefined, canonicalize), false);
  assert.equal(isTrustedRendererUrl('https://example.com/index.html', renderer, undefined, canonicalize), false);
  assert.equal(isTrustedRendererUrl(`${pathToFileURL(renderer).href}?remote=1`, renderer, undefined, canonicalize), false);
  assert.equal(isTrustedRendererUrl(pathToFileURL(renderer).href, renderer, undefined, () => { throw new Error('missing'); }), false);
});
test('development origin remains exact and cannot accept a lookalike hostname', () => {
  assert.equal(isTrustedRendererUrl('http://127.0.0.1:5173/#/capture', renderer, 'http://127.0.0.1:5173'), true);
  assert.equal(isTrustedRendererUrl('http://127.0.0.1:5174/', renderer, 'http://127.0.0.1:5173'), false);
  assert.equal(isTrustedRendererUrl('http://127.0.0.1.evil.test:5173/', renderer, 'http://127.0.0.1:5173'), false);
});
