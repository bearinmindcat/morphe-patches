package org.ungoogled.coloroslauncher;

import android.appwidget.AppWidgetHost;
import android.appwidget.AppWidgetHostView;
import android.os.Handler;
import android.os.Looper;

/**
 * Widget hosting. Binding needs no help: where Android refuses the launcher's bind (BIND_APPWIDGET is the system's),
 * the launcher opens Android's own "Create widget and allow access?" dialog itself. What it cannot do is register a
 * widget view it made ahead of time for the widget's updates (AppWidgetHost.setListener, hidden).
 */
public final class Widgets {
    private static final ThreadLocal<AppWidgetHostView> PENDING = new ThreadLocal<>();

    private Widgets() {
    }

    /**
     * AppWidgetHost.setListener: the host's public createView registers the view instead (the patched onCreateView hands
     * it back through pending) and also fetches the widget's current views.
     */
    static void listen(AppWidgetHost host, int id, Object listener) {
        if (!(listener instanceof AppWidgetHostView)) return;
        AppWidgetHostView view = (AppWidgetHostView) listener;
        // setListener only records the listener and may be called from any thread (the launcher refreshes widget
        // containers on its LauncherUtilsThread); createView also sets the widget on the view, which only the UI thread
        // may touch once the view is shown (CalledFromWrongThreadException).
        if (Looper.myLooper() != Looper.getMainLooper()) {
            new Handler(Looper.getMainLooper()).post(() -> {
                if (view.getAppWidgetId() == id) register(host, id, view);
            });
            return;
        }
        register(host, id, view);
    }

    private static void register(AppWidgetHost host, int id, AppWidgetHostView view) {
        PENDING.set(view);
        try {
            host.createView(view.getContext(), id, view.getAppWidgetInfo());
        } finally {
            PENDING.remove();
        }
    }

    /** The host view listen() is registering for this widget, else null (the launcher makes a new one). */
    public static AppWidgetHostView pending(int id) {
        AppWidgetHostView view = PENDING.get();
        return view != null && view.getAppWidgetId() == id ? view : null;
    }
}
