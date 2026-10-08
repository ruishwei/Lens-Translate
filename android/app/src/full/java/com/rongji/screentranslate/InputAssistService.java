package com.rongji.screentranslate;

import android.accessibilityservice.AccessibilityService;
import android.app.KeyguardManager;
import android.content.*;
import android.content.res.Configuration;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.os.*;
import android.text.InputType;
import android.text.TextUtils;
import android.view.*;
import android.view.accessibility.*;
import android.widget.*;
import java.lang.ref.WeakReference;
import java.util.List;
import java.util.ArrayDeque;
import java.util.concurrent.*;

/** User-triggered editing only. Background events inspect focus/keyboard metadata, never event text. */
@SuppressWarnings("deprecation") // Node/window recycling remains necessary on supported older Android versions.
public final class InputAssistService extends AccessibilityService {
    private static volatile WeakReference<InputAssistService> instance = new WeakReference<>(null);
    private static boolean screenSuspended;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private WindowManager windows;
    private AppPrefs prefs;
    private TranslationEngine engine;
    private Future<?> task;
    private View shortcut, panel;
    private WindowManager.LayoutParams panelParams;
    private AccessibilityNodeInfo editor;
    private AccessibilityNodeInfo dismissedEditor;
    private long ignoreOwnEditsUntil;
    private boolean localResult;
    private InputReplacement edit;
    private TextView output, status;
    private Button apply;
    private ImageView attribution;
    private String translated = "", targetLanguage = "";
    private String overlayError = "";
    private int generation;
    private boolean destroyed, applying, registered, quick;
    private final Runnable refresh = this::updateShortcut;
    private final BroadcastReceiver screenOff = new BroadcastReceiver() {
        @Override public void onReceive(Context context, Intent intent) { closePanel(); removeShortcut(); clearDismissed(); }
    };

    static boolean connected() { InputAssistService service = instance.get(); return service != null && !service.destroyed && service.getServiceInfo() != null; }
    static void refreshShortcut() { InputAssistService service = instance.get(); if (service != null) service.scheduleRefresh(); }
    static void suspendForScreen(boolean suspend) {
        screenSuspended = suspend;
        InputAssistService service = instance.get();
        if (service != null) { if (suspend) { service.closePanel(); service.removeShortcut(); } else service.scheduleRefresh(); }
    }
    static void requestTranslation(Context context) {
        InputAssistService service = instance.get();
        if (connected() && service != null) service.main.post(service::beginTranslation);
        else context.startActivity(new Intent(context, InputAssistSettingsActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
    }
    @Override protected void onServiceConnected() {
        super.onServiceConnected(); instance = new WeakReference<>(this);
        if (engine == null) {
            windows = getSystemService(WindowManager.class); prefs = new AppPrefs(this); engine = new TranslationEngine(this);
        }
        if (!registered) {
            if (Build.VERSION.SDK_INT >= 33) registerReceiver(screenOff, new IntentFilter(Intent.ACTION_SCREEN_OFF), Context.RECEIVER_NOT_EXPORTED);
            else registerReceiver(screenOff, new IntentFilter(Intent.ACTION_SCREEN_OFF));
            registered = true;
        }
        scheduleRefresh();
    }
    @Override public void onAccessibilityEvent(AccessibilityEvent event) {
        // Text-change metadata only; never event.getText() or draft reads in the background.
        int type = event.getEventType();
        boolean hostEvent = dismissedEditor != null && TextUtils.equals(dismissedEditor.getPackageName(), event.getPackageName());
        if (hostEvent && (type == AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED || type == AccessibilityEvent.TYPE_VIEW_TEXT_SELECTION_CHANGED
                || type == AccessibilityEvent.TYPE_VIEW_FOCUSED || type == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED)
                && !applying && event.getEventTime() > ignoreOwnEditsUntil) clearDismissed();
        scheduleRefresh();
    }
    private void clearDismissed() {
        if (dismissedEditor != null) { dismissedEditor.recycle(); dismissedEditor = null; }
        // A completion label is not a usable shortcut. Drop it when the next edit/focus arrives,
        // otherwise its later timeout can leave the editor with no button and no refresh event.
        if (shortcut != null && shortcut.getId() != R.id.input_shortcut) removeShortcut();
    }
    @Override public void onInterrupt() { closePanel(); removeShortcut(); clearDismissed(); }
    private void scheduleRefresh() { if (!destroyed) { main.removeCallbacks(refresh); main.postDelayed(refresh, 120); } }
    private boolean locked() { return getSystemService(KeyguardManager.class).isKeyguardLocked(); }
    private int dp(float value) { return Ui.dp(this, value); }
    private int screenHeight() {
        android.util.DisplayMetrics metrics = new android.util.DisplayMetrics(); windows.getDefaultDisplay().getRealMetrics(metrics); return metrics.heightPixels;
    }
    private int keyboardTop() {
        int top = screenHeight();
        for (AccessibilityWindowInfo window : getWindows()) {
            try { if (window.getType() == AccessibilityWindowInfo.TYPE_INPUT_METHOD) { Rect bounds = new Rect(); window.getBoundsInScreen(bounds); if (!bounds.isEmpty()) top = Math.min(top, bounds.top); } }
            finally { window.recycle(); }
        }
        return top;
    }
    static boolean password(AccessibilityNodeInfo node) {
        if (node.isPassword()) return true;
        int input = node.getInputType(), kind = input & InputType.TYPE_MASK_CLASS, variation = input & InputType.TYPE_MASK_VARIATION;
        return kind == InputType.TYPE_CLASS_TEXT && (variation == InputType.TYPE_TEXT_VARIATION_PASSWORD
                || variation == InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD || variation == InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD)
                || kind == InputType.TYPE_CLASS_NUMBER && variation == InputType.TYPE_NUMBER_VARIATION_PASSWORD;
    }
    private boolean usable(AccessibilityNodeInfo node) {
        return node != null && node.isFocused() && node.isEditable() && node.isEnabled() && node.isVisibleToUser()
                && !password(node) && !getPackageName().contentEquals(node.getPackageName() == null ? "" : node.getPackageName())
                && node.getActionList().contains(AccessibilityNodeInfo.AccessibilityAction.ACTION_SET_TEXT);
    }
    private AccessibilityNodeInfo focusedEditor() {
        AccessibilityNodeInfo found = findFocus(AccessibilityNodeInfo.FOCUS_INPUT);
        if (usable(found)) return found;
        if (found != null) found.recycle();
        // Some web editors expose focus only through the host application's root.
        List<AccessibilityWindowInfo> candidates = getWindows();
        try {
            for (AccessibilityWindowInfo window : candidates) {
                if (window.getType() != AccessibilityWindowInfo.TYPE_APPLICATION || !(window.isActive() || window.isFocused())) continue;
                AccessibilityNodeInfo root = window.getRoot(); if (root == null) continue;
                try {
                    found = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT);
                    if (!usable(found)) {
                        if (found != null) found.recycle();
                        // WebView may return the outer view for findFocus while a virtual editor is focused.
                        found = findFocusedChild(root);
                    }
                }
                finally { root.recycle(); }
                if (usable(found)) return found;
                if (found != null) found.recycle();
            }
        } finally { for (AccessibilityWindowInfo window : candidates) window.recycle(); }
        return null;
    }
    private AccessibilityNodeInfo findFocusedChild(AccessibilityNodeInfo root) {
        ArrayDeque<AccessibilityNodeInfo> queue = new ArrayDeque<>(); queue.add(AccessibilityNodeInfo.obtain(root));
        int inspected = 0;
        try {
            while (!queue.isEmpty() && inspected++ < 512) {
                AccessibilityNodeInfo node = queue.remove();
                if (usable(node)) return node;
                try {
                    if (node.isVisibleToUser()) for (int i = 0; i < node.getChildCount() && queue.size() < 512; i++) {
                        AccessibilityNodeInfo child = node.getChild(i); if (child != null) queue.add(child);
                    }
                } finally { node.recycle(); }
            }
        } finally { while (!queue.isEmpty()) queue.remove().recycle(); }
        return null;
    }
    private void updateShortcut() {
        if (destroyed || windows == null) return;
        if (screenSuspended || locked() || LensKeyboardService.isSelected(this)) { closePanel(); removeShortcut(); return; }
        if (panel == null && (!prefs.inputShortcut() || keyboardTop() >= screenHeight() - dp(80))) { clearDismissed(); removeShortcut(); return; }
        AccessibilityNodeInfo current = focusedEditor();
        try {
            if (panel != null) {
                removeShortcut();
                if (current == null || editor == null || !current.equals(editor)) closePanel();
                else positionPanel();
                return;
            }
            if (current == null) { removeShortcut(); return; }
            if (dismissedEditor != null) {
                if (current.equals(dismissedEditor)) return;
                clearDismissed(); removeShortcut();
            }
            if (shortcut == null) {
                Button button = Ui.button(this, "译", true); button.setPadding(0, 0, 0, 0); button.setMinWidth(0); button.setMinimumWidth(0);
                button.setContentDescription("点按直接翻译替换，长按预览和选择语言"); button.setId(R.id.input_shortcut);
                button.setOnClickListener(v -> beginTranslation(true)); button.setOnLongClickListener(v -> { beginTranslation(false); return true; }); shortcut = button;
                try { windows.addView(shortcut, shortcutParams()); overlayError = ""; }
                catch (RuntimeException error) { overlayError = error.getClass().getSimpleName() + ": " + error.getMessage(); shortcut = null; }
            } else {
                WindowManager.LayoutParams next = shortcutParams(), previous = (WindowManager.LayoutParams) shortcut.getLayoutParams();
                if (next.x != previous.x || next.y != previous.y) windows.updateViewLayout(shortcut, next);
            }
        } finally { if (current != null) current.recycle(); }
    }
    private WindowManager.LayoutParams baseParams(int width, int height) {
        return new WindowManager.LayoutParams(width, height, WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT);
    }
    private WindowManager.LayoutParams shortcutParams() {
        // Reserve the entire composer row above the keyboard; never cover the host's Send action.
        WindowManager.LayoutParams p = baseParams(dp(48), dp(48)); p.gravity = Gravity.TOP | Gravity.LEFT;
        p.x = dp(8); p.y = Math.max(dp(32), keyboardTop() - dp(120)); return p;
    }
    private void removeShortcut() { if (shortcut != null) { remove(shortcut); shortcut = null; } }
    private void remove(View view) { try { windows.removeView(view); } catch (IllegalArgumentException ignored) { } }

    private void beginTranslation() { beginTranslation(false); }
    private void beginTranslation(boolean direct) {
        closePanel(); removeShortcut();
        quick = direct;
        if (destroyed || screenSuspended || locked()) return;
        AccessibilityNodeInfo current = focusedEditor();
        if (current == null) { toast("请先点击原应用的普通输入框；密码框或不支持写回的输入框无法使用。"); return; }
        try {
            // Text is read only after the user's explicit translation action, never in background events.
            edit = new InputReplacement(text(current), current.getTextSelectionStart(), current.getTextSelectionEnd());
            editor = current; current = null; targetLanguage = prefs.writingTarget();
            showPanel(); translate(prefs.engineMode());
        } catch (Exception error) { closePanel(); toast(error.getMessage()); scheduleRefresh(); }
        finally { if (current != null) current.recycle(); }
    }
    private static String text(AccessibilityNodeInfo node) { return node.getText() == null ? "" : node.getText().toString(); }
    private void showPanel() {
        LinearLayout card = Ui.column(this); card.setPadding(dp(14), dp(10), dp(14), dp(10)); card.setBackground(Ui.rounded(this, Ui.BG, 18, Ui.BORDER));
        LinearLayout header = Ui.row(this);
        header.addView(Ui.text(this, "输入翻译", 19, Ui.NAVY, true), new LinearLayout.LayoutParams(0, -2, 1));
        Button close = Ui.button(this, "取消", false); close.setOnClickListener(v -> { closePanel(); scheduleRefresh(); }); header.addView(close); card.addView(header);
        Ui.add(card, Ui.text(this, edit.selected ? "只替换选中部分 · 不自动发送" : "替换当前输入框全文 · 不自动发送", 12, Ui.ACCENT, false), 4);
        ScrollView scroll = new ScrollView(this); LinearLayout body = Ui.column(this); scroll.addView(body); card.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));
        TextView original = Ui.text(this, edit.source, 14, Ui.MUTED, false); original.setId(R.id.input_original); original.setMaxLines(3); original.setEllipsize(TextUtils.TruncateAt.END); Ui.add(body, original, 8);
        LinearLayout language = Ui.row(this); language.addView(Ui.text(this, "译成", 13, Ui.NAVY, true));
        Button target = Ui.button(this, targetLanguage + " ▾", false);
        target.setId(R.id.input_target); target.setContentDescription("输入翻译目标语言"); language.addView(target, new LinearLayout.LayoutParams(0, dp(48), 1)); Ui.add(body, language, 4);
        // Keep choices in the same non-focusable window so the host editor never loses input focus.
        LinearLayout choices = Ui.column(this); choices.setVisibility(View.GONE); Ui.add(body, choices, 4);
        for (int first = 0; first < TranslationRules.TARGETS.length; first += 3) {
            LinearLayout row = Ui.row(this); choices.addView(row);
            for (int i = first; i < Math.min(first + 3, TranslationRules.TARGETS.length); i++) {
                String chosen = TranslationRules.TARGETS[i]; Button choice = Ui.button(this, chosen, false);
                row.addView(choice, new LinearLayout.LayoutParams(0, dp(48), 1));
                choice.setOnClickListener(v -> {
                    choices.setVisibility(View.GONE); target.setText(chosen + " ▾");
                    if (!chosen.equals(targetLanguage)) { targetLanguage = chosen; prefs.writingTarget(chosen); translate(prefs.engineMode()); }
                });
            }
        }
        target.setOnClickListener(v -> choices.setVisibility(choices.getVisibility() == View.VISIBLE ? View.GONE : View.VISIBLE));
        output = Ui.text(this, "", 18, Ui.NAVY, false); output.setId(R.id.input_translation); Ui.add(body, output, 4);
        attribution = Ui.attribution(this); attribution.setVisibility(View.GONE); Ui.add(body, attribution, 6);
        status = Ui.text(this, "", 12, Ui.MUTED, false); status.setId(R.id.input_status); status.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE); Ui.add(body, status, 6);
        LinearLayout extra = Ui.row(this);
        Button reload = Ui.button(this, "重新读取", false); reload.setOnClickListener(v -> beginTranslation()); extra.addView(reload, new LinearLayout.LayoutParams(0, dp(48), 1));
        Button improve = Ui.button(this, "DeepSeek 重译", false); improve.setOnClickListener(v -> translate(TranslationRouter.LLM)); extra.addView(improve, new LinearLayout.LayoutParams(0, dp(48), 1));
        Button copy = Ui.button(this, "复制", false); copy.setOnClickListener(v -> { if (!translated.isEmpty()) { getSystemService(ClipboardManager.class).setPrimaryClip(ClipData.newPlainText("译镜", translated)); toast("已复制译文"); } }); extra.addView(copy); Ui.add(body, extra, 6);
        apply = Ui.button(this, "替换输入", true); apply.setId(R.id.input_replace); apply.setEnabled(false); apply.setOnClickListener(v -> applyTranslation()); Ui.add(card, apply, 6);
        if (quick) {
            LinearLayout progress = Ui.row(this); progress.setBackground(Ui.rounded(this, Ui.BG, 12, Ui.BORDER));
            progress.addView(Ui.text(this, " 正在翻译…", 13, Ui.NAVY, false), new LinearLayout.LayoutParams(0, -2, 1));
            Button cancel = Ui.button(this, "取消", false); cancel.setOnClickListener(v -> { closePanel(); scheduleRefresh(); }); progress.addView(cancel);
            panel = progress; panelParams = shortcutParams(); panelParams.width = dp(184);
        } else {
            panel = card; panelParams = baseParams(-1, dp(360)); panelParams.gravity = Gravity.TOP | Gravity.CENTER_HORIZONTAL;
            panelParams.width = getResources().getDisplayMetrics().widthPixels - dp(24);
        }
        calculatePanelPosition(); windows.addView(panel, panelParams);
    }
    private void calculatePanelPosition() {
        if (quick) { panelParams.y = shortcutParams().y; return; }
        int bottom = Math.min(keyboardTop(), screenHeight() - dp(32));
        panelParams.height = Math.max(dp(160), Math.min(dp(360), bottom - dp(48)));
        panelParams.y = Math.max(dp(32), bottom - panelParams.height - dp(8));
    }
    private void positionPanel() {
        if (panel != null) {
            int height = panelParams.height, y = panelParams.y; calculatePanelPosition();
            if (height != panelParams.height || y != panelParams.y) windows.updateViewLayout(panel, panelParams);
        }
    }
    private void translate(String mode) {
        if (edit == null || panel == null || applying) return;
        cancelTask(); final int request = generation; final String source = edit.source, target = targetLanguage;
        translated = ""; output.setText(""); apply.setEnabled(false); attribution.setVisibility(View.GONE);
        status.setText(TranslationRouter.label(mode) + " · 正在翻译…");
        TranslationEngine.Config config = new TranslationEngine.Config(mode, prefs.model(), prefs.autoLongText());
        task = worker.submit(() -> {
            try {
                TranslationRouter.Result result = engine.text(source, "自动检测", target, config);
                main.post(() -> {
                    if (destroyed || panel == null || request != generation) return;
                    translated = result.text(); output.setText(translated); attribution.setVisibility(result.localCount > 0 ? View.VISIBLE : View.GONE);
                    localResult = result.localCount > 0;
                    boolean unchanged = unchangedEditor(); apply.setEnabled(unchanged);
                    status.setText(unchanged ? result.summary() : "原文、选区或输入框已改变，请点“重新读取”。");
                    prefs.addHistory(source, translated, target, result.summary(), result.localCount > 0);
                    if (quick) {
                        if (unchanged) applyTranslation();
                        else { closePanel(); toast("输入内容已改变，未替换，请重试。"); scheduleRefresh(); }
                    }
                });
            } catch (Exception error) { main.post(() -> { if (!destroyed && panel != null && request == generation) {
                if (quick) { closePanel(); toast(DeepSeekClient.friendlyError(error)); scheduleRefresh(); }
                else status.setText(DeepSeekClient.friendlyError(error));
            } }); }
        });
    }
    private boolean unchangedEditor() {
        if (locked() || editor == null || edit == null || !editor.refresh()) return false;
        AccessibilityNodeInfo current = focusedEditor();
        try { return current != null && current.equals(editor) && edit.matches(text(current), current.getTextSelectionStart(), current.getTextSelectionEnd()); }
        finally { if (current != null) current.recycle(); }
    }
    private void applyTranslation() {
        if (translated.isEmpty() || applying || !unchangedEditor()) {
            if (quick && !applying) { closePanel(); toast("原文、选区或输入框已改变，未替换。请重新翻译。"); scheduleRefresh(); }
            else if (status != null) { status.setText("原文、选区或输入框已改变，未替换。请点“重新读取”。"); apply.setEnabled(false); } return;
        }
        final String replacement;
        try { replacement = edit.replaceWith(translated); } catch (Exception error) {
            if (quick) { closePanel(); toast(error.getMessage()); scheduleRefresh(); } else status.setText(error.getMessage()); return;
        }
        Bundle value = new Bundle(); value.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, replacement);
        apply.setEnabled(false); applying = true;
        if (!editor.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, value)) {
            applying = false;
            if (quick) { closePanel(); toast("此应用拒绝写回，请使用译镜键盘，或长按“译”复制译文。"); scheduleRefresh(); }
            else status.setText("此应用不支持直接替换，可复制译文；原文未改动。"); return;
        }
        final int request = generation, cursor = edit.start + translated.length();
        verifyReplacement(request, replacement, cursor, 0);
    }
    private void verifyReplacement(int request, String replacement, int cursor, int attempt) {
        main.postDelayed(() -> {
            if (destroyed || panel == null || request != generation || editor == null) return;
            if (editor.refresh() && replacement.equals(text(editor))) {
                Bundle selection = new Bundle(); selection.putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_START_INT, cursor); selection.putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_END_INT, cursor);
                editor.performAction(AccessibilityNodeInfo.ACTION_SET_SELECTION, selection);
                // Ignore delayed delivery of our write, not edits the user makes after completion.
                clearDismissed(); dismissedEditor = AccessibilityNodeInfo.obtain(editor); ignoreOwnEditsUntil = SystemClock.uptimeMillis();
                boolean local = localResult; closePanel(); showSuccess(local); scheduleRefresh();
            } else if (attempt < 4) verifyReplacement(request, replacement, cursor, attempt + 1);
            else { applying = false;
                if (quick) { closePanel(); toast("应用未完整写入译文，请检查输入框，或使用译镜键盘重试。"); scheduleRefresh(); }
                else status.setText("应用未完整写入译文，请检查原输入框。译文仍可复制。");
            }
        }, 100);
    }
    private void showSuccess(boolean local) {
        removeShortcut(); LinearLayout label = Ui.row(this); label.setPadding(dp(8), 0, dp(8), 0); label.setBackground(Ui.rounded(this, Ui.BG, 10, 0));
        label.addView(Ui.text(this, "已替换", 12, Ui.NAVY, false));
        if (local) label.addView(Ui.attribution(this), new LinearLayout.LayoutParams(dp(100), dp(18)));
        shortcut = label; WindowManager.LayoutParams p = shortcutParams(); p.width = dp(local ? 164 : 64);
        try { windows.addView(label, p); main.postDelayed(() -> { if (shortcut == label) { removeShortcut(); scheduleRefresh(); } }, 4000); }
        catch (RuntimeException error) { shortcut = null; }
    }
    private void cancelTask() { generation++; if (engine != null) engine.cancel(); if (task != null) { task.cancel(true); task = null; } }
    private void closePanel() {
        cancelTask(); if (panel != null) { remove(panel); panel = null; }
        if (editor != null) { editor.recycle(); editor = null; }
        edit = null; translated = ""; applying = false; output = null; status = null; apply = null; attribution = null;
    }
    private void toast(String value) { Toast.makeText(this, value == null ? "输入翻译暂不可用，请重试。" : value, Toast.LENGTH_LONG).show(); }
    @Override protected void dump(java.io.FileDescriptor fd, java.io.PrintWriter writer, String[] args) {
        // Deliberately metadata only: diagnostics never print draft text, selections or credentials.
        writer.println("connected=" + connected() + " suspended=" + screenSuspended + " locked=" + locked());
        writer.println("serviceInfo=" + getServiceInfo());
        writer.println("shortcutEnabled=" + (prefs != null && prefs.inputShortcut()) + " shown=" + (shortcut != null) + " panel=" + (panel != null));
        if (windows != null) writer.println("keyboardTop=" + keyboardTop() + " screenHeight=" + screenHeight());
        writer.println("overlayError=" + overlayError);
        AccessibilityNodeInfo node = findFocus(AccessibilityNodeInfo.FOCUS_INPUT);
        try {
            if (node == null) writer.println("focus=null");
            else writer.println("focused=" + node.isFocused() + " editable=" + node.isEditable() + " enabled=" + node.isEnabled()
                    + " visible=" + node.isVisibleToUser() + " password=" + password(node) + " usable=" + usable(node)
                    + " setText=" + node.getActionList().contains(AccessibilityNodeInfo.AccessibilityAction.ACTION_SET_TEXT));
        } finally { if (node != null) node.recycle(); }
    }
    @Override public void onConfigurationChanged(Configuration configuration) { super.onConfigurationChanged(configuration); closePanel(); removeShortcut(); scheduleRefresh(); }
    @Override public void onDestroy() {
        destroyed = true; if (instance.get() == this) instance.clear(); main.removeCallbacksAndMessages(null);
        closePanel(); removeShortcut(); clearDismissed(); if (registered) unregisterReceiver(screenOff);
        if (engine != null) engine.close(); worker.shutdownNow(); super.onDestroy();
    }
}
