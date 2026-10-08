package com.rongji.screentranslate;

import android.app.Activity;
import android.content.*;
import android.os.Bundle;
import android.view.*;
import android.widget.*;
import java.util.concurrent.*;

/** Exact selected text in, explicitly confirmed replacement out; never sends the host form. */
public class ProcessTextActivity extends Activity {
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private TranslationEngine client;
    private AppPrefs prefs;
    private String original = "", translated = "", lastSource, lastTarget;
    private TextView output, status;
    private Button retry, replace, improve;
    private Spinner source, target;
    private android.widget.ImageView attribution;
    private boolean busy, editable, writing;
    private int generation;

    @Override public void onCreate(Bundle state) {
        super.onCreate(state); setResult(RESULT_CANCELED); client = new TranslationEngine(this); prefs = new AppPrefs(this);
        editable = Intent.ACTION_PROCESS_TEXT.equals(getIntent().getAction()) && !getIntent().getBooleanExtra(Intent.EXTRA_PROCESS_TEXT_READONLY, true);
        writing = editable || this instanceof InputShareActivity;
        CharSequence selection = getIntent().getCharSequenceExtra(Intent.EXTRA_PROCESS_TEXT);
        if (this instanceof InputShareActivity) selection = getIntent().getCharSequenceExtra(Intent.EXTRA_TEXT);
        original = state == null ? (selection == null ? "" : selection.toString()) : state.getString("original", "");
        translated = state == null ? "" : state.getString("translated", "");
        lastSource = state == null ? (writing ? "自动检测" : prefs.source()) : state.getString("from", "自动检测");
        lastTarget = state == null ? (writing ? prefs.writingTarget() : prefs.target()) : state.getString("to", "English");
        ScrollView scroll = new ScrollView(this); LinearLayout page = Ui.column(this);
        page.setPadding(Ui.dp(this, 20), Ui.dp(this, 16), Ui.dp(this, 20), Ui.dp(this, 16)); scroll.addView(page);
        LinearLayout header = Ui.row(this);
        header.addView(Ui.text(this, writing ? "输入翻译" : "选中文字翻译", 22, Ui.NAVY, true), new LinearLayout.LayoutParams(0, -2, 1));
        Button close = Ui.button(this, "关闭", false); close.setOnClickListener(v -> finish()); header.addView(close); page.addView(header);
        Ui.add(page, Ui.text(this, editable ? "仅翻译选中部分，确认后替换；不会自动发送消息。" : writing
                ? "来自其他应用的分享。确认后复制并返回；重新选中原文，再粘贴替换。" : "直接使用选中文字，无需 OCR。", 12, Ui.ACCENT, false), 8);
        Ui.add(page, Ui.text(this, "原文", 12, Ui.MUTED, true), 12);
        TextView originalView = Ui.text(this, original, 15, Ui.NAVY, false); originalView.setTextIsSelectable(true);
        originalView.setMaxHeight(Ui.dp(this, 120)); originalView.setMovementMethod(new android.text.method.ScrollingMovementMethod()); Ui.add(page, originalView, 6);
        LinearLayout languages = Ui.row(this);
        source = Ui.spinner(this, TranslationRules.SOURCES, lastSource); target = Ui.spinner(this, TranslationRules.TARGETS, lastTarget);
        source.setContentDescription("选中文字的原文语言"); target.setContentDescription(writing ? "写作目标语言" : "本次目标语言");
        source.setId(R.id.selection_source); target.setId(R.id.selection_target);
        languages.addView(source, new LinearLayout.LayoutParams(0, Ui.dp(this, 48), 1)); languages.addView(Ui.text(this, "→", 16, Ui.ACCENT, true));
        languages.addView(target, new LinearLayout.LayoutParams(0, Ui.dp(this, 48), 1)); Ui.add(page, languages, 12);
        output = Ui.text(this, translated, 18, Ui.NAVY, false); output.setId(R.id.translated_text); output.setTextIsSelectable(true);
        output.setMinHeight(Ui.dp(this, 64)); Ui.add(page, output, 8);
        attribution = Ui.attribution(this); attribution.setVisibility(state != null && state.getBoolean("offline") ? View.VISIBLE : View.GONE); Ui.add(page, attribution, 4);
        status = Ui.text(this, TranslationRouter.label(prefs.engineMode()), 12, Ui.MUTED, false); status.setId(R.id.translation_status); Ui.add(page, status, 8);
        LinearLayout actions = Ui.row(this);
        retry = Ui.button(this, "翻译", true); retry.setOnClickListener(v -> translate(prefs.engineMode())); actions.addView(retry, new LinearLayout.LayoutParams(0, -2, 1));
        Button copy = Ui.button(this, "复制译文", false); copy.setOnClickListener(v -> {
            if (translated.isEmpty()) return;
            getSystemService(ClipboardManager.class).setPrimaryClip(ClipData.newPlainText("译镜", translated)); Toast.makeText(this, "已复制译文", Toast.LENGTH_SHORT).show();
        });
        LinearLayout.LayoutParams copyParams = new LinearLayout.LayoutParams(0, -2, 1); copyParams.leftMargin = Ui.dp(this, 8); actions.addView(copy, copyParams); Ui.add(page, actions, 12);
        if (writing) {
            replace = Ui.button(this, editable ? "用译文替换选中文字" : "确认复制译文并返回", true); replace.setEnabled(!translated.isEmpty()); replace.setId(R.id.replace_selection);
            replace.setOnClickListener(v -> {
                if (translated.isEmpty() || busy) return;
                if (editable) setResult(RESULT_OK, new Intent().putExtra(Intent.EXTRA_PROCESS_TEXT, translated));
                else { getSystemService(ClipboardManager.class).setPrimaryClip(ClipData.newPlainText("译镜输入翻译", translated)); Toast.makeText(this, "已复制，请重新选中原文后粘贴替换。", Toast.LENGTH_LONG).show(); }
                finish();
            }); Ui.add(page, replace, 8);
        }
        improve = Ui.button(this, "用 DeepSeek 重译 · 消耗 token", false); improve.setOnClickListener(v -> translate(TranslationRouter.LLM)); Ui.add(page, improve, 8);
        Button configure = Ui.button(this, "引擎设置 / 下载离线包", false);
        configure.setOnClickListener(v -> startActivity(new Intent(this, MainActivity.class).putExtra("openSettings", true))); Ui.add(page, configure, 8);
        setContentView(scroll); getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN);
        AdapterView.OnItemSelectedListener languageChange = new AdapterView.OnItemSelectedListener() {
            public void onNothingSelected(AdapterView<?> parent) {}
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                String from = source.getSelectedItem().toString(), to = target.getSelectedItem().toString();
                if (from.equals(lastSource) && to.equals(lastTarget)) return;
                lastSource = from; lastTarget = to; generation++; client.cancel(); translated = ""; output.setText(""); attribution.setVisibility(View.GONE); busy = false;
                retry.setEnabled(true); improve.setEnabled(true); if (replace != null) replace.setEnabled(false);
                if (writing) prefs.writingTarget(to); status.setText("语言已更新，点击翻译；旧译文已清除。");
            }
        };
        source.setOnItemSelectedListener(languageChange); target.setOnItemSelectedListener(languageChange);
        if (translated.isEmpty()) translate(prefs.engineMode());
        else status.setText(state.getString("status", "译文已恢复，确认后可替换。"));
    }
    private void translate(String mode) {
        if (busy) return;
        final String text;
        try { text = TranslationRules.validate(original); } catch (Exception e) { status.setText(e.getMessage()); return; }
        final String from = source.getSelectedItem().toString(), to = target.getSelectedItem().toString();
        if (writing) prefs.writingTarget(to);
        final TranslationEngine.Config config = new TranslationEngine.Config(mode, prefs.model(), prefs.autoLongText());
        final int request = ++generation; busy = true; translated = ""; output.setText(""); attribution.setVisibility(View.GONE); retry.setEnabled(false); improve.setEnabled(false);
        if (replace != null) replace.setEnabled(false); status.setText(TranslationRouter.label(mode) + " · 正在翻译…");
        worker.execute(() -> {
            try {
                TranslationRouter.Result result = client.text(text, from, to, config);
                runOnUiThread(() -> {
                    if (isDestroyed() || isFinishing() || request != generation) return;
                    translated = result.text(); output.setText(translated); busy = false; retry.setEnabled(true); improve.setEnabled(true);
                    attribution.setVisibility(result.localCount > 0 ? View.VISIBLE : View.GONE);
                    if (replace != null) replace.setEnabled(true); status.setText(result.summary());
                    prefs.addHistory(text, translated, to, result.summary(), result.localCount > 0);
                });
            } catch (Exception e) {
                runOnUiThread(() -> { if (isDestroyed() || isFinishing() || request != generation) return;
                    busy = false; retry.setEnabled(true); improve.setEnabled(true); status.setText(DeepSeekClient.friendlyError(e)); });
            }
        });
    }
    @Override protected void onSaveInstanceState(Bundle state) {
        super.onSaveInstanceState(state); state.putString("original", original); state.putString("translated", translated);
        state.putString("from", lastSource); state.putString("to", lastTarget); state.putString("status", status.getText().toString());
        state.putBoolean("offline", attribution.getVisibility() == View.VISIBLE);
    }
    @Override protected void onDestroy() { generation++; if (client != null) client.close(); worker.shutdownNow(); super.onDestroy(); }
}
