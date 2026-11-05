package com.oplus.animation;

/**
 * Stand-in (.github/scripts/coloros-launcher/overrides): names OnePlus System Launcher uses, answering "absent" off
 * ColorOS (no OPPO code). A view property is a FloatPropertyCompat, as the launcher passes one where the other is
 * expected.
 */
public class DynamicAnimation {
    public static com.oplus.animation.DynamicAnimation.ViewProperty ALPHA;
    public static com.oplus.animation.DynamicAnimation.ViewProperty SCALE_X;
    public static com.oplus.animation.DynamicAnimation.ViewProperty SCALE_Y;
    public static com.oplus.animation.DynamicAnimation.ViewProperty TRANSLATION_X;
    public static com.oplus.animation.DynamicAnimation.ViewProperty TRANSLATION_Y;
    public boolean isRunning() {
        return false;
    }
    public interface OnAnimationEndListener {
    }
    public interface OnAnimationUpdateListener {
    }
    public static class ViewProperty extends com.oplus.animation.FloatPropertyCompat {
        public ViewProperty() {
            super("");
        }
    }
}
