package org.ungoogled.patches.onepluslauncher.misc

import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.patch.resourcePatch
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import org.ungoogled.patches.coloros.exportDynamicReceivers
import org.ungoogled.patches.onepluslauncher.HIDDEN_CONSTRUCTORS
import org.ungoogled.patches.onepluslauncher.HIDDEN_MEMBERS
import org.ungoogled.patches.onepluslauncher.OnePlusLauncherConstants.COMPATIBILITY_ONEPLUS_LAUNCHER
import org.ungoogled.patches.samsung.redirectCalls
import org.ungoogled.patches.samsung.redirectMembers

internal const val ONEPLUS_LAUNCHER_EXTENSION = "extensions/onepluslauncher.mpe"

private const val EXT = "Lorg/ungoogled/onepluslauncher"
private const val RUNTIME = "$EXT/ColorOS;"

private val log = java.util.logging.Logger.getLogger("OnePlusLauncher")

private const val BINDER = "Landroid/os/Binder;"
private const val OBJECT = "Ljava/lang/Object;"

/**
 * Android classes the launcher extends whose constructor an installed app may not call: the public class each subclass
 * is rebased onto, and the interfaces it keeps.
 */
private val REBASES = mapOf(
    "Landroid/app/ITaskStackListener\$Stub;" to (BINDER to listOf("Landroid/app/ITaskStackListener;")),
    "Landroid/view/ISystemGestureExclusionListener\$Stub;" to (BINDER to listOf("Landroid/view/ISystemGestureExclusionListener;")),
    "Landroid/app/AppOpsManager\$OnOpChangedInternalListener;" to (OBJECT to listOf("Landroid/app/AppOpsManager\$OnOpChangedListener;")),
    "Landroid/view/InputEventSender;" to (OBJECT to emptyList()),
)

private val SETTINGS_SHAPES = listOf(
    "getString(Landroid/content/ContentResolver;Ljava/lang/String;)Ljava/lang/String;" to "GetString",
    "getInt(Landroid/content/ContentResolver;Ljava/lang/String;I)I" to "GetInt",
    "getInt(Landroid/content/ContentResolver;Ljava/lang/String;)I" to "GetInt",
    "getLong(Landroid/content/ContentResolver;Ljava/lang/String;J)J" to "GetLong",
    "getLong(Landroid/content/ContentResolver;Ljava/lang/String;)J" to "GetLong",
    "getFloat(Landroid/content/ContentResolver;Ljava/lang/String;F)F" to "GetFloat",
    "getFloat(Landroid/content/ContentResolver;Ljava/lang/String;)F" to "GetFloat",
    "putString(Landroid/content/ContentResolver;Ljava/lang/String;Ljava/lang/String;)Z" to "PutString",
    "putInt(Landroid/content/ContentResolver;Ljava/lang/String;I)Z" to "PutInt",
    "putLong(Landroid/content/ContentResolver;Ljava/lang/String;J)Z" to "PutLong",
    "putFloat(Landroid/content/ContentResolver;Ljava/lang/String;F)Z" to "PutFloat",
    "getIntForUser(Landroid/content/ContentResolver;Ljava/lang/String;II)I" to "GetIntForUser",
    "getStringForUser(Landroid/content/ContentResolver;Ljava/lang/String;I)Ljava/lang/String;" to "GetStringForUser",
    "putIntForUser(Landroid/content/ContentResolver;Ljava/lang/String;II)Z" to "PutIntForUser",
    "putStringForUser(Landroid/content/ContentResolver;Ljava/lang/String;Ljava/lang/String;I)Z" to "PutStringForUser",
)

/** Every settings call goes through LocalSettings, which keeps a refused write in the app's own preferences. */
private val SETTINGS_CALLS: Map<String, String> = buildMap {
    for (table in listOf("Global", "Secure", "System")) {
        for ((shape, suffix) in SETTINGS_SHAPES) {
            if (table == "Global" && suffix.endsWith("ForUser")) continue
            put(
                "Landroid/provider/Settings\$$table;->$shape",
                "$EXT/LocalSettings;->${table.lowercase()}$suffix${shape.substring(shape.indexOf('('))}",
            )
        }
    }
}

/**
 * The launcher refuses to start anywhere but a OnePlus phone (the "com.oneplus.software.oos" system feature or
 * OxygenOS 11.3): it shows "not compatible", skips its set-up and then crashes. Here it starts on any phone.
 */
private val deviceCheckPatch = bytecodePatch(
    description = "Lets the launcher start on phones that are not OnePlus.",
) {
    execute {
        val check = mutableClassDefBy("Lnet/oneplus/launcher/util/DeviceCompatible;").methods.firstOrNull {
            it.name == "getCompatible" && it.returnType == "Z"
        } ?: throw PatchException("DeviceCompatible.getCompatible not found")
        check.addInstructions(
            0,
            """
                const/4 v0, 0x1
                return v0
            """,
        )
    }
}

/** The launcher's own entry points into Firebase Crashlytics / Analytics. */
private val QUIET_METHODS = listOf(
    "Lnet/oneplus/launcher/LauncherApplication;" to "setCrashlyticsEnabled",
    "Lnet/oneplus/launcher/LauncherApplication;" to "setGaAnalyticsEnable",
    "Lnet/oneplus/launcher/LauncherApplication;" to "logGAEvent",
    "Lnet/oneplus/launcher/util/FirebaseUtils;" to "log",
)

/**
 * Off OxygenOS nobody agreed to OnePlus's network access, yet the launcher's Firebase start-up (Crashlytics, Analytics,
 * Firebase Installations) went online at every start even with collection off. Firebase is no longer started, the
 * launcher's calls into it do nothing, and the weather card no longer sends the phone's location to OnePlus's server.
 */
private val telemetryPatch = bytecodePatch(
    description = "Keeps the launcher offline: no Firebase, no weather card requests.",
) {
    dependsOn(
        resourcePatch {
            execute {
                document("AndroidManifest.xml").use { manifest ->
                    val drop = setOf(
                        "com.google.firebase.provider.FirebaseInitProvider",
                        "com.google.firebase.components.ComponentDiscoveryService",
                        "com.google.android.gms.measurement.AppMeasurementReceiver",
                        "com.google.android.gms.measurement.AppMeasurementService",
                        "com.google.android.gms.measurement.AppMeasurementJobService",
                        "com.google.android.gms.analytics.AnalyticsReceiver",
                        "com.google.android.gms.analytics.AnalyticsService",
                        "com.google.android.gms.analytics.AnalyticsJobService",
                    )
                    val application = manifest.getElementsByTagName("application").item(0)
                    var removed = 0
                    for (tag in listOf("provider", "service", "receiver")) {
                        val nodes = manifest.getElementsByTagName(tag)
                        for (i in nodes.length - 1 downTo 0) {
                            val node = nodes.item(i) as org.w3c.dom.Element
                            if (node.getAttribute("android:name") in drop) {
                                application.removeChild(node)
                                removed++
                            }
                        }
                    }
                    if (removed != drop.size) throw PatchException("removed $removed of ${drop.size} Firebase/GA components")
                }
            }
        },
    )

    execute {
        for ((type, name) in QUIET_METHODS) {
            val methods = mutableClassDefBy(type).methods.filter { it.name == name && it.returnType == "V" }
            if (methods.isEmpty()) throw PatchException("$type.$name not found")
            methods.forEach { it.addInstructions(0, "return-void") }
        }
        val weather = mutableClassDefBy("Lnet/oneplus/launcher/quickpage/weatherassistant/WeatherUpdateService;").methods
            .firstOrNull { it.name == "onStartJob" && it.returnType == "Z" }
            ?: throw PatchException("WeatherUpdateService.onStartJob not found")
        weather.addInstructions(
            0,
            """
                const/4 v0, 0x0
                return v0
            """,
        )
    }
}

/**
 * OnePlus Launcher is a system app built against the full Android framework: an installed app may not link to its
 * hidden parts. Each hidden member it uses goes through Members (used where Android allows it, "absent" elsewhere),
 * each `new` of a class an app may not construct through Members.construct, and subclasses of such classes are
 * rebased. The OPLUS SDK stubs it carries answer "absent" instead of throwing. Its broadcast receivers registered at run time get the export flag Android 14+ demands, and its settings
 * (first-boot state, its own options) are kept locally where Android refuses the write.
 */
private val hiddenApisPatch = bytecodePatch(
    description = "Answers Android's hidden parts where an installed app may not use them.",
) {
    extendWith(ONEPLUS_LAUNCHER_EXTENSION)

    execute {
        // The OPLUS SDK compile-time stubs the APK carries (846 classes) answer "absent" instead of throwing.
        val stubs = neutralizeStubs()
        log.info("stub methods: $stubs")
        // Measured against 5.2.66: 7,287 stub methods.
        if (stubs < 1000) throw PatchException("only $stubs stub methods found")
        // Settings first: some of its calls are hidden per-user ones, which LocalSettings answers for the app's user.
        val settings = redirectCalls(SETTINGS_CALLS)
        log.info("settings calls: $settings")
        // Measured against 5.2.66: 113 settings calls.
        if (settings < 50) throw PatchException("only $settings settings calls found")
        val (calls, fields) = redirectMembers("$EXT/Members;", "$EXT/Hidden;", HIDDEN_MEMBERS) { _, _ -> false }
        val constructors = redirectConstructors("$EXT/Members;", "$EXT/Hidden;", HIDDEN_CONSTRUCTORS)
        val rebased = rebaseSubclasses(REBASES)
        skipCastOfNull("$EXT/Hidden;")
        val receivers = exportDynamicReceivers(RUNTIME)
        log.info("hidden members: $calls calls, $fields fields; constructors: $constructors; rebased: $rebased; receivers: $receivers")
        // Measured against 5.2.66: 208 calls, 26 field accesses, 12 constructors, 4 rebased classes, 51 receivers.
        if (calls < 50) throw PatchException("only $calls hidden member calls found")

        // Android 11's ParcelableColorSpace was a ColorSpace; later ones are not, and the recents screenshot helper
        // passing one to Bitmap.wrapHardwareBuffer made its class fail verification: it now passes its ColorSpace.
        val bitmapUtil = mutableClassDefBy("Lcom/android/systemui/shared/recents/utilities/BitmapUtil;").methods
            .firstOrNull { it.name == "bundleToHardwareBitmap" }
            ?: throw PatchException("BitmapUtil.bundleToHardwareBitmap not found")
        val wrap = bitmapUtil.implementation!!.instructions.indexOfFirst {
            ((it as? ReferenceInstruction)?.reference as? MethodReference)?.name == "wrapHardwareBuffer"
        }
        if (wrap < 0) throw PatchException("wrapHardwareBuffer call not found")
        val colorSpace = (bitmapUtil.implementation!!.instructions[wrap] as FiveRegisterInstruction).registerD
        bitmapUtil.addInstructions(
            wrap,
            """
                invoke-virtual { v$colorSpace }, Landroid/graphics/ParcelableColorSpace;->getColorSpace()Landroid/graphics/ColorSpace;
                move-result-object v$colorSpace
            """,
        )
    }
}

/**
 * Off OxygenOS the launcher stops at once (it is the OxygenOS 11 launcher of 2021, net.oneplus.launcher 5.2.66).
 * The extension carries stand-ins for the OnePlus / ColorOS framework classes it calls; on a OnePlus phone the
 * framework's own classes load first, so they never run there. Recents and navigation gestures stay the phone's own:
 * Android gives them only to the system's home app.
 */
@Suppress("unused")
val enableNonOxygenOsPatch = bytecodePatch(
    name = "Enable non-OxygenOS ROM support",
    description = "Makes the old OxygenOS 11 OnePlus Launcher usable as a home screen on non-OxygenOS ROMs.",
    default = true,
) {
    compatibleWith(COMPATIBILITY_ONEPLUS_LAUNCHER)
    dependsOn(deviceCheckPatch, hiddenApisPatch, telemetryPatch)
    extendWith(ONEPLUS_LAUNCHER_EXTENSION)
}
