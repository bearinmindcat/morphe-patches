package org.ungoogled.honorscreenrecorder;

import android.content.Context;
import android.os.Build;
import android.os.Environment;
import android.os.storage.StorageManager;
import android.os.storage.StorageVolume;
import java.io.File;

/**
 * Where recordings go (patches/.../honorscreenrecorder sends the recorder's storage path lookup here). The recorder
 * reads the phone's storage volumes through StorageManager.getVolumeList and StorageVolume.getPath, hidden methods
 * Android refuses to any app but a system one: the path came back null, "null/Pictures/Screenshots" could not be made
 * and no recording started. The same answer from Android's public storage volumes: the internal (emulated) one or the
 * removable one.
 */
public final class Storage {
    private Storage() {
    }

    private static Context application() {
        Context context = Projection.context();
        if (context != null) return context;
        try {
            return (Context) Class.forName("android.app.ActivityThread").getMethod("currentApplication").invoke(null);
        } catch (ReflectiveOperationException | RuntimeException e) {
            return null;
        }
    }

    /** The mounted volume that is (or is not) the internal, emulated one; null when there is none. */
    public static String path(boolean emulated) {
        Context context = application();
        StorageManager storage = context == null ? null : context.getSystemService(StorageManager.class);
        if (storage != null) {
            for (StorageVolume volume : storage.getStorageVolumes()) {
                if (volume.isEmulated() != emulated || !Environment.MEDIA_MOUNTED.equals(volume.getState())) continue;
                File directory = Build.VERSION.SDK_INT >= 30 ? volume.getDirectory() : null;
                if (directory != null) return directory.getPath();
                if (emulated && volume.isPrimary()) return Environment.getExternalStorageDirectory().getPath();
            }
        }
        return emulated ? Environment.getExternalStorageDirectory().getPath() : null;
    }
}
