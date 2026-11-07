package org.ungoogled.patches.gmail

import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.extensions.InstructionExtensions.addInstructionsWithLabels
import app.morphe.patcher.extensions.InstructionExtensions.replaceInstruction
import app.morphe.patcher.patch.AvailabilityResolver
import app.morphe.patcher.patch.InstallerType
import app.morphe.patcher.patch.PatchAvailability
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.patch.resourcePatch
import app.morphe.patcher.util.smali.ExternalLabel
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.formats.Instruction35c
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import org.ungoogled.patches.gmail.GmailConstants.COMPATIBILITY_GMAIL
import org.ungoogled.patches.gmail.GmailConstants.GMAIL_EXTENSION
import org.ungoogled.patches.shared.MicrogRe
import org.w3c.dom.Document
import org.w3c.dom.Element
import java.io.File
import java.util.Locale
import kotlin.math.cos
import kotlin.math.sin

private const val STOCK_PACKAGE = "com.google.android.gm"
/** microG Gmail's own package, so it installs beside stock Gmail (usually built into the phone). */
private const val MICROG_GMAIL = "org.ungoogled.android.gm.microg"
/** Gmail's signing certificate (SHA-1, the legacy signer, the same as Maps'), which microG reports to Google for this app. */
private const val STOCK_CERT_SHA1 = "38918a453d07199354f8b19af05ec6562ced5788"
private const val APP_NAME = "microG Gmail"
private const val STOCK_LABEL = "@string/ag_app_name"
private const val NEW_LABEL = "@string/ungoogled_app_name"
private const val ICON = "ungoogled_microg_icon"

/**
 * Gmail's own names that must move with the package, or the two Gmails collide (Android refuses a second app
 * with the same provider authority or permission) or share accounts they cannot open: every provider
 * authority, every permission Gmail declares, and the account types of its Exchange, IMAP and POP3 accounts,
 * whose authenticators belong to whichever Gmail was installed first. Measured against this Gmail version; the
 * manifest half checks the manifest still holds exactly these.
 */
private val AUTHORITIES = listOf(
    "com.google.android.gmail.provider", "com.google.android.gm.email.provider", "com.google.android.gm.tasks.provider",
    "com.google.android.gm.email.notifier", "com.google.android.gmail.conversation.provider", "gmail-ls", "com.google.android.gm",
    "com.google.android.gm.combinedaccounts", "com.google.android.gm.hub.features", "com.google.android.gm.email.uinotifications",
    "com.android.gmail.uiinternal", "com.android.gmail.ui", "com.google.android.gm.fileprovider", "com.google.android.gm2.accountcache",
    "com.google.android.gm.provider.eml.attachment", "com.google.android.gmail.attachmentprovider",
    "com.google.android.gm.email.attachmentprovider", "com.google.android.gm.exchange.bundled.directory.provider",
    "com.google.android.gm.sapi", "com.google.android.gm.composeimagefileprovider", "com.google.android.gm.generativeaiimagefileprovider",
    "com.google.android.gm.androidx-startup",
    "com.google.android.libraries.hub.media.viewer.data.storage.filesystem.fileprovider.com.google.android.gm",
    "com.google.android.gm.tracing", "com.google.android.gm.chime_sdk_file_provider", "com.google.android.gm.lifecycle-process",
)
private val PERMISSIONS = listOf(
    "com.google.android.gm.email.permission.READ_ATTACHMENT", "com.google.android.gm.email.permission.ACCESS_PROVIDER",
    "com.google.android.gm.email.permission.UPDATE_AUTH_NOTIFICATION", "com.google.android.gm.email.permission.GET_WIDGET_UPDATE",
    "com.google.android.gm.permission.READ_GMAIL", "com.google.android.gm.permission.WRITE_GMAIL",
    "com.google.android.gm.permission.AUTO_SEND", "com.google.android.gm.permission.READ_CONTENT_PROVIDER",
    "com.google.android.gm.permission.BROADCAST_INTERNAL", "com.google.android.gm.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION",
    "com.google.android.gm.permission.READ_HUB_FEATURE_STATUS",
)
/** The account types of Gmail's Exchange, IMAP and POP3 accounts. */
private val ACCOUNT_TYPES = listOf("com.google.android.gm.exchange", "com.google.android.gm.legacyimap", "com.google.android.gm.pop3")

/** A name of Gmail's own, moved under microG Gmail's package. */
private fun moved(name: String): String = when {
    name == STOCK_PACKAGE -> MICROG_GMAIL
    name.startsWith("$STOCK_PACKAGE.") -> MICROG_GMAIL + name.substring(STOCK_PACKAGE.length)
    name.endsWith(".$STOCK_PACKAGE") -> name.removeSuffix(STOCK_PACKAGE) + MICROG_GMAIL
    name.startsWith("com.google.android.") -> "$MICROG_GMAIL." + name.removePrefix("com.google.android.")
    name.startsWith("com.android.") -> "$MICROG_GMAIL." + name.removePrefix("com.")
    else -> "$MICROG_GMAIL.$name"
}

/** Every name of Gmail's own the patch moves, old to new. */
private val OWN_NAMES: Map<String, String> = (AUTHORITIES + PERMISSIONS + ACCOUNT_TYPES).associateWith(::moved)

/** microG Gmail's name for one of Gmail's own names, or a content URI with one of its authorities; null if neither. */
private fun ownName(value: String): String? {
    OWN_NAMES[value]?.let { return it }
    if (value.startsWith("content://")) {
        val rest = value.removePrefix("content://")
        val authority = rest.substringBefore('/')
        if (authority in AUTHORITIES) return "content://" + moved(authority) + rest.substring(authority.length)
    }
    return null
}

/** microG Gmail's name for a whole string in Gmail's code: Play services' (microG-RE's) or Gmail's own; null if neither. */
private fun rename(value: String): String? = MicrogRe.route(value) ?: ownName(value)

private fun Document.elements(tag: String): List<Element> =
    getElementsByTagName(tag).let { list -> (0 until list.length).map { list.item(it) as Element } }

/**
 * microG's C -- its own logo, four arcs open to the right -- as a badge on the M of Gmail's icon, in the
 * adaptive icon's 108-unit space: a white disc at the lower right, inside the area every launcher mask keeps,
 * with the C at 86% of it so a white ring stays around it (microG's proportions: 30 wide at radius 70).
 */
private fun microgBadge(): String {
    val cx = 68.5
    val cy = 68.5
    val disc = 13.5
    val outer = disc * 0.86
    val width = outer * 30 / 70
    val r = outer - width / 2
    fun f(v: Double) = String.format(Locale.ROOT, "%.2f", v)
    fun at(degrees: Int): String {
        val a = Math.toRadians(degrees.toDouble())
        return "${f(cx + r * cos(a))},${f(cy + r * sin(a))}"
    }
    val arcs = listOf(Triple(45, 120, "#E91E63"), Triple(120, 195, "#FF9800"), Triple(195, 240, "#CDDC39"), Triple(240, 315, "#009688"))
    return buildString {
        append("<?xml version=\"1.0\" encoding=\"utf-8\"?>\n")
        append("<vector xmlns:android=\"http://schemas.android.com/apk/res/android\"\n")
        append("    android:width=\"108dp\" android:height=\"108dp\"\n")
        append("    android:viewportWidth=\"108\" android:viewportHeight=\"108\">\n")
        append("    <path android:fillColor=\"#FFFFFF\"\n")
        append("        android:pathData=\"M${f(cx - disc)},${f(cy)} a${f(disc)},${f(disc)} 0 1,0 ${f(2 * disc)},0 a${f(disc)},${f(disc)} 0 1,0 ${f(-2 * disc)},0z\" />\n")
        for ((from, to, colour) in arcs) {
            append("    <path android:strokeColor=\"$colour\" android:strokeLineCap=\"butt\" android:strokeWidth=\"${f(width)}\"\n")
            append("        android:pathData=\"M${at(from)} A${f(r)},${f(r)} 0 0,1 ${at(to)}\" />\n")
        }
        append("</vector>\n")
    }
}

private val gmailMicrogResourcesPatch = resourcePatch(
    description = "Moves Gmail's package, providers, permissions and account types under microG Gmail's own, points its " +
        "Play services permissions at microG and tells microG which app to vouch for.",
) {
    execute {
        val counts = linkedMapOf<String, Int>()
        fun count(what: String) { counts[what] = (counts[what] ?: 0) + 1 }

        document("AndroidManifest.xml").use { manifest ->
            val root = manifest.documentElement
            if (root.getAttribute("package") != STOCK_PACKAGE) throw PatchException("not Gmail's manifest")
            root.setAttribute("package", MICROG_GMAIL)

            // Gmail's own permissions: their declarations, the ones it holds, and the components they guard.
            val declared = manifest.elements("permission").map { it.getAttribute("android:name") }.toSet()
            if (declared != PERMISSIONS.toSet()) throw PatchException("Gmail's own permissions changed: ${declared - PERMISSIONS.toSet()} / ${PERMISSIONS.toSet() - declared}")
            for (element in manifest.elements("*")) {
                val attributes = when (element.tagName) {
                    "permission", "uses-permission" -> listOf("android:name")
                    else -> listOf("android:permission", "android:readPermission", "android:writePermission")
                }
                for (attribute in attributes) {
                    val value = element.getAttribute(attribute)
                    val to = if (value in declared) moved(value) else MicrogRe.PERMISSION_ROUTES[value] ?: continue
                    element.setAttribute(attribute, to)
                    count(if (value in declared) "own permission" else "Play services permission")
                }
                // A task affinity naming Gmail's package would put microG Gmail's screens into stock Gmail's tasks.
                val affinity = element.getAttribute("android:taskAffinity")
                if (affinity == STOCK_PACKAGE || affinity.startsWith("$STOCK_PACKAGE.")) {
                    element.setAttribute("android:taskAffinity", moved(affinity))
                    count("task affinity")
                }
            }

            // Every provider of Gmail's own: authorities are unique on a phone.
            val application = manifest.elements("application").single()
            val authorities = mutableListOf<String>()
            for (provider in manifest.elements("provider").filter { it.parentNode == application }) {
                val list = provider.getAttribute("android:authorities").split(';')
                authorities += list
                provider.setAttribute("android:authorities", list.joinToString(";") { moved(it) })
            }
            if (authorities.toSet() != AUTHORITIES.toSet()) {
                throw PatchException("Gmail's providers changed: ${authorities.toSet() - AUTHORITIES.toSet()} / ${AUTHORITIES.toSet() - authorities.toSet()}")
            }

            // Android 11+ package visibility, and whom microG vouches for: stock Gmail's package and certificate.
            val queries = manifest.elements("queries").firstOrNull { it.parentNode == root }
                ?: manifest.createElement("queries").also { root.insertBefore(it, root.firstChild) }
            queries.appendChild(manifest.createElement("package").apply { setAttribute("android:name", MicrogRe.PACKAGE) })
            fun meta(name: String, value: String) = application.appendChild(manifest.createElement("meta-data").apply {
                setAttribute("android:name", name)
                setAttribute("android:value", value)
            })
            meta("${MicrogRe.PACKAGE}.SPOOFED_PACKAGE_NAME", STOCK_PACKAGE)
            meta("${MicrogRe.PACKAGE}.SPOOFED_PACKAGE_SIGNATURE", STOCK_CERT_SHA1)
            meta("${MicrogRe.VENDOR}.MICROG_PACKAGE_NAME", MicrogRe.PACKAGE)
            meta("${MicrogRe.PACKAGE}.MICROG_PACKAGE_NAME", MicrogRe.PACKAGE)

            // The launcher and app-switcher name and icon.
            for (element in manifest.elements("*")) {
                // NEW_LABEL already: Change app name ran first and keeps its own name.
                if (element.getAttribute("android:label") in setOf(STOCK_LABEL, NEW_LABEL)) {
                    element.setAttribute("android:label", NEW_LABEL)
                    count("label")
                }
                for (attribute in listOf("android:icon", "android:roundIcon")) {
                    if (element.getAttribute(attribute) in setOf("@mipmap/ic_launcher", "@mipmap/ic_launcher_round")) {
                        element.setAttribute(attribute, "@mipmap/$ICON")
                        count("icon")
                    }
                }
            }
        }

        // The string resources naming Gmail's own account types, authorities and account-list URIs (the account
        // list is read from content://<authority>/... strings, so a stock-named one opens stock Gmail's provider and
        // is refused), and the app name.
        document("res/values/strings.xml").use { strings ->
            for (string in strings.elements("string")) {
                val to = ownName(string.textContent) ?: continue
                string.textContent = to
                count("own name in strings")
            }
            if (strings.elements("string").none { it.getAttribute("name") == NEW_LABEL.removePrefix("@string/") }) {
                strings.documentElement.appendChild(strings.createElement("string").apply {
                    setAttribute("name", NEW_LABEL.removePrefix("@string/"))
                    textContent = APP_NAME
                })
            }
        }

        // Sync adapters for Google accounts (microG's account type) and the launcher shortcuts' target package.
        decode("res/xml", "res/xml-*")
        val res = this["res"]
        for (dir in res.listFiles()!!.filter { it.isDirectory && (it.name == "xml" || it.name.startsWith("xml-")) }) {
            for (file in dir.listFiles()!!.filter { it.name.endsWith(".xml") }) {
                val text = file.readText()
                if ("\"com.google\"" !in text && "\"$STOCK_PACKAGE\"" !in text) continue
                document("res/${dir.name}/${file.name}").use { xml ->
                    for (element in xml.elements("*")) {
                        if (element.tagName == "sync-adapter" && element.getAttribute("android:accountType") == "com.google") {
                            element.setAttribute("android:accountType", MicrogRe.VENDOR)
                            count("Google sync adapter")
                        }
                        if (element.getAttribute("android:targetPackage") == STOCK_PACKAGE) {
                            element.setAttribute("android:targetPackage", MICROG_GMAIL)
                            count("shortcut package")
                        }
                    }
                }
            }
        }

        // microG Gmail's icon: Gmail's own, with microG's C as a badge.
        File(res, "drawable").mkdirs()
        File(res, "drawable/ungoogled_microg_badge.xml").writeText(microgBadge())
        File(res, "drawable/ungoogled_microg_foreground.xml").writeText(
            """
            <?xml version="1.0" encoding="utf-8"?>
            <layer-list xmlns:android="http://schemas.android.com/apk/res/android">
                <item android:drawable="@mipmap/adaptiveproduct_gmail_2026_foreground_color_108" />
                <item android:drawable="@drawable/ungoogled_microg_badge" />
            </layer-list>
            """.trimIndent() + "\n",
        )
        File(res, "mipmap-anydpi").mkdirs()
        File(res, "mipmap-anydpi/$ICON.xml").writeText(
            """
            <?xml version="1.0" encoding="utf-8"?>
            <adaptive-icon xmlns:android="http://schemas.android.com/apk/res/android">
                <background android:drawable="@mipmap/adaptiveproduct_gmail_2026_background_color_108" />
                <foreground android:drawable="@drawable/ungoogled_microg_foreground" />
                <monochrome android:drawable="@drawable/ic_launcher_monochrome_release" />
            </adaptive-icon>
            """.trimIndent() + "\n",
        )

        // Measured against this Gmail version.
        val expected = mapOf(
            // 3 account types (+1 Exchange package string of the same name), 3 sync authorities, 2 account-list URIs.
            "own permission" to 11, "Play services permission" to 4, "own name in strings" to 9,
            "Google sync adapter" to 1, "shortcut package" to 1, "label" to 1, "icon" to 2,
        )
        val short = expected.filter { (k, v) -> (counts[k] ?: 0) < v }
        if (short.isNotEmpty()) throw PatchException("microG Gmail found less than expected: " + short.keys.joinToString { "$it ${counts[it] ?: 0}/${expected[it]}" })
    }
}

/** Gmail's helper that connects its Play services clients and shows Play services' error dialog when one fails. */
private const val PLAY_SERVICES_HELPER = "com/google/android/apps/gmail/libraries/gmscore/AbstractPlayServicesHelper"
private const val CONNECTION_RESULT = "Lcom/google/android/gms/common/ConnectionResult;"
/** ConnectionResult.API_UNAVAILABLE: microG-RE has no such service (first-party People API, for one). */
private const val API_UNAVAILABLE = 16

/** Play services' own checks in Gmail: is this package signed by Google, and is Play services there. */
private const val SIGNATURE_HISTORY = "Unable to obtain package certificate history."
private const val GMS_VERSION_KEY = "com.google.android.gms.version"

@Suppress("unused")
val gmailMicrogSupportPatch = bytecodePatch(
    name = "Add microG-RE support",
    description = "Builds microG Gmail, a separate app that signs in to your Google account through microG-RE. Not for root installs.",
    default = false,
) {
    compatibleWith(COMPATIBILITY_GMAIL)
    category("microG-RE")
    // A mount install keeps stock Gmail's manifest, so microG never learns whom to vouch for.
    availability(AvailabilityResolver { installer, _ ->
        if (installer == InstallerType.MOUNT) PatchAvailability.UNAVAILABLE else PatchAvailability.DISABLED
    })
    // Google's "isn't Play Protect certified" verdict shut microG Gmail down (issue #70).
    dependsOn(gmailMicrogResourcesPatch, gmailPlayProtectScreenCorePatch)
    extendWith(GMAIL_EXTENSION)

    execute {
        // Play services' client library in Gmail checks that Play services is installed and signed by Google
        // before it binds; microG is neither, so both checks report success, as in microG Maps.
        val anchors = methodsUsing(setOf(SIGNATURE_HISTORY, GMS_VERSION_KEY))
        mutable(anchors.single(SIGNATURE_HISTORY, "Play services signature check")).apply {
            if (returnType != "Z" || parameterTypes.map(CharSequence::toString) != listOf("Landroid/content/pm/PackageInfo;", "Z")) {
                throw PatchException("Play services signature check changed shape")
            }
            addInstructions(0, "const/4 v0, 0x1\nreturn v0")
        }
        val availability = anchors[GMS_VERSION_KEY].orEmpty().filter {
            it.method.returnType == "I" && it.method.parameterTypes.map(CharSequence::toString) == listOf("Landroid/content/Context;", "I")
        }.singleOrNull() ?: throw PatchException("Play services availability check not found")
        mutable(availability).addInstructions(0, "const/4 v0, 0x0\nreturn v0")

        // Rewriting strings.xml round-trips every string through XML, which cannot hold a backspace: the Delete
        // menu item's keyboard shortcut came back empty, and the menu inflater's charAt(0) crashed every
        // conversation. Its two shortcut reads now treat an empty string as no shortcut.
        var shortcuts = 0
        val inflaters = mutableListOf<String>()
        classDefForEach { c -> if (c.superclass == "Landroid/view/MenuInflater;") inflaters += c.type }
        for (type in inflaters) {
            for (m in mutableClassDefBy(type).methods) {
                val instructions = m.implementation?.instructions?.toList() ?: continue
                for ((index, insn) in instructions.withIndex()) {
                    val ref = (insn as? ReferenceInstruction)?.reference as? MethodReference ?: continue
                    if (insn.opcode != Opcode.INVOKE_VIRTUAL || ref.definingClass != "Ljava/lang/String;" || ref.name != "charAt") continue
                    val call = insn as Instruction35c
                    m.replaceInstruction(index, "invoke-static { v${call.registerC}, v${call.registerD} }, Lorg/ungoogled/gmail/Safe;->charAt(Ljava/lang/String;I)C")
                    shortcuts++
                }
            }
        }
        if (shortcuts != 2) throw PatchException("expected the menu inflater to read 2 shortcut keys, found $shortcuts")

        // A Play services API microG-RE does not have (Gmail's account list asks for the first-party People API)
        // fails its connection with API_UNAVAILABLE, and Gmail answered that with "having trouble with Google Play
        // services" on every screen. Gmail already lets network and internal errors pass quietly; this one too.
        val helper = classesLogging(PLAY_SERVICES_HELPER).singleOrNull() ?: throw PatchException("Play services helper not found")
        val failed = helper.methods.singleOrNull { m ->
            m.returnType == "V" && m.parameterTypes.map(CharSequence::toString) == listOf(CONNECTION_RESULT) &&
                m.implementation?.instructions?.any { it.stringOrNull() == "onConnectionFailed" } == true
        } ?: throw PatchException("Play services helper no longer has one onConnectionFailed handler")
        mutableClassDefBy(helper.type).methods.single { it.name == failed.name && it.parameterTypes.size == 1 && it.returnType == "V" }.apply {
            val code = implementation!!.instructions.firstNotNullOfOrNull { insn ->
                ((insn as? ReferenceInstruction)?.reference as? FieldReference)?.takeIf {
                    insn.opcode == Opcode.IGET && it.definingClass == CONNECTION_RESULT && it.type == "I"
                }
            } ?: throw PatchException("connection-failure handler no longer reads the error code")
            if (implementation!!.registerCount < 4) throw PatchException("connection-failure handler has no free registers")
            addInstructionsWithLabels(
                0,
                """
                    iget v0, p1, $CONNECTION_RESULT->${code.name}:I
                    const/16 v1, $API_UNAVAILABLE
                    if-ne v0, v1, :report
                    return-void
                """,
                ExternalLabel("report", implementation!!.instructions.first()),
            )
        }
    }

    // Every Play services name and every name of Gmail's own in Gmail's code: in finalize, after every other
    // patch has run, so code they add is covered too. Not in the extension.
    finalize {
        val holders = mutableListOf<String>()
        classDefForEach { classDef ->
            if (classDef.type.startsWith("Lorg/ungoogled/")) return@classDefForEach
            if (classDef.methods.any { m -> m.implementation?.instructions?.any { it.stringOrNull()?.let(::rename) != null } == true }) {
                holders += classDef.type
            }
        }
        val rewritten = linkedMapOf<String, Int>()
        for (type in holders) {
            for (method in mutableClassDefBy(type).methods) {
                val instructions = method.implementation?.instructions?.toList() ?: continue
                instructions.forEachIndexed { index, insn ->
                    val value = insn.stringOrNull() ?: return@forEachIndexed
                    val to = rename(value) ?: return@forEachIndexed
                    val opcode = if (insn.opcode == Opcode.CONST_STRING) "const-string" else "const-string/jumbo"
                    method.replaceInstruction(index, "$opcode v${(insn as OneRegisterInstruction).registerA}, \"$to\"")
                    val kind = if (MicrogRe.route(value) != null) "Play services" else "Gmail"
                    rewritten[kind] = (rewritten[kind] ?: 0) + 1
                }
            }
        }
        // Measured on this Gmail version.
        if ((rewritten["Play services"] ?: 0) < 100 || (rewritten["Gmail"] ?: 0) < 20) {
            throw PatchException("rewrote only $rewritten names; Gmail changed shape")
        }
    }
}
