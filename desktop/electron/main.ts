import { app, BrowserWindow, clipboard, desktopCapturer, dialog, globalShortcut, ipcMain, Menu, nativeImage, net, screen, session, type IpcMainInvokeEvent } from 'electron';
import { readFile, stat } from 'node:fs/promises';
import { basename, join } from 'node:path';
import { TranslationService } from './core';
import { decodeImage, WindowsOcr } from './ocr';
import { LocalStore } from './store';
import { initializeProxy } from './proxy';
import { isTrustedRendererUrl } from './trust';

const SHORTCUT = 'CommandOrControl+Shift+T';
const rendererPath = join(__dirname, '..', 'dist', 'index.html');
const appIcon = join(app.isPackaged ? process.resourcesPath : join(__dirname, '..'), 'assets', 'lens.ico');
const devUrl = !app.isPackaged && process.env.VITE_DEV_SERVER_URL ? new URL(process.env.VITE_DEV_SERVER_URL) : undefined;
if (devUrl && !['localhost', '127.0.0.1', '[::1]'].includes(devUrl.hostname)) throw new Error('Development server must use a loopback address.');
if (!app.isPackaged && process.env.LENS_USER_DATA_DIR) app.setPath('userData', process.env.LENS_USER_DATA_DIR);
let mainWindow: BrowserWindow | null = null;
let captureWindow: BrowserWindow | null = null;
let screenshot: { dataUrl: string; width: number; height: number; scaleFactor: number } | null = null;
let startingCapture = false;
let captureSubmitting = false;
let recognizing = false;
let translating = false;
let store: LocalStore;
let ocr: WindowsOcr;
let translator: TranslationService;
let registeredShortcut = false;

function trustedUrl(url: string) {
  return isTrustedRendererUrl(url, rendererPath, devUrl?.origin);
}
function requireSender(event: IpcMainInvokeEvent, allowed: 'main' | 'capture' | 'both') {
  const window = BrowserWindow.fromWebContents(event.sender);
  if (!event.senderFrame || event.senderFrame !== event.sender.mainFrame || !trustedUrl(event.senderFrame.url)
    || (allowed === 'main' && window !== mainWindow) || (allowed === 'capture' && window !== captureWindow)
    || (window !== mainWindow && window !== captureWindow)) throw new Error('不允许的应用请求。');
}
function handle(channel: string, allowed: 'main' | 'capture' | 'both', action: (...args: any[]) => unknown) {
  ipcMain.handle(`lens:${channel}`, (event, ...args) => { requireSender(event, allowed); return action(...args); });
}
function secureWindow(window: BrowserWindow) {
  window.webContents.setWindowOpenHandler(() => ({ action: 'deny' }));
  window.webContents.on('will-navigate', (event, url) => { if (!trustedUrl(url)) event.preventDefault(); });
  window.webContents.on('will-attach-webview', event => event.preventDefault());
}
async function loadRenderer(window: BrowserWindow, capture = false) {
  if (devUrl) await window.loadURL(`${devUrl.href.replace(/\/$/, '')}${capture ? '/#/capture' : ''}`);
  else await window.loadFile(rendererPath, capture ? { hash: '/capture' } : undefined);
}
function restoreMain() {
  if (!mainWindow || mainWindow.isDestroyed()) return;
  if (mainWindow.isMinimized()) mainWindow.restore();
  mainWindow.show(); mainWindow.focus();
}
function emitResult(value: { text: string; error?: string; cancelled?: boolean }) {
  if (mainWindow && !mainWindow.isDestroyed()) mainWindow.webContents.send('lens:capture-result', value);
}
function closeCapture(cancelled = false) {
  const window = captureWindow;
  captureWindow = null; screenshot = null;
  if (window && !window.isDestroyed()) window.destroy();
  restoreMain();
  if (cancelled) emitResult({ text: '', cancelled: true });
}
async function startCapture() {
  if (startingCapture || captureWindow) throw new Error('截图选择器已打开，请完成或按 Esc 取消。');
  if (translating) throw new Error('正在翻译，请完成后再开始截图。');
  if (recognizing) throw new Error('正在识别上一张图片，请稍候。');
  startingCapture = true;
  captureSubmitting = false;
  try {
    const display = screen.getDisplayNearestPoint(screen.getCursorScreenPoint());
    mainWindow?.hide();
    // Give the compositor time to remove the app from the screenshot.
    await new Promise(resolve => setTimeout(resolve, 180));
    const sources = await desktopCapturer.getSources({ types: ['screen'], thumbnailSize: {
      width: Math.round(display.size.width * display.scaleFactor),
      height: Math.round(display.size.height * display.scaleFactor),
    } });
    const source = sources.find(item => item.display_id === String(display.id)) || (sources.length === 1 ? sources[0] : undefined);
    if (!source || source.thumbnail.isEmpty()) throw new Error('无法截取当前屏幕，请重试。');
    const size = source.thumbnail.getSize();
    screenshot = { dataUrl: source.thumbnail.toDataURL(), width: size.width, height: size.height, scaleFactor: display.scaleFactor };
    const window = new BrowserWindow({
      icon: appIcon,
      ...display.bounds, show: false, frame: false, transparent: false, backgroundColor: '#10141c',
      resizable: false, movable: false, minimizable: false, maximizable: false,
      skipTaskbar: true, alwaysOnTop: true, fullscreen: true,
      webPreferences: { preload: join(__dirname, 'preload.js'), contextIsolation: true, sandbox: true, nodeIntegration: false, webSecurity: true },
    });
    captureWindow = window;
    secureWindow(window);
    window.webContents.on('before-input-event', (event, input) => { if (input.key === 'Escape') { event.preventDefault(); closeCapture(true); } });
    window.on('closed', () => { if (captureWindow === window) { captureWindow = null; screenshot = null; restoreMain(); emitResult({ text: '', cancelled: true }); } });
    await loadRenderer(window, true);
    window.show(); window.focus();
  } catch (error) {
    closeCapture();
    throw error;
  } finally { startingCapture = false; }
}
async function recognize(input: unknown) {
  if (translating) throw new Error('正在翻译，请完成后再开始识别图片。');
  if (recognizing) throw new Error('正在识别上一张图片，请稍候。');
  recognizing = true;
  try {
    if (!input || typeof input !== 'object') throw new Error('图片识别请求格式不正确。');
    const { dataUrl, language } = input as { dataUrl: unknown; language: unknown };
    const image = nativeImage.createFromBuffer(decodeImage(dataUrl));
    if (image.isEmpty()) throw new Error('无法读取图片，请使用有效的 PNG 或 JPEG 图片。');
    const size = image.getSize();
    const scale = Math.min(1, 2560 / Math.max(size.width, size.height));
    const normalized = scale < 1 ? image.resize({ width: Math.max(1, Math.round(size.width * scale)), height: Math.max(1, Math.round(size.height * scale)), quality: 'best' }) : image;
    return await ocr.recognize({ dataUrl: normalized.toDataURL(), language });
  } finally { recognizing = false; }
}
function installHandlers() {
  handle('get-status', 'main', async () => ({
    platform: process.platform, version: app.getVersion(), hasApiKey: Boolean(process.env.DEEPSEEK_API_KEY?.trim()),
    ocrLanguages: await ocr.languages().catch(() => []), shortcut: registeredShortcut ? 'Ctrl+Shift+T' : '快捷键被占用，请使用截图按钮',
  }));
  handle('get-settings', 'both', () => store.getSettings());
  handle('save-settings', 'main', value => store.saveSettings(value));
  handle('translate', 'main', async request => {
    if (translating) throw new Error('正在翻译，请稍候。');
    if (startingCapture || captureWindow || recognizing) throw new Error('请完成截图或文字识别后再开始翻译。');
    translating = true;
    try {
      const result = await translator.translate(request, store.getSettings().model);
      store.addHistory(result);
      return result;
    } finally { translating = false; }
  });
  handle('get-history', 'main', () => store.getHistory());
  handle('clear-history', 'main', () => { store.clearHistory(); translator.clearCache(); });
  handle('delete-history', 'main', id => store.deleteHistory(id));
  handle('read-clipboard', 'main', () => clipboard.readText());
  handle('write-clipboard', 'main', value => {
    if (typeof value !== 'string' || value.length > 100000) throw new Error('复制内容格式不正确或过长。');
    clipboard.writeText(value);
  });
  handle('open-image', 'main', async () => {
    const result = await dialog.showOpenDialog(mainWindow!, { title: '选择需要翻译的图片', properties: ['openFile'], filters: [{ name: '图片', extensions: ['png', 'jpg', 'jpeg', 'webp', 'bmp'] }] });
    if (result.canceled || !result.filePaths[0]) return null;
    const filename = result.filePaths[0];
    if ((await stat(filename)).size > 15 * 1024 * 1024) throw new Error('图片大小不能超过 15 MB。');
    const image = nativeImage.createFromBuffer(await readFile(filename));
    if (image.isEmpty()) throw new Error('无法读取这张图片，请使用 PNG 或 JPEG 格式。');
    const size = image.getSize();
    const factor = Math.min(1, 2560 / Math.max(size.width, size.height));
    const normalized = factor < 1 ? image.resize({ width: Math.max(1, Math.round(size.width * factor)), height: Math.max(1, Math.round(size.height * factor)), quality: 'best' }) : image;
    return { dataUrl: normalized.toDataURL(), name: basename(filename) };
  });
  handle('recognize-image', 'main', recognize);
  handle('start-capture', 'main', startCapture);
  handle('capture-ready', 'capture', () => screenshot);
  handle('cancel-capture', 'capture', () => { setImmediate(() => closeCapture(true)); });
  handle('finish-capture', 'capture', request => {
    if (captureSubmitting) throw new Error('这张截图正在处理，请稍候。');
    captureSubmitting = true;
    // Reply before destroying the invoking renderer; complete recognition in the main process.
    setImmediate(() => {
      closeCapture();
      void recognize(request).then(result => emitResult({ text: result.text })).catch(error => emitResult({ text: '', error: error instanceof Error ? error.message : '文字识别失败，请重试。' }));
    });
  });
}
function installMenu() {
  Menu.setApplicationMenu(Menu.buildFromTemplate([
    { label: '译镜', submenu: [
      { label: '截图翻译', click: () => { void startCapture().catch(error => emitResult({ text: '', error: error.message })); } },
      { label: '翻译历史', click: () => { restoreMain(); mainWindow?.webContents.send('lens:navigate', 'history'); } },
      { label: '设置', click: () => { restoreMain(); mainWindow?.webContents.send('lens:navigate', 'settings'); } },
      { type: 'separator' }, { role: 'quit', label: '退出' },
    ] },
    { label: '编辑', submenu: [{ role: 'undo', label: '撤销' }, { role: 'redo', label: '重做' }, { type: 'separator' }, { role: 'cut', label: '剪切' }, { role: 'copy', label: '复制' }, { role: 'paste', label: '粘贴' }, { role: 'selectAll', label: '全选' }] },
    { label: '视图', submenu: [{ role: 'resetZoom', label: '实际大小' }, { role: 'zoomIn', label: '放大' }, { role: 'zoomOut', label: '缩小' }, { role: 'togglefullscreen', label: '全屏' }] },
  ]));
}
async function createMainWindow() {
  const window = new BrowserWindow({
    icon: appIcon,
    width: 1250, height: 840, minWidth: 920, minHeight: 640, show: false,
    title: '译镜 Lens Translate', backgroundColor: '#f7f8fc', autoHideMenuBar: true,
    webPreferences: { preload: join(__dirname, 'preload.js'), contextIsolation: true, sandbox: true, nodeIntegration: false, webSecurity: true },
  });
  mainWindow = window;
  secureWindow(window);
  window.on('closed', () => { mainWindow = null; if (captureWindow && !captureWindow.isDestroyed()) captureWindow.destroy(); });
  await loadRenderer(window);
  window.show();
}

const singleInstance = app.requestSingleInstanceLock();
if (!singleInstance) app.quit();
else {
  app.on('second-instance', restoreMain);
  void app.whenReady().then(async () => {
    store = new LocalStore(app.getPath('userData'));
    ocr = new WindowsOcr(join(app.isPackaged ? process.resourcesPath : join(__dirname, '..'), 'native', 'windows-ocr.ps1'));
    await initializeProxy(session.defaultSession);
    translator = new TranslationService(() => process.env.DEEPSEEK_API_KEY, net.fetch as typeof fetch);
    session.defaultSession.setPermissionRequestHandler((_webContents, _permission, callback) => callback(false));
    session.defaultSession.setPermissionCheckHandler(() => false);
    session.defaultSession.webRequest.onHeadersReceived((details, callback) => {
      const connect = devUrl ? `'self' ${devUrl.origin} ${devUrl.origin.replace(/^http/, 'ws')}` : "'none'";
      callback({ responseHeaders: { ...details.responseHeaders, 'Content-Security-Policy': [`default-src 'self'; script-src 'self'${devUrl ? " 'unsafe-inline'" : ''}; style-src 'self' 'unsafe-inline'; img-src 'self' data: blob:; font-src 'self'; connect-src ${connect}; object-src 'none'; base-uri 'none'; frame-src 'none'`] } });
    });
    installHandlers(); installMenu();
    registeredShortcut = globalShortcut.register(SHORTCUT, () => { void startCapture().catch(error => { restoreMain(); emitResult({ text: '', error: error.message }); }); });
    await createMainWindow();
  }).catch(error => { dialog.showErrorBox('译镜启动失败', error instanceof Error ? error.message : '应用启动失败。'); app.quit(); });
}
app.on('window-all-closed', () => app.quit());
app.on('will-quit', () => globalShortcut.unregisterAll());
