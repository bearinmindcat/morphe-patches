package org.ungoogled.patches.waze

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.extensions.InstructionExtensions.addInstructionsWithLabels
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.patch.intOption
import app.morphe.patcher.patch.stringOption
import app.morphe.patcher.util.smali.ExternalLabel
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import org.ungoogled.patches.waze.WazeConstants.COMPATIBILITY_WAZE

// Waze's own server decides these per country and per experiment; each patch keeps its values
// after every sync (see wazeConfigOverridesPatch). They are the settings the old "Waze mod"
// preferences files changed -- those files are no longer read by Waze 5.24.

/** Waze's own ad server ("Advil") is asked for ads with the page they are for. */
private const val ADVIL_REQUEST = "Lcom/waze/jni/protos/AdvilRequest;"

@Suppress("unused")
val wazeHideAdsPatch = bytecodePatch(
    name = "Hide ads",
    description = "Removes the sponsored pins on the map and on Android Auto, the full-screen ads shown " +
        "while you are stopped, and the ad statistics Waze sends.",
) {
    compatibleWith(COMPATIBILITY_WAZE)
    dependsOn(wazeConfigOverridesPatch)

    execute {
        // The page an ad is requested for is blank while Hide ads is switched on, so Waze's ad server has
        // nothing to answer. The getter is `iget-object p0, p0, ->pageUrl_; return-object p0` on one
        // register; the field's value goes through Customization.pageUrl on its way out.
        val pageUrl = mutableClassDefBy(ADVIL_REQUEST).methods.singleOrNull {
            it.name == "getPageUrl" && it.parameterTypes.isEmpty() && it.returnType == "Ljava/lang/String;"
        } ?: throw PatchException("$ADVIL_REQUEST.getPageUrl() not found")
        val getter = pageUrl.implementation!!.instructions.toList()
        if (getter.size != 2 || getter[0].opcode != Opcode.IGET_OBJECT || getter[1].opcode != Opcode.RETURN_OBJECT) {
            throw PatchException("$ADVIL_REQUEST.getPageUrl() is not a plain getter any more")
        }
        val url = (getter[1] as OneRegisterInstruction).registerA
        pageUrl.addInstructions(
            1,
            """
                invoke-static { v$url }, $WAZE_CUSTOMIZATION->pageUrl(Ljava/lang/String;)Ljava/lang/String;
                move-result-object v$url
            """,
        )

        keepWazeConfig("hideAds", "CONFIG_VALUE_GOOGLE_ADS_FEATURE_ENABLED", false)
        keepWazeConfig("hideAds", "CONFIG_VALUE_GOOGLE_ADS_IN_CAR_ENABLED", false)
        keepWazeConfig("hideAds", "CONFIG_VALUE_GOOGLE_ADS_DISCOVERY_PINS_ENABLED", false)
        keepWazeConfig("hideAds", "CONFIG_VALUE_GOOGLE_ADS_MAX_CONCURRENT_PINS", 0)
        keepWazeConfig("hideAds", "CONFIG_VALUE_GOOGLE_ADS_IN_CAR_MAX_CONCURRENT_PINS", 0)
        // A stopped car is shown a "takeover" ad at most this often: about once a lifetime.
        keepWazeConfig("hideAds", "CONFIG_VALUE_ZSPEED_MIN_TAKEOVER_REQUEST_INTERVAL_SEC", 2_000_000_000)
        keepWazeConfig("hideAds", "CONFIG_VALUE_ADS_INVENTORY_PREDICTION_ENABLED", false)
        keepWazeConfig("hideAds", "CONFIG_VALUE_AD_EVENTS_FEATURE_ENABLED", false)
        keepWazeConfig("hideAds", "CONFIG_VALUE_ADS_ALLOW_PROFILE_TARGETING_DEFAULT", false)
        keepWazeConfig("hideAds", "CONFIG_VALUE_ADS_ALLOW_PROFILE_TARGETING", false)
        // Partner pins and coupons ("external POIs"), and the promotional splash screens Waze's
        // server can switch on at start (OFF, EVERY_COLD_START or DAILY).
        keepWazeConfig("hideAds", "CONFIG_VALUE_EXTERNALPOI_MAX_POIS_DISPLAY", 0)
        keepWazeConfig("hideAds", "CONFIG_VALUE_APP_LAUNCH_DYNAMIC_SPLASH_SCREEN_SHOW_FREQUENCY", "OFF")
    }
}

@Suppress("unused")
val wazeNoGoogleAssistantPatch = bytecodePatch(
    name = "No Google Assistant",
    description = "Keeps Google Assistant out of Waze: no Assistant button, onboarding or settings entry, whatever " +
        "Waze's server enables for your region.",
    default = false,
) {
    compatibleWith(COMPATIBILITY_WAZE)
    dependsOn(wazeConfigOverridesPatch)

    execute {
        keepWazeConfig("noAssistant", "CONFIG_VALUE_GOOGLE_ASSISTANT_WAZE_FEATURE_ENABLED", false)
    }
}

@Suppress("unused")
val wazeNoAiReportingPatch = bytecodePatch(
    name = "No AI reporting",
    description = "Turns off conversational reporting, Waze's Gemini voice assistant for reports, together with " +
        "its activation dialog, tips and onboarding.",
    default = false,
) {
    compatibleWith(COMPATIBILITY_WAZE)
    dependsOn(wazeConfigOverridesPatch)

    execute {
        for (name in listOf(
            "CONFIG_VALUE_REPORTING_CONVERSATIONAL_REPORTING_ENABLED",
            "CONFIG_VALUE_REPORTING_CONVERSATIONAL_REPORTING_IN_CAR_ENABLED",
            "CONFIG_VALUE_REPORTING_CONVERSATIONAL_REPORTING_AGENT_ENABLED",
            "CONFIG_VALUE_REPORTING_CONVERSATIONAL_REPORTING_ACTIVATION_DIALOG_ENABLED",
            "CONFIG_VALUE_REPORTING_CONVERSATIONAL_REPORTING_FTE_TOOLTIP_ENABLED",
            "CONFIG_VALUE_REPORTING_CONVERSATIONAL_REPORTING_BUTTON_EDUCATION_ENABLED",
            "CONFIG_VALUE_REPORTING_CONVERSATIONAL_REPORTING_REPORT_MENU_ONBOARDING_ENABLED",
            "CONFIG_VALUE_REPORTING_CONVERSATIONAL_REPORTING_NON_FTE_HINT_ENABLED",
            "CONFIG_VALUE_REPORTING_CONVERSATIONAL_REPORTING_REPORT_BUTTON_FTE_HINT_ENABLED",
            "CONFIG_VALUE_REPORTING_CONVERSATIONAL_REPORTING_REPORT_MENU_FTE_HINT_ENABLED",
            "CONFIG_VALUE_REPORTING_CONVERSATIONAL_REPORTING_AGENT_FTE_HINT_ENABLED",
        )) keepWazeConfig("noAiReporting", name, false)
    }
}

/** MoodManager.canSetMood(Context, String): whether the user has earned a mood. A JNI class, so the names survive. */
private object CanSetMoodFingerprint : Fingerprint(
    definingClass = "Lcom/waze/MoodManager;",
    name = "canSetMood",
    returnType = "Z",
    parameters = listOf("Landroid/content/Context;", "Ljava/lang/String;"),
)

@Suppress("unused")
val wazeAllMoodsPatch = bytecodePatch(
    name = "All moods",
    description = "Every mood can be chosen: the ones Waze keeps for higher ranks and the beta moods it hides.",
    default = false,
) {
    compatibleWith(COMPATIBILITY_WAZE)
    dependsOn(wazeConfigOverridesPatch)

    execute {
        keepWazeConfig("allMoods", "CONFIG_VALUE_MOODS_BETA_ENABLED", true)
        // Every mood may be set while All moods is switched on; Waze's own check otherwise.
        CanSetMoodFingerprint.method.apply {
            addInstructionsWithLabels(
                0,
                """
                    const-string v0, "allMoods"
                    invoke-static { v0 }, $WAZE_CUSTOMIZATION->on(Ljava/lang/String;)Z
                    move-result v0
                    if-eqz v0, :waze_check
                    return v0
                """,
                ExternalLabel("waze_check", implementation!!.instructions.first()),
            )
        }
    }
}

@Suppress("unused")
val wazeReportSpeedLimitPatch = bytecodePatch(
    name = "Report speed limit",
    description = "Turns on Waze's own switch for correcting the speed limit from the speedometer, the old mods' " +
        "\"Report speed limit\". Waze's server keeps it off; what it shows is not verified here.",
    default = false,
) {
    compatibleWith(COMPATIBILITY_WAZE)
    dependsOn(wazeConfigOverridesPatch)

    execute {
        keepWazeConfig("reportSpeedLimit", "CONFIG_VALUE_MAP_SPEEDOMETER_SPEED_LIMIT_OVERRIDE_ENABLED", true)
    }
}

@Suppress("unused")
val wazeSpeedCamerasEverywherePatch = bytecodePatch(
    name = "Speed cameras everywhere",
    description = "Turns speed camera and police alerts back on in the countries where Waze's server " +
        "switches them off. Warning of speed cameras is against the law in some of those countries.",
    default = false,
) {
    compatibleWith(COMPATIBILITY_WAZE)
    dependsOn(wazeConfigOverridesPatch)

    execute {
        keepWazeConfig("camerasEverywhere", "CONFIG_VALUE_ALERTS_ENABLE_ENFORCEMENT_ALERTS", true)
        keepWazeConfig("camerasEverywhere", "CONFIG_VALUE_ALERTS_ENABLE_ENFORCEMENT_POLICE", "yes")
    }
}

@Suppress("unused")
val wazeCameraSoundAnySpeedPatch = bytecodePatch(
    name = "Speed camera sound at any speed",
    description = "Plays the speed camera sound for every camera, not only when you are over the speed limit.",
) {
    compatibleWith(COMPATIBILITY_WAZE)
    dependsOn(wazeConfigOverridesPatch)

    execute {
        keepWazeConfig("cameraSoundAnySpeed", "CONFIG_VALUE_ALERTS_PLAY_SPEED_CAMERA_SOUND_BELOW_SPEED_LIMIT", true)
    }
}

@Suppress("unused")
val wazeAlertDistancesPatch = bytecodePatch(
    name = "Alert distances",
    description = "How far ahead, in metres, Waze warns of police, hazards, crashes, traffic jams and speed " +
        "cameras. The defaults are the old Waze mods' (CGE); Waze's own are lower for most of them." +
        " They can be changed later in Customization, at the bottom of Waze's side menu.",
    default = false,
) {
    compatibleWith(COMPATIBILITY_WAZE)
    dependsOn(wazeConfigOverridesPatch)

    val police by intOption(key = "police", default = 1000, title = "Police", description = "Waze: 600 m.")
    val hazard by intOption(key = "hazard", default = 500, title = "Hazards", description = "Waze: 600 to 900 m.")
    val accident by intOption(key = "accident", default = 2000, title = "Crashes", description = "Waze: 600 m.")
    val traffic by intOption(key = "traffic", default = 3000, title = "Traffic jams", description = "Waze: 600 m.")
    val streets by intOption(key = "streets", default = 700, title = "Speed cameras on streets", description = "Waze: 500 m.")
    val highways by intOption(key = "highways", default = 900, title = "Speed cameras on highways", description = "Waze: 1000 m.")
    val freeways by intOption(key = "freeways", default = 1200, title = "Speed cameras on freeways", description = "Waze: 2000 m.")
    val blockedLane by intOption(key = "blockedLane", default = 1000, title = "Blocked lanes", description = "Waze: 400 m.")
    val between by intOption(key = "between", default = 200, title = "Least distance between two alerts", description = "Waze: 300 m.")

    execute {
        police?.let { keepWazeConfig("alertDistances", "CONFIG_VALUE_ALERTS_POLICE_ALERT_DISTANCE_METERS", it) }
        blockedLane?.let { keepWazeConfig("alertDistances", "CONFIG_VALUE_ALERTS_BLOCKED_LANE_ALERT_DISTANCE", it) }
        between?.let { keepWazeConfig("alertDistances", "CONFIG_VALUE_ALERTS_DISTANCE_BETWEEN_ALERTS", it) }
        hazard?.let { keepWazeConfig("alertDistances", "CONFIG_VALUE_ALERTS_HAZARD_ALERT_DISTANCE_METERS", it) }
        accident?.let { keepWazeConfig("alertDistances", "CONFIG_VALUE_ALERTS_ACCIDENT_ALERT_DISTANCE_METERS", it) }
        traffic?.let { keepWazeConfig("alertDistances", "CONFIG_VALUE_ALERTS_HEAVY_TRAFFIC_ALERT_DISTANCE_METERS", it) }
        streets?.let { keepWazeConfig("alertDistances", "CONFIG_VALUE_ALERTS_ENFORCEMENT_ALERTS_DISTANCE_STREETS", it) }
        highways?.let { keepWazeConfig("alertDistances", "CONFIG_VALUE_ALERTS_ENFORCEMENT_ALERTS_DISTANCE_HIGHWAY", it) }
        freeways?.let { keepWazeConfig("alertDistances", "CONFIG_VALUE_ALERTS_ENFORCEMENT_ALERTS_DISTANCE_FREEWAY", it) }
    }
}

@Suppress("unused")
val wazeSpeedLimitSignPatch = bytecodePatch(
    name = "Speed limit sign",
    description = "The style of the speed limit sign beside the speedometer, whatever the country. It can be " +
        "changed later in Customization, at the bottom of Waze's side menu.",
    default = false,
) {
    compatibleWith(COMPATIBILITY_WAZE)
    dependsOn(wazeConfigOverridesPatch)

    val style by stringOption(
        key = "style",
        default = "us",
        values = mapOf("US (white rectangle)" to "us", "World (red circle)" to "world"),
        title = "Sign style",
        description = "Waze picks one by country.",
        required = true,
    )

    execute {
        keepWazeConfig("speedLimitSign", "CONFIG_VALUE_MAP_SPEEDOMETER_STYLE", style!!)
    }
}

@Suppress("unused")
val wazeNavigationDistancesPatch = bytecodePatch(
    name = "Navigation distances",
    description = "How far ahead Android Auto gives its heads-up, and how long a jam must last before the " +
        "traffic bar shows it. The defaults are the old Waze mods' (Chuppito)." +
        " They can be changed later in Customization, at the bottom of Waze's side menu.",
    default = false,
) {
    compatibleWith(COMPATIBILITY_WAZE)
    dependsOn(wazeConfigOverridesPatch)

    val headsUp by intOption(key = "headsUp", default = 1200, title = "Android Auto heads-up, normal roads (m)", description = "Waze: 200 m.")
    val headsUpFreeway by intOption(key = "headsUpFreeway", default = 1300, title = "Android Auto heads-up, freeways (m)", description = "Waze: 500 m.")
    val trafficBar by intOption(key = "trafficBar", default = 180, title = "Traffic bar: time in traffic (s)", description = "Waze: 360 s.")

    execute {
        headsUp?.let { keepWazeConfig("navigationDistances", "CONFIG_VALUE_ANDROID_AUTO_HEADS_UP_DISTANCE_NORMAL", it) }
        headsUpFreeway?.let { keepWazeConfig("navigationDistances", "CONFIG_VALUE_ANDROID_AUTO_HEADS_UP_DISTANCE_FREEWAY", it) }
        trafficBar?.let { keepWazeConfig("navigationDistances", "CONFIG_VALUE_TRAFFIC_BAR_MIN_TIME_IN_TRAFFIC_SECONDS", it) }
    }
}
