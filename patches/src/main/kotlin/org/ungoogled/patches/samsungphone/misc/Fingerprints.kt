package org.ungoogled.patches.samsungphone.misc

import app.morphe.patcher.Fingerprint

/** Shows one of the dial pad's start-up dialogs by type; type 90 asks to make Samsung Phone the default phone app. */
internal object DialogManagerShowFingerprint : Fingerprint(
    returnType = "V",
    parameters = listOf("I"),
    strings = listOf("DialtactsDialogManager", "isLaunchFromExternal"),
)

internal object DialerApplicationOnCreateFingerprint : Fingerprint(
    definingClass = "Lcom/samsung/android/dialer/DialerApplication;",
    name = "onCreate",
    returnType = "V",
    parameters = listOf(),
)

/** The app's single-permission check (PermissionsUtil); its class also holds the "all of these granted" check. */
internal object PermissionCheckFingerprint : Fingerprint(
    returnType = "Z",
    parameters = listOf("Landroid/content/Context;", "Ljava/lang/String;"),
    strings = listOf("PermissionsUtil", "context is null"),
)

/** Recents (the call log list) loads a page of entries: (from, count). */
internal object RecentsLoadFingerprint : Fingerprint(
    returnType = "V",
    parameters = listOf("I", "I"),
    strings = listOf("loadCallLogs  from("),
)
