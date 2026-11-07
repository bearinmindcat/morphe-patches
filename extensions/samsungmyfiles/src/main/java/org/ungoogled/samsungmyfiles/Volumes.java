package org.ungoogled.samsungmyfiles;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.net.Uri;
import android.os.Environment;
import android.os.Process;
import android.os.storage.StorageManager;
import android.os.storage.StorageVolume;
import android.util.Log;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * SD cards and USB drives. My Files lists them from Android's hidden StorageManager.getVolumes() and reads each
 * VolumeInfo by reflection, down to its fsType field -- which Android refuses to apps (max-target-o), so the whole
 * list came back empty and no SD card or USB drive ever showed. The patch makes that list come from here: one
 * {@link Volume} per removable volume Android's public getStorageVolumes() reports, with the members My Files reads.
 *
 * One UI also tells My Files about cards and drives coming and going with VOLUME_STATE_CHANGED, a broadcast only
 * the system's own apps receive; {@link #registerReceiver} feeds the receivers that want it from Android's public
 * MEDIA_... broadcasts instead.
 */
public final class Volumes {
    private static final String TAG = "UA";
    private static final String VOLUME_STATE_CHANGED = "android.os.storage.action.VOLUME_STATE_CHANGED";
    private static final String EXTRA_VOLUME_ID = "android.os.storage.extra.VOLUME_ID";
    private static final String EXTRA_VOLUME_STATE = "android.os.storage.extra.VOLUME_STATE";
    private static final String EXTRA_FS_UUID = "android.os.storage.extra.FS_UUID";

    // VolumeInfo's states.
    private static final int UNMOUNTED = 0;
    private static final int CHECKING = 1;
    private static final int MOUNTED = 2;
    private static final int MOUNTED_READ_ONLY = 3;
    private static final int FORMATTING = 4;
    private static final int EJECTING = 5;
    private static final int UNMOUNTABLE = 6;
    private static final int REMOVED = 7;
    private static final int BAD_REMOVAL = 8;

    /** Volume ids by file system UUID, so a volume keeps the id it was first listed with. */
    private static final Map<String, String> IDS = new ConcurrentHashMap<>();
    private static volatile StorageManager storage;
    /** Whether the last listing had an SD card (mounted or not); null before the first. */
    private static volatile Boolean sdCard;
    private static final Map<BroadcastReceiver, Object[]> TRANSLATORS = new HashMap<>();

    private Volumes() {
    }

    /** What Android's hidden VolumeInfo answers My Files: it reads exactly these members, by name. */
    public static final class Volume {
        private final String id;
        private final int state;
        private final File path;
        private final String fsUuid;
        private final Disk disk;
        @SuppressWarnings({"unused", "FieldCanBeLocal"})
        private final String fsType;

        Volume(String id, int state, File path, String fsUuid, String fsType, Disk disk) {
            this.id = id;
            this.state = state;
            this.path = path;
            this.fsUuid = fsUuid;
            this.fsType = fsType;
            this.disk = disk;
        }

        public String getId() {
            return id;
        }

        public int getState() {
            return state;
        }

        public static String getEnvironmentForState(int state) {
            switch (state) {
                case UNMOUNTED:
                case FORMATTING:
                    return Environment.MEDIA_UNMOUNTED;
                case CHECKING:
                    return Environment.MEDIA_CHECKING;
                case MOUNTED:
                    return Environment.MEDIA_MOUNTED;
                case MOUNTED_READ_ONLY:
                    return Environment.MEDIA_MOUNTED_READ_ONLY;
                case EJECTING:
                    return Environment.MEDIA_EJECTING;
                case UNMOUNTABLE:
                    return Environment.MEDIA_UNMOUNTABLE;
                case REMOVED:
                    return Environment.MEDIA_REMOVED;
                case BAD_REMOVAL:
                    return Environment.MEDIA_BAD_REMOVAL;
            }
            return Environment.MEDIA_UNKNOWN;
        }

        public File getPath() {
            return path;
        }

        public String getFsUuid() {
            return fsUuid;
        }

        public Disk getDisk() {
            return disk;
        }

        public int getMountUserId() {
            return Process.myUserHandle().hashCode();
        }

        @Override
        public String toString() {
            return "Volume{" + id + " " + getEnvironmentForState(state) + " " + path + " " + fsType + " sd=" + disk.sd
                    + " usb=" + disk.usb + "}";
        }
    }

    /** What Android's hidden DiskInfo answers: an SD card or a USB drive. */
    public static final class Disk {
        private final boolean sd;
        private final boolean usb;

        Disk(boolean sd, boolean usb) {
            this.sd = sd;
            this.usb = usb;
        }

        public boolean isSd() {
            return sd;
        }

        public boolean isUsb() {
            return usb;
        }
    }

    private static int state(String environment) {
        if (environment == null) return UNMOUNTED;
        switch (environment) {
            case Environment.MEDIA_CHECKING:
                return CHECKING;
            case Environment.MEDIA_MOUNTED:
                return MOUNTED;
            case Environment.MEDIA_MOUNTED_READ_ONLY:
                return MOUNTED_READ_ONLY;
            case Environment.MEDIA_EJECTING:
                return EJECTING;
            case Environment.MEDIA_UNMOUNTABLE:
                return UNMOUNTABLE;
            case Environment.MEDIA_REMOVED:
                return REMOVED;
            case Environment.MEDIA_BAD_REMOVAL:
                return BAD_REMOVAL;
        }
        return UNMOUNTED;
    }

    /** From /proc/self/mounts: UUID to {vold volume id, file system type} for every mounted removable volume. */
    private static Map<String, String[]> mounts() {
        Map<String, String[]> out = new HashMap<>();
        try (BufferedReader reader = new BufferedReader(new FileReader("/proc/self/mounts"))) {
            String line;
            while ((line = reader.readLine()) != null) {
                String[] f = line.split(" ");
                if (f.length < 3 || !f[1].startsWith("/mnt/media_rw/")) continue;
                String id = f[0].startsWith("/dev/block/vold/") ? f[0].substring("/dev/block/vold/".length()) : null;
                out.put(f[1].substring("/mnt/media_rw/".length()), new String[] {id, f[2]});
            }
        } catch (IOException | RuntimeException e) {
            // Unreadable: ids and types fall back below.
        }
        return out;
    }

    /**
     * From Android's own (hidden, but still open to apps) VolumeInfo and DiskInfo getters: UUID to
     * {id, "sd" / "usb" / null}. Empty where a phone refuses them.
     */
    private static Map<String, String[]> disks(StorageManager storage) {
        Map<String, String[]> out = new HashMap<>();
        try {
            List<?> volumes = (List<?>) StorageManager.class.getMethod("getVolumes").invoke(storage);
            if (volumes == null) return out;
            for (Object volume : volumes) {
                Class<?> type = volume.getClass();
                String uuid = (String) type.getMethod("getFsUuid").invoke(volume);
                Object disk = type.getMethod("getDisk").invoke(volume);
                if (uuid == null || disk == null) continue;
                String id = (String) type.getMethod("getId").invoke(volume);
                Method isSd = disk.getClass().getMethod("isSd");
                Method isUsb = disk.getClass().getMethod("isUsb");
                String kind = Boolean.TRUE.equals(isSd.invoke(disk)) ? "sd"
                        : Boolean.TRUE.equals(isUsb.invoke(disk)) ? "usb" : null;
                out.put(uuid, new String[] {id, kind});
            }
        } catch (ReflectiveOperationException | LinkageError | RuntimeException e) {
            if (Log.isLoggable(TAG, Log.DEBUG)) Log.d(TAG, "volumes: no disk info, " + e);
        }
        return out;
    }

    /** A volume id like Android's, "public:<disk major>,<minor>"; 179 is the SD card (MMC) major. */
    private static String id(String uuid, String[] disk, String[] mount, boolean usb) {
        String id = IDS.get(uuid);
        if (id != null) return id;
        if (disk != null && disk[0] != null) id = disk[0];
        else if (mount != null && mount[0] != null) id = mount[0];
        if (id != null) {
            IDS.put(uuid, id);
            return id;
        }
        return usb ? "public:8,0" : "public:179,0";
    }

    /** USB mass storage (SCSI disk majors) when Android's DiskInfo cannot say. */
    private static boolean scsi(String id) {
        if (id == null || !id.startsWith("public:") || id.indexOf(',') < 0) return false;
        try {
            int major = Integer.parseInt(id.substring("public:".length(), id.indexOf(',')));
            return major == 8 || (major >= 65 && major <= 71) || (major >= 128 && major <= 135);
        } catch (NumberFormatException e) {
            return false;
        }
    }

    /** Replaces My Files' StorageManager.getVolumes(): SD cards and USB drives (the internal storage it knows). */
    public static List<Object> list(StorageManager storage) {
        List<Object> out = new ArrayList<>();
        if (storage == null) return out;
        Volumes.storage = storage;
        boolean anySd = false;
        Map<String, String[]> mounts = mounts();
        Map<String, String[]> disks = disks(storage);
        for (StorageVolume volume : storage.getStorageVolumes()) {
            String uuid = volume.getUuid();
            if (volume.isPrimary() || !volume.isRemovable() || uuid == null) continue;
            String[] disk = disks.get(uuid);
            String[] mount = mounts.get(uuid);
            String kind = disk != null ? disk[1] : null;
            if (kind == null) {
                String known = disk != null && disk[0] != null ? disk[0] : mount != null ? mount[0] : IDS.get(uuid);
                kind = scsi(known) ? "usb" : "sd";
            }
            boolean usb = "usb".equals(kind);
            String fsType = mount != null && mount[1] != null ? mount[1] : "vfat";
            // exFAT is "exfat" to My Files (it allows files over 4 GB there); some kernels name their driver otherwise.
            if (fsType.contains("exfat")) fsType = "exfat";
            Volume v = new Volume(id(uuid, disk, mount, usb), state(volume.getState()), volume.getDirectory(), uuid,
                    fsType, new Disk(!usb, usb));
            if (Log.isLoggable(TAG, Log.DEBUG)) Log.d(TAG, "volume: " + v);
            out.add(v);
            anySd |= !usb;
        }
        sdCard = anySd;
        return out;
    }

    private static boolean sdCard() {
        Boolean known = sdCard;
        if (known != null) return known;
        StorageManager s = storage;
        if (s == null) {
            try {
                Context app = (Context) Class.forName("android.app.ActivityThread").getMethod("currentApplication")
                        .invoke(null);
                s = app != null ? app.getSystemService(StorageManager.class) : null;
            } catch (ReflectiveOperationException | LinkageError | RuntimeException e) {
                s = null;
            }
        }
        if (s == null) return false;
        list(s);
        return Boolean.TRUE.equals(sdCard);
    }

    /**
     * One UI's "this phone has an SD card slot" (EnvManager.isSupportSdCard), which My Files answers from a Samsung
     * system property: with it the SD card row shows ("Not inserted" without a card), without it no SD card shows at
     * all. Off One UI there is no such property, so the slot counts as there while Android has an SD card, mounted
     * or not.
     */
    public static boolean sdCard(Context context) {
        if (storage == null && context != null) storage = context.getSystemService(StorageManager.class);
        return sdCard();
    }

    /**
     * The same, where My Files reads the answer it kept from start-up: a card put in later must show too. (A Knox
     * block cannot apply off One UI, and another profile is not shown the card by Android.)
     */
    public static boolean sdCard(boolean keptFromStartUp) {
        return sdCard();
    }

    /** One UI's VOLUME_STATE_CHANGED for an Android MEDIA_... broadcast, delivered straight to the app's receiver. */
    private static final class Translator extends BroadcastReceiver {
        private final BroadcastReceiver target;

        Translator(BroadcastReceiver target) {
            this.target = target;
        }

        @Override
        public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();
            if (action == null) return;
            int state;
            switch (action) {
                case Intent.ACTION_MEDIA_UNMOUNTED:
                    state = UNMOUNTED;
                    break;
                case Intent.ACTION_MEDIA_CHECKING:
                    state = CHECKING;
                    break;
                case Intent.ACTION_MEDIA_MOUNTED:
                    state = intent.getBooleanExtra("read-only", false) ? MOUNTED_READ_ONLY : MOUNTED;
                    break;
                case Intent.ACTION_MEDIA_EJECT:
                    state = EJECTING;
                    break;
                case Intent.ACTION_MEDIA_UNMOUNTABLE:
                    state = UNMOUNTABLE;
                    break;
                case Intent.ACTION_MEDIA_REMOVED:
                    state = REMOVED;
                    break;
                case Intent.ACTION_MEDIA_BAD_REMOVAL:
                    state = BAD_REMOVAL;
                    break;
                default:
                    return;
            }
            StorageVolume volume = intent.getParcelableExtra(StorageVolume.EXTRA_STORAGE_VOLUME, StorageVolume.class);
            if (volume != null && volume.isPrimary()) return;
            String uuid = volume != null ? volume.getUuid() : null;
            Uri data = intent.getData();
            if (uuid == null && data != null) uuid = data.getLastPathSegment();
            if (uuid == null) return;
            String id = IDS.get(uuid);
            if (id == null) {
                StorageManager storage = context.getSystemService(StorageManager.class);
                String[] disk = storage != null ? disks(storage).get(uuid) : null;
                String[] mount = mounts().get(uuid);
                String known = disk != null && disk[0] != null ? disk[0] : mount != null ? mount[0] : null;
                boolean usb = disk != null && disk[1] != null ? "usb".equals(disk[1]) : scsi(known);
                id = id(uuid, disk, mount, usb);
            }
            Intent out = new Intent(VOLUME_STATE_CHANGED);
            out.putExtra(EXTRA_VOLUME_ID, id);
            out.putExtra(EXTRA_VOLUME_STATE, state);
            out.putExtra(EXTRA_FS_UUID, uuid);
            if (Log.isLoggable(TAG, Log.DEBUG)) Log.d(TAG, "volume event: " + action + " -> " + id + " state " + state);
            target.onReceive(context, out);
        }
    }

    /** Replaces Context.registerReceiver(receiver, filter, flags) in My Files. */
    public static Intent registerReceiver(Context context, BroadcastReceiver receiver, IntentFilter filter, int flags) {
        Intent sticky = context.registerReceiver(receiver, filter, flags);
        if (receiver == null || filter == null || !filter.hasAction(VOLUME_STATE_CHANGED)) return sticky;
        synchronized (TRANSLATORS) {
            if (TRANSLATORS.containsKey(receiver)) return sticky;
            IntentFilter media = new IntentFilter();
            media.addAction(Intent.ACTION_MEDIA_UNMOUNTED);
            media.addAction(Intent.ACTION_MEDIA_CHECKING);
            media.addAction(Intent.ACTION_MEDIA_MOUNTED);
            media.addAction(Intent.ACTION_MEDIA_EJECT);
            media.addAction(Intent.ACTION_MEDIA_UNMOUNTABLE);
            media.addAction(Intent.ACTION_MEDIA_REMOVED);
            media.addAction(Intent.ACTION_MEDIA_BAD_REMOVAL);
            media.addDataScheme("file");
            Translator translator = new Translator(receiver);
            try {
                context.registerReceiver(translator, media, flags);
                TRANSLATORS.put(receiver, new Object[] {context, translator});
            } catch (RuntimeException e) {
                Log.w(TAG, "volumes: cannot follow cards and drives, " + e);
            }
        }
        return sticky;
    }

    /** Replaces Context.unregisterReceiver(receiver) in My Files: its translator goes too. */
    public static void unregisterReceiver(Context context, BroadcastReceiver receiver) {
        Object[] translator;
        synchronized (TRANSLATORS) {
            translator = TRANSLATORS.remove(receiver);
        }
        if (translator != null) {
            try {
                ((Context) translator[0]).unregisterReceiver((BroadcastReceiver) translator[1]);
            } catch (IllegalArgumentException e) {
                // Already gone with its Activity.
            }
        }
        context.unregisterReceiver(receiver);
    }
}
