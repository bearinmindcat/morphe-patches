package org.ungoogled.patches.samsung

import app.morphe.patcher.extensions.InstructionExtensions.addInstruction
import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.extensions.InstructionExtensions.replaceInstruction
import app.morphe.patcher.patch.BytecodePatch
import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.patch.Compatibility
import app.morphe.patcher.patch.Patch
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.ResourcePatchContext
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.patch.resourcePatch
import app.morphe.patcher.patch.stringOption
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod.Companion.toMutable
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.builder.MutableMethodImplementation
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.iface.reference.StringReference
import com.android.tools.smali.dexlib2.immutable.ImmutableMethod
import org.w3c.dom.Document
import org.w3c.dom.Element

/*
 * "Change package name" for the Samsung ports (issue #212): a Galaxy phone already has Samsung's own copy of the app
 * as a system app signed by Samsung, and a patched (re-signed) copy cannot replace it. Renamed, the patched copy
 * installs next to it. Android refuses a second app that declares a permission or provider authority another app
 * already has (INSTALL_FAILED_DUPLICATE_PERMISSION, INSTALL_FAILED_CONFLICTING_PROVIDER), so everything the manifest
 * declares moves with the package, and the code's names of it follow.
 */

private const val CLONE_EXTENSION = "extensions/samsungclone.mpe"
private const val GALAXY = "Lorg/ungoogled/samsungclone/Galaxy;"
private const val SAMSUNG_CLASSES = "Lorg/ungoogled/samsungclone/SamsungClasses;"
private const val SIBLINGS = "Lorg/ungoogled/samsungclone/Siblings;"
private const val RESOLVER = "Lorg/ungoogled/samsungclone/Resolver;"
private const val SWITCHES = "Lorg/ungoogled/samsungclone/Switches;"
private const val STRING_HASH = "Ljava/lang/String;->hashCode()I"

private val log = java.util.logging.Logger.getLogger("ChangePackageName")

private val PACKAGE_NAME = Regex("^[a-zA-Z][a-zA-Z0-9_]*(\\.[a-zA-Z][a-zA-Z0-9_]*)+$")

/**
 * Another Samsung port a renamed copy works with: Samsung Phone and Samsung Contacts open each other's screens and
 * providers, Home Up plugs into One UI Home. [declared] are the names the sibling's manifest declares (permissions,
 * permission groups, provider authorities and its own intent actions, as in its stock manifest), which its renamed
 * copy moves by the same rule (see cloneName). With [atRuntime] each of the sibling's names in the app picks the
 * renamed sibling while it is installed (signed with the same key) and Samsung's own otherwise, for apps that are
 * useful alone; without, the names are the renamed sibling's, for apps that only work as a pair.
 */
internal class CloneSibling(
    val stock: String,
    val clone: String,
    val optionKey: String,
    val title: String,
    val declared: Set<String>,
    val atRuntime: Boolean,
)

/**
 * The one rule every renamed copy uses, so that apps renamed separately still agree on each other's names: a name under
 * the package keeps its suffix under the new one, any other name the app declares gets the new package in front
 * (com.samsung.contacts.permission.QR_CODE -> org.ungoogled.samsungcontacts.com.samsung.contacts.permission.QR_CODE).
 */
internal fun cloneName(name: String, stock: String, new: String): String = when {
    name == stock -> new
    name.startsWith("$stock.") || name.startsWith("$stock:") -> new + name.substring(stock.length)
    else -> "$new.$name"
}

private fun Document.elements(tag: String): List<Element> =
    getElementsByTagName(tag).let { list -> (0 until list.length).map { list.item(it) as Element } }

private val DECLARATIONS = setOf("permission", "permission-group", "permission-tree")
private val GUARD_ATTRIBUTES = listOf("android:permission", "android:readPermission", "android:writePermission")

/** Permissions Android declares itself: an app's redeclaration of them is ignored, not refused. */
private val FRAMEWORK_PERMISSIONS = setOf(
    "com.android.launcher.permission.INSTALL_SHORTCUT",
    "com.android.launcher.permission.UNINSTALL_SHORTCUT",
)

/** content://<authority>[/...] with the authority's new name, or null when it is not one of [names]. */
private fun contentUri(value: String, names: Map<String, String>): String? {
    if (!value.startsWith("content://")) return null
    val rest = value.removePrefix("content://")
    val hit = names.keys.filter { rest == it || rest.startsWith("$it/") }.maxByOrNull { it.length } ?: return null
    return "content://" + names.getValue(hit) + rest.substring(hit.length)
}

/** What the manifest step moved, for the code step. */
private class MovedNames {
    /** Every moved name of the app's own (package, permissions, authorities, private actions) -> its new name. */
    val own = mutableMapOf<String, String>()
    /** Sibling names the code keeps as they are but which must pick at run time: stock -> renamed sibling's, per sibling. */
    val runtime = mutableMapOf<String, Pair<String, String>>()
    /** Sibling names the code simply renames (pairs that only work together). */
    val fixed = mutableMapOf<String, String>()
    var stock = ""
    var new = ""

    fun clear() {
        own.clear(); runtime.clear(); fixed.clear()
    }
}

/**
 * Builds a Samsung port's "Change package name" patch (default off). [dependencies] are the port's main patches, which
 * run first (the rename moves what they leave). Moves:
 * - the package itself; sharedUserId (Samsung's uids), coreApp and original-package go;
 * - every permission, permission group and permission tree the manifest declares, with every use of them (requests,
 *   component and provider guards); every provider authority of the app; the app's own process names and task
 *   affinities (a shared affinity would put its screens into the task of Samsung's copy); its own intent actions
 *   (those under the package, and [privateActionPrefixes]), so an intent meant for this copy never reaches Samsung's;
 *   meta-data values that name one of them (backup provider URIs); launcher shortcuts' and searchable / sync
 *   adapters' names of the package and its authorities;
 * - in code, every literal naming one of those (also as content://, package: and android.resource:// URIs and
 *   "<package>:<process>"), and the package itself, which there means the app itself (component names, its own
 *   resources by name, "is this the dialer" checks). Class names stay, since the classes do not move.
 *
 * On a Galaxy phone the app then runs on Samsung's real framework classes instead of the port's stand-ins, and
 * Samsung's services and providers refuse this copy what only Samsung's own build may do: every call into One UI's
 * framework classes goes through Galaxy, and every remaining ContentResolver call through Resolver, which answer a
 * refusal as if the phone did not have that member or provider.
 *
 * [siblings]: the other ports it works with (see CloneSibling); each gets an option for its renamed package.
 * [extensionClasses]: the port's extension classes whose literals of a sibling's package move too.
 * [minimumNames]: the fewest permissions + authorities the manifest must move (fewer means it changed shape).
 */
internal fun samsungChangePackageNamePatch(
    compatibility: Compatibility,
    stock: String,
    defaultName: String,
    appName: String,
    dependencies: List<Patch<*>>,
    minimumNames: Int,
    siblings: List<CloneSibling> = emptyList(),
    privateActionPrefixes: List<String> = emptyList(),
    extensionClasses: String? = null,
    description: String = "Installs next to Samsung's own $appName on Galaxy phones.",
): BytecodePatch = bytecodePatch(
    name = "Change package name",
    description = description,
    default = false,
) {
    compatibleWith(compatibility)

    val packageName = stringOption(
        key = "packageName",
        default = defaultName,
        title = "Package name",
        description = "Must be a valid Android package name.",
        required = true,
    ) { it != null && it != stock && it.matches(PACKAGE_NAME) }

    val siblingNames = siblings.associateWith { sibling ->
        stringOption(
            key = sibling.optionKey,
            default = sibling.clone,
            title = sibling.title,
            description = "The package name its own \"Change package name\" was given.",
            required = true,
        ) { it != null && it != sibling.stock && it.matches(PACKAGE_NAME) }
    }

    val moved = MovedNames()

    dependsOn(
        *dependencies.toTypedArray(),
        resourcePatch(description = "Moves the manifest to the new package name.") {
            execute {
                moved.clear()
                moved.stock = stock
                moved.new = packageName.value!!
                val renamedSiblings = siblings.associateWith { siblingNames.getValue(it).value!! }
                moveManifest(moved, renamedSiblings, privateActionPrefixes, minimumNames)
            }
        },
        oneUiFrameworkClassesPatch(),
        samsungProvidersPatch(),
    )
    extendWith(CLONE_EXTENSION)

    execute {
        val (literals, picks) = moveCodeLiterals(moved, extensionClasses)
        log.info("$stock -> ${moved.new}: $literals literals moved, $picks sibling names picked at run time")
        if (literals == 0) throw PatchException("no literal of the package, its permissions or authorities found")
        if (moved.runtime.isNotEmpty() && picks == 0) throw PatchException("no literal of a sibling app found")
    }
}

private fun ResourcePatchContext.moveManifest(
    moved: MovedNames,
    siblings: Map<CloneSibling, String>,
    privateActionPrefixes: List<String>,
    minimumNames: Int,
) {
    val old = moved.stock
    val new = moved.new
    val own = moved.own
    own[old] = new
    var permissions = 0
    var authorities = 0
    var actions = 0
    var affinities = 0
    val added = mutableListOf<String>()

    // The sibling's names, renamed by the same rule its own patch uses.
    val siblingNames = mutableMapOf<String, Pair<String, String>>()
    for ((sibling, renamed) in siblings) {
        for (name in sibling.declared + sibling.stock) {
            siblingNames[name] = cloneName(name, sibling.stock, renamed) to renamed
        }
        if (sibling.atRuntime) {
            for (name in sibling.declared + sibling.stock) moved.runtime[name] = siblingNames.getValue(name)
        } else {
            for (name in sibling.declared + sibling.stock) moved.fixed[name] = siblingNames.getValue(name).first
        }
    }

    val strings = DefaultStrings(this)
    document("AndroidManifest.xml").use { manifest ->
        val root = manifest.documentElement
        if (root.getAttribute("package") != old) throw PatchException("not $old's manifest")
        root.setAttribute("package", new)
        listOf("android:sharedUserId", "android:sharedUserLabel", "android:sharedUserMaxSdkVersion", "coreApp")
            .forEach(root::removeAttribute)
        for (tag in listOf("original-package", "adopt-permissions")) {
            manifest.elements(tag).forEach { it.parentNode.removeChild(it) }
        }

        val application = manifest.elements("application").single()
        val declarations = manifest.elements("*").filter { it.tagName in DECLARATIONS && it.parentNode == root }
        for (declaration in declarations) {
            val name = declaration.getAttribute("android:name")
            if (name.isBlank() || name.startsWith("@") || name in FRAMEWORK_PERMISSIONS) continue
            own[name] = cloneName(name, old, new)
            permissions++
        }
        val providers = manifest.elements("provider").filter { it.parentNode == application }
        for (provider in providers) {
            val list = provider.getAttribute("android:authorities")
            if (list.startsWith("@")) throw PatchException("$list: authorities from resources are not handled")
            val names = list.split(';').filter { it.isNotBlank() }
            names.forEach { own[it] = cloneName(it, old, new); authorities++ }
            provider.setAttribute("android:authorities", names.joinToString(";") { own.getValue(it) })
        }
        // The app's own intent actions: an implicit intent for one would otherwise also find Samsung's copy (the app
        // opens its own screens with them, e.g. com.samsung.contacts.action.VIEW_CONTACT, and got a chooser).
        val otherNames = manifest.elements("*").filter { it.tagName != "action" }.map { it.getAttribute("android:name") }.toSet()
        for (action in manifest.elements("action")) {
            val name = action.getAttribute("android:name")
            if (isOwnAction(name, action, old, privateActionPrefixes, siblings.keys, otherNames)) {
                if (own.put(name, cloneName(name, old, new)) == null) actions++
            }
        }

        fun renamedPermission(value: String, sibling: Boolean): String? = own[value]
            ?: if (sibling) moved.fixed[value] else null

        for (element in manifest.elements("*")) {
            when {
                element.tagName in DECLARATIONS -> {
                    for (attribute in listOf("android:name", "android:permissionGroup")) {
                        own[element.getAttribute(attribute)]?.let { element.setAttribute(attribute, it) }
                    }
                }
                element.tagName.startsWith("uses-permission") -> {
                    val name = element.getAttribute("android:name")
                    renamedPermission(name, true)?.let { element.setAttribute("android:name", it) }
                    // A runtime sibling's permission: also the renamed sibling's, for when that one is installed.
                    moved.runtime[name]?.let { (renamed, _) ->
                        val copy = element.cloneNode(false) as Element
                        copy.setAttribute("android:name", renamed)
                        element.parentNode.insertBefore(copy, element.nextSibling)
                        added += renamed
                    }
                }
                element.tagName == "action" -> {
                    val name = element.getAttribute("android:name")
                    (own[name] ?: moved.fixed[name])?.let { element.setAttribute("android:name", it) }
                }
                element.tagName == "meta-data" -> {
                    val value = element.getAttribute("android:value")
                    val to = own[value]?.takeIf { value != old } ?: moved.fixed[value] ?: contentUri(value, own)
                    to?.let { element.setAttribute("android:value", it) }
                }
            }
            for (attribute in GUARD_ATTRIBUTES) {
                val value = element.getAttribute(attribute)
                if (value.isNotEmpty()) renamedPermission(value, true)?.let { element.setAttribute(attribute, it) }
            }
            val process = element.getAttribute("android:process")
            if (process == old || process.startsWith("$old:")) element.setAttribute("android:process", new + process.substring(old.length))
            // Explicit affinities (relative ones follow the package by themselves): Android puts a screen into any task
            // with its affinity, also one of Samsung's copy.
            val affinity = element.getAttribute("android:taskAffinity")
            if (affinity.isNotEmpty() && !affinity.startsWith(".") && !affinity.startsWith(":")) {
                val value = if (affinity.startsWith("@string/")) strings.resolve(affinity) else affinity
                if (value.isNotEmpty() && !value.startsWith(new)) {
                    element.setAttribute("android:taskAffinity", cloneName(value, old, new))
                    affinities++
                }
            }
        }

        // The renamed siblings must be visible to this app (Android 11 package visibility).
        if (siblings.isNotEmpty()) {
            val queries = manifest.elements("queries").firstOrNull { it.parentNode == root }
                ?: manifest.createElement("queries").also { root.insertBefore(it, application) }
            for (renamed in siblings.values) {
                queries.appendChild(manifest.createElement("package").apply { setAttribute("android:name", renamed) })
            }
        }

        val left = (declarations.map { it.getAttribute("android:name") } +
            providers.flatMap { it.getAttribute("android:authorities").split(';') })
            .filter { it.isNotBlank() && it !in FRAMEWORK_PERMISSIONS && !it.startsWith(new) }
        if (left.isNotEmpty()) throw PatchException("declared names that did not move: $left")
    }

    strings.close()
    val xml = moveResourceXml(old, new, own)
    val values = moveSiblingValues(moved.fixed.filterKeys { name -> siblings.keys.any { !it.atRuntime && it.stock == name } })

    log.info(
        "$old -> $new: $permissions permissions, $authorities authorities, $actions actions, $affinities task " +
            "affinities, $xml resource XML names and $values sibling values moved; ${added.size} sibling permissions requested",
    )
    if (permissions + authorities < minimumNames) {
        throw PatchException("package rename found only $permissions permissions and $authorities authorities")
    }
}

/** One UI's vendor namespaces: actions there that the app's screens declare are the app's own. */
private val VENDOR_ACTION = Regex("^(com\\.samsung\\.|com\\.sec\\.|samsung\\.|sec\\.)")

/**
 * Whether a manifest action is the app's own and moves with it: under its package or [privateActionPrefixes], or a
 * One UI vendor action one of its screens (activity, activity-alias) declares. Not: a sibling's (under its package,
 * which the sibling's code builds on), or one the manifest also uses as another name (a meta-data key the code reads).
 * ClonePairs' lists were made by the same rule.
 */
private fun isOwnAction(
    name: String,
    action: Element,
    old: String,
    privateActionPrefixes: List<String>,
    siblings: Set<CloneSibling>,
    otherNames: Set<String>,
): Boolean {
    if (name.startsWith("$old.") || privateActionPrefixes.any { name.startsWith(it) }) return true
    if (!VENDOR_ACTION.containsMatchIn(name) || name in otherNames) return false
    if (siblings.any { name == it.stock || name.startsWith("${it.stock}.") }) return false
    var parent = action.parentNode
    while (parent is Element) {
        if (parent.tagName == "activity" || parent.tagName == "activity-alias") return true
        parent = parent.parentNode
    }
    return false
}

/** @string/ values from the default strings, decoded only when the manifest names one (task affinities). */
private class DefaultStrings(private val context: ResourcePatchContext) {
    private var document: app.morphe.patcher.util.Document? = null

    fun resolve(reference: String): String {
        val strings = document ?: run {
            context.decode("res/values/strings.xml")
            context.document("res/values/strings.xml").also { document = it }
        }
        val name = reference.removePrefix("@string/")
        return strings.elements("string").firstOrNull { it.getAttribute("name") == name }?.textContent
            ?: throw PatchException("$reference has no default value")
    }

    fun close() {
        document?.close()
    }
}

/** Attributes in res/xml files (shortcuts, searchables, sync adapters) that name the package or an authority. */
private val XML_ATTRIBUTES = listOf(
    "android:targetPackage", "android:searchSuggestAuthority", "android:contentAuthority", "android:authority",
    "android:accountType",
)

/** Launcher shortcuts, searchables, authenticators and sync adapters naming the package or a moved name; returns how many. */
private fun ResourcePatchContext.moveResourceXml(old: String, new: String, own: MutableMap<String, String>): Int {
    decode("res/xml", "res/xml-*")
    val res = this["res"]
    var moved = 0
    val dirs = res.listFiles()!!.filter { it.isDirectory && (it.name == "xml" || it.name.startsWith("xml-")) }
    // The account types the app's authenticators declare: Android keeps one authenticator per type, Samsung's copy's
    // on a Galaxy phone, and refuses this copy its accounts. They move like the other names (also its sync adapters'
    // and the code's).
    for (dir in dirs) {
        for (file in dir.listFiles()!!.filter { it.name.endsWith(".xml") }) {
            if ("account-authenticator" !in file.readText()) continue
            document("res/${dir.name}/${file.name}").use { xml ->
                val root = xml.documentElement
                val type = root.getAttribute("android:accountType")
                if (root.tagName == "account-authenticator" && type.isNotEmpty() && !type.startsWith("@")) {
                    own.getOrPut(type) { cloneName(type, old, new) }
                }
            }
        }
    }
    for (dir in dirs) {
        for (file in dir.listFiles()!!.filter { it.name.endsWith(".xml") }) {
            val text = file.readText()
            if (own.keys.none { "\"$it\"" in text || "\"content://$it" in text }) continue
            document("res/${dir.name}/${file.name}").use { xml ->
                for (element in xml.elements("*")) {
                    for (attribute in XML_ATTRIBUTES) {
                        val value = element.getAttribute(attribute)
                        if (value.isEmpty()) continue
                        val to = if (attribute == "android:targetPackage") new.takeIf { value == old } else own[value]
                        if (to != null) {
                            element.setAttribute(attribute, to)
                            moved++
                        }
                    }
                    val data = element.getAttribute("android:data")
                    contentUri(data, own)?.let { element.setAttribute("android:data", it); moved++ }
                }
            }
        }
    }
    return moved
}

/**
 * A pair sibling's package as a string or array item in the app's values (One UI Home only loads plugins of the
 * packages in its config_pluginAllowlist array): the renamed sibling's. Returns how many.
 */
private fun ResourcePatchContext.moveSiblingValues(packages: Map<String, String>): Int {
    if (packages.isEmpty()) return 0
    decode("res/values", "res/values-*")
    var moved = 0
    for (dir in this["res"].listFiles()!!.filter { it.isDirectory && (it.name == "values" || it.name.startsWith("values-")) }) {
        for (file in dir.listFiles()!!.filter { it.name.endsWith(".xml") }) {
            val text = file.readText()
            if (packages.keys.none { ">$it<" in text }) continue
            document("res/${dir.name}/${file.name}").use { xml ->
                for (tag in listOf("item", "string")) {
                    for (element in xml.elements(tag)) {
                        val to = packages[element.textContent] ?: continue
                        element.textContent = to
                        moved++
                    }
                }
            }
        }
    }
    return moved
}

/**
 * Moves the code's literals: the app's own moved names (exact, and as content:// / package: / android.resource:// URIs
 * and process names), a pair's sibling names, and turns a runtime sibling's names into a pick (see Siblings).
 * Returns (literals moved, sibling literals picked at run time).
 */
private fun BytecodePatchContext.moveCodeLiterals(moved: MovedNames, extensionClasses: String?): Pair<Int, Int> {
    val old = moved.stock
    val new = moved.new
    // The app's own name wins where a sibling declares the same one.
    val all = moved.fixed + moved.own

    fun rename(s: String): String? {
        all[s]?.let { return it }
        if (s.startsWith("$old:")) return new + s.substring(old.length)
        for (scheme in listOf("package:", "android.resource://")) {
            if (s == scheme + old || s.startsWith("$scheme$old/")) return scheme + new + s.substring(scheme.length + old.length)
        }
        return contentUri(s, all)
    }

    val literals = rewriteStringLiterals(::rename)

    // The port's own extension: only a pair sibling's names (its own code asks Android for its own package).
    var extension = 0
    if (extensionClasses != null && moved.fixed.isNotEmpty()) {
        extension = rewriteLiteralsIn(extensionClasses) { moved.fixed[it] ?: contentUri(it, moved.fixed) }
    }

    var picks = 0
    if (moved.runtime.isNotEmpty()) {
        val runtime = moved.runtime
        fun pick(s: String): Pair<String, String>? {
            runtime[s]?.let { (renamed, pkg) -> return renamed to pkg }
            if (!s.startsWith("content://")) return null
            val names = runtime.mapValues { it.value.first }
            val to = contentUri(s, names) ?: return null
            val hit = runtime.keys.filter { s.removePrefix("content://").let { r -> r == it || r.startsWith("$it/") } }
                .maxBy { it.length }
            return to to runtime.getValue(hit).second
        }
        picks = pickAtRuntime(::pick)
    }

    // Switches over strings compare hash codes worked out at build time: a renamed case string keeps its old hash.
    val hashes = mutableMapOf<String, Int>()
    for ((stock, renamed) in all) if (stock.hashCode() != renamed.hashCode()) hashes[renamed] = stock.hashCode()
    for ((stock, renamed) in moved.runtime) if (stock.hashCode() != renamed.first.hashCode()) hashes[renamed.first] = stock.hashCode()
    val switches = keepSwitchHashes(hashes)
    log.info("$old -> $new: $switches string switches keep their renamed cases")
    return (literals + extension) to picks
}

/**
 * Sends String.hashCode() in every method whose string switch has a case for one of [hashes]' original names to a
 * generated Switches.hash, which answers each renamed name's original hash code. Returns the methods changed.
 */
private fun BytecodePatchContext.keepSwitchHashes(hashes: Map<String, Int>): Int {
    if (hashes.isEmpty()) return 0
    val wanted = hashes.values.toSet()
    fun Any.isHashCode() = this is ReferenceInstruction && (opcode == Opcode.INVOKE_VIRTUAL || opcode == Opcode.INVOKE_VIRTUAL_RANGE) &&
        (reference as? MethodReference)?.let { "${it.definingClass}->${it.name}(${it.parameterTypes.joinToString("")})${it.returnType}" } == STRING_HASH
    val used = mutableSetOf<Int>()
    val sites = mutableListOf<Pair<String, Method>>()
    classDefForEach { c ->
        if (c.type.startsWith("Lorg/ungoogled/")) return@classDefForEach
        for (m in c.methods) {
            val instructions = m.implementation?.instructions ?: continue
            if (instructions.none { it.isHashCode() }) continue
            val keys = instructions.flatMap { insn ->
                (insn as? com.android.tools.smali.dexlib2.iface.instruction.SwitchPayload)?.switchElements?.map { it.key } ?: emptyList()
            }.filter { it in wanted }
            if (keys.isEmpty()) continue
            used += keys
            sites += c.type to m
        }
    }
    if (sites.isEmpty()) return 0

    // hash(String): the original hash code of each renamed name a switch has a case for, else the string's own.
    val body = StringBuilder()
    hashes.filterValues { it in used }.entries.forEachIndexed { i, (renamed, hash) ->
        val quoted = renamed.replace("\\", "\\\\").replace("\"", "\\\"")
        body.append(
            """
                const-string v0, "$quoted"
                invoke-virtual { v0, p0 }, Ljava/lang/String;->equals(Ljava/lang/Object;)Z
                move-result v0
                if-eqz v0, :next$i
                const v0, $hash
                return v0
                :next$i
            """,
        )
    }
    body.append(
        """
            invoke-virtual { p0 }, $STRING_HASH
            move-result v0
            return v0
        """,
    )
    mutableClassDefBy(SWITCHES).methods.add(
        ImmutableMethod(
            SWITCHES, "hash", listOf(com.android.tools.smali.dexlib2.immutable.ImmutableMethodParameter("Ljava/lang/String;", null, null)),
            "I", AccessFlags.PUBLIC.value or AccessFlags.STATIC.value, null, null, MutableMethodImplementation(2),
        ).toMutable().apply { addInstructions(0, body.toString()) },
    )

    for ((type, method) in sites) {
        val mutable = mutableClassDefBy(type).methods.first {
            it.name == method.name && it.parameterTypes == method.parameterTypes && it.returnType == method.returnType
        }
        val instructions = mutable.implementation!!.instructions
        for (index in instructions.indices) {
            val insn = instructions[index]
            if (!insn.isHashCode()) continue
            val call = when (insn) {
                is com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction -> "invoke-static { v${insn.registerC} }, $SWITCHES->hash(Ljava/lang/String;)I"
                is com.android.tools.smali.dexlib2.iface.instruction.RegisterRangeInstruction -> "invoke-static/range { v${insn.startRegister} .. v${insn.startRegister} }, $SWITCHES->hash(Ljava/lang/String;)I"
                else -> throw PatchException("unexpected ${insn.opcode} in $type.${method.name}")
            }
            mutable.replaceInstruction(index, call)
        }
    }
    return sites.size
}

private val CONST_STRING = setOf(Opcode.CONST_STRING, Opcode.CONST_STRING_JUMBO)

private fun Any.literal(): String? =
    if (this is ReferenceInstruction && opcode in CONST_STRING) (reference as? StringReference)?.string else null

/** rewriteStringLiterals for the classes under [prefix] only (our own extension's). */
private fun BytecodePatchContext.rewriteLiteralsIn(prefix: String, change: (String) -> String?): Int {
    val sites = mutableListOf<Pair<String, Method>>()
    classDefForEach { c ->
        if (!c.type.startsWith(prefix)) return@classDefForEach
        for (m in c.methods) {
            if (m.implementation?.instructions?.any { it.literal()?.let(change) != null } == true) sites += c.type to m
        }
    }
    var rewritten = 0
    for ((type, method) in sites) {
        val mutable = mutableClassDefBy(type).methods.first {
            it.name == method.name && it.parameterTypes == method.parameterTypes && it.returnType == method.returnType
        }
        val instructions = mutable.implementation!!.instructions
        for (index in instructions.indices) {
            val insn = instructions[index]
            val to = insn.literal()?.let(change) ?: continue
            mutable.replaceInstruction(index, "const-string/jumbo v${(insn as OneRegisterInstruction).registerA}, \"$to\"")
            rewritten++
        }
    }
    return rewritten
}

/**
 * Each literal [pick] answers (renamed name, renamed sibling package) for becomes a call to a generated Siblings method
 * that returns the renamed name while that sibling is installed and the literal otherwise. Returns how many.
 */
private fun BytecodePatchContext.pickAtRuntime(pick: (String) -> Pair<String, String>?): Int {
    val siblings = mutableClassDefBy(SIBLINGS)
    val generated = mutableMapOf<String, String>()
    fun picker(stock: String, renamed: String, pkg: String): String = generated.getOrPut(stock) {
        val name = "s${generated.size}"
        fun quoted(s: String) = s.replace("\\", "\\\\").replace("\"", "\\\"")
        siblings.methods.add(
            ImmutableMethod(
                SIBLINGS, name, emptyList(), "Ljava/lang/String;",
                AccessFlags.PUBLIC.value or AccessFlags.STATIC.value, null, null, MutableMethodImplementation(3),
            ).toMutable().apply {
                addInstructions(
                    0,
                    """
                        const-string v0, "${quoted(stock)}"
                        const-string v1, "${quoted(renamed)}"
                        const-string v2, "${quoted(pkg)}"
                        invoke-static { v0, v1, v2 }, $SIBLINGS->pick(Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;)Ljava/lang/String;
                        move-result-object v0
                        return-object v0
                    """,
                )
            },
        )
        "$SIBLINGS->$name()Ljava/lang/String;"
    }

    val sites = mutableListOf<Pair<String, Method>>()
    classDefForEach { c ->
        if (c.type.startsWith("Lorg/ungoogled/")) return@classDefForEach
        for (m in c.methods) {
            if (m.implementation?.instructions?.any { it.literal()?.let(pick) != null } == true) sites += c.type to m
        }
    }
    var picked = 0
    for ((type, method) in sites) {
        val mutable = mutableClassDefBy(type).methods.first {
            it.name == method.name && it.parameterTypes == method.parameterTypes && it.returnType == method.returnType
        }
        val instructions = mutable.implementation!!.instructions
        // Back to front: each literal becomes two instructions.
        for (index in instructions.indices.reversed()) {
            val insn = instructions[index]
            val stock = insn.literal() ?: continue
            val (renamed, pkg) = pick(stock) ?: continue
            val register = (insn as OneRegisterInstruction).registerA
            mutable.replaceInstruction(index, "invoke-static { }, ${picker(stock, renamed, pkg)}")
            mutable.addInstruction(index + 1, "move-result-object v$register")
            picked++
        }
    }
    return picked
}

/** One UI's framework classes (from a One UI 7 framework plus the ports' stand-ins), as dex type names. */
private val ONE_UI_CLASSES: Set<String> by lazy {
    val stream = CloneSibling::class.java.classLoader.getResourceAsStream("samsung/oneui-framework-classes.txt")
        ?: throw PatchException("the One UI framework class list is missing from the bundle")
    stream.bufferedReader().useLines { lines -> lines.filter { it.isNotBlank() }.toSet() }
}

/**
 * On a Galaxy phone One UI's framework classes are Samsung's real ones, which refuse this renamed copy what only
 * Samsung's own build may do; off One UI they are the port's stand-ins. Every call into them (not constructors, not
 * super calls, not into classes the app extends or implements, whose calls must stay as they are) goes through
 * Galaxy, which answers a refused call as if the phone did not have it.
 */
private fun oneUiFrameworkClassesPatch() = bytecodePatch(
    description = "Keeps One UI's framework classes from stopping the renamed app where they refuse it.",
) {
    extendWith(CLONE_EXTENSION)

    execute {
        val inherited = mutableSetOf<String>()
        val calls = mutableSetOf<String>()
        val direct = mutableSetOf<String>()
        classDefForEach { c ->
            if (c.type.startsWith("Lorg/ungoogled/") || c.type in ONE_UI_CLASSES) return@classDefForEach
            (listOfNotNull(c.superclass) + c.interfaces).filter { it in ONE_UI_CLASSES }.forEach { inherited += it }
            for (m in c.methods) {
                for (insn in m.implementation?.instructions ?: continue) {
                    val ref = (insn as? ReferenceInstruction)?.reference as? MethodReference ?: continue
                    if (ref.definingClass !in ONE_UI_CLASSES || ref.name == "<init>" || ref.name == "<clinit>") continue
                    val key = "${ref.definingClass}->${ref.name}(${ref.parameterTypes.joinToString("")})${ref.returnType}"
                    when (insn.opcode) {
                        Opcode.INVOKE_SUPER, Opcode.INVOKE_SUPER_RANGE, Opcode.INVOKE_DIRECT, Opcode.INVOKE_DIRECT_RANGE -> direct += key
                        else -> calls += key
                    }
                }
            }
        }
        val keys = calls.filter { key -> key !in direct && inherited.none { key.startsWith("$it->") } }.toSet()
        val (moved, _) = redirectMembers(GALAXY, SAMSUNG_CLASSES, keys) { _, _ -> false }
        log.info("One UI framework class calls through Galaxy: $moved (${keys.size} members)")
    }
}

/** The ContentResolver calls Resolver stands in for ("name(params)return"). */
private val RESOLVER_CALLS = setOf(
    "query(Landroid/net/Uri;[Ljava/lang/String;Ljava/lang/String;[Ljava/lang/String;Ljava/lang/String;)Landroid/database/Cursor;",
    "query(Landroid/net/Uri;[Ljava/lang/String;Ljava/lang/String;[Ljava/lang/String;Ljava/lang/String;Landroid/os/CancellationSignal;)Landroid/database/Cursor;",
    "query(Landroid/net/Uri;[Ljava/lang/String;Landroid/os/Bundle;Landroid/os/CancellationSignal;)Landroid/database/Cursor;",
    "call(Landroid/net/Uri;Ljava/lang/String;Ljava/lang/String;Landroid/os/Bundle;)Landroid/os/Bundle;",
    "call(Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;Landroid/os/Bundle;)Landroid/os/Bundle;",
    "insert(Landroid/net/Uri;Landroid/content/ContentValues;)Landroid/net/Uri;",
    "update(Landroid/net/Uri;Landroid/content/ContentValues;Ljava/lang/String;[Ljava/lang/String;)I",
    "delete(Landroid/net/Uri;Ljava/lang/String;[Ljava/lang/String;)I",
    "getType(Landroid/net/Uri;)Ljava/lang/String;",
    "registerContentObserver(Landroid/net/Uri;ZLandroid/database/ContentObserver;)V",
    "notifyChange(Landroid/net/Uri;Landroid/database/ContentObserver;)V",
    "openInputStream(Landroid/net/Uri;)Ljava/io/InputStream;",
    "acquireContentProviderClient(Ljava/lang/String;)Landroid/content/ContentProviderClient;",
    "acquireContentProviderClient(Landroid/net/Uri;)Landroid/content/ContentProviderClient;",
    "acquireUnstableContentProviderClient(Ljava/lang/String;)Landroid/content/ContentProviderClient;",
    "acquireUnstableContentProviderClient(Landroid/net/Uri;)Landroid/content/ContentProviderClient;",
)

/**
 * On a Galaxy phone Samsung's own content providers are there and many refuse this renamed copy (SecurityException),
 * where the port expects them to be absent. The app's remaining ContentResolver calls (the port's own wrappers keep
 * theirs) go through Resolver, which answers a Samsung provider's refusal as an absent provider.
 */
private fun samsungProvidersPatch() = bytecodePatch(
    description = "Answers Samsung's content providers refusing the renamed app as absent ones.",
) {
    extendWith(CLONE_EXTENSION)

    execute {
        val moved = redirectCalls(RESOLVER, mapOf("Landroid/content/ContentResolver;" to RESOLVER_CALLS))
        log.info("ContentResolver calls through Resolver: $moved")
    }
}
