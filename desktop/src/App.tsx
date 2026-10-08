import { useCallback, useEffect, useRef, useState } from "react";
import {
  ArrowLeftRight,
  ArrowRight,
  Check,
  ChevronDown,
  ChevronRight,
  Clipboard,
  Clock3,
  Copy,
  FileImage,
  Globe2,
  History,
  ImagePlus,
  Keyboard,
  Languages,
  LoaderCircle,
  Monitor,
  ScanLine,
  Search,
  Settings2,
  ShieldCheck,
  Sparkles,
  Trash2,
  X,
  Zap,
} from "lucide-react";
import type { AppStatus, Page, Settings, Style, Translation } from "./types";
import { formatDate, languageName, languages } from "./languages";

const defaults: Settings = {
  sourceLanguage: "auto",
  targetLanguage: "zh-CN",
  style: "natural",
  model: "deepseek-flash",
  saveHistory: true,
};
const navItems = [
  { id: "translate", icon: Languages, text: "文本翻译" },
  { id: "screen", icon: ScanLine, text: "屏幕翻译" },
  { id: "image", icon: FileImage, text: "图片翻译" },
  { id: "history", icon: History, text: "翻译历史" },
] as const;
const styles: [Style, string, string][] = [
  ["natural", "自然流畅", "保留原意，让表达更贴近母语习惯"],
  ["literal", "忠于原文", "尽量保留原文结构、措辞与语气"],
  ["professional", "专业准确", "适合文档、技术内容与商务沟通"],
];
const errorMessage = (error: unknown) =>
  error instanceof Error
    ? error.message.replace(
        /^Error invoking remote method '[^']+': (Error: )?/,
        "",
      )
    : "操作失败，请稍后重试";

export default function App() {
  const [page, setPage] = useState<Page>("translate");
  const [settings, setSettings] = useState<Settings>(defaults);
  const [status, setStatus] = useState<AppStatus>();
  const [source, setSource] = useState("");
  const [result, setResult] = useState<Translation>();
  const [history, setHistory] = useState<Translation[]>([]);
  const [busy, setBusy] = useState<"translation" | "ocr" | "capture" | null>(
    null,
  );
  const [notice, setNotice] = useState<{ text: string; error?: boolean }>();
  const [filter, setFilter] = useState("");
  const [image, setImage] = useState<{ dataUrl: string; name: string }>();
  const [dragging, setDragging] = useState(false);
  const [confirmClear, setConfirmClear] = useState(false);
  const inputRef = useRef<HTMLTextAreaElement>(null);
  const busyRef = useRef(false);
  const activityRef = useRef(busy);
  activityRef.current = busy;
  const mounted = useRef(true);

  const notify = useCallback(
    (text: string, error = false) => setNotice({ text, error }),
    [],
  );
  const refreshHistory = useCallback(async () => {
    if (window.lens) setHistory(await window.lens.getHistory());
  }, []);
  useEffect(() => {
    mounted.current = true;
    const api = window.lens;
    if (!api) return;
    Promise.all([api.getSettings(), api.getStatus(), api.getHistory()])
      .then(([s, state, h]) => {
        if (mounted.current) {
          setSettings(s);
          setStatus(state);
          setHistory(h);
        }
      })
      .catch((e) => notify(errorMessage(e), true));
    const off = api.onCaptureResult(({ text, error, cancelled }) => {
      if (activityRef.current === "translation" && (error || cancelled)) {
        if (error) notify(error, true);
        return;
      }
      busyRef.current = false;
      setBusy(null);
      if (cancelled) return;
      if (error) {
        notify(error, true);
        return;
      }
      if (!text?.trim()) {
        notify("没有识别到文字，请框选更清晰的区域。", true);
        return;
      }
      setSource(text);
      setResult(undefined);
      setPage("translate");
      notify("屏幕文字已识别，确认内容后点击翻译");
    });
    const offNav = api.onNavigate(setPage);
    return () => {
      mounted.current = false;
      off();
      offNav();
    };
  }, [notify]);
  useEffect(() => {
    if (!notice) return;
    const timer = setTimeout(
      () => setNotice(undefined),
      notice.error ? 9000 : 3500,
    );
    return () => clearTimeout(timer);
  }, [notice]);

  const save = async (partial: Partial<Settings>) => {
    if (busyRef.current) return;
    if (!window.lens) return notify("请在 Windows 桌面应用中使用此功能", true);
    try {
      setSettings(await window.lens.saveSettings(partial));
    } catch (e) {
      notify(errorMessage(e), true);
    }
  };
  const translate = useCallback(async () => {
    if (!source.trim() || busyRef.current) return;
    if (!window.lens)
      return notify("请启动 Windows 桌面应用以连接翻译引擎", true);
    busyRef.current = true;
    setBusy("translation");
    setResult(undefined);
    try {
      const response = await window.lens.translate({
        text: source,
        sourceLanguage: settings.sourceLanguage,
        targetLanguage: settings.targetLanguage,
        style: settings.style,
      });
      setResult(response);
      await refreshHistory();
    } catch (e) {
      notify(errorMessage(e), true);
    } finally {
      busyRef.current = false;
      setBusy(null);
    }
  }, [source, settings, notify, refreshHistory]);
  useEffect(() => {
    const handle = (e: KeyboardEvent) => {
      if ((e.ctrlKey || e.metaKey) && e.key === "Enter") {
        e.preventDefault();
        void translate();
      }
    };
    window.addEventListener("keydown", handle);
    return () => window.removeEventListener("keydown", handle);
  }, [translate]);

  const capture = async () => {
    if (busyRef.current) return;
    if (!window.lens)
      return notify("屏幕框选需要在 Windows 桌面应用中运行", true);
    busyRef.current = true;
    setBusy("capture");
    try {
      await window.lens.startCapture();
    } catch (e) {
      busyRef.current = false;
      setBusy(null);
      notify(errorMessage(e), true);
    }
  };
  const recognize = async (file: { dataUrl: string; name: string }) => {
    if (busyRef.current) return;
    if (!window.lens)
      return notify("图片识别需要在 Windows 桌面应用中运行", true);
    setImage(file);
    setPage("image");
    setResult(undefined);
    busyRef.current = true;
    setBusy("ocr");
    try {
      const data = await window.lens.recognizeImage({
        dataUrl: file.dataUrl,
        language: settings.sourceLanguage,
      });
      setSource(data.text);
      notify(
        data.text.trim()
          ? "文字识别完成，可编辑后翻译"
          : "未识别到文字，请尝试更清晰的图片",
        !data.text.trim(),
      );
    } catch (e) {
      notify(errorMessage(e), true);
    } finally {
      busyRef.current = false;
      setBusy(null);
    }
  };
  const openImage = async () => {
    if (!window.lens) return notify("请在 Windows 桌面应用中打开图片", true);
    try {
      const file = await window.lens.openImage();
      if (file) await recognize(file);
    } catch (e) {
      notify(errorMessage(e), true);
    }
  };
  const paste = async () => {
    try {
      const text = window.lens
        ? await window.lens.readClipboard()
        : await navigator.clipboard.readText();
      if (!text.trim()) return notify("剪贴板中没有文字");
      if (text.length > 12000)
        return notify("文字超过 12,000 字，请分段粘贴", true);
      setSource(text);
      setResult(undefined);
      setPage("translate");
      inputRef.current?.focus();
    } catch (e) {
      notify(errorMessage(e), true);
    }
  };
  const copy = async (text: string) => {
    try {
      if (window.lens) await window.lens.writeClipboard(text);
      else await navigator.clipboard.writeText(text);
      notify("已复制到剪贴板");
    } catch {
      notify("复制失败，请手动选择文字复制", true);
    }
  };
  const useHistory = (item: Translation) => {
    if (busyRef.current) return notify("请等待当前任务完成");
    setSource(item.sourceText);
    setResult(item);
    void save({
      sourceLanguage: item.sourceLanguage,
      targetLanguage: item.targetLanguage,
    });
    setPage("translate");
  };
  const deleteItem = async (id: string) => {
    try {
      await window.lens?.deleteHistory(id);
      await refreshHistory();
    } catch (e) {
      notify(errorMessage(e), true);
    }
  };
  const clear = async () => {
    try {
      await window.lens?.clearHistory();
      await refreshHistory();
      setConfirmClear(false);
      notify("历史记录已清空");
    } catch (e) {
      notify(errorMessage(e), true);
    }
  };
  const filteredHistory = history.filter((item) =>
    `${item.sourceText} ${item.translatedText}`
      .toLowerCase()
      .includes(filter.toLowerCase()),
  );
  const swap = async () => {
    if (settings.sourceLanguage === "auto" || busy) return;
    const original = source;
    await save({
      sourceLanguage: settings.targetLanguage,
      targetLanguage: settings.sourceLanguage,
    });
    if (result) {
      setSource(result.translatedText);
      setResult(undefined);
    } else setSource(original);
  };
  const dropFile = (file?: File) => {
    if (!file) return;
    if (
      !["image/png", "image/jpeg", "image/webp", "image/bmp"].includes(
        file.type,
      )
    )
      return notify("请选择 PNG、JPG、WebP 或 BMP 图片", true);
    if (file.size > 15 * 1024 * 1024) return notify("图片不能超过 15 MB", true);
    const reader = new FileReader();
    reader.onload = () =>
      void recognize({ dataUrl: String(reader.result), name: file.name });
    reader.onerror = () => notify("无法读取图片", true);
    reader.readAsDataURL(file);
  };

  return (
    <div className="app-shell">
      <aside className="sidebar">
        <a
          className="brand"
          href="#"
          onClick={(e) => {
            e.preventDefault();
            setPage("translate");
          }}
          aria-label="译镜首页"
        >
          <span className="brand-mark">
            <ScanLine size={25} />
            <span>译</span>
          </span>
          <span>
            <strong>译镜</strong>
            <small>LENS TRANSLATE</small>
          </span>
        </a>
        <div className="nav-label">工作空间</div>
        <nav>
          {navItems.map(({ id, icon: Icon, text }) => (
            <button
              key={id}
              className={`nav-item ${page === id ? "active" : ""}`}
              onClick={() => setPage(id)}
            >
              <Icon size={19} />
              <span>{text}</span>
              {page === id && <span className="active-dot" />}
            </button>
          ))}
        </nav>
        <div className="sidebar-bottom">
          <div className="shortcut-note">
            <span className="mini-icon">
              <Zap size={16} />
            </span>
            <strong>好奇心，不必暂停</strong>
            <p>
              看到不懂的内容？
              <br />
              按下快捷键，框选即可识别。
            </p>
            <div className="keys">
              <kbd>Ctrl</kbd>
              <span>+</span>
              <kbd>Shift</kbd>
              <span>+</span>
              <kbd>T</kbd>
            </div>
          </div>
          <button
            className={`nav-item ${page === "settings" ? "active" : ""}`}
            onClick={() => setPage("settings")}
          >
            <Settings2 size={19} />
            <span>偏好设置</span>
          </button>
          <div className="sidebar-footer">
            <span className="status-dot" /> Windows 桌面版{" "}
            <span>v{status?.version ?? "0.1.0"}</span>
          </div>
        </div>
      </aside>
      <div className="main-shell">
        <header className="topbar">
          <div className="breadcrumb">
            工作空间 <ChevronRight size={14} />
            <span>
              {navItems.find((n) => n.id === page)?.text ?? "偏好设置"}
            </span>
          </div>
          <div className="topbar-right">
            <span className="privacy-pill">
              <ShieldCheck size={14} /> 截图在本机识别
            </span>
            <span className="avatar">译</span>
          </div>
        </header>
        <main>
          {page !== "settings" && page !== "history" && (
            <>
              <section className="hero">
                <div>
                  <div className="eyebrow">
                    <span /> A LITTLE CLOSER TO THE WORLD
                  </div>
                  <h1>
                    {page === "screen"
                      ? "看见，即可读懂。"
                      : page === "image"
                        ? "让图片里的文字开口。"
                        : "跨过语言，继续探索。"}
                  </h1>
                  <p>
                    {page === "screen"
                      ? "网页、软件、游戏对白，框选屏幕上的一段文字即可开始。"
                      : page === "image"
                        ? "导入截图或图片，在本地提取文字，再交给 AI 准确翻译。"
                        : "一句话、一张图、一整片屏幕。让理解，发生得更自然。"}
                  </p>
                </div>
                <div className="hero-symbol">
                  <Globe2 size={65} strokeWidth={0.8} />
                  <span className="symbol-one">文</span>
                  <span className="symbol-two">A</span>
                </div>
              </section>
              <section className="quick-actions">
                <button
                  className="capture-card"
                  onClick={capture}
                  disabled={!!busy}
                >
                  <div className="capture-card-icon">
                    <ScanLine size={24} />
                  </div>
                  <div>
                    <strong>框选屏幕翻译</strong>
                    <span>不用切换，让文字触手可及</span>
                  </div>
                  <span className="capture-hotkey">Ctrl + Shift + T</span>
                  <ArrowRight size={20} />
                  <div className="capture-decoration" />
                </button>
                <button
                  className="quick-card"
                  onClick={openImage}
                  disabled={!!busy}
                >
                  <span className="quick-icon lavender">
                    <ImagePlus size={22} />
                  </span>
                  <div>
                    <strong>导入图片</strong>
                    <span>识别图片里的文字</span>
                  </div>
                  <ChevronRight size={17} />
                </button>
                <button
                  className="quick-card"
                  onClick={paste}
                  disabled={!!busy}
                >
                  <span className="quick-icon peach">
                    <Clipboard size={22} />
                  </span>
                  <div>
                    <strong>粘贴文本</strong>
                    <span>从剪贴板开始</span>
                  </div>
                  <ChevronRight size={17} />
                </button>
              </section>
              {page === "screen" && (
                <section className="screen-guide">
                  <div className="guide-icon">
                    <Monitor size={26} />
                  </div>
                  <div>
                    <strong>把整个桌面，变成你的阅读空间</strong>
                    <p>
                      点击「框选屏幕翻译」后，拖动选择文字区域。松开鼠标完成识别，按
                      Esc 取消。识别结果可在下方编辑并翻译。
                    </p>
                  </div>
                  <span className="small-tag">系统 OCR</span>
                </section>
              )}
              {page === "image" && (
                <section
                  className={`image-drop ${dragging ? "dragging" : ""} ${image ? "has-image" : ""}`}
                  onDragOver={(e) => {
                    e.preventDefault();
                    setDragging(true);
                  }}
                  onDragLeave={() => setDragging(false)}
                  onDrop={(e) => {
                    e.preventDefault();
                    setDragging(false);
                    dropFile(e.dataTransfer.files[0]);
                  }}
                >
                  {image ? (
                    <>
                      <img src={image.dataUrl} alt="待识别图片" />
                      <div>
                        <strong>{image.name}</strong>
                        <p>
                          {busy === "ocr"
                            ? "正在本地识别文字…"
                            : "图片保留在本机，识别结果显示在下方"}
                        </p>
                        <button
                          className="text-button"
                          onClick={openImage}
                          disabled={!!busy}
                        >
                          更换图片 <ArrowRight size={14} />
                        </button>
                      </div>
                      <button
                        className="icon-button image-remove"
                        aria-label="移除图片"
                        onClick={() => setImage(undefined)}
                      >
                        <X size={17} />
                      </button>
                    </>
                  ) : (
                    <button onClick={openImage} disabled={!!busy}>
                      <ImagePlus size={31} />
                      <strong>拖入图片，或点击选择</strong>
                      <span>PNG、JPG、WebP、BMP · 最大 15 MB</span>
                    </button>
                  )}
                </section>
              )}
              <section className="translator" aria-label="翻译工作台">
                <div className="language-bar">
                  <div className="language-control">
                    <span className="field-caption">原文</span>
                    <label className="select-wrap">
                      <Globe2 size={16} />
                      <select
                        aria-label="原文语言"
                        value={settings.sourceLanguage}
                        disabled={!!busy}
                        onChange={(e) => {
                          setResult(undefined);
                          void save({ sourceLanguage: e.target.value });
                        }}
                      >
                        {languages.map(([code, name]) => (
                          <option key={code} value={code}>
                            {name}
                          </option>
                        ))}
                      </select>
                      <ChevronDown size={14} />
                    </label>
                  </div>
                  <button
                    className="swap-button"
                    onClick={swap}
                    disabled={settings.sourceLanguage === "auto" || !!busy}
                    title={
                      settings.sourceLanguage === "auto"
                        ? "指定原文语言后可交换"
                        : "交换语言"
                    }
                    aria-label="交换语言"
                  >
                    <ArrowLeftRight size={17} />
                  </button>
                  <div className="language-control">
                    <span className="field-caption">译文</span>
                    <label className="select-wrap">
                      <select
                        aria-label="译文语言"
                        value={settings.targetLanguage}
                        disabled={!!busy}
                        onChange={(e) => {
                          setResult(undefined);
                          void save({ targetLanguage: e.target.value });
                        }}
                      >
                        {languages
                          .filter(([c]) => c !== "auto")
                          .map(([code, name]) => (
                            <option key={code} value={code}>
                              {name}
                            </option>
                          ))}
                      </select>
                      <ChevronDown size={14} />
                    </label>
                    <span className="ai-tag">
                      <Sparkles size={12} /> DeepSeek
                    </span>
                  </div>
                </div>
                <div className="editor-grid">
                  <div className="source-editor">
                    <textarea
                      ref={inputRef}
                      aria-label="待翻译文本"
                      placeholder={
                        "在这里输入，世界会更近一点。\n\n也可以粘贴文字，或通过屏幕、图片识别内容。"
                      }
                      value={source}
                      maxLength={12000}
                      disabled={!!busy}
                      onChange={(e) => {
                        setSource(e.target.value);
                        setResult(undefined);
                      }}
                      spellCheck={false}
                    />
                    <div className="editor-foot">
                      <button
                        className="icon-button"
                        onClick={paste}
                        disabled={!!busy}
                        aria-label="粘贴文本"
                        title="粘贴文本"
                      >
                        <Clipboard size={16} />
                      </button>
                      {source && (
                        <button
                          className="icon-button"
                          disabled={!!busy}
                          onClick={() => {
                            setSource("");
                            setResult(undefined);
                            inputRef.current?.focus();
                          }}
                          aria-label="清空原文"
                          title="清空原文"
                        >
                          <Trash2 size={16} />
                        </button>
                      )}
                      <span className="character-count">
                        {source.length.toLocaleString()} / 12,000
                      </span>
                    </div>
                  </div>
                  <div
                    className={`result-editor ${result ? "has-result" : ""}`}
                    aria-live="polite"
                  >
                    {busy ? (
                      <div className="translation-empty">
                        <div className="result-symbol loading">
                          <LoaderCircle size={28} />
                        </div>
                        <strong>
                          {busy === "translation"
                            ? "正在寻找恰当的表达…"
                            : busy === "ocr"
                              ? "正在识别图片文字…"
                              : "准备屏幕框选…"}
                        </strong>
                        <p>
                          {busy === "translation"
                            ? "DeepSeek 正在翻译这段内容"
                            : "文字识别在你的设备上完成"}
                        </p>
                      </div>
                    ) : result ? (
                      <>
                        <div className="translated-text">
                          {result.translatedText}
                        </div>
                        <div className="result-foot">
                          <span>
                            <Check size={13} /> 翻译完成 ·{" "}
                            {(result.durationMs / 1000).toFixed(1)} 秒
                          </span>
                          <button
                            className="icon-button"
                            aria-label="复制译文"
                            title="复制译文"
                            onClick={() => copy(result.translatedText)}
                          >
                            <Copy size={16} />
                          </button>
                        </div>
                      </>
                    ) : (
                      <div className="translation-empty">
                        <div className="result-symbol">
                          <Languages size={28} strokeWidth={1.5} />
                          <span className="tiny-spark">
                            <Sparkles size={12} />
                          </span>
                        </div>
                        <strong>每一种语言，都值得被理解</strong>
                        <p>译文将在这里呈现</p>
                      </div>
                    )}
                  </div>
                </div>
                <div className="translate-toolbar">
                  <div className="style-picker">
                    <Sparkles size={15} />
                    <select
                      aria-label="翻译风格"
                      value={settings.style}
                      disabled={!!busy}
                      onChange={(e) => {
                        setResult(undefined);
                        void save({ style: e.target.value as Style });
                      }}
                    >
                      {styles.map(([value, name]) => (
                        <option key={value} value={value}>
                          {name}
                        </option>
                      ))}
                    </select>
                    <ChevronDown size={13} />
                  </div>
                  <div className="translate-controls">
                    <span className="keyboard-hint">
                      <Keyboard size={14} /> Ctrl + Enter
                    </span>
                    <button
                      className="primary-button"
                      disabled={!source.trim() || !!busy}
                      onClick={translate}
                    >
                      {busy === "translation" ? (
                        <LoaderCircle className="spin" size={16} />
                      ) : (
                        <Sparkles size={16} />
                      )}{" "}
                      开始翻译 <ArrowRight size={15} />
                    </button>
                  </div>
                </div>
              </section>
              <div className="workspace-note">
                <ShieldCheck size={14} />
                <span>本地 OCR 识别 · 仅将文字发送至 DeepSeek</span>
                <span className="engine-state">
                  <span
                    className={`status-dot ${!status?.hasApiKey ? "muted" : ""}`}
                  />
                  {status?.hasApiKey ? "翻译引擎已就绪" : "尚未连接翻译引擎"}
                  {!status?.hasApiKey && (
                    <button onClick={() => setPage("settings")}>
                      配置 <ArrowRight size={12} />
                    </button>
                  )}
                </span>
              </div>
              <section className="recent-section">
                <div className="section-heading">
                  <h2>
                    <Clock3 size={17} /> 最近的理解
                  </h2>
                  <button
                    className="text-button"
                    onClick={() => setPage("history")}
                  >
                    全部历史 <ArrowRight size={14} />
                  </button>
                </div>
                {history.length ? (
                  <div className="recent-grid">
                    {history.slice(0, 3).map((item) => (
                      <button
                        key={item.id}
                        className="recent-card"
                        onClick={() => useHistory(item)}
                      >
                        <span className="recent-language">
                          {languageName(item.sourceLanguage)}{" "}
                          <ArrowRight size={11} />{" "}
                          {languageName(item.targetLanguage)}
                          <time>{formatDate(item.createdAt)}</time>
                        </span>
                        <strong>{item.translatedText}</strong>
                        <p>{item.sourceText}</p>
                      </button>
                    ))}
                  </div>
                ) : (
                  <div className="recent-empty">
                    <span className="empty-line" />
                    <p>
                      每一次理解，都从一句话开始。你的翻译记录会保留在这里。
                    </p>
                    <span className="empty-line" />
                  </div>
                )}
              </section>
            </>
          )}
          {page === "history" && (
            <>
              <section className="page-heading">
                <div className="eyebrow">YOUR WORDS, WITH YOU</div>
                <h1>每一次理解，都有迹可循。</h1>
                <p>回到那些曾让你停留的文字。历史记录仅保存在此设备。</p>
              </section>
              <div className="history-toolbar">
                <label className="search-box">
                  <Search size={17} />
                  <input
                    aria-label="搜索翻译历史"
                    placeholder="搜索原文或译文…"
                    value={filter}
                    onChange={(e) => setFilter(e.target.value)}
                  />
                  {filter && (
                    <button
                      className="icon-button"
                      onClick={() => setFilter("")}
                      aria-label="清空搜索"
                    >
                      <X size={15} />
                    </button>
                  )}
                </label>
                <span>{filteredHistory.length} 条记录</span>
                <button
                  className="secondary-button danger"
                  disabled={!history.length}
                  onClick={() => setConfirmClear(true)}
                >
                  <Trash2 size={15} /> 清空历史
                </button>
              </div>
              <div className="history-list">
                {filteredHistory.map((item) => (
                  <article key={item.id} className="history-card">
                    <div className="history-meta">
                      <span>
                        {languageName(item.sourceLanguage)}{" "}
                        <ArrowRight size={12} />{" "}
                        {languageName(item.targetLanguage)}
                      </span>
                      <time>{formatDate(item.createdAt)}</time>
                      <button
                        className="icon-button"
                        onClick={() => copy(item.translatedText)}
                        aria-label="复制历史译文"
                      >
                        <Copy size={16} />
                      </button>
                      <button
                        className="icon-button"
                        onClick={() => deleteItem(item.id)}
                        aria-label="删除记录"
                      >
                        <Trash2 size={16} />
                      </button>
                    </div>
                    <button
                      className="history-content"
                      onClick={() => useHistory(item)}
                    >
                      <p>{item.sourceText}</p>
                      <strong>{item.translatedText}</strong>
                    </button>
                  </article>
                ))}
                {!filteredHistory.length && (
                  <div className="large-empty">
                    <History size={38} strokeWidth={1.2} />
                    <h3>
                      {filter
                        ? "暂时没有匹配的记录"
                        : "你的翻译旅程，从这里开始"}
                    </h3>
                    <p>
                      {filter
                        ? "试试其他关键词，或清空搜索条件。"
                        : "完成一次翻译后，就可以在这里重新查看。"}
                    </p>
                    <button
                      className="secondary-button"
                      onClick={() =>
                        filter ? setFilter("") : setPage("translate")
                      }
                    >
                      {filter ? "清空搜索" : "开始翻译"}
                      <ArrowRight size={15} />
                    </button>
                  </div>
                )}
              </div>
            </>
          )}
          {page === "settings" && (
            <>
              <section className="page-heading">
                <div className="eyebrow">MAKE IT YOURS</div>
                <h1>按你的习惯，理解世界。</h1>
                <p>设置翻译引擎、表达风格与本地数据偏好。</p>
              </section>
              <div className="settings-grid">
                <section className="settings-card">
                  <div className="settings-title">
                    <Sparkles size={20} />
                    <div>
                      <h2>翻译引擎</h2>
                      <p>由 DeepSeek 提供上下文翻译</p>
                    </div>
                    <span
                      className={`connection-badge ${status?.hasApiKey ? "connected" : ""}`}
                    >
                      {status?.hasApiKey ? "密钥已配置" : "等待配置"}
                    </span>
                  </div>
                  <div className="setting-row">
                    <div>
                      <strong>API 密钥</strong>
                      <p>从系统环境变量安全读取</p>
                    </div>
                    <code>DEEPSEEK_API_KEY</code>
                  </div>
                  <div className="setup-help">
                    在 Windows 用户环境变量中添加 <code>DEEPSEEK_API_KEY</code>
                    ，然后重新启动应用。如果刚修改变量，请从新终端启动。密钥不会进入页面或保存到应用设置。
                  </div>
                  <div className="setting-row">
                    <div>
                      <strong>模型</strong>
                      <p>快速、准确地处理日常翻译</p>
                    </div>
                    <select className="model-label" aria-label="翻译模型" value={settings.model} disabled={!!busy} onChange={e => save({model:e.target.value})}>
                      <option value="deepseek-flash">DeepSeek Flash</option>
                      <option value="deepseek-v4-pro">DeepSeek V4 Pro</option>
                      {!["deepseek-flash","deepseek-v4-pro"].includes(settings.model) && <option value={settings.model}>{settings.model}</option>}
                    </select>
                  </div>
                  <button
                    className="secondary-button"
                    onClick={async () => {
                      try {
                        if (window.lens) {
                          setStatus(await window.lens.getStatus());
                          notify("已刷新当前进程的引擎状态");
                        }
                      } catch (e) {
                        notify(errorMessage(e), true);
                      }
                    }}
                  >
                    <Zap size={15} /> 刷新状态
                  </button>
                </section>
                <section className="settings-card">
                  <div className="settings-title">
                    <ShieldCheck size={20} />
                    <div>
                      <h2>隐私与本地识别</h2>
                      <p>保留控制，也保留便利</p>
                    </div>
                  </div>
                  <div className="setting-row">
                    <div>
                      <strong>保存翻译历史</strong>
                      <p>仅存储在本机，最多保留 200 条</p>
                    </div>
                    <button
                      className={`toggle ${settings.saveHistory ? "on" : ""}`}
                      role="switch"
                      aria-checked={settings.saveHistory}
                      aria-label="保存翻译历史"
                      disabled={!!busy}
                      onClick={() =>
                        save({ saveHistory: !settings.saveHistory })
                      }
                    >
                      <span />
                    </button>
                  </div>
                  <div className="setting-row">
                    <div>
                      <strong>Windows OCR</strong>
                      <p>使用系统已安装的识别语言</p>
                    </div>
                    <span className="small-tag">本地运行</span>
                  </div>
                  <div className="ocr-languages">
                    {status?.ocrLanguages?.length ? (
                      status.ocrLanguages.map((l) => <span key={l}>{l}</span>)
                    ) : (
                      <p>
                        尚未检测到 OCR 语言。请在
                        Windows「语言和区域」中安装对应语言的“光学字符识别”组件。
                      </p>
                    )}
                  </div>
                  <p className="settings-fineprint">
                    截图与导入图片不会上传。翻译时，识别出的文字会发送给
                    DeepSeek API。
                  </p>
                </section>
                <section className="settings-card style-settings">
                  <div className="settings-title">
                    <Languages size={20} />
                    <div>
                      <h2>默认翻译风格</h2>
                      <p>为不同语境，找到合适的表达</p>
                    </div>
                  </div>
                  <div className="style-options">
                    {styles.map(([value, name, description]) => (
                      <button
                        key={value}
                        className={`style-option ${settings.style === value ? "selected" : ""}`}
                        disabled={!!busy}
                        onClick={() => {
                          setResult(undefined);
                          void save({ style: value });
                        }}
                      >
                        <span className="radio-dot">
                          {settings.style === value && <span />}
                        </span>
                        <strong>{name}</strong>
                        <p>{description}</p>
                      </button>
                    ))}
                  </div>
                </section>
                <section className="settings-card shortcut-settings">
                  <div className="settings-title">
                    <Keyboard size={20} />
                    <div>
                      <h2>随手可用</h2>
                      <p>少一点操作，多一点专注</p>
                    </div>
                  </div>
                  <div className="setting-row">
                    <strong>屏幕框选</strong>
                    <div className="keys">
                      <kbd>Ctrl</kbd> + <kbd>Shift</kbd> + <kbd>T</kbd>
                    </div>
                  </div>
                  <div className="setting-row">
                    <strong>翻译当前文本</strong>
                    <div className="keys">
                      <kbd>Ctrl</kbd> + <kbd>Enter</kbd>
                    </div>
                  </div>
                  <div className="setting-row">
                    <strong>取消屏幕框选</strong>
                    <kbd>Esc</kbd>
                  </div>
                  {status?.shortcut && status.shortcut !== "Ctrl+Shift+T" && <p className="settings-fineprint">{status.shortcut}</p>}
                </section>
              </div>
            </>
          )}
          <footer className="main-footer">
            <span>
              译镜 <span className="footer-dot">·</span> 让理解，自然发生
            </span>
            <span>
              LOCAL OCR <span className="footer-dot">/</span> POWERED BY
              DEEPSEEK
            </span>
          </footer>
        </main>
      </div>
      {notice && (
        <div
          className={`toast ${notice.error ? "error" : ""}`}
          role={notice.error ? "alert" : "status"}
        >
          {notice.error ? (
            <span className="toast-alert">!</span>
          ) : (
            <Check size={17} />
          )}
          <span>{notice.text}</span>
          <button aria-label="关闭提示" onClick={() => setNotice(undefined)}>
            <X size={15} />
          </button>
        </div>
      )}
      {confirmClear && (
        <div className="modal-backdrop">
          <div
            className="confirm-modal"
            role="dialog"
            aria-modal="true"
            aria-labelledby="clear-title"
          >
            <span className="modal-icon">
              <Trash2 size={23} />
            </span>
            <h2 id="clear-title">清空所有翻译历史？</h2>
            <p>
              这将删除此设备上的 {history.length} 条翻译记录，删除后无法恢复。
            </p>
            <div>
              <button
                className="secondary-button"
                onClick={() => setConfirmClear(false)}
              >
                保留记录
              </button>
              <button className="primary-button danger-button" onClick={clear}>
                清空历史
              </button>
            </div>
          </div>
        </div>
      )}
      {!window.lens && (
        <div className="preview-banner">
          <Monitor size={14} /> 界面预览 · 屏幕识别与翻译请使用 Windows 桌面应用
        </div>
      )}
    </div>
  );
}
