package com.lge.lgdynamicactionbar;

import android.content.Context;
import android.content.res.TypedArray;
import android.util.AttributeSet;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;
import android.widget.Toolbar;

import java.util.ArrayList;

/**
 * Generated stand-in (.github/scripts/lg-standins): names and signatures LG Gallery uses (no LG code). Edited by hand:
 * LG's dynamic app bar as a plain one, always collapsed to the toolbar's height (expanding is "not enabled", a state
 * Gallery has for its own screens): the title and subtitle go to LG's title views (the collapsed one is shown over the
 * toolbar), a custom view (search) takes their place in the toolbar, offset listeners hear "collapsed, nothing to
 * scroll".
 */
public class AppBarLayout extends android.widget.LinearLayout {
    private final ArrayList<OnOffsetChangedListener> listeners = new ArrayList<>();
    private View customView;
    private boolean reported;

    public AppBarLayout(Context p0) {
        super(p0);
        setOrientation(VERTICAL);
    }
    public AppBarLayout(Context p0, AttributeSet p1) {
        super(p0, p1);
        setOrientation(VERTICAL);
    }
    public AppBarLayout(Context p0, AttributeSet p1, int p2) {
        super(p0, p1, p2);
        setOrientation(VERTICAL);
    }
    public AppBarLayout(Context p0, AttributeSet p1, int p2, int p3) {
        super(p0, p1, p2, p3);
        setOrientation(VERTICAL);
    }

    private static Toolbar findToolbar(View view) {
        if (view instanceof Toolbar) return (Toolbar) view;
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) {
                Toolbar found = findToolbar(group.getChildAt(i));
                if (found != null) return found;
            }
        }
        return null;
    }

    /** The collapsed height: the theme's action bar size. */
    int collapsedHeight() {
        TypedArray a = getContext().obtainStyledAttributes(new int[]{android.R.attr.actionBarSize});
        try {
            return a.getDimensionPixelSize(0, Math.round(56 * getResources().getDisplayMetrics().density));
        } finally {
            a.recycle();
        }
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        super.onMeasure(widthMeasureSpec, MeasureSpec.makeMeasureSpec(collapsedHeight() + getPaddingTop() + getPaddingBottom(), MeasureSpec.EXACTLY));
    }

    @Override
    protected void onLayout(boolean changed, int l, int t, int r, int b) {
        super.onLayout(changed, l, t, r, b);
        if (reported) return;
        reported = true;
        for (OnOffsetChangedListener listener : new ArrayList<>(listeners)) listener.onOffsetChanged(this, 0);
    }

    public void addOnOffsetChangedListener(OnOffsetChangedListener p0) {
        if (p0 != null && !listeners.contains(p0)) listeners.add(p0);
    }
    public int getAppBarCurrentHeight() {
        return getHeight() > 0 ? getHeight() : collapsedHeight();
    }
    public boolean getAppBarExpandEnable() {
        return false;
    }
    public int getTotalScrollRange() {
        return 0;
    }
    public void setAppBarExpandEnable(boolean p0) {
    }
    public void setAppBarExpandEnable(boolean p0, boolean p1) {
    }
    public void setExpanded(boolean p0) {
    }
    public void setExpanded(boolean p0, boolean p1) {
    }

    private void setText(int id, CharSequence text) {
        View view = findViewById(id);
        if (view instanceof TextView) ((TextView) view).setText(text);
    }

    public void setAppBarTitle(String p0) {
        // LG's title views: expanded (text1) and collapsed (text2, the one shown, see CollapsingToolbarLayout).
        setText(android.R.id.text1, p0);
        setText(android.R.id.text2, p0);
    }
    public void setAppBarSubTitle(String p0) {
        setText(android.R.id.summary, p0);
        setText(android.R.id.shareText, p0);
    }
    public void setAppBarSubTitleVisible(boolean p0) {
        for (int id : new int[]{android.R.id.summary, android.R.id.shareText}) {
            View view = findViewById(id);
            if (view != null) view.setVisibility(p0 ? VISIBLE : GONE);
        }
    }

    public boolean isAppBarCustomView() {
        return customView != null;
    }
    public void setAppBarCustomView(View p0) {
        removeAppBarCustomView();
        Toolbar toolbar = findToolbar(this);
        if (p0 == null || toolbar == null) return;
        if (p0.getParent() instanceof ViewGroup) ((ViewGroup) p0.getParent()).removeView(p0);
        customView = p0;
        View title = findViewById(android.R.id.replaceText);
        if (title != null) title.setVisibility(INVISIBLE);
        toolbar.addView(p0, new Toolbar.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
    }
    public void removeAppBarCustomView() {
        if (customView == null) return;
        if (customView.getParent() instanceof ViewGroup) ((ViewGroup) customView.getParent()).removeView(customView);
        customView = null;
        View title = findViewById(android.R.id.replaceText);
        if (title != null) title.setVisibility(VISIBLE);
    }

    public interface OnOffsetChangedListener {
        void onOffsetChanged(AppBarLayout appBarLayout, int verticalOffset);
        void onTitlePaddingChanged(AppBarLayout appBarLayout, int start, int end);
    }
}
