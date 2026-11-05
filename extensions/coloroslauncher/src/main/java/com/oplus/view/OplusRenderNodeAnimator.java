package com.oplus.view;

/** Generated stand-in (.github/scripts/coloros-launcher): names and signatures OnePlus System Launcher uses, answering "absent" off ColorOS and on Android versions without them (no OPPO or Google code). */
public class OplusRenderNodeAnimator {
    public OplusRenderNodeAnimator() {
    }
    public static void animateToFinalPosition(android.animation.Animator p0, float p1) {
        org.ungoogled.coloroslauncher.RenderAnimators.animateTo(p0, p1);
    }
    public static com.oplus.view.OplusRenderNodeAnimator createInterpolatorAnimator(int p0, float p1, android.animation.TimeInterpolator p2) {
        return org.ungoogled.coloroslauncher.RenderAnimators.create(p0, p1, p2);
    }
    public static android.animation.TimeInterpolator createOSpringInterpolator(double p0, double p1, double p2, float p3, float p4) {
        return new android.view.animation.DecelerateInterpolator(1.5f);
    }
    public static android.animation.Animator createRenderValueAnimator(android.animation.Animator p0, android.view.View p1) {
        return p0;
    }
    public static android.animation.Animator createRtAnimator(com.oplus.view.IRtAnimationTarget p0, android.view.View p1) {
        return org.ungoogled.coloroslauncher.RenderAnimators.rt(p0, p1);
    }
    public android.animation.Animator getRenderNodeAnimator() {
        return org.ungoogled.coloroslauncher.RenderAnimators.animator(this);
    }
    public void setStartValue(float p0) {
        org.ungoogled.coloroslauncher.RenderAnimators.setStartValue(this, p0);
    }
    public void setTarget(android.view.View p0) {
        org.ungoogled.coloroslauncher.RenderAnimators.setTarget(this, p0);
    }
}
