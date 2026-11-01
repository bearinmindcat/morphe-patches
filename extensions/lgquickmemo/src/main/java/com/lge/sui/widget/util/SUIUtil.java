package com.lge.sui.widget.util;

/** Generated stand-in (.github/scripts/lg-standins): names and signatures LG QuickMemo+ uses (no LG code). Edited by hand: sizes and orientation, as Android answers them. */
public class SUIUtil {
    public static int dip2pixel(float p0, android.content.Context p1) {
        return Math.round(android.util.TypedValue.applyDimension(android.util.TypedValue.COMPLEX_UNIT_DIP, p0, p1.getResources().getDisplayMetrics()));
    }
    public static boolean isLandScapeMode(android.content.Context p0) {
        return p0.getResources().getConfiguration().orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE;
    }
}
