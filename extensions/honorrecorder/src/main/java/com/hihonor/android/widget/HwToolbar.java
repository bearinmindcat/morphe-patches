package com.hihonor.android.widget;

/**
 * Honor Recorder bundles this compile-time copy of MagicOS's toolbar (an Android Toolbar); it is declared here only so
 * the hand-written ActionBarEx stand-in can name it. Merged into the app's own class (same superclass, same
 * constructor), it adds nothing.
 */
public class HwToolbar extends android.widget.Toolbar {
    public HwToolbar(android.content.Context context, android.util.AttributeSet attrs) {
        super(context, attrs);
    }
}
