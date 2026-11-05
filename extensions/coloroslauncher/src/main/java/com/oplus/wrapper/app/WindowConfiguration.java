package com.oplus.wrapper.app;

/** Generated stand-in (.github/scripts/coloros-launcher): names and signatures OnePlus System Launcher uses, answering "absent" off ColorOS and on Android versions without them (no OPPO or Google code). */
public class WindowConfiguration {
    public static int ROTATION_UNDEFINED = -1;
    public static int WINDOWING_MODE_SPLIT_SCREEN_SECONDARY = 4;
    public android.graphics.Rect getBounds() {
        return org.ungoogled.coloroslauncher.Reflect.windowConfiguration.getBounds();
    }
    public android.graphics.Rect getMaxBounds() {
        return org.ungoogled.coloroslauncher.Reflect.windowConfiguration.getMaxBounds();
    }
    public int getRotation() {
        return org.ungoogled.coloroslauncher.Reflect.windowConfiguration.getRotation();
    }
}
