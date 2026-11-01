package com.hihonor.android.view;

/**
 * Stand-in for MagicOS's search-bar animations (hand-written: their signatures name the app's bundled HwToolbar). No
 * Honor code: an empty animation, so the search bar simply opens and closes.
 */
public class HwSearchAnimationUtils {
    public static android.animation.Animator getSearchBelowActionBarSearchAnimator(android.content.Context context,
            android.view.MenuItem item, com.hihonor.android.widget.HwToolbar toolbar, android.view.View a, android.view.View b,
            android.view.View c, android.view.View d) {
        return new android.animation.AnimatorSet();
    }

    public static android.animation.Animator getSearchBelowActionBarCancelAnimator(android.content.Context context,
            com.hihonor.android.widget.HwToolbar toolbar, android.view.View a, android.view.View b, android.view.View c,
            android.view.View d) {
        return new android.animation.AnimatorSet();
    }
}
