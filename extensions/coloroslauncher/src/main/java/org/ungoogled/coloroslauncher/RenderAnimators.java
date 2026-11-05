package org.ungoogled.coloroslauncher;

import android.animation.Animator;
import android.animation.ObjectAnimator;
import android.animation.TimeInterpolator;
import android.util.Property;
import android.view.Choreographer;
import android.view.View;

import com.oplus.view.OplusRenderNodeAnimator;

import java.lang.reflect.Method;
import java.util.ArrayList;

/**
 * ColorOS's render-thread animators (com.oplus.view.OplusRenderNodeAnimator), which the launcher finds and then uses for
 * folder, icon and popup animations. Off ColorOS the same animations run on the UI thread: a property animator becomes
 * an ObjectAnimator on the view, a render-thread spring (COUI's spring behind an IRtAnimationTarget) runs as the spring
 * it wraps. Without them those animations never moved (a folder opened half way, a popup never appeared).
 */
public final class RenderAnimators {
    private RenderAnimators() {
    }

    /** What an OplusRenderNodeAnimator was asked to animate. */
    static final class Spec {
        int property;
        float end;
        TimeInterpolator interpolator;
        View target;
        Float start;
    }

    private static Spec spec(Object animator) {
        Spec s = ColorOS.wrapped(animator, Spec.class);
        if (s == null) {
            s = new Spec();
            ColorOS.wrap(animator, s);
        }
        return s;
    }

    public static OplusRenderNodeAnimator create(int property, float end, TimeInterpolator interpolator) {
        OplusRenderNodeAnimator a = new OplusRenderNodeAnimator();
        Spec s = spec(a);
        s.property = property;
        s.end = end;
        s.interpolator = interpolator;
        return a;
    }

    public static void setTarget(Object animator, View view) {
        spec(animator).target = view;
    }

    public static void setStartValue(Object animator, float value) {
        spec(animator).start = value;
    }

    /** RenderNodeAnimator's property ids (TRANSLATION_X .. ALPHA) as View properties. */
    private static Property<View, Float> property(int id) {
        switch (id) {
            case 0: return View.TRANSLATION_X;
            case 1: return View.TRANSLATION_Y;
            case 2: return View.TRANSLATION_Z;
            case 3: return View.SCALE_X;
            case 4: return View.SCALE_Y;
            case 5: return View.ROTATION;
            case 6: return View.ROTATION_X;
            case 7: return View.ROTATION_Y;
            case 8: return View.X;
            case 9: return View.Y;
            case 10: return View.Z;
            default: return View.ALPHA;
        }
    }

    public static Animator animator(Object animator) {
        Spec s = spec(animator);
        ObjectAnimator a = s.start != null
                ? ObjectAnimator.ofFloat(s.target, property(s.property), s.start, s.end)
                : ObjectAnimator.ofFloat(s.target, property(s.property), s.end);
        if (s.interpolator != null) a.setInterpolator(s.interpolator);
        return a;
    }

    public static Animator rt(Object target, View view) {
        return target == null ? null : new Rt(target);
    }

    public static void animateTo(Animator animator, float value) {
        if (animator instanceof Rt) ((Rt) animator).animateTo(value);
    }

    /** A render-thread spring run as the UI-thread spring it wraps (COUIRtAnimationImpl's own methods). */
    static final class Rt extends Animator implements Choreographer.FrameCallback {
        private final Object target;
        private long duration = 300;
        private boolean watching;

        Rt(Object target) {
            this.target = target;
        }

        private Object call(String name, Class<?>[] types, Object... args) {
            try {
                Method m = target.getClass().getMethod(name, types);
                return m.invoke(target, args);
            } catch (ReflectiveOperationException | RuntimeException e) {
                return null;
            }
        }

        void animateTo(float value) {
            call("animateToFinalPosition", new Class<?>[] { float.class }, value);
            if (!watching) {
                watching = true;
                for (AnimatorListener l : listeners()) l.onAnimationStart(this);
                Choreographer.getInstance().postFrameCallback(this);
            }
        }

        private ArrayList<AnimatorListener> listeners() {
            ArrayList<AnimatorListener> l = getListeners();
            return l == null ? new ArrayList<>() : new ArrayList<>(l);
        }

        @Override
        public void doFrame(long frameTimeNanos) {
            if (isRunning()) {
                Choreographer.getInstance().postFrameCallback(this);
                return;
            }
            watching = false;
            for (AnimatorListener l : listeners()) l.onAnimationEnd(this);
        }

        @Override
        public void start() {
            call("start", new Class<?>[0]);
        }

        @Override
        public void cancel() {
            call("cancel", new Class<?>[0]);
        }

        @Override
        public void end() {
            call("end", new Class<?>[0]);
        }

        @Override
        public boolean isRunning() {
            Object r = call("isRunning", new Class<?>[0]);
            return r instanceof Boolean && (Boolean) r;
        }

        @Override
        public long getStartDelay() {
            return 0;
        }

        @Override
        public void setStartDelay(long startDelay) {
        }

        @Override
        public Animator setDuration(long duration) {
            this.duration = duration;
            return this;
        }

        @Override
        public long getDuration() {
            return duration;
        }

        @Override
        public void setInterpolator(TimeInterpolator value) {
        }
    }
}
