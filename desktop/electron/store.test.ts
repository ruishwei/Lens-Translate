import test from 'node:test';
import assert from 'node:assert/strict';
import { mkdtempSync, readFileSync, rmSync, writeFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { LocalStore } from './store';
import { Translation } from './core';
const entry = (id: string): Translation => ({ id, sourceText: 'Hello', translatedText: '你好', sourceLanguage: 'en', targetLanguage: 'zh-CN', createdAt: new Date().toISOString(), model: 'deepseek-flash', durationMs: 12 });
test('settings and bounded history survive reload, history opt-out works, deletion persists', () => {
  const directory = mkdtempSync(join(tmpdir(), 'lens-store-test-'));
  try {
    const store = new LocalStore(directory);
    store.saveSettings({ targetLanguage: 'ja' });
    for (let i = 0; i < 204; i++) store.addHistory(entry(String(i)));
    assert.equal(store.getHistory().length, 200);
    assert.equal(store.getHistory()[0].id, '203');
    store.saveSettings({ saveHistory: false });
    store.addHistory(entry('do-not-store'));
    const reload = new LocalStore(directory);
    assert.equal(reload.getSettings().targetLanguage, 'ja');
    assert.equal(reload.getSettings().saveHistory, false);
    assert.equal(reload.getHistory()[0].id, '203');
    reload.deleteHistory('203');
    assert.equal(new LocalStore(directory).getHistory().length, 199);
    reload.clearHistory();
    assert.deepEqual(new LocalStore(directory).getHistory(), []);
    assert.equal(readFileSync(join(directory, 'lens-data.json'), 'utf8').includes('apiKey'), false);
  } finally { rmSync(directory, { recursive: true, force: true }); }
});
test('invalid saved state cannot prevent startup or change trusted model', () => {
  const directory = mkdtempSync(join(tmpdir(), 'lens-store-test-'));
  try {
    writeFileSync(join(directory, 'lens-data.json'), '{invalid');
    assert.equal(new LocalStore(directory).getSettings().model, 'deepseek-flash');
    writeFileSync(join(directory, 'lens-data.json'), JSON.stringify({ settings: {}, history: [{ id: 'invalid' }, entry('valid')] }));
    const store = new LocalStore(directory);
    assert.equal(store.getHistory().length, 1);
    const copy = store.getHistory(); copy[0].sourceText = 'changed';
    assert.equal(store.getHistory()[0].sourceText, 'Hello');
  } finally { rmSync(directory, { recursive: true, force: true }); }
});
