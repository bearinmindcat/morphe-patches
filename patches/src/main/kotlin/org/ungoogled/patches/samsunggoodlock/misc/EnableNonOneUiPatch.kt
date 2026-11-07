package org.ungoogled.patches.samsunggoodlock.misc

import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.extensions.InstructionExtensions.addInstructionsWithLabels
import app.morphe.patcher.extensions.InstructionExtensions.getInstruction
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.patch.resourcePatch
import app.morphe.patcher.util.smali.ExternalLabel
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.TwoRegisterInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.iface.reference.StringReference
import com.android.tools.smali.dexlib2.iface.reference.TypeReference
import org.ungoogled.patches.samsung.redirectOneUiMembers
import org.ungoogled.patches.samsunggoodlock.SamsungGoodLockConstants.COMPATIBILITY_SAMSUNG_GOOD_LOCK
import org.w3c.dom.Element

internal const val SAMSUNG_GOOD_LOCK_EXTENSION = "extensions/samsunggoodlock.mpe"

private const val EXT = "Lorg/ungoogled/samsunggoodlock"
private const val EXT_CLASSES = "org.ungoogled.samsunggoodlock"

private val log = java.util.logging.Logger.getLogger("GoodLock")

/** Sends Good Lock's uses of One UI's additions to Android classes through OneUi (see redirectOneUiMembers). */
private val oneUiMembersPatch = bytecodePatch(
    description = "Answers One UI's additions to Android classes where the phone has none.",
) {
    extendWith(SAMSUNG_GOOD_LOCK_EXTENSION)

    execute {
        val (calls, fields) = redirectOneUiMembers("$EXT/OneUi;", "$EXT/Sem;")
        log.info("One UI members: $calls calls, $fields fields")
        if (calls == 0) throw PatchException("no One UI calls found")
    }
}

/**
 * Good Lock lists what the Galaxy Store offers the phone and installs modules from it. Off One UI it lists the modules
 * that work there (see Modules) and sends downloads to APKMirror, to be patched with Morphe (see Store).
 */
private val modulesPatch = bytecodePatch(
    description = "Lists the modules that work off One UI and opens their downloads on APKMirror.",
) {
    extendWith(SAMSUNG_GOOD_LOCK_EXTENSION)

    execute {
        // The merge of (module list, store entries, policies): both lists are replaced at its start.
        val merge = PluginsMergeFingerprint.method
        if (merge.parameterTypes.size < 2 || merge.parameterTypes[0] != "Ljava/util/List;" ||
            merge.parameterTypes[1] != "Ljava/util/List;"
        ) throw PatchException("the module merge does not take (List, List, ...)")
        // The store entry class: what the merge casts the store list's items to, next to the "not found" check.
        val entry = merge.implementation!!.instructions.filter { it.opcode == Opcode.CHECK_CAST }
            .map { ((it as ReferenceInstruction).reference as TypeReference).type }
            .firstOrNull { type ->
                classDefByOrNull(type)?.fields?.any { it.name.isNotEmpty() && it.type == "Ljava/lang/Boolean;" } == true &&
                    classDefByOrNull(type)?.methods?.any { it.name == "<init>" && it.parameterTypes.size == 11 } == true
            } ?: throw PatchException("no store entry class in the module merge")
        merge.addInstructionsWithLabels(
            0,
            """
                invoke-static/range { p1 .. p1 }, $EXT/Modules;->keep(Ljava/util/List;)Ljava/util/List;
                move-result-object p1
                move-object/from16 v0, p1
                move-object/from16 v1, p2
                const-class v2, $entry
                invoke-static { v0, v1, v2 }, $EXT/Modules;->store(Ljava/util/List;Ljava/util/List;Ljava/lang/Class;)Ljava/util/List;
                move-result-object p2
            """,
        )

        // Without an answer from the Galaxy Store its cached entry list is null and Good Lock lists nothing: an empty
        // list instead, so the modules come from Good Lock's own list (see Modules.store).
        val load = PluginsLoadFingerprint.method
        val loadCode = load.implementation!!.instructions.toList()
        val listReads = loadCode.withIndex().filter { (index, insn) ->
            if (insn.opcode != Opcode.CONST_STRING && insn.opcode != Opcode.CONST_STRING_JUMBO) return@filter false
            ((insn as ReferenceInstruction).reference as StringReference).string == "storePlugins is null in local"
        }.mapNotNull { (index, _) ->
            (index downTo 0).firstOrNull { i ->
                val field = (loadCode[i] as? ReferenceInstruction)?.reference as? FieldReference
                loadCode[i].opcode == Opcode.IGET_OBJECT && field?.type == "Ljava/util/List;"
            }
        }.distinct().sortedDescending()
        if (listReads.isEmpty()) throw PatchException("no Galaxy Store entry list read before \"storePlugins is null in local\"")
        for (index in listReads) {
            val register = (loadCode[index] as TwoRegisterInstruction).registerA
            load.addInstructionsWithLabels(
                index + 1,
                """
                    if-nez v$register, :present
                    new-instance v$register, Ljava/util/ArrayList;
                    invoke-direct/range { v$register .. v$register }, Ljava/util/ArrayList;-><init>()V
                """,
                ExternalLabel("present", load.getInstruction(index + 1)),
            )
        }
        log.info("Galaxy Store list reads made null-safe: ${listReads.size}")

        // Installs: open the module on APKMirror instead (Store.open answers false where the Galaxy Store is).
        val install = InstallFingerprint.method
        if (install.parameterTypes.firstOrNull() != "Ljava/lang/String;") throw PatchException("the install job takes no package name")
        val suspended = install.implementation!!.instructions.firstNotNullOfOrNull { insn ->
            val field = (insn as? ReferenceInstruction)?.reference as? FieldReference
            if (insn.opcode == Opcode.SGET_OBJECT && field != null && field.type == field.definingClass) field else null
        } ?: throw PatchException("the install job has no COROUTINE_SUSPENDED")
        install.addInstructionsWithLabels(
            0,
            """
                invoke-static/range { p1 .. p1 }, $EXT/Store;->open(Ljava/lang/String;)Z
                move-result v0
                if-eqz v0, :stock
                sget-object v0, $suspended
                return-object v0
            """,
            ExternalLabel("stock", install.getInstruction(0)),
        )
        val installNow = InstallNowFingerprint.method
        installNow.addInstructionsWithLabels(
            0,
            """
                invoke-static/range { p1 .. p1 }, $EXT/Store;->open(Ljava/lang/String;)Z
                move-result v0
                if-eqz v0, :stock
                return-void
            """,
            ExternalLabel("stock", installNow.getInstruction(0)),
        )

        val page = StorePageFingerprint.method
        if (page.parameterTypes.firstOrNull() != "Ljava/lang/String;") throw PatchException("the store page takes no package name")
        page.addInstructionsWithLabels(
            0,
            """
                invoke-static/range { p1 .. p1 }, $EXT/Store;->open(Ljava/lang/String;)Z
                move-result v0
                if-eqz v0, :stock
                return-void
            """,
            ExternalLabel("stock", page.getInstruction(0)),
        )
    }
}

/**
 * Good Lock fetched its content files and asked the Galaxy Store for its own update (sending the phone's model) before
 * the terms screen was answered. Every request goes through its HTTP client's engine, which now asks Consent first:
 * nothing leaves before "Agree", and the Galaxy Store update check never does off One UI (Good Lock's own no-network
 * path: no update).
 */
private val consentPatch = bytecodePatch(
    description = "Sends nothing before the terms are agreed to and skips Galaxy Store update checks.",
) {
    extendWith(SAMSUNG_GOOD_LOCK_EXTENSION)

    execute {
        val engine = mutableClassDefBy(HttpEngineInitFingerprint.method.definingClass)
        // execute(request, continuation): the engine's only two-argument method returning Object.
        val execute = engine.methods.filter {
            it.returnType == "Ljava/lang/Object;" && it.parameterTypes.size == 2 && it.implementation != null &&
                !it.parameterTypes[0].startsWith("L" + "java/")
        }.singleOrNull() ?: throw PatchException("no execute(request, continuation) in the HTTP client engine")
        execute.addInstructions(
            0,
            "invoke-static/range { p1 .. p1 }, $EXT/Consent;->check(Ljava/lang/Object;)V",
        )
    }
}

/** Declares WallpaperActivity (see wallpapersPatch) and the permission WallpaperManager needs. */
private val wallpaperManifestPatch = resourcePatch {
    execute {
        document("AndroidManifest.xml").use { manifest ->
            val root = manifest.documentElement
            val permissions = manifest.getElementsByTagName("uses-permission")
            val declared = (0 until permissions.length).map { (permissions.item(it) as Element).getAttribute("android:name") }
            if ("android.permission.SET_WALLPAPER" !in declared) {
                root.insertBefore(
                    manifest.createElement("uses-permission").apply { setAttribute("android:name", "android.permission.SET_WALLPAPER") },
                    manifest.getElementsByTagName("application").item(0),
                )
            }
            val application = manifest.getElementsByTagName("application").item(0) as Element
            application.appendChild(
                manifest.createElement("activity").apply {
                    setAttribute("android:name", "$EXT_CLASSES.WallpaperActivity")
                    setAttribute("android:exported", "false")
                    setAttribute("android:excludeFromRecents", "true")
                    setAttribute("android:theme", "@android:style/Theme.Translucent.NoTitleBar")
                    setAttribute("android:configChanges", "orientation|screenSize|screenLayout|smallestScreenSize|keyboardHidden|uiMode|density")
                },
            )
        }
    }
}

/**
 * Discover's wallpapers went to One UI's "Wallpaper and style" (com.samsung.android.app.dressroom), which other phones
 * do not have: "No Activity found to handle Intent". The intent now goes through Wallpapers.route, which sends it to
 * WallpaperActivity (Android's WallpaperManager, on the screens picked in Good Lock) where nothing answers it.
 */
private val wallpapersPatch = bytecodePatch(
    description = "Sets Discover wallpapers through Android's wallpaper manager.",
) {
    extendWith(SAMSUNG_GOOD_LOCK_EXTENSION)
    dependsOn(wallpaperManifestPatch)

    execute {
        val method = SetAsWallpaperFingerprint.method
        val code = method.implementation!!.instructions.toList()
        // The intent is complete after addFlags; the launcher's launch(intent) is the next non-Android call taking it.
        val addFlags = code.indexOfLast {
            val ref = (it as? ReferenceInstruction)?.reference as? MethodReference
            ref?.definingClass == "Landroid/content/Intent;" && ref.name == "addFlags"
        }
        if (addFlags < 0 || code.getOrNull(addFlags + 1)?.opcode != Opcode.MOVE_RESULT_OBJECT) {
            throw PatchException("no Intent.addFlags(...) in the wallpaper hand-over")
        }
        val intent = (code[addFlags + 1] as OneRegisterInstruction).registerA
        val launch = (addFlags + 2 until code.size).firstOrNull { i ->
            val insn = code[i]
            val ref = (insn as? ReferenceInstruction)?.reference as? MethodReference ?: return@firstOrNull false
            (insn.opcode == Opcode.INVOKE_VIRTUAL || insn.opcode == Opcode.INVOKE_INTERFACE) &&
                (insn as FiveRegisterInstruction).registerCount == 2 && insn.registerD == intent &&
                ref.returnType == "V" && ref.parameterTypes.size == 1 &&
                !ref.definingClass.startsWith("Landroid/") && !ref.definingClass.startsWith("Ljava/")
        } ?: throw PatchException("no launch(intent) after the wallpaper intent is built")
        method.addInstructions(
            launch,
            """
                invoke-static/range { v$intent .. v$intent }, $EXT/Wallpapers;->route(Landroid/content/Intent;)Landroid/content/Intent;
                move-result-object v$intent
            """,
        )
    }
}

/**
 * Off One UI Good Lock crashes when it opens a module (SemFloatingFeature, One UI's icon calls) and lists only what
 * the Galaxy Store offers the phone. The extension carries stand-ins for the One UI classes it calls. Discover's
 * wallpapers are set through Android's wallpaper manager (see wallpapersPatch).
 */
@Suppress("unused")
val enableNonOneUiPatch = bytecodePatch(
    name = "Enable non-One UI ROM support",
    description = "Runs Good Lock on non-One UI ROMs, listing the modules that work there.",
    default = true,
) {
    compatibleWith(COMPATIBILITY_SAMSUNG_GOOD_LOCK)
    dependsOn(oneUiMembersPatch, modulesPatch, consentPatch, wallpapersPatch)
    extendWith(SAMSUNG_GOOD_LOCK_EXTENSION)
}
