package com.lge.lgdynamicactionbar;

import android.content.Context;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;

/**
 * Generated stand-in (.github/scripts/lg-standins): names and signatures LG Gallery uses (no LG code). Edited by hand:
 * LG's dynamic action bar library lays a scrolling view out below its app bar (lge:layout_behavior, which the patch
 * turns into the tag "lg_scrolling_view_behavior"); here the app bar stays collapsed and such views fill the space
 * below it. Scroll gestures Gallery forwards to it (onTouchEvent with a flag) have no bar to move.
 */
public class CoordinatorLayout extends android.widget.FrameLayout {
    static final String BELOW_APP_BAR = "lg_scrolling_view_behavior";

    public CoordinatorLayout(Context p0) {
        super(p0);
    }
    public CoordinatorLayout(Context p0, AttributeSet p1) {
        super(p0, p1);
    }
    public CoordinatorLayout(Context p0, AttributeSet p1, int p2) {
        super(p0, p1, p2);
    }
    public CoordinatorLayout(Context p0, AttributeSet p1, int p2, int p3) {
        super(p0, p1, p2, p3);
    }

    public boolean onTouchEvent(MotionEvent p0, boolean p1) {
        return false;
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
        ViewGroup.MarginLayoutParams barParams = (ViewGroup.MarginLayoutParams) bar.getLayoutParams();
        int available = Math.max(0, getMeasuredHeight() - getPaddingTop() - getPaddingBottom() - bar.getMeasuredHeight() - barParams.topMargin - barParams.bottomMargin);
        for (int i = 0; i < getChildCount(); i++) {
            View child = getChildAt(i);
            if (!belowAppBar(child)) continue;
            android.widget.FrameLayout.LayoutParams lp = (android.widget.FrameLayout.LayoutParams) child.getLayoutParams();
            int size = Math.max(0, available - lp.topMargin - lp.bottomMargin);
            int height = MeasureSpec.makeMeasureSpec(size, lp.height == ViewGroup.LayoutParams.MATCH_PARENT ? MeasureSpec.EXACTLY : MeasureSpec.AT_MOST);
            child.measure(getChildMeasureSpec(widthMeasureSpec, getPaddingLeft() + getPaddingRight() + lp.leftMargin + lp.rightMargin, lp.width), height);
        }
    }

    @Override
    protected void onLayout(boolean changed, int left, int top, int right, int bottom) {
        super.onLayout(changed, left, top, right, bottom);
        View bar = appBar();
        if (bar == null) return;
        int below = bar.getBottom() + ((ViewGroup.MarginLayoutParams) bar.getLayoutParams()).bottomMargin;
        for (int i = 0; i < getChildCount(); i++) {
            View child = getChildAt(i);
            if (!belowAppBar(child)) continue;
            android.widget.FrameLayout.LayoutParams lp = (android.widget.FrameLayout.LayoutParams) child.getLayoutParams();
            int y = below + lp.topMargin;
            child.layout(child.getLeft(), y, child.getRight(), y + child.getMeasuredHeight());
        }
    }

    @Override
    public android.widget.FrameLayout.LayoutParams generateLayoutParams(AttributeSet attrs) {
        return new LayoutParams(getContext(), attrs);
    }
    @Override
    protected android.widget.FrameLayout.LayoutParams generateDefaultLayoutParams() {
        return new LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
    }
    @Override
    protected ViewGroup.LayoutParams generateLayoutParams(ViewGroup.LayoutParams lp) {
        return lp instanceof ViewGroup.MarginLayoutParams ? new LayoutParams((ViewGroup.MarginLayoutParams) lp) : new LayoutParams(lp);
    }
    @Override
    protected boolean checkLayoutParams(ViewGroup.LayoutParams p) {
        return p instanceof LayoutParams;
    }

    public static class LayoutParams extends android.widget.FrameLayout.LayoutParams {
        public LayoutParams(Context c, AttributeSet attrs) {
            super(c, attrs);
        }
        public LayoutParams(int width, int height) {
            super(width, height);
        }
        public LayoutParams(ViewGroup.MarginLayoutParams source) {
            super(source);
        }
        public LayoutParams(ViewGroup.LayoutParams source) {
            super(source);
        }
    }
}
