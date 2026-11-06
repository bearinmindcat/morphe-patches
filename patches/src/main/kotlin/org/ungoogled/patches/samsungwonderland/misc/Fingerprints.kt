package org.ungoogled.patches.samsungwonderland.misc

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.string

/** Wonderland's reflection helper: invokes a hidden method by name (WallpaperManager's setWallpaperComponent...). */
internal object ReflectionInvokeFingerprint : Fingerprint(
    returnType = "Ljava/lang/Object;",
    parameters = listOf("Ljava/lang/Object;", "Ljava/lang/String;", "[Ljava/lang/Class;", "[Ljava/lang/Object;"),
    filters = listOf(string(" : Cannot invoke there's no method reflection : ")),
)
