package com.hihonor.android.os.storage;

/** Generated stand-in (.github/scripts/honor-standins): names and signatures Honor Calendar uses, answering "absent" off MagicOS (no Honor code). */
public class StorageVolumeEx {
    public static java.lang.String getPath(android.os.storage.StorageVolume p0) {
        java.io.File dir = p0.getDirectory(); return dir == null ? "" : dir.getPath();
    }
}
