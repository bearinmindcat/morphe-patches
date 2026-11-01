package org.ungoogled.patches.xiaomigalleryeditor.misc

import app.morphe.patcher.Fingerprint
import com.android.tools.smali.dexlib2.AccessFlags

/**
 * The installer of Xiaomi's "magic string" resource hook (called from PhotoShopApp.attachBaseContext): wraps the
 * Application's Resources, then puts its own Instrumentation into ActivityThread to wrap every Activity's.
 */
internal object ResGuardInstallFingerprint : Fingerprint(
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.STATIC),
    returnType = "V",
    parameters = listOf("Lcom/miui/mediaeditor/PhotoShopApp;"),
    strings = listOf("currentActivityThread", "mLoadedApk", "mActivityThread", "mInstrumentation"),
)
