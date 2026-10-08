import test from 'node:test';
import assert from 'node:assert/strict';
import { explicitProxy, initializeProxy, normalizeWindowsProxy } from './proxy';

test('normalizes malformed Windows static HTTP proxy without changing bypass rules', () => {
  assert.deepEqual(normalizeWindowsProxy({ ProxyEnable: 1, ProxyServer: 'http=http://127.0.0.1:7890;https=http://127.0.0.1:7890', ProxyOverride: '<local>;*.internal' }), {
    mode: 'fixed_servers', proxyRules: 'http=127.0.0.1:7890;https=127.0.0.1:7890', proxyBypassRules: '<local>;*.internal',
  });
  assert.equal(normalizeWindowsProxy({ ProxyEnable: 1, ProxyServer: 'http://127.0.0.1:7890' })?.proxyRules, 'http://127.0.0.1:7890');
});
test('leaves valid, disabled, PAC, auto-detect and credential-bearing Windows settings unchanged', () => {
  const malformed = { ProxyEnable: 1, ProxyServer: 'https=http://127.0.0.1:7890' };
  for (const input of [
    { ...malformed, ProxyEnable: 0 }, { ...malformed, AutoConfigURL: 'http://local/proxy.pac' },
    { ...malformed, AutoDetect: 1 }, { ...malformed, ProxyServer: 'http=127.0.0.1:7890;https=127.0.0.1:7890' },
    { ...malformed, ProxyServer: 'https=http://user:secret@127.0.0.1:7890' },
    { ...malformed, ProxyServer: 'https=http://127.0.0.1:7890/file' },
  ]) assert.equal(normalizeWindowsProxy(input), undefined);
});
test('explicit proxy accepts HTTP, HTTPS, SOCKS4 and SOCKS5 while rejecting credentials and URLs with paths', () => {
  for (const protocol of ['http', 'https', 'socks4', 'socks5']) {
    assert.equal(explicitProxy(`${protocol}://127.0.0.1:7890`).proxyRules, `${protocol}://127.0.0.1:7890`);
  }
  for (const input of ['file:///tmp/proxy', '127.0.0.1:7890', 'http://user:secret@host:80', 'http://host/path', 'http://host?secret=x']) {
    assert.throws(() => explicitProxy(input), /LENS_PROXY_URL/);
  }
});
test('initialization prefers explicit override and reconnects only when app routing changed', async () => {
  const applied: unknown[] = []; let closed = 0; let reads = 0;
  const session = { setProxy: async (value: unknown) => { applied.push(value); }, closeAllConnections: async () => { closed++; } };
  const read = async () => { reads++; return { ProxyEnable: 1, ProxyServer: 'https=http://127.0.0.1:7890' }; };
  assert.equal(await initializeProxy(session, 'socks5://127.0.0.1:1080', read), 'override');
  assert.equal(reads, 0); assert.equal(closed, 1);
  assert.equal(await initializeProxy(session, '', read), 'windows-normalized');
  assert.equal(reads, 1); assert.equal(closed, 2);
  assert.equal(await initializeProxy(session, '', async () => undefined), 'system');
  assert.equal(closed, 2); assert.equal(applied.length, 2);
});
