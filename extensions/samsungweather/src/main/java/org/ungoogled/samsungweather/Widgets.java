package org.ungoogled.samsungweather;

import android.appwidget.AppWidgetManager;
import android.content.res.Configuration;
import android.content.res.Resources;
import android.os.Build;
import android.os.Bundle;
import android.util.SizeF;

import java.util.ArrayList;

/**
 * Widget text sizes. Weather's widgets take their text and padding sizes from a one-row height: One UI's home screen
 * hands it over (or the row span, from which Weather works it out), other launchers hand nothing and Weather then
 * assumed a fixed 56 dp row whatever the widget's real size. Launchers elsewhere make bigger widgets than One UI (a
 * Pixel 2x2 is 173 x 225 dp against One UI's 125 x 130 reference), so the text came out small and out of proportion.
 * Here the row height is One UI's reference row (52 dp) scaled like the widget: the widget's size against One UI's
 * reference size of the same kind (Samsung's own table: 1x1 52 x 52, 2x1 125 x 52, 4x1 270 x 52, 2x2 125 x 130, 4x2
 * 270 x 130, taller 270 x 280 and 270 x 430), the smaller of the two ratios so nothing outgrows its widget.
 */
public final class Widgets {
    private static final String ROW_SPAN = "semAppWidgetRowSpan";
    private static final float REFERENCE_ROW = 52f;

    /** The options the size helper just read, for rowHeight (same thread, same call). */
    private static final ThreadLocal<Bundle> OPTIONS = new ThreadLocal<>();

    private Widgets() {
    }

    /** AppWidgetManager.getAppWidgetOptions in Weather's widget size helper: noted for rowHeight. */
    public static Bundle getAppWidgetOptions(AppWidgetManager manager, int id) {
        Bundle options = manager.getAppWidgetOptions(id);
        OPTIONS.set(options);
        return options;
    }

    /** The one-row height the helper sizes text and padding from; Weather's own where One UI's home screen is there. */
    public static float rowHeight(float fallback) {
        Bundle options = OPTIONS.get();
        OPTIONS.remove();
        if (options == null || options.containsKey(ROW_SPAN)) return fallback;
        Configuration config = Resources.getSystem().getConfiguration();
        boolean portrait = config.orientation != Configuration.ORIENTATION_LANDSCAPE;
        SizeF size = size(options, portrait);
        if (size == null || size.getWidth() <= 0 || size.getHeight() <= 0) return fallback;
        // Weather's size classes for other launchers (its he.b / he.a), and One UI's reference size of each.
        float k = config.smallestScreenWidthDp >= 800 ? 1.3f : 1f;
        float w = size.getWidth() / k;
        float h = size.getHeight() / k;
        float refW = w < (portrait ? 118 : 109) ? 52 : w < (portrait ? 268 : 345) ? 125 : 270;
        float refH = h < (portrait ? 131 : 120) ? 52 : h < (portrait ? 354 : 241) ? 130 : h < 600 ? 280 : 430;
        return REFERENCE_ROW * k * Math.min(w / refW, h / refH);
    }

    /** The widget's size as Weather measures it: narrowest and tallest in portrait, widest and shortest in landscape. */
    @SuppressWarnings("deprecation")
    private static SizeF size(Bundle options, boolean portrait) {
        ArrayList<SizeF> sizes = Build.VERSION.SDK_INT >= 33
                ? options.getParcelableArrayList(AppWidgetManager.OPTION_APPWIDGET_SIZES, SizeF.class)
                : options.getParcelableArrayList(AppWidgetManager.OPTION_APPWIDGET_SIZES);
        if (sizes != null && !sizes.isEmpty()) {
            float w = portrait ? Float.MAX_VALUE : 0f;
            float h = portrait ? 0f : Float.MAX_VALUE;
            for (SizeF s : sizes) {
                w = portrait ? Math.min(w, s.getWidth()) : Math.max(w, s.getWidth());
                h = portrait ? Math.max(h, s.getHeight()) : Math.min(h, s.getHeight());
            }
            return new SizeF(w, h);
        }
        int w = options.getInt(portrait ? AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH : AppWidgetManager.OPTION_APPWIDGET_MAX_WIDTH, 0);
        int h = options.getInt(portrait ? AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT : AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT, 0);
        return w > 0 && h > 0 ? new SizeF(w, h) : null;
    }
}
