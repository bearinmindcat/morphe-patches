package org.ungoogled.samsunglauncher;

/**
 * App launches. One UI Home animates every launch with a remote animation, which Android only lets a privileged home
 * app start: anything else gets a SecurityException and the app does not open. Without that permission launches use
 * Android's own animation.
 */
public final class Launch {
    private Launch() {
    }

    /** Whether launches may use remote animations (the patch skips One UI Home's own launch animation otherwise). */
    public static boolean remoteAnimations() {
        return App.remoteAnimations();
    }

    /** Whether the suggested-apps predictions may be asked for (the patch skips them otherwise). */
    public static boolean predictions() {
        return App.predictions();
    }
}
