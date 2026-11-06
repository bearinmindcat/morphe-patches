package miui.app;

/**
 * Stand-in (.github/scripts/xiaomi-launcher/overrides): the generated one, but HyperOS's quick toggles (offered as home
 * screen shortcuts in the widget picker) are an empty list here, not null (the picker iterates it). Names only, no
 * Xiaomi code.
 */
public class ToggleManager {
    public ToggleManager() {
    }
    public static miui.app.ToggleManager createInstance(android.content.Context p0) {
        return new miui.app.ToggleManager();
    }
    public static java.util.ArrayList getAllToggles(android.content.Context p0) {
        return new java.util.ArrayList();
    }
    public static int getGeneralImage(int p0) {
        return 0;
    }
    public static android.graphics.drawable.Drawable getImageDrawable(int p0, android.content.Context p1) {
        return null;
    }
    public static int getName(int p0) {
        return 0;
    }
    public static boolean getStatus(int p0) {
        return false;
    }
    public static java.lang.String getStatusName(int p0, android.content.res.Resources p1) {
        return "";
    }
    public static int getToggleIdFromString(java.lang.String p0) {
        return 0;
    }
    public static java.lang.String getToggleStringFromId(int p0) {
        return "";
    }
    public static void initDrawable(int p0, android.graphics.drawable.Drawable p1) {
    }
    public void setOnToggleChangedListener(miui.app.ToggleManager.OnToggleChangedListener p0) {
    }
    public interface OnToggleChangedListener {
    }
}
