package com.hihonor.android.view;

/**
 * Stand-in for MagicOS's search bar animations (hand-written: they name HwToolbar, a class the app bundles). As the
 * known.py answer for the fixed search bar: an animator that changes nothing, so the app's own listeners still run
 * when it ends.
 */
public class HwSearchAnimationUtils {
    public static android.animation.Animator getSearchBelowActionBarSearchAnimator(android.content.Context p0,
            android.view.MenuItem p1, com.hihonor.android.widget.HwToolbar p2, android.view.View p3, android.view.View p4,
            android.view.View p5, android.view.View p6) {
        return android.animation.ValueAnimator.ofFloat(0f, 1f);
    }

    public static android.animation.Animator getSearchBelowActionBarCancelAnimator(android.content.Context p0,
            com.hihonor.android.widget.HwToolbar p1, android.view.View p2, android.view.View p3, android.view.View p4,
            android.view.View p5) {
        return android.animation.ValueAnimator.ofFloat(0f, 1f);
    }
}
