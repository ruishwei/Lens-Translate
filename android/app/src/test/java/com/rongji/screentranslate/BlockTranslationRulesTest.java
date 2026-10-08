package com.rongji.screentranslate;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.Test;
import static org.junit.Assert.*;

public class BlockTranslationRulesTest {
    private static ScreenTextBlock block(String id, String text) { return new ScreenTextBlock(id, text, 0, 0, 100, 20, List.of()); }
    private static BlockTranslationRules.Result result(String id, String text) { return new BlockTranslationRules.Result(id, text); }
    private static void rejected(List<ScreenTextBlock> input, List<BlockTranslationRules.Result> output) {
        try { BlockTranslationRules.validateResponse(input, output); fail("Invalid mapping was accepted"); }
        catch (IllegalArgumentException expected) { assertTrue(expected.getMessage().contains("对应")); }
    }

    @Test public void reorderedResponseMapsByIdAndRetainsSourceOrder() {
        List<ScreenTextBlock> input = List.of(block("b0", "Hello"), block("b1", "Goodbye"));
        Map<String, String> output = BlockTranslationRules.validateResponse(input, List.of(result("b1", "再见"), result("b0", "你好")));
        assertEquals("你好", output.get("b0")); assertEquals("再见", output.get("b1"));
        assertEquals(List.of("b0", "b1"), new ArrayList<>(output.keySet()));
    }

    @Test public void rejectsMissingUnknownDuplicateAndEmptyResponses() {
        List<ScreenTextBlock> input = List.of(block("b0", "Hello"), block("b1", "Goodbye"));
        rejected(input, List.of(result("b0", "你好")));
        rejected(input, List.of(result("b0", "你好"), result("wrong", "再见")));
        rejected(input, List.of(result("b0", "你好"), result("b0", "重复")));
        rejected(input, List.of(result("b0", "你好"), result("b1", " \n")));
    }

    @Test public void preservesEmbeddedNewlinesWithinTheCorrectBlock() {
        Map<String, String> output = BlockTranslationRules.validateResponse(List.of(block("b5", "First\nSecond")), List.of(result("b5", "第一行\n第二行")));
        assertEquals("第一行\n第二行", output.get("b5"));
    }

    @Test public void preservesHandlesUrlsCountersAndSymbolsButNotProse() {
        for (String text : new String[]{"@thsottiaux", "1.7K", "35m", "27.6k", "+", "11:27", "$12.50", "https://example.com/a", "person@example.com"})
            assertTrue(text, BlockTranslationRules.preserveOriginal(text));
        for (String text : new String[]{"This might have been my best day", "Future is bright", "LIVE", "October 9", "Keep pushing", "2 cups a day"})
            assertFalse(text, BlockTranslationRules.preserveOriginal(text));
    }

    @Test public void capsBatchSizeWithoutDroppingOrRenumberingBlocks() {
        List<ScreenTextBlock> input = new ArrayList<>();
        for (int i = 0; i < 50; i++) input.add(block("b" + i, "Translate this sentence"));
        List<List<ScreenTextBlock>> batches = BlockTranslationRules.batches(input);
        assertEquals(3, batches.size()); assertEquals(24, batches.get(0).size());
        assertEquals("b24", batches.get(1).get(0).id); assertEquals("b49", batches.get(2).get(1).id);
    }

    @Test public void batchesByCharactersAndExcludesIdentityTokens() {
        List<List<ScreenTextBlock>> batches = BlockTranslationRules.batches(List.of(block("b0", "@alice"),
                block("b1", "a".repeat(3000)), block("b2", "b".repeat(2500))));
        assertEquals(2, batches.size()); assertEquals("b1", batches.get(0).get(0).id); assertEquals("b2", batches.get(1).get(0).id);
    }

    @Test public void refusesOverLimitAndDuplicateRequestsWithoutSilentTruncation() {
        for (List<ScreenTextBlock> blocks : List.of(List.of(block("b0", "a".repeat(5001))),
                List.of(block("b0", "a".repeat(4500)), block("b1", "b".repeat(4500)), block("b2", "c".repeat(4000))),
                List.of(block("b0", "Hello"), block("b0", "World")))) {
            try { BlockTranslationRules.batches(blocks); fail(); } catch (IllegalArgumentException expected) { }
        }
    }

    @Test public void refusesTooManyBlocks() {
        List<ScreenTextBlock> blocks = new ArrayList<>();
        for (int i = 0; i <= BlockTranslationRules.MAX_BLOCKS; i++) blocks.add(block("b" + i, "text"));
        try { BlockTranslationRules.batches(blocks); fail(); } catch (IllegalArgumentException expected) { }
    }
}
