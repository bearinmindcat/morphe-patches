package com.android.internal.jank;

/** Generated stand-in (.github/scripts/coloros-launcher): names and signatures OnePlus System Launcher uses, answering "absent" off ColorOS and on Android versions without them (no OPPO or Google code). */
public class InteractionJankMonitor {
    public InteractionJankMonitor() {
    }
    public boolean begin(android.view.View p0, int p1) {
        return false;
    }
    public boolean begin(com.android.internal.jank.InteractionJankMonitor.Configuration.Builder p0) {
        return false;
    }
    public boolean cancel(int p0) {
        return false;
    }
    public boolean end(int p0) {
        return false;
    }
    public static com.android.internal.jank.InteractionJankMonitor getInstance() {
        return new com.android.internal.jank.InteractionJankMonitor();
    }
    public static class Configuration {
        public static class Builder {
            public Builder() {
            }
            public com.android.internal.jank.InteractionJankMonitor.Configuration.Builder setTag(java.lang.String p0) {
                return this;
            }
            public com.android.internal.jank.InteractionJankMonitor.Configuration.Builder setTimeout(long p0) {
                return this;
            }
            public static com.android.internal.jank.InteractionJankMonitor.Configuration.Builder withSurface(int p0, android.content.Context p1, android.view.SurfaceControl p2) {
                return new com.android.internal.jank.InteractionJankMonitor.Configuration.Builder();
            }
            public static com.android.internal.jank.InteractionJankMonitor.Configuration.Builder withView(int p0, android.view.View p1) {
                return new com.android.internal.jank.InteractionJankMonitor.Configuration.Builder();
            }
        }
    }
}
