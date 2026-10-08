package com.rongji.screentranslate;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.media.projection.MediaProjectionConfig;
import android.media.projection.MediaProjectionManager;
import android.os.Build;
import android.os.Bundle;

/** A fresh system consent after sharing ends; closing it never stops the floating shortcut. */
public final class CapturePermissionActivity extends Activity {
    private static final int CAPTURE = 1;
    static Intent captureIntent(Context context) {
        MediaProjectionManager manager = context.getSystemService(MediaProjectionManager.class);
        // A transparent overlay needs full-display coordinates rather than a movable app window.
        return Build.VERSION.SDK_INT >= 34
                ? manager.createScreenCaptureIntent(MediaProjectionConfig.createConfigForDefaultDisplay())
                : manager.createScreenCaptureIntent();
    }
    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        if (!ScreenCaptureService.running) { finish(); return; }
        if (state == null) startActivityForResult(captureIntent(this), CAPTURE);
    }
    @Override protected void onActivityResult(int request, int result, Intent data) {
        super.onActivityResult(request, result, data);
        if (request != CAPTURE) return;
        if (result == RESULT_OK && data != null && ScreenCaptureService.running) {
            startForegroundService(new Intent(this, ScreenCaptureService.class)
                    .putExtra("data", data).putExtra("resultCode", result).putExtra("captureAfterConsent", true)
                    .putExtra("selectArea", getIntent().getBooleanExtra("selectArea", false)));
        }
        finish();
    }
}
