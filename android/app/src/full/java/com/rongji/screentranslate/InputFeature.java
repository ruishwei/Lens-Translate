package com.rongji.screentranslate;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.widget.Button;
import android.widget.LinearLayout;

/** The full edition owns every entry into the optional input feature. */
final class InputFeature {
    static void addHomeSetup(Activity activity, LinearLayout page) {
        Ui.add(page, Ui.text(activity, "译镜键盘：输入中文 → 点翻译 → 点原应用发送。本机拼音与离线翻译，无需无障碍权限。有选区只替换选区，替换后可撤销。", 13, Ui.MUTED, false), 14);
        addSettingsSetup(activity, page);
    }
    static void addSettingsSetup(Activity activity, LinearLayout page) {
        Button setup = Ui.button(activity, "设置输入翻译", false);
        setup.setOnClickListener(v -> activity.startActivity(new Intent(activity, InputAssistSettingsActivity.class)));
        Ui.add(page, setup, 8);
    }
    static void suspendForScreen(boolean suspended) { InputAssistService.suspendForScreen(suspended); }
    static void requestTranslation(Context context) { InputAssistService.requestTranslation(context); }
}
