import { execFile } from 'node:child_process';
import { join } from 'node:path';

export interface WindowsProxySettings {
  ProxyEnable?: number;
  ProxyServer?: string;
  ProxyOverride?: string;
  AutoConfigURL?: string;
  AutoDetect?: number;
}
export interface SessionProxyConfig {
  mode: 'fixed_servers';
  proxyRules: string;
  proxyBypassRules?: string;
}
interface ProxySession {
  setProxy(config: SessionProxyConfig): Promise<void>;
  closeAllConnections(): Promise<void>;
}
const EXPLICIT_ERROR = 'LENS_PROXY_URL 格式无效，请使用不含用户名和密码的 HTTP、HTTPS、SOCKS4 或 SOCKS5 代理地址。';

export function explicitProxy(value: string): SessionProxyConfig {
  try {
    const input = value.trim();
    if (!/^(https?|socks4|socks5):\/\//i.test(input)) throw new Error();
    const url = new URL(input);
    if (!['http:', 'https:', 'socks4:', 'socks5:'].includes(url.protocol) || !url.hostname
      || url.username || url.password || url.search || url.hash || (url.pathname && url.pathname !== '/')) throw new Error();
    return {
      mode: 'fixed_servers', proxyRules: `${url.protocol}//${url.host}`,
      proxyBypassRules: '<local>;localhost;127.0.0.1;[::1]',
    };
  } catch { throw new Error(EXPLICIT_ERROR); }
}

// Some Windows proxy clients save protocol entries such as
// "https=http://127.0.0.1:7890". Chromium can misparse this as host "http:80".
// Normalize only that malformed static syntax; valid system/PAC settings remain owned by Windows.
export function normalizeWindowsProxy(settings: WindowsProxySettings): SessionProxyConfig | undefined {
  if (settings.ProxyEnable !== 1 || !settings.ProxyServer?.trim() || settings.AutoConfigURL?.trim() || settings.AutoDetect === 1) return undefined;
  const original = settings.ProxyServer.trim();
  if (/^https?:\/\//i.test(original)) {
    try { return { ...explicitProxy(original), proxyBypassRules: settings.ProxyOverride || '' }; }
    catch { return undefined; }
  }
  if (!/(?:^|;)\s*(?:https?|ftp)\s*=\s*https?:\/\//i.test(original)) return undefined;
  const normalized: string[] = [];
  for (const item of original.split(';').map(part => part.trim()).filter(Boolean)) {
    const match = /^(https?|ftp|socks)\s*=\s*(.+)$/i.exec(item);
    if (!match) return undefined;
    const protocol = match[1].toLowerCase();
    const address = match[2];
    try {
      // Credentials, URL paths and PAC URLs are never turned into a static proxy rule.
      const url = new URL(/^[a-z0-9]+:\/\//i.test(address) ? address : `http://${address}`);
      if (!url.hostname || url.username || url.password || url.search || url.hash || (url.pathname && url.pathname !== '/')) return undefined;
      if (!['http:', 'https:', 'socks4:', 'socks5:'].includes(url.protocol)) return undefined;
      const proxyAddress = ['socks4:', 'socks5:'].includes(url.protocol) ? `${url.protocol}//${url.host}` : url.host;
      normalized.push(`${protocol}=${proxyAddress}`);
    } catch { return undefined; }
  }
  if (!normalized.length) return undefined;
  return { mode: 'fixed_servers', proxyRules: normalized.join(';'), proxyBypassRules: settings.ProxyOverride || '' };
}

export function readWindowsProxy(): Promise<WindowsProxySettings | undefined> {
  if (process.platform !== 'win32') return Promise.resolve(undefined);
  const executable = join(process.env.SystemRoot || 'C:\\Windows', 'System32', 'WindowsPowerShell', 'v1.0', 'powershell.exe');
  const command = "$ErrorActionPreference='Stop'; $v=Get-ItemProperty -LiteralPath 'HKCU:\\Software\\Microsoft\\Windows\\CurrentVersion\\Internet Settings'; $c=Get-ItemProperty -LiteralPath 'HKCU:\\Software\\Microsoft\\Windows\\CurrentVersion\\Internet Settings\\Connections' -ErrorAction SilentlyContinue; $a=$v.AutoDetect; if($c.DefaultConnectionSettings -and $c.DefaultConnectionSettings.Length -gt 8 -and ($c.DefaultConnectionSettings[8] -band 8)){ $a=1 }; [ordered]@{ ProxyEnable=$v.ProxyEnable; ProxyServer=$v.ProxyServer; ProxyOverride=$v.ProxyOverride; AutoConfigURL=$v.AutoConfigURL; AutoDetect=$a } | ConvertTo-Json -Compress";
  return new Promise(resolve => {
    execFile(executable, ['-NoLogo', '-NoProfile', '-NonInteractive', '-Command', command],
      { windowsHide: true, timeout: 10000, maxBuffer: 65536, encoding: 'utf8' }, (error, stdout) => {
        if (error) { resolve(undefined); return; }
        try { resolve(JSON.parse(stdout.replace(/^\uFEFF/, '').trim()) as WindowsProxySettings); }
        catch { resolve(undefined); }
      });
  });
}

export async function initializeProxy(
  target: ProxySession,
  override: string | undefined = process.env.LENS_PROXY_URL,
  readSettings: () => Promise<WindowsProxySettings | undefined> = readWindowsProxy,
): Promise<'override' | 'windows-normalized' | 'system'> {
  let config: SessionProxyConfig | undefined;
  let source: 'override' | 'windows-normalized' | 'system' = 'system';
  if (override?.trim()) {
    config = explicitProxy(override); source = 'override';
  } else {
    const settings = await readSettings();
    config = settings && normalizeWindowsProxy(settings);
    if (config) source = 'windows-normalized';
  }
  if (config) {
    await target.setProxy(config);
    await target.closeAllConnections();
  }
  return source;
}
