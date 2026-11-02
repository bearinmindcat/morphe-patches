package com.hihonor.android.widget;

/**
 * Generated stand-in (.github/scripts/honor-standins): names and signatures Honor Weather uses, answering "absent" off
 * MagicOS (no Honor code). Answered by hand: a row that never swipes open (its states are distinct objects, so "open"
 * is never the answer), and SwipeListener an interface as on MagicOS.
 */
public class SwipeLayout extends android.widget.FrameLayout {
    public SwipeLayout(android.content.Context p0) {
        super(p0);
    }
    public SwipeLayout(android.content.Context p0, android.util.AttributeSet p1) {
        super(p0, p1);
    }
    public SwipeLayout(android.content.Context p0, android.util.AttributeSet p1, int p2) {
        super(p0, p1, p2);
    }
    public SwipeLayout(android.content.Context p0, android.util.AttributeSet p1, int p2, int p3) {
        super(p0, p1, p2, p3);
    }
    public void addDrag(com.hihonor.android.widget.SwipeLayout.DragEdge p0, android.view.View p1) {
    }
    public void addSwipeListener(com.hihonor.android.widget.SwipeLayout.SwipeListener p0) {
    }
    public void close(boolean p0, boolean p1) {
    }
    public com.hihonor.android.widget.SwipeLayout.Status getOpenStatus() {
        return com.hihonor.android.widget.SwipeLayout.Status.Close;
    }
    public boolean hasUserSetSwipeListener() {
        return false;
    }
    public void setShowMode(com.hihonor.android.widget.SwipeLayout.ShowMode p0) {
    }
    public static class DragEdge {
        public static com.hihonor.android.widget.SwipeLayout.DragEdge Left = new DragEdge();
        public static com.hihonor.android.widget.SwipeLayout.DragEdge Right = new DragEdge();
    }
    public static class ShowMode {
        public static com.hihonor.android.widget.SwipeLayout.ShowMode PullOut = new ShowMode();
    }
    public static class Status {
        public static com.hihonor.android.widget.SwipeLayout.Status Open = new Status();
        public static com.hihonor.android.widget.SwipeLayout.Status Close = new Status();
    }
    /** An interface on MagicOS: SimpleSwipeListener implements it. */
    public interface SwipeListener {
    }
}
