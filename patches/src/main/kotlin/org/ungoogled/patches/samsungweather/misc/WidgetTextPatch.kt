package org.ungoogled.patches.samsungweather.misc

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.extensions.InstructionExtensions.replaceInstruction
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.patch.resourcePatch
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import org.w3c.dom.Element

private const val GET_OPTIONS = "getAppWidgetOptions"

/** The widget size helper's table builder (Samsung's Glance support library), which falls back where One UI gave none. */
private object WidgetSizeTableFingerprint : Fingerprint(
    returnType = "Ljava/lang/Object;",
    strings = listOf("empty size table - make fallback size table"),
)

private const val GLYPH_STRINGS = "res/values-v35/strings.xml"

/**
 * On Android 15 and later the current location's pin before the city name is U+F899, a glyph only One UI's system font
 * has: elsewhere it showed as a box on the widgets. It is left out, as Weather does on older Android.
 */
private val locationGlyphPatch = resourcePatch(
    description = "Leaves out One UI's location pin glyph where the font lacks it.",
) {
    execute {
        decode(GLYPH_STRINGS)
        document(GLYPH_STRINGS).use { strings ->
            val nodes = strings.getElementsByTagName("string")
            val pin = (0 until nodes.length).map { nodes.item(it) as Element }
                .firstOrNull { it.getAttribute("name") == "unicode_location_on" }
                ?: throw PatchException("no unicode_location_on string in $GLYPH_STRINGS")
            pin.textContent = ""
        }
    }
}

/**
 * Off One UI the widgets' text was small: text and padding sizes come from a one-row height that only One UI's home
 * screen hands widgets, and other launchers got a fixed 56 dp row however big the widget. The helper's fallback now
 * notes the widget's options (Widgets.getAppWidgetOptions) and takes its row height from Widgets.rowHeight: One UI's
 * reference row scaled like the widget against One UI's reference size, so the text keeps One UI's proportions.
 */
internal val widgetTextPatch = bytecodePatch(
    description = "Sizes widget text like One UI on other launchers.",
) {
    extendWith(SAMSUNG_WEATHER_EXTENSION)
    dependsOn(locationGlyphPatch)

    execute {
        WidgetSizeTableFingerprint.method.apply {
            fun MethodReference.shape() = "${parameterTypes.joinToString("")})$returnType"
            val instructions = implementation!!.instructions.toList()
            val options = instructions.indexOfFirst {
                it.opcode == Opcode.INVOKE_VIRTUAL &&
                    ((it as ReferenceInstruction).reference as MethodReference).let { r ->
                        r.definingClass == "Landroid/appwidget/AppWidgetManager;" && r.name == GET_OPTIONS
                    }
            }
            if (options < 0) throw PatchException("no getAppWidgetOptions call in the widget size helper")
            // The font table built from the row height: the first static (F) -> LinkedHashMap call after it.
            val fonts = (options + 1 until instructions.size).firstOrNull { i ->
                instructions[i].opcode == Opcode.INVOKE_STATIC &&
                    ((instructions[i] as ReferenceInstruction).reference as MethodReference).shape() == "F)Ljava/util/LinkedHashMap;"
            } ?: throw PatchException("no font table call after the widget options in the widget size helper")
            val row = (instructions[fonts] as FiveRegisterInstruction).registerC
            val call = instructions[options] as FiveRegisterInstruction

            addInstructions(
                fonts,
                """
                    invoke-static { v$row }, Lorg/ungoogled/samsungweather/Widgets;->rowHeight(F)F
                    move-result v$row
                """,
            )
            replaceInstruction(
                options,
                "invoke-static { v${call.registerC}, v${call.registerD} }, " +
                    "Lorg/ungoogled/samsungweather/Widgets;->getAppWidgetOptions(Landroid/appwidget/AppWidgetManager;I)Landroid/os/Bundle;",
            )
        }
    }
}
