package com.rongji.screentranslate;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Typeface;
import android.view.Gravity;
import android.view.MotionEvent;
import android.widget.FrameLayout;
import android.widget.TextView;

/** Translucent circular menu. The center and everything outside the ring remain transparent. */
// Constructed only in code with required action callbacks; never inflated from XML.
@android.annotation.SuppressLint("ViewConstructor")
public final class RadialMenuView extends FrameLayout {
    public interface Actions { void fullScreen(); void region(); void settings(); void input(); void dismiss(); }
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path ring = new Path();
    private final TextView[] choices = new TextView[BuildConfig.HAS_INPUT_TRANSLATION ? 5 : 4];
    private final Actions actions;
    private boolean outsideGesture;

    public RadialMenuView(Context context, Actions actions) {
        super(context); this.actions = actions; setWillNotDraw(false);
        setBackgroundColor(Color.TRANSPARENT);
        choices[0] = choice("▣\n全屏翻译", "全屏翻译", actions::fullScreen);
        choices[1] = choice("□\n区域翻译", "区域翻译", actions::region);
        choices[2] = choice("⚙\n设置", "打开译镜设置", actions::settings);
        if (BuildConfig.HAS_INPUT_TRANSLATION) choices[3] = choice("文\n输入翻译", "输入翻译", actions::input);
        choices[choices.length - 1] = choice("×", "关闭菜单并返回悬浮球", actions::dismiss);
        choices[choices.length - 1].setTextSize(28);
    }
    private TextView choice(String text, String description, Runnable action) {
        TextView item = new TextView(getContext()); item.setText(text); item.setTextColor(Color.WHITE);
        item.setTextSize(14); item.setTypeface(Typeface.DEFAULT, Typeface.BOLD); item.setGravity(Gravity.CENTER);
        item.setContentDescription(description); item.setFocusable(true); item.setClickable(true);
        item.setBackgroundColor(Color.TRANSPARENT); item.setOnClickListener(v -> action.run());
        addView(item, new LayoutParams(1, 1)); return item;
    }
    @Override protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        float diameter = Math.min(w, h), cx = w / 2f, cy = h / 2f;
        ring.reset(); ring.setFillType(Path.FillType.EVEN_ODD);
        ring.addCircle(cx, cy, diameter / 2f, Path.Direction.CW);
        ring.addCircle(cx, cy, diameter * .17f, Path.Direction.CW);
    }
    @Override protected void onMeasure(int widthSpec, int heightSpec) {
        int w = MeasureSpec.getSize(widthSpec), h = MeasureSpec.getSize(heightSpec);
        setMeasuredDimension(w, h);
        for (int i = 0; i < choices.length; i++) {
            int size = Math.round(Math.min(w, h) * (i == choices.length - 1 ? .28f : .30f));
            choices[i].measure(MeasureSpec.makeMeasureSpec(size, MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(size, MeasureSpec.EXACTLY));
        }
    }
    @Override protected void onLayout(boolean changed, int left, int top, int right, int bottom) {
        float diameter = Math.min(getWidth(), getHeight()), cx = getWidth() / 2f, cy = getHeight() / 2f;
        float distance = diameter * .32f;
        float[][] centers = BuildConfig.HAS_INPUT_TRANSLATION
                ? new float[][]{{cx, cy - distance}, {cx - distance, cy}, {cx + distance, cy}, {cx, cy + distance}, {cx, cy}}
                : new float[][]{{cx, cy - distance}, {cx - distance * .866f, cy + distance * .5f}, {cx + distance * .866f, cy + distance * .5f}, {cx, cy}};
        for (int i = 0; i < choices.length; i++) {
            int size = choices[i].getMeasuredWidth();
            int x = Math.round(centers[i][0] - size / 2f), y = Math.round(centers[i][1] - size / 2f);
            choices[i].layout(x, y, x + size, y + size);
        }
    }
    @Override protected void onDraw(Canvas canvas) {
        super.onDraw(canvas); paint.setColor(0xCC20213B); canvas.drawPath(ring, paint);
    }
    @Override public boolean dispatchTouchEvent(MotionEvent event) {
        if (event.getActionMasked() == MotionEvent.ACTION_DOWN)
            outsideGesture = Math.hypot(event.getX() - getWidth() / 2f, event.getY() - getHeight() / 2f) > Math.min(getWidth(), getHeight()) / 2f;
        if (outsideGesture) {
            if (event.getActionMasked() == MotionEvent.ACTION_UP) actions.dismiss();
            return true;
        }
        // Empty space between menu items also collapses back to the bubble.
        return super.dispatchTouchEvent(event);
    }
    @Override public boolean onTouchEvent(MotionEvent event) {
        if (event.getActionMasked() == MotionEvent.ACTION_UP) performClick();
        return true;
    }
    @Override public boolean performClick() { super.performClick(); actions.dismiss(); return true; }
}
