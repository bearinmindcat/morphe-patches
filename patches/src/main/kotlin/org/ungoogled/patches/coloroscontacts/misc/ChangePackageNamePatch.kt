package org.ungoogled.patches.coloroscontacts.misc

import app.morphe.patcher.extensions.InstructionExtensions.replaceInstruction
import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.patch.resourcePatch
import app.morphe.patcher.patch.stringOption
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.iface.reference.StringReference
import org.ungoogled.patches.coloroscontacts.ColorOsContactsConstants.COMPATIBILITY_COLOROS_CONTACTS
import org.ungoogled.patches.samsung.rewriteStringLiterals
import org.w3c.dom.Document
import org.w3c.dom.Element

private const val STOCK_PACKAGE = "com.android.contacts"

private val log = java.util.logging.Logger.getLogger("OnePlusContacts")

private fun Document.elements(tag: String): List<Element> =
    getElementsByTagName(tag).let { list -> (0 until list.length).map { list.item(it) as Element } }

/** The authorities and permission names the manifest moves; the code's literals of them follow. */
private val renamed = mutableMapOf<String, String>()

private val CONST_STRING = setOf(Opcode.CONST_STRING, Opcode.CONST_STRING_JUMBO)

/** Calls whose first argument after the receiver is a package name: there the app means itself. */
private val PACKAGE_ARGUMENT = setOf(
    "Landroid/content/Intent;->setPackage(Ljava/lang/String;)Landroid/content/Intent;",
    "Landroid/content/Intent;->setClassName(Ljava/lang/String;Ljava/lang/String;)Landroid/content/Intent;",
    "Landroid/content/ComponentName;-><init>(Ljava/lang/String;Ljava/lang/String;)V",
)
private const val CALLING_PACKAGE = "getCallingPackage()Ljava/lang/String;"

private fun MethodReference.key() = "$definingClass->$name(${parameterTypes.joinToString("")})$returnType"

/**
 * "com.android.contacts" is both the app's package and the authority of Android's contacts provider, which the app
 * uses everywhere. Only the few literals that name the app itself move: those passed as the package of an Intent or
 * ComponentName within the next instructions, and those in a method that compares them with the calling package (the
 * call log cache provider only answers its own app). Returns the literals moved.
 */
private fun BytecodePatchContext.moveOwnPackageLiterals(new: String): Int {
    fun Instruction.literal(): String? =
        if (opcode in CONST_STRING) ((this as ReferenceInstruction).reference as StringReference).string else null
    fun Instruction.callee(): MethodReference? = (this as? ReferenceInstruction)?.reference as? MethodReference

    val sites = mutableListOf<Pair<String, Method>>()
    classDefForEach { c ->
        if (c.type.startsWith("Lorg/ungoogled/")) return@classDefForEach
        for (m in c.methods) {
            if (m.implementation?.instructions?.any { it.literal() == STOCK_PACKAGE } == true) sites += c.type to m
        }
    }
    var moved = 0
    for ((type, method) in sites) {
        val instructions = method.implementation!!.instructions.toList()
        val comparesCaller = instructions.any { it.callee()?.let { r -> "${r.name}(${r.parameterTypes.joinToString("")})${r.returnType}" } == CALLING_PACKAGE }
        val indices = instructions.indices.filter { index ->
            if (instructions[index].literal() != STOCK_PACKAGE) return@filter false
            if (comparesCaller) return@filter true
            val register = (instructions[index] as OneRegisterInstruction).registerA
            // The package of an Intent / ComponentName built right after (until the register is reused).
            for (next in instructions.subList(index + 1, minOf(instructions.size, index + 12))) {
                val callee = next.callee()
                if (callee != null && callee.key() in PACKAGE_ARGUMENT && next is FiveRegisterInstruction &&
                    next.registerCount >= 2 && next.registerD == register
                ) {
                    return@filter true
                }
                if (next is OneRegisterInstruction && next.registerA == register && next.opcode.setsRegister()) break
            }
            false
        }
        if (indices.isEmpty()) continue
        val mutable = mutableClassDefBy(type).methods.first {
            it.name == method.name && it.parameterTypes == method.parameterTypes && it.returnType == method.returnType
        }
        log.fine("Own package literal in $type->${method.name}: ${indices.size}")
        for (index in indices) {
            val register = (instructions[index] as OneRegisterInstruction).registerA
            mutable.replaceInstruction(index, "const-string v$register, \"$new\"")
            moved++
        }
    }
    return moved
}

/**
 * Installs OnePlus Contacts as its own app: "com.android.contacts" is also the package of the contacts app on
 * Android's own ROMs (LineageOS and other custom ROMs, Xiaomi, ColorOS itself), which a re-signed copy can neither
 * replace nor sit beside.
 *
 * Moves with the package: the package itself, the app's own provider authorities and permission, and in code the
 * literals of those authorities and the few literals that name the app itself. Android's contacts provider
 * ("com.android.contacts") stays. Component class names stay, since the classes do not move.
 */
@Suppress("unused")
val changePackageNamePatch = bytecodePatch(
    name = "Change package name",
    description = "Installs next to the phone's own Contacts app under its own package name.",
    default = true,
) {
    compatibleWith(COMPATIBILITY_COLOROS_CONTACTS)

    val packageName = stringOption(
        key = "packageName",
        default = "org.ungoogled.opluscontacts",
        title = "Package name",
        description = "Must be a valid Android package name.",
        required = true,
    ) {
        it != null && it != STOCK_PACKAGE && it.matches(Regex("^[a-zA-Z][a-zA-Z0-9_]*(\\.[a-zA-Z][a-zA-Z0-9_]*)+$"))
    }

    dependsOn(
        resourcePatch(description = "Moves the manifest to the new package name.") {
            execute {
                val old = STOCK_PACKAGE
                val new = packageName.value!!
                fun moved(value: String) = new + value.substring(old.length)
                renamed.clear()
                var authorities = 0

                document("AndroidManifest.xml").use { manifest ->
                    val root = manifest.documentElement
                    if (root.getAttribute("package") != old) throw PatchException("not OnePlus Contacts' manifest")
                    root.setAttribute("package", new)
                    // Only a system app may take over another package's data under a new name.
                    manifest.elements("original-package").forEach { it.parentNode.removeChild(it) }

                    val own = manifest.elements("permission").map { it.getAttribute("android:name") }
                        .filter { it.startsWith("$old.") }.toSet()
                    if (own.isEmpty()) throw PatchException("no permission of its own in the manifest")
                    for (element in manifest.elements("*")) {
                        for (attribute in listOf("android:name", "android:permission", "android:readPermission", "android:writePermission")) {
                            if (attribute == "android:name" && element.tagName !in setOf("permission", "uses-permission")) continue
                            val value = element.getAttribute(attribute)
                            if (value in own) {
                                element.setAttribute(attribute, moved(value))
                                renamed[value] = moved(value)
                            }
                        }
                    }

                    // Only the app's own providers, not <queries> entries naming other apps' ones.
                    val application = manifest.elements("application").single()
                    for (provider in manifest.elements("provider").filter { it.parentNode == application }) {
                        val list = provider.getAttribute("android:authorities").split(';')
                        val moved = list.map { if (it.startsWith("$old.")) moved(it) else it }
                        list.zip(moved).filter { (a, b) -> a != b }.forEach { (a, b) -> renamed[a] = b; authorities++ }
                        provider.setAttribute("android:authorities", moved.joinToString(";"))
                    }
                }

                log.info("Package rename: $authorities authorities, ${renamed.size - authorities} permissions")
                // Measured against 16.80.15: 20 authorities under the package, 1 permission.
                if (authorities < 10) throw PatchException("package rename found only $authorities authorities")
            }
        },
    )

    execute {
        val new = packageName.value!!
        // Exact authorities, and content:// URIs built on them.
        fun rename(s: String): String? {
            renamed[s]?.let { return it }
            if (s.startsWith("content://")) {
                val rest = s.removePrefix("content://")
                val hit = renamed.keys.filter { rest == it || rest.startsWith("$it/") }.maxByOrNull { it.length } ?: return null
                return "content://" + renamed.getValue(hit) + rest.substring(hit.length)
            }
            return null
        }
        val literals = rewriteStringLiterals(::rename)
        val own = moveOwnPackageLiterals(new)
        log.info("Package rename: $literals authority literals, $own own package literals moved")
        if (literals == 0) throw PatchException("no authority literals found to move")
        if (own == 0) throw PatchException("no literal naming the app itself found")
    }
}
