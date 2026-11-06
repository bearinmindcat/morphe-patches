package org.ungoogled.patches.nothingrecorder.misc

import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.patch.resourcePatch
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.reference.StringReference
import org.ungoogled.patches.nothingrecorder.NothingRecorderConstants.COMPATIBILITY_NOTHING_RECORDER
import org.ungoogled.patches.samsung.rewriteStringLiterals

internal const val NOTHING_RECORDER_EXTENSION = "extensions/nothingrecorder.mpe"

/**
 * Nothing Recorder's recordings provider and file provider carry AOSP's recorder authorities
 * (com.android.soundrecorder.provider / .fileprovider), which the Xiaomi Recorder port (package com.android.soundrecorder)
 * declares as its own: Android installs only one app per authority, so the two ports could not be installed together.
 * Nothing Recorder's get its package's name instead, in the manifest and in its code.
 */
private val AUTHORITIES = mapOf(
    "com.android.soundrecorder.provider" to "com.nothing.soundrecorder.provider",
    "com.android.soundrecorder.fileprovider" to "com.nothing.soundrecorder.fileprovider",
)

private val authoritiesPatch = bytecodePatch(
    description = "Gives Nothing Recorder's providers names of its own, so it installs beside Xiaomi Recorder.",
) {
    dependsOn(
        resourcePatch {
            execute {
                document("AndroidManifest.xml").use { manifest ->
                    val providers = manifest.getElementsByTagName("provider")
                    var renamed = 0
                    for (i in 0 until providers.length) {
                        val p = providers.item(i) as org.w3c.dom.Element
                        val authority = AUTHORITIES[p.getAttribute("android:authorities")] ?: continue
                        p.setAttribute("android:authorities", authority)
                        renamed++
                    }
                    if (renamed != AUTHORITIES.size) {
                        throw PatchException("recorder providers found: $renamed of ${AUTHORITIES.size}")
                    }
                }
            }
        },
    )

    execute {
        // The authority itself (UriMatcher, FileProvider lookups) and content:// URIs naming it.
        val rewritten = rewriteStringLiterals { literal ->
            AUTHORITIES.entries.firstOrNull { (old, _) -> literal == old || literal.startsWith("content://$old/") }
                ?.let { (old, new) -> literal.replaceFirst(old, new) }
        }
        // Measured against 4.0.0: 4 (two UriMatcher entries, the recordings URI, the FileProvider lookup).
        if (rewritten < 3) throw PatchException("only $rewritten recorder authority literals found")
    }
}

/**
 * Off a Nothing phone, Nothing Recorder crashes at launch: it reflects on Nothing OS framework classes that only the
 * ROM provides (com.nothing.NtFeaturesUtils, the online-config reader, Nothing's analytics client, the NDot font
 * helper) and turns any missing one into a fatal error. The extension carries stand-ins for those classes that
 * answer "feature absent", so the app starts and recording, playback, the recordings list and search work on any
 * ROM; features that need Nothing's own services (home-screen widget, internal-audio capture) are out of scope.
 */
@Suppress("unused")
val enableNonNothingPatch = bytecodePatch(
    name = "Enable non-Nothing OS ROM support",
    description = "Makes Nothing Recorder installable and usable on non-Nothing OS ROMs.",
    default = true,
) {
    compatibleWith(COMPATIBILITY_NOTHING_RECORDER)
    dependsOn(authoritiesPatch)
    extendWith(NOTHING_RECORDER_EXTENSION)

    execute {
        // Fail loudly on a build that is not the expected Nothing-framework-reflecting one.
        var found = false
        classDefForEach classes@{ classDef ->
            if (found) return@classes
            for (method in classDef.methods) {
                val instructions = method.implementation?.instructions ?: continue
                if (
                    instructions.any {
                        it.opcode == Opcode.CONST_STRING &&
                            ((it as? ReferenceInstruction)?.reference as? StringReference)?.string ==
                            "com.nothing.NtFeaturesUtils"
                    }
                ) {
                    found = true
                    break
                }
            }
        }
        if (!found) {
            throw PatchException("Nothing Recorder: the com.nothing.NtFeaturesUtils reference is gone; re-check the port.")
        }
    }
}
