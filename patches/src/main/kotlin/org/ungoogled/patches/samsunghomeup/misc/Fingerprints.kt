package org.ungoogled.patches.samsunghomeup.misc

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.string

/** The provider One UI Home reads Home Up's settings from (call is its only working entry point). */
internal object HomestarProviderCallFingerprint : Fingerprint(
    definingClass = "Lcom/samsung/android/app/homestar/common/HomestarProvider;",
    name = "call",
    returnType = "Landroid/os/Bundle;",
    parameters = listOf("Ljava/lang/String;", "Ljava/lang/String;", "Landroid/os/Bundle;"),
    filters = listOf(string("version_info")),
)
