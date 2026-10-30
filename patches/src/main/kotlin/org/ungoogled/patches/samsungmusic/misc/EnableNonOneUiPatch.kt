package org.ungoogled.patches.samsungmusic.misc

import app.morphe.patcher.extensions.InstructionExtensions.addInstructionsWithLabels
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.patch.resourcePatch
import app.morphe.patcher.util.smali.ExternalLabel
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import org.ungoogled.patches.samsung.redirectOneUiMembers
import org.ungoogled.patches.samsung.rewriteStringLiterals
import org.ungoogled.patches.samsungmusic.SamsungMusicConstants.COMPATIBILITY_SAMSUNG_MUSIC
import org.ungoogled.patches.shared.addInstructionsAtLabel
import org.w3c.dom.Element

internal const val SAMSUNG_MUSIC_EXTENSION = "extensions/samsungmusic.mpe"

/**
 * Off One UI the libraries Samsung's system provides are missing. Music requires one Java library, com.samsung.device,
 * which alone stops the install; it becomes optional like the others.
 */
private val manifestPatch = resourcePatch {
    execute {
        document("AndroidManifest.xml").use { manifest ->
            var required = 0
            for (tag in listOf("uses-library", "uses-native-library")) {
                val list = manifest.getElementsByTagName(tag)
                for (i in 0 until list.length) {
                    val element = list.item(i) as Element
                    if (element.getAttribute("android:required") != "false") required++
                    element.setAttribute("android:required", "false")
                }
            }
            if (required == 0) throw PatchException("no required Samsung library in the manifest")
        }
    }
}

/** Sends Music's uses of One UI's additions to Android classes through OneUi (see redirectOneUiMembers). */
private val oneUiMembersPatch = bytecodePatch(
    description = "Answers One UI's additions to Android classes where the phone has none.",
) {
    extendWith(SAMSUNG_MUSIC_EXTENSION)

    execute {
        val (calls, fields) = redirectOneUiMembers("Lorg/ungoogled/samsungmusic/OneUi;", "Lorg/ungoogled/samsungmusic/Sem;")
        java.util.logging.Logger.getLogger("SamsungMusic").info("One UI members: $calls calls, $fields fields")
        // Measured against 16.2.45.1: 72 calls and 7 field accesses.
        if (calls < 30) throw PatchException("only $calls One UI calls found")
    }
}

/**
 * Music sorts its library with LOCALIZED_NATURAL, a collation only One UI's SQLite has; elsewhere every such query
 * fails ("no such collation sequence"). LOCALIZED is Android's own locale-aware collation.
 */
private val collationPatch = bytecodePatch(
    description = "Sorts the library with Android's own collation.",
) {
    execute {
        val rewritten = rewriteStringLiterals { s ->
            if (s.contains("COLLATE LOCALIZED_NATURAL")) s.replace("COLLATE LOCALIZED_NATURAL", "COLLATE LOCALIZED") else null
        }
        // Measured against 16.2.45.1: 119 literals (some kept by R8 as static String fields).
        java.util.logging.Logger.getLogger("SamsungMusic").info("Collations: $rewritten literals")
        if (rewritten == 0) throw PatchException("no LOCALIZED_NATURAL collation found")
    }
}

/** Android 11+ hides other apps' activities from queries unless the manifest names the intent. */
private val soundEffectsManifestPatch = resourcePatch {
    execute {
        document("AndroidManifest.xml").use { manifest ->
            val root = manifest.documentElement
            val queries = manifest.getElementsByTagName("queries").item(0) as Element?
                ?: manifest.createElement("queries").also { root.insertBefore(it, root.firstChild) }
            queries.appendChild(
                manifest.createElement("intent").apply {
                    appendChild(
                        manifest.createElement("action").apply {
                            setAttribute("android:name", "android.media.action.DISPLAY_AUDIO_EFFECT_CONTROL_PANEL")
                        },
                    )
                },
            )
        }
    }
}

/**
 * "Sound quality and effects" only acts when One UI's SoundAlive app is installed, and otherwise logs "no action".
 * Its fallback is Android's standard audio effect panel, which other ROMs answer with their own equalizer: the
 * SoundAlive check now also passes when something answers that panel.
 */
private val soundEffectsPatch = bytecodePatch(
    description = "Opens the phone's own equalizer from Sound quality and effects.",
) {
    dependsOn(soundEffectsManifestPatch)
    extendWith(SAMSUNG_MUSIC_EXTENSION)

    execute {
        SoundAliveLaunchFingerprint.method.apply {
            val instructions = implementation!!.instructions
            // The flag is tested right after the "launch. id=... external=..." log line.
            val log = instructions.indexOfFirst {
                it.opcode == Opcode.INVOKE_STATIC &&
                    ((it as ReferenceInstruction).reference as MethodReference).let { r ->
                        r.definingClass == "Landroid/util/Log;" && r.name == "i"
                    }
            }
            val branch = instructions.getOrNull(log + 1)
            if (log < 0 || branch?.opcode != Opcode.IF_EQZ) throw PatchException("SoundAlive check not found")
            val flag = (branch as OneRegisterInstruction).registerA
            if (flag > 15) throw PatchException("SoundAlive flag register v$flag too high")
            addInstructionsAtLabel(
                log + 1,
                """
                    invoke-static { v$flag, p0 }, Lorg/ungoogled/samsungmusic/SoundEffects;->available(ZLandroid/content/Context;)Z
                    move-result v$flag
                """,
            )
        }
    }
}

/**
 * Music's media browser (library browsing over Bluetooth, resuming from the system's media controls) answers only
 * packages signed with Samsung's or Google's keys; elsewhere the ROM's own Bluetooth and SystemUI carry its platform
 * key, so a caller signed like the system is let in too.
 */
private val mediaBrowserPatch = bytecodePatch(
    description = "Lets the system's own Bluetooth and media controls browse the library.",
) {
    extendWith(SAMSUNG_MUSIC_EXTENSION)

    execute {
        MediaBrowserCallerFingerprint.method.apply {
            if (implementation!!.registerCount - 2 < 1) throw PatchException("media browser check has no local register")
            addInstructionsWithLabels(
                0,
                """
                    invoke-static { p0, p1 }, Lorg/ungoogled/samsungmusic/Callers;->system(Landroid/content/Context;Ljava/lang/String;)Z
                    move-result v0
                    if-eqz v0, :samsung_check
                    return v0
                """,
                ExternalLabel("samsung_check", implementation!!.instructions.first()),
            )
        }
    }
}

/**
 * Off One UI Music cannot be installed (com.samsung.device is required), and once installed dies at start-up: its
 * support library reads One UI's version and members and calls 62 One UI classes, which the extension stands in for.
 */
@Suppress("unused")
val enableNonOneUiPatch = bytecodePatch(
    name = "Enable non-One UI ROM support",
    description = "Makes Samsung Music installable and usable on non-One UI ROMs.",
    default = true,
) {
    compatibleWith(COMPATIBILITY_SAMSUNG_MUSIC)
    dependsOn(manifestPatch, oneUiMembersPatch, collationPatch, soundEffectsPatch, mediaBrowserPatch)
    extendWith(SAMSUNG_MUSIC_EXTENSION)
}
