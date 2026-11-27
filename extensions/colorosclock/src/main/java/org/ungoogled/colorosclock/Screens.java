package org.ungoogled.colorosclock;

import android.os.Build;

/** Clock's screens on phones without ColorOS 15. */
public final class Screens {
    private Screens() {
    }

    /**
     * Whether Clock takes the system bars into account (its ColorOS 15 layout: the bottom tabs and every screen are
     * kept clear of the navigation bar). Clock decides by the ColorOS version, which other phones don't have; Android
     * 15 and later draw every app edge to edge, so there it is needed on any phone (the tabs sat under the 3-button
     * navigation bar).
     */
    public static boolean edgeToEdge(boolean colorOs15) {
        return colorOs15 || Build.VERSION.SDK_INT >= 35;
    }
}
