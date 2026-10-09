package org.ungoogled.patches.maps.microg

import app.morphe.patcher.extensions.InstructionExtensions.addInstruction
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
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.instruction.NarrowLiteralInstruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.TwoRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.formats.Instruction35c
import com.android.tools.smali.dexlib2.iface.instruction.formats.Instruction3rc
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.iface.reference.StringReference
import com.android.tools.smali.dexlib2.iface.reference.TypeReference
import org.ungoogled.patches.maps.ui.activityContextHookPatch
import org.ungoogled.patches.maps.ui.applicationStartHookPatch
import org.ungoogled.patches.maps.ui.markPatched
import org.ungoogled.patches.maps.ui.sharedExtensionPatch
import org.ungoogled.patches.shared.Constants.COMPATIBILITY_MAPS
import org.w3c.dom.Element

/*
 * Signing in through microG. The approach follows the GmsCore support patches of
 * ReVanced / Morphe (YouTube) and fangkampanat/gmaps-patches (Google Maps, GPLv3):
 * microG installs under its own package and authorities, so every Play services
 * name Maps uses is pointed at microG's, and the manifest names the app microG
 * should vouch for -- stock Maps' package and signing certificate -- so the
 * account tokens it hands out are the ones Google issues to Maps.
 */

/** The package microG installs as: MicroG-RE and ReVanced GmsCore both use it. */
private const val MICROG_PACKAGE = "app.revanced.android.gms"
private const val STOCK_PACKAGE = "com.google.android.apps.maps"
/** Google Maps' signing certificate (SHA-1), which microG reports to Google for this app. */
private const val STOCK_CERT_SHA1 = "38918a453d07199354f8b19af05ec6562ced5788"
private const val VENDOR = "app.revanced"
private const val C2DM = "$VENDOR.android.c2dm"
private const val MICROG_CLASS = "Lorg/ungoogled/ui/MicroG;"
private const val LOCATION_ACTION = "com.google.android.location.internal.GoogleLocationManagerService.START"
/** Location sharing's Play services services, which MicroG-RE answers only under app.revanced names. */
private val SHARING_ACTIONS = setOf(
    "com.google.android.gms.locationsharingreporter.service.START",
    "com.google.android.gms.location.reporting.service.START",
)
/** Location sharing's settings screen in Play services, which MicroG-RE opens only under an app.revanced name. */
private const val LOCATION_SHARING_SETTINGS = "com.google.android.gms.location.settings.LOCATION_SHARING"
private const val LOCATION_SETTINGS_REQUEST = "Lcom/google/android/gms/location/LocationSettingsRequest;"
private const val NOTICE_ACKED_REQUEST = "Lcom/google/android/gms/locationsharingreporter/NoticeAckedUpdateRequest;"
/** Maps' own record that an account acknowledged Location sharing's notice. */
private const val NOTICE_ACKED_KEY = "centralized_location_sharing_centralized_sharing_notice_acked"
private const val CRONET_PROVIDER = "Lcom/google/android/gms/net/PlayServicesCronetProvider;"
private const val USE_LOCATION_FAILED = "Failed to get 'Use Location for Services' setting"
private const val MODULE_CLASS_FAILED = "Failed to instantiate module class: "
private const val COULD_NOT_BIND = "Could not bind to service."

/** Play services permissions microG declares under its own names. */
private val PERMISSION_ROUTES = mapOf(
    "com.google.android.c2dm.permission.RECEIVE" to "$C2DM.permission.RECEIVE",
    "com.google.android.c2dm.permission.SEND" to "$C2DM.permission.SEND",
    "com.google.android.providers.gsf.permission.READ_GSERVICES" to "$VENDOR.android.providers.gsf.permission.READ_GSERVICES",
    "com.google.android.gms.permission.CAR_SPEED" to "$MICROG_PACKAGE.permission.CAR_SPEED",
)

/** Whole strings in Maps' code: the account type, Play services' package, its providers and permissions. */
private val STRING_ROUTES = PERMISSION_ROUTES + mapOf(
    "com.google" to VENDOR,
    "subscribedfeeds" to "$VENDOR.subscribedfeeds",
    "com.google.android.gms" to MICROG_PACKAGE,
    "com.google.android.gms.auth.accounts" to "$MICROG_PACKAGE.auth.accounts",
    "com.google.android.gms.chimera" to "$MICROG_PACKAGE.chimera",
    "com.google.android.gms.fonts" to "$MICROG_PACKAGE.fonts",
    "com.google.android.gms.phenotype" to "$MICROG_PACKAGE.phenotype",
)

/** Content URIs, by prefix. */
private val URI_ROUTES = listOf(
    "content://com.google.android.gms.phenotype" to "content://$MICROG_PACKAGE.phenotype",
    "content://com.google.android.gsf.gservices" to "content://$VENDOR.android.gsf.gservices",
    "content://com.google.settings" to "content://$VENDOR.settings",
    "content://subscribedfeeds" to "content://$VENDOR.subscribedfeeds",
)

/** A const-string (or /jumbo) loading [value]. */
private fun com.android.tools.smali.dexlib2.iface.instruction.Instruction.isString(value: String) =
    (opcode == Opcode.CONST_STRING || opcode == Opcode.CONST_STRING_JUMBO) &&
        ((this as ReferenceInstruction).reference as StringReference).string == value

private fun route(value: String): String? {
    STRING_ROUTES[value]?.let { return it }
    for ((from, to) in URI_ROUTES) if (value.startsWith(from)) return to + value.substring(from.length)
    return null
}

private val microgManifestPatch = resourcePatch(
    description = "Points the manifest's Play services permissions at microG and tells microG which app to vouch for.",
) {
    execute {
        // Runs first as the microG patch's dependency, before any resource patch it replaces.
        MicrogSelection.select(this)

        document("AndroidManifest.xml").use { manifest ->
            fun all(tag: String) = manifest.getElementsByTagName(tag).let { nodes -> (0 until nodes.length).map { nodes.item(it) as Element } }

            // 1. The four Play services permissions: three this app holds, one guarding its push receiver.
            var routed = 0
            for (element in all("*")) {
                val attributes = if (element.tagName.startsWith("uses-permission")) listOf("android:name")
                else listOf("android:permission", "android:readPermission", "android:writePermission")
                for (attribute in attributes) {
                    val to = PERMISSION_ROUTES[element.getAttribute(attribute)] ?: continue
                    element.setAttribute(attribute, to)
                    routed++
                }
            }
            if (routed != PERMISSION_ROUTES.size) throw PatchException("expected 4 Play services permissions, found $routed")

            // 2. Android 11+ package visibility: Maps must be allowed to see microG.
            val root = manifest.documentElement
            val queries = all("queries").firstOrNull { it.parentNode == root }
                ?: manifest.createElement("queries").also { root.insertBefore(it, root.firstChild) }
            queries.appendChild(manifest.createElement("package").apply { setAttribute("android:name", MICROG_PACKAGE) })

            // 3. Whom microG vouches for: stock Maps' package and certificate.
            val application = all("application").single()
            fun meta(name: String, value: String) = application.appendChild(manifest.createElement("meta-data").apply {
                setAttribute("android:name", name)
                setAttribute("android:value", value)
            })
            meta("$MICROG_PACKAGE.SPOOFED_PACKAGE_NAME", STOCK_PACKAGE)
            meta("$MICROG_PACKAGE.SPOOFED_PACKAGE_SIGNATURE", STOCK_CERT_SHA1)
            meta("$VENDOR.MICROG_PACKAGE_NAME", MICROG_PACKAGE)
            meta("$MICROG_PACKAGE.MICROG_PACKAGE_NAME", MICROG_PACKAGE)
        }
    }
}

/**
 * Builds microG Maps instead of Ungoogled Maps. Its name sorts it ahead of every patch it
 * replaces, which Morphe runs in name order: those see the choice ([MicrogSelection]) and
 * leave the account in, and the package and app name default to microG Maps' own.
 */
@Suppress("unused")
val microgSupportPatch = bytecodePatch(
    name = "Add microG support",
    description = "Builds microG Maps, a separate app (org.ungoogled.android.apps.maps.microg) that signs in to your " +
        "Google account through microG: saved places and lists, Timeline, location sharing, contributions and push " +
        "messages. Remove sign-in prompts, Trim account menu and Remove permissions are left out of this build, " +
        "Offline saved places keeps only its Local saved screen, which copies your account's saved lists to the " +
        "phone (Pull from Google account), and its icon carries microG's C. Needs microG: MicroG-RE or ReVanced " +
        "GmsCore. Not for root (mount) installs.",
    default = false,
) {
    compatibleWith(COMPATIBILITY_MAPS)
    category("microG")
    // A mount install keeps stock Maps' manifest, so microG never learns whom to vouch for.
    availability(AvailabilityResolver { installer, _ ->
        if (installer == InstallerType.MOUNT) PatchAvailability.UNAVAILABLE else PatchAvailability.DISABLED
    })
    // Shapes.wrap starts the microG notice (missing, or keeping the account from Maps), the
    // location-source switch reads microgPatched(), and Shapes.processStart decides where Play
    // services modules come from before Maps loads any.
    dependsOn(sharedExtensionPatch, activityContextHookPatch, applicationStartHookPatch, microgManifestPatch)

    execute {
        MicrogSelection.select(this)
        markPatched("microgPatched")

        // 1. The location service. Maps asks for it through two getters that return the
        //    action alone; MicroG-RE answers it under a name of its own, so the action is
        //    picked at runtime from what the installed microG answers.
        val getters = mutableListOf<Pair<String, com.android.tools.smali.dexlib2.iface.Method>>()
        classDefForEach { classDef ->
            if (classDef.type.startsWith("Lorg/ungoogled/")) return@classDefForEach
            for (method in classDef.methods) {
                if (method.returnType != "Ljava/lang/String;" || method.parameterTypes.isNotEmpty()) continue
                val instructions = method.implementation?.instructions?.toList() ?: continue
                if (instructions.size != 2 || instructions[1].opcode != Opcode.RETURN_OBJECT) continue
                val string = ((instructions[0] as? ReferenceInstruction)?.reference as? StringReference)?.string
                if (string == LOCATION_ACTION) getters += classDef.type to method
            }
        }
        if (getters.size != 2) throw PatchException("expected two location service action getters, found ${getters.size}")
        for ((type, getter) in getters) {
            mutableClassDefBy(type).methods.single {
                it.name == getter.name && it.parameterTypes.isEmpty() && it.returnType == "Ljava/lang/String;"
            }.apply {
                val register = (implementation!!.instructions[0] as OneRegisterInstruction).registerA
                replaceInstruction(0, "invoke-static {}, $MICROG_CLASS->locationAction()Ljava/lang/String;")
                addInstruction(1, "move-result-object v$register")
            }
        }

        // 1b. Location sharing's reporter and location reporting (issue #30): their clients name
        //     the service the same way, and MicroG-RE answers both under app.revanced names only,
        //     so the action is picked at runtime too.
        val sharingGetters = mutableListOf<Triple<String, com.android.tools.smali.dexlib2.iface.Method, String>>()
        classDefForEach { classDef ->
            if (classDef.type.startsWith("Lorg/ungoogled/")) return@classDefForEach
            for (method in classDef.methods) {
                if (method.returnType != "Ljava/lang/String;" || method.parameterTypes.isNotEmpty()) continue
                val instructions = method.implementation?.instructions?.toList() ?: continue
                if (instructions.size != 2 || instructions[1].opcode != Opcode.RETURN_OBJECT) continue
                val string = ((instructions[0] as? ReferenceInstruction)?.reference as? StringReference)?.string
                if (string in SHARING_ACTIONS) sharingGetters += Triple(classDef.type, method, string!!)
            }
        }
        if (sharingGetters.map { it.third }.sorted() != SHARING_ACTIONS.sorted()) {
            throw PatchException("expected one getter for each Location sharing service, found ${sharingGetters.map { it.third }}")
        }
        for ((type, getter, _) in sharingGetters) {
            mutableClassDefBy(type).methods.single {
                it.name == getter.name && it.parameterTypes.isEmpty() && it.returnType == "Ljava/lang/String;"
            }.apply {
                val register = (implementation!!.instructions[0] as OneRegisterInstruction).registerA
                addInstructions(
                    1,
                    """
                        invoke-static { v$register }, $MICROG_CLASS->serviceAction(Ljava/lang/String;)Ljava/lang/String;
                        move-result-object v$register
                    """,
                )
            }
        }

        // 1c. "Turn on location" (issue #27): with Android's location off, my location has Play
        //     services resolve its location check, and MicroG-RE's dialog is under Google's action
        //     in its own package, which it does not answer: the ActivityNotFoundException crashed
        //     Maps. Every call to Status.startResolutionForResult(Activity, int) goes through
        //     MicroG.resolve, which opens Android's location settings instead. The location check
        //     itself -- the one class that builds a LocationSettingsRequest -- goes through
        //     MicroG.resolveLocation, which also answers it while location is on (issue #30).
        val status = "Lcom/google/android/gms/common/api/Status;"
        fun startsResolution(insn: com.android.tools.smali.dexlib2.iface.instruction.Instruction) =
            ((insn as? ReferenceInstruction)?.reference as? MethodReference)?.let { r ->
                r.definingClass == status && r.returnType == "V" &&
                    r.parameterTypes.map(CharSequence::toString) == listOf("Landroid/app/Activity;", "I")
            } == true
        val resolutionCallers = mutableListOf<Pair<String, com.android.tools.smali.dexlib2.iface.Method>>()
        val locationChecks = mutableSetOf<String>()
        classDefForEach { c ->
            if (c.type.startsWith("Lorg/ungoogled/")) return@classDefForEach
            for (m in c.methods) {
                val instructions = m.implementation?.instructions ?: continue
                if (instructions.any(::startsResolution)) resolutionCallers += c.type to m
                if (instructions.any { ((it as? ReferenceInstruction)?.reference as? MethodReference)?.let { r ->
                        r.definingClass == LOCATION_SETTINGS_REQUEST && r.name == "<init>" } == true }
                ) locationChecks += c.type
            }
        }
        if (resolutionCallers.isEmpty()) throw PatchException("Status.startResolutionForResult is no longer called")
        if (resolutionCallers.count { it.first in locationChecks } != 1) {
            throw PatchException("expected one location settings check to resolve, found ${resolutionCallers.filter { it.first in locationChecks }}")
        }
        val resolutionNames = mutableSetOf<String>()
        for ((type, found) in resolutionCallers) {
            val method = mutableClassDefBy(type).methods.single {
                it.name == found.name && it.parameterTypes == found.parameterTypes && it.returnType == found.returnType
            }
            val ins = method.implementation!!.instructions.toList()
            for (i in ins.indices.reversed()) {
                val insn = ins[i]
                if (!startsResolution(insn)) continue
                resolutionNames += ((insn as ReferenceInstruction).reference as MethodReference).name
                val handler = if (type in locationChecks) "resolveLocation" else "resolve"
                val resolve = "$MICROG_CLASS->$handler(Ljava/lang/Object;Landroid/app/Activity;I)V"
                when {
                    insn.opcode == Opcode.INVOKE_VIRTUAL && insn is Instruction35c ->
                        method.replaceInstruction(i, "invoke-static { v${insn.registerC}, v${insn.registerD}, v${insn.registerE} }, $resolve")
                    insn.opcode == Opcode.INVOKE_VIRTUAL_RANGE && insn is Instruction3rc ->
                        method.replaceInstruction(i, "invoke-static/range { v${insn.startRegister} .. v${insn.startRegister + 2} }, $resolve")
                    else -> throw PatchException("unexpected call to Status.startResolutionForResult in $type: ${insn.opcode}")
                }
            }
        }
        val resolutionName = resolutionNames.singleOrNull()
            ?: throw PatchException("Status.startResolutionForResult has more than one name: $resolutionNames")
        mutableClassDefBy(MICROG_CLASS).methods.single {
            it.name == "resolveMethod" && it.parameterTypes.isEmpty() && it.returnType == "Ljava/lang/String;"
        }.apply {
            val first = implementation!!.instructions.first()
            if (first.opcode != Opcode.CONST_STRING) throw PatchException("MicroG.resolveMethod() no longer starts with const-string")
            replaceInstruction(0, "const-string v${(first as OneRegisterInstruction).registerA}, \"$resolutionName\"")
        }

        // 1d. Location sharing's settings -- the gear on its screen, and Settings > Location
        //     sharing (issue #30): MicroG-RE opens that screen only under an app.revanced action,
        //     so the action is picked at runtime from what the installed microG answers.
        val settingsHolders = mutableListOf<String>()
        classDefForEach { c ->
            if (c.type.startsWith("Lorg/ungoogled/")) return@classDefForEach
            if (c.methods.any { m -> m.implementation?.instructions?.any { it.isString(LOCATION_SHARING_SETTINGS) } == true }) {
                settingsHolders += c.type
            }
        }
        var settingsActions = 0
        for (type in settingsHolders) {
            for (method in mutableClassDefBy(type).methods) {
                val instructions = method.implementation?.instructions?.toList() ?: continue
                for (index in instructions.indices.reversed()) {
                    if (!instructions[index].isString(LOCATION_SHARING_SETTINGS)) continue
                    val register = (instructions[index] as OneRegisterInstruction).registerA
                    method.addInstructions(
                        index + 1,
                        """
                            invoke-static/range { v$register .. v$register }, $MICROG_CLASS->activityAction(Ljava/lang/String;)Ljava/lang/String;
                            move-result-object v$register
                        """,
                    )
                    settingsActions++
                }
            }
        }
        if (settingsActions != 2) throw PatchException("expected Location sharing's settings action twice, found $settingsActions")

        // 1e. Location sharing's notice, "Updates to Google Location Sharing" (issue #30): Maps keeps
        //     its banner up until Play services reports the notice acknowledged, and MicroG-RE keeps
        //     no record of it, so the banner could not be dismissed. Maps' acknowledgement -- the one
        //     method sending (Account, NoticeAckedUpdateRequest) -- now records it in the extension,
        //     and Maps' own check for it reads that record too.
        val senders = mutableListOf<Pair<String, com.android.tools.smali.dexlib2.iface.Method>>()
        classDefForEach { c ->
            if (c.type.startsWith("Lorg/ungoogled/")) return@classDefForEach
            for (m in c.methods) {
                if (m.implementation != null &&
                    m.parameterTypes.map(CharSequence::toString) == listOf("Landroid/accounts/Account;", NOTICE_ACKED_REQUEST)
                ) senders += c.type to m
            }
        }
        val (senderType, sender) = senders.singleOrNull()
            ?: throw PatchException("expected one sender of Location sharing's notice acknowledgement, found ${senders.size}")
        mutableClassDefBy(senderType).methods.single {
            it.name == sender.name && it.parameterTypes == sender.parameterTypes && it.returnType == sender.returnType
        }.apply {
            if (AccessFlags.STATIC.isSet(accessFlags)) throw PatchException("notice acknowledgement sender is static")
            addInstructions(0, "invoke-static/range { p1 .. p1 }, $MICROG_CLASS->acceptNotice(Landroid/accounts/Account;)V")
        }
        // Maps' check: the one method reading the record's key, a static field set from the key's name.
        var keyField: String? = null
        classDefForEach { c ->
            if (keyField != null || c.type.startsWith("Lorg/ungoogled/")) return@classDefForEach
            val instructions = c.methods.firstOrNull { it.name == "<clinit>" }?.implementation?.instructions?.toList()
                ?: return@classDefForEach
            val at = instructions.indexOfFirst { it.isString(NOTICE_ACKED_KEY) }
            if (at < 0) return@classDefForEach
            val put = instructions.drop(at).firstOrNull { it.opcode == Opcode.SPUT_OBJECT } ?: return@classDefForEach
            keyField = ((put as ReferenceInstruction).reference as FieldReference).let { "${it.definingClass}->${it.name}:${it.type}" }
        }
        val noticeKey = keyField ?: throw PatchException("Location sharing's notice record ($NOTICE_ACKED_KEY) not found")
        val readers = mutableListOf<Pair<String, com.android.tools.smali.dexlib2.iface.Method>>()
        classDefForEach { c ->
            if (c.type.startsWith("Lorg/ungoogled/")) return@classDefForEach
            for (m in c.methods) {
                if (m.returnType != "Z" || m.parameterTypes.size != 1) continue
                val reads = m.implementation?.instructions?.any { insn ->
                    insn.opcode == Opcode.SGET_OBJECT &&
                        ((insn as ReferenceInstruction).reference as FieldReference).let { "${it.definingClass}->${it.name}:${it.type}" } == noticeKey
                } == true
                if (reads) readers += c.type to m
            }
        }
        val (readerType, reader) = readers.singleOrNull()
            ?: throw PatchException("expected one check of Location sharing's notice record, found ${readers.size}")
        mutableClassDefBy(readerType).methods.single {
            it.name == reader.name && it.parameterTypes == reader.parameterTypes && it.returnType == "Z"
        }.apply {
            // v0 must be a local: an instance method's this and one object parameter take the last two registers.
            val code = implementation!!
            if (AccessFlags.STATIC.isSet(accessFlags) || code.registerCount < 3) throw PatchException("notice check has no free register")
            addInstructionsWithLabels(
                0,
                """
                    invoke-static/range { p1 .. p1 }, $MICROG_CLASS->noticeAccepted(Ljava/lang/Object;)Z
                    move-result v0
                    if-eqz v0, :stock
                    return v0
                """,
                ExternalLabel("stock", code.instructions.first()),
            )
        }

        // 2. The Play services names in Maps' code: in finalize, below.

        // 3. Cronet, Maps' HTTP engine, comes from microG as a module. ReVanced GmsCore
        //    builds that module from Google's Play services APK whenever Google's is
        //    installed too -- one without the engine -- and Maps died at start. Play
        //    services' Cronet provider is now tried before Maps settles on it; a broken
        //    one sits out and Maps takes its next provider, the Java one it bundles.
        mutableClassDefBy(CRONET_PROVIDER).methods.single {
            it.name == "isEnabled" && it.parameterTypes.isEmpty() && it.returnType == "Z"
        }.apply {
            val instructions = implementation!!.instructions.toList()
            val move = instructions.size - 2
            val installed = instructions.getOrNull(move - 1)
            if (instructions.last().opcode != Opcode.RETURN || instructions[move].opcode != Opcode.MOVE_RESULT ||
                installed?.opcode != Opcode.INVOKE_STATIC ||
                ((installed as ReferenceInstruction).reference as MethodReference).let { it.parameterTypes.isNotEmpty() || it.returnType != "Z" }
            ) throw PatchException("PlayServicesCronetProvider.isEnabled changed shape")
            // .registers 2, no parameters: p0 is the last register, and it holds this
            // until the original move-result overwrites it; v0 is free by then.
            val self = implementation!!.registerCount - 1
            replaceInstruction(move, "move-result v0")
            addInstructions(
                move + 1,
                """
                    invoke-static { v$self, v0 }, $MICROG_CLASS->cronetUsable(Ljava/lang/Object;Z)Z
                    move-result v$self
                """,
            )
        }

        // 4. Whether location is on. Where Google's location settings screen exists, Maps
        //    first reads Google's "use location for services" switch from the Google
        //    settings provider -- microG's, here -- and takes anything but "1" as "disabled
        //    by security". ReVanced GmsCore answers nothing, so Maps stopped its location
        //    providers a second after starting them. That switch belongs to Google's own
        //    location service, so Maps now goes straight to Android's provider states, as
        //    on a phone without Google apps: the flag that chooses the check reads false.
        val checks = mutableListOf<Pair<String, String>>()
        classDefForEach { classDef ->
            if (classDef.type.startsWith("Lorg/ungoogled/")) return@classDefForEach
            for (method in classDef.methods) {
                val found = method.implementation?.instructions?.any {
                    it.opcode == Opcode.CONST_STRING && ((it as ReferenceInstruction).reference as StringReference).string == USE_LOCATION_FAILED
                } == true
                if (found) checks += classDef.type to method.name
            }
        }
        val (checkClass, checkName) = checks.singleOrNull()
            ?: throw PatchException("expected one 'Use Location for Services' check, found ${checks.size}")
        mutableClassDefBy(checkClass).methods.single { it.name == checkName && it.parameterTypes.isEmpty() }.apply {
            val read = implementation!!.instructions.first()
            val field = (read as? ReferenceInstruction)?.reference as? FieldReference
            if (read.opcode != Opcode.IGET_BOOLEAN || field?.definingClass != checkClass || field.type != "Z")
                throw PatchException("the 'Use Location for Services' check no longer starts with its flag")
            val register = (read as TwoRegisterInstruction).registerA
            if (register > 15) throw PatchException("flag register v$register out of const/4 range")
            replaceInstruction(0, "const/4 v$register, 0x0")
        }

        // 5. Play services modules (code Maps loads from Play services: certificate checks,
        //    Cronet, the security provider). ReVanced GmsCore builds each from Google's Play
        //    services APK whenever Google's is installed too, without the context Google's own
        //    loader sets up, and Play services 26.36's GoogleCertificatesImpl throws "Missing
        //    DynamiteApplicationContext" from its constructor -- an exception Maps' module code
        //    does not expect, so Maps crashed at start. Instantiating a module's class now goes
        //    through the extension, which turns any failure into Maps' own "module unavailable"
        //    exception; every caller already carries on without the module then.
        val instantiators = mutableListOf<Pair<String, String>>()
        classDefForEach { classDef ->
            if (classDef.type.startsWith("Lorg/ungoogled/")) return@classDefForEach
            for (method in classDef.methods) {
                if (method.returnType != "Landroid/os/IBinder;" || method.parameterTypes.map { it.toString() } != listOf("Ljava/lang/String;")) continue
                val found = method.implementation?.instructions?.any {
                    it.opcode == Opcode.CONST_STRING && ((it as ReferenceInstruction).reference as StringReference).string == MODULE_CLASS_FAILED
                } == true
                if (found) instantiators += classDef.type to method.name
            }
        }
        val (moduleClass, instantiate) = instantiators.singleOrNull()
            ?: throw PatchException("expected one module class instantiation, found ${instantiators.size}")
        mutableClassDefBy(moduleClass).methods.single {
            it.name == instantiate && it.returnType == "Landroid/os/IBinder;" && it.parameterTypes.map { p -> p.toString() } == listOf("Ljava/lang/String;")
        }.apply {
            val instructions = implementation!!.instructions.toList()
            val context = instructions.firstNotNullOfOrNull { insn ->
                ((insn as? ReferenceInstruction)?.reference as? FieldReference)
                    ?.takeIf { insn.opcode == Opcode.IGET_OBJECT && it.type == "Landroid/content/Context;" }
            } ?: throw PatchException("module class instantiation no longer reads its Context")
            val failure = instructions.firstNotNullOfOrNull { insn ->
                ((insn as? ReferenceInstruction)?.reference as? TypeReference)?.takeIf { insn.opcode == Opcode.NEW_INSTANCE }?.type
            } ?: throw PatchException("module class instantiation no longer throws an exception of its own")
            // .registers 3: v0, then p0 (this) and p1 (the class name); p0 is reused once read.
            if (implementation!!.registerCount != 3) throw PatchException("module class instantiation changed its registers")
            addInstructions(
                0,
                """
                    iget-object p0, p0, ${context.definingClass}->${context.name}:Landroid/content/Context;
                    invoke-virtual { p0 }, Landroid/content/Context;->getClassLoader()Ljava/lang/ClassLoader;
                    move-result-object p0
                    const-class v0, $failure
                    invoke-static { p0, p1, v0 }, $MICROG_CLASS->moduleObject(Ljava/lang/ClassLoader;Ljava/lang/String;Ljava/lang/Class;)Landroid/os/IBinder;
                    move-result-object p0
                    return-object p0
                """,
            )
        }

        // 6. Where those modules come from: Google's own Play services whenever it is installed
        //    -- its loader is the one its modules work with, as in Ungoogled Maps -- and microG
        //    only on phones without it (MicroG.modulesPackage). The loader names Play services
        //    twice (to load Google's module loader, and as the module provider's owner) and the
        //    provider twice; each name becomes a call, which also keeps it out of the rewrite to
        //    microG's names in finalize below.
        val calls = mapOf(
            "com.google.android.gms" to "modulesPackage",
            "com.google.android.gms.chimera" to "modulesAuthority",
        )
        val replaced = mutableMapOf<String, Int>()
        for (method in mutableClassDefBy(moduleClass).methods) {
            val instructions = method.implementation?.instructions?.toList() ?: continue
            for (index in instructions.indices.reversed()) {
                val insn = instructions[index]
                if (insn.opcode != Opcode.CONST_STRING) continue
                val string = ((insn as ReferenceInstruction).reference as StringReference).string
                val call = calls[string] ?: continue
                val register = (insn as OneRegisterInstruction).registerA
                method.replaceInstruction(index, "invoke-static { }, $MICROG_CLASS->$call()Ljava/lang/String;")
                method.addInstruction(index + 1, "move-result-object v$register")
                replaced[string] = (replaced[string] ?: 0) + 1
            }
        }
        if (calls.keys.any { replaced[it] != 2 }) throw PatchException("module loader names Play services differently now: $replaced")

        // 7. The Google-auth helper's connection to the token service (account ids, tokens).
        //    It binds with no executor, so the connection arrives on the main thread, and then
        //    waits for it with no time limit. On its first start Maps waits for each Google
        //    account's id on that same main thread (the "Make it your map" page): with an
        //    account in microG -- ReVanced GmsCore turns Maps' account lookup away, so Maps
        //    asks the token service instead -- Maps froze on its splash screen. The helper now
        //    connects on a background executor and gives up after 15 s.
        val helpers = mutableListOf<Pair<String, String>>()
        classDefForEach { classDef ->
            if (classDef.type.startsWith("Lorg/ungoogled/")) return@classDefForEach
            for (method in classDef.methods) {
                if (method.parameterTypes.size != 3 || method.parameterTypes[1].toString() != "Landroid/content/ComponentName;") continue
                val found = method.implementation?.instructions?.any {
                    it.opcode == Opcode.CONST_STRING && ((it as ReferenceInstruction).reference as StringReference).string == COULD_NOT_BIND
                } == true
                if (found) helpers += classDef.type to method.name
            }
        }
        val (helperClass, helperName) = helpers.singleOrNull()
            ?: throw PatchException("expected one token service connection, found ${helpers.size}")
        mutableClassDefBy(helperClass).methods.single {
            it.name == helperName && it.parameterTypes.size == 3 && it.parameterTypes[1].toString() == "Landroid/content/ComponentName;"
        }.apply {
            val instructions = implementation!!.instructions.toList()
            // The bind: supervisor.f(descriptor, connection, executor), the executor a null constant.
            val bind = instructions.indexOfFirst { insn ->
                ((insn as? ReferenceInstruction)?.reference as? MethodReference)?.let {
                    it.parameterTypes.size == 3 &&
                        it.parameterTypes[1].toString() == "Landroid/content/ServiceConnection;" &&
                        it.parameterTypes[2].toString() == "Ljava/util/concurrent/Executor;" &&
                        it.returnType == "Lcom/google/android/gms/common/ConnectionResult;"
                } == true
            }
            if (bind < 1) throw PatchException("token service bind not found")
            val executor = (instructions[bind] as Instruction35c).registerF
            val none = instructions[bind - 1]
            if (none.opcode != Opcode.CONST_4 || (none as OneRegisterInstruction).registerA != executor ||
                (none as NarrowLiteralInstruction).narrowLiteral != 0
            ) throw PatchException("token service bind no longer passes a null executor")
            // The wait: BlockingQueue.take() on the connection's queue.
            val take = instructions.indexOfFirst { insn ->
                ((insn as? ReferenceInstruction)?.reference as? MethodReference)?.let {
                    it.definingClass == "Ljava/util/concurrent/BlockingQueue;" && it.name == "take"
                } == true
            }
            if (take < 0) throw PatchException("token service wait not found")
            val queue = (instructions[take] as Instruction35c).registerC
            // Replace the wait first: it comes after the bind, so the bind's index stays put.
            replaceInstruction(take, "invoke-static { v$queue }, $MICROG_CLASS->takeService(Ljava/util/concurrent/BlockingQueue;)Ljava/lang/Object;")
            replaceInstruction(bind - 1, "invoke-static { }, $MICROG_CLASS->bindExecutor()Ljava/util/concurrent/Executor;")
            addInstruction(bind, "move-result-object v$executor")
        }
    }

    // 2. Every Play services name in Maps' code: the account type, the package, its
    //    providers, permissions and content URIs. In finalize, after every other patch
    //    has run, so code they add is covered too. Not in the extension, which asks
    //    for real Play services and microG by name on purpose.
    finalize {
        val holders = mutableListOf<String>()
        classDefForEach { classDef ->
            if (classDef.type.startsWith("Lorg/ungoogled/")) return@classDefForEach
            val routes = classDef.methods.any { method ->
                method.implementation?.instructions?.any { insn ->
                    (insn.opcode == Opcode.CONST_STRING || insn.opcode == Opcode.CONST_STRING_JUMBO) &&
                        route(((insn as ReferenceInstruction).reference as StringReference).string) != null
                } == true
            }
            if (routes) holders += classDef.type
        }
        var rewritten = 0
        for (type in holders) {
            for (method in mutableClassDefBy(type).methods) {
                val instructions = method.implementation?.instructions?.toList() ?: continue
                instructions.forEachIndexed { index, insn ->
                    if (insn.opcode != Opcode.CONST_STRING && insn.opcode != Opcode.CONST_STRING_JUMBO) return@forEachIndexed
                    val to = route(((insn as ReferenceInstruction).reference as StringReference).string) ?: return@forEachIndexed
                    val register = (insn as OneRegisterInstruction).registerA
                    val opcode = if (insn.opcode == Opcode.CONST_STRING) "const-string" else "const-string/jumbo"
                    method.replaceInstruction(index, "$opcode v$register, \"$to\"")
                    rewritten++
                }
            }
        }
        // Measured on this Maps version: 90 package, 64 account type, and the rest.
        if (rewritten < 160) throw PatchException("rewrote only $rewritten Play services names; Maps changed shape")
    }
}
