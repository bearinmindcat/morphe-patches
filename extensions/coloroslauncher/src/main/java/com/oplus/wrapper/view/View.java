package com.oplus.wrapper.view;

/** Generated stand-in (.github/scripts/coloros-launcher): names and signatures OnePlus System Launcher uses, answering "absent" off ColorOS and on Android versions without them (no OPPO or Google code). */
public class View {
    public View(android.view.View p0) {
        org.ungoogled.coloroslauncher.ColorOS.wrap(this, p0);
    }
    public boolean isVisibleToUser() {
        android.view.View v = org.ungoogled.coloroslauncher.ColorOS.wrapped(this, android.view.View.class); return v != null && v.isShown() && v.getGlobalVisibleRect(new android.graphics.Rect());
    }
}
