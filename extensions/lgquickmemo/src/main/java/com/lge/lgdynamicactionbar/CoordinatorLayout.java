package com.lge.lgdynamicactionbar;

import android.view.View;

/**
 * Generated stand-in (.github/scripts/lg-standins): names and signatures LG QuickMemo+ uses, answering "absent" off
 * LG's ROM (no LG code). Edited by hand: LG's dynamic action bar library lays a scrolling view out below its app bar
 * (lge:layout_behavior, which the patch turns into the tag "lg_scrolling_view_behavior"); here the app bar stays
 * expanded and such views fill the space below it.
 */
public class CoordinatorLayout extends android.widget.FrameLayout {
    static final String BELOW_APP_BAR = "lg_scrolling_view_behavior";

    public CoordinatorLayout(android.content.Context p0) {
        super(p0);
    }
    public CoordinatorLayout(android.content.Context p0, android.util.AttributeSet p1) {
        super(p0, p1);
    }
    public CoordinatorLayout(android.content.Context p0, android.util.AttributeSet p1, int p2) {
        super(p0, p1, p2);
    }
    public CoordinatorLayout(android.content.Context p0, android.util.AttributeSet p1, int p2, int p3) {
        super(p0, p1, p2, p3);
    }

    private View appBar() {
        for (int i = 0; i < getChildCount(); i++) {
            View child = getChildAt(i);
            if (child instanceof AppBarLayout && child.getVisibility() != GONE) return child;
        }
        return null;
    }

    private static boolean belowAppBar(View child) {
        return BELOW_APP_BAR.equals(child.getTag()) && child.getVisibility() != GONE;
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        super.onMeasure(widthMeasureSpec, heightMeasureSpec);
        View bar = appBar();
        if (bar == null) return;
        int available = Math.max(0, getMeasuredHeight() - getPaddingTop() - getPaddingBottom() - bar.getMeasuredHeight());
        for (int i = 0; i < getChildCount(); i++) {
            View child = getChildAt(i);
            if (!belowAppBar(child)) continue;
            LayoutParams lp = (LayoutParams) child.getLayoutParams();
            int height = lp.height == LayoutParams.MATCH_PARENT
                    ? MeasureSpec.makeMeasureSpec(Math.max(0, available - lp.topMargin - lp.bottomMargin), MeasureSpec.EXACTLY)
                    : MeasureSpec.makeMeasureSpec(Math.max(0, available - lp.topMargin - lp.bottomMargin), MeasureSpec.AT_MOST);
            child.measure(getChildMeasureSpec(widthMeasureSpec, getPaddingLeft() + getPaddingRight() + lp.leftMargin + lp.rightMargin, lp.width), height);
        }
    }

    @Override
    protected void onLayout(boolean changed, int left, int top, int right, int bottom) {
        super.onLayout(changed, left, top, right, bottom);
        View bar = appBar();
        if (bar == null) return;
        int below = bar.getBottom();
        for (int i = 0; i < getChildCount(); i++) {
            View child = getChildAt(i);
            if (!belowAppBar(child)) continue;
            LayoutParams lp = (LayoutParams) child.getLayoutParams();
            int y = below + lp.topMargin;
            child.layout(child.getLeft(), y, child.getRight(), y + child.getMeasuredHeight());
        }
    }
}
