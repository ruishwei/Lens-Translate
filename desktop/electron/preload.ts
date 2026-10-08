import { contextBridge, ipcRenderer } from 'electron';

const invoke = (name: string, ...args: unknown[]) => ipcRenderer.invoke(`lens:${name}`, ...args);
contextBridge.exposeInMainWorld('lens', {
  getStatus: () => invoke('get-status'),
  getSettings: () => invoke('get-settings'),
  saveSettings: (settings: unknown) => invoke('save-settings', settings),
  translate: (request: unknown) => invoke('translate', request),
  getHistory: () => invoke('get-history'),
  clearHistory: () => invoke('clear-history'),
  deleteHistory: (id: string) => invoke('delete-history', id),
  readClipboard: () => invoke('read-clipboard'),
  writeClipboard: (text: string) => invoke('write-clipboard', text),
  openImage: () => invoke('open-image'),
  recognizeImage: (request: unknown) => invoke('recognize-image', request),
  startCapture: () => invoke('start-capture'),
  captureReady: () => invoke('capture-ready'),
  finishCapture: (request: unknown) => invoke('finish-capture', request),
  cancelCapture: () => invoke('cancel-capture'),
  onCaptureResult: (callback: (value: unknown) => void) => {
    const listener = (_event: Electron.IpcRendererEvent, value: unknown) => callback(value);
    ipcRenderer.on('lens:capture-result', listener);
    return () => ipcRenderer.removeListener('lens:capture-result', listener);
  },
  onNavigate: (callback: (value: unknown) => void) => {
    const listener = (_event: Electron.IpcRendererEvent, value: unknown) => callback(value);
    ipcRenderer.on('lens:navigate', listener);
    return () => ipcRenderer.removeListener('lens:navigate', listener);
  },
});
