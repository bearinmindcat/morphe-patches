package org.ungoogled.samsungemail;

import android.content.Context;

import com.samsung.android.content.clipboard.SemClipboardManager;

/**
 * Every Context.getSystemService(String) in Email comes here. Off One UI Samsung's own services are absent (null); the
 * clipboard one Email uses without checking (opening the composer) gets a stand-in that does nothing.
 */
public final class Services {
    private Services() {
    }

    public static Object get(Context context, String name) {
        Object service = context.getSystemService(name);
        if (service != null || name == null) return service;
        if ("semclipboard".equals(name)) return new SemClipboardManager();
        return null;
    }
}
