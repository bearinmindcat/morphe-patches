package com.lge.sui.widget.control;

import android.content.Context;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;

/**
 * Generated stand-in (.github/scripts/lg-standins): names and signatures LG QuickMemo+ uses (no LG code). Edited by hand: LG's colour picker as a palette: rows of 7 round swatches (SUIColorUtil's colours
 * unless the app sets its own list); a tap picks one (ringed) and tells the listeners. LG's custom colour space
 * (hue / saturation) is not offered.
 */
public class SUIColorPickerRuby extends android.widget.LinearLayout {
    private static final int PER_ROW = 7;
    private int[] colors = com.lge.sui.widget.util.SUIColorUtil.COLORS.clone();
    private String[] names = com.lge.sui.widget.util.SUIColorUtil.NAMES.clone();
    private int selected = Color.BLACK;
    private OnColorChangedListener intListener;
    private com.lge.sui.widget.control.color.OnSUIColorChangedListener colorListener;

    public SUIColorPickerRuby(android.content.Context p0) {
        super(p0);
        build();
    }
    public SUIColorPickerRuby(android.content.Context p0, android.util.AttributeSet p1) {
        super(p0, p1);
        build();
    }
    public SUIColorPickerRuby(android.content.Context p0, android.util.AttributeSet p1, int p2) {
        super(p0, p1, p2);
        build();
    }
    public SUIColorPickerRuby(android.content.Context p0, android.util.AttributeSet p1, int p2, int p3) {
        super(p0, p1, p2, p3);
        build();
    }

    private int dp(float value) {
        return Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value, getResources().getDisplayMetrics()));
    }

    private void build() {
        setOrientation(VERTICAL);
        removeAllViews();
        Context context = getContext();
        LinearLayout row = null;
        for (int i = 0; i < colors.length; i++) {
            if (i % PER_ROW == 0) {
                row = new LinearLayout(context);
                row.setOrientation(HORIZONTAL);
                row.setGravity(Gravity.CENTER_HORIZONTAL);
                addView(row, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT));
            }
            final int color = colors[i];
            View swatch = new View(context);
            swatch.setTag(color);
            swatch.setContentDescription(names != null && i < names.length ? names[i] : String.format("#%06X", color & 0xffffff));
            swatch.setOnClickListener(v -> pick(color, true));
            LayoutParams lp = new LayoutParams(dp(36), dp(36));
            lp.setMargins(dp(5), dp(5), dp(5), dp(5));
            row.addView(swatch, lp);
        }
        paint();
    }

    private void paint() {
        boolean night = (getResources().getConfiguration().uiMode & android.content.res.Configuration.UI_MODE_NIGHT_MASK)
                == android.content.res.Configuration.UI_MODE_NIGHT_YES;
        for (int r = 0; r < getChildCount(); r++) {
            if (!(getChildAt(r) instanceof LinearLayout)) continue;
            LinearLayout row = (LinearLayout) getChildAt(r);
            for (int i = 0; i < row.getChildCount(); i++) {
                View swatch = row.getChildAt(i);
                int color = (Integer) swatch.getTag();
                GradientDrawable circle = new GradientDrawable();
                circle.setShape(GradientDrawable.OVAL);
                circle.setColor(color);
                boolean ring = color == selected;
                // An outline where the swatch would vanish into the background, a thick ring on the picked one.
                circle.setStroke(ring ? dp(3) : dp(1), ring ? (night ? Color.WHITE : Color.DKGRAY) : Color.argb(0x60, 0x80, 0x80, 0x80));
                swatch.setBackground(circle);
                swatch.setSelected(ring);
            }
        }
    }

    private void pick(int color, boolean notify) {
        selected = color;
        paint();
        if (!notify) return;
        if (intListener != null) intListener.onColorChanged(color);
        if (colorListener != null) colorListener.onColorChanged(new com.lge.sui.widget.control.color.SUIColor(color));
    }

    public boolean getHSBSpaceVisibility() {
        return false;
    }
    public com.lge.sui.widget.control.color.SUIColor getSelectedSUIColor() {
        return new com.lge.sui.widget.control.color.SUIColor(selected);
    }
    public void refresh() {
        paint();
    }
    public void saveRecentlyUsedColor() {
    }
    public void setColorList(int[] p0) {
        if (p0 == null || p0.length == 0) return;
        colors = p0.clone();
        build();
    }
    public void setColorTalkbackList(java.lang.String[] p0) {
        names = p0;
        build();
    }
    public void setColorToHSBColorSpace(int p0) {
        pick(p0, true);
    }
    public void setColorToHSBColorSpace(int p0, boolean p1) {
        pick(p0, p1);
    }
    public void setCustomColorButtonCallbackEnabled(boolean p0) {
    }
    public void setDefaultColorList() {
        colors = com.lge.sui.widget.util.SUIColorUtil.COLORS.clone();
        names = com.lge.sui.widget.util.SUIColorUtil.NAMES.clone();
        build();
    }
    public void setDefaultColorListWithoutCustomColor() {
        setDefaultColorList();
    }
    public void setInitialCheckedColor(int p0) {
        pick(p0, false);
    }
    public void setInitialCheckedColor(com.lge.sui.widget.control.color.SUIColor p0, boolean p1) {
        if (p0 != null) pick(p0.getColor(), false);
    }
    public void setInitialCheckedColorWithoutFeedBack(int p0) {
        pick(p0, false);
    }
    public void setOnColorChangeListener(com.lge.sui.widget.control.SUIColorPickerRuby.OnColorChangedListener p0) {
        intListener = p0;
    }
    public void setOnColorChangeListener(com.lge.sui.widget.control.color.OnSUIColorChangedListener p0) {
        colorListener = p0;
    }
    public void setPreviewColor(com.lge.sui.widget.control.color.SUIColor p0) {
    }
    public void setSingleLineImageButtonOnLandscape(boolean p0) {
    }
    public void setType(int p0) {
    }
    public void setUseRecentlyUsedColorsView(boolean p0, java.lang.String p1) {
    }
    public interface OnColorChangedListener {
        void onColorChanged(int color);
    }
}
