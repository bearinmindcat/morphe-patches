package org.ungoogled.colorosrecorder;

/**
 * Recorder records MP3 (its default), WAV and AMR with OPPO's own recorder engine (com.oplus.media.OplusRecorder),
 * whose native half is a ColorOS system library (liboplusrecorder.so): elsewhere recording died at once
 * (UnsatisfiedLinkError). Where that library is missing every recording is made in AAC, the one format Recorder records
 * with Android's MediaRecorder, and Settings no longer offers a format choice that couldn't apply.
 */
public final class Formats {
    /** MediaRecorder.OutputFormat.AAC_ADTS, Recorder's AAC. */
    private static final int AAC = 6;
    private static Boolean engine;

    private Formats() {
    }

    private static synchronized boolean engine() {
        if (engine == null) {
            boolean loaded;
            try {
                System.loadLibrary("oplusrecorder");
                loaded = true;
            } catch (Throwable t) {
                loaded = false;
            }
            engine = loaded;
        }
        return engine;
    }

    /** Settings' "Recording file format" row is shown only where the other formats can be recorded. */
    public static void formatRowVisible(Object preference, boolean visible) {
        try {
            boolean shown = visible && engine();
            preference.getClass().getMethod("setVisible", boolean.class).invoke(preference, shown);
            if (shown) return;
            // Its category ("Audio") also holds cloud sync and record modes; with none of them shown it would be a bare title.
            Object category = preference.getClass().getMethod("getParent").invoke(preference);
            if (category == null) return;
            int count = (Integer) category.getClass().getMethod("getPreferenceCount").invoke(category);
            for (int i = 0; i < count; i++) {
                Object child = category.getClass().getMethod("getPreference", int.class).invoke(category, i);
                if ((Boolean) child.getClass().getMethod("isVisible").invoke(child)) return;
            }
            category.getClass().getMethod("setVisible", boolean.class).invoke(category, false);
        } catch (ReflectiveOperationException | RuntimeException ignored) {
        }
    }

    /** The format a new recording's settings (RecordConfig) get. */
    public static int recordFormat(int format) {
        return format == AAC || engine() ? format : AAC;
    }
}
