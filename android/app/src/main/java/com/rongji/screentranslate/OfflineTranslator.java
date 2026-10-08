package com.rongji.screentranslate;

import com.google.android.gms.tasks.Task;
import com.google.android.gms.tasks.Tasks;
import com.google.mlkit.common.model.RemoteModelManager;
import com.google.mlkit.nl.languageid.LanguageIdentification;
import com.google.mlkit.nl.languageid.LanguageIdentifier;
import com.google.mlkit.nl.translate.*;
import java.io.InterruptedIOException;
import java.util.*;
import java.util.concurrent.*;

/** Only uses installed language packs. Downloads are explicit actions in OfflineModelsActivity. */
final class OfflineTranslator implements AutoCloseable {
    private LanguageIdentifier identifier;
    private final Map<String, Translator> translators = new LinkedHashMap<>();
    private boolean closed;
    static String code(String name) {
        switch (name) {
            case "English": return "en"; case "简体中文": case "繁體中文": return "zh";
            case "日本語": return "ja"; case "한국어": return "ko"; case "Français": return "fr";
            case "Deutsch": return "de"; case "Español": return "es"; case "Русский": return "ru";
            default: return null;
        }
    }
    static String display(String code) { return Locale.forLanguageTag(code).getDisplayLanguage(Locale.SIMPLIFIED_CHINESE); }
    static <T> T await(Task<T> task, TranslationRouter.Check check) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(60);
        while (true) {
            check.run();
            try { T value = Tasks.await(task, 200, TimeUnit.MILLISECONDS); check.run(); return value; }
            catch (TimeoutException e) { if (System.nanoTime() >= deadline) throw new java.io.IOException("离线引擎响应超时，请重试。"); }
            catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new InterruptedIOException("已取消翻译。"); }
            catch (ExecutionException e) { throw new java.io.IOException("离线引擎暂时不可用，请检查语言包后重试。", e); }
        }
    }
    private synchronized LanguageIdentifier identifier() throws InterruptedIOException {
        if (closed) throw new InterruptedIOException("已取消翻译。");
        if (identifier == null) identifier = LanguageIdentification.getClient(); return identifier;
    }
    String translate(String text, String source, String target, TranslationRouter.Check check) throws Exception {
        return translate(text, source, target, null, check);
    }
    String detect(String text, TranslationRouter.Check check) throws Exception {
        return TranslateLanguage.fromLanguageTag(await(identifier().identifyLanguage(text), check));
    }
    String translate(String text, String source, String target, String fallbackLanguage, TranslationRouter.Check check) throws Exception {
        if ("繁體中文".equals(target)) throw new java.io.IOException("离线引擎暂不保证繁体输出，请选简体中文或 DeepSeek。");
        String from = code(source), to = code(target);
        if ("自动检测".equals(source)) { from = detect(text, check); if (from == null) from = fallbackLanguage; }
        if (from == null || to == null) throw new java.io.IOException("无法确定离线翻译语言，请手动选择原文语言，或使用 DeepSeek。");
        if (from.equals(to)) return text;
        Set<TranslateRemoteModel> installed = await(RemoteModelManager.getInstance().getDownloadedModels(TranslateRemoteModel.class), check);
        Set<String> available = new HashSet<>(); available.add("en");
        for (TranslateRemoteModel model : installed) available.add(model.getLanguage());
        if (!available.contains(from) || !available.contains(to)) {
            String missing = !available.contains(from) ? from : to;
            throw new java.io.IOException("缺少" + display(missing) + "离线包，请在设置 → 离线语言包中下载；离线模式不会自动调用 DeepSeek。");
        }
        Translator translator;
        synchronized (this) {
            if (closed) throw new InterruptedIOException("已取消翻译。");
            String pair = from + ":" + to;
            translator = translators.get(pair);
            if (translator == null) {
                if (translators.size() >= 4) { String oldest = translators.keySet().iterator().next(); translators.remove(oldest).close(); }
                translator = Translation.getClient(new TranslatorOptions.Builder().setSourceLanguage(from).setTargetLanguage(to).build());
                translators.put(pair, translator);
            }
        }
        String translated = await(translator.translate(text), check);
        if (translated.trim().isEmpty()) throw new java.io.IOException("离线翻译返回空内容，请重试。");
        return translated;
    }
    @Override public synchronized void close() {
        closed = true; if (identifier != null) identifier.close();
        for (Translator translator : translators.values()) translator.close(); translators.clear();
    }
}
