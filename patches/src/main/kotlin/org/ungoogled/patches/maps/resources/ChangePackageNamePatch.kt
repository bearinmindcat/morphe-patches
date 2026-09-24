package org.ungoogled.patches.maps.resources

import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.resourcePatch
import app.morphe.patcher.patch.stringOption
import org.ungoogled.patches.shared.Constants.COMPATIBILITY_MAPS
import org.w3c.dom.Document
import org.w3c.dom.Element

private const val STOCK_PACKAGE = "com.google.android.apps.maps"

/** `old` itself, or `old` followed by one of [separators], moved under `new`; anything else is not ours. */
private fun moved(value: String, old: String, new: String, separators: String = "."): String? = when {
    value == old -> new
    separators.any { value.startsWith(old + it) } -> new + value.substring(old.length)
    else -> null
}

private fun Document.elements(tag: String): List<Element> =
    getElementsByTagName(tag).let { list -> (0 until list.length).map { list.item(it) as Element } }

/**
 * Installs Maps under a package name of your choosing, alongside stock Maps.
 *
 * Everything that makes up the app's OWN identity moves with the package, so
 * the renamed app stays consistent with itself:
 *
 * - package and sharedUserId;
 * - its provider authorities (all nine are "<package>.something");
 * - its three custom permissions, the components they protect, and the one it
 *   holds itself (DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION, which AndroidX uses
 *   to keep the app's own broadcast receivers private -- left behind, the app
 *   would no longer hold the permission guarding its own receivers);
 * - the process name seven components set explicitly: in stock Maps it IS the
 *   main process, and left unrenamed those components would quietly run in a
 *   second process of their own;
 * - all 54 activity-alias names. Maps builds these at runtime as
 *   getPackageName() + ".ReviewActivity" (and so on), so they must sit under
 *   whatever the package is -- otherwise writing a review, the location
 *   sharing screens, notification taps and launcher shortcuts all fail to
 *   find their screen;
 * - the two static launcher shortcuts, which name the package and an alias.
 *
 * Deliberately left alone: activity/service/provider/receiver names (those are
 * real class names, which do not move), the <queries> entries (they name OTHER
 * apps, stock Maps among them), intent-filter action names, and the literals in
 * Maps' code: none of those 19 name this app's own components -- they are an
 * internal account type, action names and lists of other apps -- and the
 * authorities and aliases the code does use are built from getPackageName().
 *
 * On by default: stock Maps comes built into most phones, signed by Google, so a
 * patched copy under the stock package name cannot install at all -- Morphe
 * Manager's Simple mode would only offer to uninstall stock Maps, which merely
 * rolls it back to the built-in version. The default name mirrors stock Maps'
 * com.google.android.apps.maps and is the same one the shell tooling uses; the
 * two builds are signed with different keys, so only one of them can be
 * installed at a time.
 */
@Suppress("unused")
val changePackageNamePatch = resourcePatch(
    name = "Change package name",
    description = "Installs alongside stock Google Maps under its own package name. On by default, because stock Maps comes built into most phones and cannot be replaced by a patched copy.",
    default = true,
) {
    compatibleWith(COMPATIBILITY_MAPS)

    val packageName = stringOption(
        key = "packageName",
        default = "org.ungoogled.android.apps.maps",
        title = "Package name",
        description = "The package name to install under. Must be a valid Android package name.",
        required = true,
    ) {
        it != null && it.matches(Regex("^[a-zA-Z][a-zA-Z0-9_]*(\\.[a-zA-Z][a-zA-Z0-9_]*)+$"))
    }

    execute {
        val old = STOCK_PACKAGE
        val new = packageName.value!!
        val counts = linkedMapOf<String, Int>()
        fun rename(element: Element, attribute: String, separators: String = ".", what: String = attribute): Boolean {
            val value = element.getAttribute(attribute)
            val renamed = moved(value, old, new, separators) ?: return false
            element.setAttribute(attribute, renamed)
            counts[what] = (counts[what] ?: 0) + 1
            return true
        }

        document("AndroidManifest.xml").use { manifest ->
            val root = manifest.documentElement
            rename(root, "package")
            rename(root, "android:sharedUserId")

            val ownPermissions = manifest.elements("permission")
                .map { it.getAttribute("android:name") }
                .filter { moved(it, old, new) != null }
                .toSet()
            for (permission in manifest.elements("permission")) rename(permission, "android:name", what = "permission")
            for (uses in manifest.elements("uses-permission")) {
                if (uses.getAttribute("android:name") in ownPermissions) rename(uses, "android:name", what = "uses-permission")
            }
            for (element in manifest.elements("*")) {
                for (attribute in listOf("android:permission", "android:readPermission", "android:writePermission")) {
                    if (element.getAttribute(attribute) in ownPermissions) rename(element, attribute, what = "permission reference")
                }
                rename(element, "android:process", separators = ".:", what = "process")
            }

            // Only the app's own providers, not the <queries> entries naming other apps' ones.
            val application = manifest.elements("application").single()
            for (provider in manifest.elements("provider").filter { it.parentNode == application }) {
                val authorities = provider.getAttribute("android:authorities").split(';')
                val renamed = authorities.map { moved(it, old, new) ?: it }
                if (renamed != authorities) {
                    provider.setAttribute("android:authorities", renamed.joinToString(";"))
                    counts["authorities"] = (counts["authorities"] ?: 0) + renamed.zip(authorities).count { (a, b) -> a != b }
                }
            }
            for (alias in manifest.elements("activity-alias")) rename(alias, "android:name", what = "activity-alias")
        }

        // Static launcher shortcuts (and anything else in res/xml naming this app's components).
        val res = this["res"]
        for (dir in res.listFiles()!!.filter { it.isDirectory && (it.name == "xml" || it.name.startsWith("xml-")) }) {
            for (file in dir.listFiles()!!.filter { it.name.endsWith(".xml") }) {
                if (old !in file.readText()) continue
                document("res/${dir.name}/${file.name}").use { xml ->
                    for (element in xml.elements("*")) {
                        if (element.getAttribute("android:targetPackage") == old) {
                            element.setAttribute("android:targetPackage", new)
                            counts["shortcut package"] = (counts["shortcut package"] ?: 0) + 1
                        }
                        rename(element, "android:targetClass", what = "shortcut class")
                    }
                }
            }
        }

        // Measured against this Maps version; anything less means the manifest changed shape.
        val expected = mapOf(
            "package" to 1, "android:sharedUserId" to 1, "permission" to 3, "uses-permission" to 1,
            "permission reference" to 2, "process" to 7, "authorities" to 9, "activity-alias" to 54,
            "shortcut package" to 2, "shortcut class" to 2,
        )
        val short = expected.filter { (k, v) -> (counts[k] ?: 0) < v }
        if (short.isNotEmpty()) {
            throw PatchException("package rename found less than expected: " + short.keys.joinToString { "$it ${counts[it] ?: 0}/${expected[it]}" })
        }
    }
}
