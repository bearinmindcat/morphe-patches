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
 * A name of the app's own moved under [new]: one under the package keeps its suffix, any other gets the new package in
 * front (OxygenOS's build declares providers outside its package, e.g. com.android.dialer.vvm.gretting).
 */
private fun moved(value: String, new: String): String = when {
    value.startsWith("$STOCK_PACKAGE.") -> new + value.substring(STOCK_PACKAGE.length)
    value.startsWith("com.android.") -> "$new." + value.removePrefix("com.")
    else -> "$new.$value"
}

/** Manifest attributes that name a permission (a declaration, a request, a component's guard). */
private val PERMISSION_ATTRIBUTES = listOf("android:permission", "android:readPermission", "android:writePermission")
private val DECLARATIONS = setOf("permission", "permission-group", "permission-tree")

/**
 * Installs OnePlus Contacts as its own app: "com.android.contacts" is also the package of the contacts app on
 * Android's own ROMs (LineageOS and other custom ROMs, Xiaomi) and on ColorOS / OxygenOS / realme UI themselves, where
 * that app stays installed. Android refuses a second app that declares a provider authority, permission or permission
 * group another app already has, so everything the manifest declares moves with the package.
 *
 * Moves: the package itself, every provider authority of the app, every permission, permission group and permission
 * tree it declares (and their uses), the launcher shortcuts' target package, and in code the literals of those names
 * (also as content:// URIs) and the few literals that name the app itself. Android's contacts provider and call log
 * ("com.android.contacts", "call_log") stay: they are the phone's. Component class names stay, since the classes do
 * not move.
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
                renamed.clear()
                var authorities = 0
                var permissions = 0

                document("AndroidManifest.xml").use { manifest ->
                    val root = manifest.documentElement
                    if (root.getAttribute("package") != old) throw PatchException("not OnePlus Contacts' manifest")
                    root.setAttribute("package", new)
                    // Only a system app may take over another package's data under a new name.
                    manifest.elements("original-package").forEach { it.parentNode.removeChild(it) }

                    // Every permission, permission group and permission tree the app declares, and every use of one.
                    val own = manifest.elements("*").filter { it.tagName in DECLARATIONS }.map { it.getAttribute("android:name") }.toSet()
                    if (own.isEmpty()) throw PatchException("no permission of its own in the manifest")
                    own.forEach { renamed[it] = moved(it, new) }
                    permissions = own.size
                    for (element in manifest.elements("*")) {
                        val attributes = when (element.tagName) {
                            in DECLARATIONS, "uses-permission", "uses-permission-sdk-23" -> listOf("android:name", "android:permissionGroup")
                            else -> PERMISSION_ATTRIBUTES
                        }
                        for (attribute in attributes) {
                            val value = element.getAttribute(attribute)
                            if (value in own) element.setAttribute(attribute, renamed.getValue(value))
                        }
                    }

                    // Every provider of the app's own (not <queries> entries naming other apps' ones): authorities are
                    // unique on a phone.
                    val application = manifest.elements("application").single()
                    for (provider in manifest.elements("provider").filter { it.parentNode == application }) {
                        val list = provider.getAttribute("android:authorities").split(';').filter { it.isNotBlank() }
                        list.forEach { renamed[it] = moved(it, new); authorities++ }
                        provider.setAttribute("android:authorities", list.joinToString(";") { renamed.getValue(it) })
                    }
                    if (renamed.values.any { it == old || it.startsWith("com.android.contacts") }) {
                        throw PatchException("a declared name did not move")
                    }
                }

                // The launcher shortcuts (static shortcuts.xml) open the app's own screens by package.
                var shortcuts = 0
                decode("res/xml", "res/xml-*")
                val res = this["res"]
                for (dir in res.listFiles()!!.filter { it.isDirectory && (it.name == "xml" || it.name.startsWith("xml-")) }) {
                    for (file in dir.listFiles()!!.filter { it.name.endsWith(".xml") }) {
                        if ("\"$old\"" !in file.readText()) continue
                        document("res/${dir.name}/${file.name}").use { xml ->
                            for (element in xml.elements("*")) {
                                if (element.getAttribute("android:targetPackage") == old) {
                                    element.setAttribute("android:targetPackage", new)
                                    shortcuts++
                                }
                            }
                        }
                    }
                }

                log.info("Package rename: $authorities authorities, $permissions permissions, $shortcuts shortcut targets")
                // Measured against 16.80.15: 24 authorities, 1 permission, 10 shortcut targets.
                if (authorities < 10) throw PatchException("package rename found only $authorities authorities")
                if (shortcuts == 0) throw PatchException("no launcher shortcut names the app")
            }
        },
    )

    execute {
        val new = packageName.value!!
        // Exact authorities and permissions, and content:// URIs built on the authorities.
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
        log.info("Package rename: $literals authority and permission literals, $own own package literals moved")
        if (literals == 0) throw PatchException("no authority literals found to move")
        if (own == 0) throw PatchException("no literal naming the app itself found")
    }
}
