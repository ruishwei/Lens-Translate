package com.rongji.screentranslate;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.text.LineBreaker;
import android.text.Layout;
import android.text.StaticLayout;
import android.text.TextPaint;
import android.text.TextUtils;
import android.view.MotionEvent;
import android.view.View;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;

/** A transparent screen layer, with an optional frozen-image renderer for image previews. */
public final class TranslationOverlayView extends View {
    public interface OnBlockClickListener { void onBlockClick(ScreenTextBlock block, String translated); }
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    private Bitmap screenshot;
    private OcrDocument document;
    private Rect crop = new Rect();
    private Map<String, String> translations = Collections.emptyMap();
    private final List<DrawBlock> rendered = new ArrayList<>();
    private boolean showOriginal;
    private boolean transparentLayer;
    private int displayWidth, displayHeight, drawnX, drawnY;
    private final int[] location = new int[2];
    private OnBlockClickListener listener;

    private static final class DrawBlock {
        ScreenTextBlock block;
        String translated;
        RectF box;
        RectF mask;
        final List<RectF> maskExclusions = new ArrayList<>();
        int background;
        int foreground;
        StaticLayout layout;
        float inset;
    }
    public TranslationOverlayView(Context context) {
        super(context); setBackgroundColor(0xFF17212D); setClickable(true); setFocusable(true);
        setContentDescription("已冻结的截图翻译。点击文字块查看完整原文与译文，或使用底部的全部文本按钮。");
    }
    public void setScreenshot(Bitmap bitmap) { screenshot = bitmap; rebuild(); }
    /** Keep the page visible; only translated source blocks receive an opaque black backing. */
    public void setTransparentLayer(int screenWidth, int screenHeight) {
        transparentLayer = true; displayWidth = screenWidth; displayHeight = screenHeight;
        setBackgroundColor(Color.TRANSPARENT);
        setContentDescription("翻译图层，点击任意位置退出翻译并返回悬浮球");
        rebuild();
    }
    /** crop is measured in original screenshot pixels; document boxes are measured inside that crop. */
    public void setDocument(OcrDocument value, Rect cropBounds, Map<String, String> values) {
        document = value; crop = new Rect(cropBounds); translations = values == null ? Collections.emptyMap() : values; rebuild();
    }
    public void setShowOriginal(boolean value) { showOriginal = value; invalidate(); }
    public boolean isShowingOriginal() { return showOriginal; }
    public void setOnBlockClickListener(OnBlockClickListener value) { listener = value; }
    @Override protected void onSizeChanged(int w, int h, int oldw, int oldh) { rebuild(); }
    private ScreenGeometry.Transform transform() {
        if (screenshot == null || screenshot.isRecycled() || getWidth() <= 0 || getHeight() <= 0) return null;
        if (transparentLayer) {
            getLocationOnScreen(location);
            return new ScreenGeometry.Transform(screenshot.getWidth(), screenshot.getHeight(), displayWidth, displayHeight, location[0], location[1]);
        }
        return new ScreenGeometry.Transform(screenshot.getWidth(), screenshot.getHeight(), getWidth(), getHeight());
    }
    private static RectF rect(ScreenGeometry.Box b) { return new RectF(b.left, b.top, b.right, b.bottom); }
    private void rebuild() {
        rendered.clear(); ScreenGeometry.Transform t = transform();
        drawnX = location[0]; drawnY = location[1];
        if (t == null || document == null) { invalidate(); return; }
        List<ScreenTextBlock> blocks = new ArrayList<>(document.blocks);
        blocks.sort(Comparator.comparingInt((ScreenTextBlock b) -> b.top).thenComparingInt(b -> b.left));
        Map<String, RectF> sourceBounds = new LinkedHashMap<>();
        for (ScreenTextBlock block : blocks) {
            String translated = translations.get(block.id);
            if (translated == null || translated.trim().isEmpty() || translated.trim().equals(block.text.trim()))
                sourceBounds.put(block.id, rect(t.toView(new ScreenGeometry.Box(
                        crop.left + block.left, crop.top + block.top, crop.left + block.right, crop.top + block.bottom))));
        }
        for (ScreenTextBlock block : blocks) {
            String text = translations.get(block.id);
            // A missing or unchanged block retains its original pixels; never put unmatched text elsewhere.
            if (text == null || text.trim().isEmpty() || text.trim().equals(block.text.trim())) continue;
            int left = Math.max(crop.left, crop.left + block.left), top = Math.max(crop.top, crop.top + block.top);
            int right = Math.min(crop.right, crop.left + block.right), bottom = Math.min(crop.bottom, crop.top + block.bottom);
            if (right <= left || bottom <= top) continue;
            DrawBlock item = new DrawBlock(); item.block = block; item.translated = text;
            item.box = rect(t.toView(new ScreenGeometry.Box(left, top, right, bottom)));
            // OCR bounds can miss antialiasing and descenders by several source pixels.
            // Extend only the opaque mask; text still lays out in the exact original box.
            int lineHeight = block.lines.isEmpty() ? block.height() : block.lines.stream().mapToInt(line -> line.height()).max().orElse(block.height());
            int padding = Math.max(2, Math.min(8, Math.round(lineHeight * .12f)));
            item.mask = rect(t.toView(new ScreenGeometry.Box(Math.max(crop.left, left - padding), Math.max(crop.top, top - padding),
                    Math.min(crop.right, right + padding), Math.min(crop.bottom, bottom + padding))));
            item.background = transparentLayer ? Color.BLACK : sampleBackground(new Rect(left, top, right, bottom));
            item.inset = Math.min(Ui.dp(getContext(), 1), Math.min(item.box.width(), item.box.height()) / 12);
            TextPaint type = new TextPaint(Paint.ANTI_ALIAS_FLAG | Paint.SUBPIXEL_TEXT_FLAG);
            int r = Color.red(item.background), g = Color.green(item.background), b = Color.blue(item.background);
            item.foreground = (r * .299 + g * .587 + b * .114) >= 145 ? 0xFF111827 : Color.WHITE;
            if (transparentLayer) item.foreground = Color.WHITE;
            type.setColor(item.foreground);
            if (transparentLayer) {
                type.setTypeface(android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL));
            }
            int lines = Math.max(1, block.lines.size());
            float maximum = Math.min(Ui.dp(getContext(), 23), Math.max(Ui.dp(getContext(), 10), item.box.height() / lines * .79f));
            int availableWidth = Math.max(1, (int)(item.box.width() - item.inset * 2));
            int availableHeight = Math.max(1, (int)(item.box.height() - item.inset * 2));
            // A screenshot may be downscaled far below device dp. The lower font bound must
            // follow the physical box height too, or small labels lose their descenders.
            float minimum = Math.min(maximum, Math.min(Ui.dp(getContext(), 9), availableHeight * .75f));
            StaticLayout candidate = null;
            for (float size = maximum; size >= minimum; size -= Math.max(.5f, t.scale)) {
                type.setTextSize(size); candidate = layout(text, type, availableWidth, Integer.MAX_VALUE, false);
                if (candidate.getHeight() <= availableHeight) break;
            }
            if (candidate == null || candidate.getHeight() > availableHeight) {
                type.setTextSize(minimum);
                int maxLines = Math.max(1, (int)(availableHeight / Math.max(1f, type.getFontSpacing())));
                candidate = layout(text, type, availableWidth, maxLines, true);
                // CJK fallback font metrics can be taller than TextPaint's nominal metrics.
                // Measure the completed layout, then decrease until the entire line fits.
                while (candidate.getHeight() > availableHeight && type.getTextSize() > 1f) {
                    type.setTextSize(Math.max(1f, type.getTextSize() * .85f));
                    maxLines = Math.max(1, (int)(availableHeight / Math.max(1f, type.getFontSpacing())));
                    candidate = layout(text, type, availableWidth, maxLines, true);
                }
            }
            item.layout = candidate != null && candidate.getHeight() <= availableHeight ? candidate : null;
            rendered.add(item);
        }
        for (DrawBlock item : rendered) for (Map.Entry<String, RectF> neighbor : sourceBounds.entrySet()) {
            if (!neighbor.getKey().equals(item.block.id) && RectF.intersects(item.mask, neighbor.getValue())) item.maskExclusions.add(neighbor.getValue());
        }
        invalidate();
    }
    // The API 35 annotation names LineBreaker, but this inlined int is the same strategy
    // supported by StaticLayout.Builder since API 23; it does not load LineBreaker on API 26.
    @android.annotation.SuppressLint("InlinedApi")
    private static StaticLayout layout(String text, TextPaint paint, int width, int lines, boolean ellipsis) {
        StaticLayout.Builder builder = StaticLayout.Builder.obtain(text, 0, text.length(), paint, width)
                .setAlignment(Layout.Alignment.ALIGN_NORMAL).setIncludePad(false).setLineSpacing(0, 1.0f)
                .setBreakStrategy(LineBreaker.BREAK_STRATEGY_HIGH_QUALITY).setHyphenationFrequency(Layout.HYPHENATION_FREQUENCY_NONE).setMaxLines(lines);
        if (ellipsis) builder.setEllipsize(TextUtils.TruncateAt.END).setEllipsizedWidth(width);
        return builder.build();
    }
    /** Choose the dominant color on the box edges, so text strokes cannot turn a white card gray. */
    private int sampleBackground(Rect box) {
        int[] count = new int[512], red = new int[512], green = new int[512], blue = new int[512];
        for (int i = 0; i < 32; i++) {
            int x = box.left + (int)((box.width() - 1) * i / 31f), y = box.top + (int)((box.height() - 1) * i / 31f);
            sample(x, box.top - 1, count, red, green, blue); sample(x, box.bottom, count, red, green, blue);
            sample(box.left - 1, y, count, red, green, blue); sample(box.right, y, count, red, green, blue);
        }
        int best = 0; for (int i = 1; i < count.length; i++) if (count[i] > count[best]) best = i;
        return count[best] == 0 ? Color.WHITE : Color.rgb(red[best] / count[best], green[best] / count[best], blue[best] / count[best]);
    }
    private void sample(int x, int y, int[] counts, int[] reds, int[] greens, int[] blues) {
        x = Math.max(0, Math.min(screenshot.getWidth() - 1, x)); y = Math.max(0, Math.min(screenshot.getHeight() - 1, y));
        int color = screenshot.getPixel(x, y), r = Color.red(color), g = Color.green(color), b = Color.blue(color);
        int bucket = ((r >> 5) << 6) | ((g >> 5) << 3) | (b >> 5);
        counts[bucket]++; reds[bucket] += r; greens[bucket] += g; blues[bucket] += b;
    }
    @Override protected void onDraw(Canvas canvas) {
        if (transparentLayer) {
            getLocationOnScreen(location);
            if (drawnX != location[0] || drawnY != location[1]) rebuild();
        }
        super.onDraw(canvas); ScreenGeometry.Transform t = transform(); if (t == null) return;
        if (!transparentLayer) { paint.setColor(Color.WHITE); canvas.drawBitmap(screenshot, null, rect(t.viewport()), paint); }
        if (showOriginal) return;
        // Back every translated source block, including original lines beyond a shorter translation.
        // Full opacity prevents original ink showing through. The rest of the page stays transparent.
        for (DrawBlock item : rendered) {
            int save = canvas.save();
            // Expanded masks must not erase unchanged counters or neighboring source labels.
            for (RectF neighbor : item.maskExclusions) canvas.clipOutRect(neighbor);
            paint.setColor(item.background); canvas.drawRect(item.mask, paint); canvas.restoreToCount(save);
        }
        for (int index = 0; index < rendered.size(); index++) {
            DrawBlock item = rendered.get(index);
            int save = canvas.save(); canvas.clipRect(item.box);
            // Imperfect OCR boxes occasionally overlap: give the earlier block ownership of those pixels.
            // Together with per-block clipping this prevents translated glyphs from ever overlapping.
            for (int previousIndex = 0; previousIndex < index; previousIndex++) {
                RectF previous = rendered.get(previousIndex).box;
                if (RectF.intersects(previous, item.box)) canvas.clipOutRect(previous);
            }
            if (item.layout != null) {
                canvas.translate(item.box.left + item.inset, item.box.top + item.inset); item.layout.draw(canvas);
            } else {
                // An exceptionally tiny rectangle cannot show a font at all: use three fully
                // contained dots as a detail affordance rather than clipped fragments of text.
                paint.setColor(item.foreground);
                float radius = Math.min(item.box.height() / 3f, item.box.width() / 12f);
                for (int dot = -1; dot <= 1; dot++) canvas.drawCircle(item.box.centerX() + dot * radius * 3, item.box.centerY(), radius, paint);
            }
            canvas.restoreToCount(save);
        }
    }
    private float downX, downY;
    @Override public boolean onTouchEvent(MotionEvent event) {
        if (event.getActionMasked() == MotionEvent.ACTION_DOWN) { downX = event.getX(); downY = event.getY(); return true; }
        if (event.getActionMasked() == MotionEvent.ACTION_UP) {
            if (Math.hypot(event.getX() - downX, event.getY() - downY) > Ui.dp(getContext(), 16)) return true;
            performClick();
            if (transparentLayer) return true;
            if (listener != null && document != null) {
                ScreenGeometry.Transform t = transform(); if (t == null) return true;
                for (ScreenTextBlock block : document.blocks) {
                    RectF bounds = rect(t.toView(new ScreenGeometry.Box(crop.left + block.left, crop.top + block.top, crop.left + block.right, crop.top + block.bottom)));
                    if (bounds.contains(event.getX(), event.getY())) { listener.onBlockClick(block, translations.getOrDefault(block.id, block.text)); break; }
                }
            }
            return true;
        }
        return event.getActionMasked() == MotionEvent.ACTION_MOVE || event.getActionMasked() == MotionEvent.ACTION_CANCEL;
    }
    @Override public boolean performClick() { super.performClick(); return true; }
}
