package org.ungoogled.coloroslauncher;

import android.graphics.RenderEffect;
import android.graphics.Shader;

/**
 * RenderEffect.createBlurEffect with a radius of 0. The launcher asks for one where a blur is just starting (the
 * long-pressed icon's resize frame, scrims fading in). Android 14 and newer give an effect that changes nothing; Android
 * 12 and 13 throw IllegalArgumentException ("nativePtr is null") from inside drawing: the icon menu and the dragged
 * icon were not drawn at all (the screen stayed blurred with nothing on it) or the launcher crashed. Here a blur of no
 * radius is the effect that changes nothing on every version.
 */
public final class Blurs {
    private Blurs() {
    }

    public static RenderEffect createBlurEffect(float radiusX, float radiusY, Shader.TileMode mode) {
        if (radiusX <= 0f && radiusY <= 0f) return RenderEffect.createOffsetEffect(0f, 0f);
        return RenderEffect.createBlurEffect(Math.max(0f, radiusX), Math.max(0f, radiusY), mode);
    }

    public static RenderEffect createBlurEffect(float radiusX, float radiusY, RenderEffect input, Shader.TileMode mode) {
        if (radiusX <= 0f && radiusY <= 0f) {
            return input != null ? input : RenderEffect.createOffsetEffect(0f, 0f);
        }
        return RenderEffect.createBlurEffect(Math.max(0f, radiusX), Math.max(0f, radiusY), input, mode);
    }
}
