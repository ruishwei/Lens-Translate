package com.rongji.screentranslate;

/** Bounded user-requested edit. Android selection offsets are UTF-16 indices. */
final class InputReplacement {
    final String original, source;
    final int selectionStart, selectionEnd, start, end;
    final boolean selected;
    InputReplacement(String original, int selectionStart, int selectionEnd) {
        if (original == null || original.trim().isEmpty()) throw new IllegalArgumentException("请先在原应用的输入框中输入文字。");
        if (original.length() > TranslationRules.MAX_CHARACTERS) throw new IllegalArgumentException("输入框超过 12,000 字符，请分段处理。");
        if (selectionStart < 0 || selectionEnd < 0 || selectionStart > original.length() || selectionEnd > original.length())
            throw new IllegalArgumentException("应用没有提供有效的光标位置，请重新点击输入框后重试。");
        this.original = original; this.selectionStart = selectionStart; this.selectionEnd = selectionEnd;
        selected = selectionStart != selectionEnd;
        start = selected ? Math.min(selectionStart, selectionEnd) : 0;
        end = selected ? Math.max(selectionStart, selectionEnd) : original.length();
        if (splitsPair(original, start) || splitsPair(original, end)) throw new IllegalArgumentException("选区截断了一个字符，请重新选择。");
        source = original.substring(start, end); TranslationRules.validate(source);
    }
    boolean matches(String text, int from, int to) { return original.equals(text) && selectionStart == from && selectionEnd == to; }
    String replaceWith(String translation) {
        if (translation == null || translation.trim().isEmpty()) throw new IllegalArgumentException("译文为空，未修改输入框。");
        String result = original.substring(0, start) + translation + original.substring(end);
        if (result.length() > 24_000) throw new IllegalArgumentException("译文过长，未修改输入框。");
        return result;
    }
    private static boolean splitsPair(String value, int offset) {
        return offset > 0 && offset < value.length() && Character.isHighSurrogate(value.charAt(offset - 1)) && Character.isLowSurrogate(value.charAt(offset));
    }
}
