package android.widget;

import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;

/**
 * Generated stand-in (.github/scripts/lg-standins): names and signatures LG QuickMemo+ uses, answering "absent" off
 * LG's ROM (no LG code). Edited by hand: LG's "back to top" button over a list (an area that shows a round up-arrow
 * button at its bottom while the list is scrolled down; the app shows/hides it and handles its clicks).
 */
public class TopButtonLayout extends android.widget.FrameLayout {
    private ImageView button;

    public TopButtonLayout(android.content.Context p0) {
        super(p0);
    }
    public TopButtonLayout(android.content.Context p0, android.util.AttributeSet p1) {
        super(p0, p1);
    }
    public TopButtonLayout(android.content.Context p0, android.util.AttributeSet p1, int p2) {
        super(p0, p1, p2);
    }
    public TopButtonLayout(android.content.Context p0, android.util.AttributeSet p1, int p2, int p3) {
        super(p0, p1, p2, p3);
    }

    private int dp(int value) {
        return Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value, getResources().getDisplayMetrics()));
    }

    public android.widget.ImageView getTopButton() {
        if (button == null) {
            button = new ImageView(getContext());
            button.setImageResource(android.R.drawable.arrow_up_float);
            button.setScaleType(ImageView.ScaleType.CENTER);
            GradientDrawable circle = new GradientDrawable();
            circle.setShape(GradientDrawable.OVAL);
            circle.setColor(Color.argb(0xcc, 0x60, 0x60, 0x60));
            button.setBackground(circle);
            button.setContentDescription("Top");
            button.setVisibility(View.GONE);
            LayoutParams lp = new LayoutParams(dp(44), dp(44), Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL);
            lp.bottomMargin = dp(16);
            addView(button, lp);
        }
        return button;
    }
    public void hide() {
        getTopButton().setVisibility(View.GONE);
    }
    public boolean isShowing() {
        return getTopButton().getVisibility() == View.VISIBLE;
    }
    public void show() {
        getTopButton().setVisibility(View.VISIBLE);
    }
}
