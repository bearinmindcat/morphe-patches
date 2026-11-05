package com.oplus.wrapper.util;

/** Generated stand-in (.github/scripts/coloros-launcher): names and signatures OnePlus System Launcher uses, answering "absent" off ColorOS and on Android versions without them (no OPPO or Google code). */
public class PathParser {
    public static android.graphics.Path createPathFromPathData(java.lang.String p0) {
        try { return (android.graphics.Path) Class.forName("androidx.core.graphics.PathParser").getMethod("createPathFromPathData", String.class).invoke(null, p0); } catch (ReflectiveOperationException | RuntimeException e) { return new android.graphics.Path(); }
    }
}
