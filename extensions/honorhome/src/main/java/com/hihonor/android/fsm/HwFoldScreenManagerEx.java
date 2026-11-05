package com.hihonor.android.fsm;

/**
 * Stand-in (written by hand from the generated one): MagicOS's fold screen manager. Off MagicOS there is no fold
 * screen and no outer screen: no apps configured for the outer screen (Honor Home looks these lists up by reflection
 * and walks them without a null check; uninstalling an app crashed the home screen without them).
 */
public class HwFoldScreenManagerEx {
    public static int getDisplayMode() {
        return 0;
    }
    public static boolean isFoldable() {
        return false;
    }
    public static boolean isInwardFoldDevice() {
        return false;
    }
    public static boolean isVerticalInwardFoldDevice() {
        return false;
    }
    public static java.util.List<Object> getExtScreenAppConfig(int p0) {
        return new java.util.ArrayList<>();
    }
    public static java.util.List<String> getCurrentExtScreenAppList(int p0) {
        return new java.util.ArrayList<>();
    }
    public static int setCurrentExtScreenAppList(java.util.List<String> p0, int p1) {
        return 0;
    }
    public static void registerFoldDisplayMode(com.hihonor.android.fsm.HwFoldScreenManagerEx.FoldDisplayModeListener p0) {
    }
    public static void unregisterFoldDisplayMode(com.hihonor.android.fsm.HwFoldScreenManagerEx.FoldDisplayModeListener p0) {
    }
    public interface FoldDisplayModeListener {
    }
}
