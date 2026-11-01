package org.ungoogled.patches.lg

import app.morphe.patcher.patch.BytecodePatchContext
import org.ungoogled.patches.samsung.redirectMembers

/**
 * LG's additions to Android's own classes that LG apps use directly, as "Lowner;->name". Missing.java
 * (.github/scripts/xiaomi-standins) lists an app's in members.txt.
 */
private val LG_MEMBERS = setOf(
    "Landroid/app/NotificationChannel;->setHideChannel",
    "Landroid/content/res/Configuration;->fontTypeIndex",
    "Landroid/view/IWindowManager;->getCurrentFocusDisplayId",
    "Landroid/view/IWindowManager;->getDensityDeviceStable",
    "Landroid/view/IWindowManager;->isDesktopModeEnabled",
    "Landroid/view/SurfaceControl;->screenshot",
    "Landroid/view/WindowManager\$LayoutParams;->bypassKeyFlags",
)

/**
 * Sends an app's uses of LG's additions to Android classes through its extension's Lg class (generated from
 * .github/scripts/lg-standins/runtime), which uses them where the phone has them and answers "not there" elsewhere:
 * off LG's ROM each one is a NoSuchMethodError or NoSuchFieldError the moment it runs. [lg] and [shims] are the
 * extension's Lg and LgShims classes; [extra] adds app-specific "Lowner;->name" members. Returns the calls and field
 * accesses moved.
 */
internal fun BytecodePatchContext.redirectLgMembers(
    lg: String,
    shims: String,
    extra: Set<String> = emptySet(),
): Pair<Int, Int> = redirectMembers(lg, shims) { owner, name ->
    owner.startsWith("Landroid/") && ("$owner->$name" in LG_MEMBERS || "$owner->$name" in extra)
}
