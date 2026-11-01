package com.hihonor.android.widget.pattern;

/** Generated stand-in (.github/scripts/honor-standins): names and signatures Honor Notes uses, answering "absent" off MagicOS (no Honor code). */
public class HwDialogTitle extends android.widget.TextView {
    public HwDialogTitle(android.content.Context p0) {
        super(p0);
    }
    public HwDialogTitle(android.content.Context p0, android.util.AttributeSet p1) {
        super(p0, p1);
    }
    public HwDialogTitle(android.content.Context p0, android.util.AttributeSet p1, int p2) {
        super(p0, p1, p2);
    }
    public HwDialogTitle(android.content.Context p0, android.util.AttributeSet p1, int p2, int p3) {
        super(p0, p1, p2, p3);
    }
    public void setElementText(java.lang.CharSequence p0, int p1) {
        
        if (p1 != 0) return;
        setTextAppearance(android.R.style.TextAppearance_DeviceDefault_DialogWindowTitle);
        setText(p0);
    }
    public void setLayoutStyle(int p0) {
    }
}
