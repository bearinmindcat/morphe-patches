package org.ungoogled.patches.colorosgallery.misc

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.extensions.InstructionExtensions.addInstructionsWithLabels
import app.morphe.patcher.extensions.InstructionExtensions.getInstruction
import app.morphe.patcher.extensions.InstructionExtensions.replaceInstruction
import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.patch.resourcePatch
import app.morphe.patcher.util.smali.ExternalLabel
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.iface.reference.StringReference
import org.ungoogled.patches.coloros.exportDynamicReceivers
import org.ungoogled.patches.coloros.treatRefusedComponentsAsMissing
import org.ungoogled.patches.colorosgallery.ColorOsGalleryConstants.COMPATIBILITY_COLOROS_GALLERY
import org.ungoogled.patches.samsung.makeLibrariesOptional
import org.ungoogled.patches.shared.supportedVersionCheck
import org.w3c.dom.Element

internal const val COLOROS_GALLERY_EXTENSION = "extensions/colorosgallery.mpe"

private const val RUNTIME = "Lorg/ungoogled/colorosgallery/ColorOS;"
private const val PHOTOS = "Lorg/ungoogled/colorosgallery/Photos;"

private const val ALL_FILES_ACCESS = "android.permission.MANAGE_EXTERNAL_STORAGE"

/**
 * Photos requires libOpenCL.so, which most phones' vendor partitions have and others (the emulator) do not. 15.33.40
 * also does not declare All files access, which its system-app install on ColorOS does not need: without it Android's
 * Settings cannot grant it (the switch is greyed out) and Photos could not delete, edit or move photos.
 */
private val manifestPatch = resourcePatch {
    execute {
        if (makeLibrariesOptional() == 0) throw PatchException("no required library in the manifest")
        document("AndroidManifest.xml").use { manifest ->
            val root = manifest.documentElement
            val declared = (0 until root.childNodes.length).mapNotNull { root.childNodes.item(it) as? Element }
                .any { it.tagName == "uses-permission" && it.getAttribute("android:name") == ALL_FILES_ACCESS }
            if (declared) return@use
            val application = manifest.getElementsByTagName("application").item(0)
                ?: throw PatchException("no <application> in the manifest")
            root.insertBefore(
                manifest.createElement("uses-permission").apply { setAttribute("android:name", ALL_FILES_ACCESS) },
                application,
            )
        }
    }
}

/** ExportPermissionManager's "show the welcome statement" step (a Pair of shown/blocking flags). */
private object ExportStatementFingerprint : Fingerprint(
    returnType = "Lkotlin/Pair;",
    parameters = listOf(),
    strings = listOf("export_privacy_policy_alert_should_show"),
    custom = { _, classDef -> classDef.sourceFile == "ExportPermissionManager.kt" },
)

/** RuntimePermissionUtils' cached "has All files access" check. */
private object AllFilesAccessFingerprint : Fingerprint(
    returnType = "Z",
    parameters = listOf(),
    strings = listOf("initManageExternalStorageStatus"),
    custom = { method, _ -> AccessFlags.STATIC.isSet(method.accessFlags) },
)

/** Index of the first static call in [method] matching [match]. */
private fun BytecodePatchContext.firstStaticCall(method: Method, match: (MethodReference) -> Boolean): Int =
    method.implementation!!.instructions.indexOfFirst {
        it.opcode == Opcode.INVOKE_STATIC && ((it as ReferenceInstruction).reference as? MethodReference)?.let(match) == true
    }

/**
 * Off ColorOS Photos shows no privacy statement and contacts OPPO's servers at once (cloud sync status at
 * cloudkit-api-gl.heytapmobile.com, feature switches at fourier-videoclip-*.allawnos.com): its export build shows its
 * own welcome statement only from ColorOS 14 on, earlier ColorOS having covered it in the phone's setup, and takes it as
 * agreed below that -- also with no ColorOS at all. The statement is now shown ("Agree and continue" / "Disagree", which
 * keeps Photos to its basic, local functions).
 * Photos also assumed All files access outside China (a system app there has it), so deleting, editing or moving a
 * photo silently changed nothing; it now checks, as its China build does, and asks for it with its own dialog.
 */
private val statementPatch = bytecodePatch(
    description = "Shows Photos' own privacy statement and All files access request.",
) {
    extendWith(COLOROS_GALLERY_EXTENSION)

    execute {
        ExportStatementFingerprint.method.apply {
            val index = firstStaticCall(this) { r ->
                r.parameterTypes.map(CharSequence::toString) == listOf("I") && r.returnType == "Z" &&
                    classDefByOrNull(r.definingClass)?.sourceFile == "OSVersionUtils.kt"
            }
            if (index < 0) throw PatchException("no ColorOS version check in ExportPermissionManager")
            val register = (implementation!!.instructions[index] as FiveRegisterInstruction).registerC
            replaceInstruction(index, "invoke-static {v$register}, $PHOTOS->showStatement(I)Z")
        }
        AllFilesAccessFingerprint.method.apply {
            val index = firstStaticCall(this) { r -> r.parameterTypes.isEmpty() && r.returnType == "Z" }
            if (index < 0) throw PatchException("no feature check in the All files access status")
            replaceInstruction(index, "invoke-static {}, $PHOTOS->checkAllFilesAccess()Z")
        }
    }
}

private const val AGREEMENT_KEY = "is_agree_user_agreement"

/** NetworkPermissionManager's "may Photos use the network" setting (use_open_network). */
private object OpenNetworkFingerprint : Fingerprint(
    returnType = "Z",
    parameters = listOf(),
    strings = listOf("use_open_network"),
    custom = { method, classDef ->
        AccessFlags.STATIC.isSet(method.accessFlags) && classDef.sourceFile == "NetworkPermissionManager.kt"
    },
)

/** OCloudSyncDM's start-up check of the OPPO Cloud state (judgeOCloudState). */
private object CloudStateFingerprint : Fingerprint(
    definingClass = "Lcom/oplus/gallery/framework/abilities/ocloudsync/OCloudSyncDM;",
    returnType = "V",
    parameters = listOf(),
    custom = { method, _ ->
        method.implementation?.instructions?.any {
            ((it as? ReferenceInstruction)?.reference as? MethodReference)?.name == "judgeOCloudState"
        } == true
    },
)

/**
 * The statement's "Disagree" keeps Photos to its basic, local functions (it stores is_agree_user_agreement = false), but
 * the export build turns its network setting on either way, and its start-up check of OPPO Cloud ignores both: it
 * still asked cloudkit-api-gl.heytapmobile.com for the cloud state and fourier-videoclip-*.allawnos.com for feature
 * switches. Both now also need "Agree and continue": the network setting (which the feature switches, downloads and
 * other server requests follow) and the cloud check read the stored answer first.
 */
private val agreementPatch = bytecodePatch(
    description = "Keeps Photos off OPPO's servers unless its statement was agreed to.",
) {
    execute {
        // ConfigAbilityWrapper's boolean getter, as Photos itself reads the answer: (group 4, key, default) -> Z in
        // 16.45.2, (key, default, fallback, default-argument mask 4) -> Z in 15.33.40.
        val shapes = mapOf(
            listOf("I", "Ljava/lang/String;", "Z") to
                listOf("const/4 v0, 0x4", "const-string v1, \"$AGREEMENT_KEY\"", "const/4 v2, 0x0"),
            listOf("Ljava/lang/String;", "Z", "Z", "I") to
                listOf("const-string v0, \"$AGREEMENT_KEY\"", "const/4 v1, 0x0", "const/4 v2, 0x0", "const/4 v3, 0x4"),
        )
        var getter: MethodReference? = null
        classDefForEach { c ->
            if (getter != null || !c.type.startsWith("Lcom/oplus/")) return@classDefForEach
            for (m in c.methods) {
                val insns = m.implementation?.instructions?.toList() ?: continue
                for (i in insns.indices) {
                    if (((insns[i] as? ReferenceInstruction)?.reference as? StringReference)?.string != AGREEMENT_KEY) continue
                    val call = insns.drop(i + 1).take(3).firstOrNull { it.opcode == Opcode.INVOKE_STATIC } ?: continue
                    val r = (call as ReferenceInstruction).reference as MethodReference
                    if (r.parameterTypes.map(CharSequence::toString) in shapes && r.returnType == "Z") {
                        getter = r; return@classDefForEach
                    }
                }
            }
        }
        val read = getter ?: throw PatchException("no reader of $AGREEMENT_KEY")
        val parameters = read.parameterTypes.map(CharSequence::toString)
        val setup = shapes.getValue(parameters)
        val call = "${read.definingClass}->${read.name}(${parameters.joinToString("")})Z"
        val arguments = setup.indices.joinToString(", ") { "v$it" }

        OpenNetworkFingerprint.method.apply {
            val locals = implementation!!.registerCount
            if (locals < setup.size) throw PatchException("NetworkPermissionManager: only $locals registers")
            addInstructionsWithLabels(
                0,
                """
                    ${setup.joinToString("\n")}
                    invoke-static {$arguments}, $call
                    move-result v0
                    if-nez v0, :agreed
                    const/4 v0, 0x0
                    return v0
                """,
                ExternalLabel("agreed", getInstruction(0)),
            )
        }
        // The cloud check asks the (now agreement-aware) network setting first. 15.33.40 has no such check at start
        // (nothing in it calls judgeOCloudState).
        if (packageMetadata.versionName == "15.33.40") return@execute
        val network = OpenNetworkFingerprint.method.let { "${it.definingClass}->${it.name}()Z" }
        CloudStateFingerprint.method.apply {
            addInstructionsWithLabels(
                0,
                """
                    invoke-static {}, $network
                    move-result v0
                    if-nez v0, :agreed
                    return-void
                """,
                ExternalLabel("agreed", getInstruction(0)),
            )
        }
    }
}

private const val ICON_HELPER = "Lcom/oplus/resolver/OplusGalleryLoadIconHelper;"

/** Photos' own copy of ColorOS's share-sheet icon helper: "No OplusGalleryLoadIconHelper in system", null. */
private object IconHelperInstanceFingerprint : Fingerprint(
    definingClass = ICON_HELPER,
    name = "getInstance",
    returnType = ICON_HELPER,
    parameters = listOf("Landroid/content/Context;"),
)

/** Its share-sheet item for an app (null in Photos' copy). */
private object IconHelperItemFingerprint : Fingerprint(
    definingClass = ICON_HELPER,
    name = "getOplusItem",
    returnType = "Lcom/oplus/widget/OplusItem;",
    parameters = listOf("Landroid/content/Intent;", "Landroid/content/pm/ResolveInfo;", "Landroid/content/pm/PackageManager;"),
)

/**
 * Photos' share sheet lists the apps that can receive a photo, but their icons and taps come from ColorOS's
 * OplusGalleryLoadIconHelper; Photos carries a copy for phones without it that answers null, so off ColorOS the apps
 * showed as bare labels that did nothing. That copy now hands out an item with the app's icon and label from Android's
 * ResolveInfo (on ColorOS the system's own class loads first, so this never runs there).
 */
private val shareSheetPatch = bytecodePatch(
    description = "Shows app icons in Photos' share sheet and makes them work.",
) {
    extendWith(COLOROS_GALLERY_EXTENSION)

    execute {
        IconHelperInstanceFingerprint.method.addInstructions(
            0,
            """
                new-instance v0, $ICON_HELPER
                invoke-direct {v0, p0}, $ICON_HELPER-><init>(Landroid/content/Context;)V
                return-object v0
            """,
        )
        IconHelperItemFingerprint.method.addInstructions(
            0,
            """
                invoke-static {p1, p2, p3}, $PHOTOS->resolverItem(Landroid/content/Intent;Landroid/content/pm/ResolveInfo;Landroid/content/pm/PackageManager;)Ljava/lang/Object;
                move-result-object v0
                check-cast v0, Lcom/oplus/widget/OplusItem;
                return-object v0
            """,
        )
    }
}

/** Another version of the app or too old an Android stops here with a clear message (see supportedVersionCheck). */
private val versionCheckPatch = supportedVersionCheck(COMPATIBILITY_COLOROS_GALLERY)

/** registerReceiver calls without an export flag, measured per version. */
private val RECEIVER_CALLS = mapOf("16.45.2" to 60, "15.33.40" to 68)

/**
 * Off ColorOS Photos cannot even be installed (it requires libOpenCL.so) and then dies at start on ColorOS framework
 * classes and on broadcast receivers registered without an export flag. The extension carries stand-ins for the ColorOS
 * classes it uses (generated by .github/scripts/coloros-standins); on an OPPO / OnePlus / realme phone the framework's
 * own classes load first, so they never run there.
 */
@Suppress("unused")
val enableNonColorOsPatch = bytecodePatch(
    name = "Enable non-ColorOS ROM support",
    description = "Makes OnePlus Photos installable and usable on non-ColorOS ROMs.",
    default = true,
) {
    compatibleWith(COMPATIBILITY_COLOROS_GALLERY)
    dependsOn(versionCheckPatch, manifestPatch, statementPatch, agreementPatch, shareSheetPatch)
    extendWith(COLOROS_GALLERY_EXTENSION)

    execute {
        val receivers = exportDynamicReceivers(RUNTIME)
        java.util.logging.Logger.getLogger("OnePlusPhotos").info("registerReceiver calls through ColorOS: $receivers")
        val expected = RECEIVER_CALLS.getValue(packageMetadata.versionName)
        if (receivers < expected) throw PatchException("only $receivers registerReceiver calls found (expected $expected)")

        // On OPPO, OnePlus and realme phones ColorOS's own apps are there but refuse it: a refusal reads as their absence.
        val (services, providers) = treatRefusedComponentsAsMissing(RUNTIME)
        java.util.logging.Logger.getLogger("OnePlusPhotos").info("Service calls: $services, provider calls: $providers through ColorOS")
    }
}
