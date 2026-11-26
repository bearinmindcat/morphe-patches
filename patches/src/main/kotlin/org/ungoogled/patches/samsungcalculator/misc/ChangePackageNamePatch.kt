package org.ungoogled.patches.samsungcalculator.misc

import org.ungoogled.patches.samsung.samsungChangePackageNamePatch
import org.ungoogled.patches.samsungcalculator.SamsungCalculatorConstants.COMPATIBILITY_SAMSUNG_CALCULATOR

/** Installs the port next to Samsung's own Calculator on a Galaxy phone (issue #212); see samsungChangePackageNamePatch. */
@Suppress("unused")
val changePackageNamePatch = samsungChangePackageNamePatch(
    compatibility = COMPATIBILITY_SAMSUNG_CALCULATOR,
    stock = "com.sec.android.app.popupcalculator",
    defaultName = "org.ungoogled.popupcalculator",
    appName = "Calculator",
    dependencies = listOf(enableNonOneUiPatch),
    // Measured: 3 permissions + authorities.
    minimumNames = 2,
)
