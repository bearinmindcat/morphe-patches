package org.ungoogled.patches.maps.ui.customization

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.string
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod.Companion.toMutable
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.builder.MutableMethodImplementation
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.NarrowLiteralInstruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.TwoRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.formats.Instruction35c
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.immutable.ImmutableMethod
import org.ungoogled.patches.shared.addInstructionsAtLabel

/*
 * Maps' account sheet ("Customize account menu"), found by shape so a new Maps build costs no
 * new names. Maps builds the sheet's common rows in two builders (which one runs depends on app
 * state), each named by its trace label; both add a Settings row, then more rows, to one list:
 *
 *     list.add(SettingsRows.settings(context, new Listener(holder, case)))  // visual element 90537
 *     ...
 *     list.add(holder.yourData())                                           // visual element 90536
 *
 * The row holder's "Your data in Maps" row is the template for rows of ours: a builder from the
 * row class, an id, an icon, a title, a visual element, a click listener and a group.
 */

/** The legacy builder: GmmAccountMenuManagerProvider.customizeAccountMenuManager. */
private object LegacyAccountSheetFingerprint : Fingerprint(
    filters = listOf(string("CustomActionSpecProvider.getCommonActions")),
)

/** The modern builder, for the action-stack sheet. */
private object ModernAccountSheetFingerprint : Fingerprint(
    filters = listOf(string("CustomActionSpecProvider.getCommonActionsForActionStackMigration")),
)

/** Visual element ids Maps logs its rows with; Google's ids, not R8 names, so they hold across builds. */
private const val SETTINGS_VE = 90537
private const val YOUR_DATA_VE = 90536

private fun Instruction.methodRef() = (this as? ReferenceInstruction)?.reference as? MethodReference
private fun Instruction.fieldRef() = (this as? ReferenceInstruction)?.reference as? FieldReference
private fun Method.hasLiteral(value: Int) =
    implementation?.instructions?.any { (it as? NarrowLiteralInstruction)?.narrowLiteral == value } == true

/** One of the sheet's builders, at its Settings row: `listener.<init>(holder, ..)`, the row, `list.add(row)`. */
internal class AccountSheetBuilder(
    val method: MutableMethod,
    /** The register holding the row holder when the Settings listener is made. */
    val holderRegister: Int,
    /** The Settings row's result register: free after its add, the next row writes it before any read. */
    val rowRegister: Int,
    val listRegister: Int,
    /** The Settings row's add. */
    val addIndex: Int,
)

/** What a row of the sheet is made of, read off the row holder's "Your data in Maps" row. */
internal class AccountSheet(
    private val patchContext: BytecodePatchContext,
    val holder: String,
    val rowType: String,
    val listAdd: MethodReference,
    val builders: List<AccountSheetBuilder>,
    private val contextField: FieldReference,
    private val loadDrawable: MethodReference,
    private val newBuilder: MethodReference,
    private val setId: MethodReference,
    private val setTitle: MethodReference,
    private val setVisualElement: MethodReference,
    private val setListener: MethodReference,
    private val build: MethodReference,
    private val drawableField: FieldReference,
    private val groupField: FieldReference,
    private val groupValue: FieldReference,
) {
    private val builderType get() = newBuilder.returnType

    /**
     * Adds `<name>()` to the row holder: a row like "Your data in Maps", with a generated id (ids must be
     * unique on the sheet: Maps throws "appears in more than one action"), [title], Maps' drawable [icon]
     * (passed through the static Drawable -> Drawable method [iconTransform] if given), and a new
     * [listener] -- made with the holder's Context when [listenerTakesContext], else with none.
     */
    fun addRowBuilder(
        name: String,
        title: String,
        icon: Int,
        listener: String,
        listenerTakesContext: Boolean,
        iconTransform: String? = null,
    ) {
        val holderClass = patchContext.mutableClassDefBy(holder)
        val transform = iconTransform?.let { "invoke-static { v1 }, $it\nmove-result-object v1" } ?: ""
        if (holderClass.methods.any { it.name == name }) throw PatchException("$holder already has $name()")
        val newListener = if (listenerTakesContext) {
            "invoke-direct { v2, v0 }, $listener-><init>(Landroid/content/Context;)V"
        } else {
            "invoke-direct { v2 }, $listener-><init>()V"
        }
        holderClass.methods.add(
            ImmutableMethod(
                holder, name, emptyList(), rowType,
                AccessFlags.PUBLIC.value or AccessFlags.FINAL.value, null, null, MutableMethodImplementation(6),
            ).toMutable().apply {
                addInstructions(
                    0,
                    """
                        iget-object v0, p0, $holder->${contextField.name}:${contextField.type}
                        const v1, $icon
                        invoke-static { v0, v1 }, $loadDrawable
                        move-result-object v1
                        $transform
                        invoke-static { }, $newBuilder
                        move-result-object v3
                        invoke-static { }, Landroid/view/View;->generateViewId()I
                        move-result v4
                        invoke-virtual { v3, v4 }, $setId
                        iput-object v1, v3, $builderType->${drawableField.name}:${drawableField.type}
                        const-string v4, "$title"
                        invoke-virtual { v3, v4 }, $setTitle
                        const v4, $YOUR_DATA_VE
                        invoke-virtual { v3, v4 }, $setVisualElement
                        new-instance v2, $listener
                        $newListener
                        invoke-virtual { v3, v2 }, $setListener
                        sget-object v4, $groupValue
                        iput-object v4, v3, $builderType->${groupField.name}:${groupField.type}
                        invoke-virtual { v3 }, $build
                        move-result-object v0
                        return-object v0
                    """,
                )
            },
        )
    }

    /**
     * In both builders, adds the holder's row `<name>()` to the list right after Settings' (when [after] is
     * empty), or else right after the first of the rows [after] -- row builders of ours, placed already --
     * that is on the sheet.
     */
    fun placeRow(name: String, after: List<String> = emptyList()) {
        for (b in builders) {
            val instructions = b.method.implementation!!.instructions
            val (index, holderRegister) = if (after.isEmpty()) {
                b.addIndex to b.holderRegister
            } else {
                // Our own placement below: move-object vRow, vHolder; check-cast; invoke <row>; move-result; add.
                val call = after.firstNotNullOfOrNull { row ->
                    instructions.indexOfFirst { it.methodRef()?.let { r -> r.definingClass == holder && r.name == row } == true }.takeIf { it >= 0 }
                } ?: throw PatchException("none of $after is on the account sheet in ${b.method.definingClass}")
                if (call < 2 || instructions[call - 2].opcode != Opcode.MOVE_OBJECT || instructions[call + 2].opcode != Opcode.INVOKE_VIRTUAL) {
                    throw PatchException("a row of ours is placed differently on the account sheet in ${b.method.definingClass}")
                }
                (call + 2) to (instructions[call - 2] as TwoRegisterInstruction).registerB
            }
            val row = b.rowRegister
            if (holderRegister > 15 || row > 15 || b.listRegister > 15) {
                throw PatchException("account sheet registers out of range: v$holderRegister, v$row, v${b.listRegister}")
            }
            val smali = """
                move-object v$row, v$holderRegister
                check-cast v$row, $holder
                invoke-virtual { v$row }, $holder->$name()$rowType
                move-result-object v$row
                invoke-virtual { v${b.listRegister}, v$row }, $listAdd
            """
            // Right after Settings: at the next instruction's label, so every path past the add gets the
            // row. After a row of ours that label is already on our code.
            if (after.isEmpty()) b.method.addInstructionsAtLabel(index + 1, smali) else b.method.addInstructions(index + 1, smali)
        }
    }
}

/**
 * Locates the sheet's builders, row holder and row parts; every part must be found exactly. Indices go
 * stale once a builder is edited, so call it again for each change.
 */
internal fun BytecodePatchContext.accountSheet(): AccountSheet {
    var settingsFactory: MethodReference? = null
    var holder: String? = null
    var template: MethodReference? = null
    var listAdd: MethodReference? = null
    val builders = listOf(LegacyAccountSheetFingerprint, ModernAccountSheetFingerprint).map { fp ->
        val method = fp.method
        val instructions = method.implementation!!.instructions.toList()
        fun resolve(ref: MethodReference) = classDefByOrNull(ref.definingClass)?.methods?.singleOrNull {
            it.name == ref.name && it.parameterTypes.map(CharSequence::toString) == ref.parameterTypes.map(CharSequence::toString) &&
                it.returnType == ref.returnType
        }
        // The Settings row: a static (Context, OnClickListener) factory logging visual element 90537.
        val settings = instructions.indices.filter { i ->
            val ref = instructions[i].methodRef() ?: return@filter false
            instructions[i].opcode == Opcode.INVOKE_STATIC &&
                ref.parameterTypes.map(CharSequence::toString) == listOf("Landroid/content/Context;", "Landroid/view/View\$OnClickListener;") &&
                resolve(ref)?.hasLiteral(SETTINGS_VE) == true
        }
        val at = settings.singleOrNull() ?: throw PatchException("Settings row in ${method.definingClass}: found ${settings.size}")
        val factory = instructions[at].methodRef()!!
        if (settingsFactory != null && settingsFactory != factory) throw PatchException("the sheet's builders make Settings differently")
        settingsFactory = factory
        val result = instructions[at + 1]
        val add = instructions[at + 2]
        if (result.opcode != Opcode.MOVE_RESULT_OBJECT || add.opcode != Opcode.INVOKE_VIRTUAL) {
            throw PatchException("Settings row in ${method.definingClass} is not added straight away")
        }
        val addRef = add.methodRef()!!
        if (addRef.parameterTypes.map(CharSequence::toString) != listOf("Ljava/lang/Object;") || addRef.returnType != "V") {
            throw PatchException("Settings row in ${method.definingClass} is added with $addRef")
        }
        if (listAdd != null && listAdd != addRef) throw PatchException("the sheet's builders add rows differently")
        listAdd = addRef
        // The listener made for it: `new Listener(holder, case)`.
        val listener = (at - 1 downTo 0).firstOrNull { i ->
            instructions[i].opcode == Opcode.INVOKE_DIRECT && instructions[i].methodRef()?.name == "<init>" &&
                (instructions[i] as Instruction35c).registerCount >= 2
        } ?: throw PatchException("Settings row listener not found in ${method.definingClass}")
        // The holder's "Your data in Maps" row, which every builder also adds: a no-argument row
        // method logging visual element 90536.
        val yourData = instructions.mapNotNull { it.methodRef() }.filter { ref ->
            // (Rows of ours, ua*, are made like it and left out.)
            ref.parameterTypes.isEmpty() && ref.returnType == factory.returnType && !ref.name.startsWith("ua") &&
                resolve(ref)?.hasLiteral(YOUR_DATA_VE) == true
        }.distinct()
        val row = yourData.singleOrNull() ?: throw PatchException("\"Your data in Maps\" row in ${method.definingClass}: found ${yourData.size}")
        if (holder != null && holder != row.definingClass) throw PatchException("the sheet's builders use different row holders")
        holder = row.definingClass
        template = row
        AccountSheetBuilder(
            method = method,
            holderRegister = (instructions[listener] as Instruction35c).registerD,
            rowRegister = (result as OneRegisterInstruction).registerA,
            listRegister = (add as Instruction35c).registerC,
            addIndex = at + 2,
        )
    }

    // The template's parts, by role.
    val rowType = settingsFactory!!.returnType
    val code = classDefBy(holder!!).methods.single { it.name == template!!.name && it.parameterTypes.isEmpty() && it.returnType == rowType }
        .implementation!!.instructions.toList()
    val p0 = classDefBy(holder!!).methods.single { it.name == template!!.name && it.parameterTypes.isEmpty() && it.returnType == rowType }
        .implementation!!.registerCount - 1
    fun <T> one(what: String, found: List<T>): T = found.distinct().singleOrNull()
        ?: throw PatchException("\"Your data in Maps\" row: $what found ${found.distinct().size} times")
    val context = one("its Context", code.filter { it.opcode == Opcode.IGET_OBJECT && (it as TwoRegisterInstruction).registerB == p0 }.map { it.fieldRef()!! }.take(1))
    val loadDrawable = one("the drawable loader", code.filter { it.opcode == Opcode.INVOKE_STATIC }.mapNotNull { it.methodRef() }.filter {
        it.returnType == "Landroid/graphics/drawable/Drawable;" && it.parameterTypes.map(CharSequence::toString) == listOf("Landroid/content/Context;", "I")
    })
    val newBuilder = one("the row builder", code.filter { it.opcode == Opcode.INVOKE_STATIC }.mapNotNull { it.methodRef() }.filter {
        it.parameterTypes.isEmpty() && it.definingClass == rowType
    })
    val builderType = newBuilder.returnType
    val builderCalls = code.indices.filter { code[it].opcode == Opcode.INVOKE_VIRTUAL && code[it].methodRef()?.definingClass == builderType }
    fun calls(vararg parameters: String) = builderCalls.filter { code[it].methodRef()!!.parameterTypes.map(CharSequence::toString) == parameters.toList() }
    val intSetters = calls("I")
    val setVisualElement = one("the visual element setter", intSetters.filter { i ->
        (i - 1 downTo 0).firstOrNull { code[it] is NarrowLiteralInstruction }?.let { (code[it] as NarrowLiteralInstruction).narrowLiteral } == YOUR_DATA_VE
    }.map { code[it].methodRef()!! })
    val setId = one("the id setter", intSetters.map { code[it].methodRef()!! }.filter { it != setVisualElement })
    val setTitle = one("the title setter", calls("Ljava/lang/String;").map { code[it].methodRef()!! })
    val setListener = one("the click listener setter", calls("Landroid/view/View\$OnClickListener;").map { code[it].methodRef()!! })
    val build = one("the build call", builderCalls.map { code[it].methodRef()!! }.filter { it.parameterTypes.isEmpty() && it.returnType == rowType })
    val puts = code.filter { it.opcode == Opcode.IPUT_OBJECT }.mapNotNull { it.fieldRef() }.filter { it.definingClass == builderType }
    val drawableField = one("the icon field", puts.filter { it.type == "Landroid/graphics/drawable/Drawable;" })
    val groupField = one("the group field", puts.filter { it.type != "Landroid/graphics/drawable/Drawable;" })
    val groupValue = one("the group", code.filter { it.opcode == Opcode.SGET_OBJECT }.mapNotNull { it.fieldRef() }.filter { it.type == groupField.type })

    return AccountSheet(
        this, holder!!, rowType, listAdd!!, builders,
        context, loadDrawable, newBuilder, setId, setTitle, setVisualElement, setListener, build,
        drawableField, groupField, groupValue,
    )
}
