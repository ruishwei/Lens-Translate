package com.rongji.screentranslate;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Pure rules for bounded requests and identity-safe translated responses. */
final class BlockTranslationRules {
    static final int MAX_BLOCKS = 160;
    static final int MAX_BATCH_BLOCKS = 24;
    static final int MAX_BATCH_CHARACTERS = 5000;
    private BlockTranslationRules() {}

    static int translatableCount(List<ScreenTextBlock> blocks) {
        int count = 0;
        for (ScreenTextBlock block : blocks) if (!block.text.trim().isEmpty() && !preserveOriginal(block.text)) count++;
        return count;
    }

    static boolean preserveOriginal(String text) {
        String value = text.trim();
        // These tokens are IDs and counters, not prose. Do not let OCR counters become words.
        return value.matches("@[\\p{L}\\p{N}_.]+")
                || value.matches("(?i)(?:https?://|www\\.)\\S+")
                || value.matches("(?i)[\\p{L}\\p{N}._%+-]+@[\\p{L}\\p{N}.-]+\\.[a-z]{2,}")
                || value.matches("(?i)[+−\\-]?[\\p{Sc}]?\\s*\\d[\\d\\s,.:/+-]*(?:[kmb]|%|s|m|h|d|w|mo|yr)?")
                || value.matches("[\\p{P}\\p{S}\\s]+");
    }

    static List<List<ScreenTextBlock>> batches(List<ScreenTextBlock> blocks) {
        if (blocks.size() > MAX_BLOCKS) throw new IllegalArgumentException("本屏文字块过多，请使用区域翻译缩小范围。");
        int total = 0, count = 0;
        Set<String> ids = new HashSet<>();
        List<List<ScreenTextBlock>> batches = new ArrayList<>();
        List<ScreenTextBlock> batch = new ArrayList<>();
        for (ScreenTextBlock block : blocks) {
            if (!ids.add(block.id)) throw new IllegalArgumentException("文字块编号重复，请重新截图。");
            total += block.text.length();
            if (total > TranslationRules.MAX_CHARACTERS) throw new IllegalArgumentException("本屏文字超过 12,000 字符，请使用区域翻译。");
            if (preserveOriginal(block.text)) continue;
            if (block.text.length() > MAX_BATCH_CHARACTERS) throw new IllegalArgumentException("单个文字块过长，请使用区域翻译缩小范围。");
            if (!batch.isEmpty() && (batch.size() >= MAX_BATCH_BLOCKS || count + block.text.length() > MAX_BATCH_CHARACTERS)) {
                batches.add(Collections.unmodifiableList(batch)); batch = new ArrayList<>(); count = 0;
            }
            batch.add(block); count += block.text.length();
        }
        if (!batch.isEmpty()) batches.add(Collections.unmodifiableList(batch));
        return batches;
    }

    static Map<String, String> validateResponse(List<ScreenTextBlock> requested, List<Result> response) {
        Map<String, String> values = new LinkedHashMap<>();
        Set<String> expected = new HashSet<>();
        for (ScreenTextBlock block : requested) if (!expected.add(block.id)) throw new IllegalArgumentException("重复的原文编号。");
        for (Result result : response) {
            if (result == null || result.id == null || !expected.contains(result.id))
                throw invalid("包含未知文字块");
            if (values.containsKey(result.id)) throw invalid("文字块编号重复");
            if (result.text == null || result.text.trim().isEmpty()) throw invalid("存在空译文");
            if (result.text.length() > 20_000) throw invalid("译文过长");
            values.put(result.id, result.text.trim());
        }
        if (values.size() != expected.size()) throw invalid("缺少文字块");
        // Return source order even when the model chooses a different JSON array order.
        Map<String, String> ordered = new LinkedHashMap<>();
        for (ScreenTextBlock block : requested) ordered.put(block.id, values.get(block.id));
        return ordered;
    }

    private static IllegalArgumentException invalid(String reason) {
        return new IllegalArgumentException("译文与原文无法完整对应（" + reason + "），请重试或缩小翻译区域。");
    }

    static final class Result {
        final String id, text;
        Result(String id, String text) { this.id = id; this.text = text; }
    }
}
