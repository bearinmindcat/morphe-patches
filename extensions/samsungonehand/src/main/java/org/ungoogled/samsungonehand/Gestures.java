package org.ungoogled.samsungonehand;

import android.accessibilityservice.GestureDescription;
import android.content.Context;
import android.graphics.Path;
import android.util.SparseArray;
import android.view.MotionEvent;

import java.util.ArrayList;
import java.util.List;

/**
 * One Hand Operation + touches the screen for the user (a tap through its pointer, a pinch, a swipe) by injecting
 * touch events, which only the system may do. The patch sends each injected event here instead: the events from a
 * first touch to the last finger's release are collected, then played through the accessibility service as one
 * gesture with the same paths and timing.
 */
public final class Gestures {
    private static final class Finger {
        final Path path = new Path();
        final long start;
        long end;

        Finger(float x, float y, long time) {
            path.moveTo(Math.max(0, x), Math.max(0, y));
            start = time;
            end = time;
        }

        void to(float x, float y, long time) {
            path.lineTo(Math.max(0, x), Math.max(0, y));
            end = time;
        }
    }

    private static final SparseArray<Finger> DOWN = new SparseArray<>();
    private static final List<Finger> UP = new ArrayList<>();
    private static long begin = -1;

    private Gestures() {
    }

    /** One injected touch event. Always handled here (Android refuses the injection itself). */
    public static synchronized boolean inject(Context context, MotionEvent e) {
        long time = e.getEventTime();
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                DOWN.clear();
                UP.clear();
                begin = time;
                DOWN.put(e.getPointerId(0), new Finger(e.getX(0), e.getY(0), time));
                break;
            case MotionEvent.ACTION_POINTER_DOWN: {
                int i = e.getActionIndex();
                if (begin >= 0) DOWN.put(e.getPointerId(i), new Finger(e.getX(i), e.getY(i), time));
                break;
            }
            case MotionEvent.ACTION_MOVE:
                for (int i = 0; i < e.getPointerCount(); i++) {
                    Finger f = DOWN.get(e.getPointerId(i));
                    if (f != null) f.to(e.getX(i), e.getY(i), time);
                }
                break;
            case MotionEvent.ACTION_POINTER_UP: {
                int i = e.getActionIndex();
                Finger f = DOWN.get(e.getPointerId(i));
                if (f != null) {
                    f.end = Math.max(f.end, time);
                    UP.add(f);
                    DOWN.remove(e.getPointerId(i));
                }
                break;
            }
            case MotionEvent.ACTION_UP: {
                Finger f = DOWN.get(e.getPointerId(0));
                if (f != null) {
                    f.end = Math.max(f.end, time);
                    UP.add(f);
                }
                DOWN.clear();
                play();
                break;
            }
            case MotionEvent.ACTION_CANCEL:
                DOWN.clear();
                UP.clear();
                begin = -1;
                break;
        }
        return true;
    }

    private static void play() {
        List<Finger> fingers = new ArrayList<>(UP);
        long start = begin;
        UP.clear();
        begin = -1;
        if (fingers.isEmpty() || start < 0) return;
        ActionService service = ActionService.instance;
        if (service == null) {
            Actions.needService();
            return;
        }
        GestureDescription.Builder gesture = new GestureDescription.Builder();
        int strokes = 0;
        for (Finger f : fingers) {
            if (strokes == GestureDescription.getMaxStrokeCount()) break;
            long at = Math.max(0, f.start - start);
            long duration = Math.max(10, Math.min(f.end - f.start, GestureDescription.getMaxGestureDuration() - at - 1));
            gesture.addStroke(new GestureDescription.StrokeDescription(f.path, at, duration));
            strokes++;
        }
        try {
            service.dispatchGesture(gesture.build(), null, null);
        } catch (RuntimeException e) {
            OneHand.debug("gesture refused: " + e);
        }
    }
}
