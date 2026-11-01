package org.ungoogled.patches.samsungweather.misc

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.patch.resourcePatch
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import org.ungoogled.patches.samsung.redirectCalls
import org.ungoogled.patches.samsung.redirectOneUiMembers
import org.ungoogled.patches.samsungweather.SamsungWeatherConstants.COMPATIBILITY_SAMSUNG_WEATHER
import org.w3c.dom.Element

internal const val SAMSUNG_WEATHER_EXTENSION = "extensions/samsungweather.mpe"

private const val POP_OVER_OPTIONS =
    "Lcom/samsung/android/weather/system/service/android/impl/AndroidActivityService\$setPopOverOptionsCompat\$1;"

/** The pop-over activity options of Weather's own non-Samsung ("Android") platform layer. */
private object PopOverOptionsToBundleFingerprint : Fingerprint(
    definingClass = POP_OVER_OPTIONS,
    name = "toBundle",
    returnType = "Landroid/os/Bundle;",
    parameters = listOf(),
)

/**
 * On One UI Weather opens from its widgets and the icon is a setting, off by default; elsewhere there would be no way
 * in, so the launcher entry starts enabled (Weather's own "Show icon" switch can still turn it off).
 */
private val launcherIconPatch = resourcePatch {
    execute {
        document("AndroidManifest.xml").use { manifest ->
            val activities = manifest.getElementsByTagName("activity")
            var found = false
            for (i in 0 until activities.length) {
                val activity = activities.item(i) as Element
                if (activity.getAttribute("android:name") != "com.samsung.android.weather.app.AppLauncherActivity") continue
                activity.setAttribute("android:enabled", "true")
                found = true
            }
            if (!found) throw PatchException("no AppLauncherActivity in the manifest")
        }
    }
}

/** Weather's "Show Weather on Apps screen" switch reads the launcher's default state (now enabled) as on. */
private val launcherStatePatch = bytecodePatch(
    description = "Shows the launcher icon's default state as on.",
) {
    extendWith(SAMSUNG_WEATHER_EXTENSION)

    execute {
        val moved = redirectCalls(
            "Lorg/ungoogled/samsungweather/Launcher;",
            mapOf("Landroid/content/pm/PackageManager;" to setOf("getComponentEnabledSetting(Landroid/content/ComponentName;)I")),
        )
        if (moved == 0) throw PatchException("no getComponentEnabledSetting calls found")
    }
}

/**
 * Weather already carries a platform layer for phones without One UI, but its ActivityOptionsCompat for pop-over
 * windows returns its own toBundle(): opening any screen through it (the location list first) died of a
 * StackOverflowError. It now hands back no options, which opens the screen normally.
 */
private val popOverOptionsPatch = bytecodePatch(
    description = "Opens Weather's screens without One UI's pop-over options.",
) {
    execute {
        PopOverOptionsToBundleFingerprint.method.apply {
            val first = implementation!!.instructions.firstOrNull() as? ReferenceInstruction
            val target = first?.reference as? MethodReference
            if (target == null || target.definingClass != POP_OVER_OPTIONS || target.name != "toBundle") {
                throw PatchException("pop-over options toBundle() no longer calls itself")
            }
            addInstructions(0, "const/4 p0, 0x0\nreturn-object p0")
        }
    }
}

/** Sends Weather's uses of One UI's additions to Android classes through OneUi (see redirectOneUiMembers). */
private val oneUiMembersPatch = bytecodePatch(
    description = "Answers One UI's additions to Android classes where the phone has none.",
) {
    extendWith(SAMSUNG_WEATHER_EXTENSION)

    execute {
        val (calls, fields) = redirectOneUiMembers("Lorg/ungoogled/samsungweather/OneUi;", "Lorg/ungoogled/samsungweather/Sem;")
        java.util.logging.Logger.getLogger("SamsungWeather").info("One UI members: $calls calls, $fields fields")
        // Measured against 1.7.30.50: 32 calls and 19 field accesses.
        if (calls < 15 || fields < 8) throw PatchException("only $calls One UI calls and $fields field accesses found")
    }
}

/**
 * Off One UI Weather runs on its own Android platform layer, but has no launcher icon, crashes opening the location
 * list (and other screens shown as pop-overs) and on the One UI classes and members its screens still call. Below
 * Android 15 it also died on start writing its Now Brief search index (appSearchPatch), and on Android 12 and 13
 * opening a forecast (olderAndroidPatch). The extension carries stand-ins for those classes.
 */
@Suppress("unused")
val enableNonOneUiPatch = bytecodePatch(
    name = "Enable non-One UI ROM support",
    description = "Makes Samsung Weather usable on non-One UI ROMs.",
    default = true,
) {
    compatibleWith(COMPATIBILITY_SAMSUNG_WEATHER)
    dependsOn(launcherIconPatch, launcherStatePatch, popOverOptionsPatch, oneUiMembersPatch, appSearchPatch, olderAndroidPatch)
    extendWith(SAMSUNG_WEATHER_EXTENSION)
}
