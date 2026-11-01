package org.ungoogled.samsungweather;

import android.os.Build;

/**
 * The forecast screen's animated sky and weather effects are AGSL shaders (RuntimeShader, Android 13). Below that a
 * shader view gets no layer, which it already draws as nothing, instead of dying on the missing class.
 */
public final class Shaders {
    private Shaders() {
    }

    public static Object layer(Object layer) {
        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU ? layer : null;
    }
}
