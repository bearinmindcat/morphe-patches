package org.ungoogled.patches.samsungkeyboard.sboard

import app.morphe.patcher.extensions.InstructionExtensions.replaceInstruction
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.patch.intOption
import app.morphe.patcher.patch.resourcePatch
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.instruction.NarrowLiteralInstruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.TwoRegisterInstruction
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.iface.reference.TypeReference
import org.ungoogled.patches.samsungkeyboard.SamsungKeyboardConstants.COMPATIBILITY_SAMSUNG_KEYBOARD
import org.ungoogled.patches.samsungkeyboard.misc.SAMSUNG_KEYBOARD_EXTENSION
import org.ungoogled.patches.shared.addInstructionsAtLabel
import org.w3c.dom.Document
import org.w3c.dom.Element

private fun Document.elements(tag: String): List<Element> =
    getElementsByTagName(tag).let { list -> (0 until list.length).map { list.item(it) as Element } }

/** The keyboard's pop-up tips (space bar, auto-replace, stickers) are all inflated from this one binding. */
private const val SMART_TIP_BINDING = "Lcom/samsung/android/honeyboard/base/databinding/SmartTipLayoutBinding;"

@Suppress("unused")
val hideTipsPatch = bytecodePatch(
    name = "Hide tips",
    description = "Stops the keyboard's pop-up tips.",
    default = false,
) {
    compatibleWith(COMPATIBILITY_SAMSUNG_KEYBOARD)
    category(SBOARD)

    execute {
        // Every tip is shown by one method of the tip pop-up's class (Context, anchor view, text, three ints, a flag,
        // ...); it now returns at once. The class is the one that inflates the tip's binding.
        val tipClasses = mutableSetOf<String>()
        classDefForEach { c ->
            if (c.methods.any { m ->
                    m.returnType == SMART_TIP_BINDING || m.parameterTypes.any { it.toString() == SMART_TIP_BINDING } ||
                        m.instructions().any { ((it as? ReferenceInstruction)?.reference as? TypeReference)?.type == SMART_TIP_BINDING }
                } && !c.type.startsWith("Lcom/samsung/android/honeyboard/base/databinding/")
            ) tipClasses += c.type
        }
        val shape = listOf("Landroid/content/Context;", "Landroid/view/View;", "Ljava/lang/String;", "I", "I", "I", "Z")
        val shows = methodsWhere { m ->
            m.definingClass in tipClasses && m.returnType == "V" && m.parameterTypes.map(CharSequence::toString).take(7) == shape
        }
        if (shows.isEmpty()) throw PatchException("the keyboard's tip pop-up was not found")
        shows.forEach { mutable(it).prepend("return-void") }
        sboardLog.info("Hide tips: ${shows.size} tip pop-ups")
    }
}

/** The "new" dots on toolbar buttons, the toolbar's "..." button and the emoji/sticker tabs. */
private val BADGES = setOf("@id/bee_item_badge", "@id/toolbar_expand_badge", "@id/expression_item_badge")
private val BADGE_LAYOUTS = listOf("bee_item", "bee_expand_item", "expression_item")

@Suppress("unused")
val hideNewBadgesPatch = resourcePatch(
    name = "Hide new badges",
    description = "Removes the \"new\" dots from the toolbar and emoji tabs.",
) {
    compatibleWith(COMPATIBILITY_SAMSUNG_KEYBOARD)
    category(SBOARD)

    execute {
        var badges = 0
        for (layout in BADGE_LAYOUTS) document("res/layout/$layout.xml").use { xml ->
            for (view in xml.elements("*").filter { it.getAttribute("android:id") in BADGES }) {
                // The code shows and hides the dot; with no size it never takes room or shows.
                view.setAttribute("android:layout_width", "0dp")
                view.setAttribute("android:layout_height", "0dp")
                badges++
            }
        }
        if (badges < BADGES.size) throw PatchException("only $badges of the ${BADGES.size} badge views were found")
    }
}

/** Logged when the sticker bar's store button can't name Galaxy Store; the button's code sits right before. */
private const val STORE_NAME_LOG = "getStoreName failed"

@Suppress("unused")
val hideStoreLinksPatch = bytecodePatch(
    name = "Hide store links",
    description = "Removes the Galaxy Store sticker button and Contact us.",
    default = false,
) {
    compatibleWith(COMPATIBILITY_SAMSUNG_KEYBOARD)
    category(SBOARD)

    execute {
        // The sticker bar's "+" opens Galaxy Store's sticker shop; the bar already hides it in some phone states (its
        // first test after fetching the button), which now always holds.
        var buttons = 0
        for (method in methodsWithString(STORE_NAME_LOG)) mutable(method).apply {
            val instructions = implementation!!.instructions
            // The button is shown with setVisibility(0) right after the test that skips to hiding it.
            // (A boolean flag -- a field, or a ()Z call's result -- not a null check such as the backspace button's.)
            fun flag(i: Int): Boolean {
                val register = (instructions[i] as OneRegisterInstruction).registerA
                val source = instructions.getOrNull(i - 1) ?: return false
                return when (source.opcode) {
                    Opcode.IGET_BOOLEAN -> (source as TwoRegisterInstruction).registerA == register
                    Opcode.MOVE_RESULT -> (source as OneRegisterInstruction).registerA == register &&
                        instructions.getOrNull(i - 2)?.call()?.endsWith(")Z") == true
                    else -> false
                }
            }
            val show = instructions.indices.firstOrNull { i ->
                i >= 3 && instructions[i].call() == "Landroid/view/View;->setVisibility(I)V" &&
                    (instructions[i - 1] as? NarrowLiteralInstruction)?.narrowLiteral == 0 &&
                    instructions[i - 2].opcode.let { op -> op == Opcode.IF_NEZ || op == Opcode.IF_EQZ } && flag(i - 2)
            } ?: throw PatchException("the sticker store button's hide test was not found")
            val register = (instructions[show - 2] as OneRegisterInstruction).registerA
            // A hide when the flag is set: if-nez jumps to it, if-eqz jumps past it.
            addInstructionsAtLabel(show - 2, "const/16 v$register, 0x1")
            buttons++
        }
        if (buttons == 0) throw PatchException("the sticker bar's store button ($STORE_NAME_LOG) was not found")

        // Settings: "Contact us" (Samsung Members).
        hideSettingsRows("CONTACT_US")
        sboardLog.info("Hide store links: $buttons store buttons")
    }
}

/** Read by the key-press vibration: 1-255 plays a one-shot of that many ms; anything else is the phone's own click. */
private const val VIBRATION_LENGTH = "custom_vibrate_duration_setting"

@Suppress("unused")
val keyVibrationLengthPatch = resourcePatch(
    name = "Key vibration length",
    description = "Adds a key vibration length slider to Touch feedback (One UI).",
    default = false,
) {
    compatibleWith(COMPATIBILITY_SAMSUNG_KEYBOARD)
    category(SBOARD)

    execute {
        document("res/values/strings.xml").use { strings ->
            strings.documentElement.appendChild(strings.createElement("string").apply {
                setAttribute("name", "sboard_vibration_length")
                textContent = "Vibration length"
            })
            strings.documentElement.appendChild(strings.createElement("string").apply {
                setAttribute("name", "sboard_vibration_length_summary")
                textContent = "All the way left: the phone's own key vibration."
            })
        }
        document("res/xml/settings_key_tap_feedback.xml").use { xml ->
            val vibration = xml.elements("*").singleOrNull { it.getAttribute("android:key") == "SETTINGS_DEFAULT_SUPPORT_KEY_VIBRATE" }
                ?: throw PatchException("Key-tap feedback has no vibration switch")
            // An int preference, as the keyboard stores this value.
            val slider = xml.createElement("androidx.preference.SeekBarPreference").apply {
                setAttribute("android:key", VIBRATION_LENGTH)
                setAttribute("android:title", "@string/sboard_vibration_length")
                setAttribute("android:persistent", "true")
                setAttribute("android:defaultValue", "0")
                setAttribute("android:max", "60")
                setAttribute("android:summary", "@string/sboard_vibration_length_summary")
            }
            vibration.parentNode.insertBefore(slider, vibration.nextSibling)
        }
    }
}

/** Logged with the backspace's repeat interval (ms) whenever it is read. */
private const val BACKSPACE_INTERVAL_LOG = "getRepeatIntervalTime : "

@Suppress("unused")
val fasterBackspacePatch = bytecodePatch(
    name = "Faster backspace",
    description = "Backspace deletes twice as fast at every speed.",
    default = false,
) {
    compatibleWith(COMPATIBILITY_SAMSUNG_KEYBOARD)
    category(SBOARD)
    extendWith(SAMSUNG_KEYBOARD_EXTENSION)

    execute {
        // Slow / Normal / Fast repeat every 140 / 100 / 60 ms (halved while the key keeps repeating); every answer is
        // halved again, so the three choices stay but each is quicker.
        val readers = methodsWithString(BACKSPACE_INTERVAL_LOG).filter { it.returnType == "I" && it.parameterTypes.isEmpty() }
        if (readers.isEmpty()) throw PatchException("the backspace repeat interval ($BACKSPACE_INTERVAL_LOG) was not found")
        for (reader in readers) mutable(reader).apply {
            val instructions = implementation!!.instructions
            for (index in instructions.indices.filter { instructions[it].opcode == Opcode.RETURN }.reversed()) {
                val register = (instructions[index] as OneRegisterInstruction).registerA
                val before = countReal()
                val smali = "invoke-static/range {v$register .. v$register}, $SBOARD_EXTENSION_CLASS->backspaceInterval(I)I\nmove-result v$register"
                addInstructionsAtLabel(index, smali)
                checkInserted(before, smali)
            }
        }
        sboardLog.info("Faster backspace: ${readers.size} readers")
    }
}

/** Dark and Solid dark keyboards' background, and the toolbar/suggestion bar above them. */
private val DARK_BACKGROUNDS = listOf("keyboard_background_dark", "keyboard_background_dark_solid", "rp_toolbar_background_color_dark")

@Suppress("unused")
val pureBlackThemePatch = resourcePatch(
    name = "Pure black theme",
    description = "Makes the Dark and Solid dark themes pure black.",
    default = false,
) {
    compatibleWith(COMPATIBILITY_SAMSUNG_KEYBOARD)
    category(SBOARD)

    execute {
        document("res/values/colors.xml").use { xml ->
            val colours = xml.elements("color").filter { it.getAttribute("name") in DARK_BACKGROUNDS }
            if (colours.size != DARK_BACKGROUNDS.size) throw PatchException("dark theme colours not found: ${colours.map { it.getAttribute("name") }}")
            colours.forEach { it.textContent = "#ff000000" }
        }
    }
}

@Suppress("unused")
val noContactsAccessPatch = resourcePatch(
    name = "No contacts access",
    description = "Removes the contacts permission (names in suggestions).",
    default = false,
) {
    compatibleWith(COMPATIBILITY_SAMSUNG_KEYBOARD)
    category(SBOARD)

    execute {
        // The keyboard checks the permission before every contacts read, so without it it just never reads them.
        document("AndroidManifest.xml").use { manifest ->
            val contacts = manifest.elements("uses-permission").filter { it.getAttribute("android:name") == "android.permission.READ_CONTACTS" }
            if (contacts.isEmpty()) throw PatchException("no READ_CONTACTS permission in the manifest")
            contacts.forEach { it.parentNode.removeChild(it) }
        }
    }
}

/** Every keyboard size table is checked by name as it is built; the same class answers "table for size type N". */
private const val SIZE_TABLE_NAME = "NORMAL_HEIGHT"

@Suppress("unused")
val biggerSizeRangePatch = bytecodePatch(
    name = "Bigger size range",
    description = "Lets the keyboard be resized smaller and taller.",
    default = false,
) {
    compatibleWith(COMPATIBILITY_SAMSUNG_KEYBOARD)
    category(SBOARD)
    extendWith(SAMSUNG_KEYBOARD_EXTENSION)

    execute {
        // Each table is {smallest, default, largest} as a fraction of the device's base size; every reader -- the resize
        // handles, the check that resets a size out of range, the restore -- asks this one getter.
        val tableClasses = methodsWithString(SIZE_TABLE_NAME).filter { it.name == "<init>" }.map { it.definingClass }.toSet()
        val getters = methodsWhere { m ->
            m.definingClass in tableClasses && !m.isStatic && m.returnType == "[F" &&
                m.parameterTypes.map(CharSequence::toString) == listOf("I")
        }
        val getter = getters.singleOrNull() ?: throw PatchException("size table getter not found (${getters.size} in $tableClasses)")
        mutable(getter).apply {
            val type = "p1"
            val instructions = implementation!!.instructions
            val returns = instructions.indices.filter { instructions[it].opcode == Opcode.RETURN_OBJECT }
            for (index in returns.reversed()) {
                val register = (instructions[index] as OneRegisterInstruction).registerA
                val before = countReal()
                val smali = "invoke-static {$type, v$register}, $SBOARD_EXTENSION_CLASS->sizeRange(I[F)[F\nmove-result-object v$register"
                addInstructionsAtLabel(index, smali)
                checkInserted(before, smali)
            }
            sboardLog.info("Bigger size range: ${getter.definingClass}->${getter.name}, ${returns.size} tables")
        }
    }
}

@Suppress("unused")
val moreToolbarButtonsPatch = bytecodePatch(
    name = "More toolbar buttons",
    description = "Shows more buttons on the toolbar before the ... button.",
    default = false,
) {
    compatibleWith(COMPATIBILITY_SAMSUNG_KEYBOARD)
    category(SBOARD)
    extendWith(SAMSUNG_KEYBOARD_EXTENSION)

    val count = intOption(
        key = "count",
        default = 9,
        values = mapOf("8" to 8, "9" to 9, "10" to 10),
        title = "Buttons",
        description = "Samsung's: 7.",
        required = true,
    ) { it != null && it in 8..12 }

    execute {
        // The toolbar's dump names its limit; the call right after it is the preset's "most buttons shown" method.
        val dump = methodsWithString("    Max Primary Bee Size : ").singleOrNull()
            ?: throw PatchException("the toolbar's state dump was not found")
        val code = dump.instructions()
        val at = code.indexOfFirst { it.string() == "    Max Primary Bee Size : " }
        val limit = code.drop(at).mapNotNull { (it as? ReferenceInstruction)?.reference as? MethodReference }
            .firstOrNull { it.returnType == "I" && it.parameterTypes.isEmpty() }
            ?: throw PatchException("the toolbar's limit call was not found")
        // The dump asks the toolbar's settings, which ask the preset: the type it names, the interfaces of it that
        // declare the same method, and every class implementing those.
        fun declares(type: String) = classDefByOrNull(type)?.methods?.any {
            it.name == limit.name && it.returnType == "I" && it.parameterTypes.isEmpty()
        } == true
        val owner = classDefByOrNull(limit.definingClass) ?: throw PatchException("${limit.definingClass} not found")
        val types = setOf(limit.definingClass) + owner.interfaces.filter(::declares)
        val presets = methodsWhere { m ->
            m.name == limit.name && m.returnType == "I" && m.parameterTypes.isEmpty() && !m.isStatic &&
                (m.definingClass in types || classDefByOrNull(m.definingClass)?.interfaces?.any { it in types } == true)
        }
        if (presets.isEmpty()) throw PatchException("no toolbar presets implement ${limit.definingClass}->${limit.name}")
        for (preset in presets) mutable(preset).apply {
            val instructions = implementation!!.instructions
            for (index in instructions.indices.filter { instructions[it].opcode == Opcode.RETURN }.reversed()) {
                val register = (instructions[index] as OneRegisterInstruction).registerA
                val before = countReal()
                val smali = "invoke-static/range {v$register .. v$register}, $SBOARD_EXTENSION_CLASS->toolbarLimit(I)I\nmove-result v$register"
                addInstructionsAtLabel(index, smali)
                checkInserted(before, smali)
            }
        }
        mutableClassDefBy(SBOARD_EXTENSION_CLASS).methods.single { it.name == "toolbarButtons" }
            .prepend("const/16 v0, ${count.value!!}\nreturn v0")
        sboardLog.info("More toolbar buttons: ${presets.size} presets")
    }
}

