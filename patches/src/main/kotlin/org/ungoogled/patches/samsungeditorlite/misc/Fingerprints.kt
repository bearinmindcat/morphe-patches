package org.ungoogled.patches.samsungeditorlite.misc

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.string
import com.android.tools.smali.dexlib2.AccessFlags

/** Sets whether a save replaces the original and keeps it for "Revert" (Samsung's non-destructive editing). */
internal object NonDestructiveFlagFingerprint : Fingerprint(
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.STATIC),
    returnType = "V",
    parameters = listOf("Z"),
    filters = listOf(string("setNDEflag ")),
)
