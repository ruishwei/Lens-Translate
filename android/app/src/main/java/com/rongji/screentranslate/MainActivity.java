package com.rongji.screentranslate;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.widget.*;
import org.json.JSONArray;
import org.json.JSONObject;
import java.text.DateFormat;
import java.util.Date;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class MainActivity extends Activity {
    private static final int PICK_IMAGE = 10, OVERLAY = 11, CAPTURE = 12, NOTIFICATIONS = 13;
    private AppPrefs prefs;
    private KeyVault vault;
    private EditText input;
    private TextView output, status, screenStatus;
    private Button translate, retranslate;
    private Spinner source, target;
    private ImageView attribution;
    private String lastSource, lastTarget;
    private OcrEngine ocr;
    private TranslationEngine client;
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private int generation;
    private boolean busy;

    @Override public void onCreate(Bundle state) {
        super.onCreate(state); prefs = new AppPrefs(this); vault = new KeyVault(this); ocr = new OcrEngine(); client = new TranslationEngine(this);
        ScrollView scroll = new ScrollView(this); scroll.setFillViewport(true); scroll.setBackgroundColor(Ui.BG);
        LinearLayout page = Ui.column(this); page.setPadding(d(22), d(18), d(22), d(32)); scroll.addView(page);
        // Android 15 enforces edge-to-edge: apply actual status/navigation insets to the root.
        scroll.setOnApplyWindowInsetsListener((v, insets) -> {
            if (Build.VERSION.SDK_INT >= 30) {
                android.graphics.Insets edges = insets.getInsets(android.view.WindowInsets.Type.systemBars() | android.view.WindowInsets.Type.displayCutout());
                scroll.setPadding(edges.left, edges.top, edges.right, edges.bottom);
            } else scroll.setPadding(insets.getSystemWindowInsetLeft(), insets.getSystemWindowInsetTop(), insets.getSystemWindowInsetRight(), insets.getSystemWindowInsetBottom());
            return insets;
        });
        LinearLayout header = Ui.row(this);
        ImageView logo = new ImageView(this); logo.setImageResource(R.drawable.ic_lens);
        logo.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        LinearLayout.LayoutParams logoParams = new LinearLayout.LayoutParams(d(44), d(44)); logoParams.rightMargin = d(12); header.addView(logo, logoParams);
        LinearLayout brand = Ui.column(this);
        brand.addView(Ui.text(this, getString(R.string.app_name), 26, Ui.NAVY, true));
        brand.addView(Ui.text(this, "LENS TRANSLATE", 10, Ui.ACCENT, true));
        header.addView(brand, new LinearLayout.LayoutParams(0, -2, 1));
        Button settings = Ui.button(this, "设置", false); settings.setOnClickListener(v -> showSettings()); header.addView(settings);
        page.addView(header);
        Ui.add(page, Ui.text(this, "让每个屏幕，都说你的语言。", 16, Ui.MUTED, false), 18);

        LinearLayout screen = Ui.card(this); screen.setBackground(Ui.rounded(this, Ui.NAVY, 20, 0));
        screen.addView(Ui.text(this, "跨应用 · 屏幕翻译", 21, Color.WHITE, true));
        screenStatus = Ui.text(this, "单击全屏翻译，长按打开圆形菜单选择区域。", 13, 0xFFB9CBDA, false); Ui.add(screen, screenStatus, 8);
        LinearLayout screenActions = Ui.row(this);
        Button start = Ui.button(this, "开启悬浮翻译", true); start.setOnClickListener(v -> startScreen());
        screenActions.addView(start, new LinearLayout.LayoutParams(0, -2, 1));
        Button stop = Ui.button(this, "停止", false); stop.setOnClickListener(v -> { stopService(new Intent(this, ScreenCaptureService.class)); screenStatus.setText("悬浮翻译已停止"); });
        LinearLayout.LayoutParams stopParams = new LinearLayout.LayoutParams(-2, -2); stopParams.leftMargin = d(10); screenActions.addView(stop, stopParams); Ui.add(screen, screenActions, 18);
        Ui.add(page, screen, 24);
        InputFeature.addHomeSetup(this, page);

        LinearLayout editor = Ui.card(this);
        LinearLayout editorTitle = Ui.row(this); editorTitle.addView(Ui.text(this, "文字与图片", 20, Ui.NAVY, true), new LinearLayout.LayoutParams(0, -2, 1));
        TextView local = Ui.text(this, "本地 OCR", 11, Ui.ACCENT, true); editorTitle.addView(local); editor.addView(editorTitle);
        LinearLayout languages = Ui.row(this); source = spinner(TranslationRules.SOURCES, prefs.source()); target = spinner(TranslationRules.TARGETS, prefs.target());
        languages.addView(source, new LinearLayout.LayoutParams(0, d(48), 1));
        TextView arrow = Ui.text(this, "→", 20, Ui.ACCENT, true); languages.addView(arrow); languages.addView(target, new LinearLayout.LayoutParams(0, d(48), 1)); Ui.add(editor, languages, 12);
        input = new EditText(this); input.setId(R.id.source_text); input.setHint("输入或粘贴文字，也可以导入截图…"); input.setTextColor(Ui.NAVY); input.setHintTextColor(0xFF8D9AAA);
        input.setTextSize(16); input.setGravity(Gravity.TOP); input.setMinLines(5); input.setMaxLines(12);
        input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        input.setBackground(Ui.rounded(this, 0xFFF6F8FB, 12, 0)); input.setPadding(d(14), d(14), d(14), d(14)); Ui.add(editor, input, 12);
        LinearLayout editActions = Ui.row(this); Button paste = Ui.button(this, "粘贴", false); paste.setOnClickListener(v -> paste());
        Button importImage = Ui.button(this, "导入图片", false); importImage.setOnClickListener(v -> { if (busy) { toast("请等待当前任务完成"); return; } Intent pick = new Intent(Intent.ACTION_OPEN_DOCUMENT).setType("image/*").addCategory(Intent.CATEGORY_OPENABLE); startActivityForResult(pick, PICK_IMAGE); });
        editActions.addView(paste, new LinearLayout.LayoutParams(0, -2, 1)); LinearLayout.LayoutParams imgP = new LinearLayout.LayoutParams(0, -2, 1); imgP.leftMargin = d(10); editActions.addView(importImage, imgP); Ui.add(editor, editActions, 12);
        translate = Ui.button(this, "翻译  →", true); translate.setOnClickListener(v -> translate()); Ui.add(editor, translate, 12);
        status = Ui.text(this, TranslationRouter.label(prefs.engineMode()) + " · 离线包可在设置中下载", 11, Ui.MUTED, false); Ui.add(editor, status, 10); Ui.add(page, editor, 16);

        LinearLayout result = Ui.card(this); LinearLayout resultHeader = Ui.row(this);
        resultHeader.addView(Ui.text(this, "译文", 20, Ui.NAVY, true), new LinearLayout.LayoutParams(0, -2, 1));
        Button copy = Ui.button(this, "复制", false); copy.setOnClickListener(v -> { if (output.getText().length() > 0) { clipboard(output.getText().toString()); toast("已复制译文"); } }); resultHeader.addView(copy); result.addView(resultHeader);
        output = Ui.text(this, "", 18, Ui.NAVY, false); output.setId(R.id.translated_text); output.setHint("译文将在这里出现"); output.setTextIsSelectable(true); output.setMinHeight(d(90)); Ui.add(result, output, 16); Ui.add(page, result, 16);
        attribution = Ui.attribution(this); attribution.setVisibility(View.GONE); Ui.add(result, attribution, 8);
        retranslate = Ui.button(this, "用 DeepSeek 重译 · 消耗 token", false); retranslate.setOnClickListener(v -> translate(TranslationRouter.LLM)); Ui.add(result, retranslate, 12);
        Button history = Ui.button(this, "查看最近翻译", false); history.setOnClickListener(v -> showHistory()); Ui.add(page, history, 16);
        Ui.add(page, Ui.text(this, getString(R.string.edition_label) + "  " + BuildConfig.VERSION_NAME, 11, Ui.MUTED, false), 20);
        setContentView(scroll);
        lastSource = source.getSelectedItem().toString(); lastTarget = target.getSelectedItem().toString();
        AdapterView.OnItemSelectedListener languageChange = new AdapterView.OnItemSelectedListener() {
            public void onNothingSelected(AdapterView<?> parent) {}
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                String from = source.getSelectedItem().toString(), to = target.getSelectedItem().toString();
                if (!from.equals(lastSource) || !to.equals(lastTarget)) { lastSource = from; lastTarget = to; output.setText(""); attribution.setVisibility(View.GONE); status.setText("语言已更新，点击翻译生成译文。"); saveLanguages(); }
            }
        };
        source.setOnItemSelectedListener(languageChange); target.setOnItemSelectedListener(languageChange);
        input.addTextChangedListener(new android.text.TextWatcher() {
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            public void onTextChanged(CharSequence s, int start, int before, int count) { output.setText(""); attribution.setVisibility(View.GONE); if (!busy) status.setText("原文已更新，点击翻译生成译文。"); }
            public void afterTextChanged(android.text.Editable s) {}
        });
        if (state != null) { input.setText(state.getString("input", "")); output.setText(state.getString("output", "")); attribution.setVisibility(state.getBoolean("offline") ? View.VISIBLE : View.GONE); }
        else acceptSharedText(getIntent());
    }

    private int d(float value) { return Ui.dp(this, value); }
    private Spinner spinner(String[] values, String selected) {
        Spinner spinner = new Spinner(this); ArrayAdapter<String> adapter = new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item, values); spinner.setAdapter(adapter);
        for (int i = 0; i < values.length; i++) if (values[i].equals(selected)) spinner.setSelection(i);
        return spinner;
    }
    private void saveLanguages() { prefs.languages(source.getSelectedItem().toString(), target.getSelectedItem().toString()); }
    private void paste() { if (busy) { toast("请等待当前任务完成"); return; } ClipboardManager manager = getSystemService(ClipboardManager.class); if (manager.hasPrimaryClip() && manager.getPrimaryClip() != null) input.setText(manager.getPrimaryClip().getItemAt(0).coerceToText(this)); else toast("剪贴板没有文字"); }
    private void clipboard(String text) { getSystemService(ClipboardManager.class).setPrimaryClip(ClipData.newPlainText("译镜", text)); }
    private void toast(String text) { Toast.makeText(this, text, Toast.LENGTH_SHORT).show(); }
    private void setBusy(boolean value, String message) { busy = value; input.setEnabled(!value); source.setEnabled(!value); target.setEnabled(!value); translate.setEnabled(!value); retranslate.setEnabled(!value); translate.setText(value ? "正在处理…" : "翻译  →"); status.setText(message); }
    private void translate() { translate(prefs.engineMode()); }
    private void translate(String mode) {
        if (busy) return; String text;
        try { text = TranslationRules.validate(input.getText().toString()); } catch (IllegalArgumentException e) { status.setText(e.getMessage()); return; }
        saveLanguages(); final String from = prefs.source(), to = prefs.target(); final int request = ++generation;
        TranslationEngine.Config config = new TranslationEngine.Config(mode, prefs.model(), prefs.autoLongText());
        output.setText(""); attribution.setVisibility(View.GONE); setBusy(true, TranslationRouter.label(mode) + " · 正在翻译…");
        worker.execute(() -> { try {
            TranslationRouter.Result result = client.text(text, from, to, config); String translated = result.text();
            if (Thread.currentThread().isInterrupted()) return;
            runOnUiThread(() -> { if (!isDestroyed() && request == generation) { output.setText(translated); attribution.setVisibility(result.localCount > 0 ? View.VISIBLE : View.GONE); setBusy(false, result.summary() + " · " + to); prefs.addHistory(text, translated, to, result.summary(), result.localCount > 0); } });
        } catch (Exception e) { runOnUiThread(() -> { if (!isDestroyed() && request == generation) setBusy(false, DeepSeekClient.friendlyError(e)); }); } });
    }

    private void recognize(Uri uri) {
        if (busy) { toast("请等待当前任务完成"); return; }
        setBusy(true, "正在本机识别图片…"); final int request = ++generation; final String from = source.getSelectedItem().toString();
        worker.execute(() -> { try { android.graphics.Bitmap bitmap = OcrEngine.load(this, uri);
            runOnUiThread(() -> { if (isDestroyed() || request != generation) { bitmap.recycle(); return; }
                ocr.recognize(bitmap, from, new OcrEngine.Callback() {
                    public void success(String text) { if (isDestroyed() || request != generation) return; input.setText(text); setBusy(false, text.trim().isEmpty() ? "没有识别到文字，请选择更清晰的中英文图片。" : "识别完成，检查原文后点击翻译。"); }
                    public void failure(Exception e) { if (!isDestroyed() && request == generation) setBusy(false, "图片识别失败，请换一张清晰的图片。"); }
                });
            });
        } catch (Exception e) { runOnUiThread(() -> { if (!isDestroyed() && request == generation) setBusy(false, "无法读取这张图片，请选择 JPEG、PNG 或 WebP。"); }); } });
    }

    private void startScreen() {
        saveLanguages();
        if (ScreenCaptureService.running) { toast("悬浮翻译已开启，请点击屏幕侧边的译镜图标"); return; }
        if (TranslationRouter.LLM.equals(prefs.engineMode())) try { if (vault.read().isEmpty()) { showSettings(); toast("DeepSeek 模式需要 API Key，也可选择离线模式"); return; } } catch (Exception e) { showSettings(); return; }
        if (!Settings.canDrawOverlays(this)) {
            new AlertDialog.Builder(this).setTitle("允许悬浮翻译").setMessage("译镜需要“显示在其他应用上层”权限来显示翻译按钮和译文。下一步请在系统设置中开启。")
                    .setPositiveButton("前往授权", (dialog, which) -> startActivityForResult(new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:" + getPackageName())), OVERLAY)).setNegativeButton("取消", null).show(); return;
        }
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) { requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, NOTIFICATIONS); return; }
        captureConsent();
    }
    private void captureConsent() {
        new AlertDialog.Builder(this).setTitle("开启屏幕翻译").setMessage("当前引擎：" + TranslationRouter.label(prefs.engineMode()) + "\n\n单击全屏翻译；长按菜单可选区域。译文白字黑底，其他区域透明；点按退出后悬浮球恢复。\n\n仅离线模式使用已下载语言包，不调用 DeepSeek。自动模式优先离线，不可用时可将文字交给 DeepSeek；长段落规则由设置控制。只有调用 DeepSeek 时发送所选范围内的文字，截图不上传。\n\n锁屏后保留悬浮球，下次截图重新授权。应用或通知中的“停止”才会关闭悬浮球。")
                .setPositiveButton("继续", (dialog, which) -> startActivityForResult(CapturePermissionActivity.captureIntent(this), CAPTURE)).setNegativeButton("取消", null).show();
    }
    @Override public void onRequestPermissionsResult(int request, String[] permissions, int[] results) { super.onRequestPermissionsResult(request, permissions, results); if (request == NOTIFICATIONS) captureConsent(); }
    @Override protected void onActivityResult(int request, int result, Intent data) {
        super.onActivityResult(request, result, data);
        if (request == PICK_IMAGE && result == RESULT_OK && data != null && data.getData() != null) recognize(data.getData());
        if (request == OVERLAY) { if (Settings.canDrawOverlays(this)) startScreen(); else toast("未开启悬浮窗权限，仍可使用文字与图片翻译"); }
        if (request == CAPTURE && result == RESULT_OK && data != null) {
            Intent service = new Intent(this, ScreenCaptureService.class).putExtra("resultCode", result).putExtra("data", data);
            startForegroundService(service); screenStatus.setText("已启动 · 单击翻译，长按圆形菜单"); moveTaskToBack(true);
        }
    }

    @Override protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent); setIntent(intent); acceptSharedText(intent);
    }

    private void showSettings() {
        LinearLayout box = Ui.column(this); box.setPadding(d(24), d(12), d(24), d(8));
        box.addView(Ui.text(this, "翻译引擎", 14, Ui.NAVY, true));
        Spinner mode = spinner(TranslationRouter.LABELS, TranslationRouter.label(prefs.engineMode())); box.addView(mode);
        CheckBox longText = new CheckBox(this); longText.setText("自动模式：长段落优先 LLM（≥240 字符）"); longText.setChecked(prefs.autoLongText()); Ui.add(box, longText, 8);
        Ui.add(box, Ui.text(this, "自动默认先离线；语言包缺失或离线失败时，有网络且已配置 Key 才调用 DeepSeek。上面的长段落规则是长度规则，不是译文质量评分。仅离线模式不会自动转云端。", 12, Ui.MUTED, false), 8);
        Button packs = Ui.button(this, "管理离线语言包", false); packs.setOnClickListener(v -> startActivity(new Intent(this, OfflineModelsActivity.class))); Ui.add(box, packs, 10);
        Spinner writing = BuildConfig.HAS_INPUT_TRANSLATION ? spinner(TranslationRules.TARGETS, prefs.writingTarget()) : null;
        if (writing != null) {
            Ui.add(box, Ui.text(this, "写作默认目标语言（与阅读分开）", 14, Ui.NAVY, true), 16);
            box.addView(writing); InputFeature.addSettingsSetup(this, box);
        }
        box.addView(Ui.text(this, "DeepSeek API Key", 14, Ui.NAVY, true));
        EditText key = new EditText(this); key.setSingleLine(true); key.setHint("输入新 Key，留空保留已保存的 Key"); key.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD); key.setImportantForAutofill(View.IMPORTANT_FOR_AUTOFILL_NO); box.addView(key);
        TextView keyStatus = Ui.text(this, "密钥通过 Android Keystore 加密保存。", 12, Ui.MUTED, false); box.addView(keyStatus);
        try { if (!vault.read().isEmpty()) keyStatus.setText("已保存 Key · Android Keystore 加密保护"); } catch (Exception e) { keyStatus.setText("已保存的 Key 无法解密，请重新输入"); }
        Ui.add(box, Ui.text(this, "模型", 14, Ui.NAVY, true), 18); EditText model = new EditText(this); model.setSingleLine(true); model.setText(prefs.model()); box.addView(model);
        Switch historyToggle = new Switch(this); historyToggle.setText("在本机保留最近 50 条翻译"); historyToggle.setChecked(prefs.historyEnabled()); Ui.add(box, historyToggle, 14);
        Button dataUse = Ui.button(this, "发送到 DeepSeek 的内容", false); dataUse.setOnClickListener(v -> showDataUse()); Ui.add(box, dataUse, 12);
        Ui.add(box, Ui.text(this, "OCR 使用本机中文 / 拉丁文字引擎。离线翻译由 Google Translate 提供，先下载语言包。Google SDK 可能下载更新并发送性能 / 使用指标，不上传离线处理的原文和译文。DeepSeek 使用你自己的额度。", 12, Ui.MUTED, false), 14);
        ScrollView settingsScroll = new ScrollView(this); settingsScroll.addView(box);
        AlertDialog dialog = new AlertDialog.Builder(this).setTitle("翻译设置").setView(settingsScroll).setPositiveButton("保存", null).setNegativeButton("取消", null).setNeutralButton("删除 Key", (d, w) -> { try { vault.save(""); toast("已删除 API Key"); } catch (Exception e) { toast("删除失败"); } }).create();
        dialog.setOnShowListener(d -> dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            if (model.getText().toString().trim().isEmpty()) { model.setError("请输入模型名称"); return; }
            try { if (!key.getText().toString().trim().isEmpty()) vault.save(key.getText().toString()); prefs.model(model.getText().toString()); prefs.historyEnabled(historyToggle.isChecked()); prefs.engineMode(TranslationRouter.MODES[mode.getSelectedItemPosition()]); prefs.autoLongText(longText.isChecked()); if (writing != null) prefs.writingTarget(writing.getSelectedItem().toString()); dialog.dismiss(); if (!busy) status.setText(TranslationRouter.label(prefs.engineMode())); toast("设置已保存"); } catch (Exception e) { key.setError("无法保存设置，请重试"); }
        })); dialog.show();
    }

    private void showDataUse() {
        new AlertDialog.Builder(this).setTitle("发送到 DeepSeek 的内容")
                .setMessage("仅离线模式：原文和译文在手机上处理，不调用 DeepSeek。语言包需要先联网下载；Google SDK 可能发送性能 / 使用指标。\n\nDeepSeek 模式：发送本次待翻译文字。自动模式：只发送被分配给 DeepSeek 的文字块；默认离线不可用时调用，可选长段落规则。\n\n全屏可能包含正文、标题、人名、评论和按钮；区域模式只识别选区。"
                        + (BuildConfig.HAS_INPUT_TRANSLATION ? "输入辅助优先翻译选中文字，未选中时使用当前输入框全文；译镜键盘始终离线，不调用 DeepSeek。" : "")
                        + "\n\n请求包含临时块编号、原文、语言、模型和翻译要求。不附带截图、坐标或历史列表。API Key 通过 HTTPS 请求头认证。\n\n独立账号、网址、邮箱、数字和符号通常保留在本机；混在正文里仍可能发送，不是自动脱敏。")
                .setPositiveButton("知道了", null).show();
    }

    private void showHistory() {
        if (busy) { toast("请等待当前任务完成"); return; }
        JSONArray history = prefs.history(); LinearLayout rows = Ui.column(this); rows.setPadding(d(20), d(8), d(20), d(16));
        ScrollView scroll = new ScrollView(this); scroll.addView(rows); AlertDialog dialog = new AlertDialog.Builder(this).setTitle("最近翻译 · " + history.length())
                .setView(scroll).setPositiveButton("关闭", null).setNeutralButton("清空记录", (d, w) -> new AlertDialog.Builder(this).setTitle("清空本机翻译记录？").setPositiveButton("清空", (a, b) -> { prefs.clearHistory(); toast("记录已清空"); }).setNegativeButton("取消", null).show()).create();
        if (history.length() == 0) rows.addView(Ui.text(this, "还没有翻译记录。完成一次翻译后可在这里查看。", 14, Ui.MUTED, false));
        for (int i = 0; i < history.length(); i++) {
            JSONObject item = history.optJSONObject(i); if (item == null) continue;
            LinearLayout card = Ui.card(this); card.addView(Ui.text(this, item.optString("target") + " · " + DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(new Date(item.optLong("time"))), 11, Ui.ACCENT, true));
            TextView original = Ui.text(this, item.optString("source"), 13, Ui.MUTED, false); original.setMaxLines(2); Ui.add(card, original, 8);
            TextView translated = Ui.text(this, item.optString("translated"), 15, Ui.NAVY, false); translated.setMaxLines(3); Ui.add(card, translated, 6);
            if (item.optBoolean("offline")) Ui.add(card, Ui.attribution(this), 6);
            card.setOnClickListener(v -> {
                int targetIndex = 0;
                for (int j = 0; j < TranslationRules.TARGETS.length; j++) if (TranslationRules.TARGETS[j].equals(item.optString("target"))) targetIndex = j;
                lastSource = TranslationRules.SOURCES[0]; lastTarget = TranslationRules.TARGETS[targetIndex];
                source.setSelection(0); target.setSelection(targetIndex);
                input.setText(item.optString("source")); output.setText(item.optString("translated")); attribution.setVisibility(item.optBoolean("offline") ? View.VISIBLE : View.GONE); status.setText("已载入历史译文 · " + lastTarget + " · " + item.optString("engine", "DeepSeek")); saveLanguages(); dialog.dismiss();
            }); Ui.add(rows, card, 10);
        } dialog.show();
    }
    private void acceptSharedText(Intent intent) { CharSequence text = intent.getCharSequenceExtra(Intent.EXTRA_TEXT); if (text == null) text = intent.getCharSequenceExtra(Intent.EXTRA_PROCESS_TEXT); if (text != null) input.setText(text.toString()); }
    @Override protected void onResume() {
        super.onResume();
        if (screenStatus != null) screenStatus.setText(ScreenCaptureService.running ? "悬浮球已开启 · 单击翻译，长按圆形菜单" : "单击全屏翻译，长按打开圆形菜单选择区域。");
        if (getIntent().getBooleanExtra("openSettings", false)) { getIntent().removeExtra("openSettings"); showSettings(); }
    }
    @Override protected void onPause() { if (source != null && target != null) saveLanguages(); super.onPause(); }
    @Override protected void onSaveInstanceState(Bundle state) { super.onSaveInstanceState(state); state.putString("input", input.getText().toString()); state.putString("output", output.getText().toString()); state.putBoolean("offline", attribution.getVisibility() == View.VISIBLE); saveLanguages(); }
    @Override protected void onRestoreInstanceState(Bundle state) { super.onRestoreInstanceState(state); output.setText(state.getString("output", "")); }
    @Override protected void onDestroy() { generation++; if (client != null) client.close(); worker.shutdownNow(); if (ocr != null) ocr.close(); super.onDestroy(); }
}
