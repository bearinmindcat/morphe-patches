package org.ungoogled.ui;

import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.drawable.Drawable;

/** A list's emoji (issue #128) where Local saved draws a list's icon: 24 dp, as the icons beside it. */
final class EmojiIcon extends Drawable {
    private static final int SIZE = Math.round(24 * android.content.res.Resources.getSystem().getDisplayMetrics().density);
    private final String emoji;
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);

    EmojiIcon(String emoji) {
        this.emoji = emoji;
        paint.setTextAlign(Paint.Align.CENTER);
    }

    @Override public void draw(Canvas c) {
        Rect b = getBounds();
        paint.setTextSize(Math.min(b.width(), b.height()) * 0.8f);
        Paint.FontMetrics m = paint.getFontMetrics();
        c.drawText(emoji, b.exactCenterX(), b.exactCenterY() - (m.ascent + m.descent) / 2, paint);
    }

    @Override public int getIntrinsicWidth() { return SIZE; }
    @Override public int getIntrinsicHeight() { return SIZE; }
    @Override public void setAlpha(int alpha) { paint.setAlpha(alpha); invalidateSelf(); }
    @Override public void setColorFilter(ColorFilter f) { paint.setColorFilter(f); invalidateSelf(); }
    @Override public int getOpacity() { return PixelFormat.TRANSLUCENT; }
}
