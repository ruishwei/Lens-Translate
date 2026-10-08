package com.rongji.screentranslate;

import android.content.Context;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.InsetDrawable;
import android.view.Gravity;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

final class Ui {
    static android.widget.ImageView attribution(Context context) {
        android.widget.ImageView image = new android.widget.ImageView(context);
        image.setImageResource(R.drawable.google_translate_attribution); image.setAdjustViewBounds(true);
        image.setScaleType(android.widget.ImageView.ScaleType.FIT_START); image.setMaxHeight(dp(context, 20));
        image.setContentDescription("Powered by Google Translate"); return image;
    }
    static android.widget.Spinner spinner(Context context, String[] values, String selected) {
        android.widget.Spinner view = new android.widget.Spinner(context);
        view.setAdapter(new android.widget.ArrayAdapter<>(context, android.R.layout.simple_spinner_dropdown_item, values));
        for (int i = 0; i < values.length; i++) if (values[i].equals(selected)) view.setSelection(i); return view;
    }
    static void systemInsets(android.view.View view) {
        view.setOnApplyWindowInsetsListener((v, insets) -> {
            if (android.os.Build.VERSION.SDK_INT >= 30) {
                android.graphics.Insets edges = insets.getInsets(android.view.WindowInsets.Type.systemBars() | android.view.WindowInsets.Type.displayCutout());
                v.setPadding(edges.left, edges.top, edges.right, edges.bottom);
            } else v.setPadding(insets.getSystemWindowInsetLeft(), insets.getSystemWindowInsetTop(), insets.getSystemWindowInsetRight(), insets.getSystemWindowInsetBottom());
            return insets;
        });
    }
    static final int NAVY = 0xFF20213B, ACCENT = 0xFF605BE8, MUTED = 0xFF72758B, BG = 0xFFF5F6FA, BORDER = 0xFFE4E6F0;
    static int dp(Context c, float value) { return Math.round(value * c.getResources().getDisplayMetrics().density); }
    static GradientDrawable rounded(Context c, int color, int radius, int border) {
        GradientDrawable shape = new GradientDrawable(); shape.setColor(color); shape.setCornerRadius(dp(c, radius));
        if (border != 0) shape.setStroke(dp(c, 1), border); return shape;
    }
    static TextView text(Context c, String text, int size, int color, boolean bold) {
        TextView view = new TextView(c); view.setText(text); view.setTextSize(size); view.setTextColor(color);
        if (bold) view.setTypeface(Typeface.DEFAULT, Typeface.BOLD); view.setLineSpacing(dp(c, 3), 1); return view;
    }
    static Button button(Context c, String text, boolean primary) {
        Button view = new Button(c); view.setText(text); view.setTextSize(13); view.setAllCaps(false);
        view.setTextColor(primary ? 0xFFFFFFFF : NAVY); view.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        // Smaller visual body, while preserving a 48dp touch target.
        view.setBackground(new InsetDrawable(rounded(c, primary ? ACCENT : 0xFFEBEDF5, 10, 0), 0, dp(c, 2), 0, dp(c, 2)));
        view.setPadding(dp(c, 14), dp(c, 6), dp(c, 14), dp(c, 6)); view.setMinHeight(dp(c, 48));
        view.setMinWidth(dp(c, 64)); view.setMinimumWidth(dp(c, 64)); view.setStateListAnimator(null); view.setElevation(0); return view;
    }
    static LinearLayout column(Context c) { LinearLayout l = new LinearLayout(c); l.setOrientation(LinearLayout.VERTICAL); return l; }
    static LinearLayout row(Context c) { LinearLayout l = new LinearLayout(c); l.setOrientation(LinearLayout.HORIZONTAL); l.setGravity(Gravity.CENTER_VERTICAL); return l; }
    static LinearLayout card(Context c) {
        LinearLayout l = column(c); l.setPadding(dp(c, 20), dp(c, 20), dp(c, 20), dp(c, 20));
        l.setBackground(rounded(c, 0xFFFFFFFF, 20, BORDER)); return l;
    }
    static void add(LinearLayout parent, android.view.View child, int top) {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-1, -2); p.topMargin = dp(parent.getContext(), top); parent.addView(child, p);
    }
}
