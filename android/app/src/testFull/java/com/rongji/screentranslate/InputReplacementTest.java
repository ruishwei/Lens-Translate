package com.rongji.screentranslate;

import org.junit.Test;
import static org.junit.Assert.*;

public class InputReplacementTest {
    @Test public void caretInMiddleStillTranslatesEntireField() {
        InputReplacement edit = new InputReplacement("你好，世界。", 2, 2);
        assertFalse(edit.selected); assertEquals("你好，世界。", edit.source); assertEquals("Hello, world.", edit.replaceWith("Hello, world."));
    }
    @Test public void selectedTextRetainsSurroundingDraftAndEmoji() {
        InputReplacement edit = new InputReplacement("🙂 你好，世界。 #draft", 6, 8);
        assertEquals("世界", edit.source); assertTrue(edit.selected); assertEquals("🙂 你好，world。 #draft", edit.replaceWith("world"));
    }
    @Test public void backwardsSelectionHasSameReplacementButTracksExactCursor() {
        InputReplacement edit = new InputReplacement("你好，世界。", 5, 3);
        assertEquals("世界", edit.source); assertEquals("你好，world。", edit.replaceWith("world"));
        assertTrue(edit.matches("你好，世界。", 5, 3)); assertFalse(edit.matches("你好，世界。", 3, 5));
    }
    @Test public void changedTextOrSelectionInvalidatesConfirmation() {
        InputReplacement edit = new InputReplacement("原始文字", 0, 4);
        assertFalse(edit.matches("新的文字", 0, 4)); assertFalse(edit.matches("原始文字", 0, 0)); assertTrue(edit.matches("原始文字", 0, 4));
    }
    @Test public void invalidOrUnknownOffsetsNeverBecomeFullFieldEdits() {
        for (int[] range : new int[][]{{-1,-1},{0,8},{2,-1}}) {
            try { new InputReplacement("文字", range[0], range[1]); fail(); } catch (IllegalArgumentException expected) { }
        }
        try { new InputReplacement("🙂文字", 1, 2); fail(); } catch (IllegalArgumentException expected) { }
    }
    @Test public void emptyAndOversizedDraftsDoNotReachTranslation() {
        for (String value : new String[]{"", "  ", "a".repeat(12_001)}) {
            try { new InputReplacement(value, 0, 0); fail(); } catch (IllegalArgumentException expected) { }
        }
    }
    @Test public void emptyTranslationCannotClearTheDraft() {
        InputReplacement edit = new InputReplacement("保留我", 0, 0);
        for (String value : new String[]{null, "", "  "}) { try { edit.replaceWith(value); fail(); } catch (IllegalArgumentException expected) { } }
    }
}
