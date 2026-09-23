package org.ungoogled.patches.maps.ui

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.extensions.InstructionExtensions.replaceInstruction
import app.morphe.patcher.methodCall
import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.string
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.builder.instruction.BuilderInstruction35c
import com.android.tools.smali.dexlib2.builder.instruction.BuilderInstruction3rc
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.NarrowLiteralInstruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.formats.Instruction35c
import com.android.tools.smali.dexlib2.iface.instruction.formats.Instruction3rc
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.iface.reference.TypeReference
import com.android.tools.smali.dexlib2.immutable.reference.ImmutableMethodReference
import org.ungoogled.patches.shared.addInstructionsAtLabel

/** org.ungoogled.ui.Shapes, the runtime half of the UI patches (extensions/extension). */
internal const val SHAPES = "Lorg/ungoogled/ui/Shapes;"

/** Merges org.ungoogled.ui.* into Maps. */
internal val sharedExtensionPatch = bytecodePatch(
    description = "Adds the Ungoogled Maps UI extension classes.",
) {
    extendWith("extensions/extension.mpe")
}

/**
 * Rewrites one of Shapes' "is this patch present" markers -- `return false` in
 * the extension -- to `return true`, so its feature can turn on and so the
 * Customization screen offers a toggle for it.
 */
internal fun BytecodePatchContext.markPatched(marker: String) {
    val method = mutableClassDefBy(SHAPES).methods.singleOrNull {
        it.name == marker && it.parameterTypes.isEmpty() && it.returnType == "Z"
    } ?: throw PatchException("extension marker $SHAPES->$marker() not found")
    val first = method.implementation!!.instructions.first()
    if (first.opcode != Opcode.CONST_4 || (first as NarrowLiteralInstruction).narrowLiteral != 0) {
        throw PatchException("$marker() no longer starts with const/4 v0, 0x0")
    }
    method.replaceInstruction(0, "const/4 v0, 0x1")
}

/**
 * AppCompatActivity.attachBaseContext -- the base class every Maps screen
 * descends from (MapsActivity -> ... -> AppCompatActivity). Its body is
 * AppCompat's inlined delegate (the only method in the app that reflects on
 * Theme.rebase), and it ends in exactly one super.attachBaseContext(context).
 */
internal object AppCompatAttachBaseContextFingerprint : Fingerprint(
    name = "attachBaseContext",
    returnType = "V",
    parameters = listOf("Landroid/content/Context;"),
    filters = listOf(
        string("rebase"),
        methodCall(opcode = Opcode.INVOKE_SUPER, name = "attachBaseContext"),
    ),
)

/**
 * Every Activity's base Context goes through Shapes.wrap(), which re-reads the
 * toggles and, while Rectangle shapes / Black theme are on, returns a copy
 * whose override Configuration selects the -mnc9999 / -mcc9999 resource
 * variants those patches add.
 *
 * The call sits right before the super call, where the context handed on is
 * final. That super call is a branch target (AppCompat reaches it from three
 * paths), so the wrap is inserted AT its label rather than in front of it --
 * otherwise the jump paths would skip it and the most common one would never
 * get the override.
 */
internal val activityContextHookPatch = bytecodePatch(
    description = "Routes every Activity's base context through the UI extension.",
) {
    dependsOn(sharedExtensionPatch)

    execute {
        AppCompatAttachBaseContextFingerprint.let { fp ->
            val superCall = fp.instructionMatches.last().index
            val registers = (fp.method.implementation!!.instructions[superCall] as Instruction35c)
            if (registers.registerCount != 2) throw PatchException("super.attachBaseContext takes ${registers.registerCount} registers")
            val context = "v${registers.registerD}"
            fp.method.addInstructionsAtLabel(
                superCall,
                """
                    invoke-static { $context }, $SHAPES->wrap(Landroid/content/Context;)Landroid/content/Context;
                    move-result-object $context
                """,
            )
        }
    }
}

/**
 * CommonGoogleMapsApplication.attachBaseContext (Maps' own trace label names
 * it): the Application base class, the first app code to run in every one of
 * Maps' processes, before its content providers, services or Activities.
 */
internal object ApplicationAttachBaseContextFingerprint : Fingerprint(
    name = "attachBaseContext",
    returnType = "V",
    parameters = listOf("Landroid/content/Context;"),
    filters = listOf(string("CommonGoogleMapsApplication.attachBaseContext")),
)

/**
 * Calls Shapes.processStart(context) first thing in every process, for the
 * settings that must cover a whole process rather than follow Activities: a
 * process started for a background job (an offline download resuming, say)
 * never creates an Activity, so wrap() alone would leave it unproxied.
 */
internal val applicationStartHookPatch = bytecodePatch(
    description = "Applies the UI extension's process-wide settings when the app starts.",
) {
    dependsOn(sharedExtensionPatch)

    execute {
        ApplicationAttachBaseContextFingerprint.method.apply {
            val first = implementation!!.instructions.first()
            if (first.location.labels.isNotEmpty()) throw PatchException("Application.attachBaseContext starts at a branch target")
            val context = "p1"
            addInstructions(
                0,
                "invoke-static { $context }, $SHAPES->processStart(Landroid/content/Context;)V",
            )
        }
    }
}

/**
 * Reroutes every framework corner-radius, shape and literal-colour call in the
 * app into Shapes' static shims, so Rectangle shapes and Black theme reach the
 * UI Maps builds in code rather than from XML resources:
 *
 *     invoke-virtual {v1, v2}, GradientDrawable;->setCornerRadius(F)V
 *  -> invoke-static  {v1, v2}, Shapes;->setCornerRadius(GradientDrawable;F)V
 *
 * The receiver becomes the first argument and the register list is unchanged,
 * so each call site is swapped in place (which keeps any label on it).
 * Constructors cannot be shimmed, so `new RoundRectShape/OvalShape` become the
 * extension's drop-in subclasses instead, as does the one Maps class that
 * extends RoundRectShape. The shims pass everything through untouched unless
 * their feature is present and switched on, so one pass serves both patches.
 *
 * Never applied to the extension itself: its shims call the real framework
 * methods, and rewriting those calls would make them call themselves.
 */
internal val shapeShimsPatch = bytecodePatch(
    description = "Routes framework shape and colour calls through the UI extension.",
) {
    dependsOn(sharedExtensionPatch)

    execute {
        val gd = "Landroid/graphics/drawable/GradientDrawable;"
        val path = "Landroid/graphics/Path;"
        val canvas = "Landroid/graphics/Canvas;"
        val outline = "Landroid/graphics/Outline;"
        val dir = "Landroid/graphics/Path\$Direction;"
        val rectF = "Landroid/graphics/RectF;"
        val rect = "Landroid/graphics/Rect;"
        val paint = "Landroid/graphics/Paint;"

        // (owner, method, parameters) -- the shim takes (owner, parameters...), same name, void.
        val virtuals = listOf(
            Triple(gd, "setCornerRadius", "F"), Triple(gd, "setCornerRadii", "[F"), Triple(gd, "setShape", "I"),
            Triple(path, "addRoundRect", rectF + "FF" + dir), Triple(path, "addRoundRect", rectF + "[F" + dir),
            Triple(path, "addRoundRect", "FFFFFF$dir"), Triple(path, "addRoundRect", "FFFF[F$dir"),
            Triple(path, "addOval", rectF + dir), Triple(path, "addOval", "FFFF$dir"), Triple(path, "addCircle", "FFF$dir"),
            Triple(canvas, "drawRoundRect", rectF + "FF" + paint), Triple(canvas, "drawRoundRect", "FFFFFF$paint"),
            Triple(canvas, "drawCircle", "FFF$paint"), Triple(canvas, "drawOval", rectF + paint), Triple(canvas, "drawOval", "FFFF$paint"),
            Triple(outline, "setRoundRect", "IIIIF"), Triple(outline, "setRoundRect", rect + "F"),
            Triple(outline, "setOval", "IIII"), Triple(outline, "setOval", rect),
            // Black theme: literal colours handed to the framework
            Triple(gd, "setColor", "I"), Triple("Landroid/graphics/drawable/ColorDrawable;", "setColor", "I"),
            Triple(paint, "setColor", "I"), Triple("Landroid/view/View;", "setBackgroundColor", "I"),
            Triple("Landroid/widget/TextView;", "setTextColor", "I"), Triple(canvas, "drawColor", "I"),
            Triple("Landroid/view/Window;", "setStatusBarColor", "I"), Triple("Landroid/view/Window;", "setNavigationBarColor", "I"),
            Triple("Landroid/graphics/drawable/Drawable;", "setTint", "I"),
            // draw-time Paint colour remap (Material elevation overlays etc.)
            Triple(canvas, "drawPath", path + paint), Triple(canvas, "drawRect", rectF + paint),
            Triple(canvas, "drawRect", rect + paint), Triple(canvas, "drawRect", "FFFF$paint"),
        )
        fun params(descriptor: String): List<String> {
            val out = mutableListOf<String>()
            var i = 0
            while (i < descriptor.length) {
                var j = i
                while (descriptor[j] == '[') j++
                j = if (descriptor[j] == 'L') descriptor.indexOf(';', j) + 1 else j + 1
                out += descriptor.substring(i, j)
                i = j
            }
            return out
        }
        fun key(owner: String, name: String, parameters: List<String>, returnType: String) =
            "$owner->$name(${parameters.joinToString("")})$returnType"

        val rules = HashMap<String, ImmutableMethodReference>()
        for ((owner, name, p) in virtuals) {
            val ps = params(p)
            rules[key(owner, name, ps, "V")] = ImmutableMethodReference(SHAPES, name, listOf(owner) + ps, "V")
        }
        val csl = "Landroid/content/res/ColorStateList;"
        val staticRules = mapOf(
            key(csl, "valueOf", listOf("I"), csl) to ImmutableMethodReference(SHAPES, "valueOf", listOf("I"), csl),
        )
        val shapeSubclasses = mapOf(
            "Landroid/graphics/drawable/shapes/RoundRectShape;" to "Lorg/ungoogled/ui/URoundRectShape;",
            "Landroid/graphics/drawable/shapes/OvalShape;" to "Lorg/ungoogled/ui/UOvalShape;",
        )
        // Colour arguments of two constructors, remapped in place right before the constructor runs.
        val ctorColourArgs = mapOf(
            key("Landroid/graphics/drawable/ColorDrawable;", "<init>", listOf("I"), "V") to (1 to "color(I)I"),
            key(csl, "<init>", listOf("[[I", "[I"), "V") to (2 to "colors([I)[I"),
        )

        var sites = 0
        var classes = 0
        val work = mutableListOf<Pair<String, Method>>()
        val reparent = mutableListOf<String>()
        classDefForEach { classDef ->
            if (classDef.type.startsWith("Lorg/ungoogled/")) return@classDefForEach
            if (classDef.superclass in shapeSubclasses) reparent += classDef.type
            for (method in classDef.methods) {
                val instructions = method.implementation?.instructions ?: continue
                if (instructions.any { insn -> rewriteKind(insn, rules, staticRules, shapeSubclasses, ctorColourArgs) != null }) {
                    work += classDef.type to method
                }
            }
        }
        // A subclass's constructor calls its parent's <init>, which is rewritten to the
        // extension's subclass below, so the declared parent has to move with it.
        for (type in reparent) {
            val mutableClass = mutableClassDefBy(type)
            mutableClass.setSuperClass(shapeSubclasses.getValue(mutableClass.superclass!!))
        }
        for ((type, method) in work) {
            val mutableClass = mutableClassDefBy(type)
            val mutableMethod = mutableClass.methods.first {
                it.name == method.name && it.parameterTypes == method.parameterTypes && it.returnType == method.returnType
            }
            sites += rewriteMethod(mutableMethod, rules, staticRules, shapeSubclasses, ctorColourArgs)
            classes++
        }
        if (sites == 0) throw PatchException("no framework shape or colour calls found to reroute")

        // Every rerouted framework method must be gone (invoke-super excepted: a
        // class calling its own parent implementation must keep doing so).
        val watched = listOf(
            "Landroid/graphics/drawable/GradientDrawable;->(setCornerRadius|setCornerRadii|setShape)\\(",
            "Landroid/graphics/Path;->(addRoundRect|addOval|addCircle)\\(",
            "Landroid/graphics/Canvas;->(drawRoundRect|drawCircle|drawOval|drawPath|drawRect)\\(",
            "Landroid/graphics/Outline;->(setRoundRect|setOval)\\(",
            "Landroid/graphics/drawable/shapes/(RoundRectShape|OvalShape);-><init>\\(",
            "Landroid/graphics/drawable/(GradientDrawable|ColorDrawable);->setColor\\(I\\)",
            "Landroid/graphics/Paint;->setColor\\(I\\)", "Landroid/view/View;->setBackgroundColor\\(",
            "Landroid/widget/TextView;->setTextColor\\(I\\)", "Landroid/graphics/Canvas;->drawColor\\(I\\)",
            "Landroid/view/Window;->set(StatusBar|NavigationBar)Color\\(", "Landroid/content/res/ColorStateList;->valueOf\\(",
        ).joinToString("|").toRegex()
        val leftovers = mutableListOf<String>()
        classDefForEach { classDef ->
            if (classDef.type.startsWith("Lorg/ungoogled/")) return@classDefForEach
            for (method in classDef.methods) {
                for (insn in method.implementation?.instructions ?: continue) {
                    if (insn.opcode == Opcode.INVOKE_SUPER || insn.opcode == Opcode.INVOKE_SUPER_RANGE) continue
                    val ref = (insn as? ReferenceInstruction)?.reference as? MethodReference ?: continue
                    val k = key(ref.definingClass, ref.name, ref.parameterTypes.map { it.toString() }, ref.returnType)
                    if (watched.containsMatchIn(k)) leftovers += "${classDef.type}->${method.name}: $k"
                }
            }
        }
        if (leftovers.isNotEmpty()) {
            throw PatchException("unhandled framework shape/colour call(s), add a shim for them:\n" + leftovers.take(10).joinToString("\n"))
        }
        logger.info("Shape/colour shims: $sites call sites in $classes methods")
    }
}

private val logger = java.util.logging.Logger.getLogger("ShapeShims")

private fun rewriteKind(
    insn: com.android.tools.smali.dexlib2.iface.instruction.Instruction,
    rules: Map<String, ImmutableMethodReference>,
    staticRules: Map<String, ImmutableMethodReference>,
    subclasses: Map<String, String>,
    ctorArgs: Map<String, Pair<Int, String>>,
): String? {
    val ref = (insn as? ReferenceInstruction)?.reference ?: return null
    if (insn.opcode == Opcode.NEW_INSTANCE) return if ((ref as TypeReference).type in subclasses) "new" else null
    if (ref !is MethodReference) return null
    val k = "${ref.definingClass}->${ref.name}(${ref.parameterTypes.joinToString("")})${ref.returnType}"
    return when (insn.opcode) {
        Opcode.INVOKE_VIRTUAL, Opcode.INVOKE_VIRTUAL_RANGE -> if (k in rules) "virtual" else null
        Opcode.INVOKE_STATIC, Opcode.INVOKE_STATIC_RANGE -> if (k in staticRules) "static" else null
        Opcode.INVOKE_DIRECT, Opcode.INVOKE_DIRECT_RANGE -> when {
            ref.name == "<init>" && ref.definingClass in subclasses -> "ctor"
            k in ctorArgs && insn.opcode == Opcode.INVOKE_DIRECT -> "ctorArg"
            else -> null
        }
        else -> null
    }
}

/** Rewrites every matching site in one method, last to first so indices stay valid. */
private fun rewriteMethod(
    method: MutableMethod,
    rules: Map<String, ImmutableMethodReference>,
    staticRules: Map<String, ImmutableMethodReference>,
    subclasses: Map<String, String>,
    ctorArgs: Map<String, Pair<Int, String>>,
): Int {
    val instructions = method.implementation!!.instructions
    var count = 0
    for (i in instructions.indices.reversed()) {
        val insn = instructions[i]
        val kind = rewriteKind(insn, rules, staticRules, subclasses, ctorArgs) ?: continue
        val ref = (insn as ReferenceInstruction).reference
        when (kind) {
            "new" -> {
                val type = subclasses.getValue((ref as TypeReference).type)
                method.replaceInstruction(i, "new-instance v${(insn as OneRegisterInstruction).registerA}, $type")
            }
            "virtual", "static", "ctor" -> {
                ref as MethodReference
                val target: MethodReference = when (kind) {
                    "virtual" -> rules.getValue("${ref.definingClass}->${ref.name}(${ref.parameterTypes.joinToString("")})${ref.returnType}")
                    "static" -> staticRules.getValue("${ref.definingClass}->${ref.name}(${ref.parameterTypes.joinToString("")})${ref.returnType}")
                    else -> ImmutableMethodReference(subclasses.getValue(ref.definingClass), "<init>", ref.parameterTypes, "V")
                }
                val opcodeRange = when (kind) { "ctor" -> Opcode.INVOKE_DIRECT_RANGE; else -> Opcode.INVOKE_STATIC_RANGE }
                val opcode = when (kind) { "ctor" -> Opcode.INVOKE_DIRECT; else -> Opcode.INVOKE_STATIC }
                val replacement = if (insn is Instruction3rc) {
                    BuilderInstruction3rc(opcodeRange, insn.startRegister, insn.registerCount, target)
                } else {
                    insn as Instruction35c
                    BuilderInstruction35c(
                        opcode, insn.registerCount,
                        insn.registerC, insn.registerD, insn.registerE, insn.registerF, insn.registerG, target,
                    )
                }
                method.replaceInstruction(i, replacement)
            }
            "ctorArg" -> {
                ref as MethodReference
                val (argIndex, shim) = ctorArgs.getValue("${ref.definingClass}->${ref.name}(${ref.parameterTypes.joinToString("")})${ref.returnType}")
                insn as Instruction35c
                val reg = listOf(insn.registerC, insn.registerD, insn.registerE, insn.registerF, insn.registerG)[argIndex]
                val move = if (shim.startsWith("colors")) "move-result-object" else "move-result"
                method.addInstructionsAtLabel(i, "invoke-static { v$reg }, $SHAPES->$shim\n$move v$reg")
            }
        }
        count++
    }
    return count
}
