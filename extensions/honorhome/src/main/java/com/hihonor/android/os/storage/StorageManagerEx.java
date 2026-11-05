package com.hihonor.android.os.storage;

/** Generated stand-in (.github/scripts/honor-standins): names and signatures Honor Home uses, answering "absent" off MagicOS (no Honor code). */
public class StorageManagerEx {
    public static long getStorageLowBytes(android.os.storage.StorageManager p0, java.io.File p1) {
        return 0L;
    }
    public static android.os.storage.StorageVolume[] getVolumeList(int p0, int p1) {
        return new android.os.storage.StorageVolume[0];
    }
    public static android.os.storage.StorageVolume[] getVolumeList(android.os.storage.StorageManager p0) {
        return p0.getStorageVolumes().toArray(new android.os.storage.StorageVolume[0]);
    }
}
