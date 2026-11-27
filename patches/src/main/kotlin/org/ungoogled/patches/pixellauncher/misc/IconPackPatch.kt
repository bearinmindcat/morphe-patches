package org.ungoogled.patches.pixellauncher.misc

import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.extensions.InstructionExtensions.addInstructionsWithLabels
import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import app.morphe.patcher.util.smali.ExternalLabel
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.builder.BuilderOffsetInstruction
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.iface.reference.StringReference
import org.ungoogled.patches.pixellauncher.PixelLauncherConstants.COMPATIBILITY_PIXEL_LAUNCHER

internal const val PIXEL_LAUNCHER_EXTENSION = "extensions/pixellauncher.mpe"

private const val ICON_PACKS = "Lorg/ungoogled/pixellauncher/IconPacks;"
private const val PREFS = "Lorg/ungoogled/pixellauncher/Prefs;"
private const val ROW = "Lorg/ungoogled/pixellauncher/IconPackPreference;"

private const val ICON_PROVIDER = "Lcom/android/launcher3/icons/IconProvider;"
private const val ICON_FACTORY = "Lcom/android/launcher3/icons/BaseIconFactory;"
private const val ICON_OPTIONS = "Lcom/android/launcher3/icons/BaseIconFactory\$IconOptions;"
private const val THEME_MANAGER = "Lcom/android/launcher3/graphics/ThemeManager;"
private const val SETTINGS_FRAGMENT = "Lcom/android/launcher3/settings/SettingsActivity\$LauncherSettingsFragment;"

private const val PREFERENCE = "Landroidx/preference/Preference;"
private const val PREFERENCE_GROUP = "Landroidx/preference/PreferenceGroup;"
private const val PREFERENCE_SCREEN = "Landroidx/preference/PreferenceScreen;"
private const val PREFERENCE_FRAGMENT = "Landroidx/preference/PreferenceFragmentCompat;"

/** Theme code ThemeManager uses when no icon theme (themed icons) is chosen. */
private const val NO_THEME = "no-theme"

/**
 * Icon packs for Pixel Launcher (Android 16 and 17 builds). Every app icon the launcher shows -- home screen, apps
 * list, folders, search, taskbar, recents -- comes from IconProvider.getIcon into the icon cache, which is keyed on
 * the theme manager's icon state; the patch hooks those, the icon factory, and the Home settings screen. See the
 * extension's IconPacks for what each hook does. No manifest change, so it works on a root (mount) install.
 */
@Suppress("unused")
val pixelLauncherIconPackPatch = bytecodePatch(
    name = "Icon pack",
    description = "Adds an icon pack choice to Home settings. Root (mount) install only.",
    default = true,
) {
    compatibleWith(COMPATIBILITY_PIXEL_LAUNCHER)
    extendWith(PIXEL_LAUNCHER_EXTENSION)

    execute {
        bridgePreferences()
        hookIconProvider()
        hookIconFactory()
        hookThemeManager()
        hookFullDrawables()
        hookSettings()
    }
}

private fun BytecodePatchContext.requireClass(type: String, what: String): ClassDef =
    classDefByOrNull(type) ?: throw PatchException(
        "$what (${type.removePrefix("L").removeSuffix(";").replace('/', '.')}) not found: this Pixel Launcher build " +
            "isn't supported (Android 16 and 17 builds are)",
    )

private fun MutableMethod.params(): Int =
    parameterTypes.sumOf { if (it == "J" || it == "D") 2 else 1 } + if (AccessFlags.STATIC.isSet(accessFlags)) 0 else 1

/** Number of non-parameter registers, i.e. registers free at the method's first instruction. */
private fun MutableMethod.locals(): Int = implementation!!.registerCount - params()

private fun MutableMethod.where() = "${definingClass.substringAfterLast('/').removeSuffix(";")}.$name"

/**
 * The Home settings row is the extension's own Preference subclass; everything else it does with androidx.preference
 * goes through the extension's Prefs bridges, which get bodies here calling the app's members. R8 renames or drops
 * members between builds (Android 16's click hook is onClick$1, and it has no setOrder), so each is looked up by name
 * and, where builds differ, by structure.
 */
private fun BytecodePatchContext.bridgePreferences() {
    val preference = requireClass(PREFERENCE, "Home settings' Preference")
    requireClass(PREFERENCE_GROUP, "Home settings' PreferenceGroup")
    requireClass(PREFERENCE_FRAGMENT, "Home settings' PreferenceFragmentCompat")

    fun find(type: String, name: String, params: List<String>, returns: String): String? {
        var t: ClassDef? = classDefByOrNull(type)
        while (t != null) {
            if (t.methods.any { it.name == name && it.parameterTypes.map(CharSequence::toString) == params && it.returnType == returns }) {
                return "$type->$name(${params.joinToString("")})$returns"
            }
            t = t.superclass?.let { classDefByOrNull(it) }
        }
        return null
    }
    fun need(type: String, name: String, params: List<String>, returns: String) = find(type, name, params, returns)
        ?: throw PatchException("${type.substringAfterLast('/').removeSuffix(";")}.$name not found; this Pixel Launcher build isn't supported")
    fun bridge(name: String, smali: String) {
        mutableClassDefBy(PREFS).methods.singleOrNull { it.name == name }?.addInstructions(0, smali)
            ?: throw PatchException("$PREFS->$name missing from the extension")
    }

    if (find(PREFERENCE, "<init>", listOf("Landroid/content/Context;", "Landroid/util/AttributeSet;"), "V") == null) {
        throw PatchException("Preference(Context, AttributeSet) not found; this Pixel Launcher build isn't supported")
    }
    for ((name, param) in listOf("setKey" to "Ljava/lang/String;", "setTitle" to "Ljava/lang/CharSequence;", "setSummary" to "Ljava/lang/CharSequence;")) {
        val target = need(PREFERENCE, name, listOf(param), "V")
        bridge(name, "check-cast p0, $PREFERENCE\ninvoke-virtual { p0, p1 }, $target\nreturn-void")
    }
    // setOrder, or the field it sets (Android 16's androidx has the field only).
    val setOrder = find(PREFERENCE, "setOrder", listOf("I"), "V")
    val orderField = preference.fields.singleOrNull { it.name == "mOrder" && it.type == "I" }
    when {
        setOrder != null -> bridge("setOrder", "check-cast p0, $PREFERENCE\ninvoke-virtual { p0, p1 }, $setOrder\nreturn-void")
        orderField != null -> bridge("setOrder", "check-cast p0, $PREFERENCE\niput p1, p0, $PREFERENCE->mOrder:I\nreturn-void")
        else -> Unit // The row goes at the end.
    }
    val add = need(PREFERENCE_GROUP, "addPreference", listOf(PREFERENCE), "V")
    bridge("add", "check-cast p0, $PREFERENCE_GROUP\ncheck-cast p1, $PREFERENCE\ninvoke-virtual { p0, p1 }, $add\nreturn-void")
    val findPreference = need(PREFERENCE_GROUP, "findPreference", listOf("Ljava/lang/CharSequence;"), PREFERENCE)
    bridge("find", "check-cast p0, $PREFERENCE_GROUP\ninvoke-virtual { p0, p1 }, $findPreference\nmove-result-object p0\nreturn-object p0")
    val screen = need(PREFERENCE_FRAGMENT, "getPreferenceScreen", listOf(), PREFERENCE_SCREEN)
    bridge("screen", "check-cast p0, $PREFERENCE_FRAGMENT\ninvoke-virtual { p0 }, $screen\nmove-result-object p0\nreturn-object p0")

    // The click hook: the first no-argument call on the row itself in performClick(View), after isEnabled().
    val performClick = preference.methods.singleOrNull {
        it.name == "performClick" && it.parameterTypes.map(CharSequence::toString) == listOf("Landroid/view/View;")
    } ?: throw PatchException("Preference.performClick(View) not found; this Pixel Launcher build isn't supported")
    val clickName = performClick.implementation!!.instructions.mapNotNull {
        ((it as? ReferenceInstruction)?.reference as? MethodReference)?.takeIf { r ->
            it.opcode == Opcode.INVOKE_VIRTUAL && r.definingClass == PREFERENCE && r.parameterTypes.isEmpty() && r.returnType == "V"
        }?.name
    }.firstOrNull() ?: throw PatchException("Preference.performClick: no click hook call")
    val click = preference.methods.single { it.name == clickName && it.parameterTypes.isEmpty() && it.returnType == "V" }
    if (AccessFlags.FINAL.isSet(click.accessFlags) || AccessFlags.PRIVATE.isSet(click.accessFlags)) {
        throw PatchException("Preference.$clickName() can't be overridden in this Pixel Launcher build")
    }
    val override = mutableClassDefBy(ROW).methods.singleOrNull { it.name == "onClick" && it.parameterTypes.isEmpty() }
        ?: throw PatchException("$ROW->onClick missing from the extension")
    override.name = clickName
}

/**
 * IconProvider.getIcon(PackageItemInfo, ApplicationInfo, int), where every app icon is loaded: ask the extension
 * first, with the provider's Context.
 */
private fun BytecodePatchContext.hookIconProvider() {
    val provider = requireClass(ICON_PROVIDER, "The icon provider")
    val contextField = provider.fields.singleOrNull {
        it.type == "Landroid/content/Context;" && !AccessFlags.STATIC.isSet(it.accessFlags)
    } ?: throw PatchException("IconProvider: no single Context field")
    val params = listOf("Landroid/content/pm/PackageItemInfo;", "Landroid/content/pm/ApplicationInfo;", "I")
    // getIcon, not loadPackageIcon (same parameters): the one calling the other.
    val candidates = mutableClassDefBy(ICON_PROVIDER).methods.filter {
        it.parameterTypes.map(CharSequence::toString) == params && it.returnType == "Landroid/graphics/drawable/Drawable;" &&
            !AccessFlags.STATIC.isSet(it.accessFlags) && it.implementation != null
    }
    val method = candidates.singleOrNull() ?: candidates.singleOrNull { m ->
        m.implementation!!.instructions.any { insn ->
            val ref = (insn as? ReferenceInstruction)?.reference as? MethodReference
            ref != null && ref.definingClass == ICON_PROVIDER && ref.name != m.name && candidates.any { it.name == ref.name }
        }
    } ?: throw PatchException("IconProvider: no getIcon(PackageItemInfo, ApplicationInfo, int)")
    if (method.locals() < 4) throw PatchException("${method.where()}: too few registers")
    method.addInstructionsWithLabels(
        0,
        """
            move-object/from16 v0, p0
            iget-object v0, v0, $ICON_PROVIDER->${contextField.name}:Landroid/content/Context;
            move-object/from16 v1, p1
            move-object/from16 v2, p2
            move/from16 v3, p3
            invoke-static { v0, v1, v2, v3 }, $ICON_PACKS->icon(Landroid/content/Context;Landroid/content/pm/PackageItemInfo;Landroid/content/pm/ApplicationInfo;I)Landroid/graphics/drawable/Drawable;
            move-result-object v0
            if-eqz v0, :stock
            return-object v0
        """,
        ExternalLabel("stock", method.implementation!!.instructions[0]),
    )
}

/**
 * BaseIconFactory.createBadgedIconBitmap(Drawable, IconOptions): the extension turns off wrapping and full-bleed
 * drawing for a pack's icon (both IconOptions fields must exist: the extension sets them by name).
 */
private fun BytecodePatchContext.hookIconFactory() {
    val options = requireClass(ICON_OPTIONS, "The icon options")
    for ((field, type) in listOf("wrapNonAdaptiveIcon" to "Z", "drawFullBleed" to "Ljava/lang/Boolean;")) {
        options.fields.singleOrNull { it.name == field && it.type == type && AccessFlags.PUBLIC.isSet(it.accessFlags) }
            ?: throw PatchException("IconOptions: no public $field; this Pixel Launcher build isn't supported")
    }
    requireClass(ICON_FACTORY, "The icon factory")
    val method = mutableClassDefBy(ICON_FACTORY).methods.singleOrNull {
        it.parameterTypes.map(CharSequence::toString) == listOf("Landroid/graphics/drawable/Drawable;", ICON_OPTIONS) &&
            it.returnType == "Lcom/android/launcher3/icons/BitmapInfo;" && it.implementation != null
    } ?: throw PatchException("BaseIconFactory: no createBadgedIconBitmap(Drawable, IconOptions)")
    if (method.locals() < 2) throw PatchException("${method.where()}: too few registers")
    method.addInstructions(
        0,
        """
            move-object/from16 v0, p1
            move-object/from16 v1, p2
            invoke-static { v0, v1 }, $ICON_PACKS->options(Landroid/graphics/drawable/Drawable;Ljava/lang/Object;)V
        """,
    )
}

/**
 * ThemeManager.parseIconState: keep the manager (with its Context) for refreshing; with a pack on, no icon theme
 * (themed icons off) and the pack added to the theme code. The extension's verifyIconState(Object) gets a body
 * calling the manager's own icon-state check, which reloads every icon when the theme code changed.
 */
private fun BytecodePatchContext.hookThemeManager() {
    val manager = requireClass(THEME_MANAGER, "The theme manager")
    val parse = manager.methods.singleOrNull { m ->
        m.implementation?.instructions?.any {
            ((it as? ReferenceInstruction)?.reference as? StringReference)?.string == NO_THEME
        } == true
    } ?: throw PatchException("ThemeManager: no single method with the \"$NO_THEME\" theme code")
    val contextField = manager.fields.singleOrNull {
        it.type == "Landroid/content/Context;" && !AccessFlags.STATIC.isSet(it.accessFlags)
    } ?: throw PatchException("ThemeManager: no single Context field")
    val parseRef = "$THEME_MANAGER->${parse.name}("
    val verify = manager.methods.filter { m ->
        m.parameterTypes.isEmpty() && m.returnType == "V" && !AccessFlags.STATIC.isSet(m.accessFlags) &&
            m.implementation?.instructions?.any {
                ((it as? ReferenceInstruction)?.reference as? MethodReference)?.toString()?.startsWith(parseRef) == true
            } == true
    }.let { found -> found.singleOrNull() ?: found.singleOrNull { it.name == "verifyIconState" } }
        ?: throw PatchException("ThemeManager: no single icon-state check calling ${parse.name}")

    val method = mutableClassDefBy(THEME_MANAGER).methods.first {
        it.name == parse.name && it.parameterTypes == parse.parameterTypes && it.returnType == parse.returnType
    }
    val instructions = method.implementation!!.instructions.toList()
    val noTheme = instructions.indexOfFirst {
        ((it as? ReferenceInstruction)?.reference as? StringReference)?.string == NO_THEME
    }
    val codeRegister = (instructions[noTheme] as OneRegisterInstruction).registerA

    // The theme value: the register tested by the branch that jumps to the "no-theme" constant (value == null).
    val builder = method.implementation!!.instructions
    val test = (0 until noTheme).lastOrNull { i ->
        builder[i].opcode == Opcode.IF_EQZ && (builder[i] as BuilderOffsetInstruction).target.location.index == noTheme
    } ?: throw PatchException("${method.where()}: no null test choosing \"$NO_THEME\"")
    val valueRegister = (instructions[test] as OneRegisterInstruction).registerA
    val valueResult = (test - 1 downTo 0).firstOrNull { i ->
        instructions[i].opcode == Opcode.MOVE_RESULT_OBJECT && (instructions[i] as OneRegisterInstruction).registerA == valueRegister
    } ?: throw PatchException("${method.where()}: the theme value isn't a call result")
    val getValue = instructions[valueResult - 1] as? ReferenceInstruction
    val valueType = (getValue?.reference as? MethodReference)?.returnType
        ?: throw PatchException("${method.where()}: the theme value isn't a call result")
    if (method.locals() < 2) throw PatchException("${method.where()}: too few registers")

    // Later insertion points first, so the earlier indexes stay valid.
    method.addInstructions(
        noTheme + 1,
        """
            invoke-static/range { v$codeRegister .. v$codeRegister }, $ICON_PACKS->themeCode(Ljava/lang/String;)Ljava/lang/String;
            move-result-object v$codeRegister
        """,
    )
    method.addInstructions(
        valueResult + 1,
        """
            invoke-static/range { v$valueRegister .. v$valueRegister }, $ICON_PACKS->themeValue(Ljava/lang/Object;)Ljava/lang/Object;
            move-result-object v$valueRegister
            check-cast v$valueRegister, $valueType
        """,
    )
    method.addInstructions(
        0,
        """
            move-object/from16 v0, p0
            iget-object v1, v0, $THEME_MANAGER->${contextField.name}:Landroid/content/Context;
            invoke-static { v0, v1 }, $ICON_PACKS->themeManager(Ljava/lang/Object;Landroid/content/Context;)V
        """,
    )

    mutableClassDefBy(ICON_PACKS).methods.singleOrNull {
        it.name == "verifyIconState" && it.parameterTypes.map(CharSequence::toString) == listOf("Ljava/lang/Object;")
    }?.addInstructions(
        0,
        """
            check-cast p0, $THEME_MANAGER
            invoke-virtual { p0 }, $THEME_MANAGER->${verify.name}()V
        """,
    ) ?: throw PatchException("$ICON_PACKS->verifyIconState missing from the extension")
}

/**
 * Every getFullDrawable call (IconLoader's on Android 17, Utilities' on 16): the full-size adaptive icon the drag
 * preview and the app open/close animation draw. For a pack's icon that is a shrunk copy on a white plate; the
 * extension answers "none" instead, which those callers already handle by using the icon as drawn.
 */
private fun BytecodePatchContext.hookFullDrawables() {
    val sites = mutableListOf<Pair<String, Method>>()
    classDefForEach { classDef ->
        if (!classDef.type.startsWith("Lcom/android/launcher3/")) return@classDefForEach
        for (method in classDef.methods) {
            val found = method.implementation?.instructions?.any {
                val ref = (it as? ReferenceInstruction)?.reference as? MethodReference
                ref != null && ref.name == "getFullDrawable" && ref.definingClass.startsWith("Lcom/android/launcher3/") &&
                    ref.returnType.startsWith("L")
            } == true
            if (found) sites += classDef.type to method
        }
    }
    var hooked = 0
    for ((type, original) in sites) {
        val method = mutableClassDefBy(type).methods.first {
            it.name == original.name && it.parameterTypes == original.parameterTypes && it.returnType == original.returnType
        }
        val calls = method.implementation!!.instructions.withIndex().filter { (_, insn) ->
            val ref = (insn as? ReferenceInstruction)?.reference as? MethodReference
            ref != null && ref.name == "getFullDrawable" && ref.definingClass.startsWith("Lcom/android/launcher3/") &&
                ref.returnType.startsWith("L")
        }.map { it.index }
        // Last first, so earlier indexes stay valid.
        for (index in calls.reversed()) {
            val result = method.implementation!!.instructions.getOrNull(index + 1)
            if (result?.opcode != Opcode.MOVE_RESULT_OBJECT) continue
            val register = (result as OneRegisterInstruction).registerA
            val returnType = ((method.implementation!!.instructions[index] as ReferenceInstruction).reference as MethodReference).returnType
            method.addInstructions(
                index + 2,
                """
                    invoke-static/range { v$register .. v$register }, $ICON_PACKS->fullDrawable(Ljava/lang/Object;)Ljava/lang/Object;
                    move-result-object v$register
                    check-cast v$register, $returnType
                """,
            )
            hooked++
        }
    }
    if (hooked == 0) throw PatchException("No getFullDrawable call found; this Pixel Launcher build isn't supported")
}

/**
 * Home settings (LauncherSettingsFragment.onCreatePreferences): the extension notes which screen the fragment shows
 * (its parameters, at the start), and adds the "Icon pack" row to the main screen right after the screen is read
 * (before the launcher's own per-row checks, which keep unknown rows).
 */
private fun BytecodePatchContext.hookSettings() {
    requireClass(SETTINGS_FRAGMENT, "Home settings")
    val method = mutableClassDefBy(SETTINGS_FRAGMENT).methods.singleOrNull {
        it.parameterTypes.map(CharSequence::toString) == listOf("Landroid/os/Bundle;", "Ljava/lang/String;") &&
            it.returnType == "V" && it.implementation != null
    } ?: throw PatchException("Home settings: no onCreatePreferences(Bundle, String)")
    val instructions = method.implementation!!.instructions.toList()
    val read = instructions.indexOfFirst {
        it.opcode == Opcode.INVOKE_VIRTUAL &&
            ((it as ReferenceInstruction).reference as MethodReference).let { r ->
                r.name == "getPreferenceScreen" && r.parameterTypes.isEmpty() && r.returnType == PREFERENCE_SCREEN
            }
    }
    if (read < 0 || instructions.getOrNull(read + 1)?.opcode != Opcode.MOVE_RESULT_OBJECT) {
        throw PatchException("${method.where()}: the preference screen isn't read")
    }
    val fragmentRegister = (instructions[read] as FiveRegisterInstruction).registerC
    if (fragmentRegister > 15) throw PatchException("${method.where()}: registers out of range")
    method.addInstructions(
        read + 2,
        """
            invoke-static { v$fragmentRegister }, $ICON_PACKS->addRow(Ljava/lang/Object;)V
        """,
    )
    method.addInstructions(
        0,
        """
            invoke-static/range { p0 .. p2 }, $ICON_PACKS->preferences(Ljava/lang/Object;Landroid/os/Bundle;Ljava/lang/String;)V
        """,
    )
}
