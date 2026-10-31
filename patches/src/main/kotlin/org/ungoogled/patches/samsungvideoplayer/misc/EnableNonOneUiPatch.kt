package org.ungoogled.patches.samsungvideoplayer.misc

import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.extensions.InstructionExtensions.replaceInstruction
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.patch.resourcePatch
import org.ungoogled.patches.samsung.implementOneUiInterfaceMethods
import org.ungoogled.patches.samsung.redirectCalls
import org.ungoogled.patches.samsung.redirectOneUiMembers
import org.ungoogled.patches.samsung.redirectSystemServices
import org.ungoogled.patches.samsung.replaceSprDrawables
import org.ungoogled.patches.samsung.rewriteStringLiterals
import org.ungoogled.patches.samsungvideoplayer.SamsungVideoPlayerConstants.COMPATIBILITY_SAMSUNG_VIDEO_PLAYER
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.iface.reference.MethodReference

internal const val SAMSUNG_VIDEO_PLAYER_EXTENSION = "extensions/samsungvideoplayer.mpe"
private const val SERVICES = "Lorg/ungoogled/samsungvideoplayer/Services;"

/**
 * Video Player's media controls in the background-play notification (and a few other images) are drawn in One UI's
 * SPR vector format, which other ROMs cannot decode: the system UI failed to show the notification and killed the
 * player. They become plain vector icons.
 */
private val sprDrawablesPatch = resourcePatch(
    description = "Replaces One UI's SPR images with plain vector icons.",
) {
    execute {
        val replaced = replaceSprDrawables()
        java.util.logging.Logger.getLogger("SamsungVideoPlayer").info("SPR drawables: $replaced")
        // Measured against 7.3.47.1: 35 SPR drawables, 11 of them media control icons.
        if (replaced.size < 10) throw PatchException("only ${replaced.size} SPR drawables found")
    }
}

/** Sends Video Player's uses of One UI's additions to Android classes through OneUi (see redirectOneUiMembers). */
private val oneUiMembersPatch = bytecodePatch(
    description = "Answers One UI's additions to Android classes where the phone has none.",
) {
    extendWith(SAMSUNG_VIDEO_PLAYER_EXTENSION)

    execute {
        val (calls, fields) = redirectOneUiMembers(
            "Lorg/ungoogled/samsungvideoplayer/OneUi;", "Lorg/ungoogled/samsungvideoplayer/Sem;",
        )
        // The player screen's own interface declares semIsResumed(), for One UI's Activity to implement.
        val implemented = implementOneUiInterfaceMethods("Lorg/ungoogled/samsungvideoplayer/OneUi;")
        java.util.logging.Logger.getLogger("SamsungVideoPlayer")
            .info("One UI members: $calls calls, $fields fields, $implemented inherited methods")
        // Measured against 7.3.47.1: 185 calls, 33 field accesses, 1 inherited method (MoviePlayer.semIsResumed).
        if (calls < 100 || implemented == 0) throw PatchException("only $calls One UI calls and $implemented inherited methods found")
    }
}

/**
 * Every video opens by registering with One UI's event delegation service, cast from
 * getSystemService("semeventdelegator") without a check: off One UI that is null and the player crashed.
 */
private val systemServicesPatch = bytecodePatch(
    description = "Answers One UI's system services where the phone has none.",
) {
    extendWith(SAMSUNG_VIDEO_PLAYER_EXTENSION)

    execute {
        val moved = redirectSystemServices(SERVICES)
        if (moved == 0) throw PatchException("no getSystemService calls found")
    }
}

/**
 * Opening a video asks for the user count (MANAGE_USERS) and the phone's call state (READ_PHONE_STATE), permissions
 * only Samsung's own signed build is granted: elsewhere each threw and the player crashed. The extension answers one
 * user, and the call state from the audio mode, when refused. The background-play notification also starts its title
 * marquee in a way only One UI's system UI accepts (the system then killed the player); that call is dropped.
 */
private val privilegedCallsPatch = bytecodePatch(
    description = "Answers the user count and call state where the phone refuses them; drops a One UI-only notification call.",
) {
    extendWith(SAMSUNG_VIDEO_PLAYER_EXTENSION)

    execute {
        val moved = redirectCalls(
            mapOf(
                "Landroid/os/UserManager;->getUserCount()I" to "$SERVICES->getUserCount(Landroid/os/UserManager;)I",
                "Landroid/telephony/TelephonyManager;->registerTelephonyCallback(Ljava/util/concurrent/Executor;Landroid/telephony/TelephonyCallback;)V" to
                    "$SERVICES->registerTelephonyCallback(Landroid/telephony/TelephonyManager;Ljava/util/concurrent/Executor;Landroid/telephony/TelephonyCallback;)V",
                "Landroid/telephony/TelephonyManager;->unregisterTelephonyCallback(Landroid/telephony/TelephonyCallback;)V" to
                    "$SERVICES->unregisterTelephonyCallback(Landroid/telephony/TelephonyManager;Landroid/telephony/TelephonyCallback;)V",
                "Landroid/telephony/TelephonyManager;->getCallState()I" to "$SERVICES->getCallState(Landroid/telephony/TelephonyManager;)I",
            ),
        )
        // Measured against 7.3.47.1: 2 getUserCount, 1 register, 1 unregister, 3 getCallState.
        if (moved < 4) throw PatchException("only $moved user count / call state calls found")
        val remoteViews = redirectCalls(
            mapOf(
                "Landroid/widget/RemoteViews;->setInt(ILjava/lang/String;I)V" to
                    "$SERVICES->setInt(Landroid/widget/RemoteViews;ILjava/lang/String;I)V",
            ),
        )
        if (remoteViews == 0) throw PatchException("no RemoteViews.setInt calls found")
    }
}

/**
 * Video Player picks its storage permission by One UI's version, which is 0 elsewhere: it then asked for
 * READ_EXTERNAL_STORAGE, which Android 13 and later never grant, and closed. It needs the video permission (the app
 * requires Android 15).
 */
private val videoPermissionPatch = bytecodePatch(
    description = "Asks for the video permission instead of the legacy storage one.",
) {
    execute {
        val rewritten = rewriteStringLiterals { s ->
            when (s) {
                "android.permission.READ_EXTERNAL_STORAGE" -> "android.permission.READ_MEDIA_VIDEO"
                "android.permission-group.STORAGE" -> "android.permission-group.READ_MEDIA_VISUAL"
                else -> null
            }
        }
        // Measured against 7.3.47.1: 2 literals, both in the required-permission list.
        if (rewritten == 0) throw PatchException("no storage permission found")
    }
}

/**
 * The pop-up player button opens Android's picture-in-picture from One UI 5.1 on; before that (and so off One UI) it
 * opens Samsung's own floating player, a window type only One UI has, and the player crashed. The button takes the One
 * UI 5.1 branch (picture-in-picture unless the hidden developer switch says otherwise), and the feature table's
 * picture-in-picture flag is set, found as the flag the player screen checks before isInPictureInPictureMode().
 */
private val pictureInPicturePatch = bytecodePatch(
    description = "Uses Android's picture-in-picture for the pop-up player.",
) {
    execute {
        val table = FeatureTableFingerprint.method
        val tableType = table.definingClass
        val player = mutableClassDefBy("Lcom/samsung/android/video/player/activity/MoviePlayer;")
        val flags = mutableSetOf<FieldReference>()
        for (m in player.methods) {
            val instructions = m.implementation?.instructions?.toList() ?: continue
            for ((i, insn) in instructions.withIndex()) {
                val field = (insn as? ReferenceInstruction)?.reference as? FieldReference ?: continue
                if (insn.opcode != Opcode.SGET_BOOLEAN || field.definingClass != tableType) continue
                val pip = instructions.drop(i + 1).take(4).any {
                    ((it as? ReferenceInstruction)?.reference as? MethodReference)?.name == "isInPictureInPictureMode"
                }
                if (pip) flags += field
            }
        }
        val flag = flags.singleOrNull() ?: throw PatchException("picture-in-picture flag not found (${flags.size} candidates)")
        val end = table.implementation!!.instructions.indexOfLast { it.opcode == Opcode.RETURN_VOID }
        table.addInstructions(end, "const/4 v0, 0x1\nsput-boolean v0, $flag")

        // if (oneUi51) pip = !developerSwitch else if (pipSupported && developerSwitch) pip = true
        val button = PopUpButtonFingerprint.method
        val instructions = button.implementation!!.instructions
        val branch = instructions.indices.singleOrNull { i ->
            instructions[i].opcode == Opcode.SGET_BOOLEAN && instructions.getOrNull(i + 1)?.opcode == Opcode.IF_EQZ &&
                instructions.drop(i + 2).take(3).any { it.opcode == Opcode.XOR_INT_2ADDR }
        } ?: throw PatchException("pop-up button's One UI version check not found")
        button.replaceInstruction(branch, "const/4 v${(instructions[branch] as OneRegisterInstruction).registerA}, 0x1")
    }
}

/**
 * Off One UI Video Player dies at start-up on One UI classes it calls (SemMultiWindowManager first) and plays every
 * video with One UI's SemMediaPlayer. The extension carries stand-ins for the 121 One UI classes it calls, and a
 * SemMediaPlayer built on Android's own MediaPlayer.
 */
@Suppress("unused")
val enableNonOneUiPatch = bytecodePatch(
    name = "Enable non-One UI ROM support",
    description = "Makes Samsung Video Player installable and usable on non-One UI ROMs.",
    default = true,
) {
    compatibleWith(COMPATIBILITY_SAMSUNG_VIDEO_PLAYER)
    dependsOn(oneUiMembersPatch, systemServicesPatch, privilegedCallsPatch, videoPermissionPatch, pictureInPicturePatch, sprDrawablesPatch)
    extendWith(SAMSUNG_VIDEO_PLAYER_EXTENSION)
}
