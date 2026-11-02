package org.ungoogled.patches.colorosweather.misc

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.extensions.InstructionExtensions.addInstructionsWithLabels
import app.morphe.patcher.extensions.InstructionExtensions.getInstruction
import app.morphe.patcher.extensions.InstructionExtensions.replaceInstruction
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.util.smali.ExternalLabel
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import org.ungoogled.patches.coloros.exportDynamicReceivers
import org.ungoogled.patches.colorosweather.ColorOsWeatherConstants.COMPATIBILITY_COLOROS_WEATHER

internal const val COLOROS_WEATHER_EXTENSION = "extensions/colorosweather.mpe"

private const val RUNTIME = "Lorg/ungoogled/colorosweather/ColorOS;"
private const val COE = "Lcom/oplus/weather/main/skin/WeatherCoeEngine;"

/** Loads the animated background's effect package into OPPO's COE engine (a coroutine; returns Unit when done). */
private object LoadWeatherCoeFingerprint : Fingerprint(
    definingClass = COE,
    name = "loadWeatherCOE2",
    returnType = "Ljava/lang/Object;",
)

/**
 * The emulator's GLES encoder overruns glGetProgramResourceiv's buffer and kills the process in the COE engine's render
 * thread once an effect is loaded (see EmulatorGl). There Weather now reports the effect as not loaded, as it does when
 * loading fails, and keeps its plain gradient background; elsewhere nothing changes.
 */
private val emulatorBackgroundPatch = bytecodePatch(
    description = "Keeps Weather's plain background where the emulator's GPU would kill its animated one.",
) {
    extendWith(COLOROS_WEATHER_EXTENSION)

    execute {
        LoadWeatherCoeFingerprint.method.apply {
            val finish = classDefBy(COE).methods.any {
                it.name == "updateCoeResFinish" && it.parameterTypes.map(CharSequence::toString) == listOf("Z")
            }
            if (!finish) throw PatchException("no updateCoeResFinish(boolean) in WeatherCoeEngine")
            addInstructionsWithLabels(
                0,
                """
                    invoke-static {}, Lorg/ungoogled/colorosweather/EmulatorGl;->active()Z
                    move-result v0
                    if-eqz v0, :load
                    sget-object v0, $COE->INSTANCE:$COE
                    const/4 v1, 0x0
                    invoke-direct {v0, v1}, $COE->updateCoeResFinish(Z)V
                    sget-object v0, Lkotlin/Unit;->INSTANCE:Lkotlin/Unit;
                    return-object v0
                """,
                ExternalLabel("load", getInstruction(0)),
            )
        }
    }
}

private const val PRIVACY = "Lcom/oplus/weather/privacy/PrivacyStatement;"

/** Loads one of Weather's cloud configs (NearX CloudConfig, product mdp_241) unless privacy isn't agreed. */
private object CloudConfigLoadFingerprint : Fingerprint(
    definingClass = "Lcom/oplus/weather/cloudconfig/CloudBaseConfig;",
    name = "loadConfig",
    returnType = "V",
    parameters = listOf(),
)

/** Loads the home page's layout config from the same cloud config service. */
private object CloudUiConfigLoadFingerprint : Fingerprint(
    definingClass = "Lcom/oplus/weather/cloudconfig/uiconfig/CloudUiConfig;",
    name = "loadCloudUiConfig",
    returnType = "V",
    parameters = listOf(),
)

/**
 * Weather loads its cloud configs (OPPO's NearX CloudConfig: cloudconf-app-*.heytapmobile.com, appconf-*.heytapdl.com)
 * as soon as it starts, behind PrivacyStatement.isPrivacyAgreed(), which this OnePlus build answers true outright: on
 * OxygenOS the phone's own setup covers it. Elsewhere nothing has been agreed yet when Weather first opens, so the loads
 * now wait for Weather's own "Statement of Use" (Agree and continue), the flag it already keeps for that dialog.
 */
private val cloudConfigConsentPatch = bytecodePatch(
    description = "Makes Weather wait for its Statement of Use before loading its cloud configs.",
) {
    execute {
        var moved = 0
        for (fingerprint in listOf(CloudConfigLoadFingerprint, CloudUiConfigLoadFingerprint)) {
            fingerprint.method.apply {
                val instructions = implementation!!.instructions.toList()
                val index = instructions.indexOfFirst {
                    it.opcode == Opcode.INVOKE_VIRTUAL &&
                        ((it as ReferenceInstruction).reference as? MethodReference)?.let { r ->
                            r.definingClass == PRIVACY && r.name == "isPrivacyAgreed"
                        } == true
                }
                if (index < 0) throw PatchException("no privacy check in ${fingerprint.javaClass.simpleName}")
                replaceInstruction(index, "invoke-static {}, $PRIVACY->isAgreedUserNeedKnow()Z")
                moved++
            }
        }
        if (moved != 2) throw PatchException("cloud config gates moved: $moved")
    }
}

private const val SYSTEM_PROP = "Lcom/oplus/weather/utils/SystemProp;"
private const val SERVICE_UTILS = "Lcom/oplus/weather/utils/WeatherServiceUtils;"
private const val WEATHER_SERVICE = "Lorg/ungoogled/colorosweather/WeatherService;"

/** Stores a unit picked in Settings; the temperature goes to ColorOS's Weather Service app below "ColorOS 13". */
private object PutUnitFingerprint : Fingerprint(
    definingClass = "Lcom/oplus/weather/service/WeatherSettingUtils\$SettingDataUnit;",
    name = "putUnit",
    returnType = "V",
    parameters = listOf("I", "I", "I"),
)

/** The region whose default units Weather uses: below "ColorOS 13" ColorOS's region mark, else China. */
private object UnitRegionFingerprint : Fingerprint(
    definingClass = "Lcom/oplus/weather/utils/LanguageCodeUtils;",
    name = "getUnitRegion",
    returnType = "Ljava/lang/String;",
    parameters = listOf(),
)

/** Shows "Enable Weather Services" when ColorOS's separate Weather Service app is off (or missing). */
private object ServiceCheckFingerprint : Fingerprint(
    definingClass = SERVICE_UTILS,
    name = "checkWeatherServiceEnabledImpl",
    returnType = "Z",
)

/** Points the call to [owner]->[name] in [fingerprint]'s method at [replacement] (an invoke-static line). */
private fun app.morphe.patcher.patch.BytecodePatchContext.redirect(
    fingerprint: Fingerprint, owner: String, name: String, replacement: String,
) {
    fingerprint.method.apply {
        val index = implementation!!.instructions.indexOfFirst {
            it.opcode == Opcode.INVOKE_STATIC &&
                ((it as ReferenceInstruction).reference as? MethodReference)?.let { r ->
                    r.definingClass == owner && r.name == name
                } == true
        }
        if (index < 0) throw PatchException("no call to $name in ${fingerprint.javaClass.simpleName}")
        replaceInstruction(index, replacement)
    }
}

/**
 * Off ColorOS there is no ColorOS version, so Weather's "ColorOS 13 or later" checks say no, and two of them send work to
 * ColorOS's separate Weather Service app (com.coloros.weather.service): a temperature unit picked in Settings never
 * changed, and default units came from China. Those two now take the ColorOS 13 path (stored in Weather's own settings,
 * units of the phone's country). Weather also asked to "Enable Weather Services" on every start because a missing
 * Weather Service app counts as switched off; only a phone that has the app is asked now. Everything else that checks
 * for the app (Weather uses its own built-in service when it is missing) is unchanged.
 */
private val weatherServicePatch = bytecodePatch(
    description = "Keeps Weather's settings and start-up off ColorOS's separate Weather Service app.",
) {
    extendWith(COLOROS_WEATHER_EXTENSION)

    execute {
        val builtIn = "invoke-static {}, $WEATHER_SERVICE->builtIn()Z"
        redirect(PutUnitFingerprint, SYSTEM_PROP, "isAboveOS13", builtIn)
        redirect(UnitRegionFingerprint, SYSTEM_PROP, "isAboveOS13", builtIn)
        redirect(
            ServiceCheckFingerprint, SERVICE_UTILS, "isWeatherServiceDisabled",
            "invoke-static {p0}, $WEATHER_SERVICE->switchedOff(Landroid/content/Context;)Z",
        )
    }
}

/**
 * Off ColorOS Weather dies at once on com.oplus.wrapper.os.SystemProperties (a ColorOS framework class). The extension
 * carries stand-ins for the ColorOS classes it uses (generated by .github/scripts/coloros-standins); on an OPPO / OnePlus
 * / realme phone the framework's own classes load first, so they never run there. Its broadcast receivers registered at
 * run time get the export flag Android 14+ demands of an installed app (see exportDynamicReceivers). Weather's data
 * comes from OPPO's weather server (weather-server.allawnos.com), which answers without any OPPO device identity.
 */
@Suppress("unused")
val enableNonColorOsPatch = bytecodePatch(
    name = "Enable non-ColorOS ROM support",
    description = "Makes OnePlus Weather installable and usable on non-ColorOS ROMs.",
    default = true,
) {
    compatibleWith(COMPATIBILITY_COLOROS_WEATHER)
    dependsOn(emulatorBackgroundPatch, cloudConfigConsentPatch, weatherServicePatch)
    extendWith(COLOROS_WEATHER_EXTENSION)

    execute {
        val receivers = exportDynamicReceivers(RUNTIME)
        java.util.logging.Logger.getLogger("OnePlusWeather").info("registerReceiver calls through ColorOS: $receivers")
        // Measured against 17.6.30: 22 calls.
        if (receivers < 22) throw PatchException("only $receivers registerReceiver calls found")
    }
}
