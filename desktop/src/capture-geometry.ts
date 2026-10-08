export interface Point {
  x: number;
  y: number;
}
export interface Rectangle extends Point {
  width: number;
  height: number;
}
export function rectangle(a: Point, b: Point): Rectangle {
  return {
    x: Math.min(a.x, b.x),
    y: Math.min(a.y, b.y),
    width: Math.abs(a.x - b.x),
    height: Math.abs(a.y - b.y),
  };
}
export function pixelRectangle(
  selection: Rectangle,
  viewport: { width: number; height: number },
  image: { width: number; height: number },
): Rectangle {
  if (
    [viewport.width, viewport.height, image.width, image.height].some(
      (n) => !Number.isFinite(n) || n <= 0,
    )
  )
    throw new Error("无效的截图尺寸");
  const sx = image.width / viewport.width,
    sy = image.height / viewport.height;
  const x = Math.max(0, Math.min(image.width, Math.round(selection.x * sx)));
  const y = Math.max(0, Math.min(image.height, Math.round(selection.y * sy)));
  const right = Math.max(
    x,
    Math.min(image.width, Math.round((selection.x + selection.width) * sx)),
  );
  const bottom = Math.max(
    y,
    Math.min(image.height, Math.round((selection.y + selection.height) * sy)),
  );
  return { x, y, width: right - x, height: bottom - y };
}
