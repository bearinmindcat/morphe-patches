package org.ungoogled.motolauncher;

import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Rect;
import android.util.SparseArray;

import java.util.Map;
import java.util.WeakHashMap;

/**
 * Canvas.saveUnclippedLayer / restoreUnclippedLayer (hidden), which the drawer's lists use for their faded top and bottom
 * edges: a layer over the whole canvas (Android's unclipped layer does not clip either), and on restore the fade paint
 * drawn over the region the layer was asked for. A clipped saveLayer in their place left only the edges drawn.
 */
final class Layers {
    private static final Map<Canvas, SparseArray<Rect>> REGIONS = new WeakHashMap<>();

    private Layers() {
    }

    static synchronized int save(Canvas canvas, int left, int top, int right, int bottom) {
        int count = canvas.saveLayer(null, null);
        SparseArray<Rect> regions = REGIONS.get(canvas);
        if (regions == null) {
            regions = new SparseArray<>();
            REGIONS.put(canvas, regions);
        }
        regions.put(count, new Rect(left, top, right, bottom));
        return count;
    }

    static synchronized void restore(Canvas canvas, int count, Paint paint) {
        SparseArray<Rect> regions = REGIONS.get(canvas);
        Rect region = regions == null ? null : regions.get(count);
        if (regions != null) regions.remove(count);
        if (region != null && paint != null && !region.isEmpty() && canvas.getSaveCount() > count) {
            canvas.drawRect(region, paint);
        }
        if (canvas.getSaveCount() > count) canvas.restoreToCount(count);
    }
}
