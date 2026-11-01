package org.ungoogled.xiaomiscanner;

import android.content.Context;

/**
 * Scanner's device id for its own servers: HyperOS's OAID (com.android.id.impl.IdProviderImpl, a framework class),
 * or "" where the phone has none. Scanner's own lookup answered null off HyperOS, and its requests put that id into
 * forms that refuse null, so text QR codes and object recognition never got an answer.
 */
public final class Oaid {
    private static volatile String cached;

    private Oaid() {
    }

    public static String get(Context context) {
        String id = cached;
        if (id != null) return id;
        try {
            Class<?> provider = Class.forName("com.android.id.impl.IdProviderImpl");
            Object value = provider.getMethod("getOAID", Context.class).invoke(provider.getDeclaredConstructor().newInstance(), context);
            id = value != null ? String.valueOf(value) : "";
        } catch (Throwable e) {
            return ""; // not HyperOS: no OAID
        }
        if (!id.isEmpty()) cached = id;
        return id;
    }
}
