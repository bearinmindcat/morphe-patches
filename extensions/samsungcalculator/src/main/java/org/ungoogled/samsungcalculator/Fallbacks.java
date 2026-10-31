package org.ungoogled.samsungcalculator;

import android.os.VibrationEffect;

/**
 * One UI members Calculator needs a real answer for off One UI (its Kotlin code null-checks them). Everything else a
 * phone lacks answers "absent" (see OneUi).
 */
final class Fallbacks {
    static final Object NONE = new Object();

    private Fallbacks() {
    }

    static Object call(Object receiver, String key, Object[] args) {
        switch (key) {
            case "Landroid/graphics/Typeface;->semGetFontPathOfCurrentFontStyle(Landroid/content/Context;I)Ljava/lang/String;":
                return "default";
            case "Landroid/os/VibrationEffect;->semCreateWaveform(IILandroid/os/VibrationEffect$SemMagnitudeType;)Landroid/os/VibrationEffect;":
                return VibrationEffect.createPredefined(VibrationEffect.EFFECT_TICK);
            case "Landroid/media/AudioManager;->semGetSituationVolume(II)F":
                return 1f;
        }
        return NONE;
    }
}
