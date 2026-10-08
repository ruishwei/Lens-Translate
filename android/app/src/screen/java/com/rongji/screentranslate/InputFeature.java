package com.rongji.screentranslate;

import android.app.Activity;
import android.content.Context;
import android.widget.LinearLayout;

/** Screen builds have no input services, activities, dictionaries or setup entries. */
final class InputFeature {
    static void addHomeSetup(Activity activity, LinearLayout page) { }
    static void addSettingsSetup(Activity activity, LinearLayout page) { }
    static void suspendForScreen(boolean suspended) { }
    static void requestTranslation(Context context) { }
}
