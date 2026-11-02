package org.ungoogled.colorosnotes;

import android.app.KeyguardManager;
import android.content.Context;
import android.content.Intent;

/**
 * Locked notes. On ColorOS they are guarded by its "privacy password" (Settings' ChooseGenericPrivacy to set it, its
 * confirm screen to enter it, a Settings provider that says whether one is set); none of that exists elsewhere, so
 * "Locked notes" and "Lock" did nothing. Off ColorOS the phone's own screen lock (PIN, pattern, password, fingerprint)
 * guards them instead (LockActivity). Where ColorOS's privacy provider exists, the app's own path stays.
 */
public final class Locks {
    private static final String[] COLOROS_PROVIDERS = {
        "oplus.provider.settings.PrivacyStateProvider", "com.oppo.settings.privacy.PrivacyStateProvider",
    };

    private Locks() {
    }

    private static boolean colorOs(Context context) {
        try {
            for (String authority : COLOROS_PROVIDERS) {
                if (context.getPackageManager().resolveContentProvider(authority, 0) != null) return true;
            }
        } catch (RuntimeException e) {
            // fall through: treat as absent
        }
        return false;
    }

    /** SafeServiceUtils.hasSettingsPassword: 1/0 = the phone has a secure screen lock, -1 = ColorOS (the app's own check). */
    public static int hasPassword(Context context) {
        if (context == null) return 0;
        if (colorOs(context)) return -1;
        KeyguardManager keyguard = context.getSystemService(KeyguardManager.class);
        return keyguard != null && keyguard.isDeviceSecure() ? 1 : 0;
    }

    /** PrivacyPasswordUtilsKt.privacyPasswordIntent: our screen-lock check, or null on ColorOS (the app's own intent). */
    public static Intent intent(Context context, Boolean hasPassword) {
        if (context == null || colorOs(context)) return null;
        return new Intent(context, LockActivity.class);
    }
}
