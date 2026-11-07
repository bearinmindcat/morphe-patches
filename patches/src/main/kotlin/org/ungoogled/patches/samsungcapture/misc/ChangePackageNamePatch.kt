package org.ungoogled.patches.samsungcapture.misc

import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.patch.resourcePatch
import app.morphe.patcher.patch.stringOption
import org.ungoogled.patches.samsung.rewriteStringLiterals
import org.ungoogled.patches.samsungcapture.SamsungCaptureConstants.COMPATIBILITY_SAMSUNG_CAPTURE
import org.w3c.dom.Document
import org.w3c.dom.Element

private const val STOCK_PACKAGE = "com.samsung.android.app.smartcapture"

private val log = java.util.logging.Logger.getLogger("SamsungCapture")

private fun Document.elements(tag: String): List<Element> =
    getElementsByTagName(tag).let { list -> (0 until list.length).map { list.item(it) as Element } }

/** The package, authorities and permission names the manifest moves; the code's literals of them follow. */
private val renamed = mutableMapOf<String, String>()

/**
 * Every Galaxy phone already has Samsung capture as a system app signed by Samsung (One UI Core phones, Galaxy A0x/A1x,
 * a build without the screen recorder), and a re-signed copy can't replace it. This installs the patched one next to
 * it under its own package name.
 *
 * Moves with the package: the package itself (and the processes named after it), the provider authorities and the
 * permissions it declares (and their uses), and in code every string that names one of those, the package or one of
 * its processes, so the app still finds its own components, processes and providers rather than the system copy's. Component class names stay, since
 * the classes do not move.
 */
@Suppress("unused")
val changePackageNamePatch = bytecodePatch(
    name = "Change package name",
    description = "Installs next to Samsung's own Samsung Capture on Galaxy phones (One UI Core).",
    default = false,
) {
    compatibleWith(COMPATIBILITY_SAMSUNG_CAPTURE)

    val packageName = stringOption(
        key = "packageName",
        default = "org.ungoogled.smartcapture",
        title = "Package name",
        description = "Must be a valid Android package name.",
        required = true,
    ) {
        it != null && it != STOCK_PACKAGE && it.matches(Regex("^[a-zA-Z][a-zA-Z0-9_]*(\\.[a-zA-Z][a-zA-Z0-9_]*)+$"))
    }

    dependsOn(
        // The patched app's own screens (consent, editor entry, launcher shortcuts) come with it.
        enableNonOneUiPatch,
        resourcePatch(description = "Moves the manifest to the new package name.") {
            execute {
                val old = STOCK_PACKAGE
                val new = packageName.value!!
                fun under(value: String) = value == old || value.startsWith("$old.")
                // Every name it declares must be unique on the phone, the system copy's included: names under the
                // package move with it, the others (screen recorder, scroll capture, AI drawing, settings) get the
                // new package in front.
                fun moved(value: String) = if (under(value)) new + value.substring(old.length) else "$new.$value"
                renamed.clear()
                renamed[old] = new
                var authorities = 0
                var permissions = 0

                document("AndroidManifest.xml").use { manifest ->
                    val root = manifest.documentElement
                    if (root.getAttribute("package") != old) throw PatchException("not Samsung capture's manifest")
                    root.setAttribute("package", new)

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
                        // The application's process is named after the package.
                        if (element.getAttribute("android:process") == old) element.setAttribute("android:process", new)
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

                // The launcher shortcuts name their target package.
                get("res/xml/ungoogled_shortcuts.xml").apply {
                    if (!exists()) throw PatchException("launcher shortcuts not found")
                    writeText(readText().replace("android:targetPackage=\"$old\"", "android:targetPackage=\"$new\""))
                }

                log.info("Package rename: $authorities authorities, $permissions permissions")
                // Measured against 6.2.12: 16 authorities, 15 permissions.
                if (authorities < 12 || permissions < 12) {
                    throw PatchException("package rename found $authorities authorities and $permissions permissions")
                }
            }
        },
    )

    execute {
        val old = STOCK_PACKAGE
        val new = renamed.getValue(old)
        // Exact names, content:// URIs built on them, the app's process names ("<package>:screenrecorder") and its
        // android.resource:// URIs.
        fun rename(s: String): String? {
            renamed[s]?.let { return it }
            if (s.startsWith("$old:")) return new + s.substring(old.length)
            if (s.startsWith("android.resource://$old/")) return "android.resource://$new/" + s.substring("android.resource://$old/".length)
            if (s.startsWith("content://")) {
                val rest = s.removePrefix("content://")
                val hit = renamed.keys.filter { rest == it || rest.startsWith("$it/") }.maxByOrNull { it.length } ?: return null
                return "content://" + renamed.getValue(hit) + rest.substring(hit.length)
            }
            return null
        }

        val literals = rewriteStringLiterals(::rename)
        log.info("Package rename: $literals literals moved")
        if (literals < 10) throw PatchException("only $literals package, authority and permission literals found")
    }
}
