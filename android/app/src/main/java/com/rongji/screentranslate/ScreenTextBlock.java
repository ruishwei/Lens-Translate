package com.rongji.screentranslate;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** One immutable OCR paragraph, in pixels of the exact image submitted to OCR. */
public final class ScreenTextBlock {
    public final String id, text;
    public final int left, top, right, bottom;
    public final List<Line> lines;

    public ScreenTextBlock(String id, String text, int left, int top, int right, int bottom, List<Line> lines) {
        if (id == null || id.isEmpty() || text == null || text.trim().isEmpty()) throw new IllegalArgumentException("文字块不能为空。");
        if (left < 0 || top < 0 || right <= left || bottom <= top) throw new IllegalArgumentException("无效的文字块坐标。");
        this.id = id; this.text = text.trim();
        this.left = left; this.top = top; this.right = right; this.bottom = bottom;
        this.lines = Collections.unmodifiableList(new ArrayList<>(lines));
    }

    public int width() { return right - left; }
    public int height() { return bottom - top; }

    public static final class Line {
        public final String text;
        public final int left, top, right, bottom;
        public Line(String text, int left, int top, int right, int bottom) {
            if (text == null || text.trim().isEmpty() || left < 0 || top < 0 || right <= left || bottom <= top)
                throw new IllegalArgumentException("无效的文字行。");
            this.text = text.trim(); this.left = left; this.top = top; this.right = right; this.bottom = bottom;
        }
        public int height() { return bottom - top; }
    }
}
