package com.rongji.screentranslate;

import org.junit.Test;
import java.io.*;
import static org.junit.Assert.*;

public class PinyinLexiconTest {
    private PinyinLexicon dictionary() throws Exception { return new PinyinLexicon(new InputStreamReader(new FileInputStream("src/full/assets/pinyin/pinyin_simp.dict.yaml"), java.nio.charset.StandardCharsets.UTF_8)); }
    @Test public void bundledDictionaryContainsEverydayWords() throws Exception {
        PinyinLexicon lexicon = dictionary();
        for (String[] pair : new String[][]{{"nihao","你好"},{"shijie","世界"},{"xiexie","谢谢"},{"zhongwen","中文"}})
            assertTrue(pair[0], lexicon.candidates(pair[0]).stream().anyMatch(c -> c.text().equals(pair[1]) && c.consumed() == pair[0].length()));
    }
    @Test public void phraseCompositionCanConsumeMultipleDictionaryWords() throws Exception {
        var result = dictionary().candidates("nihaoshijie");
        assertTrue(result.toString(), result.stream().anyMatch(c -> c.text().equals("你好世界") && c.consumed() == "nihaoshijie".length()));
    }
    @Test public void unfinishedSpellingKeepsUnconsumedTail() throws Exception {
        var result = dictionary().candidates("nihaosh");
        assertTrue(result.stream().anyMatch(c -> c.text().equals("你好") && c.consumed() == 5));
        assertTrue(dictionary().candidates("a".repeat(65)).isEmpty());
        assertTrue(dictionary().candidates("hello🙂").isEmpty());
    }
    @Test public void candidatesAreBoundedAndUnique() throws Exception {
        var result = dictionary().candidates("shi");
        assertTrue(result.size() <= 24); assertEquals(result.size(), result.stream().map(PinyinLexicon.Candidate::text).distinct().count());
    }
}
