package com.rongji.screentranslate;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Coordinates are relative to the original OCR bitmap, including for an area crop. */
public final class OcrDocument {
    public final int width, height;
    public final List<ScreenTextBlock> blocks;
    public OcrDocument(int width, int height, List<ScreenTextBlock> blocks) {
        if (width <= 0 || height <= 0) throw new IllegalArgumentException("无效的截图尺寸。");
        Set<String> ids = new HashSet<>();
        for (ScreenTextBlock block : blocks) {
            if (block.right > width || block.bottom > height || !ids.add(block.id))
                throw new IllegalArgumentException("文字块坐标或编号无效。");
        }
        this.width = width; this.height = height;
        this.blocks = Collections.unmodifiableList(new ArrayList<>(blocks));
    }
    public String text() {
        StringBuilder result = new StringBuilder();
        for (ScreenTextBlock block : blocks) {
            if (result.length() > 0) result.append("\n\n");
            result.append(block.text);
        }
        return result.toString();
    }
}
