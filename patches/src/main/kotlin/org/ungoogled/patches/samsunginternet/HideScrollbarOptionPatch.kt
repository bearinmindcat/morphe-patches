package org.ungoogled.patches.samsunginternet

import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.extensions.InstructionExtensions.addInstructionsWithLabels
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.patch.resourcePatch
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod.Companion.toMutable
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.builder.MutableMethodImplementation
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.reference.StringReference
import com.android.tools.smali.dexlib2.immutable.ImmutableMethod
import org.ungoogled.patches.samsunginternet.SamsungInternetConstants.COMPATIBILITY_SAMSUNG_INTERNET
import org.w3c.dom.Element

/** The switch's key in Samsung Internet's default preferences. */
private const val HIDE_SCROLL_BAR_KEY = "ungoogled_hide_scroll_bar"

private const val COMMAND_LINE = "${SBROWSER}init/SBrowserCommandLine;"

/** Adds "Hide scroll bar" to Settings > Webpage view and scrolling, under Pull to refresh. */
private val hideScrollbarSettingPatch = resourcePatch(
    description = "Adds the Hide scroll bar switch to the webpage view settings.",
) {
    execute {
        document("res/xml/display_preferences.xml").use { settings ->
            val all = settings.getElementsByTagName("*")
            var anchor: Element? = null
            for (i in 0 until all.length) {
                val e = all.item(i) as Element
                if (e.getAttribute("android:key") == "pref_pull_to_refresh") anchor = e
            }
            anchor ?: throw PatchException("Webpage view settings have no Pull to refresh switch (pref_pull_to_refresh)")
            val row = settings.createElement(anchor.tagName).apply {
                setAttribute("android:key", HIDE_SCROLL_BAR_KEY)
                setAttribute("android:title", "Hide scroll bar")
                setAttribute("android:summary", "Applies after restarting the app")
                setAttribute("android:defaultValue", "false")
            }
            anchor.parentNode.insertBefore(row, anchor.nextSibling)
        }
    }
}

/**
 * Samsung Internet 27 dropped the Scroll bar setting (position, or hide). Its scroll bar today is Chromium's own
 * scroll bar on the page (switch enable-hit-testable-scrollbar-in-visual-viewport), which Chromium leaves out
 * altogether under its hide-scrollbars switch. The switch comes back as "Hide scroll bar"; when it is on, Samsung
 * Internet's start-up command line (SBrowserCommandLine.initialize) gets hide-scrollbars.
 */
@Suppress("unused")
val hideScrollbarOptionPatch = bytecodePatch(
    name = "Hide scrollbar option",
    description = "Brings back the setting to hide the scroll bar.",
) {
    compatibleWith(COMPATIBILITY_SAMSUNG_INTERNET)
    dependsOn(hideScrollbarSettingPatch)

    execute {
        val initialize = method(COMMAND_LINE, "initialize")
        // The scroll bar this hides: Samsung Internet's own command line turns it on in the same method.
        val scrollbarSwitch = initialize.implementation!!.instructions.any {
            ((it as? ReferenceInstruction)?.reference as? StringReference)?.string == "enable-hit-testable-scrollbar-in-visual-viewport"
        }
        if (!scrollbarSwitch) throw PatchException("SBrowserCommandLine.initialize no longer sets up the page scroll bar")

        val helper = "ungoogledHideScrollbars"
        mutableClassDefBy(COMMAND_LINE).methods.add(
            ImmutableMethod(
                COMMAND_LINE, helper, emptyList(), "V", AccessFlags.PRIVATE.value or AccessFlags.STATIC.value,
                null, null, MutableMethodImplementation(3),
            ).toMutable().apply {
                addInstructionsWithLabels(
                    0,
                    """
                        invoke-static {}, Lorg/chromium/base/ContextUtils;->getAppSharedPreferences()Landroid/content/SharedPreferences;
                        move-result-object v0
                        if-eqz v0, :done
                        const-string v1, "$HIDE_SCROLL_BAR_KEY"
                        const/4 v2, 0x0
                        invoke-interface {v0, v1, v2}, Landroid/content/SharedPreferences;->getBoolean(Ljava/lang/String;Z)Z
                        move-result v0
                        if-eqz v0, :done
                        const-string v0, "hide-scrollbars"
                        invoke-static {v0}, Lcom/sec/terrace/TerraceCommandLine;->appendSwitch(Ljava/lang/String;)V
                        :done
                        return-void
                    """,
                )
            },
        )
        initialize.addInstructions(0, "invoke-static {}, $COMMAND_LINE->$helper()V")
    }
}
