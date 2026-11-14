package org.ungoogled.gmail;

import android.graphics.Canvas;
import android.graphics.Paint;

/** Square sender pictures, as in Gmail before 2014. */
public final class Avatars {
    private Avatars() {}

    /** In place of Canvas.drawCircle in Gmail's sender picture drawable (picture, letter tile and its outline). */
    public static void drawCircle(Canvas canvas, float cx, float cy, float radius, Paint paint) {
        float corner = radius * 0.12f;
        canvas.drawRoundRect(cx - radius, cy - radius, cx + radius, cy + radius, corner, corner, paint);
    }
}
