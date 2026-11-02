package org.ungoogled.colorosweather;

/**
 * Whether OpenGL ES goes through the Android emulator's GLES encoder (ro.hardware.egl = "emulation"). Its
 * glGetProgramResourceiv writes past the caller's buffer, which kills Weather's animated-background engine (OPPO's COE
 * VFX, libnaiveEngine) as soon as it loads an effect. A phone's GPU driver is never "emulation", so there the
 * animated background runs as before.
 */
public final class EmulatorGl {
    private static Boolean active;

    private EmulatorGl() {
    }

    public static boolean active() {
        if (active == null) active = "emulation".equals(ColorOS.prop("ro.hardware.egl", ""));
        return active;
    }
}
