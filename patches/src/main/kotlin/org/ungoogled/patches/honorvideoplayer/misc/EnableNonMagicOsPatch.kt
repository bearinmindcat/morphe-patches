package org.ungoogled.patches.honorvideoplayer.misc

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.extensions.InstructionExtensions.replaceInstruction
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.patch.resourcePatch
import org.ungoogled.patches.honorvideoplayer.HonorVideoPlayerConstants.COMPATIBILITY_HONOR_VIDEO_PLAYER
import org.ungoogled.patches.samsung.makeLibrariesOptional
import org.w3c.dom.Element
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.reference.MethodReference

internal const val HONOR_VIDEO_PLAYER_EXTENSION = "extensions/honorvideoplayer.mpe"
private const val EXT = "Lorg/ungoogled/honorvideoplayer"

private val log = java.util.logging.Logger.getLogger("HonorVideoPlayer")

/**
 * Video Player requires Honor's media library (uses-library com.hihonor.mcs.media.avfoundation, part of MagicOS), so
 * Android refuses to install it elsewhere (INSTALL_FAILED_MISSING_SHARED_LIBRARY). The library becomes optional; the
 * extension's stand-ins for its player and extractor play and read media with Android's own framework.
 */
private val optionalLibraryPatch = resourcePatch(
    description = "Lets Honor Video Player install without Honor's media library.",
) {
    execute {
        val required = makeLibrariesOptional()
        log.info("Required libraries made optional: $required")
        if (required < 1) throw PatchException("Honor's media library is no longer a required library")
    }
}

/**
 * Video Player has no launcher entry (on MagicOS videos open in it from Gallery and Files); its own library -- all
 * videos, folders, playlists, watch history, scans -- is only reachable from the activity's MAIN filter. That filter
 * also gets the LAUNCHER category, so the library opens from the app drawer.
 */
private val launcherPatch = resourcePatch(
    description = "Gives Honor Video Player's library a launcher icon.",
) {
    execute {
        var added = 0
        document("AndroidManifest.xml").use { manifest ->
            val activities = manifest.getElementsByTagName("activity")
            for (i in 0 until activities.length) {
                val activity = activities.item(i) as Element
                if (activity.getAttribute("android:name") != MAIN_ACTIVITY) continue
                for (filter in activity.childElements("intent-filter")) {
                    val actions = filter.childElements("action").map { it.getAttribute("android:name") }
                    val categories = filter.childElements("category").map { it.getAttribute("android:name") }
                    if ("android.intent.action.MAIN" !in actions || "android.intent.category.LAUNCHER" in categories) continue
                    filter.appendChild(
                        manifest.createElement("category").apply {
                            setAttribute("android:name", "android.intent.category.LAUNCHER")
                        },
                    )
                    added++
                }
            }
        }
        if (added != 1) throw PatchException("$MAIN_ACTIVITY's MAIN intent filter not found ($added)")
    }
}

private const val MAIN_ACTIVITY = "com.honor.videoplayer.MainActivity"

private fun Element.childElements(tag: String) =
    (0 until childNodes.length).map { childNodes.item(it) }.filterIsInstance<Element>().filter { it.tagName == tag }

/** PlayerViewModel.castToTv(Context): opens MagicOS's projection device picker. */
private object CastToTvFingerprint : Fingerprint(
    returnType = "V",
    parameters = listOf("Landroid/content/Context;"),
    strings = listOf("com.hihonor.android.airsharing.DEVICE_SELECTOR_CALLER"),
)

/** "Wireless Projection" opens Android's Cast screen where MagicOS's projection picker is missing (it crashed). */
private val projectionPatch = bytecodePatch(
    description = "Opens Android's Cast screen for Wireless Projection.",
) {
    extendWith(HONOR_VIDEO_PLAYER_EXTENSION)

    execute {
        CastToTvFingerprint.method.apply {
            val instructions = implementation!!.instructions.toList()
            val index = instructions.indexOfFirst { insn ->
                insn.opcode == Opcode.INVOKE_VIRTUAL &&
                    ((insn as ReferenceInstruction).reference as MethodReference).let {
                        it.name == "startActivityForResult" && it.parameterTypes == listOf("Landroid/content/Intent;", "I")
                    }
            }
            if (index < 0) throw PatchException("castToTv no longer starts the projection picker")
            val call = instructions[index] as FiveRegisterInstruction
            replaceInstruction(
                index,
                "invoke-static { v${call.registerC}, v${call.registerD}, v${call.registerE} }, " +
                    "$EXT/Projection;->startActivityForResult(Landroid/app/Activity;Landroid/content/Intent;I)V",
            )
        }
    }
}

/**
 * Off MagicOS Video Player has neither Honor's media library nor the MagicOS framework classes it calls. The extension
 * carries stand-ins for both (generated by .github/scripts/honor-standins; the library's player and extractor written on
 * Android's MediaPlayer, MediaExtractor and MediaMetadataRetriever). The library's own ExoPlayer adapter (HnExoPlayer)
 * is no way out: the app does not bundle ExoPlayer itself. On an Honor phone the library's and the framework's own
 * classes load first, so the stand-ins never run there.
 */
@Suppress("unused")
val enableNonMagicOsPatch = bytecodePatch(
    name = "Enable non-MagicOS ROM support",
    description = "Makes Honor Video Player installable and usable on non-Honor ROMs.",
    default = true,
) {
    compatibleWith(COMPATIBILITY_HONOR_VIDEO_PLAYER)
    dependsOn(optionalLibraryPatch, launcherPatch, projectionPatch)
    extendWith(HONOR_VIDEO_PLAYER_EXTENSION)
}
