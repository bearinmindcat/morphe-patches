package org.ungoogled.patches.samsungkeyboard.sboard

import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.patch.resourcePatch
import app.morphe.patcher.resource.ResourceType
import app.morphe.patcher.resource.hasResourceId
import app.morphe.patcher.resource.resourceId
import org.ungoogled.patches.samsungkeyboard.SamsungKeyboardConstants.COMPATIBILITY_SAMSUNG_KEYBOARD
import org.ungoogled.patches.samsungkeyboard.misc.SAMSUNG_KEYBOARD_EXTENSION

private const val KEYBOARD_SERVICE = "Lcom/samsung/android/honeyboard/service/HoneyBoardService;"
private const val INSETS = "Landroid/inputmethodservice/InputMethodService\$Insets;"
private const val IME = "Landroid/inputmethodservice/InputMethodService;"
private const val GLASS = "Lorg/ungoogled/samsungkeyboard/Glass;"

/** The keyboard's background (an image view behind the toolbar, the keys and the navigation bar's area). */
private const val BACKGROUND_VIEW = "layout_background"

/** The keyboard window's theme: Android's input method theme, translucent (Android blurs only behind those). */
private const val WINDOW_STYLE = "SBoardGlassWindow"

private val glassWindowThemePatch = resourcePatch(
    description = "Adds a translucent input method window theme.",
) {
    execute {
        document("res/values/styles.xml").use { xml ->
            xml.documentElement.appendChild(xml.createElement("style").apply {
                setAttribute("name", WINDOW_STYLE)
                setAttribute("parent", "@android:style/Theme.DeviceDefault.InputMethod")
                appendChild(xml.createElement("item").apply {
                    setAttribute("name", "android:windowIsTranslucent")
                    textContent = "true"
                })
            })
        }
    }
}

/**
 * Frosted glass (#219): the keyboard's background lets the app behind show through, blurred by Android (Android 12+,
 * where the phone blurs windows; otherwise a stronger tint). No keyboard build has a glass theme of its own (One UI's
 * blur is only used for pop-ups), so Glass does it at runtime: the keyboard window gets a translucent theme and a
 * background blur kept to the keyboard background's area, that background becomes partly transparent and plain fills
 * laid on it (toolbar strip, navigation bar area, emoji board) clear. Keys and panels over keys keep their backgrounds.
 */
@Suppress("unused")
val frostedGlassPatch = bytecodePatch(
    name = "Frosted glass",
    description = "Makes the keyboard background see-through and blurred.",
    default = false,
) {
    compatibleWith(COMPATIBILITY_SAMSUNG_KEYBOARD)
    category(SBOARD)
    dependsOn(glassWindowThemePatch)
    extendWith(SAMSUNG_KEYBOARD_EXTENSION)

    execute {
        if (!hasResourceId(ResourceType.ID, BACKGROUND_VIEW)) throw PatchException("the keyboard has no view $BACKGROUND_VIEW")
        mutableClassDefBy(GLASS).methods.single { it.name == "backgroundId" }
            .prepend("const v0, ${resourceId(ResourceType.ID, BACKGROUND_VIEW)}\nreturn v0")

        val service = mutableClassDefBy(KEYBOARD_SERVICE)
        // The window's theme has to be set before InputMethodService.onCreate creates the window.
        service.methods.firstOrNull { it.name == "onCreate" && it.parameterTypes.isEmpty() && it.returnType == "V" }
            ?.addInstructions(0, "invoke-static/range { p0 .. p0 }, $GLASS->theme($IME)V")
            ?: throw PatchException("the keyboard service's onCreate was not found")

        // Each time the keyboard works out its area (its window shows, resizes, changes mode), Glass sets the window up.
        // At the start: R8 reuses the parameter registers later in the method (the ...400 builds).
        service.methods.firstOrNull {
            it.name == "onComputeInsets" && it.returnType == "V" && it.parameterTypes.map(CharSequence::toString) == listOf(INSETS)
        }?.addInstructions(0, "invoke-static/range { p0 .. p0 }, $GLASS->shown($IME)V")
            ?: throw PatchException("the keyboard service's onComputeInsets was not found")
    }
}
