package org.ungoogled.patches.samsunginternet

import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.bytecodePatch
import com.android.tools.smali.dexlib2.iface.instruction.NarrowLiteralInstruction
import org.ungoogled.patches.samsunginternet.SamsungInternetConstants.COMPATIBILITY_SAMSUNG_INTERNET

private const val TAB_COUNT_MANAGER = "${SBROWSER}multi_instance/MultiInstanceTabCountManager;"
private const val TAB_STATE_READER = "${SBROWSER}tab_saver/util/TabStateReader;"

/**
 * Samsung Internet allows 99 open tabs across its windows (MultiInstanceTabCountManager, 99 unless a debug setting
 * says otherwise): every new tab first checks the count and closes the oldest tab at the limit, and a restart drops
 * the oldest saved tabs beyond it. Both checks go; the count itself stays, so the recently-closed list keeps its cap.
 */
@Suppress("unused")
val removeTabLimitPatch = bytecodePatch(
    name = "Remove tab limit",
    description = "Removes the 99-tab limit.",
) {
    compatibleWith(COMPATIBILITY_SAMSUNG_INTERNET)

    execute {
        // The limit is still the one this patch was written for: the constructor sets 99.
        val constructor = method(TAB_COUNT_MANAGER, "<init>")
        if (constructor.implementation!!.instructions.none { (it as? NarrowLiteralInstruction)?.narrowLiteral == 99 }) {
            throw PatchException("MultiInstanceTabCountManager no longer sets the 99-tab limit")
        }
        // New tab: "count >= limit -> close the oldest tab and show Reopen".
        method(TAB_COUNT_MANAGER, "checkGlobalTabCountLimit", "Landroid/content/Context;").returnEarly()
        // Reopening the closed oldest tab: "is the limit reached?".
        method(TAB_COUNT_MANAGER, "isMaxTabCountReached").returnEarly()
        // Restore after a restart: how many of the oldest saved tabs exceed the limit and are not restored.
        method(TAB_STATE_READER, "getTabsCountExceedingMax", "I", "Z").returnEarly()
    }
}
