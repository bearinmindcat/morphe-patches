package org.ungoogled.ui;

import android.content.Context;
import android.view.View;
import android.widget.Toast;

import java.lang.reflect.Field;

/** Remove sign-in prompts: what the account sheet's profile row and sign-in pill do instead of signing in. */
public final class SignIn {
    private SignIn() {}

    /**
     * Shows "Can't sign in". [from] is whatever the tapped row has at hand: a Context, a View, or the
     * row's holder, which keeps the Activity in one of its fields.
     */
    public static void cantSignIn(Object from) {
        Context c = context(from);
        if (c != null) Toast.makeText(c, "Can't sign in", Toast.LENGTH_SHORT).show();
    }

    private static Context context(Object o) {
        if (o instanceof Context) return (Context) o;
        if (o instanceof View) return ((View) o).getContext();
        if (o == null) return null;
        for (Class<?> k = o.getClass(); k != null && k != Object.class; k = k.getSuperclass()) {
            for (Field f : k.getDeclaredFields()) {
                if (f.getType().isPrimitive() || java.lang.reflect.Modifier.isStatic(f.getModifiers())) continue;
                try {
                    f.setAccessible(true);
                    Object v = f.get(o);
                    if (v instanceof Context) return (Context) v;
                } catch (Throwable ignored) {
                }
            }
        }
        return null;
    }
}
