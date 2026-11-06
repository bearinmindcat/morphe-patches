package org.ungoogled.honorscreenrecorder;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Rect;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.media.MediaMetadataRetriever;
import android.media.projection.MediaProjection;
import android.os.Binder;
import android.os.Environment;
import android.os.IBinder;
import android.util.Log;
import android.view.Surface;
import java.io.File;
import java.util.HashMap;
import java.util.Map;

/**
 * The recorder's screen capture (patches/.../honorscreenrecorder sends its SurfaceControlEx calls here). On an Honor
 * phone it mirrors the screen into the video encoder through a SurfaceControl display, which only a system app holding
 * CAPTURE_VIDEO_OUTPUT may create. Here the same display is a MediaProjection virtual display of the encoder's size
 * (the user's consent, see Projection), mirroring the screen as Android's own screen recorder does: turned sideways
 * mid-recording the screen is fitted into the frame instead of rotated.
 *
 * Its screenshot (SurfaceControlEx.screenshot, a system app's too) is the picture the saved recording's thumbnail
 * shrinks from when a recording ends: here the last frame of that recording, which shows the same screen.
 */
public final class Capture {
    private static final class Display {
        final String name;
        Surface surface;
        Rect frame;
        VirtualDisplay virtual;

        Display(String name) {
            this.name = name;
        }
    }

    private static final Map<IBinder, Display> DISPLAYS = new HashMap<>();

    private Capture() {
    }

    public static synchronized IBinder createDisplay(String name, boolean secure) {
        IBinder token = new Binder();
        DISPLAYS.put(token, new Display(name == null ? "ScreenCapture" : name));
        return token;
    }

    public static void openTransaction() {
    }

    /** The display is made once it has its surface and its size, which the recorder sets in one transaction. */
    public static synchronized void closeTransaction() {
        for (Display display : DISPLAYS.values()) {
            if (display.virtual == null && display.surface != null && display.frame != null) create(display);
        }
    }

    public static synchronized void setDisplaySurface(IBinder token, Surface surface) {
        Display display = DISPLAYS.get(token);
        if (display == null) return;
        display.surface = surface;
        if (display.virtual != null) display.virtual.setSurface(surface);
    }

    /** The recorder's frame (the encoder's size); later calls (rotation) change nothing: the mirror fits itself. */
    public static synchronized void setDisplayProjection(IBinder token, int orientation, Rect layerStack, Rect frame) {
        Display display = DISPLAYS.get(token);
        if (display == null || display.frame != null || frame == null || frame.isEmpty()) return;
        display.frame = new Rect(frame);
    }

    public static void setDisplayLayerStack(IBinder token, int layerStack) {
    }

    public static void destroyDisplay(IBinder token) {
        Display display;
        synchronized (Capture.class) {
            display = DISPLAYS.remove(token);
        }
        if (display != null && display.virtual != null) {
            try {
                display.virtual.release();
            } catch (RuntimeException e) {
                Log.w(Projection.TAG, "virtual display not released", e);
            }
        }
    }

    private static void create(Display display) {
        MediaProjection projection = Projection.projection();
        Context context = Projection.context();
        if (projection == null || context == null) {
            Log.w(Projection.TAG, "no screen capture consent: nothing recorded");
            return;
        }
        int dpi = context.getResources().getDisplayMetrics().densityDpi;
        try {
            display.virtual = projection.createVirtualDisplay(display.name, display.frame.width(),
                    display.frame.height(), dpi, DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, display.surface,
                    null, null);
        } catch (RuntimeException e) {
            Log.w(Projection.TAG, "virtual display not created", e);
        }
    }

    /** The projection ended (Android stopped it, or the recording ended): its virtual display went with it. */
    static synchronized void projectionStopped() {
        for (Display display : DISPLAYS.values()) {
            if (display.virtual == null) continue;
            try {
                display.virtual.release();
            } catch (RuntimeException e) {
                Log.w(Projection.TAG, "virtual display not released", e);
            }
            display.virtual = null;
        }
    }

    public static Bitmap screenshot(Rect crop, int width, int height, int rotation) {
        return lastFrame(width, height);
    }

    public static Bitmap screenshot(Rect crop, int width, int height, int minLayer, int maxLayer, boolean identity, int rotation) {
        return lastFrame(width, height);
    }

    private static String framePath;
    private static long frameModified;
    private static Bitmap frame;

    /** The last frame of the recording saved in the last minute (the recorder's folder), at the size asked for. */
    private static synchronized Bitmap lastFrame(int width, int height) {
        String root = Storage.path(true);
        File[] videos = root == null ? null
                : new File(root, Environment.DIRECTORY_PICTURES + "/Screenshots").listFiles((dir, name) -> name.startsWith("SVID_") && name.endsWith(".mp4"));
        File newest = null;
        if (videos != null) {
            for (File video : videos) if (newest == null || video.lastModified() > newest.lastModified()) newest = video;
        }
        if (newest == null || System.currentTimeMillis() - newest.lastModified() > 60_000L) return null;
        if (frame == null || !newest.getPath().equals(framePath) || newest.lastModified() != frameModified) {
            frame = null;
            MediaMetadataRetriever retriever = new MediaMetadataRetriever();
            try {
                retriever.setDataSource(newest.getPath());
                String duration = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION);
                long end = duration == null ? 0 : Math.max(0, Long.parseLong(duration) - 100) * 1000;
                frame = retriever.getFrameAtTime(end, MediaMetadataRetriever.OPTION_CLOSEST_SYNC);
                framePath = newest.getPath();
                frameModified = newest.lastModified();
            } catch (RuntimeException e) {
                Log.w(Projection.TAG, "no frame of the saved recording", e);
            } finally {
                try {
                    retriever.release();
                } catch (Exception e) {
                    // released
                }
            }
        }
        if (frame == null) return null;
        Bitmap copy = width > 0 && height > 0 ? Bitmap.createScaledBitmap(frame, width, height, true)
                : frame.copy(Bitmap.Config.ARGB_8888, false);
        return copy == frame ? frame.copy(Bitmap.Config.ARGB_8888, false) : copy;
    }
}
