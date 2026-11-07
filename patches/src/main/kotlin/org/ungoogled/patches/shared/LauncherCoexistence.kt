package org.ungoogled.patches.shared

import app.morphe.patcher.patch.BytecodePatch
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.ResourcePatchContext
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.patch.resourcePatch
import app.morphe.patcher.util.Document
import org.ungoogled.patches.samsung.rewriteStringLiterals
import org.w3c.dom.Element

private val log = java.util.logging.Logger.getLogger("LauncherCoexistence")

/**
 * Packages whose own namespace other vendors' launchers also declare names in: AOSP's Launcher2 / Launcher3 names
 * (POCO Launcher declares com.android.launcher.permission.READ_SETTINGS and WRITE_SETTINGS, ROMs ship Launcher3 as
 * com.android.launcher3) and MIUI's (POCO Launcher keeps com.miui.home.launcher.permission.* and a dozen com.miui.home.*
 * authorities). For these, the package prefix proves nothing and every name moves under "<package>.ungoogled".
 */
private val SHARED_NAMESPACES = setOf("com.android.launcher", "com.android.launcher3", "com.miui.home")

/** Permissions Android itself declares: Android ignores an app's redeclaration of them instead of refusing it. */
private val FRAMEWORK_PERMISSIONS = setOf(
    "com.android.launcher.permission.INSTALL_SHORTCUT",
    "com.android.launcher.permission.UNINSTALL_SHORTCUT",
)

/** androidx builds this permission's name from the package name at runtime (ContextCompat.registerReceiver). */
private const val DYNAMIC_RECEIVER = ".DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION"

private val DECLARATIONS = setOf("permission", "permission-group", "permission-tree")
private val GUARD_ATTRIBUTES = listOf("android:permission", "android:readPermission", "android:writePermission")

private fun Document.elements(tag: String): List<Element> =
    getElementsByTagName(tag).let { list -> (0 until list.length).map { list.item(it) as Element } }

/**
 * Lets a launcher port install next to the phone's own launcher. Android refuses an app that declares a permission
 * another app (signed by someone else) already declares, or a provider authority any other app has: "Package
 * com.android.launcher attempting to redeclare permission com.android.launcher.permission.WRITE_SETTINGS already owned
 * by com.mi.android.globallauncher" (issue #144). Launchers declare AOSP names (com.android.launcher.*,
 * com.android.launcher3.*, com.android.quickstep.*) and names of other packages, so every permission, permission group,
 * permission tree and provider authority the manifest declares outside the app's own package moves under it:
 * com.android.launcher3.contentProvider becomes <package>.android.launcher3.contentProvider (for a package in
 * SHARED_NAMESPACES everything moves, under <package>.ungoogled), with its uses (requests, component and provider
 * guards, permission groups, meta-data values, @string values) and the code's literals of them (also as content://
 * URIs). Literals that are the package name itself or a class name of the app stay.
 *
 * Stay as they are: permissions Android declares itself (INSTALL_SHORTCUT, so other apps' shortcut broadcasts still
 * reach the guarded receiver), <package>.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION (built from the package name at
 * runtime), and [keep]: names other apps use to reach the launcher (badge providers) or that its code builds from the
 * package name at runtime; an entry ending in "." keeps everything under it. [aliases] are further code literals that
 * mean one of the app's own names (old name -> its own old name, which the rename then follows).
 *
 * Add it as a dependency of the launcher's main patch, after its other dependencies (it renames what they left).
 */
internal fun launcherCoexistencePatch(
    keep: Set<String> = emptySet(),
    aliases: Map<String, String> = emptyMap(),
): BytecodePatch {
    val renamed = mutableMapOf<String, String>()
    var packageName = ""

    val manifestPatch = resourcePatch(description = "Gives the launcher's permissions and authorities names of its own.") {
        execute {
            renamed.clear()
            val strings = StringResources(this)
            var kept = 0

            document("AndroidManifest.xml").use { manifest ->
                val root = manifest.documentElement
                packageName = root.getAttribute("package")
                val shared = packageName in SHARED_NAMESPACES
                val base = if (shared) "$packageName.ungoogled" else packageName

                fun needsRename(name: String) = when {
                    name.isBlank() || name in FRAMEWORK_PERMISSIONS || name == packageName + DYNAMIC_RECEIVER -> false
                    keep.any { if (it.endsWith(".")) name.startsWith(it) else name == it } -> false
                    !shared && (name == packageName || name.startsWith("$packageName.")) -> false
                    else -> true
                }
                fun moved(name: String) = when {
                    name.endsWith(DYNAMIC_RECEIVER) -> packageName + DYNAMIC_RECEIVER
                    shared && name == packageName -> base
                    shared && name.startsWith("$packageName.") -> base + name.substring(packageName.length)
                    else -> "$base." + name.replaceFirst(Regex("^(com|net|org)\\."), "")
                }
                fun declare(name: String) {
                    if (needsRename(name)) renamed[name] = moved(name) else kept++
                }

                val application = manifest.elements("application").single()
                val providers = manifest.elements("provider").filter { it.parentNode == application }
                val declarations = manifest.elements("*").filter { it.tagName in DECLARATIONS }
                declarations.forEach { declare(strings.resolve(it.getAttribute("android:name"))) }
                providers.forEach { p -> strings.resolve(p.getAttribute("android:authorities")).split(';').forEach(::declare) }

                // A name is a literal or a @string reference, whose string then changes (meta-data: literals only).
                fun rename(element: Element, attribute: String, references: Boolean = true, transform: (String) -> String?) {
                    val value = element.getAttribute(attribute)
                    if (value.isEmpty()) return
                    if (value.startsWith("@")) {
                        if (references) transform(strings.resolve(value))?.let { strings.set(value, it) }
                    } else {
                        transform(value)?.let { element.setAttribute(attribute, it) }
                    }
                }
                val exact = { value: String -> renamed[value] }
                for (element in manifest.elements("*")) {
                    when {
                        element.tagName in DECLARATIONS -> {
                            rename(element, "android:name", transform = exact)
                            rename(element, "android:permissionGroup", transform = exact)
                        }
                        element.tagName.startsWith("uses-permission") -> rename(element, "android:name", transform = exact)
                        // ThemePicker finds the grid provider through the home activity's meta-data.
                        element.tagName == "meta-data" -> rename(element, "android:value", references = false) {
                            contentUri(it, renamed) ?: renamed[it]
                        }
                    }
                    GUARD_ATTRIBUTES.forEach { rename(element, it, transform = exact) }
                }
                for (provider in providers) {
                    rename(provider, "android:authorities") { list ->
                        list.split(';').map { renamed[it] ?: it }.joinToString(";").takeIf { it != list }
                    }
                }

                // What the phone sees afterwards: no declared name left that another app could have.
                val permissions = declarations.map { strings.resolve(it.getAttribute("android:name")) }
                val authorities = providers.flatMap { strings.resolve(it.getAttribute("android:authorities")).split(';') }
                val left = (permissions + authorities).filter { needsRename(it) && it !in renamed.values }
                if (left.isNotEmpty()) throw PatchException("declared names that did not move: $left")
                for (names in listOf(permissions, authorities)) {
                    val twice = names.groupingBy { it }.eachCount().filterValues { it > 1 }.keys
                    if (twice.isNotEmpty()) throw PatchException("declared twice after the rename: $twice")
                }
            }
            strings.close()
            log.info("$packageName: ${renamed.size} permissions and authorities renamed, $kept already its own or kept")
            renamed.forEach { (old, new) -> log.fine("  $old -> $new") }
        }
    }

    return bytecodePatch(description = "Moves the code's literals of the launcher's renamed permissions and authorities.") {
        dependsOn(manifestPatch)

        execute {
            if (renamed.isEmpty()) return@execute
            // A literal equal to a class name of the app (some authorities are their provider's class name) or to the
            // package name names that, not the authority.
            val classNames = mutableSetOf<String>()
            classDefForEach { classNames += it.type.substring(1, it.type.length - 1).replace('/', '.') }
            val exact = renamed.filterKeys { it != packageName && it !in classNames } +
                aliases.mapValues { (_, own) -> renamed[own] ?: own }
            val literals = rewriteStringLiterals { exact[it] ?: contentUri(it, renamed) }
            log.info("$packageName: $literals literals of renamed permissions and authorities moved")
        }
    }
}

/** content://<authority>[/...] with the authority's new name, or null when it is not one of [renamed]. */
private fun contentUri(value: String, renamed: Map<String, String>): String? {
    if (!value.startsWith("content://")) return null
    val rest = value.removePrefix("content://")
    val hit = renamed.keys.filter { rest == it || rest.startsWith("$it/") }.maxByOrNull { it.length } ?: return null
    return "content://" + renamed.getValue(hit) + rest.substring(hit.length)
}

/** The default string resources, decoded only when the manifest names one (the HyperOS launcher's authorities). */
private class StringResources(private val context: ResourcePatchContext) {
    private var document: Document? = null

    private fun strings(): Document = document ?: run {
        context.decode("res/values/strings.xml")
        context.document("res/values/strings.xml").also { document = it }
    }

    private fun element(reference: String): Element {
        val name = reference.removePrefix("@string/")
        return strings().elements("string").firstOrNull { it.getAttribute("name") == name }
            ?: throw PatchException("the manifest names $reference, which has no default value")
    }

    fun resolve(value: String): String = when {
        value.startsWith("@string/") -> element(value).textContent
        value.startsWith("@") -> throw PatchException("the manifest names an unexpected resource: $value")
        else -> value
    }

    fun set(reference: String, value: String) {
        element(reference).textContent = value
    }

    fun close() {
        document?.close()
    }
}
