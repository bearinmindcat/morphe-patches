package org.ungoogled.patches.photos

import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.extensions.InstructionExtensions.replaceInstruction
import app.morphe.patcher.patch.AvailabilityResolver
import app.morphe.patcher.patch.InstallerType
import app.morphe.patcher.patch.PatchAvailability
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.patch.resourcePatch
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import org.ungoogled.patches.gmail.methodsUsing
import org.ungoogled.patches.gmail.mutable
import org.ungoogled.patches.gmail.single
import org.ungoogled.patches.gmail.stringOrNull
import org.ungoogled.patches.photos.PhotosConstants.COMPATIBILITY_PHOTOS
import org.ungoogled.patches.shared.MicrogRe
import org.ungoogled.patches.shared.microgBadge
import org.w3c.dom.Document
import org.w3c.dom.Element
import java.io.File

private const val STOCK_PACKAGE = "com.google.android.apps.photos"
/** microG Photos' own package, so it installs beside stock Photos (usually built into the phone). */
private const val MICROG_PHOTOS = "org.ungoogled.android.apps.photos.microg"
/** Photos' signing certificate (SHA-1, the legacy signer), which microG reports to Google for this app. */
private const val STOCK_CERT_SHA1 = "24bb24c05e47e0aefa68a58a766179d9b613a600"
private const val APP_NAME = "microG Photos"
/** The launcher's name for Photos, and the app's own (share targets, the app switcher). */
private val STOCK_LABELS = setOf("@string/photos_launchericon_label", "@string/photos_theme_google_photos")
private const val NEW_LABEL = "@string/ungoogled_app_name"
private val STOCK_ICONS = setOf("@mipmap/photos_launcher", "@mipmap/photos_round_launcher")
private const val ICON = "ungoogled_microg_icon"

/**
 * Photos' own names that must move with the package, or the two Photos collide (Android refuses a second app with
 * the same provider authority or permission): every provider authority and every permission Photos declares.
 * Measured against this Photos version; the manifest half checks the manifest still holds exactly these.
 */
private val AUTHORITIES = listOf(
    "com.google.android.apps.photos.backup.apiservice.status.BackupStatusContentProvider",
    "com.google.android.apps.photos.cloudpicker", "com.google.android.apps.photos.contentprovider",
    "com.google.android.apps.photos.freeable_space", "com.google.android.apps.photos.editor.contentprovider",
    "com.google.android.apps.photos.fileprovider", "com.google.android.libraries.photos.api.mars",
    "com.google.android.apps.photos.mars.contentprovider.local_locked_media", "com.google.android.apps.photos.memories",
    "com.google.android.apps.photos.partnercontentprovider", "com.google.android.apps.photos.photoeditor.cachedfileprovider",
    "com.google.android.apps.photos.photoeditor.localeditcontentprovider",
    "com.google.android.apps.photos.photoeditor.renderedimagecontentprovider",
    "com.google.android.apps.photos.SharouselContentProvider", "com.google.android.apps.photos.mlkitinitprovider",
    "com.google.android.apps.photos.chime_sdk_file_provider",
)
private val PERMISSIONS = listOf(
    "com.google.android.apps.photos.permission.C2D_MESSAGE",
    "com.google.android.apps.photos.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION",
)

/** A name of Photos' own, moved under microG Photos' package. */
private fun moved(name: String): String = when {
    name == STOCK_PACKAGE -> MICROG_PHOTOS
    name.startsWith("$STOCK_PACKAGE.") -> MICROG_PHOTOS + name.substring(STOCK_PACKAGE.length)
    name.startsWith("com.google.android.") -> "$MICROG_PHOTOS." + name.removePrefix("com.google.android.")
    else -> "$MICROG_PHOTOS.$name"
}

/** Every name of Photos' own the patch moves, old to new: its package (its own intents and components), authorities, permissions. */
private val OWN_NAMES: Map<String, String> = (listOf(STOCK_PACKAGE) + AUTHORITIES + PERMISSIONS).associateWith(::moved)

/** microG Photos' name for one of Photos' own names, or a content URI of Photos' own; null if neither. */
private fun ownName(value: String): String? {
    OWN_NAMES[value]?.let { return it }
    if (value.startsWith("content://")) {
        val rest = value.removePrefix("content://")
        val authority = rest.substringBefore('/')
        if (authority in AUTHORITIES) return "content://" + moved(authority) + rest.substring(authority.length)
        // Change-notification URIs Photos makes up under its own name ("content://<package>.cloudstorage.monitor").
        if (authority == STOCK_PACKAGE || authority.startsWith("$STOCK_PACKAGE.")) return "content://" + moved(authority) + rest.substring(authority.length)
    }
    return null
}

/** microG-RE's name for a Play services name in Photos' code, its push actions included; null if it is none. */
private fun playServicesName(value: String): String? = MicrogRe.route(value) ?: MicrogRe.PUSH_ACTION_ROUTES[value]

/** microG Photos' name for a whole string in Photos' code: Play services' (microG-RE's) or Photos' own; null if neither. */
private fun rename(value: String): String? = playServicesName(value) ?: ownName(value)

/** The push actions Photos' FCM client uses: without microG-RE's names it gets no token and no notifications. */
private val PUSH_ACTIONS_USED = listOf(
    "com.google.android.c2dm.intent.REGISTER", "com.google.android.c2dm.intent.REGISTRATION",
    "com.google.android.c2dm.intent.RECEIVE", "com.google.iid.TOKEN_REQUEST",
)

private fun Document.elements(tag: String): List<Element> =
    getElementsByTagName(tag).let { list -> (0 until list.length).map { list.item(it) as Element } }

private val photosMicrogResourcesPatch = resourcePatch(
    description = "Moves Photos' package, providers and permissions under microG Photos' own, points its Play services " +
        "permissions and push actions at microG and tells microG which app to vouch for.",
) {
    execute {
        val counts = linkedMapOf<String, Int>()
        fun count(what: String) { counts[what] = (counts[what] ?: 0) + 1 }

        document("AndroidManifest.xml").use { manifest ->
            val root = manifest.documentElement
            if (root.getAttribute("package") != STOCK_PACKAGE) throw PatchException("not Photos' manifest")
            root.setAttribute("package", MICROG_PHOTOS)

            // Photos' own permissions: their declarations, the ones it holds, and the components they guard.
            val declared = manifest.elements("permission").map { it.getAttribute("android:name") }.toSet()
            if (declared != PERMISSIONS.toSet()) throw PatchException("Photos' own permissions changed: ${declared - PERMISSIONS.toSet()} / ${PERMISSIONS.toSet() - declared}")
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
                // A task affinity of Photos' (its own screens' names) would put microG Photos' screens into stock
                // Photos' tasks.
                val affinity = element.getAttribute("android:taskAffinity")
                if (affinity.startsWith("com.google.android.")) {
                    element.setAttribute("android:taskAffinity", moved(affinity))
                    count("task affinity")
                }
            }

            // Push messages: microG-RE delivers them only with its own RECEIVE action.
            for (action in manifest.elements("action")) {
                val to = MicrogRe.PUSH_ACTION_ROUTES[action.getAttribute("android:name")] ?: continue
                action.setAttribute("android:name", to)
                count("push action")
            }

            // Every provider of Photos' own: authorities are unique on a phone.
            val application = manifest.elements("application").single()
            val authorities = mutableListOf<String>()
            for (provider in manifest.elements("provider").filter { it.parentNode == application }) {
                val list = provider.getAttribute("android:authorities").split(';')
                authorities += list
                provider.setAttribute("android:authorities", list.joinToString(";") { moved(it) })
            }
            if (authorities.toSet() != AUTHORITIES.toSet()) {
                throw PatchException("Photos' providers changed: ${authorities.toSet() - AUTHORITIES.toSet()} / ${AUTHORITIES.toSet() - authorities.toSet()}")
            }

            // Android 11+ package visibility, and whom microG vouches for: stock Photos' package and certificate.
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

            // The launcher, share sheet and app-switcher name, and the launcher icon.
            for (element in manifest.elements("*")) {
                if (element.getAttribute("android:label") in STOCK_LABELS) {
                    element.setAttribute("android:label", NEW_LABEL)
                    count("label")
                }
                for (attribute in listOf("android:icon", "android:roundIcon")) {
                    if (element.getAttribute(attribute) in STOCK_ICONS) {
                        element.setAttribute(attribute, "@mipmap/$ICON")
                        count("icon")
                    }
                }
            }
        }

        // The app name.
        document("res/values/strings.xml").use { strings ->
            if (strings.elements("string").none { it.getAttribute("name") == NEW_LABEL.removePrefix("@string/") }) {
                strings.documentElement.appendChild(strings.createElement("string").apply {
                    setAttribute("name", NEW_LABEL.removePrefix("@string/"))
                    textContent = APP_NAME
                })
            }
        }

        // microG Photos' icon: Photos' own pinwheel, with microG's C as a badge.
        val res = this["res"]
        File(res, "drawable").mkdirs()
        File(res, "drawable/ungoogled_microg_badge.xml").writeText(microgBadge())
        File(res, "drawable/ungoogled_microg_foreground.xml").writeText(
            """
            <?xml version="1.0" encoding="utf-8"?>
            <layer-list xmlns:android="http://schemas.android.com/apk/res/android">
                <item android:drawable="@mipmap/adaptiveproduct_photos_2025_foreground_color_108" />
                <item android:drawable="@drawable/ungoogled_microg_badge" />
            </layer-list>
            """.trimIndent() + "\n",
        )
        File(res, "mipmap-anydpi").mkdirs()
        File(res, "mipmap-anydpi/$ICON.xml").writeText(
            """
            <?xml version="1.0" encoding="utf-8"?>
            <adaptive-icon xmlns:android="http://schemas.android.com/apk/res/android">
                <background android:drawable="@color/google_white" />
                <foreground android:drawable="@drawable/ungoogled_microg_foreground" />
                <monochrome android:drawable="@drawable/photos_launchericon_release_monochrome" />
            </adaptive-icon>
            """.trimIndent() + "\n",
        )

        // Measured against this Photos version.
        val expected = mapOf(
            "own permission" to 3, "Play services permission" to 3, "task affinity" to 3, "push action" to 1,
            "label" to 20, "icon" to 7,
        )
        val short = expected.filter { (k, v) -> (counts[k] ?: 0) < v }
        if (short.isNotEmpty()) throw PatchException("microG Photos found less than expected: " + short.keys.joinToString { "$it ${counts[it] ?: 0}/${expected[it]}" })
    }
}

/** Play services' own checks in Photos: is this package signed by Google, and is Play services there. */
private const val SIGNATURE_HISTORY = "Unable to obtain package certificate history."
private const val GMS_VERSION_KEY = "com.google.android.gms.version"

@Suppress("unused")
val photosMicrogSupportPatch = bytecodePatch(
    name = "Add microG-RE support",
    description = "Builds microG Photos, a separate app that signs in to your Google account through microG-RE. Not for root installs.",
    default = false,
) {
    compatibleWith(COMPATIBILITY_PHOTOS)
    category("microG-RE")
    // A mount install keeps stock Photos' manifest, so microG never learns whom to vouch for.
    availability(AvailabilityResolver { installer, _ ->
        if (installer == InstallerType.MOUNT) PatchAvailability.UNAVAILABLE else PatchAvailability.DISABLED
    })
    dependsOn(photosMicrogResourcesPatch)

    execute {
        // Play services' client library in Photos checks that Play services is installed and signed by Google
        // before it binds; microG is neither, so both checks report success, as in microG Maps and Gmail.
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
    }

    // Every Play services name and every name of Photos' own in Photos' code: in finalize, after every other
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
        val pushActions = mutableSetOf<String>()
        for (type in holders) {
            for (method in mutableClassDefBy(type).methods) {
                val instructions = method.implementation?.instructions?.toList() ?: continue
                instructions.forEachIndexed { index, insn ->
                    val value = insn.stringOrNull() ?: return@forEachIndexed
                    val to = rename(value) ?: return@forEachIndexed
                    val opcode = if (insn.opcode == Opcode.CONST_STRING) "const-string" else "const-string/jumbo"
                    method.replaceInstruction(index, "$opcode v${(insn as OneRegisterInstruction).registerA}, \"$to\"")
                    val kind = if (playServicesName(value) != null) "Play services" else "Photos"
                    rewritten[kind] = (rewritten[kind] ?: 0) + 1
                    if (value in MicrogRe.PUSH_ACTION_ROUTES) pushActions += value
                }
            }
        }
        // Measured on this Photos version.
        if ((rewritten["Play services"] ?: 0) < 50 || (rewritten["Photos"] ?: 0) < 50) {
            throw PatchException("rewrote only $rewritten names; Photos changed shape")
        }
        val missing = PUSH_ACTIONS_USED - pushActions
        if (missing.isNotEmpty()) throw PatchException("Photos' FCM client no longer uses $missing; push not routed to microG-RE")
    }
}
