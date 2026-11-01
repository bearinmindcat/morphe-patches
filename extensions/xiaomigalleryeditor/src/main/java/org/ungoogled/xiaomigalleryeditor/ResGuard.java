package org.ungoogled.xiaomigalleryeditor;

import android.content.Context;
import android.content.res.Resources;

/**
 * Xiaomi's "magic string" resource hook: the editor swaps its Application's and every Activity's Resources for a
 * wrapper that decrypts the string and raw resources listed in its arrays magic_string_white_list and
 * magic_resource_white_list. Building that wrapper needs Android's hidden ResourcesManager internals, which a
 * preinstalled HyperOS app may use and an installed app may not (the first Activity died with NoSuchMethodException).
 * The Global build lists nothing in either array, so the hook would change nothing: install it only when it has work.
 */
public final class ResGuard {
    private ResGuard() {
    }

    /** True when the app's resources list anything to decrypt. */
    public static boolean needed(Context context) {
        try {
            Resources res = context.getResources();
            return listed(context, res, "magic_string_white_list") || listed(context, res, "magic_resource_white_list");
        } catch (RuntimeException e) {
            return true;
        }
    }

    private static boolean listed(Context context, Resources res, String name) {
        int id = res.getIdentifier(name, "array", context.getPackageName());
        if (id == 0) return true; // can't tell: keep Xiaomi's behaviour
        int[] ids = res.getIntArray(id);
        return ids != null && ids.length > 0;
    }
}
