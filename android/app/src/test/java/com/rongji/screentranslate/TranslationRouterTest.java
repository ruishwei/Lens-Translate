package com.rongji.screentranslate;

import org.junit.Test;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import static org.junit.Assert.*;

public class TranslationRouterTest {
    private static ScreenTextBlock block(String id, String text) { return new ScreenTextBlock(id, text, 0, 0, 100, 30, Collections.emptyList()); }
    private static class Backends implements TranslationRouter.Local, TranslationRouter.Cloud {
        int localCalls, cloudCalls; boolean localFails, cloudFails; List<ScreenTextBlock> sent = Collections.emptyList();
        public String translate(String text, String source, String target) throws Exception { localCalls++; if (localFails || text.startsWith("missing")) throw new Exception("missing model"); return "local:" + text; }
        TranslationRouter.Cloud cloud() { return new TranslationRouter.Cloud() {
            public String translate(String text, String source, String target) throws Exception { cloudCalls++; if (cloudFails) throw new Exception("network"); return "cloud:" + text; }
            public Map<String, String> blocks(List<ScreenTextBlock> blocks, String source, String target) throws Exception { return Backends.this.blocks(blocks, source, target); }
        }; }
        public Map<String, String> blocks(List<ScreenTextBlock> blocks, String source, String target) throws Exception {
            cloudCalls++; if (cloudFails) throw new Exception("network"); sent = blocks;
            Map<String, String> result = new LinkedHashMap<>(); for (ScreenTextBlock b : blocks) result.put(b.id, "cloud:" + b.text); return result;
        }
        TranslationRouter router() { return new TranslationRouter(this, cloud(), () -> {}); }
    }
    private static TranslationRouter.Options option(String mode, boolean longText, boolean available) { return new TranslationRouter.Options(mode, longText, available); }
    @Test public void strictOfflineNeverUsesCloudEvenWhenLocalFails() throws Exception {
        Backends b = new Backends(); b.localFails = true;
        try { b.router().text("Hello", "English", "简体中文", option("offline", true, true)); fail(); } catch (Exception expected) { assertEquals("missing model", expected.getMessage()); }
        assertEquals(0, b.cloudCalls);
    }
    @Test public void automaticUsesLocalForShortText() throws Exception {
        Backends b = new Backends(); var result = b.router().text("Hello", "English", "简体中文", option("auto", false, true));
        assertEquals("local:Hello", result.text()); assertEquals(0, b.cloudCalls); assertEquals(0, result.cloudCount);
    }
    @Test public void automaticOnlySendsFailedBlocksAndPreservesOrder() throws Exception {
        Backends b = new Backends(); var result = b.router().blocks(Arrays.asList(block("a", "Hello"), block("b", "missing model"), block("c", "@example")), "English", "简体中文", option("auto", false, true));
        assertEquals(1, b.sent.size()); assertEquals("b", b.sent.get(0).id); assertEquals(Arrays.asList("a", "b", "c"), new ArrayList<>(result.values.keySet()));
        assertEquals("@example", result.values.get("c")); assertEquals(1, result.localCount); assertEquals(1, result.cloudCount);
    }
    @Test public void noCloudAvailabilityKeepsAutomaticLocal() throws Exception {
        Backends b = new Backends(); b.localFails = true;
        try { b.router().text("Hello".repeat(100), "English", "简体中文", option("auto", true, false)); fail(); } catch (Exception expected) { }
        assertEquals(0, b.cloudCalls); assertEquals(1, b.localCalls);
    }
    @Test public void longParagraphRuleIsOptInAndHasBoundary() throws Exception {
        Backends b = new Backends();
        b.router().text("x".repeat(240), "English", "简体中文", option("auto", false, true)); assertEquals(0, b.cloudCalls);
        b.router().text("x".repeat(239), "English", "简体中文", option("auto", true, true)); assertEquals(0, b.cloudCalls);
        var result = b.router().text("x".repeat(240), "English", "简体中文", option("auto", true, true)); assertEquals(1, b.cloudCalls); assertEquals("长段落规则", result.reason);
    }
    @Test public void automaticCloudFailureFallsBackToLocal() throws Exception {
        Backends b = new Backends(); b.cloudFails = true;
        var result = b.router().text("x".repeat(240), "English", "简体中文", option("auto", true, true));
        assertEquals(1, result.localCount); assertEquals(0, result.cloudCount); assertEquals(1, b.cloudCalls);
    }
    @Test public void llmModeDoesNotSilentlyReturnLocalOutput() throws Exception {
        Backends b = new Backends(); b.cloudFails = true;
        try { b.router().text("Hello", "English", "简体中文", option("llm", false, true)); fail(); } catch (Exception expected) { assertEquals("network", expected.getMessage()); }
        assertEquals(0, b.localCalls);
    }
    @Test public void cancellationDoesNotTriggerCloudFallback() throws Exception {
        AtomicBoolean cancelled = new AtomicBoolean(); Backends b = new Backends();
        TranslationRouter router = new TranslationRouter((text, source, target) -> { cancelled.set(true); throw new Exception("cancelled"); }, b.cloud(), () -> { if (cancelled.get()) throw new java.io.InterruptedIOException("cancelled"); });
        try { router.text("Hello", "English", "简体中文", option("auto", false, true)); fail(); } catch (java.io.InterruptedIOException expected) { }
        assertEquals(0, b.cloudCalls);
    }
    @Test public void invalidInputNeverReachesAnyEngine() throws Exception {
        Backends b = new Backends();
        try { b.router().blocks(Arrays.asList(block("same", "Hello"), block("same", "World")), "English", "简体中文", option("auto", true, true)); fail(); } catch (IllegalArgumentException expected) { }
        assertEquals(0, b.localCalls); assertEquals(0, b.cloudCalls);
    }
    @Test public void numericScreenOnlyRequiresNeitherEngine() throws Exception {
        Backends b = new Backends(); var result = b.router().blocks(Arrays.asList(block("a", "1.8K"), block("b", "@example")), "English", "简体中文", option("llm", false, false));
        assertEquals(0, b.localCalls); assertEquals(0, b.cloudCalls); assertEquals("1.8K", result.values.get("a"));
    }
    @Test public void longScreenRuleOnlySendsLongBlocks() throws Exception {
        Backends b = new Backends(); var result = b.router().blocks(Arrays.asList(block("short", "Hello"), block("long", "x".repeat(240))), "English", "简体中文", option("auto", true, true));
        assertEquals(1, b.sent.size()); assertEquals("long", b.sent.get(0).id); assertEquals(1, result.localCount);
    }
    @Test public void failedCloudBatchCanCompleteOfflineWithOriginalIds() throws Exception {
        Backends b = new Backends(); b.cloudFails = true;
        var result = b.router().blocks(Arrays.asList(block("a", "x".repeat(240)), block("b", "y".repeat(240))), "English", "简体中文", option("auto", true, true));
        assertEquals(Arrays.asList("a", "b"), new ArrayList<>(result.values.keySet())); assertEquals(2, result.localCount); assertEquals(0, result.cloudCount);
    }
    @Test public void failedSideLabelDoesNotDiscardOfflineArticle() throws Exception {
        Backends b = new Backends();
        var result = b.router().blocks(Arrays.asList(block("article", "Hello world"), block("label", "missing language pack"), block("count", "1.8K")),
                "自动检测", "简体中文", option("offline", false, true));
        assertEquals("local:Hello world", result.values.get("article")); assertEquals("missing language pack", result.values.get("label"));
        assertEquals(1, result.localCount); assertEquals(1, result.failedCount); assertEquals(0, b.cloudCalls);
        assertTrue(result.summary().contains("1 块未翻译")); assertFalse(result.warning.isEmpty());
    }
    @Test public void everyBlockFailingIsStillAnError() throws Exception {
        Backends b = new Backends(); b.localFails = true;
        try { b.router().blocks(Arrays.asList(block("body", "Hello"), block("clock", "12:35")), "English", "简体中文", option("offline", false, true)); fail(); }
        catch (Exception expected) { assertEquals("missing model", expected.getMessage()); }
        assertEquals(0, b.cloudCalls);
    }
    @Test public void cloudOutageDoesNotDiscardAlreadyTranslatedBlocks() throws Exception {
        Backends b = new Backends(); b.cloudFails = true;
        var result = b.router().blocks(Arrays.asList(block("body", "Hello"), block("label", "missing model")), "English", "简体中文", option("auto", false, true));
        assertEquals("local:Hello", result.values.get("body")); assertEquals(1, result.failedCount); assertEquals(0, result.cloudCount);
    }
    @Test public void statusBarAndCountersAreNotRecognizedProse() throws Exception {
        List<ScreenTextBlock> labels = Arrays.asList(block("time", "12:35"), block("counter", "1.8K"), block("handle", "@example"));
        assertEquals(0, BlockTranslationRules.translatableCount(labels));
        assertEquals(1, BlockTranslationRules.translatableCount(Arrays.asList(block("time", "12:35"), block("body", "The future is bright."))));
        assertEquals(0, BlockTranslationRules.translatableCount(Collections.emptyList()));
        var result = new Backends().router().blocks(labels, "English", "简体中文", option("llm", false, false));
        assertFalse("Empty recognition must not look like a successful zero-token translation", result.summary().contains("0 token"));
    }
}
