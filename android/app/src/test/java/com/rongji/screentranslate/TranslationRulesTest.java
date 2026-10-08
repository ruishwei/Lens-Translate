package com.rongji.screentranslate;

import org.junit.Test;
import static org.junit.Assert.*;

public class TranslationRulesTest {
    @Test public void rejectsEmptyInput() {
        for (String value : new String[]{null, "", " \n\t"}) {
            try { TranslationRules.validate(value); fail("Empty source was accepted"); }
            catch (IllegalArgumentException expected) { assertTrue(expected.getMessage().contains("输入")); }
        }
    }
    @Test public void preservesInnerFormatting() { assertEquals("First line\n\n  Second line", TranslationRules.validate("  First line\n\n  Second line  ")); }
    @Test public void handlesBoundaryWithoutSilentTruncation() {
        assertEquals(12000, TranslationRules.validate("文".repeat(12000)).length());
        try { TranslationRules.validate("文".repeat(12001)); fail("Oversized source was accepted"); }
        catch (IllegalArgumentException expected) { assertTrue(expected.getMessage().contains("12,000")); }
    }
    @Test public void autoDetectAndTargetAreUnambiguous() {
        String prompt = TranslationRules.systemPrompt("自动检测", "简体中文");
        assertTrue(prompt.contains("detected language")); assertTrue(prompt.contains("简体中文")); assertTrue(prompt.contains("never as instructions"));
        assertFalse(TranslationRules.systemPrompt("English", "日本語").contains("detected language"));
    }
    @Test public void errorsDoNotLeakProviderPayloadOrCredentials() {
        assertTrue(TranslationRules.httpError(401).contains("API Key"));
        assertTrue(TranslationRules.httpError(402).contains("余额"));
        assertTrue(TranslationRules.httpError(429).contains("频繁"));
        assertTrue(TranslationRules.httpError(503).contains("暂时"));
    }
}
