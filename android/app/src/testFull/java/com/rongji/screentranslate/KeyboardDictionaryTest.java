package com.rongji.screentranslate;

import org.junit.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import static org.junit.Assert.*;

public class KeyboardDictionaryTest {
    private static PinyinLexicon pinyin;
    private static StrokeLexicon strokes;
    @BeforeClass public static void loadRealDictionaries() throws Exception {
        pinyin = new PinyinLexicon(reader("pinyin/pinyin_simp.dict.yaml"));
        strokes = new StrokeLexicon(reader("stroke/stroke.dict.yaml"), pinyin);
    }
    private static Reader reader(String file) throws Exception { return new InputStreamReader(new FileInputStream("src/full/assets/" + file), StandardCharsets.UTF_8); }
    @Test public void nineKeysDisambiguateRealEverydayWords() {
        for (String[] pair : new String[][]{{"nihao","你好"},{"shijie","世界"},{"xiexie","谢谢"},{"tianqi","天气"}}) {
            String digits = PinyinLexicon.digits(pair[0]);
            assertTrue(pair[1], pinyin.nineKeyCandidates(digits, "").stream().anyMatch(c -> c.text().equals(pair[1]) && c.consumed() == digits.length()));
            assertTrue(pinyin.nineKeySpellings(digits).contains(pair[0]));
        }
    }
    @Test public void nineKeySpellingFilterPreservesAlternatives() {
        assertTrue(pinyin.nineKeySpellings("64").contains("ni"));
        assertTrue(pinyin.nineKeySpellings("64").contains("mi"));
        assertTrue(pinyin.nineKeyCandidates("64", "ni").stream().anyMatch(c -> c.text().equals("你")));
        assertFalse(pinyin.nineKeyCandidates("64", "mi").stream().anyMatch(c -> c.text().equals("你")));
        assertTrue(pinyin.nineKeyCandidates("64", "hao").isEmpty());
    }
    @Test public void nineKeyPrefixLeavesUnfinishedDigits() {
        String digits = PinyinLexicon.digits("nihao") + "7";
        assertTrue(pinyin.nineKeyCandidates(digits, "").stream().anyMatch(c -> c.text().equals("你好") && c.consumed() == 5));
    }
    @Test public void invalidOrExcessiveCodesAreRejected() {
        for (String code : new String[]{"", "0123", "64426🙂", "2".repeat(65)}) assertTrue(pinyin.nineKeyCandidates(code, "").isEmpty());
        for (String code : new String[]{"", "123", "hspx", "h".repeat(65)}) assertTrue(strokes.candidates(code).isEmpty());
    }
    @Test public void bundledStrokeDataSupportsSimplifiedAndAlternativeStrokeOrders() {
        for (String[] pair : new String[][]{{"pspzzpn","你"},{"pspzspn","你"},{"zphzzh","好"},{"zphzsh","好"},{"szhs","中"},{"szhhshnh","国"},{"hspn","木"}})
            assertTrue(pair[1] + pair[0], strokes.candidates(pair[0]).stream().anyMatch(c -> c.text().equals(pair[1])));
    }
    @Test public void strokePrefixesAndWildcardRemainBoundedAndUnique() {
        assertTrue(strokes.candidates("hsp?").stream().anyMatch(c -> c.text().equals("木")));
        assertTrue(strokes.candidates("hsp").stream().anyMatch(c -> c.text().equals("木")));
        var result = strokes.candidates("?"); assertTrue(result.size() <= 64);
        assertEquals(result.size(), result.stream().map(PinyinLexicon.Candidate::text).distinct().count());
        assertEquals("一丨丿丶乛?", StrokeLexicon.display("hspnz?"));
    }
    @Test public void staticPredictionsUseLongestContextAndOnlyAppendSuffix() {
        assertTrue(pinyin.predictions("天气").contains("预报"));
        assertFalse("Do not mix unrelated single-character continuations", pinyin.predictions("天气").contains("氛"));
        assertTrue(pinyin.predictions("你好").contains("吗"));
        assertTrue(pinyin.predictions("今天北京").contains("大学"));
        assertTrue(pinyin.predictions("谢谢").contains("大家"));
        assertTrue(pinyin.predictions("天气。").isEmpty());
        assertTrue(pinyin.predictions("hello").isEmpty());
        assertTrue(pinyin.predictions("天气").size() <= 12);
    }
    @Test public void predictionsAndNumericCandidatesAreDeterministicWithoutLearning() {
        assertEquals(pinyin.predictions("天气"), pinyin.predictions("天气"));
        var result = pinyin.nineKeyCandidates("744", ""); assertTrue(result.size() <= 24);
        assertEquals(result.size(), result.stream().map(PinyinLexicon.Candidate::text).distinct().count());
    }
}
