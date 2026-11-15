package org.ungoogled.coloroslauncher;

import android.content.Context;
import android.content.res.Resources;
import android.util.Log;

/**
 * The icon style ColorOS's icon library draws large app icons with. ColorOS keeps it in its own configuration (an OPPO
 * value the system sets from the theme); elsewhere there is none, and every large icon failed to load. Where the phone
 * has none, the library's default style: the "Rectangle" theme (ColorOS's default icon theme) with the library's own
 * default sizes, as its parser builds it.
 */
public final class Icons {
    private static final String TAG = "UA";
    /** Theme 2 (IconConfig.THEME_RECTANGLE), everything else default (sizes from the library's own resources). */
    private static final long DEFAULT_STYLE = 2L << 4;
    private static Object style;

    private Icons() {
    }

    public static Object style(Object current) {
        if (current != null) return current;
        if (style != null) return style;
        Context c = App.context();
        if (c == null) return null;
        try {
            style = Class.forName("com.oplus.uxicon.helper.IconConfigParser", true, Icons.class.getClassLoader())
                    .getMethod("parserConfig", Resources.class, Long.class).invoke(null, c.getResources(), DEFAULT_STYLE);
        } catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
            if (Log.isLoggable(TAG, Log.DEBUG)) Log.d(TAG, "no default icon style: " + e);
        }
        return style;
    }
}
