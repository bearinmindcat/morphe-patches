package android.window;

/** Generated stand-in (.github/scripts/coloros-launcher): names and signatures OnePlus System Launcher uses, answering "absent" off ColorOS and on Android versions without them (no OPPO or Google code). */
public class SplashScreenView {
    public SplashScreenView() {
    }
    public void addOnAttachStateChangeListener(android.view.View.OnAttachStateChangeListener p0) {
    }
    public android.view.View getBrandingView() {
        return null;
    }
    public java.time.Duration getIconAnimationDuration() {
        return null;
    }
    public android.view.View getIconView() {
        return null;
    }
    public int getInitBackgroundColor() {
        return 0;
    }
    public android.view.SurfaceControlViewHost getSurfaceHost() {
        return null;
    }
    public android.view.WindowInsetsController getWindowInsetsController() {
        return null;
    }
    public boolean isAttachedToWindow() {
        return false;
    }
    public boolean isCopyable() {
        return false;
    }
    public void onCopied() {
    }
    public boolean postDelayed(java.lang.Runnable p0, long p1) {
        return false;
    }
    public static void releaseIconHost(android.view.SurfaceControlViewHost p0) {
    }
    public void setBackground(android.graphics.drawable.Drawable p0) {
    }
    public void setNotCopyable() {
    }
    public void setVisibility(int p0) {
    }
    public static class Builder {
        public Builder(android.content.Context p0) {
        }
        public Builder() {
        }
        public android.window.SplashScreenView build() {
            return new android.window.SplashScreenView();
        }
        public android.window.SplashScreenView.Builder setAllowHandleSolidColor(boolean p0) {
            return this;
        }
        public android.window.SplashScreenView.Builder setBackgroundColor(int p0) {
            return this;
        }
        public android.window.SplashScreenView.Builder setBrandingDrawable(android.graphics.drawable.Drawable p0, int p1, int p2) {
            return this;
        }
        public android.window.SplashScreenView.Builder setCenterViewDrawable(android.graphics.drawable.Drawable p0) {
            return this;
        }
        public android.window.SplashScreenView.Builder setIconBackground(android.graphics.drawable.Drawable p0) {
            return this;
        }
        public android.window.SplashScreenView.Builder setIconSize(int p0) {
            return this;
        }
        public android.window.SplashScreenView.Builder setOverlayDrawable(android.graphics.drawable.Drawable p0) {
            return this;
        }
        public android.window.SplashScreenView.Builder setUiThreadInitConsumer(java.util.function.Consumer p0) {
            return this;
        }
    }
    public interface IconAnimateListener {
    }
    public static class SplashScreenViewParcelable {
        public SplashScreenViewParcelable(android.window.SplashScreenView p0) {
        }
    }
}
