package org.ungoogled.patches.maps.network

import app.morphe.patcher.extensions.InstructionExtensions.addInstructionsWithLabels
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.util.smali.ExternalLabel
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.*
import org.ungoogled.patches.maps.ui.sharedExtensionPatch
import org.ungoogled.patches.shared.Constants.COMPATIBILITY_MAPS
import java.security.MessageDigest
import java.util.Locale

private const val JAVA_PROVIDER = "Lorg/chromium/net/impl/JavaCronetProvider;"
private const val SYSTEM_PROVIDER = "Lorg/chromium/net/impl/HttpEngineNativeProvider;"
private const val BUILDER = "Lorg/chromium/net/CronetEngine\$Builder;"
private const val ORIGINAL_FALLBACK = "5978fd0da3dc59c0056a19bd13cd0dce8752152944c84d58799bdd3cff53cada"

/**
 * Only changes the Java fallback, not provider discovery or a working GMS engine.
 * Maps already bundles an adapter for Android's system HttpEngine. Prefer it when
 * available, but keep the original body for older Android and configured proxies:
 * the system adapter does not expose the proxy API used by the extension.
 */
@Suppress("unused")
val systemCronetFallbackPatch = bytecodePatch(
    name = "Use system Cronet fallback",
    description = "Prefers Android's system HttpEngine when Maps falls back to Java Cronet. " +
        "Keeps working Play services engines and the original fallback when the system " +
        "provider is unavailable or an app proxy is configured.",
    default = true,
) {
    compatibleWith(COMPATIBILITY_MAPS)
    dependsOn(sharedExtensionPatch)

    execute {
        val fallback = mutableClassDefBy(JAVA_PROVIDER).methods.singleOrNull {
            it.name == "createBuilder" && it.parameterTypes.isEmpty() && it.returnType == BUILDER
        } ?: throw PatchException("Java Cronet fallback builder not found")
        if (fallback.implementation == null || fallbackShape(fallback) != ORIGINAL_FALLBACK) {
            throw PatchException("Unexpected or already modified Java Cronet fallback")
        }

        val provider = classDefBy(SYSTEM_PROVIDER)
        listOf("isEnabled" to "Z", "createBuilder" to BUILDER).forEach { (name, result) ->
            if (provider.methods.none {
                it.name == name && it.parameterTypes.isEmpty() && it.returnType == result
            }) {
                throw PatchException("Missing system Cronet provider method: $name")
            }
        }
        if (provider.methods.none {
            it.name == "<init>" && it.parameterTypes == listOf("Landroid/content/Context;")
        }) {
            throw PatchException("Missing system Cronet provider constructor")
        }

        // The exact gate also fixes the register layout: v2/p0 is this, v0/v1 are
        // scratch registers. Keep every instruction and branch in the old tail.
        fallback.addInstructionsWithLabels(
            0,
            """
                const-string v0, "UGMapsCronet"
                const-string v1, "Java fallback requested"
                invoke-static { v0, v1 }, Landroid/util/Log;->i(Ljava/lang/String;Ljava/lang/String;)I
                invoke-static { }, Lorg/ungoogled/ui/Shapes;->proxyEffective()Ljava/lang/String;
                move-result-object v0
                invoke-virtual { v0 }, Ljava/lang/String;->isEmpty()Z
                move-result v0
                if-eqz v0, :original
                new-instance v0, $SYSTEM_PROVIDER
                iget-object v1, p0, $JAVA_PROVIDER->mContext:Landroid/content/Context;
                invoke-direct { v0, v1 }, $SYSTEM_PROVIDER-><init>(Landroid/content/Context;)V
                invoke-virtual { v0 }, $SYSTEM_PROVIDER->isEnabled()Z
                move-result v1
                if-eqz v1, :original
                invoke-virtual { v0 }, $SYSTEM_PROVIDER->createBuilder()$BUILDER
                move-result-object v0
                const-string v1, "UGMapsCronet"
                const-string p0, "Using system HttpEngine instead of Java fallback"
                invoke-static { v1, p0 }, Landroid/util/Log;->i(Ljava/lang/String;Ljava/lang/String;)I
                return-object v0
            """,
            ExternalLabel("original", fallback.implementation!!.instructions.first()),
        )
    }
}

/** Exact, DEX-order-independent gate including register operands and jump targets. */
private fun fallbackShape(method: Method): String {
    val implementation = method.implementation!!
    val shape = buildString {
        append(method.accessFlags).append(':').append(implementation.registerCount).append('\n')
        implementation.instructions.forEach { instruction ->
            // Opcode.name is the smali mnemonic; use the enum name for this gate.
            append(instruction.opcode.toString())
            if (instruction is OneRegisterInstruction) append(" A=").append(instruction.registerA)
            if (instruction is TwoRegisterInstruction) append(" B=").append(instruction.registerB)
            if (instruction is ThreeRegisterInstruction) append(" C=").append(instruction.registerC)
            if (instruction is FiveRegisterInstruction) {
                append(" n=").append(instruction.registerCount)
                append(" C=").append(instruction.registerC)
                append(" D=").append(instruction.registerD)
                append(" E=").append(instruction.registerE)
                append(" F=").append(instruction.registerF)
                append(" G=").append(instruction.registerG)
            }
            if (instruction is RegisterRangeInstruction) {
                append(" n=").append(instruction.registerCount)
                append(" start=").append(instruction.startRegister)
            }
            if (instruction is ReferenceInstruction) append(" ref=").append(instruction.reference)
            if (instruction is DualReferenceInstruction) append(" ref2=").append(instruction.reference2)
            if (instruction is OffsetInstruction) append(" offset=").append(instruction.codeOffset)
            if (instruction is WideLiteralInstruction) append(" literal=").append(instruction.wideLiteral)
            append('\n')
        }
    }
    return MessageDigest.getInstance("SHA-256").digest(shape.toByteArray(Charsets.UTF_8))
        .joinToString("") { String.format(Locale.ROOT, "%02x", it.toInt() and 0xff) }
}
