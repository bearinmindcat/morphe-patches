package com.samsung.android.transcode.util;

import android.content.Context;
import android.os.storage.StorageManager;
import android.os.storage.StorageVolume;

import java.io.File;

/** One UI's storage helper: the removable card's path, from Android's own storage volumes. */
public class FileHelper {
    public static String getExternalSdCardStoragePath(Context context) {
        try {
            StorageManager storage = context.getSystemService(StorageManager.class);
            for (StorageVolume volume : storage.getStorageVolumes()) {
                File directory = volume.getDirectory();
                if (volume.isRemovable() && directory != null) return directory.getPath();
            }
        } catch (RuntimeException e) {
            // No card.
        }
        return "";
    }

    public static boolean isSdcardPath(Context context, String path) {
        if (path == null || context == null) return false;
        String card = getExternalSdCardStoragePath(context);
        return !card.isEmpty() && path.startsWith(card);
    }
}
