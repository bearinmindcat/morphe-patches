package org.ungoogled.ui;

import android.content.Context;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.VelocityTracker;
import android.view.ViewConfiguration;
import android.widget.LinearLayout;
import android.widget.OverScroller;

/**
 * Navigation's media panel: the row of the other media apps under the controls (issue #206). Maps lays it out as a
 * plain horizontal LinearLayout as wide as the sheet, so the apps past the fifth or so were cut off with no way to reach
 * them. This is the same row, its apps measured at their own width, scrolled sideways by a drag or a fling.
 */
public class ScrollRow extends LinearLayout {
    private final OverScroller scroller;
    private final int slop, minFling, maxFling;
    private VelocityTracker velocity;
    private float downX, downY, lastX;
    private boolean dragging;
    private int content;

    public ScrollRow(Context context) {
        this(context, null);
    }

    public ScrollRow(Context context, AttributeSet attrs) {
        super(context, attrs);
        scroller = new OverScroller(context);
        ViewConfiguration vc = ViewConfiguration.get(context);
        slop = vc.getScaledTouchSlop();
        minFling = vc.getScaledMinimumFlingVelocity();
        maxFling = vc.getScaledMaximumFlingVelocity();
        setHorizontalScrollBarEnabled(false);
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        int width = MeasureSpec.getSize(widthMeasureSpec);
        if (getOrientation() != HORIZONTAL || MeasureSpec.getMode(widthMeasureSpec) == MeasureSpec.UNSPECIFIED) {
            super.onMeasure(widthMeasureSpec, heightMeasureSpec);
            content = getMeasuredWidth();
            return;
        }
        // Every app at its own width, however many there are; the row itself stays as wide as it was given.
        super.onMeasure(MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED), heightMeasureSpec);
        content = getMeasuredWidth();
        int measured = MeasureSpec.getMode(widthMeasureSpec) == MeasureSpec.EXACTLY ? width : Math.min(content, width);
        setMeasuredDimension(measured, getMeasuredHeight());
    }

    @Override
    protected void onLayout(boolean changed, int l, int t, int r, int b) {
        super.onLayout(changed, l, t, r, b);
        clampScroll();
    }

    private int range() {
        return Math.max(0, content - getWidth());
    }

    private void clampScroll() {
        int x = Math.max(0, Math.min(getScrollX(), range()));
        if (x != getScrollX()) scrollTo(x, 0);
    }

    @Override
    public boolean onInterceptTouchEvent(MotionEvent e) {
        if (range() <= 0) return false;
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                downX = lastX = e.getX();
                downY = e.getY();
                dragging = !scroller.isFinished();
                scroller.forceFinished(true);
                track(e, true);
                return dragging;
            case MotionEvent.ACTION_MOVE:
                track(e, false);
                float dx = Math.abs(e.getX() - downX), dy = Math.abs(e.getY() - downY);
                if (dx > slop && dx > dy) {
                    dragging = true;
                    lastX = e.getX();
                    if (getParent() != null) getParent().requestDisallowInterceptTouchEvent(true);
                }
                return dragging;
            default:
                if (e.getActionMasked() == MotionEvent.ACTION_UP || e.getActionMasked() == MotionEvent.ACTION_CANCEL) dragging = false;
                return false;
        }
    }

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        if (range() <= 0) return super.onTouchEvent(e);
        track(e, e.getActionMasked() == MotionEvent.ACTION_DOWN);
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                downX = lastX = e.getX();
                downY = e.getY();
                scroller.forceFinished(true);
                return true;
            case MotionEvent.ACTION_MOVE:
                if (!dragging && Math.abs(e.getX() - downX) > slop) {
                    dragging = true;
                    if (getParent() != null) getParent().requestDisallowInterceptTouchEvent(true);
                }
                if (dragging) {
                    int to = Math.max(0, Math.min(range(), getScrollX() + Math.round(lastX - e.getX())));
                    scrollTo(to, 0);
                }
                lastX = e.getX();
                return true;
            case MotionEvent.ACTION_UP:
                if (dragging && velocity != null) {
                    velocity.computeCurrentVelocity(1000, maxFling);
                    int v = (int) velocity.getXVelocity();
                    if (Math.abs(v) > minFling) {
                        scroller.fling(getScrollX(), 0, -v, 0, 0, range(), 0, 0);
                        postInvalidateOnAnimation();
                    }
                }
                // fall through
            case MotionEvent.ACTION_CANCEL:
                dragging = false;
                if (velocity != null) {
                    velocity.recycle();
                    velocity = null;
                }
                return true;
            default:
                return true;
        }
    }

    private void track(MotionEvent e, boolean restart) {
        if (velocity == null || restart) {
            if (velocity != null) velocity.recycle();
            velocity = VelocityTracker.obtain();
        }
        velocity.addMovement(e);
    }

    @Override
    public void computeScroll() {
        if (scroller.computeScrollOffset()) {
            scrollTo(Math.max(0, Math.min(range(), scroller.getCurrX())), 0);
            postInvalidateOnAnimation();
        }
    }

    @Override
    protected int computeHorizontalScrollRange() {
        return content;
    }

    @Override
    public boolean canScrollHorizontally(int direction) {
        return direction < 0 ? getScrollX() > 0 : getScrollX() < range();
    }
}
