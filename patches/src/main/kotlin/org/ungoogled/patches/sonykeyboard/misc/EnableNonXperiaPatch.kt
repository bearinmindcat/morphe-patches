package org.ungoogled.patches.sonykeyboard.misc

import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.patch.resourcePatch
import org.ungoogled.patches.samsung.makeLibrariesOptional
import org.ungoogled.patches.shared.supportedVersionCheck
import org.ungoogled.patches.sonykeyboard.SonyKeyboardConstants.COMPATIBILITY_XPERIA_KEYBOARD

private val log = java.util.logging.Logger.getLogger("XperiaKeyboard")

private const val UXP = "Lcom/sonyericsson/textinput/uxp"

/**
 * The keyboard requires Xperia's com.sony.device library (it names nothing from it), so Android refuses to install it
 * elsewhere.
 */
private val librariesPatch = resourcePatch(
    description = "Makes the keyboard's Xperia library optional.",
) {
    execute {
        val required = makeLibrariesOptional()
        log.info("required libraries made optional: $required")
        // Measured against 8.1.A.0.12: one, com.sony.device.
        if (required == 0) throw PatchException("no required library found")
    }
}

/**
 * What the keyboard could send: Backup & Sync uploaded the sentences typed (kept in a local "unsynced messages" table
 * until then) and the learned language model to Sony's server (xperiakeyboard.sonymobile.com, gone), Google Analytics
 * through Tag Manager, and Crashlytics when Tag Manager's container switched it on. Off Xperia Sony's own checks
 * already hide Backup & Sync and the personalisation logins (no Sony Gmail reader service) and Analytics (no Sony
 * analytics setting); this makes it unconditional: Backup & Sync is never on, typed sentences are never stored for it,
 * and Tag Manager / Analytics / Crashlytics are never started.
 */
private val privacyPatch = bytecodePatch(
    description = "Keeps typed text on the phone and analytics off.",
) {
    execute {
        fun method(type: String, name: String, parameters: List<String>, returnType: String) =
            mutableClassDefByOrNull(type)?.methods?.firstOrNull {
                it.name == name && it.returnType == returnType && it.parameterTypes.map(CharSequence::toString) == parameters
            } ?: throw PatchException("$type->$name not found")

        method("$UXP/model/settings/Settings;", "isBackupAndSyncEnabled", listOf(), "Z").addInstructions(
            0,
            """
                const/4 v0, 0x0
                return v0
            """,
        )
        method("$UXP/model/cloud/UnsyncedMessagesDatabase;", "write", listOf("Landroid/content/Context;", "Ljava/lang/String;"), "V")
            .addInstructions(0, "return-void")
        method(
            "$UXP/tracker/AbstractAnalyticsTracker;", "getGaGtmUtils",
            listOf("$UXP/tracker/AbstractAnalyticsTracker\$OnAnalyticsEventListener;"), "V",
        ).addInstructions(0, "return-void")
    }
}

/**
 * Sony's dictionaries came from SwiftKey's server (skslm.swiftkey.net/sony/...), which no longer serves them: the host
 * now answers with another site's certificate and a 404. The keyboard still asked it for the dictionary list on every
 * start until one answer arrived, and offered a download that cannot work. The keyboard now behaves as Sony's builds for
 * China do: no automatic dictionary checks (only when the user starts a download or turns on automatic updates), and the
 * "Download dictionary?" prompt is not shown. Word suggestions come from the words the user types (kept on the phone).
 */
private val dictionaryPatch = bytecodePatch(
    description = "Stops the automatic dictionary checks against SwiftKey's retired server.",
) {
    execute {
        fun method(type: String, name: String, parameters: List<String>) =
            mutableClassDefByOrNull(type)?.methods?.firstOrNull {
                it.name == name && it.returnType == "Z" && it.parameterTypes.map(CharSequence::toString) == parameters
            } ?: throw PatchException("$type->$name not found")

        method("$UXP/util/EnvironmentUtils;", "allowedToConnectToNetwork", listOf("Landroid/content/Context;"))
            .addInstructions(
                0,
                """
                    const/4 v0, 0x0
                    return v0
                """,
            )
        method("$UXP/model/settings/Settings;", "getHasShownDictionaryDownloadDialog", listOf()).addInstructions(
            0,
            """
                const/4 v0, 0x1
                return v0
            """,
        )
    }
}

@Suppress("unused")
val enableNonXperiaPatch = bytecodePatch(
    name = "Enable non-Xperia ROM support",
    description = "Makes Xperia Keyboard usable on non-Xperia ROMs.",
    default = true,
) {
    compatibleWith(COMPATIBILITY_XPERIA_KEYBOARD)
    dependsOn(supportedVersionCheck(COMPATIBILITY_XPERIA_KEYBOARD), librariesPatch, privacyPatch, dictionaryPatch)
}
