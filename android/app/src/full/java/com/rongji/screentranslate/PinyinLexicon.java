package com.rongji.screentranslate;

import java.io.*;
import java.util.*;

/** Immutable offline dictionary. No learning, history, network, or personal vocabulary storage. */
final class PinyinLexicon {
    record Candidate(String text, int consumed) { }
    private record Entry(String text, int weight) { }
    private record Spelling(String code, int weight) { }
    private final NavigableMap<String, List<Entry>> words = new TreeMap<>();
    private final NavigableMap<String, List<Spelling>> nineKey = new TreeMap<>();
    private final NavigableMap<String, Integer> vocabulary = new TreeMap<>();
    PinyinLexicon(Reader source) throws IOException {
        try (BufferedReader reader = new BufferedReader(source)) {
            for (String line; (line = reader.readLine()) != null;) {
                if (line.startsWith("#")) continue;
                String[] fields = line.split("\t"); if (fields.length < 3) continue;
                String code = fields[1].replace(" ", "").replace("ü", "v");
                if (!code.matches("[a-z]+") || code.length() > 64) continue;
                int weight; try { weight = Integer.parseInt(fields[2]); } catch (NumberFormatException ignored) { continue; }
                words.computeIfAbsent(code, k -> new ArrayList<>()).add(new Entry(fields[0], weight));
                vocabulary.merge(fields[0], weight, Math::max);
            }
        }
        for (var pair : words.entrySet()) {
            pair.getValue().sort(Comparator.comparingInt(Entry::weight).reversed());
            nineKey.computeIfAbsent(digits(pair.getKey()), k -> new ArrayList<>()).add(new Spelling(pair.getKey(), pair.getValue().get(0).weight));
        }
        for (var values : nineKey.values()) values.sort(Comparator.comparingInt(Spelling::weight).reversed());
        if (words.isEmpty()) throw new IOException("拼音词库未能载入");
    }
    List<Candidate> candidates(String spelling) {
        if (spelling.isEmpty() || spelling.length() > 64 || !spelling.matches("[a-z']+")) return List.of();
        String raw = spelling.replace("'", "");
        LinkedHashMap<String, Candidate> result = new LinkedHashMap<>();
        List<Entry> exact = words.get(raw);
        if (exact != null) for (Entry entry : exact) add(result, entry.text, spelling.length());
        // Segmentation permits whole short phrases without a cloud sentence model.
        String[] best = new String[raw.length() + 1]; double[] score = new double[best.length]; best[raw.length()] = "";
        for (int i = raw.length() - 1; i >= 0; i--) {
            score[i] = Double.NEGATIVE_INFINITY;
            for (int end = i + 1; end <= Math.min(raw.length(), i + 40); end++) {
                List<Entry> entries = words.get(raw.substring(i, end));
                if (entries == null || best[end] == null) continue;
                Entry entry = entries.get(0); double next = Math.log(entry.weight + 1.0) - 20 + score[end];
                if (next > score[i]) { score[i] = next; best[i] = entry.text + best[end]; }
            }
        }
        if (best[0] != null) add(result, best[0], spelling.length());
        // Offer a completed prefix so a user can choose a word and keep composing the remainder.
        for (int end = spelling.length() - 1; end > 0 && result.size() < 24; end--) {
            List<Entry> entries = words.get(spelling.substring(0, end).replace("'", ""));
            if (entries != null) for (Entry entry : entries.subList(0, Math.min(5, entries.size()))) add(result, entry.text, end);
        }
        if (result.isEmpty()) {
            List<Entry> prefixes = new ArrayList<>(); int visited = 0;
            for (var pair : words.tailMap(raw).entrySet()) {
                if (!pair.getKey().startsWith(raw) || visited++ >= 600) break;
                prefixes.add(pair.getValue().get(0));
            }
            prefixes.sort(Comparator.comparingInt(Entry::weight).reversed());
            for (Entry entry : prefixes) add(result, entry.text, spelling.length());
        }
        return new ArrayList<>(result.values());
    }
    private static void add(Map<String, Candidate> result, String text, int consumed) {
        if (result.size() < 24) result.putIfAbsent(text, new Candidate(text, consumed));
    }
    static String digits(String code) {
        StringBuilder result = new StringBuilder();
        for (char c : code.toCharArray()) {
            if (c < 'a' || c > 'z') throw new IllegalArgumentException("Invalid Pinyin");
            result.append("22233344455566677778889999".charAt(c - 'a'));
        }
        return result.toString();
    }
    List<String> nineKeySpellings(String code) {
        if (!validDigits(code)) return List.of();
        List<Spelling> matches = new ArrayList<>(nineKey.getOrDefault(code, List.of()));
        if (matches.isEmpty()) for (var pair : nineKey.tailMap(code).entrySet()) {
            if (!pair.getKey().startsWith(code)) break;
            matches.addAll(pair.getValue());
        }
        matches.sort(Comparator.comparingInt(Spelling::weight).reversed());
        return matches.stream().map(Spelling::code).distinct().limit(32).collect(java.util.stream.Collectors.toList());
    }
    List<Candidate> nineKeyCandidates(String code, String spelling) {
        if (!validDigits(code)) return List.of();
        LinkedHashMap<String, Candidate> result = new LinkedHashMap<>();
        if (!spelling.isEmpty()) {
            if (!spelling.matches("[a-z]+") || !digits(spelling).startsWith(code)) return List.of();
            for (Entry entry : words.getOrDefault(spelling, List.of())) add(result, entry.text, code.length());
            return new ArrayList<>(result.values());
        }
        List<Entry> exact = new ArrayList<>();
        for (Spelling option : nineKey.getOrDefault(code, List.of())) exact.addAll(words.get(option.code));
        exact.sort(Comparator.comparingInt(Entry::weight).reversed());
        for (Entry entry : exact) add(result, entry.text, code.length());
        // Decode complete short phrases even when they span multiple dictionary entries.
        String[] best = new String[code.length() + 1]; double[] score = new double[best.length]; best[code.length()] = "";
        for (int i = code.length() - 1; i >= 0; i--) {
            score[i] = Double.NEGATIVE_INFINITY;
            for (int end = i + 1; end <= Math.min(code.length(), i + 40); end++) {
                List<Spelling> options = nineKey.get(code.substring(i, end));
                if (options == null || best[end] == null) continue;
                Entry entry = words.get(options.get(0).code).get(0);
                double next = Math.log(entry.weight + 1.0) - 20 + score[end];
                if (next > score[i]) { score[i] = next; best[i] = entry.text + best[end]; }
            }
        }
        if (best[0] != null) add(result, best[0], code.length());
        for (int end = code.length() - 1; end > 0 && result.size() < 24; end--) {
            for (Spelling option : nineKey.getOrDefault(code.substring(0, end), List.of())) {
                for (Entry entry : words.get(option.code).subList(0, Math.min(3, words.get(option.code).size()))) add(result, entry.text, end);
            }
        }
        if (result.isEmpty()) for (String option : nineKeySpellings(code)) {
            for (Entry entry : words.get(option)) add(result, entry.text, code.length());
        }
        return new ArrayList<>(result.values());
    }
    private static boolean validDigits(String code) { return !code.isEmpty() && code.length() <= 64 && code.matches("[2-9]+"); }
    int frequency(String text) { return vocabulary.getOrDefault(text, 0); }
    /** Immutable dictionary continuations, not personal learning or a sentence generator. */
    List<String> predictions(String context) {
        if (context == null || context.isEmpty()) return List.of();
        int end = context.length(), start = end;
        while (start > 0 && end - start < 16 && Character.UnicodeScript.of(context.codePointBefore(start)) == Character.UnicodeScript.HAN)
            start -= Character.charCount(context.codePointBefore(start));
        if (start == end) return List.of();
        String tail = context.substring(start);
        LinkedHashSet<String> found = new LinkedHashSet<>();
        for (int offset = 0; offset < tail.length() && found.size() < 12; offset += Character.charCount(tail.codePointAt(offset))) {
            String prefix = tail.substring(offset); List<Map.Entry<String, Integer>> matches = new ArrayList<>();
            for (var pair : vocabulary.tailMap(prefix).entrySet()) {
                if (!pair.getKey().startsWith(prefix)) break;
                if (pair.getKey().length() > prefix.length()) matches.add(pair);
            }
            matches.sort(Map.Entry.<String, Integer>comparingByValue().reversed());
            for (var match : matches) { found.add(match.getKey().substring(prefix.length())); if (found.size() == 12) break; }
            // Once a word matches, do not dilute its suggestions with unrelated suffixes of
            // the last character (e.g. 天气 -> 预报 must not also suggest 气 -> 氛).
            if (!found.isEmpty()) break;
        }
        return new ArrayList<>(found);
    }
}
