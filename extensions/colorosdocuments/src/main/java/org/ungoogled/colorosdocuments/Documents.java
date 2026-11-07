package org.ungoogled.colorosdocuments;

import android.app.Application;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Environment;
import android.provider.DocumentsContract;
import android.util.Log;

import java.io.File;

/**
 * OnePlus Documents off ColorOS: the parts of ColorOS's own apps it leans on (My Files' folder picker and folder view,
 * the Private Safe) answered with what this phone has.
 */
public final class Documents {
    private static final String TAG = "OnePlusDocuments";

    private Documents() {
    }

    /**
     * SDK.FileManager.isAvailable: "save to" and "export to" go to My Files' folder picker only where that picker
     * exists (ColorOS, or a phone with OnePlus My Files). Elsewhere Documents shows its own save panel (its fallback for
     * tablets) instead of a "My Files has been uninstalled" dialog.
     */
    public static boolean fileManagerPicker(Context context) {
        try {
            Intent intent = (Intent) Class.forName("com.yozo.oplus.SDK$FileManager")
                    .getMethod("requireFileExportIntent", boolean.class, String.class, Context.class)
                    .invoke(null, false, null, context);
            return resolves(context, intent);
        } catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
            return false;
        }
    }

    /**
     * SDK.FileManager.requireSpecifiedPathIntent ("Open folder" after saving, converting or extracting): My Files' folder
     * view where it exists, else Android's Files app at that folder.
     */
    public static Intent folderIntent(Intent intent) {
        Context context = app();
        if (context == null || intent == null || resolves(context, intent)) return intent;
        String dir = intent.getStringExtra("CurrentDir");
        Intent files = filesAppIntent(dir);
        if (files == null || !resolves(context, files)) return intent;
        Log.i(TAG, "folder opens in Android's Files app: " + dir);
        return files;
    }

    private static Intent filesAppIntent(String dir) {
        if (dir == null) return null;
        String root = Environment.getExternalStorageDirectory().getAbsolutePath();
        String path = new File(dir).getAbsolutePath();
        if (!path.equals(root) && !path.startsWith(root + "/")) return null;
        String relative = path.length() > root.length() ? path.substring(root.length() + 1) : "";
        Uri uri = DocumentsContract.buildDocumentUri("com.android.externalstorage.documents", "primary:" + relative);
        Intent files = new Intent(Intent.ACTION_VIEW);
        files.setDataAndType(uri, DocumentsContract.Document.MIME_TYPE_DIR);
        files.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        return files;
    }

    /** The Private Safe is ColorOS's encryption app; its row and "move to Private Safe" only where it is installed. */
    public static boolean hasPrivateSafe() {
        Context context = app();
        return context != null && (installed(context, "com.coloros.encryption") || installed(context, "com.oplus.encryption"));
    }

    /** A list's add() of the "Private Safe" row: the row only where that app is installed. */
    public static boolean addPrivateSafeRow(java.util.ArrayList<Object> list, Object row) {
        return hasPrivateSafe() && list.add(row);
    }

    private static boolean resolves(Context context, Intent intent) {
        try {
            // Not Intent.resolveActivity: for an explicit component it answers that component without looking.
            return intent != null && context.getPackageManager().resolveActivity(intent, 0) != null;
        } catch (RuntimeException e) {
            return false;
        }
    }

    private static boolean installed(Context context, String pkg) {
        try {
            context.getPackageManager().getPackageInfo(pkg, 0);
            return true;
        } catch (PackageManager.NameNotFoundException e) {
            return false;
        } catch (RuntimeException e) {
            return false;
        }
    }

    private static Context app() {
        try {
            return (Application) Class.forName("android.app.ActivityThread").getMethod("currentApplication").invoke(null);
        } catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
            return null;
        }
    }
}
