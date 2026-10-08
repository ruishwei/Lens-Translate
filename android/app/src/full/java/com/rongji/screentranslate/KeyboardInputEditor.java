package com.rongji.screentranslate;

import android.os.Build;
import android.view.inputmethod.*;
import java.util.function.BooleanSupplier;

/** A connection belongs to one keyboard session, independent of accessibility/selection menus. */
final class KeyboardInputEditor implements InputEditor {
    private final InputConnection connection;
    private final BooleanSupplier active;
    KeyboardInputEditor(InputConnection connection, BooleanSupplier active) { this.connection = connection; this.active = active; }
    @Override public boolean current() { return active.getAsBoolean(); }
    @Override public State read() {
        if (!current()) throw InputEditor.changed();
        ExtractedTextRequest request = new ExtractedTextRequest(); request.hintMaxChars = TranslationRules.MAX_CHARACTERS + 1;
        // No GET_EXTRACTED_TEXT_MONITOR: text is requested only when the user taps Translate/Undo.
        ExtractedText value = connection.getExtractedText(request, 0);
        State result = null;
        if (value != null && value.text != null && value.startOffset == 0 && value.partialStartOffset < 0)
            result = new State(value.text.toString(), value.selectionStart, value.selectionEnd);
        if (result == null && Build.VERSION.SDK_INT >= 31) {
            SurroundingText surrounding = connection.getSurroundingText(TranslationRules.MAX_CHARACTERS + 1, TranslationRules.MAX_CHARACTERS + 1, 0);
            if (surrounding != null && surrounding.getOffset() == 0 && surrounding.getText() != null)
                result = new State(surrounding.getText().toString(), surrounding.getSelectionStart(), surrounding.getSelectionEnd());
        }
        if (!current()) throw InputEditor.changed();
        if (result == null) throw new IllegalStateException("此输入框没有提供完整文字和光标，无法安全替换。请在普通文字输入框重试。");
        return result;
    }
    @Override public void replace(InputReplacement edit, String translation) {
        edit.replaceWith(translation);
        if (!read().matches(edit)) throw InputEditor.changed();
        boolean selected = edit.selected ? connection.setSelection(edit.start, edit.end) : connection.performContextMenuAction(android.R.id.selectAll);
        // Read the actual selected text: an incomplete snapshot must never erase an unknown tail.
        CharSequence actual = selected ? connection.getSelectedText(0) : null;
        State state = read();
        if (!selected || actual == null || !edit.source.contentEquals(actual) || !state.text().equals(edit.original)
                || state.start() != edit.start || state.end() != edit.end) {
            if (current() && state.text().equals(edit.original)) connection.setSelection(edit.selectionStart, edit.selectionEnd);
            throw new IllegalStateException("输入框没有接受完整选区，未写入译文。请重新选择文字后重试。");
        }
        if (!current() || Thread.currentThread().isInterrupted()) throw InputEditor.changed();
        if (!connection.commitText(translation, 1)) throw new IllegalStateException("输入框拒绝写入译文，请检查原文后重试。");
    }
}
