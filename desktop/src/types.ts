export type Page = "translate" | "screen" | "image" | "history" | "settings";
export type Style = "natural" | "literal" | "professional";
export interface Settings {
  sourceLanguage: string;
  targetLanguage: string;
  style: Style;
  model: string;
  saveHistory: boolean;
}
export interface AppStatus {
  platform: string;
  version: string;
  hasApiKey: boolean;
  ocrLanguages: string[];
  shortcut: string;
}
export interface Translation {
  id: string;
  sourceText: string;
  translatedText: string;
  sourceLanguage: string;
  targetLanguage: string;
  createdAt: string;
  model: string;
  durationMs: number;
}
export interface LensApi {
  getStatus(): Promise<AppStatus>;
  getSettings(): Promise<Settings>;
  saveSettings(settings: Partial<Settings>): Promise<Settings>;
  translate(request: {
    text: string;
    sourceLanguage: string;
    targetLanguage: string;
    style: Style;
  }): Promise<Translation>;
  getHistory(): Promise<Translation[]>;
  clearHistory(): Promise<void>;
  deleteHistory(id: string): Promise<void>;
  readClipboard(): Promise<string>;
  writeClipboard(text: string): Promise<void>;
  openImage(): Promise<{ dataUrl: string; name: string } | null>;
  recognizeImage(request: {
    dataUrl: string;
    language: string;
  }): Promise<{ text: string; language: string }>;
  startCapture(): Promise<void>;
  captureReady(): Promise<{
    dataUrl: string;
    width: number;
    height: number;
    scaleFactor: number;
  } | null>;
  finishCapture(request: { dataUrl: string; language: string }): Promise<void>;
  cancelCapture(): Promise<void>;
  onCaptureResult(
    callback: (result: {
      text: string;
      error?: string;
      cancelled?: boolean;
    }) => void,
  ): () => void;
  onNavigate(
    callback: (page: "settings" | "history" | "translate") => void,
  ): () => void;
}
declare global {
  interface Window {
    lens?: LensApi;
  }
}
