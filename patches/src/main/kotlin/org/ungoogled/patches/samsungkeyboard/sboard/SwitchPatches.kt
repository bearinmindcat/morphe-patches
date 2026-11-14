package org.ungoogled.patches.samsungkeyboard.sboard

import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.bytecodePatch
import com.android.tools.smali.dexlib2.iface.Method
import org.ungoogled.patches.samsungkeyboard.SamsungKeyboardConstants.COMPATIBILITY_SAMSUNG_KEYBOARD
import org.ungoogled.patches.samsungkeyboard.misc.SAMSUNG_KEYBOARD_EXTENSION

/** One UI's switch that turns the phone's on-device Galaxy AI off; the keyboard drops every Galaxy AI entry with it. */
private const val NATIVE_AI_OFF = "SEC_FLOATING_FEATURE_COMMON_DISABLE_NATIVE_AI"

/** Logged when the work-profile (Knox) Galaxy AI policy can't be read; its class answers "is Galaxy AI greyed out?". */
private const val GALAXY_AI_POLICY_LOG = "Galaxy Ai restriction is null"

/** Galaxy AI's rows in the keyboard's settings. */
private val GALAXY_AI_ROWS = arrayOf(
    "SETTINGS_WRITING_ASSIST", "SETTINGS_DRAWING_ASSIST", "SETTINGS_WRITING_ASSIST_TRANSLATION",
)

@Suppress("unused")
val hideGalaxyAiPatch = bytecodePatch(
    name = "Hide Galaxy AI",
    description = "Removes Galaxy AI from the toolbar, stickers and settings.",
    default = false,
) {
    compatibleWith(COMPATIBILITY_SAMSUNG_KEYBOARD)
    category(SBOARD)

    execute {
        // The keyboard asks One UI once whether on-device AI is off; it now always is, which drops the writing-assist
        // button, panel and suggested replies and the AI stickers, as on a phone without Galaxy AI.
        val reads = answerSwitch(NATIVE_AI_OFF, FLOATING_GET_BOOLEAN, "const/16 {r}, 0x1")

        // What the switch doesn't reach asks the Galaxy AI policy a work profile can set ("greyed out?"): it now always
        // says yes for the keyboard's keys.
        val policyReaders = methodsWithString(GALAXY_AI_POLICY_LOG)
        if (policyReaders.isEmpty()) throw PatchException("the Galaxy AI policy reader ($GALAXY_AI_POLICY_LOG) was not found")
        val readerKeys = policyReaders.map { it.key() }.toSet()
        val checks = methodsWhere { m ->
            m.returnType == "Z" && m.parameterTypes.map(CharSequence::toString) == listOf("Ljava/lang/String;") &&
                m.definingClass in policyReaders.map { it.definingClass } &&
                (GALAXY_AI_POLICY_LOG in m.strings() || m.calls().any { it in readerKeys })
        }
        if (checks.isEmpty()) throw PatchException("the Galaxy AI policy check was not found")
        checks.forEach { mutable(it).prepend("const/4 v0, 0x1\nreturn v0") }

        hideSettingsRows(*GALAXY_AI_ROWS)
        hideToolbarButtons("ai_writing", "ai_expression")
        sboardLog.info("Hide Galaxy AI: $reads switch reads, ${checks.size} policy checks")
    }
}

/** One UI's speed class for the suggestion bar's text animation: "low"/"medium" phones get 150 ms instead of 300 ms. */
private const val CANDIDATE_DURATION = "SEC_FLOATING_FEATURE_SIP_CONFIG_CANDIDATE_DURATION_TYPE"

@Suppress("unused")
val fasterSuggestionsPatch = bytecodePatch(
    name = "Faster suggestion animations",
    description = "Halves the suggestion bar's animation time.",
    default = false,
) {
    compatibleWith(COMPATIBILITY_SAMSUNG_KEYBOARD)
    category(SBOARD)

    execute {
        val reads = answerSwitch(CANDIDATE_DURATION, FLOATING_GET_STRING, "const-string {r}, \"low\"")
        sboardLog.info("Faster suggestion animations: $reads switch reads")
    }
}

/** The region's (CSC) list of toolbar apps to leave out; "bitmoji" and "mojitok" in it remove those sticker services. */
private const val TOOLBAR_APP_LIST = "CscFeature_Sip_DisableToolbarAppList"

@Suppress("unused")
val hideThirdPartyStickersPatch = bytecodePatch(
    name = "Hide Bitmoji and mojitok",
    description = "Removes the Bitmoji and mojitok sticker services.",
    default = false,
) {
    compatibleWith(COMPATIBILITY_SAMSUNG_KEYBOARD)
    category(SBOARD)
    extendWith(SAMSUNG_KEYBOARD_EXTENSION)

    execute {
        val reads = answerSwitch(
            TOOLBAR_APP_LIST, CSC_GET_STRING_1,
            "invoke-static/range {{r} .. {r}}, $SBOARD_EXTENSION_CLASS->withoutThirdPartyStickers(Ljava/lang/String;)Ljava/lang/String;\nmove-result-object {r}",
            CSC_GET_STRING_2,
        )
        sboardLog.info("Hide Bitmoji and mojitok: $reads switch reads")
    }
}

@Suppress("unused")
val hideTranslatePatch = bytecodePatch(
    name = "Hide Translate",
    description = "Removes the Translate button from the toolbar.",
    default = false,
) {
    compatibleWith(COMPATIBILITY_SAMSUNG_KEYBOARD)
    category(SBOARD)

    execute {
        // Google Translate through Samsung's key: a re-signed copy only gets errors from it.
        hideToolbarButtons("translation", "sogou_translation")
    }
}

/** Samsung Pass's toolbar button (a plugin; its list must stay: an empty resource array can't be read). */
private const val SAMSUNG_PASS = "com.samsung.android.authfw.samsungpass"

@Suppress("unused")
val hideSamsungPassPatch = bytecodePatch(
    name = "Hide Samsung Pass",
    description = "Removes the Samsung Pass button from the toolbar.",
    default = false,
) {
    compatibleWith(COMPATIBILITY_SAMSUNG_KEYBOARD)
    category(SBOARD)

    execute {
        hideToolbarButtons(SAMSUNG_PASS)
    }
}

/** One UI's one-handed mode policy; the keyboard offers its one-handed mode only when the phone has one (off One UI: none). */
private const val ONE_HAND_POLICY = "SEC_FLOATING_FEATURE_COMMON_CONFIG_ONEHAND_MODE_POLICY"

@Suppress("unused")
val oneHandedEverywherePatch = bytecodePatch(
    name = "One-handed keyboard on every phone",
    description = "Offers the one-handed keyboard mode on every phone.",
) {
    compatibleWith(COMPATIBILITY_SAMSUNG_KEYBOARD)
    category(SBOARD)
    extendWith(SAMSUNG_KEYBOARD_EXTENSION)

    execute {
        // A Galaxy phone's own policy is kept; an empty one (no One UI) reads as present.
        val reads = answerSwitch(
            ONE_HAND_POLICY, FLOATING_GET_STRING,
            "invoke-static/range {{r} .. {r}}, $SBOARD_EXTENSION_CLASS->orPresent(Ljava/lang/String;)Ljava/lang/String;\nmove-result-object {r}",
        )
        sboardLog.info("One-handed keyboard: $reads switch reads")
    }
}
