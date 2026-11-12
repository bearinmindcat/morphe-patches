package org.ungoogled.patches.coloros

import app.morphe.patcher.extensions.InstructionExtensions.replaceInstruction
import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.util.proxy.mutableTypes.MutableAnnotation.Companion.toMutable
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod.Companion.toMutable
import app.morphe.patcher.util.proxy.mutableTypes.encodedValue.MutableEncodedValue.Companion.toMutable
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.Opcodes
import com.android.tools.smali.dexlib2.dexbacked.DexBackedDexFile
import com.android.tools.smali.dexlib2.iface.Annotation
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.DualReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.reference.CallSiteReference
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.iface.reference.MethodHandleReference
import com.android.tools.smali.dexlib2.iface.reference.MethodProtoReference
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.iface.reference.Reference
import com.android.tools.smali.dexlib2.iface.reference.StringReference
import com.android.tools.smali.dexlib2.iface.reference.TypeReference
import com.android.tools.smali.dexlib2.iface.value.AnnotationEncodedValue
import com.android.tools.smali.dexlib2.iface.value.ArrayEncodedValue
import com.android.tools.smali.dexlib2.iface.value.EncodedValue
import com.android.tools.smali.dexlib2.iface.value.EnumEncodedValue
import com.android.tools.smali.dexlib2.iface.value.FieldEncodedValue
import com.android.tools.smali.dexlib2.iface.value.MethodEncodedValue
import com.android.tools.smali.dexlib2.iface.value.MethodHandleEncodedValue
import com.android.tools.smali.dexlib2.iface.value.MethodTypeEncodedValue
import com.android.tools.smali.dexlib2.iface.value.TypeEncodedValue
import com.android.tools.smali.dexlib2.immutable.ImmutableMethod
import com.android.tools.smali.dexlib2.rewriter.DexRewriter
import com.android.tools.smali.dexlib2.rewriter.Rewriter
import com.android.tools.smali.dexlib2.rewriter.RewriterModule
import com.android.tools.smali.dexlib2.rewriter.Rewriters
import com.android.tools.smali.dexlib2.rewriter.TypeRewriter

/**
 * Names only OPPO / OnePlus / realme frameworks use: their own packages, or "Oplus" / "OnePlus" / "ColorOS" / "Oppo" in
 * the name (Android has no class matching this, checked against the boot classpaths of Android 11, 13, 15, 16, 17 and
 * the canary). Stand-ins for Android's own newer classes (the OnePlus System Launcher's android.window.* and the like)
 * never match, so on a newer Android those stay Android's.
 */
private val VENDOR = Regex(
    "^L(com/(oplus|oplusos|oneplus|color|coloros|oppo|heytap|nearme|itgsa|sdid|aiunit)|oplus|oppo|cn/teddymobile)/|" +
        "(?i:oplus|oneplus|coloros)|Oppo(?!rtun)",
)

/** OPPO classes in Android's packages without a telling name that custom ROMs' oplus-fwk carries. */
private val VENDOR_CLASSES = setOf("Landroid/app/job/IJobInfoExt;", "Landroid/app/job/IJobInfoExt\$JobBuilderExt;")

private const val SUFFIX = "_ungoogled"

internal fun isVendorFrameworkClass(type: String) = type in VENDOR_CLASSES || VENDOR.containsMatchIn(type)

/** "Lpkg/Top$Inner;" -> "Lpkg/Top;". */
private fun topLevel(type: String) = type.substringBefore('$').removeSuffix(";") + ";"

/** "Lpkg/Top$Inner;" -> "Lpkg/Top_ungoogled$Inner;": same package (package-private access is unchanged). */
private fun privateName(type: String): String {
    val body = type.substring(1, type.length - 1)
    val dollar = body.indexOf('$', body.lastIndexOf('/') + 1)
    return if (dollar < 0) "L$body$SUFFIX;" else "L${body.substring(0, dollar)}$SUFFIX${body.substring(dollar)};"
}

/** The classes an extension (.mpe, a dex in the patch bundle) adds outside org/ungoogled: its stand-ins. */
internal fun extensionStandIns(extension: String): Set<String> {
    val stream = object {}.javaClass.classLoader.getResourceAsStream(extension)
        ?: throw PatchException("extension $extension not found")
    val dex = DexBackedDexFile(Opcodes.getDefault(), java.nio.ByteBuffer.wrap(stream.use { it.readBytes() }))
    return dex.classes.map { it.type }.filterNot { it.startsWith("Lorg/ungoogled/") }.toSet()
}

private class Mentions(private val types: Set<String>) {
    fun type(t: String) = t.trimStart('[') in types
    fun method(r: MethodReference) =
        type(r.definingClass) || type(r.returnType) || r.parameterTypes.any { type(it.toString()) }
    fun field(r: FieldReference) = type(r.definingClass) || type(r.type)
    fun proto(r: MethodProtoReference) = type(r.returnType) || r.parameterTypes.any { type(it.toString()) }
    fun handle(r: MethodHandleReference) = when (val m = r.memberReference) {
        is MethodReference -> method(m)
        is FieldReference -> field(m)
        else -> false
    }

    fun value(v: EncodedValue?): Boolean = when (v) {
        is TypeEncodedValue -> type(v.value)
        is FieldEncodedValue -> field(v.value)
        is EnumEncodedValue -> field(v.value)
        is MethodEncodedValue -> method(v.value)
        is MethodTypeEncodedValue -> proto(v.value)
        is MethodHandleEncodedValue -> handle(v.value)
        is ArrayEncodedValue -> v.value.any { value(it) }
        is AnnotationEncodedValue -> type(v.type) || v.elements.any { value(it.value) }
        else -> false
    }

    fun annotations(set: Set<Annotation>) = set.any { annotation(it) }
    fun annotation(a: Annotation) = type(a.type) || a.elements.any { value(it.value) }

    fun reference(r: Reference): Boolean = when (r) {
        is TypeReference -> type(r.type)
        is MethodReference -> method(r)
        is FieldReference -> field(r)
        is MethodProtoReference -> proto(r)
        is MethodHandleReference -> handle(r)
        is CallSiteReference -> handle(r.methodHandle) || proto(r.methodProto) || r.extraArguments.any { value(it) }
        else -> false
    }

    fun method(m: Method): Boolean {
        if (type(m.returnType) || m.parameters.any { type(it.type) || annotations(it.annotations) }) return true
        if (annotations(m.annotations)) return true
        val implementation = m.implementation ?: return false
        val handlers = implementation.tryBlocks.flatMap { it.exceptionHandlers }
        if (handlers.any { h -> h.exceptionType?.let(::type) == true }) return true
        return implementation.instructions.any { insn ->
            ((insn as? ReferenceInstruction)?.reference?.let(::reference) == true) ||
                ((insn as? DualReferenceInstruction)?.reference2?.let(::reference) == true)
        }
    }

    fun classDef(c: ClassDef) =
        c.superclass?.let(::type) == true || c.interfaces.any(::type) || annotations(c.annotations) ||
            c.fields.any { type(it.type) || annotations(it.annotations) || value(it.initialValue) } ||
            c.methods.any(::method)
}

internal data class PrivateFrameworkClasses(val moved: Int, val rewritten: Int, val literals: Int)

/**
 * On an OPPO, OnePlus or realme phone, and on custom ROMs made for one (LineageOS, crDroid, Project Infinity X, ...,
 * which put a small oplus-fwk.jar on the boot classpath for the camera and other vendor parts), the phone's own copy
 * of a framework class loads before the app's, so the app's stand-in (or compile stub) for it never runs. An installed
 * app may not link to the members of such a jar at all (hidden API: a boot jar's members outside Android's SDK are
 * blocked, for reflection too), and the phone's version may lack a member: each call stopped the app with
 * NoSuchMethodError (issue #155: OplusResourceManager.getInstance in the OxygenOS 11 launcher, on every app open;
 * OplusFeatureConfigManager.getInstance at the start of the ColorOS ports).
 *
 * Here [candidates] (the app's stand-ins and stubs of such classes, see [isVendorFrameworkClass]) and the classes
 * nested in them get names of their own (Top_ungoogled, same package) and every reference in the app follows, so
 * the app always uses the copies tested off ColorOS, whatever the phone's boot classpath holds. A string naming such
 * a class (Class.forName and the like) follows too, outside the moved classes themselves (their AIDL descriptors
 * stay). Classes named in [keep] stay as they are: layouts inflate them by name. With a STRIP bytecode mode the
 * original dex still holds the old classes, unused. Returns the classes moved, the classes rewritten and the string
 * literals moved.
 */
internal fun BytecodePatchContext.privatizeFrameworkClasses(
    candidates: Set<String>,
    keep: Set<String>,
): PrivateFrameworkClasses {
    val defined = HashMap<String, ClassDef>()
    classDefForEach { defined[it.type] = it }
    val kept = keep.map(::topLevel).toSet()
    fun movable(type: String) = topLevel(type) !in kept &&
        defined[type]?.methods?.none { AccessFlags.NATIVE.isSet(it.accessFlags) } == true
    // Whole top-level classes move, with every class nested in them (an inner class keeps its outer one).
    val rootTops = candidates.filter { it in defined && isVendorFrameworkClass(it) && movable(it) }.map(::topLevel)
        .filter { it in candidates && it in defined && movable(it) }.toSet()
    val moved = defined.keys.filter { topLevel(it) in rootTops && movable(it) }.associateWith(::privateName)
    if (moved.isEmpty()) return PrivateFrameworkClasses(0, 0, 0)

    val literals = HashMap<String, String>()
    for ((from, to) in moved) {
        val a = from.substring(1, from.length - 1)
        val b = to.substring(1, to.length - 1)
        literals[a.replace('/', '.')] = b.replace('/', '.')
        literals[a] = b
        literals[from] = to
    }
    val mentions = Mentions(moved.keys)
    fun literal(insn: com.android.tools.smali.dexlib2.iface.instruction.Instruction) =
        (insn.opcode == Opcode.CONST_STRING || insn.opcode == Opcode.CONST_STRING_JUMBO) &&
            ((insn as ReferenceInstruction).reference as StringReference).string in literals
    fun hasLiteral(m: Method) = m.implementation?.instructions?.any(::literal) == true

    val targets = defined.values.filter { c ->
        c.type in moved || mentions.classDef(c) || c.methods.any(::hasLiteral)
    }.map { it.type }

    val rewriter = DexRewriter(object : RewriterModule() {
        override fun getTypeRewriter(rewriters: Rewriters): Rewriter<String> = object : TypeRewriter() {
            override fun rewriteUnwrappedType(value: String): String = moved[value] ?: value
        }
    })

    var literalCount = 0
    fun moveLiterals(method: MutableMethod) {
        val instructions = method.implementation?.instructions?.toList() ?: return
        instructions.forEachIndexed { index, insn ->
            if (!literal(insn)) return@forEachIndexed
            val to = literals.getValue(((insn as ReferenceInstruction).reference as StringReference).string)
            val opcode = if (insn.opcode == Opcode.CONST_STRING) "const-string" else "const-string/jumbo"
            method.replaceInstruction(index, "$opcode v${(insn as OneRegisterInstruction).registerA}, \"$to\"")
            literalCount++
        }
    }

    for (type in targets) {
        val mutable = mutableClassDefBy(type)
        val renamed = moved[type]
        mutable.superclass?.let { s -> moved[s]?.let(mutable::setSuperClass) }
        (mutable.interfaces as MutableList<String>).replaceAll { moved[it] ?: it }
        mutable.annotations.filter(mentions::annotation).forEach {
            mutable.annotations.remove(it)
            mutable.annotations.add(rewriter.annotationRewriter.rewrite(it).toMutable())
        }
        for (field in mutable.fields) {
            if (renamed != null) field.setDefiningClass(renamed)
            val fieldType = rewriter.typeRewriter.rewrite(field.type)
            if (fieldType != field.type) field.setType(fieldType)
            field.annotations.filter(mentions::annotation).forEach {
                field.annotations.remove(it)
                field.annotations.add(rewriter.annotationRewriter.rewrite(it).toMutable())
            }
            field.initialValue?.let { v ->
                if (mentions.value(v)) field.setInitialValue(rewriter.encodedValueRewriter.rewrite(v).toMutable())
            }
        }
        for (method in mutable.methods.toList()) {
            val current = if (renamed != null || mentions.method(method)) {
                val rebuilt = ImmutableMethod.of(rewriter.methodRewriter.rewrite(method)).toMutable()
                mutable.methods.remove(method)
                mutable.methods.add(rebuilt)
                rebuilt
            } else {
                method
            }
            if (renamed == null) moveLiterals(current)
        }
        if (renamed != null) mutable.setType(renamed)
    }
    return PrivateFrameworkClasses(moved.size, targets.size, literalCount)
}

/**
 * The patch that gives an app private copies of its OPPO framework classes (see privatizeFrameworkClasses): the
 * stand-ins of [extension], plus what [extra] finds in the app before other patches change it (the OxygenOS 11
 * launcher's compile stubs). It works in finalize, after every patch has used the classes by their framework names;
 * make it an early dependency of the app's main patch (finalize runs in reverse order, so it comes last).
 */
internal fun privateFrameworkClassesPatch(
    app: String,
    extension: String,
    minimum: Int,
    keep: Set<String> = emptySet(),
    extra: BytecodePatchContext.() -> Set<String> = { emptySet() },
) = bytecodePatch(
    description = "Keeps the phone's own OPPO framework classes from replacing the app's copies.",
) {
    var found = emptySet<String>()
    execute { found = extra() }
    finalize {
        val (moved, rewritten, literals) = privatizeFrameworkClasses(extensionStandIns(extension) + found, keep)
        java.util.logging.Logger.getLogger(app)
            .info("framework classes made private: $moved; classes rewritten: $rewritten; names in strings: $literals")
        if (moved < minimum) {
            throw PatchException("only $moved framework classes found to make private (expected at least $minimum)")
        }
    }
}
