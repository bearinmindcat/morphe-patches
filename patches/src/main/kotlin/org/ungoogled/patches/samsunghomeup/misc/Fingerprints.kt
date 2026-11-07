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

/** Home Up's main screen (the module tiles); it logs "onCreate" and sets up its tiles' listeners. */
internal object MainScreenCreateFingerprint : Fingerprint(
    definingClass = "Lcom/samsung/android/app/homestar/v2/ui/MainActivity;",
    name = "onCreate",
    returnType = "V",
    parameters = listOf("Landroid/os/Bundle;"),
    filters = listOf(string("onCreate")),
)

/** The Edge panel module's settings screen. */
internal object EdgePanelCreateFingerprint : Fingerprint(
    definingClass = "Lcom/samsung/android/app/homestar/v2/ui/edgepanel/EdgePanelActivity;",
    name = "onCreate",
    returnType = "V",
    parameters = listOf("Landroid/os/Bundle;"),
)

/** The Share Manager module's settings screen (exported: other apps can open it). */
internal object ShareManagerCreateFingerprint : Fingerprint(
    definingClass = "Lcom/samsung/android/app/sharestar/activities/ShareStarActivity;",
    name = "onCreate",
    returnType = "V",
    parameters = listOf("Landroid/os/Bundle;"),
)

/** Whether One UI's apps edge panel is turned on, read from One UI's edge panel list setting. */
internal object AppsEdgeEnabledFingerprint : Fingerprint(
    definingClass = "Lcom/samsung/android/app/homestar/v2/ui/edgepanel/EdgePanelUtils;",
    returnType = "Z",
    parameters = listOf("Landroid/content/Context;"),
    filters = listOf(string("cocktail_bar_enabled_cocktails"), string("AppsEdgePanelProvider")),
)
