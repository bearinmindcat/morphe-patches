package org.ungoogled.patches.sonyalbum.misc

import app.morphe.patcher.extensions.InstructionExtensions.addInstructionsWithLabels
import app.morphe.patcher.extensions.InstructionExtensions.removeInstruction
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.patch.resourcePatch
import app.morphe.patcher.util.smali.ExternalLabel
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import org.ungoogled.patches.samsung.makeLibrariesOptional
import org.ungoogled.patches.sonyalbum.SonyAlbumConstants.COMPATIBILITY_SONY_ALBUM

private val log = java.util.logging.Logger.getLogger("SonyAlbum")

private const val FACEBOOK_SDK = "Lcom/facebook/FacebookSdk;"
private const val ACCESS_TOKEN = "Lcom/facebook/AccessToken;"

/**
 * Album requires Xperia's com.sony.device library (it names nothing from it), so Android refuses to install it
 * elsewhere. Every other Sony library is already optional: Album probes each one by class name before using it
 * (DependencyManager), so off Xperia those features (DLNA, photo analyzer faces, Play Anywhere, illumination, Sony's
 * Idd usage reports) simply stay off. No stand-ins on purpose: they would make those probes answer "present".
 */
private val librariesPatch = resourcePatch(
    description = "Makes Album's Xperia libraries optional.",
) {
    execute {
        val required = makeLibrariesOptional()
        log.info("required libraries made optional: $required")
        // Measured against 9.9.A.0.20: one, com.sony.device.
        if (required == 0) throw PatchException("no required library found")
    }
}

/**
 * Each start Album clears an expired Facebook online-album cache, and the token handler it creates for that starts
 * Facebook's SDK, which fetches the app's settings from graph.facebook.com and flushes its event logger, without the
 * user ever signing in. This build has no Facebook sign-in left (its launchLogin is empty), so the SDK is no longer
 * started and the token lookup that needs it answers "no token".
 */
private val offlinePatch = bytecodePatch(
    description = "Keeps Album offline: Facebook's SDK is not started.",
) {
    execute {
        val handlerType = "Lcom/sonyericsson/album/online/socialcloud/facebook/FacebookTokenHandler;"
        val handler = mutableClassDefByOrNull(handlerType)
            ?: throw PatchException("FacebookTokenHandler not found")
        if (handler.fields.none { f -> f.name == "PERMISSION_KEY" }) {
            throw PatchException("FacebookTokenHandler changed: no PERMISSION_KEY")
        }

        val init = handler.methods.firstOrNull { it.name == "<init>" }
            ?: throw PatchException("FacebookTokenHandler constructor not found")
        val initIndex = init.implementation!!.instructions.indexOfFirst {
            it.opcode == Opcode.INVOKE_STATIC && ((it as ReferenceInstruction).reference as MethodReference).let { r ->
                r.definingClass == FACEBOOK_SDK && r.name == "sdkInitialize"
            }
        }
        if (initIndex < 0) throw PatchException("FacebookSdk.sdkInitialize call not found in FacebookTokenHandler")
        init.removeInstruction(initIndex)

        // getToken: Album's stored token first, else the SDK's current access token, which throws while the SDK is
        // not started: answered as "none" then.
        val getToken = handler.methods.firstOrNull {
            it.name == "getToken" && it.parameterTypes.isEmpty() && it.returnType == "Ljava/lang/String;"
        } ?: throw PatchException("FacebookTokenHandler.getToken not found")
        val instructions = getToken.implementation!!.instructions
        val tokenIndex = instructions.indexOfFirst {
            it.opcode == Opcode.INVOKE_STATIC && ((it as ReferenceInstruction).reference as MethodReference).let { r ->
                r.definingClass == ACCESS_TOKEN && r.name == "getCurrentAccessToken"
            }
        }
        if (tokenIndex < 0) throw PatchException("AccessToken.getCurrentAccessToken call not found in getToken")
        val moveResult = instructions[tokenIndex + 1]
        if (moveResult.opcode != Opcode.MOVE_RESULT_OBJECT) throw PatchException("getToken: no move-result after the token lookup")
        val r = (moveResult as OneRegisterInstruction).registerA
        getToken.addInstructionsWithLabels(
            tokenIndex,
            """
                invoke-static { }, $FACEBOOK_SDK->isInitialized()Z
                move-result v$r
                if-nez v$r, :sdk
                const/4 v$r, 0x0
                goto :after
            """,
            ExternalLabel("sdk", instructions[tokenIndex]),
            ExternalLabel("after", instructions[tokenIndex + 2]),
        )

        // Nothing else in Album starts the SDK (measured against 9.9.A.0.20).
        var starts = 0
        classDefForEach { c ->
            if (c.type.startsWith("Lcom/facebook/") || c.type == handlerType) return@classDefForEach
            for (m in c.methods) {
                m.implementation?.instructions?.forEach {
                    val ref = (it as? ReferenceInstruction)?.reference as? MethodReference ?: return@forEach
                    if (ref.definingClass == FACEBOOK_SDK && ref.name == "sdkInitialize") starts++
                }
            }
        }
        if (starts != 0) throw PatchException("$starts more FacebookSdk.sdkInitialize calls found")
    }
}

@Suppress("unused")
val enableNonXperiaPatch = bytecodePatch(
    name = "Enable non-Xperia ROM support",
    description = "Makes Sony Album usable on non-Xperia ROMs.",
    default = true,
) {
    compatibleWith(COMPATIBILITY_SONY_ALBUM)
    dependsOn(librariesPatch, offlinePatch)
}
