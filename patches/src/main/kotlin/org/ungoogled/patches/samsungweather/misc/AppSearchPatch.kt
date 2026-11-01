package org.ungoogled.patches.samsungweather.misc

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod.Companion.toMutable
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.builder.MutableMethodImplementation
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.iface.reference.TypeReference
import com.android.tools.smali.dexlib2.immutable.ImmutableMethod
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodParameter
import org.ungoogled.patches.samsung.redirectCalls

private const val APP_SEARCH = "Lorg/ungoogled/samsungweather/AppSearch;"
private const val BRIEF_WRITER = "Lcom/sec/android/daemonapp/data/source/SaveWeatherBriefAppSearchImpl;"

private fun Instruction.methodRef() = (this as? ReferenceInstruction)?.reference as? MethodReference

private fun MethodReference.key() = "$definingClass->$name(${parameterTypes.joinToString("")})$returnType"

/**
 * The AppSearch library's platform session (androidx.appsearch platform-storage): its setSchema throws
 * UnsupportedOperationException for any schema feature the phone's AppSearch lacks, parent types first.
 */
private object PlatformSetSchemaFingerprint : Fingerprint(
    returnType = "L",
    parameters = listOf("L"),
    strings = listOf("SCHEMA_ADD_PARENT_TYPE is not available on this AppSearch implementation."),
    custom = { _, classDef -> classDef.fields.any { it.type == "Landroid/app/appsearch/AppSearchSession;" } },
)

/**
 * The library's session opener, AppSearchManager.createSearchSession (R8 outlines that call into a helper taking the
 * manager and its SearchContext): a class Android 11 does not have.
 */
private object PlatformCreateSessionFingerprint : Fingerprint(
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.STATIC),
    returnType = "L",
    parameters = listOf("L"),
    custom = { method, _ ->
        method.implementation?.instructions?.any { insn ->
            insn.methodRef()?.parameterTypes?.any { it.toString() == "Landroid/app/appsearch/AppSearchManager\$SearchContext;" } == true
        } == true
    },
)

/**
 * Weather's start-up writes a "weather brief" into Android's on-device search index for One UI's Now Brief (and a
 * forecast-change brief on refresh), with a schema that uses parent types. The AppSearch library Weather bundles only
 * allows those on Android 15 (SDK 35) and newer and otherwise throws, outside the catch Weather has around it, so on
 * Android 12 to 14 Weather died on every start; on Android 11 there is no index at all and opening it throws instead.
 * Both library calls now go through generated methods that hand that refusal back as the library's own failed result
 * (an AppSearchException in a done future), which Weather's code already catches and logs before skipping the brief.
 * Where the phone supports it the calls are unchanged. Nothing reads that index off One UI.
 */
internal val appSearchPatch = bytecodePatch(
    description = "Skips Weather's Now Brief search index where the phone's AppSearch cannot hold it.",
) {
    extendWith(SAMSUNG_WEATHER_EXTENSION)

    execute {
        val setSchema = PlatformSetSchemaFingerprint.method
        val createSession = PlatformCreateSessionFingerprint.method
        val future = setSchema.returnType
        if (createSession.returnType != future) {
            throw PatchException("AppSearch session opener returns ${createSession.returnType}, setSchema $future")
        }

        // The library's own way to fail a call, read from setSchema's handler for a schema over the size limit:
        // a new future, an AppSearchException(code, message, cause) and future.setException(exception).
        val body = setSchema.implementation!!.instructions.toList()
        val futureNew = body.indexOfFirst {
            it.opcode == Opcode.NEW_INSTANCE && ((it as ReferenceInstruction).reference as TypeReference).type == future
        }
        val futureInit = body.drop(futureNew + 1).firstOrNull { it.opcode == Opcode.INVOKE_DIRECT }?.methodRef()
        val exceptionInit = body.firstNotNullOfOrNull { insn ->
            insn.methodRef()?.takeIf {
                insn.opcode == Opcode.INVOKE_DIRECT && it.name == "<init>" &&
                    it.parameterTypes.map(CharSequence::toString) == listOf("I", "Ljava/lang/String;", "Ljava/lang/Exception;")
            }
        }
        val setException = body.firstNotNullOfOrNull { insn ->
            insn.methodRef()?.takeIf {
                insn.opcode == Opcode.INVOKE_VIRTUAL && it.returnType == "Z" &&
                    it.parameterTypes.map(CharSequence::toString) == listOf("Ljava/lang/Throwable;")
            }
        }
        if (futureNew < 0 || futureInit == null || futureInit.name != "<init>" || exceptionInit == null || setException == null) {
            throw PatchException("AppSearch setSchema no longer fails a future with an AppSearchException")
        }
        val exception = exceptionInit.definingClass

        // That exception has to be the one Weather catches around its brief's setSchema and session opening.
        val writer = mutableClassDefBy(BRIEF_WRITER)
        for (name in listOf("setSchema", "createAppSearchSession")) {
            val method = writer.methods.firstOrNull { it.name == name }
                ?: throw PatchException("no $name in the weather brief writer")
            if (method.implementation?.tryBlocks?.any { t -> t.exceptionHandlers.any { it.exceptionType == exception } } != true) {
                throw PatchException("the weather brief writer's $name no longer catches $exception")
            }
        }

        val shims = mutableClassDefBy(APP_SEARCH)
        fun shim(name: String, target: Method, call: String, catches: List<String>): String {
            val parameters = target.parameterTypes.map(CharSequence::toString).let {
                if (AccessFlags.STATIC.isSet(target.accessFlags)) it else listOf(target.definingClass) + it
            }
            val method = ImmutableMethod(
                APP_SEARCH, name, parameters.map { ImmutableMethodParameter(it, null, null) }, future,
                AccessFlags.PUBLIC.value or AccessFlags.STATIC.value, null, null,
                MutableMethodImplementation(5 + parameters.size),
            ).toMutable()
            method.addInstructions(
                0,
                """
                    $call
                    move-result-object v0
                    return-object v0
                    move-exception v0
                    invoke-static { v0 }, $APP_SEARCH->skipped(Ljava/lang/Throwable;)Ljava/lang/String;
                    move-result-object v0
                    new-instance v1, $future
                    invoke-direct { v1 }, ${futureInit.key()}
                    new-instance v2, $exception
                    const/4 v3, 0x1
                    const/4 v4, 0x0
                    invoke-direct { v2, v3, v0, v4 }, ${exceptionInit.key()}
                    invoke-virtual { v1, v2 }, ${setException.key()}
                    return-object v1
                """,
            )
            // The call and its move-result (indices 0..1) are covered; the handler starts at the move-exception.
            val implementation = method.implementation!!
            for (type in catches) {
                implementation.addCatch(
                    type, implementation.newLabelForIndex(0), implementation.newLabelForIndex(2),
                    implementation.newLabelForIndex(3),
                )
            }
            shims.methods.add(method)
            return "$APP_SEARCH->$name(${parameters.joinToString("")})$future"
        }

        val setSchemaKey = "${setSchema.definingClass}->${setSchema.name}(${setSchema.parameterTypes.joinToString("")})$future"
        val createSessionKey = "${createSession.definingClass}->${createSession.name}(${createSession.parameterTypes.joinToString("")})$future"
        val moved = redirectCalls(
            mapOf(
                setSchemaKey to shim(
                    "setSchema", setSchema, "invoke-virtual { p0, p1 }, $setSchemaKey",
                    listOf("Ljava/lang/UnsupportedOperationException;"),
                ),
                createSessionKey to shim(
                    "createSession", createSession, "invoke-static { p0 }, $createSessionKey",
                    listOf("Ljava/lang/LinkageError;", "Ljava/lang/RuntimeException;"),
                ),
            ),
        )
        java.util.logging.Logger.getLogger("SamsungWeather").info("AppSearch calls guarded: $moved")
        // Measured against 1.7.30.50: 3 setSchema calls and 4 session openings (two briefs, the delete, the sync).
        if (moved < 5) throw PatchException("only $moved AppSearch setSchema / session calls found")
    }
}
