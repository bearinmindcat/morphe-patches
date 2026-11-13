package org.ungoogled.samsungfinder;

import android.app.DownloadManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.os.Environment;
import android.provider.DocumentsContract;

import java.io.File;

/**
 * Finder's "Downloads" and "Screenshots" shortcuts open Samsung My Files and Samsung Gallery. Where those are not
 * installed they open Android's own Files app instead (its Downloads view, and the screenshots folder), and show its
 * icon.
 */
public final class Directories {
    private static final String MY_FILES = "com.sec.android.app.myfiles";
    private static final String GALLERY = "com.sec.android.gallery3d";

    private Directories() {
    }

    private static boolean installed(Context c, String packageName) {
        try {
            c.getPackageManager().getPackageInfo(packageName, 0);
            return true;
        } catch (PackageManager.NameNotFoundException e) {
            return false;
        }
    }

    /** The Files app's launcher entry (null if there is none). */
    private static ComponentName filesApp(Context c) {
        ResolveInfo r = c.getPackageManager().resolveActivity(
                new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_APP_FILES), 0);
        return r == null || r.activityInfo == null ? null : new ComponentName(r.activityInfo.packageName, r.activityInfo.name);
    }

    /** The shortcut icon's app: Samsung's where installed, else the Files app. */
    public static String iconPackage(String packageName, String className) {
        Context c = App.context();
        if (c == null || installed(c, packageName)) return packageName;
        ComponentName files = filesApp(c);
        return files == null ? packageName : files.getPackageName();
    }

    public static String iconClass(String packageName, String className) {
        Context c = App.context();
        if (c == null || installed(c, packageName)) return className;
        ComponentName files = filesApp(c);
        return files == null ? className : files.getClassName();
    }

    /** The Downloads shortcut's start: My Files' Downloads, else Android's. */
    public static void start(Context c, Intent intent) {
        if (!installed(c, MY_FILES)) {
            intent = new Intent(DownloadManager.ACTION_VIEW_DOWNLOADS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        }
        c.startActivity(intent);
    }

    /**
     * The Screenshots shortcut: without Samsung Gallery the screenshots folder opens in the Files app (One UI keeps
     * screenshots in DCIM/Screenshots, Android in Pictures/Screenshots). True when handled here.
     */
    public static boolean screenshots(Context c) {
        if (installed(c, GALLERY)) return false;
        String[] folders = {Environment.DIRECTORY_PICTURES + "/Screenshots", Environment.DIRECTORY_DCIM + "/Screenshots"};
        for (String folder : folders) {
            if (!new File(Environment.getExternalStorageDirectory(), folder).isDirectory()) continue;
            Intent view = new Intent(Intent.ACTION_VIEW)
                    .setDataAndType(DocumentsContract.buildDocumentUri("com.android.externalstorage.documents",
                            "primary:" + folder), DocumentsContract.Document.MIME_TYPE_DIR)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            try {
                c.startActivity(view);
                return true;
            } catch (RuntimeException e) {
                return false;
            }
        }
        return false;
    }
}
