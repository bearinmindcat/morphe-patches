package com.lge.lgdynamicactionbar;

import android.view.View;
import android.widget.TextView;

import java.util.ArrayList;

/**
 * Generated stand-in (.github/scripts/lg-standins): names and signatures LG QuickMemo+ uses (no LG code). Edited by
 * hand: LG's app bar, always expanded (no scroll range); its title goes to the app's dynamic title views
 * (android:id/text1, expanded, and text2, collapsed); offset listeners hear "expanded" once laid out.
 */
public class AppBarLayout extends android.widget.LinearLayout {
    private final ArrayList<OnOffsetChangedListener> listeners = new ArrayList<>();
    private boolean expandEnabled = true;
    private boolean reported;

    public AppBarLayout(android.content.Context p0) {
        super(p0);
        setOrientation(VERTICAL);
    }
    public AppBarLayout(android.content.Context p0, android.util.AttributeSet p1) {
        super(p0, p1);
        setOrientation(VERTICAL);
    }
    public AppBarLayout(android.content.Context p0, android.util.AttributeSet p1, int p2) {
        super(p0, p1, p2);
        setOrientation(VERTICAL);
    }
    public AppBarLayout(android.content.Context p0, android.util.AttributeSet p1, int p2, int p3) {
        super(p0, p1, p2, p3);
        setOrientation(VERTICAL);
    }
    public void addOnOffsetChangedListener(com.lge.lgdynamicactionbar.AppBarLayout.OnOffsetChangedListener p0) {
        if (p0 != null && !listeners.contains(p0)) listeners.add(p0);
    }
    public boolean getAppBarExpandEnable() {
        return expandEnabled;
    }
    public int getTotalScrollRange() {
        return 0;
    }
    public void setAppBarExpandEnable(boolean p0) {
        expandEnabled = p0;
    }
    public void setAppBarSubTitleVisible(boolean p0) {
    }
    public void setAppBarTitle(java.lang.String p0) {
        for (int id : new int[]{android.R.id.text1, android.R.id.text2}) {
            View view = findViewById(id);
            if (view instanceof TextView) ((TextView) view).setText(p0);
        }
    }
    public void setExpanded(boolean p0) {
    }
    public void setExpanded(boolean p0, boolean p1) {
    }

    @Override
    protected void onLayout(boolean changed, int l, int t, int r, int b) {
        super.onLayout(changed, l, t, r, b);
        if (reported) return;
        reported = true;
        for (OnOffsetChangedListener listener : new ArrayList<>(listeners)) listener.onOffsetChanged(this, 0);
    }

    public interface OnOffsetChangedListener {
        void onOffsetChanged(com.lge.lgdynamicactionbar.AppBarLayout appBarLayout, int verticalOffset);
    }
}
