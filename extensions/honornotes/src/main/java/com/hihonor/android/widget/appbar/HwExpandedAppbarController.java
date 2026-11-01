package com.hihonor.android.widget.appbar;

/** Generated stand-in (.github/scripts/honor-standins): names and signatures Honor Notes uses, answering "absent" off MagicOS (no Honor code). */
public class HwExpandedAppbarController {
    public HwExpandedAppbarController(android.app.Activity p0) {
        org.ungoogled.honornotes.MagicAppbar.attach(this, p0);
    }
    public void expand(int p0, boolean p1) {
    }
    public android.view.View getAppbar() {
        return org.ungoogled.honornotes.MagicAppbar.appbar(this);
    }
    public int getCurrentVisibleHeight() {
        return org.ungoogled.honornotes.MagicAppbar.visibleHeight(this);
    }
    public int getExpandedStatus() {
        return 0;
    }
    public int getLayoutRes(int p0) {
        return org.ungoogled.honornotes.MagicAppbar.layout(this);
    }
    public void notifyContentOverScroll(int p0) {
    }
    public void setActived(boolean p0) {
    }
    public void setContentView(int p0, android.view.View p1) {
        org.ungoogled.honornotes.MagicAppbar.setContentView(this, p0, p1);
    }
    public void setCustomView(android.view.View p0) {
        org.ungoogled.honornotes.MagicAppbar.setCustomView(this, p0);
    }
    public void setExpanded(boolean p0, boolean p1) {
    }
    public void setOnDragListener(com.hihonor.android.widget.appbar.HwExpandedAppbarController.OnDragListener p0) {
    }
    public void setSubTitle(java.lang.CharSequence p0) {
    }
    public void setSubTitleBehavior(int p0) {
    }
    public void setTitle(java.lang.CharSequence p0) {
    }
    public interface OnDragListener {
    }
}
