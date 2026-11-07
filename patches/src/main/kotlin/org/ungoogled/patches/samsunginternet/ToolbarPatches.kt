package org.ungoogled.patches.samsunginternet

import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.extensions.InstructionExtensions.addInstructionsWithLabels
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod.Companion.toMutable
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.builder.MutableMethodImplementation
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.immutable.ImmutableMethod
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodParameter
import org.ungoogled.patches.samsunginternet.SamsungInternetConstants.COMPATIBILITY_SAMSUNG_INTERNET
import org.ungoogled.patches.shared.addInstructionsAtLabel

private const val FEATURES = "${SBROWSER}common/device/SBrowserFeatures;"
private const val DEVICE_FEATURE_UTILS = "${SBROWSER}common/device/DeviceFeatureUtils;"
private const val DEVICE_LAYOUT_UTIL = "${SBROWSER}common/device/DeviceLayoutUtil;"
private const val THEME_PARAM = "${SBROWSER}common/toolbar/ThemeParam;"
private const val BROWSER_THEME = "${SBROWSER}common/model/theme/BrowserTheme;"
private const val BOTTOMBAR = "${SBROWSER}toolbar/Bottombar;"
private const val GRADIENTS = "${SBROWSER}toolbar/blur/GradientDrawableFactory;"
private const val NAVIGATION_BAR_STATE = "${SBROWSER}common/device/DeviceLayoutUtil\$NavigationBarState;"
private const val TOOLBAR_COLOR = "${SBROWSER}toolbar/ToolbarColor;"

/**
 * The see-through, blurred address and bottom bars (and the matching hide-on-scroll and status bar handling) are the
 * One UI 9 look Samsung Internet turns on wherever One UI says the phone supports real-time blur
 * (SEC_FLOATING_FEATURE_GRAPHICS_SUPPORT_3D_SURFACE_TRANSITION_FLAG). Answering "not supported" gives the solid bars it
 * draws on every other phone. On a phone without that One UI flag the patch changes nothing.
 */
@Suppress("unused")
val removeAddressBarBlurPatch = bytecodePatch(
    name = "Remove address bar blur",
    description = "Solid address and bottom bars instead of the blurred gradient.",
) {
    compatibleWith(COMPATIBILITY_SAMSUNG_INTERNET)

    execute {
        val blur = method(FEATURES, "isRealTimeBlurSupported")
        if (blur.returnType != "Z") throw PatchException("SBrowserFeatures.isRealTimeBlurSupported no longer answers a boolean")
        blur.returnEarly()
    }
}

/**
 * Samsung Internet paints a page's theme-color into the address bar only when the bar is at the top: at the bottom
 * (ToolbarFocus) it is never themed and only its icons are tinted, and the bottom bar of buttons never takes the colour
 * either. Now both bottom bars, and the navigation bar strip under them, take the page colour, with the same text and
 * icon colours the top bar uses on it. Night
 * mode, secret mode, high contrast, reader themes and warning pages keep their own colours, as at the top.
 */
@Suppress("unused")
val themeColorBottomBarPatch = bytecodePatch(
    name = "Theme color on bottom bar",
    description = "The page's theme color also colors the bottom address bar and bottom bar.",
) {
    compatibleWith(COMPATIBILITY_SAMSUNG_INTERNET)

    execute {
        // The bottom address bar applies the page theme like the top one.
        method("${SBROWSER}toolbar/ToolbarFocus;", "shouldApplyBrowserTheme").addInstructions(0, "const/4 p0, 0x1\nreturn p0")

        // Its URL text, buttons and ripples use the top bar's colours for a themed bar, not the ones meant for a
        // white bar at the bottom.
        val atBottom = call(DEVICE_LAYOUT_UTIL, "isToolbarAtBottom")
        for ((type, name, parameters) in listOf(
            Triple("${SBROWSER}omnibox/UrlBar;", "setBackground", arrayOf(THEME_PARAM)),
            Triple("${SBROWSER}toolbar/ToolbarColor;", "getIconColor", arrayOf(THEME_PARAM)),
            Triple("${SBROWSER}toolbar/ToolbarColor;", "getButtonRippleResId", arrayOf(THEME_PARAM)),
        )) {
            if (method(type, name, *parameters).answerFalse(atBottom) != 1) {
                throw PatchException("${type.simpleName()}.$name: no single bottom-layout check")
            }
        }

        // "Bottom" icon colour of a theme (60% value of the colour, for a white bar) -> the colour drawn on the
        // themed bar itself. Every user of it is a bar at the bottom, which is now themed.
        val browserTheme = mutableClassDefBy(BROWSER_THEME)
        if (browserTheme.fields.none { it.name == "mMoreTextColor" && it.type == "I" }) {
            throw PatchException("BrowserTheme.mMoreTextColor not found")
        }
        method(BROWSER_THEME, "getBottomTextColor").addInstructions(
            0,
            "iget p0, p0, $BROWSER_THEME->mMoreTextColor:I\nreturn p0",
        )

        // The bottom bar's background: after Samsung's own (solid resource, or the blur gradient), a themed page gets
        // its colour -- solid, or as the same gradient in the page colour where the bars are blurred.
        val bottombar = mutableClassDefBy(BOTTOMBAR)
        val helper = "ungoogledThemeBackground"
        bottombar.methods.add(
            ImmutableMethod(
                BOTTOMBAR, helper,
                listOf(ImmutableMethodParameter(BOTTOMBAR, null, null), ImmutableMethodParameter(THEME_PARAM, null, null)),
                "V", AccessFlags.PRIVATE.value or AccessFlags.STATIC.value, null, null, MutableMethodImplementation(4),
            ).toMutable().apply {
                addInstructionsWithLabels(
                    0,
                    """
                        invoke-virtual {p1}, $THEME_PARAM->hasTheme()Z
                        move-result v0
                        if-eqz v0, :keep
                        invoke-virtual {p1}, $THEME_PARAM->isNightColor()Z
                        move-result v0
                        if-nez v0, :keep
                        invoke-virtual {p1}, $THEME_PARAM->isReaderThemeSepia()Z
                        move-result v0
                        if-nez v0, :keep
                        invoke-virtual {p1}, $THEME_PARAM->isMaliciousSite()Z
                        move-result v0
                        if-nez v0, :keep
                        invoke-virtual {p1}, $THEME_PARAM->getThemeColor()I
                        move-result v0
                        invoke-static {}, $DEVICE_FEATURE_UTILS->getInstance()$DEVICE_FEATURE_UTILS
                        move-result-object v1
                        invoke-virtual {v1}, $DEVICE_FEATURE_UTILS->isToolbarBlurSupported()Z
                        move-result v1
                        if-nez v1, :blurred
                        invoke-virtual {p0, v0}, Landroid/view/View;->setBackgroundColor(I)V
                        return-void
                        :blurred
                        invoke-virtual {p0}, Landroid/view/View;->getContext()Landroid/content/Context;
                        move-result-object v1
                        invoke-static {v1, v0}, $GRADIENTS->createBottombarGradientDrawable(Landroid/content/Context;I)Landroid/graphics/drawable/GradientDrawable;
                        move-result-object v1
                        invoke-virtual {p0, v1}, Landroid/view/View;->setBackground(Landroid/graphics/drawable/Drawable;)V
                        :keep
                        return-void
                    """,
                )
            },
        )
        val setBackground = method(BOTTOMBAR, "setBackground", THEME_PARAM, "Z", "Z")
        // Both background branches meet at the button-background code, which starts by asking for night mode.
        val merge = setBackground.implementation!!.instructions.indexOfFirst {
            val reference = (it as? ReferenceInstruction)?.reference as? MethodReference
            reference?.definingClass == THEME_PARAM && reference.name == "isNightMode"
        }
        if (merge < 0) throw PatchException("Bottombar.setBackground: no night-mode check after the background")
        // p1 (the ThemeParam) is still unchanged there.
        setBackground.addInstructionsAtLabel(merge, "invoke-static {p0, p1}, $BOTTOMBAR->$helper($BOTTOMBAR$THEME_PARAM)V")

        // The navigation bar strip under the bars: where Samsung's own colour for it is the plain one
        // (NAVIGATION_MAIN_VIEW), a themed page gets its colour and matching light or dark buttons. The helper sits in
        // DeviceLayoutUtil for its private setNavigationBarColorApi35 (the strip view Samsung draws on Android 15+).
        val navigationHelper = "ungoogledThemeNavigationBar"
        mutableClassDefBy(DEVICE_LAYOUT_UTIL).methods.add(
            ImmutableMethod(
                DEVICE_LAYOUT_UTIL, navigationHelper,
                listOf("Landroid/content/Context;", NAVIGATION_BAR_STATE, THEME_PARAM).map { ImmutableMethodParameter(it, null, null) },
                "V", AccessFlags.PUBLIC.value or AccessFlags.STATIC.value, null, null, MutableMethodImplementation(7),
            ).toMutable().apply {
                addInstructionsWithLabels(
                    0,
                    """
                        sget-object v0, $NAVIGATION_BAR_STATE->NAVIGATION_MAIN_VIEW:$NAVIGATION_BAR_STATE
                        if-ne p1, v0, :done
                        invoke-virtual {p2}, $THEME_PARAM->hasTheme()Z
                        move-result v0
                        if-eqz v0, :done
                        invoke-virtual {p2}, $THEME_PARAM->isNightColor()Z
                        move-result v0
                        if-nez v0, :done
                        invoke-virtual {p2}, $THEME_PARAM->isReaderThemeSepia()Z
                        move-result v0
                        if-nez v0, :done
                        invoke-virtual {p2}, $THEME_PARAM->isMaliciousSite()Z
                        move-result v0
                        if-nez v0, :done
                        invoke-static {p0}, ${SBROWSER}common/device/ActivityUtil;->getActivity(Landroid/content/Context;)Landroid/app/Activity;
                        move-result-object v0
                        if-eqz v0, :done
                        invoke-virtual {v0}, Landroid/app/Activity;->getWindow()Landroid/view/Window;
                        move-result-object v0
                        if-eqz v0, :done
                        invoke-virtual {p2}, $THEME_PARAM->getThemeColor()I
                        move-result v1
                        invoke-virtual {p2}, $THEME_PARAM->isDarkTheme()Z
                        move-result v2
                        sget v3, Landroid/os/Build${'$'}VERSION;->SDK_INT:I
                        const/16 p1, 0x23
                        if-lt v3, p1, :older
                        invoke-static {v0, v1, v2}, $DEVICE_LAYOUT_UTIL->setNavigationBarColorApi35(Landroid/view/Window;IZ)V
                        goto :buttons
                        :older
                        invoke-virtual {v0, v1}, Landroid/view/Window;->setNavigationBarColor(I)V
                        :buttons
                        invoke-virtual {v0}, Landroid/view/Window;->getDecorView()Landroid/view/View;
                        move-result-object v0
                        invoke-virtual {v0}, Landroid/view/View;->getSystemUiVisibility()I
                        move-result v1
                        if-eqz v2, :light
                        and-int/lit8 v1, v1, -0x11
                        goto :apply
                        :light
                        or-int/lit8 v1, v1, 0x10
                        :apply
                        invoke-virtual {v0, v1}, Landroid/view/View;->setSystemUiVisibility(I)V
                        :done
                        return-void
                    """,
                )
            },
        )
        val updateNavigationBar = method(TOOLBAR_COLOR, "updateNavigationBarColor", THEME_PARAM)
        val instructions = updateNavigationBar.implementation!!.instructions
        // The main path ends: state = getNavigationBarState(p1); DeviceLayoutUtil.setNavigationBarColor(context, state);
        // return. The early returns branch to that same return-void's label, so code put in front of it with a plain
        // insert runs on the main path only.
        val last = instructions.lastIndex
        val setColor = instructions.getOrNull(last - 1)
        val setColorReference = (setColor as? ReferenceInstruction)?.reference as? MethodReference
        if (instructions[last].opcode != Opcode.RETURN_VOID || setColorReference?.name != "setNavigationBarColor" ||
            setColorReference.definingClass != DEVICE_LAYOUT_UTIL
        ) {
            throw PatchException("ToolbarColor.updateNavigationBarColor no longer ends by setting the navigation bar colour")
        }
        val themeParamRegister = updateNavigationBar.implementation!!.registerCount - 1
        val getState = instructions.indexOfLast {
            ((it as? ReferenceInstruction)?.reference as? MethodReference)?.name == "getNavigationBarState"
        }
        if (getState < 0 || (instructions[getState] as FiveRegisterInstruction).registerD != themeParamRegister) {
            throw PatchException("ToolbarColor.updateNavigationBarColor: the theme no longer reaches getNavigationBarState")
        }
        val call = setColor as FiveRegisterInstruction
        updateNavigationBar.addInstructions(
            last,
            "invoke-static {v${call.registerC}, v${call.registerD}, v$themeParamRegister}, " +
                "$DEVICE_LAYOUT_UTIL->$navigationHelper(Landroid/content/Context;$NAVIGATION_BAR_STATE$THEME_PARAM)V",
        )
    }
}
