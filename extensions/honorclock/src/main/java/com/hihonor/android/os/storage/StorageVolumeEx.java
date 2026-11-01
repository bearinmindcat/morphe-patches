package com.hihonor.android.os.storage;

/** Generated stand-in (.github/scripts/honor-standins): names and signatures Honor Clock uses, answering "absent" off MagicOS (no Honor code). */
public class StorageVolumeEx {
    public static java.lang.String getPath(android.os.storage.StorageVolume p0) {
        return p0.getDirectory() == null ? "" : p0.getDirectory().getPath();
    }
}
