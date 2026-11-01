package org.ungoogled.honorfiles;

import android.os.storage.StorageManager;
import android.os.storage.StorageVolume;

import java.io.File;

/**
 * Files' storage helper (its "StorageInvoke") reaches the phone's volumes through Android's hidden StorageManager and
 * StorageVolume methods, which MagicOS lets a system app call; Android refuses them to an app ("Accessing hidden method
 * StorageVolume.getPath ... denied"), so every volume had an empty path and the phone storage listed nothing. The
 * same answers from Android's public API.
 */
public final class Storage {
    private Storage() {
    }

    /** StorageVolume.getPath(). */
    public static String path(StorageVolume volume) {
        if (volume == null) return "";
        File dir = volume.getDirectory();
        return dir == null ? "" : dir.getPath();
    }

    /** StorageManager.getVolumeList(). */
    public static StorageVolume[] volumes(StorageManager manager) {
        return manager == null ? new StorageVolume[0] : manager.getStorageVolumes().toArray(new StorageVolume[0]);
    }

    /** The path of the first volume of a user (only this app's user can be reached): the primary volume's. */
    public static String userPath(StorageManager manager, int userId) {
        return manager == null ? null : path(manager.getPrimaryStorageVolume());
    }

    /** StorageManager.getVolumeState(String path). */
    public static String state(StorageManager manager, String path) {
        if (manager == null || path == null) return null;
        StorageVolume volume = manager.getStorageVolume(new File(path));
        return volume == null ? null : volume.getState();
    }
}
