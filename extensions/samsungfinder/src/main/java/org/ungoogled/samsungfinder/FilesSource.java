package org.ungoogled.samsungfinder;

import android.content.ContentResolver;
import android.content.ContentUris;
import android.content.Intent;
import android.database.Cursor;
import android.graphics.drawable.Icon;
import android.net.Uri;
import android.os.Bundle;
import android.os.Environment;
import android.provider.MediaStore;
import android.provider.Settings;

import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/**
 * Files on the phone as Finder results. On One UI My Files answers Finder; here Android's media store is searched by
 * file name (every file with "All files access", which the first result asks for until it is allowed), and a tap opens
 * the file in the app the phone uses for its type.
 */
public final class FilesSource extends SearchSource {
    private static final String[] COLUMNS = {
        MediaStore.Files.FileColumns._ID, MediaStore.Files.FileColumns.DISPLAY_NAME, MediaStore.Files.FileColumns.DATA,
        MediaStore.Files.FileColumns.MIME_TYPE, MediaStore.Files.FileColumns.SIZE,
        MediaStore.Files.FileColumns.DATE_MODIFIED, MediaStore.Files.FileColumns.MEDIA_TYPE,
    };

    @Override
    String label() {
        Intent files = launchIntent();
        android.content.pm.ResolveInfo r = getContext().getPackageManager().resolveActivity(files, 0);
        return r != null ? r.loadLabel(getContext().getPackageManager()).toString() : "Files";
    }

    @Override
    Intent launchIntent() {
        return new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_APP_FILES);
    }

    /** Whether every file can be searched: "All files access" (Android 11+) or storage access (Android 10). */
    private boolean allFiles() {
        if (android.os.Build.VERSION.SDK_INT >= 30) return Environment.isExternalStorageManager();
        return getContext().checkSelfPermission(android.Manifest.permission.READ_EXTERNAL_STORAGE)
                == android.content.pm.PackageManager.PERMISSION_GRANTED;
    }

    @Override
    List<JSONObject> search(String query, int limit) throws JSONException {
        List<JSONObject> items = new ArrayList<>();
        if (!allFiles()) {
            Intent allow = android.os.Build.VERSION.SDK_INT >= 30
                    ? new Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                            Uri.parse("package:" + getContext().getPackageName()))
                    : new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                            Uri.parse("package:" + getContext().getPackageName()));
            items.add(new JSONObject()
                    .put("ObjectType", "Basic")
                    .put("title", "Allow access to all files")
                    .put("description", "Finder needs it to search your files")
                    .put("ObjectViewActions", viewAction(allow)));
            return items;
        }
        Uri files = MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL);
        Bundle args = new Bundle();
        String escaped = query.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
        args.putString(ContentResolver.QUERY_ARG_SQL_SELECTION, MediaStore.Files.FileColumns.DISPLAY_NAME
                + " LIKE ? ESCAPE '\\' AND " + MediaStore.Files.FileColumns.MIME_TYPE + " IS NOT NULL");
        args.putStringArray(ContentResolver.QUERY_ARG_SQL_SELECTION_ARGS, new String[] {"%" + escaped + "%"});
        args.putString(ContentResolver.QUERY_ARG_SQL_SORT_ORDER, MediaStore.Files.FileColumns.DATE_MODIFIED + " DESC");
        args.putInt(ContentResolver.QUERY_ARG_LIMIT, limit);
        try (Cursor c = getContext().getContentResolver().query(files, COLUMNS, args, null)) {
            if (c == null) return items;
            while (c.moveToNext()) {
                long id = c.getLong(0);
                String name = c.getString(1);
                String path = c.getString(2);
                String mime = c.getString(3);
                int mediaType = c.getInt(6);
                Uri item = ContentUris.withAppendedId(contentUri(mediaType), id);
                Intent view = new Intent(Intent.ACTION_VIEW).setDataAndType(item, mime)
                        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                JSONObject json = new JSONObject()
                        .put("ObjectType", "MyFiles")
                        .put("ObjectId", id)
                        .put("fileName", name)
                        .put("mimeType", mime)
                        .put("fileSize", c.getLong(4))
                        .put("date", String.valueOf(c.getLong(5) * 1000))
                        .put("ObjectViewActions", viewAction(view));
                if (path != null) json.put("filePath", path);
                String icon = typeIcon(mime);
                if (icon != null) json.put("icon", icon);
                items.add(json);
            }
        }
        return items;
    }

    /** The media store's own collection for a file (so a viewer granted the URI can open it). */
    private static Uri contentUri(int mediaType) {
        switch (mediaType) {
            case MediaStore.Files.FileColumns.MEDIA_TYPE_IMAGE:
                return MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL);
            case MediaStore.Files.FileColumns.MEDIA_TYPE_VIDEO:
                return MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL);
            case MediaStore.Files.FileColumns.MEDIA_TYPE_AUDIO:
                return MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL);
            default:
                return MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL);
        }
    }

    /** Android's own icon for a file type (the one its file pickers show), as a resource URI. */
    private String typeIcon(String mime) {
        try {
            Icon icon = getContext().getContentResolver().getTypeInfo(mime).getIcon();
            if (icon.getType() != Icon.TYPE_RESOURCE) return null;
            return "android.resource://" + icon.getResPackage() + "/" + icon.getResId();
        } catch (RuntimeException e) {
            return null;
        }
    }
}
