import { useEffect, useRef, useState } from "react";
import { ScanLine, X } from "lucide-react";
import { pixelRectangle, rectangle, type Point } from "./capture-geometry";

export default function Capture() {
  const [image, setImage] = useState("");
  const [error, setError] = useState("");
  const [language, setLanguage] = useState("auto");
  const [start, setStart] = useState<Point>();
  const [end, setEnd] = useState<Point>();
  const [working, setWorking] = useState(false);
  const img = useRef<HTMLImageElement>(null);
  const selecting = useRef(false);
  useEffect(() => {
    let alive = true;
    Promise.all([window.lens?.captureReady(), window.lens?.getSettings()])
      .then(([capture, settings]) => {
        if (!alive) return;
        if (capture) setImage(capture.dataUrl);
        else setError("截图不可用，请取消后重试。");
        if (settings) setLanguage(settings.sourceLanguage);
      })
      .catch(() => alive && setError("无法载入截图，请取消后重试。"));
    const key = (e: KeyboardEvent) => {
      if (e.key === "Escape") void window.lens?.cancelCapture();
    };
    window.addEventListener("keydown", key);
    return () => {
      alive = false;
      window.removeEventListener("keydown", key);
    };
  }, []);
  const rect = start && end ? rectangle(start, end) : undefined;
  const point = (e: React.PointerEvent) => ({
    x: Math.max(0, Math.min(innerWidth, e.clientX)),
    y: Math.max(0, Math.min(innerHeight, e.clientY)),
  });
  const finish = async (e: React.PointerEvent<HTMLDivElement>) => {
    if (!selecting.current || !start || !img.current || working) return;
    selecting.current = false;
    const selection = rectangle(start, point(e));
    setEnd(point(e));
    if (selection.width < 12 || selection.height < 12) {
      setStart(undefined);
      setEnd(undefined);
      return;
    }
    setWorking(true);
    try {
      const canvas = document.createElement("canvas");
      const pixels = pixelRectangle(
        selection,
        { width: innerWidth, height: innerHeight },
        { width: img.current.naturalWidth, height: img.current.naturalHeight },
      );
      canvas.width = pixels.width;
      canvas.height = pixels.height;
      const context = canvas.getContext("2d");
      if (!context) throw new Error("无法初始化截图");
      context.drawImage(
        img.current,
        pixels.x,
        pixels.y,
        canvas.width,
        canvas.height,
        0,
        0,
        canvas.width,
        canvas.height,
      );
      await window.lens?.finishCapture({
        dataUrl: canvas.toDataURL("image/png"),
        language,
      });
    } catch {
      setError("无法识别选区，请取消后重试。");
      setWorking(false);
    }
  };
  return (
    <div
      className="capture-surface"
      onPointerDown={(e) => {
        if (!image || working || e.button !== 0) return;
        selecting.current = true;
        setStart(point(e));
        setEnd(point(e));
        e.currentTarget.setPointerCapture(e.pointerId);
      }}
      onPointerMove={(e) => {
        if (selecting.current) setEnd(point(e));
      }}
      onPointerUp={finish}
    >
      {image && (
        <img
          ref={img}
          className="capture-image"
          src={image}
          alt="屏幕快照"
          draggable={false}
        />
      )}
      {rect ? (
        <div
          className="capture-selection"
          style={{
            left: rect.x,
            top: rect.y,
            width: rect.width,
            height: rect.height,
          }}
        >
          <span>
            {Math.round(rect.width)} × {Math.round(rect.height)}
          </span>
          <i />
          <i />
          <i />
          <i />
        </div>
      ) : (
        <div className="capture-dim" />
      )}
      <div className="capture-tip" onPointerDown={(e) => e.stopPropagation()}>
        <ScanLine size={20} />
        <strong>
          {error || (working ? "正在识别…" : "拖动框选你想读懂的文字")}
        </strong>
        <span>松开鼠标完成 · Esc 取消</span>
        <button
          aria-label="取消截图"
          onClick={() => window.lens?.cancelCapture()}
        >
          <X size={18} />
        </button>
      </div>
    </div>
  );
}
