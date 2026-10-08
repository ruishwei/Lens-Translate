package com.rongji.screentranslate;

import android.content.Context;
import android.content.SharedPreferences;
import org.json.JSONArray;
import org.json.JSONObject;

public final class AppPrefs {
    private final SharedPreferences prefs;
    public AppPrefs(Context context) { prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE); }
    public String source() { return prefs.getString("source", TranslationRules.SOURCES[0]); }
    public String target() { return prefs.getString("target", TranslationRules.TARGETS[0]); }
    public String model() { return prefs.getString("model", "deepseek-flash"); }
    public void model(String value) { prefs.edit().putString("model", value.trim()).apply(); }
    public String engineMode() { return prefs.getString("engine_mode", TranslationRouter.AUTO); }
    public void engineMode(String value) { prefs.edit().putString("engine_mode", TranslationRouter.validMode(value)).apply(); }
    public boolean autoLongText() { return prefs.getBoolean("auto_long_text", false); }
    public void autoLongText(boolean value) { prefs.edit().putBoolean("auto_long_text", value).apply(); }
    public String writingTarget() { return prefs.getString("writing_target", "English"); }
    public void writingTarget(String value) { prefs.edit().putString("writing_target", value).apply(); }
    public String keyboardLayout() { String value = prefs.getString("keyboard_layout", "pinyin"); return "nine".equals(value) || "stroke".equals(value) ? value : "pinyin"; }
    public void keyboardLayout(String value) { prefs.edit().putString("keyboard_layout", value).apply(); }
    public boolean keyboardPrediction() { return prefs.getBoolean("keyboard_prediction", true); }
    public void keyboardPrediction(boolean enabled) { prefs.edit().putBoolean("keyboard_prediction", enabled).apply(); }
    public boolean keyboardChinese() { return prefs.getBoolean("keyboard_chinese", true); }
    public void keyboardChinese(boolean enabled) { prefs.edit().putBoolean("keyboard_chinese", enabled).apply(); }
    public boolean inputShortcut() { return prefs.getBoolean("input_shortcut", true); }
    public void inputShortcut(boolean value) { prefs.edit().putBoolean("input_shortcut", value).apply(); }
    public void languages(String source, String target) { prefs.edit().putString("source", source).putString("target", target).apply(); }
    public boolean historyEnabled() { return prefs.getBoolean("history_enabled", true); }
    public void historyEnabled(boolean enabled) { synchronized (AppPrefs.class) { prefs.edit().putBoolean("history_enabled", enabled).apply(); } }
    public JSONArray history() { try { return new JSONArray(prefs.getString("history", "[]")); } catch (Exception ignored) { return new JSONArray(); } }
    public void clearHistory() { synchronized (AppPrefs.class) { prefs.edit().remove("history").apply(); } }
    public void addHistory(String source, String translated, String target) {
        addHistory(source, translated, target, "DeepSeek", false);
    }
    public void addHistory(String source, String translated, String target, String engine, boolean offline) {
        synchronized (AppPrefs.class) {
            if (!historyEnabled()) return;
            try {
                JSONArray old = history(), next = new JSONArray();
                next.put(new JSONObject().put("source", source).put("translated", translated).put("target", target).put("engine", engine).put("offline", offline).put("time", System.currentTimeMillis()));
                for (int i = 0; i < Math.min(old.length(), 49); i++) next.put(old.getJSONObject(i));
                prefs.edit().putString("history", next.toString()).apply();
            } catch (Exception ignored) { /* A history write must not discard a successful translation. */ }
        }
    }
}
