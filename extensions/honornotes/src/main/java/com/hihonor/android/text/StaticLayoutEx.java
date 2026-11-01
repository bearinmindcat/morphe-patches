package com.hihonor.android.text;

/** Generated stand-in (.github/scripts/honor-standins): names and signatures Honor Notes uses, answering "absent" off MagicOS (no Honor code). */
public class StaticLayoutEx {
    public static android.text.StaticLayout getStaticLayout(java.lang.CharSequence p0, int p1, int p2, android.text.TextPaint p3, int p4, android.text.Layout.Alignment p5, android.text.TextDirectionHeuristic p6, float p7, float p8, boolean p9, android.text.TextUtils.TruncateAt p10, int p11, int p12) {
        return android.text.StaticLayout.Builder.obtain(p0, p1, p2, p3, Math.max(0, p4)).setAlignment(p5).setTextDirection(p6).setLineSpacing(p8, p7).setIncludePad(p9).setEllipsize(p10).setEllipsizedWidth(Math.max(0, p11)).setMaxLines(p12).build();
    }
}
