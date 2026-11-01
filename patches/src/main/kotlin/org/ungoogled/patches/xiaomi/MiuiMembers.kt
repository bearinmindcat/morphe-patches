package org.ungoogled.patches.xiaomi

import app.morphe.patcher.patch.BytecodePatchContext
import org.ungoogled.patches.samsung.redirectMembers

/**
 * HyperOS's additions to Android's own classes that Xiaomi apps call directly (miuix's transitions and the like),
 * as "Lowner;->name". Missing.java (.github/scripts/xiaomi-standins) lists an app's in members.txt.
 */
private val MIUI_MEMBERS = setOf(
    "Landroid/app/ActivityOptions;->makeMiuiClipAnimation",
    "Landroid/app/ActivityOptions;->makeMiuiRoundAnimation",
    "Landroid/app/ActivityOptions;->makeScaleUpDown",
    "Landroid/app/ActivityOptions;->makeScaleUpAnimationFromRoundedView",
    "Landroid/app/ActivityOptions;->makeHyperViewScaleAnimation",
    "Landroid/content/Context;->getContentResolverForUser",
)

/** Member names that only HyperOS gives Android's classes. */
private val MIUI_NAME = Regex("(?i)miui|hyperos")

/**
 * Sends an app's uses of HyperOS's additions to Android classes through its extension's Miui class (generated from
 * .github/scripts/xiaomi-standins/runtime), which uses them where the phone has them and answers "not there"
 * elsewhere (plain ActivityOptions for the miuix transitions): off HyperOS each one is a NoSuchMethodError or
 * NoSuchFieldError the moment it runs. [miui] and [shims] are the extension's Miui and MiuiShims classes; [extra]
 * adds app-specific "Lowner;->name" members. Returns the calls and field accesses moved.
 */
internal fun BytecodePatchContext.redirectMiuiMembers(
    miui: String,
    shims: String,
    extra: Set<String> = emptySet(),
): Pair<Int, Int> = redirectMembers(miui, shims) { owner, name ->
    owner.startsWith("Landroid/") && !owner.contains("Miui") &&
        ("$owner->$name" in MIUI_MEMBERS || "$owner->$name" in extra || MIUI_NAME.containsMatchIn(name))
}
