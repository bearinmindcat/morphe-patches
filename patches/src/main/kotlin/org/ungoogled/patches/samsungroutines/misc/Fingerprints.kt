package org.ungoogled.patches.samsungroutines.misc

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.string

/** The app's Application.onCreate (com.samsung.android.app.routines.Routine), run in each of its processes. */
internal object ApplicationOnCreateFingerprint : Fingerprint(
    definingClass = "Lcom/samsung/android/app/routines/Routine;",
    name = "onCreate",
    returnType = "V",
    parameters = listOf(),
    filters = listOf(string("onCreate: start >>> ")),
)

/** Shows or hides the launcher icon (and the One UI Finder entry, the other way round), from a setting off by default. */
internal object LauncherIconToggleFingerprint : Fingerprint(
    returnType = "V",
    parameters = listOf("Landroid/content/Context;", "Z"),
    filters = listOf(
        string("com.samsung.android.app.routines.ui.main.RoutineLaunchActivityDummy"),
        string("com.samsung.android.app.routines.ui.main.RoutineFinderActivity"),
    ),
)

/** The main screen (Modes / Routines / Discover tabs). */
internal object MainScreenOnCreateFingerprint : Fingerprint(
    definingClass = "Lcom/samsung/android/app/routines/ui/main/RoutineMainTabActivity;",
    name = "onCreate",
    returnType = "V",
    parameters = listOf("Landroid/os/Bundle;"),
)
