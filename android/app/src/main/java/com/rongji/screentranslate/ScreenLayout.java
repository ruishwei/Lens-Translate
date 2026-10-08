package com.rongji.screentranslate;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Conservative paragraph grouping. Never join across native OCR blocks or across columns. */
final class ScreenLayout {
    private ScreenLayout() {}

    static OcrDocument document(int width, int height, List<List<ScreenTextBlock.Line>> nativeBlocks) {
        Set<ScreenTextBlock.Line> profileRows = profileRows(nativeBlocks);
        List<List<ScreenTextBlock.Line>> paragraphs = new ArrayList<>();
        for (List<ScreenTextBlock.Line> nativeBlock : nativeBlocks) {
            List<ScreenTextBlock.Line> ordered = new ArrayList<>(nativeBlock);
            ordered.sort(Comparator.comparingInt((ScreenTextBlock.Line line) -> line.top).thenComparingInt(line -> line.left));
            List<List<ScreenTextBlock.Line>> groups = new ArrayList<>();
            for (ScreenTextBlock.Line line : ordered) {
                List<ScreenTextBlock.Line> best = null;
                int closestGap = Integer.MAX_VALUE;
                for (List<ScreenTextBlock.Line> candidate : groups) {
                    ScreenTextBlock.Line last = candidate.get(candidate.size() - 1);
                    int gap = line.top - last.bottom;
                    if (!profileRows.contains(last) && !profileRows.contains(line)
                            && canContinue(last, line) && gap < closestGap) { best = candidate; closestGap = gap; }
                }
                if (best == null) { best = new ArrayList<>(); groups.add(best); }
                best.add(line);
            }
            paragraphs.addAll(groups);
        }
        // Screen reading order: top-to-bottom visual rows, left-to-right within each row.
        // The order supplies context only; translation placement always uses the immutable IDs.
        paragraphs.sort(Comparator.comparingInt((List<ScreenTextBlock.Line> lines) -> lines.get(0).top)
                .thenComparingInt(lines -> lines.get(0).left));
        List<ScreenTextBlock> blocks = new ArrayList<>();
        for (List<ScreenTextBlock.Line> lines : paragraphs) {
            StringBuilder text = new StringBuilder();
            int left = width, top = height, right = 0, bottom = 0;
            for (ScreenTextBlock.Line line : lines) {
                if (text.length() > 0) text.append('\n');
                text.append(line.text);
                left = Math.min(left, line.left); top = Math.min(top, line.top);
                right = Math.max(right, line.right); bottom = Math.max(bottom, line.bottom);
            }
            blocks.add(new ScreenTextBlock("b" + blocks.size(), text.toString(), left, top, right, bottom, lines));
        }
        return new OcrDocument(width, height, blocks);
    }

    /** ML Kit may place a display name and body together, but the handle in a separate block. */
    private static Set<ScreenTextBlock.Line> profileRows(List<List<ScreenTextBlock.Line>> nativeBlocks) {
        List<ScreenTextBlock.Line> lines = new ArrayList<>(), handles = new ArrayList<>();
        for (List<ScreenTextBlock.Line> block : nativeBlocks) for (ScreenTextBlock.Line line : block) {
            lines.add(line);
            if (line.text.matches(".*(?:^|\\s)@[\\p{L}\\p{N}_].*")) handles.add(line);
        }
        Set<ScreenTextBlock.Line> profileRows = new HashSet<>();
        for (ScreenTextBlock.Line line : lines) for (ScreenTextBlock.Line handle : handles) {
            if (line == handle) continue;
            int gap = Math.max(line.left, handle.left) - Math.min(line.right, handle.right);
            int height = Math.min(line.height(), handle.height());
            int overlap = Math.min(line.bottom, handle.bottom) - Math.max(line.top, handle.top);
            // Only nearby fragments on the same visual row; a handle in another column or
            // a later comment must not split an unrelated paragraph.
            if (gap >= 0 && gap <= height * 4 && overlap >= height * .65f
                    && Math.abs((line.top + line.bottom) - (handle.top + handle.bottom)) <= height * .7f) {
                profileRows.add(line); break;
            }
        }
        return profileRows;
    }

    /** Prefer Latin results for English, replacing only individual rows that contain Chinese. */
    static OcrDocument combineScripts(OcrDocument latin, OcrDocument chinese) {
        List<ScreenTextBlock.Line> chineseLines = new ArrayList<>();
        List<List<ScreenTextBlock.Line>> groups = new ArrayList<>();
        for (ScreenTextBlock block : chinese.blocks) {
            List<ScreenTextBlock.Line> selected = new ArrayList<>();
            for (ScreenTextBlock.Line line : block.lines) {
                long han = line.text.chars().filter(character -> Character.UnicodeScript.of(character) == Character.UnicodeScript.HAN).count();
                long letters = line.text.chars().filter(Character::isLetter).count();
                if (han >= 2 && han >= letters * .15) { selected.add(line); chineseLines.add(line); }
            }
            if (!selected.isEmpty()) groups.add(selected);
        }
        for (ScreenTextBlock block : latin.blocks) {
            List<ScreenTextBlock.Line> retained = new ArrayList<>();
            for (ScreenTextBlock.Line line : block.lines) {
                boolean replaced = false;
                for (ScreenTextBlock.Line cjk : chineseLines) {
                    long overlap = (long) Math.max(0, Math.min(line.right, cjk.right) - Math.max(line.left, cjk.left))
                            * Math.max(0, Math.min(line.bottom, cjk.bottom) - Math.max(line.top, cjk.top));
                    if (overlap > Math.min((long) (line.right - line.left) * line.height(), (long) (cjk.right - cjk.left) * cjk.height()) * .45) {
                        replaced = true; break;
                    }
                }
                if (!replaced) retained.add(line);
            }
            if (!retained.isEmpty()) groups.add(retained);
        }
        return document(latin.width, latin.height, groups);
    }

    private static boolean canContinue(ScreenTextBlock.Line previous, ScreenTextBlock.Line next) {
        float height = Math.min(previous.height(), next.height());
        int gap = next.top - previous.bottom;
        if (gap < -height * .12f || gap > height * .85f) return false;
        if (Math.max(previous.height(), next.height()) > height * 1.35f) return false;
        if (Math.abs(previous.left - next.left) > height * .8f) return false;
        if (Math.min(previous.right, next.right) <= Math.max(previous.left, next.left)) return false;
        // Social profile/metadata rows, counters and short labels must not swallow the body below.
        if (previous.text.contains("@") || next.text.contains("@")) return false;
        if (BlockTranslationRules.preserveOriginal(previous.text) || BlockTranslationRules.preserveOriginal(next.text)) return false;
        if (previous.text.trim().split("\\s+").length <= 3
                && previous.right - previous.left < (next.right - next.left) * .48f) return false;
        return true;
    }
}
