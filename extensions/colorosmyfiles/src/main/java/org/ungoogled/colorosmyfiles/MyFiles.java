package org.ungoogled.colorosmyfiles;

import android.app.Application;
import android.content.pm.PackageManager;
import android.os.Build;

/** OnePlus My Files's answers where it asks ColorOS's phones something only they can answer. */
public final class MyFiles {
    private MyFiles() {
    }

    /**
     * My Files ships one code base for OnePlus, OPPO and realme and picks its "brand flavour" from Build.BRAND: OnePlus
     * phones get OnePlus's (application id com.oneplus.filemanager), every other phone OPPO's (com.coloros.filemanager),
     * so off OPPO's phones the OnePlus build looked for its own data folder, recycle-bin provider and file provider
     * under OPPO's package name and found nothing. This build is OnePlus's: answer "OnePlus" everywhere except on OPPO and
     * realme phones, where the app's own choice stays.
     */
    public static boolean isOnePlusBrand() {
        String brand = Build.BRAND;
        return !"oppo".equalsIgnoreCase(brand) && !"realme".equalsIgnoreCase(brand);
    }

    /**
     * The Private Safe (the main screen's row, the "Lock" menu item, its settings switch) is ColorOS's encryption app
     * (com.coloros.encryption, com.oplus.encryption), which My Files only opens; the app asks ColorOS's feature list
     * whether it is supported, and off ColorOS that list says nothing, so the row showed and did nothing. Supported only
     * where the encryption app is installed.
     */
    public static boolean hasPrivateSafe() {
        return installed("com.coloros.encryption") || installed("com.oplus.encryption");
    }

    private static boolean installed(String pkg) {
        try {
            Application app = (Application) Class.forName("android.app.ActivityThread").getMethod("currentApplication").invoke(null);
            if (app == null) return false;
            app.getPackageManager().getPackageInfo(pkg, 0);
            return true;
        } catch (PackageManager.NameNotFoundException e) {
            return false;
        } catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
            return false;
        }
    }
}
