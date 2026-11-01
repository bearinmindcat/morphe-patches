package com.lge.lgdynamicactionbar;

import android.util.TypedValue;
import android.view.View;
import android.widget.TextView;
import android.widget.Toolbar;

/**
 * Generated stand-in (.github/scripts/lg-standins): names and signatures LG QuickMemo+ uses (no LG code). Edited by
 * hand: LG's collapsing title area, kept expanded: the toolbar at the bottom, the app's big title (its
 * dynamic_actionbar_title layout: android:id/textAssist) just above it, the collapsed title (android:id/replaceText)
 * not shown.
 */
public class CollapsingToolbarLayout extends android.widget.FrameLayout {
    public CollapsingToolbarLayout(android.content.Context p0) {
        super(p0);
    }
    public CollapsingToolbarLayout(android.content.Context p0, android.util.AttributeSet p1) {
        super(p0, p1);
    }
    public CollapsingToolbarLayout(android.content.Context p0, android.util.AttributeSet p1, int p2) {
        super(p0, p1, p2);
    }
    public CollapsingToolbarLayout(android.content.Context p0, android.util.AttributeSet p1, int p2, int p3) {
        super(p0, p1, p2, p3);
    }
    public void setTitleEnabled(boolean p0) {
    }

    private float expandedTitleSize() {
        int id = getResources().getIdentifier("dynamic_actionbar_text_size_expanded", "dimen", getContext().getPackageName());
        if (id != 0) {
            try {
                return getResources().getDimension(id);
            } catch (RuntimeException e) {
                // LG's size below.
            }
        }
        return TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, 32, getResources().getDisplayMetrics());
    }

    @Override
    protected void onFinishInflate() {
        super.onFinishInflate();
        View collapsed = findViewById(android.R.id.replaceText);
        if (collapsed != null) collapsed.setVisibility(GONE);
        View expanded = findViewById(android.R.id.textAssist);
        if (expanded != null) {
            int side = Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 24, getResources().getDisplayMetrics()));
            expanded.setPaddingRelative(side, 0, side, side / 3);
        }
        View title = findViewById(android.R.id.text1);
        if (title instanceof TextView) ((TextView) title).setTextSize(TypedValue.COMPLEX_UNIT_PX, expandedTitleSize());
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        // The title area ends where the toolbar at the bottom begins.
        View root = findViewById(android.R.id.textAssist) == null ? null : (View) findViewById(android.R.id.textAssist).getParent();
        Toolbar toolbar = null;
        for (int i = 0; i < getChildCount(); i++) if (getChildAt(i) instanceof Toolbar) toolbar = (Toolbar) getChildAt(i);
        if (root != null && toolbar != null && root != this) {
            int bar = toolbar.getLayoutParams().height > 0 ? toolbar.getLayoutParams().height : 0;
            if (root.getPaddingBottom() != bar) root.setPadding(root.getPaddingLeft(), root.getPaddingTop(), root.getPaddingRight(), bar);
        }
        super.onMeasure(widthMeasureSpec, heightMeasureSpec);
    }
}
