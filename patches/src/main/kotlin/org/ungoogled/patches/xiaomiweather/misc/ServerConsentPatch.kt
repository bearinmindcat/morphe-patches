package org.ungoogled.patches.xiaomiweather.misc

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.extensions.InstructionExtensions.addInstruction
import app.morphe.patcher.extensions.InstructionExtensions.addInstructionsWithLabels
import app.morphe.patcher.extensions.InstructionExtensions.getInstruction
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import app.morphe.patcher.util.smali.ExternalLabel
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.iface.reference.StringReference

private const val CONSENT = "Lorg/ungoogled/xiaomiweather/Consent;"

/** OkHttp's RealCall.execute(): the class every request of Weather's HTTP clients runs through. */
private object RealCallExecuteFingerprint : Fingerprint(
    name = "execute",
    parameters = listOf(),
    strings = listOf("Already Executed"),
)

/** WeatherApplication.onCreate(). */
private object ApplicationCreateFingerprint : Fingerprint(
    name = "onCreate",
    returnType = "V",
    parameters = listOf(),
    strings = listOf("application return, user is not agree"),
)

/** ActivityWeatherMain.onResume(). */
private object MainResumeFingerprint : Fingerprint(
    name = "onResume",
    returnType = "V",
    parameters = listOf(),
    strings = listOf("WeatherActivity onResume"),
)

/** UserNoticeUtil's "has the user agreed" check (app_already_run): identifies Weather's notice class. */
private object NoticeAgreedFingerprint : Fingerprint(
    returnType = "Z",
    parameters = listOf("Landroid/content/Context;"),
    strings = listOf("com.miui.providers.weather.apprun", "app_already_run"),
)

/** The main screen's pull-to-refresh handler. */
private object PullToRefreshFingerprint : Fingerprint(
    returnType = "V",
    parameters = listOf("I"),
    strings = listOf("pull_to_refresh_home"),
)

/** UpdateWeatherReceiver.onReceive(): boot, wake-up and scheduled forecast updates. */
private object UpdateReceiverFingerprint : Fingerprint(
    name = "onReceive",
    returnType = "V",
    strings = listOf("onReceive() return, user is not agree to run"),
)

private fun MutableMethod.freeRegisters(): Int = implementation!!.registerCount - parameterTypes.size - 1

/**
 * Off HyperOS Weather took Xiaomi's notice as accepted and went straight to Xiaomi's weather server (location, device
 * and ROM details) on first launch and on every boot. Now the main screen asks once (Consent); until the user allows
 * it, every request of Weather's HTTP clients fails as if offline and scheduled updates don't run; pulling the weather
 * screen down or adding a city asks again. Weather's own first-run steps (location prompt, first forecast) wait for the
 * answer.
 */
internal val serverConsentPatch = bytecodePatch(
    description = "Asks before Weather uses Xiaomi's weather server.",
) {
    extendWith(XIAOMI_WEATHER_EXTENSION)

    execute {
        // Every request: OkHttp's interceptor chain (RealCall, run by execute() and by asynchronous calls) fails
        // before it opens a connection.
        val call = mutableClassDefBy(RealCallExecuteFingerprint.classDef.type)
        val response = RealCallExecuteFingerprint.method.returnType
        val chain = call.methods.singleOrNull { m ->
            m.parameterTypes.isEmpty() && m.returnType == response && m.name != "execute" &&
                m.implementation?.instructions?.any {
                    ((it as? ReferenceInstruction)?.reference as? StringReference)?.string == "Canceled"
                } == true
        } ?: throw PatchException("OkHttp's interceptor chain not found in ${call.type}")
        if (chain.freeRegisters() < 2) throw PatchException("OkHttp's interceptor chain has no free registers")
        chain.addInstructionsWithLabels(
            0,
            """
                invoke-static { p0 }, $CONSENT->request(Ljava/lang/Object;)Z
                move-result v0
                if-nez v0, :servers_allowed
                new-instance v0, Ljava/io/IOException;
                const-string v1, "Xiaomi's weather server is not allowed"
                invoke-direct { v0, v1 }, Ljava/io/IOException;-><init>(Ljava/lang/String;)V
                throw v0
            """,
            ExternalLabel("servers_allowed", chain.getInstruction(0)),
        )

        // The answer is read with the application's context.
        ApplicationCreateFingerprint.method.addInstruction(0, "invoke-static { p0 }, $CONSENT->init(Landroid/content/Context;)V")

        // The main screen asks once.
        MainResumeFingerprint.method.apply {
            val superCall = implementation!!.instructions.indexOfFirst { it.opcode == Opcode.INVOKE_SUPER }
            if (superCall < 0) throw PatchException("ActivityWeatherMain.onResume has no super call")
            addInstruction(superCall + 1, "invoke-static { p0 }, $CONSENT->attach(Landroid/app/Activity;)V")
        }

        // Weather's first-run step (its notice, then the location prompt and the first forecast) waits for the answer:
        // the main screen's Runnable that calls UserNoticeUtil's (Context, callback) step.
        val notice = NoticeAgreedFingerprint.classDef.type
        val steps = mutableListOf<String>()
        classDefForEach { c ->
            if (!c.type.startsWith("Lcom/miui/weather2/ActivityWeatherMain$")) return@classDefForEach
            if (c.methods.any { m ->
                    m.name == "run" && m.parameterTypes.isEmpty() && m.returnType == "V" &&
                        m.implementation?.instructions?.any {
                            val r = (it as? ReferenceInstruction)?.reference as? MethodReference
                            it.opcode == Opcode.INVOKE_STATIC && r?.definingClass == notice &&
                                r.parameterTypes.size == 2 && r.parameterTypes[0].toString() == "Landroid/content/Context;"
                        } == true
                }
            ) steps += c.type
        }
        // Measured against G-17.0.3.26: one (ActivityWeatherMain's notice step, posted from onCreate).
        val step = steps.singleOrNull() ?: throw PatchException("expected one first-run notice step, found ${steps.size}")
        mutableClassDefBy(step).methods.first { it.name == "run" && it.parameterTypes.isEmpty() }.apply {
            if (freeRegisters() < 1) throw PatchException("first-run notice step has no free register")
            addInstructionsWithLabels(
                0,
                """
                    invoke-static { p0 }, $CONSENT->defer(Ljava/lang/Runnable;)Z
                    move-result v0
                    if-eqz v0, :step_now
                    return-void
                """,
                ExternalLabel("step_now", getInstruction(0)),
            )
        }

        // Pull to refresh asks again after "Don't allow".
        PullToRefreshFingerprint.method.addInstruction(0, "invoke-static { }, $CONSENT->offer()V")

        // Scheduled and boot-time updates.
        UpdateReceiverFingerprint.method.apply {
            if (freeRegisters() < 1) throw PatchException("UpdateWeatherReceiver.onReceive has no free register")
            addInstructionsWithLabels(
                0,
                """
                    invoke-static { p1 }, $CONSENT->init(Landroid/content/Context;)V
                    invoke-static { }, $CONSENT->network()Z
                    move-result v0
                    if-nez v0, :updates_allowed
                    return-void
                """,
                ExternalLabel("updates_allowed", getInstruction(0)),
            )
        }
    }
}
