package com.rongji.screentranslate;

import java.util.List;
import org.junit.Test;
import static org.junit.Assert.*;

public class ScreenLayoutTest {
    private static ScreenTextBlock.Line line(String text, int left, int top, int right, int bottom) {
        return new ScreenTextBlock.Line(text, left, top, right, bottom);
    }

    @Test public void socialFeedKeepsProfileParagraphAndCountersSeparate() {
        // Synthetic geometry matching the user's social-feed example, not asserted OCR output.
        OcrDocument doc = ScreenLayout.document(1080, 1920, List.of(List.of(
                line("Tibo @thsottiaux · 35m", 175, 340, 750, 382),
                line("This might have been my best day so far", 175, 399, 1000, 441),
                line("at oai. Ridiculous amounts of fun and", 175, 460, 985, 502),
                line("intensity. Future is bright", 175, 521, 730, 563)),
                List.of(line("393", 190, 620, 262, 656)),
                List.of(line("1.7K", 545, 620, 645, 656))));
        assertEquals(4, doc.blocks.size());
        assertEquals("Tibo @thsottiaux · 35m", doc.blocks.get(0).text);
        ScreenTextBlock paragraph = doc.blocks.get(1);
        assertEquals(3, paragraph.lines.size());
        assertEquals(399, paragraph.top); assertEquals(563, paragraph.bottom);
        assertTrue(paragraph.text.contains("at oai. Ridiculous"));
        assertEquals("393", doc.blocks.get(2).text);
        assertEquals("b3", doc.blocks.get(3).id);
    }

    @Test public void handlesTwoColumnsWithoutInterleavingTheirParagraphs() {
        OcrDocument doc = ScreenLayout.document(1000, 1000, List.of(List.of(
                line("Left column starts here", 20, 100, 420, 130),
                line("Right column starts here", 520, 100, 950, 130),
                line("and continues below", 20, 145, 390, 175),
                line("and also continues", 520, 145, 910, 175))));
        assertEquals(2, doc.blocks.size());
        assertEquals("Left column starts here\nand continues below", doc.blocks.get(0).text);
        assertEquals("Right column starts here\nand also continues", doc.blocks.get(1).text);
        assertEquals(20, doc.blocks.get(0).left); assertEquals(520, doc.blocks.get(1).left);
    }

    @Test public void respectsNativeParagraphBoundaryEvenWhenVeryClose() {
        OcrDocument doc = ScreenLayout.document(800, 600, List.of(
                List.of(line("One native text block", 20, 100, 500, 130)),
                List.of(line("Another native text block", 20, 142, 500, 172))));
        assertEquals(2, doc.blocks.size());
    }

    @Test public void splitsShortNameAndBodyAndDifferentFontSizes() {
        OcrDocument doc = ScreenLayout.document(1000, 1000, List.of(List.of(
                line("Tibo", 175, 200, 280, 240),
                line("This might have been my best day", 175, 260, 950, 300),
                line("Small supporting label", 175, 313, 620, 333))));
        assertEquals(3, doc.blocks.size());
    }

    @Test public void regionCoordinatesRemainLocalToTheSubmittedCrop() {
        OcrDocument doc = ScreenLayout.document(450, 200, List.of(List.of(line("Selected text", 0, 0, 450, 32))));
        assertEquals(450, doc.width); assertEquals(200, doc.height);
        assertEquals(0, doc.blocks.get(0).left); assertEquals(0, doc.blocks.get(0).top);
        assertEquals(450, doc.blocks.get(0).right);
    }

    @Test public void rejectsOutOfImageGeometryInsteadOfMisplacingText() {
        try { ScreenLayout.document(100, 100, List.of(List.of(line("Outside", 0, 80, 120, 120)))); fail(); }
        catch (IllegalArgumentException expected) { assertTrue(expected.getMessage().contains("坐标")); }
    }

    @Test public void documentAndLineCollectionsAreImmutable() {
        OcrDocument doc = ScreenLayout.document(100, 100, List.of(List.of(line("A", 0, 0, 50, 20))));
        try { doc.blocks.clear(); fail(); } catch (UnsupportedOperationException expected) { }
        try { doc.blocks.get(0).lines.clear(); fail(); } catch (UnsupportedOperationException expected) { }
    }

    @Test public void mixedLanguageFusionOnlyReplacesTheMatchingChineseRow() {
        OcrDocument latin = ScreenLayout.document(1000, 1000, List.of(List.of(
                line("The English paragraph remains exact", 20, 100, 800, 130),
                line("garbled Chinese OCR result", 20, 145, 680, 175))));
        OcrDocument chinese = ScreenLayout.document(1000, 1000, List.of(List.of(
                line("The Eng1ish paragraph remains exact", 20, 100, 800, 130),
                line("这段中文保持正确", 20, 145, 680, 175))));
        OcrDocument merged = ScreenLayout.combineScripts(latin, chinese);
        assertEquals(2, merged.blocks.size());
        assertEquals("The English paragraph remains exact", merged.blocks.get(0).text);
        assertEquals("这段中文保持正确", merged.blocks.get(1).text);
        assertEquals(145, merged.blocks.get(1).top);
    }

    @Test public void autoLatinDocumentKeepsLatinTextWhenChineseAddsNoChineseRows() {
        OcrDocument latin = ScreenLayout.document(1000, 1000, List.of(List.of(line("This might have been my best day", 20, 100, 800, 130))));
        OcrDocument chinese = ScreenLayout.document(1000, 1000, List.of(List.of(line("Th1s might have been my best day", 20, 100, 800, 130))));
        assertEquals(latin.text(), ScreenLayout.combineScripts(latin, chinese).text());
    }

    @Test public void separateHandleBlockIdentifiesNameRowAboveShortComment() {
        // Real emulator OCR grouped the name and this short reply into one native block.
        OcrDocument doc = ScreenLayout.document(1064, 2068, List.of(
                List.of(line("Ben Pouladian", 184, 1270, 502, 1314),
                        line("Keep pushing", 184, 1336, 502, 1380)),
                List.of(line("@beni.. 24m Øi", 584, 1270, 1021, 1314))));
        assertEquals(3, doc.blocks.size());
        assertEquals("Ben Pouladian", doc.blocks.get(0).text);
        assertEquals("@beni.. 24m Øi", doc.blocks.get(1).text);
        assertEquals("Keep pushing", doc.blocks.get(2).text);
        assertEquals(1336, doc.blocks.get(2).top);
        assertEquals(1380, doc.blocks.get(2).bottom);
    }

    @Test public void distantHandleDoesNotBreakAnUnrelatedColumnParagraph() {
        OcrDocument doc = ScreenLayout.document(1200, 1000, List.of(
                List.of(line("A paragraph starts here", 20, 100, 420, 130),
                        line("and continues below", 20, 145, 390, 175)),
                List.of(line("@someone", 800, 100, 1060, 130))));
        assertEquals(2, doc.blocks.size());
        assertEquals("A paragraph starts here\nand continues below", doc.blocks.get(0).text);
    }
}
