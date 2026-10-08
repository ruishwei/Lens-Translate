import { execFile } from 'node:child_process';
import { mkdtemp, writeFile, rm } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { validateLanguage } from './core';

const MAX_IMAGE_BYTES = 15 * 1024 * 1024;
export function decodeImage(dataUrl: unknown): Buffer {
  if (typeof dataUrl !== 'string' || dataUrl.length > MAX_IMAGE_BYTES * 1.4) throw new Error('图片大小不能超过 15 MB。');
  const match = /^data:image\/(?:png|jpeg|webp|bmp);base64,([A-Za-z0-9+/]*={0,2})$/.exec(dataUrl);
  if (!match) throw new Error('请使用 PNG、JPEG、WebP 或 BMP 图片。');
  const bytes = Buffer.from(match[1], 'base64');
  if (bytes.length < 12 || bytes.length > MAX_IMAGE_BYTES) throw new Error('图片内容为空或超过 15 MB。');
  return bytes;
}
export class WindowsOcr {
  constructor(private readonly scriptPath: string) {}
  private run(args: string[]): Promise<Record<string, unknown>> {
    if (process.platform !== 'win32') return Promise.reject(new Error('此版本的本地 OCR 需要 Windows 10 或更新版本。'));
    const executable = join(process.env.SystemRoot || 'C:\\Windows', 'System32', 'WindowsPowerShell', 'v1.0', 'powershell.exe');
    return new Promise((resolve, reject) => {
      execFile(executable, ['-NoLogo', '-NoProfile', '-NonInteractive', '-ExecutionPolicy', 'Bypass', '-File', this.scriptPath, ...args],
        { windowsHide: true, timeout: 30000, maxBuffer: 2 * 1024 * 1024, encoding: 'utf8' }, (error, stdout) => {
          let result: Record<string, unknown>;
          try { result = JSON.parse(stdout.replace(/^\uFEFF/, '').trim()); }
          catch { reject(new Error(error?.killed ? '文字识别超时，请缩小截图范围后重试。' : 'Windows 文字识别不可用，请确认已安装 Windows OCR 语言包。')); return; }
          if (error || result.error) {
            const message = String(result.error || '');
            if (message.includes('OCR_LANGUAGE_MISSING')) reject(new Error('尚未安装此语言的 Windows OCR 语言包。请在 Windows「设置 → 时间和语言 → 语言和区域」中安装对应语言的基本输入/OCR，然后重启译镜。'));
            else if (message.includes('OCR_IMAGE_TOO_LARGE')) reject(new Error('图片尺寸超过 Windows OCR 限制，请缩小图片或截图范围。'));
            else reject(new Error('Windows 未能识别这张图片，请换用 PNG/JPEG 图片或重新截图。'));
          } else resolve(result);
        });
    });
  }
  async languages(): Promise<string[]> {
    const result = await this.run(['-Action', 'languages']);
    return Array.isArray(result.languages) ? result.languages.filter((v): v is string => typeof v === 'string') : [];
  }
  async recognize(input: unknown): Promise<{ text: string; language: string }> {
    if (!input || typeof input !== 'object') throw new Error('图片识别请求格式不正确。');
    const { dataUrl, language } = input as { dataUrl: unknown; language: unknown };
    const bytes = decodeImage(dataUrl);
    const selected = validateLanguage(language);
    const directory = await mkdtemp(join(tmpdir(), 'lens-ocr-'));
    const filename = join(directory, 'capture.png');
    try {
      await writeFile(filename, bytes);
      const result = await this.run(['-Action', 'recognize', '-ImagePath', filename, '-Language', selected]);
      const text = typeof result.text === 'string' ? result.text : '';
      if (!text.trim()) throw new Error('没有识别到文字，请选择更清晰、包含文字的区域。');
      return { text, language: String(result.language || selected) };
    } finally { await rm(directory, { recursive: true, force: true }); }
  }
}
