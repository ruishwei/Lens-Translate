import { realpathSync } from 'node:fs';
import { fileURLToPath } from 'node:url';

export function canonicalRendererFile(
  value: string,
  canonicalize: (value: string) => string = realpathSync,
  canonicalizePhysical: (value: string) => string = realpathSync.native,
  platform: NodeJS.Platform = process.platform,
): string {
  const normalizePhysical = (path: string) => platform === 'win32' ? path.toLowerCase() : path;
  const archive = /^(.*\.asar)[\\/](.+)$/i.exec(value);
  if (!archive) return normalizePhysical(canonicalize(value));
  const inner = archive[2].replace(/\\/g, '/');
  if (inner.split('/').some(segment => !segment || segment === '.' || segment === '..')) throw new Error('Invalid archive entry');
  // Electron's patched realpathSync preserves the spelling of the outer archive
  // for virtual entries. Resolve the physical .asar file with the native OS call
  // instead, then retain the exact case-sensitive path of the bundled entry.
  return `${normalizePhysical(canonicalizePhysical(archive[1]))}/${inner}`;
}

export function isTrustedRendererUrl(
  candidate: string,
  rendererPath: string,
  developmentOrigin?: string,
  canonicalize: (value: string) => string = realpathSync,
  platform: NodeJS.Platform = process.platform,
  canonicalizePhysical: (value: string) => string = realpathSync.native,
): boolean {
  try {
    const url = new URL(candidate);
    if (developmentOrigin) return url.origin === developmentOrigin;
    if (url.protocol !== 'file:' || url.host || url.search) return false;
    // NSIS may launch from an 8.3 temporary path (ADMINI~1). Chromium resolves
    // that path to its long spelling. Canonicalize both exact files, preserving
    // the existing sender-window and top-frame checks in the IPC handler.
    return canonicalRendererFile(fileURLToPath(url), canonicalize, canonicalizePhysical, platform)
      === canonicalRendererFile(rendererPath, canonicalize, canonicalizePhysical, platform);
  } catch { return false; }
}
