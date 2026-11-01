package org.ungoogled.honorrecorder;

import android.app.ActionBar;
import android.app.Activity;
import android.graphics.Color;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.widget.Toolbar;
import java.lang.reflect.Field;

/**
 * The recordings list's title bar (patches/.../honorrecorder). On MagicOS the list sits under the framework's
 * collapsing title bar, which also makes the list's toolbar the activity's action bar; elsewhere the list takes its
 * plain toolbar layout, and that toolbar becomes the action bar here, so the list gets its title and its menu
 * (settings, sorting, selection) as on MagicOS. The list draws its own status bar background under a translucent
 * status bar: MagicOS draws that bar fully see-through, Android with a dark scrim, so off MagicOS the bar is made
 * transparent instead (see setTranslucentStatus).
 */
public final class Appbar {
    private Appbar() {
    }

    public static ActionBar actionBar(Activity activity, Object fragment) {
        ActionBar bar = activity.getActionBar();
        if (bar != null || Magic.onMagicOs()) return bar;
        Toolbar toolbar = null;
        for (Class<?> c = fragment == null ? null : fragment.getClass(); c != null && toolbar == null; c = c.getSuperclass()) {
            try {
                Field field = c.getDeclaredField("mHwToolbar");
                field.setAccessible(true);
                toolbar = (Toolbar) field.get(fragment);
            } catch (NoSuchFieldException e) {
                // the field is the list fragments' base class's
            } catch (ReflectiveOperationException | RuntimeException e) {
                return null;
            }
        }
        if (toolbar == null) return null;
        activity.setActionBar(toolbar);
        return activity.getActionBar();
    }

    /** RecorderUtils.setTranslucentStatus: on MagicOS as Recorder does it; elsewhere a transparent bar, no scrim. */
    public static void setTranslucentStatus(Activity activity, boolean on) {
        if (activity == null) return;
        Window window = activity.getWindow();
        if (Magic.onMagicOs()) {
            WindowManager.LayoutParams attributes = window.getAttributes();
            if (on) attributes.flags |= WindowManager.LayoutParams.FLAG_TRANSLUCENT_STATUS;
            else attributes.flags &= ~WindowManager.LayoutParams.FLAG_TRANSLUCENT_STATUS;
            window.setAttributes(attributes);
            return;
        }
        window.clearFlags(WindowManager.LayoutParams.FLAG_TRANSLUCENT_STATUS);
        if (!on) return;
        window.addFlags(WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS);
        window.setStatusBarColor(Color.TRANSPARENT);
        View decor = window.getDecorView();
        decor.setSystemUiVisibility(decor.getSystemUiVisibility() | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN | View.SYSTEM_UI_FLAG_LAYOUT_STABLE);
    }
}
