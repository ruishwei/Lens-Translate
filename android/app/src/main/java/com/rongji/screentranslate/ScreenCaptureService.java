package com.rongji.screentranslate;

import android.app.*;
import android.content.*;
import android.content.pm.ServiceInfo;
import android.content.res.Configuration;
import android.graphics.*;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.media.Image;
import android.media.ImageReader;
import android.media.projection.MediaProjection;
import android.media.projection.MediaProjectionManager;
import android.os.*;
import android.provider.Settings;
import android.util.DisplayMetrics;
import android.view.*;
import android.widget.*;
import java.nio.ByteBuffer;
import java.util.Collections;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/** Persistent user-enabled bubble; each fresh consent owns at most one virtual display. */
public final class ScreenCaptureService extends Service {
    public static volatile boolean running;
    public static volatile boolean captureReady;
    private static final String STOP = "com.rongji.screentranslate.STOP";
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService network = Executors.newSingleThreadExecutor();
    private WindowManager windows;
    private MediaProjection projection;
    private VirtualDisplay display;
    private ImageReader reader;
    private BubbleView bubble;
    private View overlay;
    private WindowManager.LayoutParams bubbleParams;
    private OcrEngine ocr;
    private AppPrefs prefs;
    private Future<?> networkTask;
    private TranslationEngine activeClient;
    private TranslationEngine engine;
    private String resultEngine = "";
    private boolean resultOffline;
    private Bitmap screenshot;
    private OcrDocument document;
    private Map<String, String> translations = Collections.emptyMap();
    private Rect crop;
    private boolean destroyed, busy, capturePending, areaMode, listenerRegistered, screenOffRegistered;
    private int width, height;
    private long generation, captureDeadline;
    private final BroadcastReceiver screenOff = new BroadcastReceiver() {
        @Override public void onReceive(Context context, Intent intent) { pauseProjection(); }
    };

    private MediaProjection.Callback projectionEvents;
    private final DisplayManager.DisplayListener rotationEvents = new DisplayManager.DisplayListener() {
        public void onDisplayAdded(int id) {}
        public void onDisplayRemoved(int id) {}
        public void onDisplayChanged(int id) {
            if (Build.VERSION.SDK_INT < 34 && display != null) { Point size = screenSize(); resize(size.x, size.y); }
        }
    };
    @Override public void onCreate() {
        super.onCreate(); windows = getSystemService(WindowManager.class); prefs = new AppPrefs(this); ocr = new OcrEngine(); engine = new TranslationEngine(this);
        getSystemService(NotificationManager.class).createNotificationChannel(new NotificationChannel("capture", "屏幕翻译", NotificationManager.IMPORTANCE_LOW));
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(screenOff, new IntentFilter(Intent.ACTION_SCREEN_OFF), Context.RECEIVER_NOT_EXPORTED);
        else registerReceiver(screenOff, new IntentFilter(Intent.ACTION_SCREEN_OFF));
        screenOffRegistered = true;
    }
    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null || STOP.equals(intent.getAction())) { stopSelf(); return START_NOT_STICKY; }
        Intent consent = Build.VERSION.SDK_INT >= 33 ? intent.getParcelableExtra("data", Intent.class) : intent.getParcelableExtra("data");
        if (!Settings.canDrawOverlays(this)) { stopSelf(); return START_NOT_STICKY; }
        if (projection != null) return START_NOT_STICKY;
        try {
            // Keep the user-visible shortcut alive independently of a revocable capture session.
            startForegroundMode(false); running = true;
            if (bubble == null) showBubble();
            if (consent == null || intent.getIntExtra("resultCode", Activity.RESULT_CANCELED) != Activity.RESULT_OK) return START_NOT_STICKY;
            startForegroundMode(true);
            projection = getSystemService(MediaProjectionManager.class).getMediaProjection(intent.getIntExtra("resultCode", Activity.RESULT_CANCELED), consent);
            if (projection == null) throw new IllegalStateException("录屏授权已失效，请重新开启");
            final MediaProjection session = projection;
            projectionEvents = new MediaProjection.Callback() {
                @Override public void onStop() { if (projection == session) pauseProjection(); }
                @Override public void onCapturedContentResize(int w, int h) {
                    if (!destroyed && projection == session && w > 0 && h > 0) resize(w, h);
                }
            };
            projection.registerCallback(projectionEvents, main);
            Point size = screenSize(); size = captureSize(size.x, size.y); width = size.x; height = size.y;
            reader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 3); startDraining();
            display = projection.createVirtualDisplay("LensTranslate", width, height, getResources().getConfiguration().densityDpi,
                    DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, reader.getSurface(), null, main);
            getSystemService(DisplayManager.class).registerDisplayListener(rotationEvents, main); listenerRegistered = true;
            captureReady = true; getSystemService(NotificationManager.class).notify(101, notification());
            if (intent.getBooleanExtra("captureAfterConsent", false)) {
                long token = generation;
                main.postDelayed(() -> { if (isCurrent(token) && captureReady) capture(intent.getBooleanExtra("selectArea", false)); }, 450);
            }
        } catch (Exception error) {
            pauseProjection();
            Toast.makeText(this, "截图授权未能启动，悬浮球已保留。点击可重新授权。", Toast.LENGTH_LONG).show();
        }
        return START_NOT_STICKY;
    }
    private void startForegroundMode(boolean capture) {
        if (Build.VERSION.SDK_INT < 29) { startForeground(101, notification()); return; }
        int type = capture ? ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION : 0;
        if (Build.VERSION.SDK_INT >= 34) type |= ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE;
        startForeground(101, notification(), type);
    }
    private void releaseProjection() {
        captureReady = false;
        MediaProjection previous = projection; projection = null;
        if (previous != null && projectionEvents != null) previous.unregisterCallback(projectionEvents);
        projectionEvents = null;
        if (listenerRegistered) { getSystemService(DisplayManager.class).unregisterDisplayListener(rotationEvents); listenerRegistered = false; }
        if (display != null) { display.release(); display = null; }
        if (reader != null) { reader.setOnImageAvailableListener(null, null); reader.close(); reader = null; }
        if (previous != null) previous.stop();
    }
    private void pauseProjection() {
        if (destroyed) return;
        closeSnapshot(); releaseProjection();
        if (running) startForegroundMode(false);
    }
    private Notification notification() {
        PendingIntent open = PendingIntent.getActivity(this, 0, new Intent(this, MainActivity.class), PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        PendingIntent stop = PendingIntent.getService(this, 1, new Intent(this, ScreenCaptureService.class).setAction(STOP), PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        return new Notification.Builder(this, "capture").setSmallIcon(R.drawable.ic_notification).setContentTitle("译镜 · 悬浮翻译已开启")
                .setContentText(captureReady ? "单击全屏翻译，长按打开圆形菜单。" : "悬浮球已保留，点击翻译时重新授权截图。")
                .setContentIntent(open).setOngoing(true)
                .addAction(new Notification.Action.Builder(null, "停止悬浮翻译", stop).build()).build();
    }
    private Point screenSize() {
        if (Build.VERSION.SDK_INT >= 30) { Rect bounds = windows.getMaximumWindowMetrics().getBounds(); return new Point(bounds.width(), bounds.height()); }
        DisplayMetrics metrics = new DisplayMetrics(); windows.getDefaultDisplay().getRealMetrics(metrics); return new Point(metrics.widthPixels, metrics.heightPixels);
    }
    private int dp(float value) { return Ui.dp(this, value); }
    private Point captureSize(int w, int h) {
        float scale = Math.min(1f, 2400f / Math.max(w, h));
        return new Point(Math.max(1, Math.round(w * scale)), Math.max(1, Math.round(h * scale)));
    }
    private void startDraining() {
        if (reader == null || destroyed) return;
        reader.setOnImageAvailableListener(r -> {
            if (capturePending || destroyed) return;
            try (Image frame = r.acquireLatestImage()) { /* Idle pixels are discarded, not saved. */ }
            catch (IllegalStateException ignored) { }
        }, main);
    }
    private void resize(int w, int h) {
        Point bounded = captureSize(w, h); w = bounded.x; h = bounded.y;
        if (reader == null || (w == width && h == height)) return;
        boolean hadSnapshot = screenshot != null || busy;
        closeSnapshot(); width = w; height = h;
        ImageReader previous = reader;
        reader = ImageReader.newInstance(w, h, PixelFormat.RGBA_8888, 3);
        if (display != null) { display.setSurface(null); display.resize(w, h, getResources().getConfiguration().densityDpi); display.setSurface(reader.getSurface()); }
        previous.setOnImageAvailableListener(null, null); previous.close(); startDraining();
        if (bubble != null && bubbleParams != null) { clampBubble(); windows.updateViewLayout(bubble, bubbleParams); }
        if (hadSnapshot) Toast.makeText(this, "屏幕尺寸已改变，请重新截屏选择区域。", Toast.LENGTH_SHORT).show();
    }
    private void showBubble() {
        bubble = new BubbleView(); bubble.setContentDescription("单击全屏翻译，长按打开圆形菜单，拖动移动悬浮球");
        bubble.setOnLongClickListener(v -> { showMenu(); return true; });
        bubbleParams = new WindowManager.LayoutParams(dp(48), dp(48), WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN, PixelFormat.TRANSLUCENT);
        bubbleParams.gravity = Gravity.TOP | Gravity.START; bubbleParams.x = Math.max(0, screenSize().x - dp(60)); bubbleParams.y = dp(220);
        bubble.setOnTouchListener(new View.OnTouchListener() {
            private float downX, downY; private int initialX, initialY; private boolean moved, longPressed;
            private final Runnable longPress = () -> { longPressed = true; if (bubble != null) bubble.performLongClick(); };
            public boolean onTouch(View v, MotionEvent event) {
                switch (event.getActionMasked()) {
                    case MotionEvent.ACTION_DOWN:
                        downX = event.getRawX(); downY = event.getRawY(); initialX = bubbleParams.x; initialY = bubbleParams.y; moved = false; longPressed = false;
                        main.postDelayed(longPress, 650); return true;
                    case MotionEvent.ACTION_MOVE:
                        if (Math.hypot(event.getRawX() - downX, event.getRawY() - downY) > dp(8)) { moved = true; main.removeCallbacks(longPress); }
                        if (moved && !destroyed) { bubbleParams.x = initialX + (int)(event.getRawX() - downX); bubbleParams.y = initialY + (int)(event.getRawY() - downY); clampBubble(); windows.updateViewLayout(bubble, bubbleParams); } return true;
                    case MotionEvent.ACTION_UP:
                        main.removeCallbacks(longPress); if (!moved && !longPressed) v.performClick(); return true;
                    case MotionEvent.ACTION_CANCEL: main.removeCallbacks(longPress); return true;
                    default: return false;
                }
            }
        }); windows.addView(bubble, bubbleParams);
    }
    @android.annotation.SuppressLint("AppCompatCustomView")
    private final class BubbleView extends ImageView {
        BubbleView() {
            super(ScreenCaptureService.this); setClickable(true); setScaleType(ScaleType.FIT_CENTER);
            setImageResource(R.drawable.ic_lens_mark); setPadding(dp(10), dp(10), dp(10), dp(10));
            setBackground(new android.graphics.drawable.InsetDrawable(Ui.rounded(ScreenCaptureService.this, Ui.NAVY, 20, 0), dp(4)));
        }
        @Override public boolean performClick() { super.performClick(); capture(false); return true; }
    }
    private void clampBubble() { Point size = screenSize(); bubbleParams.x = Math.max(0, Math.min(bubbleParams.x, size.x - dp(48))); bubbleParams.y = Math.max(dp(24), Math.min(bubbleParams.y, size.y - dp(72))); }
    private void setBubbleVisible(boolean visible) { if (bubble != null) bubble.setVisibility(visible ? View.VISIBLE : View.INVISIBLE); InputFeature.suspendForScreen(!visible); }
    private void showMenu() {
        if (destroyed || busy) return;
        removeOverlay(); setBubbleVisible(false);
        FrameLayout backdrop = new FrameLayout(this); backdrop.setBackgroundColor(Color.TRANSPARENT);
        backdrop.setContentDescription("圆形翻译菜单，点击外部返回悬浮球"); backdrop.setOnClickListener(v -> closeSnapshot());
        RadialMenuView menu = new RadialMenuView(this, new RadialMenuView.Actions() {
            public void fullScreen() { capture(false); }
            public void region() { capture(true); }
            public void settings() { closeSnapshot(); startActivity(new Intent(ScreenCaptureService.this, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP).putExtra("openSettings", true)); }
            public void input() { closeSnapshot(); main.postDelayed(() -> InputFeature.requestTranslation(ScreenCaptureService.this), 200); }
            public void dismiss() { closeSnapshot(); }
        });
        Point size = screenSize(); int diameter = Math.min(dp(280), Math.min(size.x, size.y) - dp(64));
        backdrop.addView(menu, new FrameLayout.LayoutParams(diameter, diameter, Gravity.CENTER));
        attachOverlay(backdrop);
    }
    private void capture(boolean selectArea) {
        if (destroyed || busy) return;
        if (reader == null || projection == null) {
            closeSnapshot();
            startActivity(new Intent(this, CapturePermissionActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP)
                    .putExtra("selectArea", selectArea));
            return;
        }
        invalidateWork(); removeOverlay(); screenshot = null; document = null; translations = Collections.emptyMap(); crop = null;
        areaMode = selectArea; busy = true; capturePending = true; setBubbleVisible(false);
        reader.setOnImageAvailableListener(null, null);
        try (Image old = reader.acquireLatestImage()) { /* Discard any frame still containing our UI. */ } catch (Exception ignored) { }
        captureDeadline = SystemClock.uptimeMillis() + 4500;
        long token = generation; main.postDelayed(() -> readCapture(token), 320);
    }
    private void readCapture(long token) {
        if (!isCurrent(token) || !capturePending || reader == null) return;
        try (Image frame = reader.acquireLatestImage()) {
            if (frame == null) {
                if (SystemClock.uptimeMillis() < captureDeadline) { main.postDelayed(() -> readCapture(token), 80); return; }
                captureFailed("没有获取到屏幕画面，请重新开启悬浮翻译。"); return;
            }
            Image.Plane plane = frame.getPlanes()[0];
            ByteBuffer pixels = PixelBuffers.rgba(plane.getBuffer(), frame.getWidth(), frame.getHeight(), plane.getPixelStride(), plane.getRowStride());
            Bitmap frozen = Bitmap.createBitmap(frame.getWidth(), frame.getHeight(), Bitmap.Config.ARGB_8888); frozen.copyPixelsFromBuffer(pixels);
            screenshot = frozen; capturePending = false; busy = false; startDraining();
            if (areaMode) showSelection(); else recognize(new Rect(0, 0, frozen.getWidth(), frozen.getHeight()));
        } catch (Exception error) { captureFailed("无法读取屏幕画面，请重新授权后重试。"); }
    }
    private boolean isCurrent(long token) { return !destroyed && token == generation; }
    private void invalidateWork() {
        generation++; capturePending = false; busy = false;
        if (activeClient != null) { activeClient.cancel(); activeClient = null; }
        if (networkTask != null) { networkTask.cancel(true); networkTask = null; }
    }
    private void closeSnapshot() {
        invalidateWork(); removeOverlay(); screenshot = null; document = null; crop = null; translations = Collections.emptyMap();
        startDraining(); setBubbleVisible(true);
    }
    private void captureFailed(String message) {
        if (destroyed) return;
        showCaptureProblem("未能完成屏幕翻译", message);
    }
    /** Preserve local evidence and actionable feedback instead of a disappearing success/error toast. */
    private void showCaptureProblem(String title, String message) {
        invalidateWork(); startDraining();
        FrameLayout root = new FrameLayout(this); root.setBackgroundColor(Color.TRANSPARENT);
        root.setOnClickListener(v -> closeSnapshot());
        LinearLayout card = Ui.column(this); card.setPadding(dp(18), dp(16), dp(18), dp(12));
        card.setBackground(Ui.rounded(this, 0xF220213B, 16, 0)); card.setOnClickListener(v -> {});
        card.addView(Ui.text(this, title, 18, Color.WHITE, true));
        TextView detail = Ui.text(this, message, 14, Color.WHITE, false); detail.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE); Ui.add(card, detail, 8);
        if (screenshot != null) {
            Button preview = Ui.button(this, "查看本次截屏与识别文字", true); preview.setOnClickListener(v -> showCapturePreview()); Ui.add(card, preview, 12);
        }
        Button retry = Ui.button(this, "重新截屏", false); retry.setOnClickListener(v -> capture(areaMode)); Ui.add(card, retry, 6);
        if (document != null && BlockTranslationRules.translatableCount(document.blocks) > 0) {
            Button settings = Ui.button(this, "检查翻译语言与引擎设置", false); settings.setOnClickListener(v -> {
                closeSnapshot(); startActivity(new Intent(this, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP).putExtra("openSettings", true));
            }); Ui.add(card, settings, 6);
        } else {
            Button authorize = Ui.button(this, "重新授权整个屏幕", false); authorize.setOnClickListener(v -> {
                boolean select = areaMode; pauseProjection(); capture(select);
            }); Ui.add(card, authorize, 6);
        }
        Button close = Ui.button(this, "返回悬浮球", false); close.setOnClickListener(v -> closeSnapshot()); Ui.add(card, close, 6);
        FrameLayout.LayoutParams p = new FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM); p.setMargins(dp(16), dp(16), dp(16), dp(32));
        root.addView(card, p); attachOverlay(root);
    }
    private void showCapturePreview() {
        if (screenshot == null) return;
        LinearLayout root = screenShell("本次截屏 · 仅本机查看", "确认画面里有目标应用的正文。黑屏或只剩状态栏时，请重新授权整个屏幕；应用禁止截屏的页面无法识别。");
        ImageView image = new ImageView(this); image.setImageBitmap(screenshot); image.setScaleType(ImageView.ScaleType.FIT_CENTER);
        image.setContentDescription("本次实际获取的屏幕画面"); root.addView(image, new LinearLayout.LayoutParams(-1, 0, 1));
        LinearLayout bar = actions(root);
        action(bar, "识别文字", false, () -> {
            LinearLayout textPage = screenShell("本次识别文字", "内容仅在本机显示；翻译失败不代表识别失败。");
            ScrollView scroll = new ScrollView(this); TextView text = Ui.text(this, document == null || document.blocks.isEmpty() ? "没有识别到文字。" : document.text(), 16, Ui.NAVY, false);
            text.setPadding(dp(16), dp(8), dp(16), dp(8)); text.setTextIsSelectable(true); scroll.addView(text); textPage.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));
            action(actions(textPage), "返回截屏", true, this::showCapturePreview); attachOverlay(textPage);
        });
        action(bar, "重新截屏", true, () -> capture(areaMode));
        action(bar, "关闭", false, this::closeSnapshot); attachOverlay(root);
    }
    private void showSelection() {
        if (screenshot == null || destroyed) return;
        invalidateWork(); document = null; translations = Collections.emptyMap(); crop = null;
        LinearLayout root = screenShell("区域翻译 · 已冻结", "拖动框选后确认。只翻译框内文字，选区过小时请重新框选。");
        RegionSelectionView selection = new RegionSelectionView(this); selection.setScreenshot(screenshot);
        root.addView(selection, new LinearLayout.LayoutParams(-1, 0, 1));
        LinearLayout actions = actions(root);
        Button confirm = action(actions, "翻译选区", true, () -> { if (selection.isSelectionValid()) recognize(selection.getSelection()); }); confirm.setEnabled(false);
        selection.setOnSelectionChangedListener(rect -> confirm.setEnabled(selection.isSelectionValid()));
        action(actions, "清除", false, selection::clearSelection);
        action(actions, "扩大选区", false, () -> selection.adjustSelection(true));
        action(actions, "缩小选区", false, () -> selection.adjustSelection(false));
        action(actions, "取消", false, this::closeSnapshot); attachOverlay(root);
    }
    private void recognize(Rect selection) {
        if (screenshot == null || destroyed || selection == null || selection.isEmpty()) return;
        invalidateWork(); final long token = generation;
        crop = new Rect(selection);
        if (!crop.intersect(0, 0, screenshot.getWidth(), screenshot.getHeight()) || crop.isEmpty()) { crop = null; return; }
        busy = true; document = null; translations = Collections.emptyMap();
        final String source = prefs.source(), target = prefs.target(), model = prefs.model();
        final Bitmap input = Bitmap.createBitmap(crop.width(), crop.height(), Bitmap.Config.ARGB_8888);
        new Canvas(input).drawBitmap(screenshot, -crop.left, -crop.top, null);
        showTranslationLayer(areaMode ? "正在识别选区…点击取消" : "正在识别屏幕…点击取消");
        ocr.recognizeDocument(input, source, new OcrEngine.DocumentCallback() {
            public void success(OcrDocument value) {
                input.recycle(); if (!isCurrent(token)) return;
                document = value;
                if (BlockTranslationRules.translatableCount(value.blocks) == 0) {
                    showCaptureProblem("没有识别到可翻译的正文", value.blocks.isEmpty()
                            ? "本次没有识别到文字，未调用翻译引擎。请先查看截屏是否包含目标页面，再尝试区域翻译。"
                            : "本次只识别到数字、时间、账号或符号，未调用翻译引擎。请查看截屏，确认目标应用的正文是否被截到。");
                    return;
                }
                translateDocument(token, source, target, model);
            }
            public void failure(Exception error) { input.recycle(); if (isCurrent(token)) captureFailed("文字识别失败，请重新选择清晰的文字区域。"); }
        });
    }
    private void translateDocument(long token, String source, String target, String model) {
        if (!isCurrent(token) || document == null) return;
        showTranslationLayer("已识别 " + BlockTranslationRules.translatableCount(document.blocks) + " 段文字 · " + TranslationRouter.label(prefs.engineMode()) + "…点击取消");
        OcrDocument recognized = document; TranslationEngine client = engine; activeClient = client;
        TranslationEngine.Config config = new TranslationEngine.Config(prefs);
        networkTask = network.submit(() -> {
            try {
                TranslationRouter.Result translated = client.blocks(recognized.blocks, source, target, config);
                Map<String, String> result = translated.values;
                main.post(() -> {
                    if (!isCurrent(token)) return;
                    activeClient = null; networkTask = null; busy = false; translations = result;
                    StringBuilder plain = new StringBuilder();
                    for (ScreenTextBlock block : recognized.blocks) { if (plain.length() > 0) plain.append('\n'); plain.append(result.getOrDefault(block.id, block.text)); }
                    resultEngine = translated.summary(); resultOffline = translated.localCount > 0;
                    prefs.addHistory(recognized.text(), plain.toString(), target, resultEngine, resultOffline);
                    boolean changed = recognized.blocks.stream().anyMatch(block -> !block.text.trim().equals(result.getOrDefault(block.id, block.text).trim()));
                    if (!changed) {
                        showCaptureProblem("已识别文字，译文与原文相同", "已识别 " + BlockTranslationRules.translatableCount(recognized.blocks) + " 段正文。请检查原文与目标语言设置，也可以查看本次识别文字。"); return;
                    }
                    showTranslationLayer(translated.failedCount == 0 ? null : translated.summary() + "\n" + translated.warning + "\n点击退出，成功的译文已保留。");
                    Toast.makeText(ScreenCaptureService.this, resultEngine, Toast.LENGTH_SHORT).show();
                });
            } catch (Exception error) { main.post(() -> { if (isCurrent(token)) showCaptureProblem("已识别文字，但翻译失败", "已识别 " + BlockTranslationRules.translatableCount(recognized.blocks) + " 段正文。\n" + DeepSeekClient.friendlyError(error)); }); }
        });
    }
    private LinearLayout screenShell(String title, String subtitle) {
        LinearLayout root = Ui.column(this); root.setBackgroundColor(Ui.BG);
        root.setOnApplyWindowInsetsListener((view, insets) -> {
            if (Build.VERSION.SDK_INT >= 30) {
                Insets bars = insets.getInsets(WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout());
                view.setPadding(bars.left, bars.top, bars.right, bars.bottom);
            } else view.setPadding(insets.getSystemWindowInsetLeft(), insets.getSystemWindowInsetTop(), insets.getSystemWindowInsetRight(), insets.getSystemWindowInsetBottom());
            return insets;
        });
        LinearLayout head = Ui.column(this); head.setPadding(dp(14), dp(10), dp(14), dp(10));
        LinearLayout titleRow = Ui.row(this); titleRow.addView(Ui.text(this, title, 16, Ui.NAVY, true), new LinearLayout.LayoutParams(0, -2, 1));
        Button close = Ui.button(this, "×", false); close.setContentDescription("关闭截图并返回屏幕"); close.setMinWidth(dp(48)); close.setMinimumWidth(dp(48));
        close.setOnClickListener(v -> closeSnapshot()); titleRow.addView(close, new LinearLayout.LayoutParams(dp(48), dp(48))); head.addView(titleRow);
        TextView note = Ui.text(this, subtitle, 12, Ui.MUTED, false); note.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE); Ui.add(head, note, 3);
        root.addView(head); return root;
    }
    private LinearLayout actions(LinearLayout root) {
        HorizontalScrollView scroll = new HorizontalScrollView(this); scroll.setFillViewport(true); scroll.setHorizontalScrollBarEnabled(true);
        LinearLayout actions = Ui.row(this); actions.setPadding(dp(8), dp(7), dp(8), dp(7)); scroll.addView(actions);
        root.addView(scroll, new LinearLayout.LayoutParams(-1, -2)); return actions;
    }
    private Button action(LinearLayout parent, String label, boolean primary, Runnable task) {
        Button button = Ui.button(this, label, primary); button.setOnClickListener(v -> task.run());
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-2, dp(48)); p.rightMargin = dp(6); parent.addView(button, p); return button;
    }
    private void showTranslationLayer(String status) {
        if (screenshot == null || destroyed) return;
        FrameLayout root = new FrameLayout(this); root.setBackgroundColor(Color.TRANSPARENT);
        TranslationOverlayView result = new TranslationOverlayView(this);
        Point screen = screenSize(); result.setTransparentLayer(screen.x, screen.y); result.setScreenshot(screenshot);
        if (document != null && crop != null) result.setDocument(document, crop, translations);
        result.setOnClickListener(v -> closeSnapshot()); root.addView(result, new FrameLayout.LayoutParams(-1, -1));
        if (status != null) {
            TextView progress = Ui.text(this, status, 14, Color.WHITE, true);
            progress.setShadowLayer(dp(2), 0, dp(1), Color.BLACK); progress.setGravity(Gravity.CENTER);
            progress.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
            progress.setOnClickListener(v -> closeSnapshot());
            progress.setBackgroundColor(0xF020213B); progress.setPadding(dp(12), dp(8), dp(12), dp(8));
            FrameLayout.LayoutParams position = new FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM);
            position.bottomMargin = dp(!busy && resultOffline ? 44 : 32); root.addView(progress, position);
        }
        if (!busy && resultOffline) {
            ImageView badge = Ui.attribution(this); badge.setBackgroundColor(Color.WHITE);
            badge.setPadding(dp(5), dp(3), dp(5), dp(3)); badge.setOnClickListener(v -> closeSnapshot());
            FrameLayout.LayoutParams label = new FrameLayout.LayoutParams(dp(152), dp(26), Gravity.BOTTOM | Gravity.END);
            label.rightMargin = dp(8); label.bottomMargin = dp(8); root.addView(badge, label);
        }
        attachOverlay(root);
    }
    private void attachOverlay(View view) {
        removeOverlay(); if (destroyed || !Settings.canDrawOverlays(this)) { stopSelf(); return; }
        setBubbleVisible(false);
        // The host sees Back even when a button or selectable text child owns keyboard focus.
        FrameLayout host = new FrameLayout(this) {
            @Override public boolean dispatchKeyEvent(KeyEvent event) {
                if (event.getKeyCode() == KeyEvent.KEYCODE_BACK) {
                    if (event.getAction() == KeyEvent.ACTION_UP) closeSnapshot(); return true;
                }
                return super.dispatchKeyEvent(event);
            }
        };
        host.addView(view, new FrameLayout.LayoutParams(-1, -1)); host.setFocusableInTouchMode(true);
        WindowManager.LayoutParams params = new WindowManager.LayoutParams(-1, -1, WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN, PixelFormat.TRANSLUCENT);
        params.gravity = Gravity.TOP | Gravity.START; params.softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_NOTHING;
        if (Build.VERSION.SDK_INT >= 28) params.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_NEVER;
        overlay = host;
        try { windows.addView(host, params); host.requestApplyInsets(); host.requestFocus(); }
        catch (RuntimeException error) { overlay = null; stopSelf(); }
    }
    private void removeOverlay() { if (overlay != null) { try { windows.removeView(overlay); } catch (IllegalArgumentException ignored) { } overlay = null; } }
    @Override public void onConfigurationChanged(Configuration configuration) {
        super.onConfigurationChanged(configuration);
        if (overlay != null || busy) closeSnapshot();
        // A shared single app may keep its capture dimensions while the physical screen rotates.
        // Keep the bubble reachable even when no onCapturedContentResize callback is delivered.
        if (!destroyed && bubble != null && bubbleParams != null) { clampBubble(); windows.updateViewLayout(bubble, bubbleParams); }
    }
    @Override public void onDestroy() {
        destroyed = true; running = false; invalidateWork(); main.removeCallbacksAndMessages(null);
        InputFeature.suspendForScreen(false);
        if (screenOffRegistered) unregisterReceiver(screenOff);
        removeOverlay(); screenshot = null; document = null; translations = Collections.emptyMap();
        if (bubble != null) { try { windows.removeView(bubble); } catch (IllegalArgumentException ignored) { } bubble = null; }
        releaseProjection();
        if (ocr != null) ocr.close(); if (engine != null) engine.close(); network.shutdownNow(); stopForeground(STOP_FOREGROUND_REMOVE); super.onDestroy();
    }
    @Override public IBinder onBind(Intent intent) { return null; }
}
