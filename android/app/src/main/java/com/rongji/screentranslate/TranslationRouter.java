package com.rongji.screentranslate;

import java.util.*;

/** Deterministic routing, independent of Android and of translation quality guesses. */
final class TranslationRouter {
    static final String AUTO = "auto", OFFLINE = "offline", LLM = "llm";
    static final String[] MODES = {AUTO, OFFLINE, LLM};
    static final String[] LABELS = {"自动 · 离线优先", "仅离线 · Google Translate", "仅 DeepSeek · LLM"};
    static final int LONG_TEXT = 240;
    interface Local { String translate(String text, String source, String target) throws Exception; }
    interface Cloud {
        String translate(String text, String source, String target) throws Exception;
        Map<String, String> blocks(List<ScreenTextBlock> blocks, String source, String target) throws Exception;
    }
    interface Check { void run() throws Exception; }
    static String validMode(String mode) { return Arrays.asList(MODES).contains(mode) ? mode : AUTO; }
    static int modeIndex(String mode) { return Arrays.asList(MODES).indexOf(validMode(mode)); }
    static String label(String mode) { return LABELS[modeIndex(mode)]; }
    static final class Options {
        final String mode; final boolean longText, cloudAvailable;
        Options(String mode, boolean longText, boolean cloudAvailable) { this.mode = validMode(mode); this.longText = longText; this.cloudAvailable = cloudAvailable; }
        boolean preferCloud(String text) { return LLM.equals(mode) || AUTO.equals(mode) && longText && cloudAvailable && text.length() >= LONG_TEXT; }
        boolean fallbackCloud() { return AUTO.equals(mode) && cloudAvailable; }
    }
    static final class Result {
        final Map<String, String> values; final int localCount, cloudCount, failedCount; final String reason, warning;
        Result(Map<String, String> values, int local, int cloud, String reason) { this(values, local, cloud, reason, 0, ""); }
        Result(Map<String, String> values, int local, int cloud, String reason, int failed, String warning) {
            this.values = values; localCount = local; cloudCount = cloud; this.reason = reason; failedCount = failed; this.warning = warning;
        }
        String text() { return values.get("text"); }
        String summary() {
            String base = cloudCount == 0 ? (localCount == 0 ? "未发现需要翻译的正文" : "离线 · 0 token · Google Translate")
                    : (localCount == 0 ? "DeepSeek" : "离线 " + localCount + " 块 + DeepSeek " + cloudCount + " 块")
                    + (reason.isEmpty() ? "" : " · " + reason);
            return base + (failedCount == 0 ? "" : " · " + failedCount + " 块未翻译，保留原文");
        }
    }
    private final Local local; private final Cloud cloud; private final Check check;
    TranslationRouter(Local local, Cloud cloud, Check check) { this.local = local; this.cloud = cloud; this.check = check; }
    Result text(String input, String source, String target, Options options) throws Exception {
        String text = TranslationRules.validate(input); check.run();
        boolean useCloud = options.preferCloud(text);
        String reason = useCloud && AUTO.equals(options.mode) ? "长段落规则" : "";
        String value;
        try { value = useCloud ? cloud.translate(text, source, target) : local.translate(text, source, target); }
        catch (Exception first) {
            check.run();
            if (!useCloud && options.fallbackCloud()) { value = cloud.translate(text, source, target); useCloud = true; reason = "离线不可用"; }
            else if (useCloud && AUTO.equals(options.mode)) { value = local.translate(text, source, target); useCloud = false; }
            else throw first;
        }
        check.run();
        if (value == null || value.trim().isEmpty()) throw new IllegalStateException("翻译返回空内容，请重试。");
        return new Result(Collections.singletonMap("text", value), useCloud ? 0 : 1, useCloud ? 1 : 0, reason);
    }
    Result blocks(List<ScreenTextBlock> blocks, String source, String target, Options options) throws Exception {
        BlockTranslationRules.batches(blocks); // Validate bounds and IDs before any engine is invoked.
        Map<String, String> values = new LinkedHashMap<>(); List<ScreenTextBlock> remote = new ArrayList<>();
        Map<String, Exception> failures = new LinkedHashMap<>();
        int localCount = 0; boolean unavailable = false;
        for (ScreenTextBlock block : blocks) {
            check.run();
            if (BlockTranslationRules.preserveOriginal(block.text)) { values.put(block.id, block.text); continue; }
            if (options.preferCloud(block.text)) { remote.add(block); continue; }
            try { values.put(block.id, local.translate(block.text, source, target)); localCount++; }
            catch (Exception error) {
                check.run();
                if (!options.fallbackCloud()) { failures.put(block.id, error); values.put(block.id, block.text); }
                else { remote.add(block); unavailable = true; }
            }
        }
        int cloudCount = 0;
        if (!remote.isEmpty()) {
            check.run();
            try {
                Map<String, String> response = cloud.blocks(remote, source, target);
                List<BlockTranslationRules.Result> checked = new ArrayList<>();
                for (Map.Entry<String, String> item : response.entrySet()) checked.add(new BlockTranslationRules.Result(item.getKey(), item.getValue()));
                values.putAll(BlockTranslationRules.validateResponse(remote, checked)); cloudCount = remote.size();
            } catch (Exception error) {
                check.run(); if (!AUTO.equals(options.mode)) throw error;
                for (ScreenTextBlock block : remote) {
                    check.run();
                    try { values.put(block.id, local.translate(block.text, source, target)); localCount++; }
                    catch (Exception localError) { check.run(); failures.put(block.id, localError); values.put(block.id, block.text); }
                }
            }
        }
        check.run(); Map<String, String> ordered = new LinkedHashMap<>();
        for (ScreenTextBlock block : blocks) {
            String value = values.get(block.id);
            if (value == null || value.trim().isEmpty()) throw new IllegalStateException("译文缺少文字块，请重试。");
            ordered.put(block.id, value);
        }
        // A failed side label must not discard the successfully translated article.
        // An entirely failed page still reports failure, never a false successful '0 token'.
        if (localCount + cloudCount == 0 && !failures.isEmpty()) throw failures.values().iterator().next();
        String warning = failures.isEmpty() ? "" : failures.values().iterator().next().getMessage();
        return new Result(ordered, localCount, cloudCount, unavailable ? "离线不可用" : AUTO.equals(options.mode) ? "长段落规则" : "",
                failures.size(), warning == null ? "部分文字块翻译失败，请检查语言包或网络。" : warning);
    }
}
