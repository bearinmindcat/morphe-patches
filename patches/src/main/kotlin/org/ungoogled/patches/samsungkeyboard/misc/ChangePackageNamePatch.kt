package org.ungoogled.patches.samsungkeyboard.misc

import app.morphe.patcher.extensions.InstructionExtensions.replaceInstruction
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.patch.resourcePatch
import app.morphe.patcher.patch.stringOption
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.reference.StringReference
import org.ungoogled.patches.samsungkeyboard.SamsungKeyboardConstants.COMPATIBILITY_SAMSUNG_KEYBOARD
import org.w3c.dom.Document
import org.w3c.dom.Element

private const val STOCK_PACKAGE = "com.samsung.android.honeyboard"

private fun Document.elements(tag: String): List<Element> =
    getElementsByTagName(tag).let { list -> (0 until list.length).map { list.item(it) as Element } }

/** The authorities and permission names the manifest moves; the code's literals of them follow. */
private val renamed = mutableMapOf<String, String>()

/**
 * Installs Samsung Keyboard as its own app next to the system one, which a patched (re-signed) copy cannot
 * replace on a Galaxy phone.
 *
 * Moves with the package: the package itself, the 17 provider authorities and the 11 custom permissions (and
 * their uses), and in code every string that names one of those authorities or permissions, so the keyboard
 * still finds its own providers. Removed: sharedUserId (android.uid.honeyboard belongs to Samsung's signature)
 * and coreApp. Component class names stay, since the classes do not move.
 */
@Suppress("unused")
val changePackageNamePatch = bytecodePatch(
    name = "Change package name",
    description = "Installs next to the system Samsung Keyboard under its own package name.",
    default = true,
) {
    compatibleWith(COMPATIBILITY_SAMSUNG_KEYBOARD)

    val packageName = stringOption(
        key = "packageName",
        default = "org.ungoogled.honeyboard",
        title = "Package name",
        description = "Must be a valid Android package name.",
        required = true,
    ) {
        it != null && it != STOCK_PACKAGE && it.matches(Regex("^[a-zA-Z][a-zA-Z0-9_]*(\\.[a-zA-Z][a-zA-Z0-9_]*)+$"))
    }

    dependsOn(
        // As an ordinary app it is refused what only the system keyboard may do.
        systemAccessPatch,
        resourcePatch(description = "Moves the manifest to the new package name.") {
            execute {
                val old = STOCK_PACKAGE
                val new = packageName.value!!
                fun under(value: String) = value.startsWith("$old.")
                // Every name it declares must be unique on the phone, the stock keyboard's included: names under the
                // package move with it, the few others (Bixby's dictation permission, two Samsung toolkit
                // providers) get the new package in front.
                fun moved(value: String) = if (under(value)) new + value.substring(old.length) else "$new.$value"
                renamed.clear()
                var authorities = 0
                var permissions = 0

                document("AndroidManifest.xml").use { manifest ->
                    val root = manifest.documentElement
                    if (root.getAttribute("package") != old) throw PatchException("not Samsung Keyboard's manifest")
                    root.setAttribute("package", new)
                    root.removeAttribute("android:sharedUserId")
                    root.removeAttribute("coreApp")

                    val own = manifest.elements("permission").map { it.getAttribute("android:name") }.toSet()
                    for (element in manifest.elements("permission") + manifest.elements("uses-permission")) {
                        val name = element.getAttribute("android:name")
                        if (name !in own) continue
                        element.setAttribute("android:name", moved(name))
                        renamed[name] = moved(name)
                        if (element.tagName == "permission") permissions++
                    }
                    for (element in manifest.elements("*")) {
                        for (attribute in listOf("android:permission", "android:readPermission", "android:writePermission")) {
                            val value = element.getAttribute(attribute)
                            if (value in own) element.setAttribute(attribute, moved(value))
                        }
                    }

                    // Only the app's own providers, not <queries> entries naming other apps' ones.
                    val application = manifest.elements("application").single()
                    for (provider in manifest.elements("provider").filter { it.parentNode == application }) {
                        val list = provider.getAttribute("android:authorities").split(';')
                        val moved = list.map(::moved)
                        list.zip(moved).filter { (a, b) -> a != b }.forEach { (a, b) -> renamed[a] = b; authorities++ }
                        provider.setAttribute("android:authorities", moved.joinToString(";"))
                    }
                }

                // Measured against 5.9.40.75; fewer means the manifest changed shape.
                if (authorities < 17 || permissions < 11) {
                    throw PatchException("package rename found $authorities authorities and $permissions permissions")
                }
            }
        },
    )

    execute {
        // Exact names, and content:// URIs built on them.
        fun rename(s: String): String? {
            renamed[s]?.let { return it }
            if (s.startsWith("content://")) {
                val rest = s.removePrefix("content://")
                val hit = renamed.keys.filter { rest == it || rest.startsWith("$it/") }.maxByOrNull { it.length } ?: return null
                return "content://" + renamed.getValue(hit) + rest.substring(hit.length)
            }
            return null
        }

        val sites = mutableListOf<Pair<String, com.android.tools.smali.dexlib2.iface.Method>>()
        classDefForEach { classDef ->
            if (classDef.type.startsWith("Lorg/ungoogled/")) return@classDefForEach
            for (method in classDef.methods) {
                val hit = method.implementation?.instructions?.any { insn ->
                    (insn.opcode == Opcode.CONST_STRING || insn.opcode == Opcode.CONST_STRING_JUMBO) &&
                        rename(((insn as ReferenceInstruction).reference as StringReference).string) != null
                } ?: false
                if (hit) sites += classDef.type to method
            }
        }

        var rewritten = 0
        for ((type, method) in sites) {
            val mutableMethod = mutableClassDefBy(type).methods.first {
                it.name == method.name && it.parameterTypes == method.parameterTypes && it.returnType == method.returnType
            }
            val instructions = mutableMethod.implementation!!.instructions
            for (index in instructions.indices) {
                val insn = instructions[index]
                if (insn.opcode != Opcode.CONST_STRING && insn.opcode != Opcode.CONST_STRING_JUMBO) continue
                val to = rename(((insn as ReferenceInstruction).reference as StringReference).string) ?: continue
                mutableMethod.replaceInstruction(index, "const-string v${(insn as OneRegisterInstruction).registerA}, \"$to\"")
                rewritten++
            }
        }
        if (rewritten == 0) throw PatchException("no authority or permission literals found to move")
        java.util.logging.Logger.getLogger("SamsungKeyboard").info("Package rename: $rewritten literals moved")
    }
}
