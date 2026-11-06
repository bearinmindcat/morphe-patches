package org.ungoogled.samsunghomeup;

import android.content.ContentProvider;
import android.content.Context;
import android.content.pm.PackageManager;
import android.os.Binder;
import android.os.Process;

/**
 * Who may use Home Up's settings provider: Home Up itself and apps signed with its key (the patched One UI Home).
 * On One UI a permission guards it, which One UI Home holds only when Home Up was installed first.
 */
public final class Access {
    private Access() {
    }

    public static boolean allowed(ContentProvider provider) {
        int uid = Binder.getCallingUid();
        if (uid == Process.myUid()) return true;
        Context context = provider.getContext();
        if (context == null) return false;
        return context.getPackageManager().checkSignatures(Process.myUid(), uid) == PackageManager.SIGNATURE_MATCH;
    }
}
