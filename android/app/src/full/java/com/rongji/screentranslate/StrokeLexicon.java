package com.rongji.screentranslate;

import java.io.*;
import java.util.*;

/** Five-stroke lookup with prefix/wildcard matching; entirely immutable and on device. */
final class StrokeLexicon {
    private record Entry(String text, String code, int frequency) { }
    private final List<Entry> entries = new ArrayList<>();
    StrokeLexicon(Reader source, PinyinLexicon pinyin) throws IOException {
        try (BufferedReader reader = new BufferedReader(source)) {
            for (String line; (line = reader.readLine()) != null;) {
                if (line.startsWith("#")) continue;
                String[] fields = line.split("\t");
                if (fields.length < 2 || fields[0].codePointCount(0, fields[0].length()) != 1 || !fields[1].matches("[hspnz]+")) continue;
                entries.add(new Entry(fields[0], fields[1], pinyin.frequency(fields[0])));
            }
        }
        if (entries.isEmpty()) throw new IOException("笔画字库未能载入");
        entries.sort(Comparator.comparingInt(Entry::frequency).reversed().thenComparingInt(e -> e.code.length()).thenComparing(Entry::text));
    }
    List<PinyinLexicon.Candidate> candidates(String code) {
        if (code.isEmpty() || code.length() > 64 || !code.matches("[hspnz?]+")) return List.of();
        LinkedHashSet<String> exact = new LinkedHashSet<>(), partial = new LinkedHashSet<>();
        for (Entry entry : entries) {
            if (!matches(code, entry.code)) continue;
            if (code.length() == entry.code.length()) { if (exact.size() < 64) exact.add(entry.text); }
            else if (partial.size() < 64) partial.add(entry.text);
        }
        exact.addAll(partial);
        return exact.stream().limit(64).map(text -> new PinyinLexicon.Candidate(text, code.length())).collect(java.util.stream.Collectors.toList());
    }
    private boolean matches(String prefix, String code) {
        if (prefix.length() > code.length()) return false;
        for (int i = 0; i < prefix.length(); i++) if (prefix.charAt(i) != '?' && prefix.charAt(i) != code.charAt(i)) return false;
        return true;
    }
    static String display(String code) {
        return code.replace('h', '一').replace('s', '丨').replace('p', '丿').replace('n', '丶').replace('z', '乛');
    }
}
