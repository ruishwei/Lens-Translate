package com.rongji.screentranslate;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.RectF;
import android.view.MotionEvent;
import android.view.View;

/** A selection is always made against these exact frozen pixels, never against a moving app. */
public final class RegionSelectionView extends View {
    public interface OnSelectionChangedListener { void onSelectionChanged(Rect selection); }
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    private Bitmap screenshot;
    private Rect selection;
    private float downX, downY;
    private boolean dragging;
    private OnSelectionChangedListener listener;

    public RegionSelectionView(Context context) {
        super(context); setBackgroundColor(0xFF17212D); setClickable(true); setFocusable(true);
        setContentDescription("已冻结的截图。拖动选择翻译区域，选好后点击翻译选区。可使用底部的扩大或缩小选区按钮。");
    }
    public void setScreenshot(Bitmap bitmap) { screenshot = bitmap; clearSelection(); }
    public void setOnSelectionChangedListener(OnSelectionChangedListener value) { listener = value; }
    public Rect getSelection() { return selection == null ? null : new Rect(selection); }
    public void clearSelection() { selection = null; dragging = false; changed(); }
    public boolean isSelectionValid() {
        ScreenGeometry.Transform t = transform();
        // A single text line can be only 10–16 dp tall after fitting the frozen screenshot.
        // Keep a small drag threshold for accidental taps without rejecting line-level areas.
        return t != null && selection != null && ScreenGeometry.isUsableSelection(box(selection), t, Ui.dp(getContext(), 8));
    }
    /** Keyboard/TalkBack alternative: start centrally; every button click changes the same visible selection. */
    public void adjustSelection(boolean expand) {
        if (screenshot == null) return;
        int w = screenshot.getWidth(), h = screenshot.getHeight();
        if (selection == null) selection = new Rect(w / 8, h / 3, w * 7 / 8, h * 2 / 3);
        else {
            int dx = Math.max(1, w / 20), dy = Math.max(1, h / 30);
            Rect next = new Rect(selection); next.inset(expand ? -dx : dx, expand ? -dy : dy);
            if (next.width() > 0 && next.height() > 0) {
                if (next.intersect(0, 0, w, h)) selection = next;
            }
        }
        changed();
    }
    private ScreenGeometry.Transform transform() {
        if (screenshot == null || screenshot.isRecycled() || getWidth() <= 0 || getHeight() <= 0) return null;
        return new ScreenGeometry.Transform(screenshot.getWidth(), screenshot.getHeight(), getWidth(), getHeight());
    }
    private static ScreenGeometry.Box box(Rect r) { return new ScreenGeometry.Box(r.left, r.top, r.right, r.bottom); }
    private static RectF rect(ScreenGeometry.Box r) { return new RectF(r.left, r.top, r.right, r.bottom); }
    private void changed() { invalidate(); if (listener != null) listener.onSelectionChanged(getSelection()); }
    @Override protected void onDraw(Canvas canvas) {
        super.onDraw(canvas); ScreenGeometry.Transform t = transform(); if (t == null) return;
        paint.setColor(Color.WHITE); paint.setStyle(Paint.Style.FILL); canvas.drawBitmap(screenshot, null, rect(t.viewport()), paint);
        RectF selected = selection == null ? null : rect(t.toView(box(selection)));
        paint.setColor(0x99050D19);
        if (selected == null) canvas.drawRect(0, 0, getWidth(), getHeight(), paint);
        else {
            canvas.drawRect(0, 0, getWidth(), selected.top, paint); canvas.drawRect(0, selected.bottom, getWidth(), getHeight(), paint);
            canvas.drawRect(0, selected.top, selected.left, selected.bottom, paint); canvas.drawRect(selected.right, selected.top, getWidth(), selected.bottom, paint);
            paint.setColor(isSelectionValid() ? 0xFF20DAC3 : 0xFFFFBA73); paint.setStyle(Paint.Style.STROKE); paint.setStrokeWidth(Ui.dp(getContext(), 2));
            canvas.drawRect(selected, paint); paint.setStyle(Paint.Style.FILL);
            float radius = Ui.dp(getContext(), 5);
            canvas.drawCircle(selected.left, selected.top, radius, paint); canvas.drawCircle(selected.right, selected.top, radius, paint);
            canvas.drawCircle(selected.left, selected.bottom, radius, paint); canvas.drawCircle(selected.right, selected.bottom, radius, paint);
        }
    }
    @Override public boolean onTouchEvent(MotionEvent event) {
        ScreenGeometry.Transform t = transform(); if (t == null) return false;
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                if (!t.viewport().contains(event.getX(), event.getY())) return false;
                downX = event.getX(); downY = event.getY(); dragging = true; if (getParent() != null) getParent().requestDisallowInterceptTouchEvent(true); return true;
            case MotionEvent.ACTION_MOVE:
            case MotionEvent.ACTION_UP:
                if (!dragging) return false;
                ScreenGeometry.Box b = t.dragToPixels(downX, downY, event.getX(), event.getY());
                selection = new Rect((int)b.left, (int)b.top, (int)b.right, (int)b.bottom); changed();
                if (event.getActionMasked() == MotionEvent.ACTION_UP) { dragging = false; performClick(); if (getParent() != null) getParent().requestDisallowInterceptTouchEvent(false); }
                return true;
            case MotionEvent.ACTION_CANCEL: dragging = false; if (getParent() != null) getParent().requestDisallowInterceptTouchEvent(false); return true;
            default: return false;
        }
    }
    @Override public boolean performClick() { super.performClick(); return true; }
}
