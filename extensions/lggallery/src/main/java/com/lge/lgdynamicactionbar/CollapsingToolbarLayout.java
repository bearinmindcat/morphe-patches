package com.lge.lgdynamicactionbar;

import android.content.Context;
import android.text.TextUtils;
import android.util.AttributeSet;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ActionMenuView;
import android.widget.RelativeLayout;
import android.widget.TextView;
import android.widget.Toolbar;

/**
 * Generated stand-in (.github/scripts/lg-standins): names and signatures LG Gallery uses (no LG code). Edited by hand:
 * LG's collapsing title area, kept collapsed: the toolbar, with LG's collapsed title (see AppBarLayout) in its row;
 * LG's expanded title is not shown.
 */
public class CollapsingToolbarLayout extends android.widget.FrameLayout {
    public CollapsingToolbarLayout(Context p0) {
        super(p0);
    }
    public CollapsingToolbarLayout(Context p0, AttributeSet p1) {
        super(p0, p1);
    }
    public CollapsingToolbarLayout(Context p0, AttributeSet p1, int p2) {
        super(p0, p1, p2);
    }
    public CollapsingToolbarLayout(Context p0, AttributeSet p1, int p2, int p3) {
        super(p0, p1, p2, p3);
    }

    private Toolbar toolbar() {
        for (int i = 0; i < getChildCount(); i++) if (getChildAt(i) instanceof Toolbar) return (Toolbar) getChildAt(i);
        return null;
    }

    @Override
    protected void onFinishInflate() {
        super.onFinishInflate();
        // LG's expanded title is not shown; its collapsed title sits in the toolbar's row, styled as Android's.
        View expanded = findViewById(android.R.id.textAssist);
        if (expanded != null) expanded.setVisibility(GONE);
        View collapsed = findViewById(android.R.id.replaceText);
        if (collapsed instanceof RelativeLayout) ((RelativeLayout) collapsed).setGravity(Gravity.CENTER_VERTICAL);
        style(android.R.id.text2, android.R.style.TextAppearance_Material_Widget_ActionBar_Title);
        style(android.R.id.shareText, android.R.style.TextAppearance_Material_Widget_ActionBar_Subtitle);
    }

    private void style(int id, int appearance) {
        View view = findViewById(id);
        if (!(view instanceof TextView)) return;
        TextView text = (TextView) view;
        text.setTextAppearance(appearance);
        text.setSingleLine(true);
        text.setEllipsize(TextUtils.TruncateAt.END);
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        // The collapsed title fills the toolbar's row between its navigation button and its menu.
        View collapsed = findViewById(android.R.id.replaceText);
        Toolbar toolbar = toolbar();
        if (collapsed != null && toolbar != null) {
            int start = toolbar.getCurrentContentInsetStart();
            if (toolbar.getNavigationIcon() == null && start == 0) start = toolbar.getContentInsetStart();
            int end = toolbar.getCurrentContentInsetEnd();
            for (int i = 0; i < toolbar.getChildCount(); i++) {
                View child = toolbar.getChildAt(i);
                if (child instanceof ActionMenuView && child.getVisibility() != GONE) end += child.getMeasuredWidth();
            }
            int height = toolbar.getLayoutParams().height > 0 ? toolbar.getLayoutParams().height : toolbar.getMeasuredHeight();
            if (collapsed.getPaddingStart() != start || collapsed.getPaddingEnd() != end) collapsed.setPaddingRelative(start, 0, end, 0);
            if (height > 0 && collapsed.getMinimumHeight() != height) collapsed.setMinimumHeight(height);
        }
        super.onMeasure(widthMeasureSpec, heightMeasureSpec);
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
