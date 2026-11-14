package org.ungoogled.samsungvideoplayer;

import android.content.ClipData;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Environment;
import android.os.storage.StorageManager;
import android.os.storage.StorageVolume;
import android.provider.DocumentsContract;
import android.util.Log;

import java.io.File;
import java.io.FileInputStream;

/**
 * A video opened from a file manager through Android's Files app (a storage document URI) never played: the player
 * stayed on its loading circle. Two things went wrong off One UI:
 * <ul>
 * <li>Video Player turns such a URI into a path by text ("/document/primary:Movies/x.mp4" -> "/storage/primary/..."),
 * a folder no phone has; off One UI it then plays from that path (One UI plays from the URI).</li>
 * <li>Asking for the video permission first closed the player and reopened it from the permission screen, which held
 * no access to the URI: the access the file manager gave ended with the first player screen.</li>
 * </ul>
 * The path now comes from the document id, the player falls back to the URI when the path cannot be read, and the
 * permission screen carries the file manager's access along.
 */
public final class OpenedFiles {
    private static final String TAG = "UA";
    private static final String EXTERNAL_STORAGE_DOCUMENTS = "com.android.externalstorage.documents";
    private static final int GRANTS = Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION | Intent.FLAG_GRANT_PREFIX_URI_PERMISSION;

    private OpenedFiles() {
    }

    /** The file behind a storage document URI ("primary:Movies/x.mp4" -> /storage/emulated/0/Movies/x.mp4). */
    public static String path(Uri uri) {
        String old = oldPath(uri);
        try {
            if (uri == null || !EXTERNAL_STORAGE_DOCUMENTS.equals(uri.getAuthority())) return old;
            String id = DocumentsContract.isTreeUri(uri) && !hasDocument(uri)
                    ? DocumentsContract.getTreeDocumentId(uri) : DocumentsContract.getDocumentId(uri);
            int colon = id == null ? -1 : id.indexOf(':');
            if (colon < 0) return old;
            File root = volumeRoot(id.substring(0, colon));
            if (root == null) return old;
            String path = new File(root, id.substring(colon + 1)).getPath();
            Log.i(TAG, "video: document " + id.substring(0, colon) + " -> " + root);
            return path;
        } catch (RuntimeException e) {
            Log.w(TAG, "video: no path for " + uri + ": " + e);
            return old;
        }
    }

    /** Video Player's own conversion, kept for what is not a storage document. */
    private static String oldPath(Uri uri) {
        String p = uri == null ? null : uri.getPath();
        return p == null ? null : p.replaceFirst("document", "storage").replaceFirst(":", "/");
    }

    private static boolean hasDocument(Uri uri) {
        java.util.List<String> segments = uri.getPathSegments();
        return segments.size() >= 4 && "document".equals(segments.get(2));
    }

    private static File volumeRoot(String volume) {
        if ("primary".equalsIgnoreCase(volume)) return Environment.getExternalStorageDirectory();
        if ("home".equalsIgnoreCase(volume)) {
            return Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS);
        }
        Context context = Services.context();
        StorageManager storage = context == null ? null : context.getSystemService(StorageManager.class);
        if (storage != null) {
            for (StorageVolume v : storage.getStorageVolumes()) {
                if (volume.equalsIgnoreCase(v.getUuid()) && v.getDirectory() != null) return v.getDirectory();
            }
        }
        return new File("/storage/" + volume);
    }

    /**
     * What the player opens: the file, or (as One UI does) the URI it was given when the file cannot be read -- a path
     * the player could not work out, or a video the "limited access" photo permission does not cover.
     */
    public static String source(String path, Uri uri) {
        if (uri == null || !"content".equals(uri.getScheme())) return path;
        if (path != null && path.startsWith("/") && canRead(path)) return path;
        if (path != null && !path.startsWith("/")) return path;
        Log.i(TAG, "video: playing from the URI, " + (path == null ? "no path" : "the file cannot be read"));
        return uri.toString();
    }

    private static boolean canRead(String path) {
        try (FileInputStream in = new FileInputStream(path)) {
            return true;
        } catch (Exception | Error e) {
            return false;
        }
    }

    /** The permission screen gets the access to the video the player was given, to hand it back when it reopens it. */
    public static void carryGrant(Intent permissionScreen, Intent player) {
        try {
            if (permissionScreen == null || player == null) return;
            Uri uri = player.getData();
            int grants = player.getFlags() & GRANTS;
            if (uri == null || !"content".equals(uri.getScheme()) || grants == 0) return;
            permissionScreen.setClipData(ClipData.newRawUri("", uri));
            permissionScreen.addFlags(grants);
        } catch (RuntimeException e) {
            Log.w(TAG, "video: access not carried: " + e);
        }
    }
}
