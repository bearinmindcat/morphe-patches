package org.ungoogled.patches.samsungweather.misc

import org.ungoogled.patches.samsung.samsungChangePackageNamePatch
import org.ungoogled.patches.samsungweather.SamsungWeatherConstants.COMPATIBILITY_SAMSUNG_WEATHER

/** Installs the port next to Samsung's own Weather on a Galaxy phone (issue #212); see samsungChangePackageNamePatch. */
@Suppress("unused")
val changePackageNamePatch = samsungChangePackageNamePatch(
    compatibility = COMPATIBILITY_SAMSUNG_WEATHER,
    stock = "com.sec.android.daemonapp",
    defaultName = "org.ungoogled.samsungweather",
    appName = "Weather",
    dependencies = listOf(enableNonOneUiPatch),
    // Measured: 11 permissions + authorities.
    minimumNames = 8,
)
