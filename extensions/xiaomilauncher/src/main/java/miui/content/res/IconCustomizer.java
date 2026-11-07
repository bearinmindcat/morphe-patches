package miui.content.res;

/**
 * Stand-in (.github/scripts/xiaomi-launcher/overrides): the generated one, except that the icon style (HyperOS's theme
 * shape around an icon) gives the icon back as it is: there is no HyperOS theme here, and "absent" (null) left pinned
 * shortcuts, installing apps and recents without an icon. Names only, no Xiaomi code.
 */
public class IconCustomizer {
    public static void clearCache() {
    }
    public static void clearCustomizedIcons(java.lang.String p0) {
    }
    public static android.graphics.drawable.BitmapDrawable generateIconStyleDrawable(android.graphics.drawable.Drawable p0) {
        return generateIconStyleDrawable(p0, false);
    }
    public static android.graphics.drawable.BitmapDrawable generateIconStyleDrawable(android.graphics.drawable.Drawable p0, boolean p1) {
        if (p0 == null) return null;
        if (p0 instanceof android.graphics.drawable.BitmapDrawable && ((android.graphics.drawable.BitmapDrawable) p0).getBitmap() != null) {
            return (android.graphics.drawable.BitmapDrawable) p0;
        }
        int w = p0.getIntrinsicWidth() > 0 ? p0.getIntrinsicWidth() : 192;
        int h = p0.getIntrinsicHeight() > 0 ? p0.getIntrinsicHeight() : 192;
        android.graphics.Bitmap b = android.graphics.Bitmap.createBitmap(w, h, android.graphics.Bitmap.Config.ARGB_8888);
        android.graphics.Canvas canvas = new android.graphics.Canvas(b);
        android.graphics.Rect bounds = new android.graphics.Rect(p0.getBounds());
        p0.setBounds(0, 0, w, h);
        p0.draw(canvas);
        p0.setBounds(bounds);
        return new android.graphics.drawable.BitmapDrawable(android.content.res.Resources.getSystem(), b);
    }
    public static java.lang.String getAnimatingIconRelativePath(android.content.pm.PackageItemInfo p0, java.lang.String p1, java.lang.String p2) {
        return p2;
    }
    public static android.graphics.drawable.BitmapDrawable getCustomizedIcon(android.content.Context p0, java.lang.String p1) {
        return null;
    }
    public static android.graphics.drawable.BitmapDrawable getCustomizedIcon(android.content.Context p0, java.lang.String p1, java.lang.String p2, android.graphics.drawable.Drawable p3) {
        return null;
    }
    public static int getCustomizedIconWidth() {
        return 0;
    }
    public static java.lang.String getFancyIconRelativePath(android.content.pm.PackageItemInfo p0, java.lang.String p1, java.lang.String p2) {
        return p2;
    }
    public static java.lang.String getMamlAdaptiveIconRelativePath(android.content.pm.PackageItemInfo p0, java.lang.String p1, java.lang.String p2) {
        return p2;
    }
    public static android.graphics.Bitmap getRawIcon(java.lang.String p0) {
        return null;
    }
    public static android.graphics.drawable.BitmapDrawable getRawIconDrawable(java.lang.String p0) {
        return null;
    }
    public static boolean isModIconEnabledForPackageName(java.lang.String p0) {
        return false;
    }
}
