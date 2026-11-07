package org.ungoogled.patches.samsungmyfiles.misc

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.extensions.InstructionExtensions.addInstructionsWithLabels
import app.morphe.patcher.extensions.InstructionExtensions.replaceInstruction
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.patch.resourcePatch
import app.morphe.patcher.util.smali.ExternalLabel
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import org.ungoogled.patches.samsung.redirectCalls
import org.ungoogled.patches.samsung.redirectOneUiMembers
import org.ungoogled.patches.samsung.redirectSystemServices
import org.ungoogled.patches.samsung.rewriteStringLiterals
import org.ungoogled.patches.samsungmyfiles.SamsungMyFilesConstants.COMPATIBILITY_SAMSUNG_MY_FILES
import org.w3c.dom.Element

internal const val SAMSUNG_MY_FILES_EXTENSION = "extensions/samsungmyfiles.mpe"

/**
 * The Trash goes through One UI's "sectrash" provider, which nothing else has: the extension carries a stand-in (see
 * TrashProvider), registered here under Samsung's authority so My Files' own calls reach it unchanged.
 */
private val trashProviderManifestPatch = resourcePatch {
    execute {
        document("AndroidManifest.xml").use { manifest ->
            val providers = manifest.getElementsByTagName("provider")
            for (i in 0 until providers.length) {
                if ((providers.item(i) as Element).getAttribute("android:authorities").split(";").contains("sectrash")) {
                    throw PatchException("the manifest already has a sectrash provider")
                }
            }
            val application = manifest.getElementsByTagName("application").item(0) as Element
            application.appendChild(
                manifest.createElement("provider").apply {
                    setAttribute("android:name", "org.ungoogled.samsungmyfiles.TrashProvider")
                    setAttribute("android:authorities", "sectrash")
                    setAttribute("android:exported", "false")
                },
            )
        }
    }
}

/** Sends My Files' uses of One UI's additions to Android classes through OneUi (see redirectOneUiMembers). */
private val oneUiMembersPatch = bytecodePatch(
    description = "Answers One UI's additions to Android classes where the phone has none.",
) {
    extendWith(SAMSUNG_MY_FILES_EXTENSION)

    execute {
        val (calls, fields) = redirectOneUiMembers("Lorg/ungoogled/samsungmyfiles/OneUi;", "Lorg/ungoogled/samsungmyfiles/Sem;")
        java.util.logging.Logger.getLogger("SamsungMyFiles").info("One UI members: $calls calls, $fields fields")
        // Measured against 15.2.03.2: 70 calls and 6 field accesses.
        if (calls < 30) throw PatchException("only $calls One UI calls found")
    }
}

/**
 * After an update (and at other times) My Files registers with One UI's event delegation service, cast from
 * getSystemService("semeventdelegator") without a check: off One UI that is null and the update receiver crashed.
 */
private val systemServicesPatch = bytecodePatch(
    description = "Answers One UI's system services where the phone has none.",
) {
    extendWith(SAMSUNG_MY_FILES_EXTENSION)

    execute {
        val moved = redirectSystemServices("Lorg/ungoogled/samsungmyfiles/Services;")
        // Measured against 15.2.03.2: 173 calls.
        if (moved == 0) throw PatchException("no getSystemService calls found")
    }
}

/**
 * My Files sorts names with UNICODE_NATURAL, a collation only One UI's SQLite has; elsewhere every such query fails
 * ("no such collation sequence") and folders and categories fall back to unsorted, uncached listings. NOCASE is
 * SQLite's own.
 */
private val collationPatch = bytecodePatch(
    description = "Sorts names with SQLite's own collation.",
) {
    execute {
        val rewritten = rewriteStringLiterals { s ->
            if (s.contains("COLLATE UNICODE_NATURAL")) s.replace("COLLATE UNICODE_NATURAL", "COLLATE NOCASE") else null
        }
        // Measured against 15.2.03.2: 6 literals.
        if (rewritten == 0) throw PatchException("no UNICODE_NATURAL collation found")
    }
}

/**
 * Manage storage (and the "storage full" error's button) open One UI Settings' storage analysis by an action only
 * Samsung's Settings handles: off One UI the tap crashed My Files (ActivityNotFoundException). Android's own Storage
 * settings are the same page on other ROMs.
 */
private val manageStoragePatch = bytecodePatch(
    description = "Opens Android's storage settings from Manage storage.",
) {
    execute {
        val rewritten = rewriteStringLiterals { s ->
            if (s == "com.sec.android.app.myfiles.RUN_STORAGE_ANALYSIS") "android.settings.INTERNAL_STORAGE_SETTINGS" else null
        }
        // Measured against 15.2.03.2: 4 literals (home item, storage-full dialog, shortcut, notification).
        if (rewritten == 0) throw PatchException("no storage analysis action found")
    }
}

/** StorageVolumeUtils.getVolumes: StorageManager.getVolumes() by reflection, the list of SD cards and USB drives. */
private object VolumeListFingerprint : Fingerprint(
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.STATIC),
    returnType = "Ljava/util/List;",
    parameters = listOf("Landroid/os/storage/StorageManager;"),
    strings = listOf("getVolumes"),
)

/** EnvManager.isSupportSdCard: One UI's "this phone has an SD card slot" property, Knox and profile checks. */
private object SdCardSupportFingerprint : Fingerprint(
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.STATIC, AccessFlags.FINAL),
    returnType = "Z",
    parameters = listOf("Landroid/content/Context;"),
    strings = listOf("isSupportSdCard()] SUPPORT_SD_CARD by System : "),
)

/** EnvManager's start-up, which keeps isSupportSdCard's answer in a static flag. */
private object EnvManagerInitFingerprint : Fingerprint(
    returnType = "V",
    parameters = listOf(),
    strings = listOf("EnvManager init"),
)

private const val VOLUMES = "Lorg/ungoogled/samsungmyfiles/Volumes;"
private const val MENU_ID_TYPE = "Lcom/sec/android/app/myfiles/ui/menu/MenuIdType;"

/**
 * My Files reads SD cards and USB drives from Android's hidden VolumeInfo by reflection, including a field Android
 * refuses to apps, so the list came back empty: no SD card or USB drive showed. The list now comes from Android's
 * public storage volumes (see Volumes), and the broadcast One UI announces cards and drives with, which only system
 * apps receive, is fed from Android's public media broadcasts.
 */
private val storageVolumesPatch = bytecodePatch(
    description = "Shows SD cards and USB drives.",
) {
    extendWith(SAMSUNG_MY_FILES_EXTENSION)

    execute {
        val list = VolumeListFingerprint.methodOrNull ?: throw PatchException("the storage volume list was not found")
        list.addInstructions(
            0,
            """
                invoke-static { p0 }, $VOLUMES->list(Landroid/os/storage/StorageManager;)Ljava/util/List;
                move-result-object p0
                return-object p0
            """,
        )
        val moved = redirectCalls(
            mapOf(
                "Landroid/content/Context;->registerReceiver(Landroid/content/BroadcastReceiver;Landroid/content/IntentFilter;I)Landroid/content/Intent;" to
                    "$VOLUMES->registerReceiver(Landroid/content/Context;Landroid/content/BroadcastReceiver;Landroid/content/IntentFilter;I)Landroid/content/Intent;",
                "Landroid/content/Context;->unregisterReceiver(Landroid/content/BroadcastReceiver;)V" to
                    "$VOLUMES->unregisterReceiver(Landroid/content/Context;Landroid/content/BroadcastReceiver;)V",
            ),
        )
        // Measured against 15.2.03.2: the storage receivers register and unregister through Context.
        if (moved == 0) throw PatchException("no receiver registrations found")

        // The SD card slot: One UI says so with a property (the stand-in answers "yes"); here it counts as there while
        // Android has an SD card, both when asked and where the start-up answer is read.
        val support = SdCardSupportFingerprint.methodOrNull ?: throw PatchException("the SD card support check was not found")
        support.addInstructionsWithLabels(
            0,
            """
                invoke-static { p0 }, $VOLUMES->sdCard(Landroid/content/Context;)Z
                move-result v0
                if-nez v0, :one_ui_check
                return v0
            """,
            ExternalLabel("one_ui_check", support.implementation!!.instructions.first()),
        )
        val init = EnvManagerInitFingerprint.methodOrNull ?: throw PatchException("EnvManager's start-up was not found")
        val initCode = init.implementation!!.instructions.toList()
        val asked = initCode.indexOfFirst {
            val r = (it as? ReferenceInstruction)?.reference as? MethodReference
            it.opcode == Opcode.INVOKE_STATIC && r?.definingClass == support.definingClass && r.name == support.name &&
                r.parameterTypes.map { p -> p.toString() } == listOf("Landroid/content/Context;") && r.returnType == "Z"
        }
        val setter = initCode.drop(asked + 1).take(2).firstNotNullOfOrNull {
            val r = (it as? ReferenceInstruction)?.reference as? MethodReference
            val takesFlag = r?.parameterTypes?.map { p -> p.toString() } == listOf("Z") && r.returnType == "V"
            if (it.opcode == Opcode.INVOKE_STATIC && takesFlag) r else null
        }
        if (asked < 0 || setter == null) throw PatchException("where EnvManager keeps the SD card answer was not found")
        val flag = classDefByOrNull(setter.definingClass)?.methods
            ?.firstOrNull { it.name == setter.name && it.parameterTypes.map { p -> p.toString() } == listOf("Z") }
            ?.implementation?.instructions?.firstNotNullOfOrNull {
                if (it.opcode == Opcode.SPUT_BOOLEAN) (it as ReferenceInstruction).reference as FieldReference else null
            } ?: throw PatchException("the SD card flag was not found")
        var reads = 0
        classDefForEach { c ->
            if (c.type.startsWith("Lorg/ungoogled/")) return@classDefForEach
            for (m in c.methods) {
                val code = m.implementation?.instructions?.toList() ?: continue
                val sites = code.indices.filter { i ->
                    val f = (code[i] as? ReferenceInstruction)?.reference as? FieldReference
                    code[i].opcode == Opcode.SGET_BOOLEAN && f?.definingClass == flag.definingClass && f.name == flag.name
                }
                if (sites.isEmpty()) continue
                val mutable = mutableClassDefBy(c.type).methods.first {
                    it.name == m.name && it.parameterTypes == m.parameterTypes && it.returnType == m.returnType
                }
                // From the last, so earlier indices stay put; branches to the next instruction still skip it.
                for (i in sites.reversed()) {
                    val r = (code[i] as OneRegisterInstruction).registerA
                    mutable.addInstructions(
                        i + 1,
                        """
                            invoke-static/range { v$r .. v$r }, $VOLUMES->sdCard(Z)Z
                            move-result v$r
                        """,
                    )
                }
                reads += sites.size
            }
        }
        java.util.logging.Logger.getLogger("SamsungMyFiles").info("SD cards: $moved receiver calls, $reads slot flag reads")
        // Measured against 15.2.03.2: 12 reads (home, drawer, pickers, search).
        if (reads == 0) throw PatchException("no reads of the SD card flag found")
    }
}

/**
 * Format and Unmount (an SD card or USB drive's menu) call One UI's StorageManager.semManageExternalStorage, which
 * only One UI has and only system apps may use: the tap did nothing. Android's Storage settings do both.
 */
private val hideFormatUnmountPatch = bytecodePatch(
    description = "Hides Format and Unmount, which only the system can do.",
) {
    execute {
        var removed = 0
        classDefForEach { c ->
            if (c.type.startsWith("Lorg/ungoogled/")) return@classDefForEach
            for (m in c.methods) {
                val instructions = m.implementation?.instructions?.toList() ?: continue
                val sites = instructions.indices.filter { i ->
                    val field = (instructions[i] as? ReferenceInstruction)?.reference as? FieldReference
                    val next = instructions.getOrNull(i + 1)
                    val call = (next as? ReferenceInstruction)?.reference as? MethodReference
                    instructions[i].opcode == Opcode.SGET_OBJECT && field?.definingClass == MENU_ID_TYPE &&
                        (field.name == "FORMAT" || field.name == "UNMOUNT") && next?.opcode == Opcode.INVOKE_STATIC &&
                        call?.parameterTypes?.map { it.toString() } == listOf(MENU_ID_TYPE, "Ljava/util/List;") &&
                        call.returnType == "V"
                }
                if (sites.isEmpty()) continue
                val mutable = mutableClassDefBy(c.type).methods.first {
                    it.name == m.name && it.parameterTypes == m.parameterTypes && it.returnType == m.returnType
                }
                // The "add to the visible menu" call goes; labels stay where they were.
                for (i in sites) mutable.replaceInstruction(i + 1, "nop")
                removed += sites.size
            }
        }
        // Measured against 15.2.03.2: Format and Unmount, once each.
        if (removed == 0) throw PatchException("the Format and Unmount menu items were not found")
    }
}

/**
 * Off One UI My Files dies at once on One UI classes it calls (SemPersonaManager first, at start-up); the Trash,
 * sorting, Manage storage, SD cards and USB drives do not work, and One UI's members and services are missing. The
 * extension carries stand-ins for the 30 One UI classes it calls.
 */
@Suppress("unused")
val enableNonOneUiPatch = bytecodePatch(
    name = "Enable non-One UI ROM support",
    description = "Makes Samsung My Files installable and usable on non-One UI ROMs.",
    default = true,
) {
    compatibleWith(COMPATIBILITY_SAMSUNG_MY_FILES)
    dependsOn(
        oneUiMembersPatch, systemServicesPatch, collationPatch, manageStoragePatch, trashProviderManifestPatch,
        storageVolumesPatch, hideFormatUnmountPatch,
    )
    extendWith(SAMSUNG_MY_FILES_EXTENSION)
}
