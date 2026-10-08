package com.rongji.screentranslate;

import android.content.*;
import android.graphics.Color;
import android.inputmethodservice.InputMethodService;
import android.os.*;
import android.provider.Settings;
import android.text.InputType;
import android.view.*;
import android.view.inputmethod.*;
import android.widget.*;
import java.io.InputStreamReader;
import java.util.*;
import java.util.concurrent.*;

/** Local Pinyin/English IME. Translation always uses installed models, never the cloud router. */
public final class LensKeyboardService extends InputMethodService {
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService work = Executors.newSingleThreadExecutor();
    private final ExecutorService dictionaryWork = Executors.newSingleThreadExecutor();
    private final OfflineTranslator translator = new OfflineTranslator();
    private volatile long session, request;
    private volatile boolean active, destroyed;
    private Future<?> pending;
    private PinyinLexicon lexicon;
    private StrokeLexicon strokes;
    private AppPrefs preferences;
    private String layout = "pinyin", spellingFilter = "";
    private boolean predictionEnabled, noSuggestions;
    private String composing = "", target = "English";
    private List<PinyinLexicon.Candidate> candidates = List.of();
    private LinearLayout root, keys, candidateRow, languages, inputModes, spellingRow;
    private HorizontalScrollView spellingScroll, candidateScroll;
    private TextView status, compositionLabel;
    private Button translate, undo, targetButton, layoutButton, predictionButton;
    private ImageView attribution;
    private boolean chinese = true, symbols, shift, sensitive, numeric, busy;
    private int candidateGeneration;
    private InputEditor undoEditor;
    private InputReplacement undoSnapshot;
    private String undoText;
    private final Runnable forgetUndo = this::clearUndo;

    static boolean isSelected(Context context) {
        String selected = Settings.Secure.getString(context.getContentResolver(), Settings.Secure.DEFAULT_INPUT_METHOD);
        return new ComponentName(context, LensKeyboardService.class).flattenToShortString().equals(selected)
                || new ComponentName(context, LensKeyboardService.class).flattenToString().equals(selected);
    }
    @Override public void onCreate() {
        super.onCreate(); preferences = new AppPrefs(this); layout = preferences.keyboardLayout(); predictionEnabled = preferences.keyboardPrediction();
        target = preferences.writingTarget(); if ("繁體中文".equals(target)) target = "English";
        dictionaryWork.execute(() -> {
            try {
                PinyinLexicon loaded = new PinyinLexicon(new InputStreamReader(getAssets().open("pinyin/pinyin_simp.dict.yaml"), java.nio.charset.StandardCharsets.UTF_8));
                StrokeLexicon loadedStrokes = new StrokeLexicon(new InputStreamReader(getAssets().open("stroke/stroke.dict.yaml"), java.nio.charset.StandardCharsets.UTF_8), loaded);
                main.post(() -> { if (!destroyed) { lexicon = loaded; strokes = loadedStrokes; updateCandidates(); requestPredictions(); } });
            } catch (Exception ignored) { main.post(() -> message("本机词库载入失败，可切换英文输入。")); }
        });
    }
    @Override public View onCreateInputView() {
        root = Ui.column(this); root.setBackgroundColor(0xFFF0F1F7); root.setPadding(dp(4), dp(4), dp(4), dp(4));
        root.setOnApplyWindowInsetsListener((view, insets) -> {
            if (Build.VERSION.SDK_INT >= 30) {
                var bars = insets.getInsets(WindowInsets.Type.navigationBars()); view.setPadding(dp(4) + bars.left, dp(4), dp(4) + bars.right, dp(4) + bars.bottom);
            } else view.setPadding(dp(4) + insets.getSystemWindowInsetLeft(), dp(4), dp(4) + insets.getSystemWindowInsetRight(), dp(4) + insets.getSystemWindowInsetBottom());
            return insets;
        });
        LinearLayout toolbar = Ui.row(this);
        targetButton = key("English ▾", () -> { if (!commitComposition()) return; inputModes.setVisibility(View.GONE); languages.setVisibility(languages.getVisibility() == View.VISIBLE ? View.GONE : View.VISIBLE); });
        targetButton.setId(R.id.keyboard_target); toolbar.addView(targetButton, new LinearLayout.LayoutParams(0, dp(44), 1.1f));
        translate = key("翻译", this::translateInput); translate.setId(R.id.keyboard_translate); translate.setTextColor(Color.WHITE); translate.setBackground(Ui.rounded(this, Ui.ACCENT, 10, 0));
        toolbar.addView(translate, new LinearLayout.LayoutParams(0, dp(44), 1));
        undo = key("撤销", this::undoTranslation); undo.setId(R.id.keyboard_undo); toolbar.addView(undo, new LinearLayout.LayoutParams(0, dp(44), 0.8f));
        layoutButton = key("全拼 ▾", () -> { languages.setVisibility(View.GONE); inputModes.setVisibility(inputModes.getVisibility() == View.VISIBLE ? View.GONE : View.VISIBLE); });
        layoutButton.setId(R.id.keyboard_layout); layoutButton.setContentDescription("选择输入方式"); toolbar.addView(layoutButton, new LinearLayout.LayoutParams(0, dp(44), 1));
        Button switchIme = key("⌨", () -> getSystemService(InputMethodManager.class).showInputMethodPicker()); switchIme.setContentDescription("切换输入法");
        toolbar.addView(switchIme, new LinearLayout.LayoutParams(dp(44), dp(44))); root.addView(toolbar);
        inputModes = Ui.column(this); inputModes.setVisibility(View.GONE); root.addView(inputModes);
        LinearLayout modeRow = Ui.row(this); inputModes.addView(modeRow);
        String[] modeCodes = {"pinyin", "nine", "stroke"}, modeLabels = {"26键全拼", "九宫格拼音", "笔画输入"};
        for (int i = 0; i < modeCodes.length; i++) {
            final String chosen = modeCodes[i]; Button choice = key(modeLabels[i], () -> setLayout(chosen));
            modeRow.addView(choice, new LinearLayout.LayoutParams(0, dp(42), 1));
        }
        predictionButton = key("", () -> { predictionEnabled = !predictionEnabled; preferences.keyboardPrediction(predictionEnabled); updateToolbar(); updateCandidates(); requestPredictions(); });
        predictionButton.setId(R.id.keyboard_prediction); inputModes.addView(predictionButton, new LinearLayout.LayoutParams(-1, dp(40)));
        languages = Ui.column(this); languages.setVisibility(View.GONE); root.addView(languages);
        String[] targets = {"English", "简体中文", "日本語", "한국어", "Français", "Deutsch", "Español", "Русский"};
        for (int i = 0; i < targets.length; i += 4) {
            LinearLayout row = Ui.row(this); languages.addView(row);
            for (int j = i; j < Math.min(i + 4, targets.length); j++) {
                String language = targets[j]; Button choice = key(language, () -> { cancelTranslation(); clearUndo(); target = language; new AppPrefs(this).writingTarget(target); languages.setVisibility(View.GONE); updateToolbar(); });
                row.addView(choice, new LinearLayout.LayoutParams(0, dp(42), 1));
            }
        }
        LinearLayout statusRow = Ui.row(this);
        status = Ui.text(this, "本机输入 · 离线翻译", 11, Ui.MUTED, false); status.setId(R.id.keyboard_status); status.setMaxLines(2);
        statusRow.addView(status, new LinearLayout.LayoutParams(0, dp(34), 1));
        attribution = Ui.attribution(this); statusRow.addView(attribution, new LinearLayout.LayoutParams(dp(100), dp(18))); root.addView(statusRow);
        compositionLabel = Ui.text(this, "", 12, Ui.ACCENT, true); compositionLabel.setId(R.id.keyboard_composition); root.addView(compositionLabel);
        spellingScroll = new HorizontalScrollView(this); spellingScroll.setHorizontalScrollBarEnabled(false); spellingScroll.setVisibility(View.GONE);
        spellingRow = Ui.row(this); spellingScroll.addView(spellingRow); root.addView(spellingScroll, new LinearLayout.LayoutParams(-1, dp(34)));
        candidateScroll = new HorizontalScrollView(this); candidateScroll.setHorizontalScrollBarEnabled(false);
        candidateRow = Ui.row(this); candidateScroll.addView(candidateRow); root.addView(candidateScroll, new LinearLayout.LayoutParams(-1, dp(42)));
        keys = Ui.column(this); root.addView(keys); renderKeys(); updateToolbar(); updateCandidates();
        return root;
    }
    private int dp(int v) { return Ui.dp(this, v); }
    private Button key(String label, Runnable action) {
        Button button = new Button(this); button.setText(label); button.setAllCaps(false); button.setTextColor(Ui.NAVY); button.setTextSize(label.length() > 4 ? 11 : 16);
        button.setPadding(0, 0, 0, 0); button.setMinWidth(0); button.setMinimumWidth(0); button.setMinHeight(0); button.setMinimumHeight(0);
        button.setBackground(Ui.rounded(this, Color.WHITE, 7, 0)); button.setStateListAnimator(null); button.setOnClickListener(v -> action.run()); return button;
    }
    private void renderKeys() {
        if (keys == null) return; keys.removeAllViews();
        if (chinese && !symbols && !sensitive && !numeric && !"pinyin".equals(layout)) { renderCompactKeys(); updateToolbar(); return; }
        String[] rows = numeric ? new String[]{"123", "456", "789"} : symbols ? new String[]{"1234567890", "@#￥%&*()-", "!?/:;\"'"} : new String[]{"qwertyuiop", "asdfghjkl", "zxcvbnm"};
        for (String letters : rows) {
            LinearLayout row = Ui.row(this); row.setGravity(Gravity.CENTER); keys.addView(row);
            if (!numeric && letters.equals("zxcvbnm")) addKey(row, "⇧", () -> { if (!commitComposition()) return; if (chinese) { chinese = false; preferences.keyboardChinese(false); shift = true; } else shift = !shift; renderKeys(); updateCandidates(); }, 1.3f);
            for (char value : letters.toCharArray()) {
                String character = !chinese && shift ? String.valueOf(value).toUpperCase(Locale.ROOT) : String.valueOf(value);
                addKey(row, character, () -> type(character), 1);
            }
            if (letters.equals(rows[rows.length - 1])) {
                Button back = addKey(row, "⌫", this::backspace, 1.3f); back.setContentDescription("删除一个字符");
                back.setOnLongClickListener(v -> { for (int i = 0; i < 5; i++) backspace(); return true; });
            }
        }
        LinearLayout bottom = Ui.row(this); keys.addView(bottom);
        if (numeric) {
            addKey(bottom, ".", () -> type("."), 1); addKey(bottom, "0", () -> type("0"), 1); addKey(bottom, "-", () -> type("-"), 1);
        } else {
            addKey(bottom, symbols ? "返回" : "123", this::toggleSymbols, 1.1f);
            Button mode = addKey(bottom, chinese ? "中/英" : "英/中", this::toggleChinese, 1.1f); mode.setId(R.id.keyboard_mode);
            addKey(bottom, chinese ? "，" : ",", () -> type(chinese ? "，" : ","), 0.7f);
            Button space = addKey(bottom, chinese ? "空格 · 选词" : "space", this::space, 3); space.setId(R.id.keyboard_space);
            addKey(bottom, chinese ? "。" : ".", () -> type(chinese ? "。" : "."), 0.7f);
        }
        Button enter = addKey(bottom, actionLabel(), this::enter, 1.2f); enter.setId(R.id.keyboard_enter);
        updateToolbar();
    }
    private void setLayout(String chosen) {
        // Switching layouts is reversible: leave a nonempty composition in place until selected
        // or explicitly cleared, rather than silently converting digits/strokes into a guess.
        if (!composing.isEmpty()) { message("请先选词，或按“重输”清除当前编码，再切换输入方式。"); return; }
        typed(); layout = chosen; preferences.keyboardLayout(chosen); chinese = true; preferences.keyboardChinese(true); symbols = false; shift = false;
        inputModes.setVisibility(View.GONE); spellingFilter = ""; renderKeys(); updateCandidates(); requestPredictions();
    }
    private void toggleSymbols() { if (!commitComposition()) return; symbols = !symbols; renderKeys(); updateCandidates(); }
    private void toggleChinese() {
        if (!commitComposition()) return;
        chinese = !chinese; preferences.keyboardChinese(chinese); symbols = false; shift = false; renderKeys(); updateCandidates(); requestPredictions();
    }
    private void renderCompactKeys() {
        if ("nine".equals(layout)) {
            String[][] labels = {{"，", "2 ABC", "3 DEF", "⌫"}, {"4 GHI", "5 JKL", "6 MNO", "重输"}, {"7 PQRS", "8 TUV", "9 WXYZ", "。"}};
            for (String[] labelsRow : labels) {
                LinearLayout row = Ui.row(this); keys.addView(row);
                for (String label : labelsRow) {
                    Runnable action = label.equals("⌫") ? this::backspace : label.equals("重输") ? this::clearComposition
                            : Character.isDigit(label.charAt(0)) ? () -> compose(label.substring(0, 1)) : () -> type(label);
                    Button button = addKey(row, label, action, label.equals("⌫") || label.equals("重输") || label.equals("。") ? 0.75f : 1);
                    button.setTextSize(16);
                }
            }
        } else {
            String[][] labels = {{"一 横", "丨 竖", "丿 撇"}, {"丶 点捺", "乛 折", "? 通配"}, {"，", "重输", "⌫"}};
            String[][] codes = {{"h", "s", "p"}, {"n", "z", "?"}, {"", "", ""}};
            for (int i = 0; i < labels.length; i++) {
                LinearLayout row = Ui.row(this); keys.addView(row);
                for (int j = 0; j < labels[i].length; j++) {
                    String label = labels[i][j], code = codes[i][j];
                    addKey(row, label, !code.isEmpty() ? () -> compose(code) : label.equals("⌫") ? this::backspace : label.equals("重输") ? this::clearComposition : () -> type(label), 1);
                }
            }
        }
        LinearLayout bottom = Ui.row(this); keys.addView(bottom);
        addKey(bottom, "123", this::toggleSymbols, 1);
        Button mode = addKey(bottom, "中/英", this::toggleChinese, 1); mode.setId(R.id.keyboard_mode);
        Button space = addKey(bottom, "空格 · 选词", this::space, 2); space.setId(R.id.keyboard_space);
        Button enter = addKey(bottom, actionLabel(), this::enter, 1); enter.setId(R.id.keyboard_enter);
    }
    private void compose(String value) {
        InputConnection connection = getCurrentInputConnection(); if (connection == null || composing.length() >= 64 || sensitive) return;
        typed(); spellingFilter = ""; composing += value; connection.setComposingText(compositionText(), 1); updateCandidates();
    }
    private String compositionText() { return "stroke".equals(layout) ? StrokeLexicon.display(composing) : composing; }
    private void clearComposition() {
        InputConnection connection = getCurrentInputConnection(); if (connection == null || composing.isEmpty()) return;
        typed(); connection.commitText("", 1); composing = ""; spellingFilter = ""; updateCandidates();
    }
    private Button addKey(LinearLayout row, String label, Runnable action, float weight) {
        Button button = key(label, action); LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(0, dp(48), weight); p.setMargins(dp(2), dp(3), dp(2), dp(3)); row.addView(button, p); return button;
    }
    private void typed() { cancelTranslation(); clearUndo(); message(sensitive ? "安全输入 · 翻译已关闭" : "本机输入 · 离线翻译"); }
    private void type(String character) {
        InputConnection connection = getCurrentInputConnection(); if (connection == null) return; typed();
        if (chinese && !symbols && !sensitive && character.matches("[a-z']") && composing.length() < 64) {
            composing += character; connection.setComposingText(composing, 1); updateCandidates();
        } else { if (!commitComposition()) return; connection.commitText(character, 1); if (shift) { shift = false; renderKeys(); } updateCandidates(); }
    }
    private List<PinyinLexicon.Candidate> lookup(String code) {
        if (lexicon == null) return List.of();
        if ("nine".equals(layout)) return lexicon.nineKeyCandidates(code, spellingFilter);
        if ("stroke".equals(layout)) return strokes == null ? List.of() : strokes.candidates(code);
        return lexicon.candidates(code);
    }
    private void updateCandidates() {
        if (compositionLabel == null) return;
        compositionLabel.setText(compositionText()); compositionLabel.setVisibility(composing.isEmpty() ? View.GONE : View.VISIBLE);
        spellingScroll.setVisibility(!sensitive && "nine".equals(layout) && !composing.isEmpty() ? View.VISIBLE : View.GONE); spellingRow.removeAllViews();
        candidateRow.removeAllViews(); candidateScroll.scrollTo(0, 0); candidates = List.of(); final int generation = ++candidateGeneration;
        if (composing.isEmpty() || sensitive) {
            String hint = undoText != null ? "原文：" + undoText : sensitive ? "" : !chinese ? "英文输入" : symbols ? "数字与符号"
                    : "stroke".equals(layout) ? "按笔顺选字 · ? 代替不确定的一笔" : "nine".equals(layout) ? "九宫格拼音 · 点拼音筛选 · 点候选选词" : "全拼输入 · 点选候选词 · 空格选首词";
            TextView help = Ui.text(this, hint, 12, Ui.MUTED, false); help.setMaxLines(1); help.setEllipsize(android.text.TextUtils.TruncateAt.END); candidateRow.addView(help); return;
        }
        if (lexicon == null) { candidateRow.addView(Ui.text(this, "正在载入本机词库…", 13, Ui.MUTED, false)); return; }
        String spelling = composing;
        dictionaryWork.execute(() -> {
            List<PinyinLexicon.Candidate> found = lookup(spelling);
            List<String> filters = "nine".equals(layout) ? lexicon.nineKeySpellings(spelling) : List.of();
            main.post(() -> { if (destroyed || generation != candidateGeneration || !spelling.equals(composing)) return;
                candidates = displayableCandidates(found); candidateRow.removeAllViews();
                for (PinyinLexicon.Candidate candidate : candidates) { Button button = key(candidate.text(), () -> choose(candidate)); button.setTextSize(18); button.setPadding(dp(12), 0, dp(12), 0); candidateRow.addView(button, new LinearLayout.LayoutParams(-2, dp(40))); }
                if (candidates.isEmpty()) candidateRow.addView(Ui.text(this, "暂无候选，请删除或重输；编码不会自动发出。", 12, Ui.MUTED, false));
                if (!filters.isEmpty()) {
                    List<String> options = new ArrayList<>(); options.add(""); options.addAll(filters);
                    for (String filter : options) {
                        Button button = key(filter.isEmpty() ? "全部拼音" : filter, () -> { spellingFilter = filter; updateCandidates(); });
                        button.setSelected(filter.equals(spellingFilter));
                        button.setTextColor(filter.equals(spellingFilter) ? Ui.ACCENT : Ui.MUTED); button.setPadding(dp(10), 0, dp(10), 0);
                        spellingRow.addView(button, new LinearLayout.LayoutParams(-2, dp(32)));
                    }
                }
            });
        });
    }
    private List<PinyinLexicon.Candidate> displayableCandidates(List<PinyinLexicon.Candidate> input) {
        if (!"stroke".equals(layout)) return input;
        List<PinyinLexicon.Candidate> visible = new ArrayList<>();
        for (var candidate : input) if (compositionLabel.getPaint().hasGlyph(candidate.text())) visible.add(candidate);
        return visible;
    }
    private void choose(PinyinLexicon.Candidate candidate) {
        InputConnection connection = getCurrentInputConnection(); if (connection == null || candidate.consumed() > composing.length()) return;
        typed(); connection.commitText(candidate.text(), 1); composing = composing.substring(candidate.consumed());
        spellingFilter = ""; if (!composing.isEmpty()) connection.setComposingText(compositionText(), 1); updateCandidates(); requestPredictions();
    }
    private boolean commitComposition() {
        InputConnection connection = getCurrentInputConnection(); if (connection == null) return false;
        if (!composing.isEmpty()) {
            // Only commit a candidate covering the complete spelling; never silently drop its tail.
            List<PinyinLexicon.Candidate> current = candidates.isEmpty() && lexicon != null ? displayableCandidates(lookup(composing)) : candidates;
            if (!"pinyin".equals(layout) && (current.isEmpty() || current.get(0).consumed() != composing.length())) { message("请先选择候选字词或重输，未提交编码。"); return false; }
            String text = !current.isEmpty() && current.get(0).consumed() == composing.length() ? current.get(0).text() : composing;
            connection.commitText(text, 1); composing = ""; spellingFilter = ""; updateCandidates();
        }
        connection.finishComposingText(); return true;
    }
    private void space() { if (!composing.isEmpty() && !candidates.isEmpty()) choose(candidates.get(0)); else if (!composing.isEmpty()) commitComposition(); else type(" "); }
    private void backspace() {
        InputConnection connection = getCurrentInputConnection(); if (connection == null) return; typed();
        if (!composing.isEmpty()) { composing = composing.substring(0, composing.length() - 1); spellingFilter = ""; connection.setComposingText(compositionText(), 1); if (composing.isEmpty()) connection.finishComposingText(); updateCandidates(); }
        else { CharSequence selected = connection.getSelectedText(0); if (selected != null && selected.length() > 0) connection.commitText("", 1); else connection.deleteSurroundingTextInCodePoints(1, 0); updateCandidates(); }
        requestPredictions();
    }
    private void requestPredictions() {
        if (!active || destroyed || sensitive || noSuggestions || !predictionEnabled || !chinese || symbols || numeric || busy || undoText != null || !composing.isEmpty() || lexicon == null || candidateRow == null) return;
        final int generation = ++candidateGeneration; final long editorSession = session; final InputConnection connection = getCurrentInputConnection();
        if (connection == null) return;
        dictionaryWork.execute(() -> {
            // A bounded local context, used transiently. Never saved, logged or sent to a model.
            CharSequence selected = connection.getSelectedText(0), before = connection.getTextBeforeCursor(24, 0);
            if (before == null || (selected != null && selected.length() > 0)) return;
            String context = before.toString(); List<String> found = lexicon.predictions(context);
            main.post(() -> {
                if (destroyed || !active || editorSession != session || generation != candidateGeneration || !composing.isEmpty() || found.isEmpty()) return;
                candidateRow.removeAllViews(); candidateScroll.scrollTo(0, 0); candidateRow.addView(Ui.text(this, "联想 ", 11, Ui.MUTED, false));
                for (String suffix : found) {
                    Button button = key(suffix, () -> choosePrediction(connection, context, suffix, editorSession, generation));
                    button.setContentDescription("联想：" + suffix); button.setTextSize(18); button.setPadding(dp(12), 0, dp(12), 0);
                    candidateRow.addView(button, new LinearLayout.LayoutParams(-2, dp(40)));
                }
            });
        });
    }
    private void choosePrediction(InputConnection connection, String context, String suffix, long editorSession, int generation) {
        if (!active || destroyed || sensitive || !predictionEnabled || session != editorSession || generation != candidateGeneration || !composing.isEmpty()) return;
        CharSequence selected = connection.getSelectedText(0), before = connection.getTextBeforeCursor(24, 0);
        if (before == null || !context.contentEquals(before) || (selected != null && selected.length() > 0)) { updateCandidates(); requestPredictions(); return; }
        typed(); connection.commitText(suffix, 1); updateCandidates(); requestPredictions();
    }
    private int action() { EditorInfo info = getCurrentInputEditorInfo(); return info == null || (info.imeOptions & EditorInfo.IME_FLAG_NO_ENTER_ACTION) != 0 ? EditorInfo.IME_ACTION_NONE : info.imeOptions & EditorInfo.IME_MASK_ACTION; }
    private String actionLabel() { return switch (action()) { case EditorInfo.IME_ACTION_SEND -> "发送"; case EditorInfo.IME_ACTION_SEARCH -> "搜索"; case EditorInfo.IME_ACTION_NEXT -> "下一项"; case EditorInfo.IME_ACTION_DONE -> "完成"; case EditorInfo.IME_ACTION_GO -> "前往"; default -> "换行"; }; }
    private void enter() {
        InputConnection connection = getCurrentInputConnection(); if (connection == null) return;
        if (!composing.isEmpty()) { commitComposition(); return; }
        typed(); int action = action();
        // The only send/editor action is this explicit user press, never translation completion.
        if (action == EditorInfo.IME_ACTION_NONE || action == EditorInfo.IME_ACTION_UNSPECIFIED) connection.commitText("\n", 1);
        else connection.performEditorAction(action);
    }
    private void translateInput() {
        if (busy) { cancelTranslation(); message("已取消翻译，原输入保留"); return; }
        if (sensitive || !active) return;
        if (!composing.isEmpty() && lexicon == null) { message("拼音词库正在载入，请稍后选词再翻译。"); return; }
        if (!commitComposition()) return; clearUndo();
        InputConnection connection = getCurrentInputConnection(); if (connection == null) { message("请先点击输入框"); return; }
        final long editorSession = session, job = ++request; final String language = target;
        InputEditor editor = new KeyboardInputEditor(connection, () -> active && !destroyed && session == editorSession && request == job);
        busy = true; updateToolbar(); message("正在本机翻译，可继续编辑或点取消");
        pending = work.submit(() -> {
            try {
                InputReplacement original = editor.read().snapshot();
                String result = translator.translate(original.source, "自动检测", language, () -> check(editorSession, job));
                check(editorSession, job); if (!editor.read().matches(original)) throw InputEditor.changed();
                String expected = original.replaceWith(result);
                if (expected.length() > TranslationRules.MAX_CHARACTERS) throw new IllegalArgumentException("译文超过 12,000 字符，请分段翻译；原文未修改。");
                // Validate the undo span before writing, so a limit error cannot occur after a
                // successful edit and misleadingly report that the translation failed.
                InputReplacement inverse = new InputReplacement(expected, original.start, original.start + result.length());
                editor.replace(original, result);
                verify(editor, expected, editorSession, job);
                main.post(() -> {
                    if (!valid(editorSession, job)) return;
                    busy = false; pending = null;
                    undoEditor = editor; undoSnapshot = inverse; undoText = original.source;
                    main.removeCallbacks(forgetUndo); main.postDelayed(forgetUndo, 30000);
                    updateCandidates(); updateToolbar(); message("已替换 · 可撤销 · 点原应用发送");
                });
            } catch (Exception error) { main.post(() -> { if (valid(editorSession, job)) { busy = false; pending = null; updateToolbar(); message(error.getMessage()); } }); }
        });
    }
    private void undoTranslation() {
        if (undoEditor == null || undoSnapshot == null || busy) return;
        final InputEditor editor = undoEditor; final InputReplacement inverse = undoSnapshot; final String original = undoText;
        final long editorSession = session, job = request;
        clearUndo(); busy = true; updateToolbar();
        pending = work.submit(() -> {
            try {
                check(editorSession, job); InputEditor.State current = editor.read();
                // Compare the entire written draft before selecting the translated span.
                if (!current.text().equals(inverse.original)) throw InputEditor.changed();
                InputConnection connection = getCurrentInputConnection(); if (connection == null || !connection.setSelection(inverse.selectionStart, inverse.selectionEnd)) throw InputEditor.changed();
                editor.replace(inverse, original); verify(editor, inverse.replaceWith(original), editorSession, job);
                main.post(() -> { if (valid(editorSession, job)) { busy = false; updateToolbar(); message("已撤销翻译"); } });
            } catch (Exception error) { main.post(() -> { if (valid(editorSession, job)) { busy = false; updateToolbar(); message("内容已改变或输入框拒绝撤销，未覆盖新文字。"); } }); }
        });
    }
    private void verify(InputEditor editor, String expected, long editorSession, long job) throws Exception {
        for (int i = 0; i < 5; i++) { check(editorSession, job); if (editor.read().text().equals(expected)) return; Thread.sleep(80); }
        throw new IllegalStateException("输入框未完整写入译文，请检查内容后重试。");
    }
    private boolean valid(long editorSession, long job) { return !destroyed && active && session == editorSession && request == job; }
    private void check(long editorSession, long job) throws java.io.InterruptedIOException { if (!valid(editorSession, job) || Thread.currentThread().isInterrupted()) throw new java.io.InterruptedIOException("已取消"); }
    private void cancelTranslation() { request++; if (pending != null) { pending.cancel(true); pending = null; } busy = false; updateToolbar(); }
    private void clearUndo() { main.removeCallbacks(forgetUndo); undoEditor = null; undoSnapshot = null; undoText = null; updateToolbar(); if (composing.isEmpty() && !destroyed) updateCandidates(); }
    private void updateToolbar() {
        if (translate == null) return;
        translate.setEnabled(!sensitive); translate.setText(busy ? "取消" : "翻译"); targetButton.setText(target + " ▾"); targetButton.setEnabled(!sensitive && !busy);
        undo.setEnabled(!sensitive && !busy && undoSnapshot != null); attribution.setVisibility(sensitive ? View.GONE : View.VISIBLE);
        undo.setAlpha(undo.isEnabled() ? 1f : 0.35f); translate.setAlpha(translate.isEnabled() ? 1f : 0.35f); targetButton.setAlpha(targetButton.isEnabled() ? 1f : 0.35f);
        if (layoutButton != null) { layoutButton.setText(!chinese ? "英文 ▾" : "stroke".equals(layout) ? "笔画 ▾" : "nine".equals(layout) ? "九宫 ▾" : "全拼 ▾"); layoutButton.setEnabled(!sensitive && !numeric); }
        if (predictionButton != null) predictionButton.setText(predictionEnabled ? "本地联想：开" : "本地联想：关");
    }
    private void message(String text) { if (status != null && !destroyed) status.setText(text == null ? "输入框暂不可用，请重试" : text); }
    @Override public void onStartInput(EditorInfo info, boolean restarting) {
        super.onStartInput(info, restarting); session++; active = true; composing = ""; spellingFilter = ""; cancelTranslation(); clearUndo(); candidateGeneration++;
        int kind = info.inputType & InputType.TYPE_MASK_CLASS, variation = info.inputType & InputType.TYPE_MASK_VARIATION;
        sensitive = kind == InputType.TYPE_CLASS_TEXT && (variation == InputType.TYPE_TEXT_VARIATION_PASSWORD || variation == InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD || variation == InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD)
                || kind == InputType.TYPE_CLASS_NUMBER && variation == InputType.TYPE_NUMBER_VARIATION_PASSWORD;
        numeric = kind == InputType.TYPE_CLASS_NUMBER || kind == InputType.TYPE_CLASS_PHONE || kind == InputType.TYPE_CLASS_DATETIME;
        noSuggestions = (info.inputType & InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS) != 0;
        chinese = preferences.keyboardChinese() && !sensitive && !numeric && variation != InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS && variation != InputType.TYPE_TEXT_VARIATION_URI;
        symbols = false; shift = false;
        if (languages != null) languages.setVisibility(View.GONE);
        if (inputModes != null) inputModes.setVisibility(View.GONE);
        renderKeys(); updateCandidates(); updateToolbar(); message(sensitive ? "安全输入 · 翻译已关闭" : "本机输入 · 离线翻译");
        InputAssistService.refreshShortcut();
    }
    @Override public void onUpdateSelection(int oldStart, int oldEnd, int newStart, int newEnd, int candidatesStart, int candidatesEnd) {
        super.onUpdateSelection(oldStart, oldEnd, newStart, newEnd, candidatesStart, candidatesEnd);
        if (!composing.isEmpty() && (newStart != candidatesEnd || newEnd != candidatesEnd)) {
            composing = ""; spellingFilter = ""; updateCandidates(); InputConnection connection = getCurrentInputConnection(); if (connection != null) connection.finishComposingText();
        }
        if (composing.isEmpty()) { updateCandidates(); if (newStart == newEnd) requestPredictions(); }
    }
    @Override public boolean onEvaluateFullscreenMode() { return false; }
    @Override public void onFinishInput() { active = false; session++; cancelTranslation(); composing = ""; spellingFilter = ""; clearUndo(); updateCandidates(); super.onFinishInput(); }
    @Override public void onFinishInputView(boolean finishingInput) { active = false; cancelTranslation(); clearUndo(); candidateGeneration++; if (candidateRow != null) candidateRow.removeAllViews(); super.onFinishInputView(finishingInput); }
    @Override public void onStartInputView(EditorInfo info, boolean restarting) { super.onStartInputView(info, restarting); active = true; updateToolbar(); updateCandidates(); requestPredictions(); }
    @Override public void onDestroy() { destroyed = true; active = false; session++; cancelTranslation(); clearUndo(); main.removeCallbacksAndMessages(null); translator.close(); work.shutdownNow(); dictionaryWork.shutdownNow(); super.onDestroy(); }
}
