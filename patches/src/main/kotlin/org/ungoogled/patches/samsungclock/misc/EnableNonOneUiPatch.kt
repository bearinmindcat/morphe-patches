package org.ungoogled.patches.samsungclock.misc

import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.extensions.InstructionExtensions.replaceInstruction
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.patch.resourcePatch
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import org.ungoogled.patches.samsung.convertSprDrawables
import org.ungoogled.patches.samsung.makeLibrariesOptional
import org.ungoogled.patches.samsung.redirectOneUiMembers
import org.ungoogled.patches.samsung.redirectCalls
import org.ungoogled.patches.samsung.redirectSystemServices
import org.ungoogled.patches.samsung.stubNativeLibraries
import org.ungoogled.patches.samsungclock.SamsungClockConstants.COMPATIBILITY_SAMSUNG_CLOCK
import org.w3c.dom.Element

internal const val SAMSUNG_CLOCK_EXTENSION = "extensions/samsungclock.mpe"

private const val ALERT_SCREEN = "Lorg/ungoogled/samsungclock/AlertScreen;"
private const val SYSTEM_ACCESS = "Lorg/ungoogled/samsungclock/SystemAccess;"

/** Samsung's Quram image and GIF codecs (alarm backgrounds), system libraries only One UI has. */
private val QURAM_LIBRARIES = setOf("agifencoder.quram", "quramagifencoder", "quram", "imagecodec.quram")

/** Android calls Clock makes with a system app's privileges, each to SystemAccess's same-named wrapper. */
private val SYSTEM_CALLS = listOf(
    "Landroid/view/ViewManager;" to "addView(Landroid/view/View;Landroid/view/ViewGroup\$LayoutParams;)V",
    "Landroid/view/ViewManager;" to "updateViewLayout(Landroid/view/View;Landroid/view/ViewGroup\$LayoutParams;)V",
    "Landroid/view/ViewManager;" to "removeView(Landroid/view/View;)V",
    "Landroid/view/WindowManager;" to "removeViewImmediate(Landroid/view/View;)V",
    "Landroid/content/Context;" to "createWindowContext(ILandroid/os/Bundle;)Landroid/content/Context;",
    "Landroid/content/Context;" to "createWindowContext(Landroid/view/Display;ILandroid/os/Bundle;)Landroid/content/Context;",
    "Landroid/telephony/TelephonyManager;" to "registerTelephonyCallback(Ljava/util/concurrent/Executor;Landroid/telephony/TelephonyCallback;)V",
    "Landroid/telephony/TelephonyManager;" to "getCallState()I",
).associate { (owner, shape) ->
    "$owner->$shape" to "$SYSTEM_ACCESS->${shape.substringBefore('(')}($owner${shape.substringAfter('(')}"
} + mapOf(
    "Ljava/lang/System;->loadLibrary(Ljava/lang/String;)V" to "$SYSTEM_ACCESS->loadLibrary(Ljava/lang/String;)V",
    // The timer's default tone is a Samsung system sound: the phone's alarm sound where it is missing.
    "Lcom/sec/android/app/clockpackage/ringtonepicker/util/RingtonePlayer\$TimerRingtone;->getDefaultRingtoneUri(Landroid/content/Context;)Landroid/net/Uri;" to
        "Lorg/ungoogled/samsungclock/Sounds;->timerDefault(Landroid/content/Context;)Landroid/net/Uri;",
    // One UI's ringtone cursor has more columns, which the sound picker reads unchecked.
    "Landroid/media/RingtoneManager;->getCursor()Landroid/database/Cursor;" to
        "Lorg/ungoogled/samsungclock/RingtoneCursor;->getCursor(Landroid/media/RingtoneManager;)Landroid/database/Cursor;",
)

/**
 * Clock requires Samsung's GIF encoder (libagifencoder.quram.so), which alone stops the install. Its ringing
 * notifications also need the full-screen intent permission to open the alarm and timer screens (see AlertScreen).
 */
private val manifestPatch = resourcePatch {
    execute {
        if (makeLibrariesOptional() == 0) throw PatchException("no required Samsung library in the manifest")
        document("AndroidManifest.xml").use { manifest ->
            val root = manifest.documentElement
            val permission = "android.permission.USE_FULL_SCREEN_INTENT"
            val list = manifest.getElementsByTagName("uses-permission")
            val declared = (0 until list.length).any { (list.item(it) as Element).getAttribute("android:name") == permission }
            if (!declared) {
                root.insertBefore(
                    manifest.createElement("uses-permission").apply { setAttribute("android:name", permission) },
                    manifest.getElementsByTagName("application").item(0),
                )
            }
        }
    }
}

/** Some icons are SPR files, Samsung's vector format that only One UI can draw (Add city died on one): vector drawables. */
private val sprDrawablesPatch = resourcePatch(
    description = "Converts Samsung's SPR icons to vector drawables.",
) {
    execute {
        val converted = convertSprDrawables()
        java.util.logging.Logger.getLogger("SamsungClock").info("SPR drawables: $converted")
        // Measured against 12.4.02.38: 22 files.
        if (converted < 10) throw PatchException("only $converted SPR drawables found")
    }
}

/** Replaces each call in [method] to [owner]->[name] with the same-shaped static [target] (receiver first). */
private fun MutableMethod.moveCalls(owner: String, name: String, target: String): Int {
    val instructions = implementation!!.instructions
    var moved = 0
    for (index in instructions.indices) {
        val insn = instructions[index] as? ReferenceInstruction ?: continue
        val r = insn.reference as? MethodReference ?: continue
        if (r.definingClass != owner || r.name != name) continue
        val i = insn as FiveRegisterInstruction
        val registers = listOf(i.registerC, i.registerD, i.registerE, i.registerF, i.registerG).take(i.registerCount)
        replaceInstruction(index, registers.joinToString(", ", "invoke-static { ", " }, $target") { "v$it" })
        moved++
    }
    if (moved == 0) throw PatchException("no $owner->$name call in ${this.definingClass}->${this.name}")
    return moved
}

/**
 * The privileges a re-signed copy lacks: the alarm and timer screens may not open from the background (their
 * ringing notifications open them instead, see AlertScreen), the pop-ups over other apps are system windows using
 * Samsung's status bar service (see SystemAccess, Services), call listeners need the phone permission, asking whether
 * the screen saver runs needs a system permission (the answer is no), and the image and GIF codecs for alarm
 * backgrounds are Samsung system libraries (their natives answer nothing).
 */
private val systemAccessPatch = bytecodePatch(
    description = "Lets Clock ring without the system app's privileges.",
) {
    extendWith(SAMSUNG_CLOCK_EXTENSION)

    execute {
        IsDreamingFingerprint.method.addInstructions(0, "const/4 v0, 0x0\nreturn v0")

        val builder = "Landroid/app/Notification\$Builder;"
        val content = "$ALERT_SCREEN->content(${builder}Landroid/app/PendingIntent;)$builder"
        AlarmRingingNotificationFingerprint.method.moveCalls(builder, "setContentIntent", content)
        TimerRingingNotificationFingerprint.method.apply {
            moveCalls(builder, "setContentIntent", content)
            moveCalls(
                "Landroid/app/PendingIntent;", "getService",
                "$ALERT_SCREEN->service(Landroid/content/Context;ILandroid/content/Intent;I)Landroid/app/PendingIntent;",
            )
        }

        // Before the loads move: classes are matched by their own System.loadLibrary call.
        val natives = stubNativeLibraries(
            QURAM_LIBRARIES, setOf("Lcom/sec/samsung/gallery/decoder/regiondecoder/QuramCodecRegionDecoder\$Companion;"),
        )
        val moved = redirectCalls(SYSTEM_CALLS)
        val services = redirectSystemServices("Lorg/ungoogled/samsungclock/Services;")
        java.util.logging.Logger.getLogger("SamsungClock")
            .info("System calls: $moved, service lookups: $services, natives stubbed: $natives")
        if (natives.size < 3) throw PatchException("Quram codec classes not found: $natives")
        if (services < 100) throw PatchException("only $services service lookups found")
        // Measured against 12.4.02.38: 22 window calls, 3 window contexts, 15 call listeners/states, 7 library loads,
        // 4 timer tone lookups, 6 ringtone cursors.
        if (moved < 30) throw PatchException("only $moved system calls found")
    }
}

/** Sends Clock's uses of One UI's additions to Android classes through OneUi (see redirectOneUiMembers). */
private val oneUiMembersPatch = bytecodePatch(
    description = "Answers One UI's additions to Android classes where the phone has none.",
) {
    extendWith(SAMSUNG_CLOCK_EXTENSION)

    execute {
        val (calls, fields) = redirectOneUiMembers("Lorg/ungoogled/samsungclock/OneUi;", "Lorg/ungoogled/samsungclock/Sem;")
        java.util.logging.Logger.getLogger("SamsungClock").info("One UI members: $calls calls, $fields fields")
        if (calls < 100 || fields < 10) throw PatchException("only $calls One UI calls and $fields field accesses found")
    }
}

/**
 * Clock also calls 41 classes only One UI has (SemFloatingFeature, the cover and motion managers ...). The extension
 * carries a generated stand-in for each, with just the members Clock uses, answering "absent"; on a Samsung phone the
 * framework's own classes load first, so they never run there.
 */
@Suppress("unused")
val enableNonOneUiPatch = bytecodePatch(
    name = "Enable non-One UI ROM support",
    description = "Makes Samsung Clock installable and usable on non-One UI ROMs.",
    default = true,
) {
    compatibleWith(COMPATIBILITY_SAMSUNG_CLOCK)
    dependsOn(manifestPatch, sprDrawablesPatch, oneUiMembersPatch, systemAccessPatch)
    extendWith(SAMSUNG_CLOCK_EXTENSION)
}
