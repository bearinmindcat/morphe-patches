package org.ungoogled.patches.samsungonehand.misc

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.fieldAccess
import app.morphe.patcher.literal
import app.morphe.patcher.string
import com.android.tools.smali.dexlib2.AccessFlags

/** The app's "is this a supported OS" check: a Samsung phone on an official One UI build. */
internal object SupportedOsFingerprint : Fingerprint(
    returnType = "Z",
    parameters = listOf("Landroid/content/Context;"),
    filters = listOf(string("unsupported OS : manufacture=")),
)

/**
 * "Is this One UI 8": the app's test for Android 16 (SDK_INT >= 36), which on a Galaxy means One UI 8. It switches the
 * app to One UI 8's own ways (HoneySpace's edge settings provider, Samsung's dialog blur, the system gesture policy).
 */
internal object OneUi8Fingerprint : Fingerprint(
    returnType = "Z",
    parameters = listOf(),
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.STATIC),
    filters = listOf(
        fieldAccess(definingClass = "Landroid/os/Build\$VERSION;", name = "SDK_INT"),
        literal(36),
    ),
    custom = { method, _ -> (method.implementation?.instructions?.count() ?: 0) <= 8 },
)

/** Runs an action by its name (a handle swipe, a quick tool, a fast action). */
internal object RunActionFingerprint : Fingerprint(
    returnType = "Z",
    filters = listOf(string("skip runAction() setupWizard case."), string("shortcutid")),
)

/** Whether an action is offered on this phone (the action lists and pickers ask it). */
internal object ActionSupportedFingerprint : Fingerprint(
    returnType = "Z",
    parameters = listOf("Ljava/lang/String;"),
    filters = listOf(string("isPayWithSamsungPayVisible NullPointerException : ")),
)

/** Presses a key: (code, action, flags, meta state) through InputManager.injectInputEvent. */
internal object InjectKeyFingerprint : Fingerprint(
    returnType = "V",
    parameters = listOf("I", "I", "I", "I"),
    filters = listOf(string("injectInputEvent")),
)

/** Touches the screen: one MotionEvent through InputManager.injectInputEvent. */
internal object InjectMotionFingerprint : Fingerprint(
    returnType = "V",
    parameters = listOf("Landroid/content/Context;", "Landroid/view/MotionEvent;"),
    filters = listOf(string("injectInputEvent")),
)

/** The app's main screen coming up. */
internal object SettingsResumeFingerprint : Fingerprint(
    definingClass = "Lcom/samsung/android/sidegesturepad/settings/SGPSettingsActivity;",
    name = "onResume",
    returnType = "V",
    parameters = listOf(),
)

/** Reads a colour of SystemUI's by name (One UI's quick panel colours). */
internal object SystemUiColorFingerprint : Fingerprint(
    returnType = "I",
    parameters = listOf("Landroid/content/Context;", "Ljava/lang/String;"),
    filters = listOf(string("com.android.systemui"), string("ColorUtil")),
)
