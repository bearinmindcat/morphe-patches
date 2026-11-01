package org.ungoogled.patches.samsungweather.misc

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.bytecodePatch
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import org.ungoogled.patches.samsung.redirectMembers

/** Android 14's: who started an activity. Weather asks every time it opens a forecast. */
private val LAUNCHED_FROM = setOf("getLaunchedFromPackage", "getLaunchedFromUid")

/** A shader view of the forecast screen's sky and weather effects taking its layer (an AGSL RuntimeShader). */
private object ShaderLayerFingerprint : Fingerprint(
    definingClass = "Lcom/iux/agsl_shader/view/AGSLShaderView;",
    returnType = "V",
    parameters = listOf("Lcom/iux/agsl_shader/view/CanvasLayer;"),
    custom = { method, _ ->
        method.implementation?.instructions?.any {
            ((it as? ReferenceInstruction)?.reference as? MethodReference)?.returnType == "Landroid/graphics/RuntimeShader;"
        } == true
    },
)

/**
 * Weather is built for One UI 7 (Android 15) and calls a few newer Android additions without checking the version:
 * opening a forecast asks Activity.getLaunchedFromPackage (Android 14), which killed it on Android 12 and 13, and the
 * forecast screen's sky effects build Android 13 shaders. Those calls now go through OneUi's reflection (the real call
 * where the phone has it, "unknown launcher" where not), and below Android 13 the effects are left out.
 */
internal val olderAndroidPatch = bytecodePatch(
    description = "Answers the Android 13 and 14 additions Weather calls on older Android.",
) {
    extendWith(SAMSUNG_WEATHER_EXTENSION)

    execute {
        val (calls, _) = redirectMembers("Lorg/ungoogled/samsungweather/OneUi;", "Lorg/ungoogled/samsungweather/Api;") { owner, name ->
            owner == "Landroid/app/Activity;" && name in LAUNCHED_FROM
        }
        java.util.logging.Logger.getLogger("SamsungWeather").info("Android 14 activity calls: $calls")
        // Measured against 1.7.30.50: 3 (the forecast screen, the launcher's and the widget splash's caller checks).
        if (calls < 2) throw PatchException("only $calls getLaunchedFrom... calls found")

        ShaderLayerFingerprint.method.addInstructions(
            0,
            """
                invoke-static/range { p1 .. p1 }, Lorg/ungoogled/samsungweather/Shaders;->layer(Ljava/lang/Object;)Ljava/lang/Object;
                move-result-object p1
                check-cast p1, Lcom/iux/agsl_shader/view/CanvasLayer;
            """,
        )
    }
}
