package com.rongji.screentranslate;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.NetworkCapabilities;
import java.io.InterruptedIOException;
import java.util.*;

/** Per-owner cancellable engine. All blocking work runs on the owner's worker, never on the UI. */
final class TranslationEngine implements AutoCloseable {
    private final Context context;
    private final DeepSeekClient cloud = new DeepSeekClient();
    private final OfflineTranslator offline = new OfflineTranslator();
    private volatile long version; private volatile boolean closed;
    TranslationEngine(Context context) { this.context = context.getApplicationContext(); }
    static final class Config {
        final String mode, model; final boolean longText;
        Config(AppPrefs prefs) { this(prefs.engineMode(), prefs.model(), prefs.autoLongText()); }
        Config(String mode, String model, boolean longText) { this.mode = mode; this.model = model; this.longText = longText; }
    }
    private boolean connected() {
        ConnectivityManager manager = context.getSystemService(ConnectivityManager.class);
        NetworkCapabilities capabilities = manager.getNetworkCapabilities(manager.getActiveNetwork());
        return capabilities != null && capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET);
    }
    private void check(long request) throws InterruptedIOException {
        if (closed || version != request || Thread.currentThread().isInterrupted()) throw new InterruptedIOException("已取消翻译。");
    }
    private TranslationRouter router(Config config, long request, String key, String fallbackLanguage) {
        return new TranslationRouter((text, source, target) -> offline.translate(text, source, target, fallbackLanguage, () -> check(request)), new TranslationRouter.Cloud() {
            public String translate(String text, String source, String target) throws Exception { check(request); return cloud.translate(key, config.model, text, source, target); }
            public Map<String, String> blocks(List<ScreenTextBlock> blocks, String source, String target) throws Exception { check(request); return cloud.translateBlocks(key, config.model, blocks, source, target); }
        }, () -> check(request));
    }
    private String key(Config config) throws Exception {
        if (TranslationRouter.OFFLINE.equals(config.mode)) return "";
        try { return new KeyVault(context).read(); }
        catch (Exception e) { if (TranslationRouter.LLM.equals(config.mode)) throw e; return ""; }
    }
    TranslationRouter.Result text(String text, String source, String target, Config config) throws Exception {
        long request = version; String key = key(config); check(request);
        return router(config, request, key, null).text(text, source, target, new TranslationRouter.Options(config.mode, config.longText, !key.isEmpty() && connected()));
    }
    TranslationRouter.Result blocks(List<ScreenTextBlock> blocks, String source, String target, Config config) throws Exception {
        long request = version; String key = key(config); check(request);
        String fallback = null;
        if ("自动检测".equals(source) && !TranslationRouter.LLM.equals(config.mode)) {
            StringBuilder text = new StringBuilder(); for (ScreenTextBlock block : blocks) if (!BlockTranslationRules.preserveOriginal(block.text)) text.append(block.text).append('\n');
            try { if (text.length() > 0) fallback = offline.detect(text.toString(), () -> check(request)); }
            catch (Exception ignored) { check(request); /* Per-block routing can still use the configured cloud fallback. */ }
        }
        return router(config, request, key, fallback).blocks(blocks, source, target, new TranslationRouter.Options(config.mode, config.longText, !key.isEmpty() && connected()));
    }
    void cancel() { version++; cloud.cancel(); }
    @Override public void close() { closed = true; cancel(); offline.close(); }
}
