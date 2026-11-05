package com.oplus.animation;

/** Generated stand-in (.github/scripts/coloros-launcher): names and signatures OnePlus System Launcher uses, answering "absent" off ColorOS and on Android versions without them (no OPPO or Google code). */
public class OplusAsyncAnimatorUtils {
    public static boolean setAlpha(android.graphics.RenderNode p0, float p1) {
        return p0 != null && p0.setAlpha(p1);
    }
    public static boolean setAlpha(android.view.View p0, float p1) {
        if (p0 == null) return false; p0.setAlpha(p1); return true;
    }
    public static boolean setScaleX(android.view.View p0, float p1) {
        if (p0 == null) return false; p0.setScaleX(p1); return true;
    }
    public static boolean setScaleY(android.view.View p0, float p1) {
        if (p0 == null) return false; p0.setScaleY(p1); return true;
    }
    public static boolean setTranslationX(android.view.View p0, float p1) {
        if (p0 == null) return false; p0.setTranslationX(p1); return true;
    }
    public static boolean setTranslationY(android.view.View p0, float p1) {
        if (p0 == null) return false; p0.setTranslationY(p1); return true;
    }
}
