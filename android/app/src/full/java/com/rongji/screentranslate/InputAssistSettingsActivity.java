package com.rongji.screentranslate;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.provider.Settings;
import android.widget.*;

/** Explicit setup; the user enables the optional service in Android's own settings. */
public final class InputAssistSettingsActivity extends Activity {
    private TextView status;
    private final android.database.ContentObserver keyboardSelection = new android.database.ContentObserver(new android.os.Handler(android.os.Looper.getMainLooper())) {
        @Override public void onChange(boolean selfChange) { updateStatus(); }
    };
    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        ScrollView scroll = new ScrollView(this); scroll.setBackgroundColor(Ui.BG); Ui.systemInsets(scroll);
        LinearLayout page = Ui.column(this); page.setPadding(Ui.dp(this, 22), Ui.dp(this, 24), Ui.dp(this, 22), Ui.dp(this, 24)); scroll.addView(page);
        page.addView(Ui.text(this, "输入翻译", 26, Ui.NAVY, true));
        Ui.add(page, Ui.text(this, "译镜键盘 · 本机输入与离线翻译", 17, Ui.ACCENT, true), 12);
        Ui.add(page, Ui.text(this, "输入中文 → 点键盘上的“翻译” → 点原应用发送。\n\n键盘上方可切换 26 键全拼、九宫格拼音、笔画，支持英文、数字和符号。候选栏提供本地联想；点击输入方式菜单可开关联想。选中部分时只翻译选区，否则翻译全文，30 秒内可撤销。无需无障碍服务或翻译弹窗。", 16, Ui.NAVY, false), 16);
        status = Ui.text(this, "", 14, Ui.ACCENT, true); Ui.add(page, status, 20);
        Button enable = Ui.button(this, "1. 在系统中启用译镜键盘", true); enable.setOnClickListener(v -> startActivity(new Intent(Settings.ACTION_INPUT_METHOD_SETTINGS))); Ui.add(page, enable, 12);
        Button select = Ui.button(this, "2. 切换到译镜键盘", true); select.setOnClickListener(v -> getSystemService(android.view.inputmethod.InputMethodManager.class).showInputMethodPicker()); Ui.add(page, select, 8);
        Button models = Ui.button(this, "3. 准备离线语言包", false); models.setOnClickListener(v -> startActivity(new Intent(this, OfflineModelsActivity.class))); Ui.add(page, models, 8);
        Ui.add(page, Ui.text(this, "中文拼音词库随安装包提供。中英翻译需先下载中文离线包；下载后可断网使用。键盘固定离线，不读取 API Key、不调用 DeepSeek、不保存输入历史或学习个人词库，也不自动发送。密码框关闭翻译和候选词。\n\n本应用仍包含其他页面的联网功能。离线翻译由 Google ML Kit 提供，联网时 SDK 可能连接 Google 下载模型、更新或发送性能指标；原文和译文在本机处理。可断网使用以限制整个应用联网。", 14, Ui.MUTED, false), 14);
        Ui.add(page, Ui.text(this, "九宫格：按数字键输入拼音，可点拼音条筛选读音。笔画：横、竖、撇、点／捺、折，? 代替不确定的一笔；“重输”只清除正在输入的编码。\n\n联想：只使用光标前最多 24 个字符，在本机词库中寻找续接词。不会记录输入习惯，没有云联想；密码框和禁止建议的输入框不提供联想。可在键盘的输入方式菜单中关闭。", 14, Ui.MUTED, false), 12);
        Button legal = Ui.button(this, "拼音、笔画字库与开源许可", false); legal.setOnClickListener(v -> showLicense()); Ui.add(page, legal, 8);
        Ui.add(page, Ui.text(this, "保留其他输入法的备选入口", 17, Ui.NAVY, true), 24);
        Ui.add(page, Ui.text(this, "可选开启“译镜输入辅助”，左侧“译”按钮点按直接翻译替换、长按预览和选语言。此入口沿用全局离线 / 自动 / DeepSeek 设置，部分应用仍可能不支持。使用译镜键盘时会自动隐藏此悬浮按钮。", 14, Ui.MUTED, false), 8);
        Switch shortcut = new Switch(this); shortcut.setText("使用其他键盘时显示左侧“译”按钮"); shortcut.setChecked(new AppPrefs(this).inputShortcut());
        shortcut.setOnCheckedChangeListener((button, checked) -> { new AppPrefs(this).inputShortcut(checked); InputAssistService.refreshShortcut(); }); Ui.add(page, shortcut, 18);
        Button setup = Ui.button(this, "前往系统开启输入辅助", true); setup.setOnClickListener(v -> openSystemSettings()); Ui.add(page, setup, 14);
        Button back = Ui.button(this, "返回", false); back.setOnClickListener(v -> finish()); Ui.add(page, back, 8);
        setContentView(scroll);
    }
    private void openSystemSettings() {
        startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS));
    }
    private void showLicense() {
        try {
            StringBuilder text = new StringBuilder();
            for (String file : new String[]{"pinyin/SOURCE.txt", "pinyin/AUTHORS", "pinyin/LICENSE", "stroke/SOURCE.txt", "stroke/AUTHORS", "stroke/LICENSE", "stroke/COPYING"}) try (var reader = new java.io.InputStreamReader(getAssets().open(file), java.nio.charset.StandardCharsets.UTF_8)) {
                char[] buffer = new char[2048]; for (int n; (n = reader.read(buffer)) >= 0;) text.append(buffer, 0, n); text.append("\n\n");
            }
            new android.app.AlertDialog.Builder(this).setTitle("本机输入字词库").setMessage(text).setPositiveButton("关闭", null).show();
        } catch (java.io.IOException error) { Toast.makeText(this, "无法读取许可文件", Toast.LENGTH_SHORT).show(); }
    }
    private void updateStatus() { if (status != null) status.setText(LensKeyboardService.isSelected(this) ? "已选择译镜键盘，可返回微信或 X 输入。" : "首次使用：启用键盘 → 切换键盘 → 准备语言包。"); }
    @Override protected void onStart() { super.onStart(); getContentResolver().registerContentObserver(Settings.Secure.getUriFor(Settings.Secure.DEFAULT_INPUT_METHOD), false, keyboardSelection); }
    @Override protected void onStop() { getContentResolver().unregisterContentObserver(keyboardSelection); super.onStop(); }
    @Override protected void onResume() { super.onResume(); updateStatus(); }
}
