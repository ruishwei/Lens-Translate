import { existsSync, mkdirSync, readFileSync, renameSync, writeFileSync } from 'node:fs';
import { join } from 'node:path';
import { DEFAULT_SETTINGS, Settings, Translation, validateSettings } from './core';

export class LocalStore {
  private settings: Settings = { ...DEFAULT_SETTINGS };
  private history: Translation[] = [];
  private readonly filename: string;
  constructor(directory: string) {
    mkdirSync(directory, { recursive: true });
    this.filename = join(directory, 'lens-data.json');
    if (existsSync(this.filename)) {
      try {
        const value = JSON.parse(readFileSync(this.filename, 'utf8'));
        this.settings = validateSettings(value.settings);
        if (Array.isArray(value.history)) this.history = value.history.filter(isTranslation).slice(0, 200);
      } catch { /* Keep startup usable if a locally edited or interrupted file is invalid. */ }
    }
  }
  getSettings(): Settings { return { ...this.settings }; }
  getHistory(): Translation[] { return this.history.map(item => ({ ...item })); }
  saveSettings(input: unknown) {
    const next = validateSettings(input, this.settings);
    this.persist(next, this.history);
    this.settings = next;
    return this.getSettings();
  }
  addHistory(value: Translation) {
    if (!this.settings.saveHistory) return;
    // Bound both entry count and total on-disk data; very long translations remain usable.
    const history = [value, ...this.history].slice(0, 200);
    while (history.length > 1 && JSON.stringify(history).length > 2_000_000) history.pop();
    this.persist(this.settings, history);
    this.history = history;
  }
  clearHistory() { this.persist(this.settings, []); this.history = []; }
  deleteHistory(id: unknown) {
    if (typeof id !== 'string' || id.length > 100) throw new Error('无效的历史记录编号。');
    const next = this.history.filter(item => item.id !== id);
    this.persist(this.settings, next); this.history = next;
  }
  private persist(settings: Settings, history: Translation[]) {
    const temp = `${this.filename}.tmp`;
    writeFileSync(temp, JSON.stringify({ version: 1, settings, history }), { encoding: 'utf8', mode: 0o600 });
    renameSync(temp, this.filename);
  }
}
function isTranslation(value: unknown): value is Translation {
  if (!value || typeof value !== 'object') return false;
  const v = value as Record<string, unknown>;
  return ['id', 'sourceText', 'translatedText', 'sourceLanguage', 'targetLanguage', 'createdAt', 'model'].every(k => typeof v[k] === 'string')
    && typeof v.durationMs === 'number' && (v.sourceText as string).length <= 12000 && (v.translatedText as string).length <= 60000;
}
