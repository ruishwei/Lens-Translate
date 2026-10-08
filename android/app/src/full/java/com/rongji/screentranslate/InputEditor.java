package com.rongji.screentranslate;

/** A user-requested edit bound to one host editor. All text access runs on the input worker. */
interface InputEditor extends AutoCloseable {
    record State(String text, int start, int end) {
        InputReplacement snapshot() { return new InputReplacement(text, start, end); }
        boolean matches(InputReplacement edit) { return edit.matches(text, start, end); }
    }
    boolean current();
    State read() throws Exception;
    void replace(InputReplacement edit, String translation) throws Exception;
    default void close() { }
    static IllegalStateException changed() { return new IllegalStateException("原文、选区或输入框已改变，未替换。请重新翻译。"); }
}
