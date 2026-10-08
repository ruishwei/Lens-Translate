package com.rongji.screentranslate;

import android.app.Activity;
import android.os.Bundle;
import android.widget.*;
import com.google.android.gms.tasks.Task;
import com.google.mlkit.common.model.*;
import com.google.mlkit.nl.translate.*;
import java.util.*;

/** Model downloads are initiated here by the user, never implicitly during translation. */
public final class OfflineModelsActivity extends Activity {
    private TextView status; private Spinner language; private Button download, delete; private CheckBox wifi;
    private final List<String> codes = new ArrayList<>();
    private final RemoteModelManager manager = RemoteModelManager.getInstance();
    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        ScrollView scroll = new ScrollView(this); LinearLayout page = Ui.column(this); scroll.addView(page);
        page.setPadding(Ui.dp(this, 22), Ui.dp(this, 30), Ui.dp(this, 22), Ui.dp(this, 30)); scroll.setBackgroundColor(Ui.BG);
        Ui.systemInsets(scroll);
        page.addView(Ui.text(this, "离线语言包", 24, Ui.NAVY, true));
        Ui.add(page, Ui.text(this, "先联网下载，之后断网也能翻译，无需 API Key，不消耗 LLM token。中文与英语互译只需下载中文包；英语组件已内置。", 14, Ui.NAVY, false), 16);
        Ui.add(page, Ui.text(this, "Google Translate 提供本机机器翻译，适合日常短句。非英语语言间会经过英语中转，质量可能下降。语言包通常约 30 MB，下载需要连接 Google 服务。", 12, Ui.MUTED, false), 12);
        codes.add("zh"); for (String code : TranslateLanguage.getAllLanguages()) if (!code.equals("en") && !code.equals("zh")) codes.add(code);
        String[] names = codes.stream().map(c -> OfflineTranslator.display(c) + " (" + c + ")").toArray(String[]::new);
        language = Ui.spinner(this, names, names[0]); Ui.add(page, language, 18);
        wifi = new CheckBox(this); wifi.setText("仅通过 Wi-Fi 下载"); wifi.setChecked(true); Ui.add(page, wifi, 10);
        download = Ui.button(this, "下载所选语言包", true); download.setOnClickListener(v -> change(false)); Ui.add(page, download, 12);
        delete = Ui.button(this, "删除所选语言包", false); delete.setOnClickListener(v -> change(true)); Ui.add(page, delete, 8);
        status = Ui.text(this, "正在检查本机语言包…", 14, Ui.NAVY, false); Ui.add(page, status, 18);
        Button refresh = Ui.button(this, "刷新下载状态", false); refresh.setOnClickListener(v -> refresh()); Ui.add(page, refresh, 12);
        Button back = Ui.button(this, "返回", false); back.setOnClickListener(v -> finish()); Ui.add(page, back, 16);
        Ui.add(page, Ui.text(this, "离线处理不上传原文或译文。ML Kit 可能连接 Google 下载模型、接收更新及发送性能 / 使用指标。", 12, Ui.MUTED, false), 18);
        TextView legal = Ui.text(this, "Google Translate 翻译说明", 12, Ui.ACCENT, false);
        legal.setOnClickListener(v -> new android.app.AlertDialog.Builder(this).setTitle("Google Translate")
                .setMessage("本应用包含 Google Translate 提供的自动翻译。Google 不对译文准确性、可靠性、适销性、特定用途适用性或不侵权作出保证。\n\nhttps://cloud.google.com/translate\nhttps://developers.google.com/ml-kit/terms")
                .setPositiveButton("知道了", null).show()); Ui.add(page, legal, 12);
        setContentView(scroll); refresh();
    }
    private void change(boolean remove) {
        String code = codes.get(language.getSelectedItemPosition()); TranslateRemoteModel model = new TranslateRemoteModel.Builder(code).build();
        DownloadConditions.Builder conditions = new DownloadConditions.Builder(); if (wifi.isChecked()) conditions.requireWifi();
        Task<Void> task = remove ? manager.deleteDownloadedModel(model) : manager.download(model, conditions.build());
        status.setText(remove ? "正在删除…" : "已请求下载 " + OfflineTranslator.display(code) + "；完成前请保持网络连接，可刷新状态。");
        download.setEnabled(false); delete.setEnabled(false);
        task.addOnCompleteListener(this, result -> {
            download.setEnabled(true); delete.setEnabled(true);
            if (result.isSuccessful()) refresh(); else status.setText("语言包操作未完成，请检查 Wi-Fi 和 Google 服务连接后重试。也可先使用 DeepSeek。");
        });
    }
    private void refresh() {
        manager.getDownloadedModels(TranslateRemoteModel.class).addOnSuccessListener(this, models -> {
            List<String> names = new ArrayList<>(); for (TranslateRemoteModel model : models) names.add(OfflineTranslator.display(model.getLanguage())); Collections.sort(names);
            status.setText("已下载：" + (names.isEmpty() ? "暂无" : android.text.TextUtils.join("、", names)) + "\n英语组件：内置\n繁体输出请使用 DeepSeek。");
        }).addOnFailureListener(this, error -> status.setText("暂时无法读取离线语言包，请重试。"));
    }
}
