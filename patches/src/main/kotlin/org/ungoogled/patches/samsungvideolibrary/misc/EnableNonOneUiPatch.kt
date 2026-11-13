package org.ungoogled.patches.samsungvideolibrary.misc

import app.morphe.patcher.extensions.InstructionExtensions.replaceInstruction
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.patch.resourcePatch
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.instruction.NarrowLiteralInstruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import org.ungoogled.patches.samsung.convertSprDrawables
import org.ungoogled.patches.samsung.implementOneUiInterfaceMethods
import org.ungoogled.patches.samsung.redirectCalls
import org.ungoogled.patches.samsung.redirectOneUiMembers
import org.ungoogled.patches.samsung.redirectSystemServices
import org.ungoogled.patches.samsung.rewriteStringLiterals
import org.ungoogled.patches.samsungvideolibrary.SamsungVideoLibraryConstants.COMPATIBILITY_SAMSUNG_VIDEO_LIBRARY
import org.w3c.dom.Element

internal const val SAMSUNG_VIDEO_LIBRARY_EXTENSION = "extensions/samsungvideolibrary.mpe"

private const val EXT = "Lorg/ungoogled/samsungvideolibrary"
private const val SERVICES = "$EXT/Services;"

/** Video Player's shared user ID, which Video Library joins on One UI. */
private const val VIDEO_SHARED_USER = "com.samsung.android.uid.video"

/** The target SDK the app is raised to (see manifestPatch). */
private const val TARGET_SDK = 34

private val log = java.util.logging.Logger.getLogger("SamsungVideoLibrary")

/**
 * Video Library joins Video Player's shared user ID and is signed with Samsung's platform key. Android 15 and later
 * refuse to install an app that is both (unless the phone's maker allow-lists it, which Samsung stopped doing for this
 * discontinued app): "signed with platform signature and joining shared uid". Re-signed, it could not join the
 * stock Video Player's shared user on a Samsung phone either, and elsewhere there is nothing to share with: it now
 * installs as a regular app. Its target SDK is raised from Android 13 to 14 (the app's runtime receivers and mutable
 * PendingIntents are adapted to what that changes, see targetSdkCallsPatch); Android 15's edge-to-edge and 16's
 * predictive back would change the app's layout and back handling. Deleting, moving and renaming videos other apps
 * made needs "All files access", which the app had through Video Player's shared user and now asks for itself.
 */
private val manifestPatch = resourcePatch {
    execute {
        document("AndroidManifest.xml").use { manifest ->
            val root = manifest.documentElement
            if (root.getAttribute("android:sharedUserId") != VIDEO_SHARED_USER) {
                throw PatchException("the manifest no longer joins $VIDEO_SHARED_USER")
            }
            root.removeAttribute("android:sharedUserId")

            val sdk = manifest.getElementsByTagName("uses-sdk").item(0) as? Element
                ?: throw PatchException("no <uses-sdk> in the manifest")
            val target = sdk.getAttribute("android:targetSdkVersion").toIntOrNull()
                ?: throw PatchException("no targetSdkVersion in the manifest")
            if (target < TARGET_SDK) sdk.setAttribute("android:targetSdkVersion", TARGET_SDK.toString())

            // One UI's own feature names: optional, so no installer filters the app out on another phone.
            val features = manifest.getElementsByTagName("uses-feature")
            for (i in 0 until features.length) {
                val feature = features.item(i) as Element
                if (feature.getAttribute("android:name").startsWith("com.samsung.")) {
                    feature.setAttribute("android:required", "false")
                }
            }

            // A video opens in Samsung Video Player, else in any player; both lookups need the app to see them
            // (Android 11+ package visibility; Video Player shared the app's user ID on One UI). Without, "Play" in a
            // list without Video Player did nothing.
            val application = manifest.getElementsByTagName("application").item(0) as? Element
                ?: throw PatchException("no application element in the manifest")
            root.insertBefore(
                manifest.createElement("queries").apply {
                    appendChild(manifest.createElement("package").apply {
                        setAttribute("android:name", "com.samsung.android.video")
                    })
                    appendChild(manifest.createElement("intent").apply {
                        appendChild(manifest.createElement("action").apply {
                            setAttribute("android:name", "android.intent.action.VIEW")
                        })
                        appendChild(manifest.createElement("data").apply { setAttribute("android:mimeType", "video/*") })
                    })
                },
                application,
            )

            val permissions = manifest.getElementsByTagName("uses-permission")
            val declared = (0 until permissions.length).map { (permissions.item(it) as Element).getAttribute("android:name") }
            if ("android.permission.MANAGE_EXTERNAL_STORAGE" !in declared) {
                val first = permissions.item(0) ?: throw PatchException("no uses-permission in the manifest")
                root.insertBefore(
                    manifest.createElement("uses-permission").apply {
                        setAttribute("android:name", "android.permission.MANAGE_EXTERNAL_STORAGE")
                    },
                    first,
                )
            }
        }
    }
}

/**
 * The inline player's play/pause animation and a few other icons are SPR files, Samsung's vector format that only
 * One UI can draw (playing a video in the list crashed on the first frame of the pause button): they become vector
 * drawables.
 */
private val sprDrawablesPatch = resourcePatch(
    description = "Converts Samsung's SPR icons to vector drawables.",
) {
    execute {
        val converted = convertSprDrawables()
        log.info("SPR drawables: $converted")
        // Measured against 1.4.22.81: 12 files.
        if (converted < 6) throw PatchException("only $converted SPR drawables found")
    }
}

/**
 * Video Library reads One UI's version once into a feature table. From One UI 3.0 on it lists videos from Samsung's
 * own media database (content://secmedia), which only One UI has and which may not answer a copy signed by someone
 * other than Samsung, so the table reads "not One UI" on every phone and the list uses Android's MediaStore. Runs before
 * the One UI member redirect, which would otherwise answer the phone's real version. The file operations' "All files
 * access" check, which One UI 3.1 added, stays on, and the URIs older One UI used for single videos become MediaStore's.
 */
private val versionTablePatch = bytecodePatch(
    description = "Lists videos from Android's MediaStore on every phone.",
) {
    execute {
        val version = setOf(
            "Landroid/os/Build\$VERSION;->SEM_INT:I",
            "Landroid/os/Build\$VERSION;->SEM_PLATFORM_INT:I",
        )
        fun FieldReference.key() = "$definingClass->$name:$type"
        val tables = mutableListOf<Pair<String, String>>()
        classDefForEach { c ->
            val clinit = c.methods.firstOrNull { it.name == "<clinit>" } ?: return@classDefForEach
            val instructions = clinit.implementation?.instructions ?: return@classDefForEach
            val readsVersion = instructions.any {
                it.opcode == Opcode.SGET && ((it as ReferenceInstruction).reference as FieldReference).key() in version
            }
            // One UI 3.0 (SEM_INT 3001): the first version that switches the list to Samsung's media database.
            val oneUi30 = instructions.any { (it as? NarrowLiteralInstruction)?.narrowLiteral == 3001 }
            if (readsVersion && oneUi30) tables += c.type to clinit.name
        }
        val (type, _) = tables.singleOrNull() ?: throw PatchException("expected one One UI version table, found ${tables.size}")
        val clinit = mutableClassDefBy(type).methods.first { it.name == "<clinit>" }
        val instructions = clinit.implementation!!.instructions
        var reads = 0
        for (index in instructions.indices) {
            val insn = instructions[index]
            if (insn.opcode != Opcode.SGET || ((insn as ReferenceInstruction).reference as FieldReference).key() !in version) continue
            clinit.replaceInstruction(index, "const/4 v${(insn as OneRegisterInstruction).registerA}, 0x0")
            reads++
        }
        // Measured against 1.4.22.81: SEM_INT twice, SEM_PLATFORM_INT twice.
        if (reads < 2) throw PatchException("only $reads One UI version reads in the version table")

        // Below One UI 3.0 the list still names each video, and reads its details, by Samsung's database URIs (the same
        // IDs as MediaStore's there): the inline player found no video ("doesn't exist in Media DB"). They are
        // MediaStore's URIs now.
        val uris = rewriteStringLiterals { literal ->
            when (literal) {
                "content://secmedia/video/media", "content://secmedia/media" -> "content://media/external/video/media"
                else -> null
            }
        }
        // Measured against 1.4.22.81: 5 video URIs and 2 file URIs.
        if (uris < 5) throw PatchException("only $uris of Samsung's media database URIs found")

        // Delete, move, copy and rename work on the files themselves, which needs "All files access"; from One UI 3.1
        // on the app asks for it first, and those checks keep asking on every phone.
        val storageManager = "Landroid/os/Environment;->isExternalStorageManager()Z"
        val gates = mutableListOf<Pair<String, com.android.tools.smali.dexlib2.iface.Method>>()
        classDefForEach { c ->
            for (m in c.methods) {
                if (m.implementation?.instructions?.any {
                        ((it as? ReferenceInstruction)?.reference as? MethodReference)?.let { r ->
                            "${r.definingClass}->${r.name}()${r.returnType}" == storageManager
                        } == true
                    } == true) gates += c.type to m
            }
        }
        var forced = 0
        for ((owner, method) in gates) {
            val mutable = mutableClassDefBy(owner).methods.first {
                it.name == method.name && it.parameterTypes == method.parameterTypes && it.returnType == method.returnType
            }
            val list = mutable.implementation!!.instructions
            for (index in list.indices) {
                val call = (list[index] as? ReferenceInstruction)?.reference as? MethodReference ?: continue
                if ("${call.definingClass}->${call.name}()${call.returnType}" != storageManager) continue
                val flag = (maxOf(0, index - 4) until index).lastOrNull { i ->
                    list[i].opcode == Opcode.SGET_BOOLEAN &&
                        ((list[i] as ReferenceInstruction).reference as FieldReference).definingClass == type
                } ?: continue
                mutable.replaceInstruction(flag, "const/4 v${(list[flag] as OneRegisterInstruction).registerA}, 0x1")
                forced++
            }
        }
        // Measured against 1.4.22.81: delete, move or copy, rename, and folder rename.
        if (forced < 3) throw PatchException("only $forced \"All files access\" checks found")
    }
}

/** Sends Video Library's uses of One UI's additions to Android classes through OneUi (see redirectOneUiMembers). */
private val oneUiMembersPatch = bytecodePatch(
    description = "Answers One UI's additions to Android classes where the phone has none.",
) {
    dependsOn(versionTablePatch)
    extendWith(SAMSUNG_VIDEO_LIBRARY_EXTENSION)

    execute {
        val (calls, fields) = redirectOneUiMembers("$EXT/OneUi;", "$EXT/Sem;")
        val implemented = implementOneUiInterfaceMethods("$EXT/OneUi;")
        log.info("One UI members: $calls calls, $fields fields, $implemented inherited methods")
        // Measured against 1.4.22.81: 47 calls and 3 field accesses (after the version table).
        if (calls < 30) throw PatchException("only $calls One UI calls found")
    }
}

/**
 * Even off One UI's media database the list asks MediaStore in Samsung's shape (Samsung's columns such as resumePos,
 * GROUP BY for folders, Samsung's natural-order collation); Google's MediaStore refused ("Invalid column resumePos")
 * and the list crashed at start. Every query and update goes through the extension, which answers one MediaStore
 * refuses from a copy of the phone's videos in Samsung's shape (MediaMirror).
 */
private val mediaQueriesPatch = bytecodePatch(
    description = "Answers Samsung-shaped MediaStore queries from a copy of the phone's videos.",
) {
    extendWith(SAMSUNG_VIDEO_LIBRARY_EXTENSION)

    execute {
        val resolver = "Landroid/content/ContentResolver;"
        val query = "query(Landroid/net/Uri;[Ljava/lang/String;Ljava/lang/String;[Ljava/lang/String;Ljava/lang/String;)Landroid/database/Cursor;"
        val querySignal = "query(Landroid/net/Uri;[Ljava/lang/String;Ljava/lang/String;[Ljava/lang/String;Ljava/lang/String;Landroid/os/CancellationSignal;)Landroid/database/Cursor;"
        val queryArgs = "query(Landroid/net/Uri;[Ljava/lang/String;Landroid/os/Bundle;Landroid/os/CancellationSignal;)Landroid/database/Cursor;"
        val update = "update(Landroid/net/Uri;Landroid/content/ContentValues;Ljava/lang/String;[Ljava/lang/String;)I"
        fun static(shape: String) = "$SERVICES->${shape.replaceFirst("(", "($resolver")}"
        val moved = redirectCalls(listOf(query, querySignal, queryArgs, update).associate { "$resolver->$it" to static(it) })
        log.info("MediaStore calls: $moved")
        // Measured against 1.4.22.81: 21 + 1 + 5 queries, 1 update.
        if (moved < 20) throw PatchException("only $moved content resolver queries found")
    }
}

/**
 * The list registers for package events with One UI's event delegation service, cast from
 * getSystemService("semeventdelegator") without a check: off One UI that is null and the list crashed.
 */
private val systemServicesPatch = bytecodePatch(
    description = "Answers One UI's system services where the phone has none.",
) {
    extendWith(SAMSUNG_VIDEO_LIBRARY_EXTENSION)

    execute {
        val moved = redirectSystemServices(SERVICES)
        if (moved == 0) throw PatchException("no getSystemService calls found")
    }
}

/**
 * What the raised target SDK changes: a receiver registered at runtime must say whether other apps may send to it
 * (unsaid, Android throws), and a mutable PendingIntent must name its app (the share sheet's "chosen" callback threw
 * and Share crashed). Receivers stay exported as before; the app's own PendingIntent broadcasts are addressed to it.
 */
private val targetSdkCallsPatch = bytecodePatch(
    description = "Adapts receivers and PendingIntents to the raised target SDK.",
) {
    extendWith(SAMSUNG_VIDEO_LIBRARY_EXTENSION)

    execute {
        val register = "(Landroid/content/BroadcastReceiver;Landroid/content/IntentFilter;)Landroid/content/Intent;"
        val receivers = redirectCalls(
            mapOf(
                "Landroid/content/Context;->registerReceiver$register" to
                    "$SERVICES->registerReceiver(Landroid/content/Context;Landroid/content/BroadcastReceiver;Landroid/content/IntentFilter;)Landroid/content/Intent;",
                "Landroid/app/Activity;->registerReceiver$register" to
                    "$SERVICES->registerReceiver(Landroid/content/Context;Landroid/content/BroadcastReceiver;Landroid/content/IntentFilter;)Landroid/content/Intent;",
            ),
        )
        val broadcast = "Landroid/app/PendingIntent;->getBroadcast(Landroid/content/Context;ILandroid/content/Intent;I)Landroid/app/PendingIntent;"
        val pendingIntents = redirectCalls(
            mapOf(broadcast to "$SERVICES->getBroadcast(Landroid/content/Context;ILandroid/content/Intent;I)Landroid/app/PendingIntent;"),
        )
        log.info("Target SDK: $receivers receivers, $pendingIntents PendingIntents")
        // Measured against 1.4.22.81: 6 runtime receivers, 4 broadcast PendingIntents (share callback, 3 package events).
        if (receivers < 4 || pendingIntents < 2) {
            throw PatchException("only $receivers receiver registrations and $pendingIntents PendingIntents found")
        }
    }
}

/**
 * A video plays in Samsung Video Player; where it is missing the list hands the video to any player, by a content URI
 * the player could only open with media access of its own. The list now lends it read access to that video.
 */
private val playerIntentPatch = bytecodePatch(
    description = "Lets any video player open the chosen video.",
) {
    extendWith(SAMSUNG_VIDEO_LIBRARY_EXTENSION)

    execute {
        val start = "startActivity(Landroid/content/Intent;)V"
        val moved = redirectCalls(
            listOf("Landroid/app/Activity;", "Landroid/content/Context;").associate {
                "$it->$start" to "$SERVICES->startActivity(Landroid/content/Context;Landroid/content/Intent;)V"
            },
        )
        // Measured against 1.4.22.81: 4 Activity and 13 Context calls (the player intent is an Activity call).
        if (moved < 4) throw PatchException("only $moved startActivity calls found")
    }
}

/**
 * Once a day (and from "About Video Library") the list asked Samsung's update server (vas.samsungapps.com) for a newer
 * version, sending the phone's model, carrier, sales code and advertising or Android ID; the app is discontinued and a
 * newer Samsung build could not replace this copy anyway. The check answers "latest version installed" without asking.
 */
private val updateCheckPatch = bytecodePatch(
    description = "Turns off the update check.",
) {
    execute {
        val method = UpdateCheckAllowedFingerprint.method
        val instructions = method.implementation!!.instructions
        // isOnline(context): the first call's result; offline, the check reports its code and returns.
        val online = instructions.indexOfFirst { it.opcode == Opcode.INVOKE_DIRECT }
        if (online < 0 || instructions[online + 1].opcode != Opcode.MOVE_RESULT) {
            throw PatchException("update check's network test not found")
        }
        val onlineRegister = (instructions[online + 1] as OneRegisterInstruction).registerA
        // The offline result code (3, "couldn't check") just before it is turned into text.
        val code = instructions.indices.singleOrNull { i ->
            val insn = instructions[i]
            insn.opcode == Opcode.CONST_4 && (insn as NarrowLiteralInstruction).narrowLiteral == 3 &&
                ((instructions.getOrNull(i + 1) as? ReferenceInstruction)?.reference as? MethodReference)?.let {
                    it.definingClass == "Ljava/lang/String;" && it.name == "valueOf"
                } == true
        } ?: throw PatchException("update check's offline result code not found")
        method.replaceInstruction(code, "const/4 v${(instructions[code] as OneRegisterInstruction).registerA}, 0x1")
        method.replaceInstruction(online + 1, "const/4 v$onlineRegister, 0x0")
    }
}

@Suppress("unused")
val enableNonOneUiPatch = bytecodePatch(
    name = "Enable non-One UI ROM support",
    description = "Makes Samsung Video Library installable on Android 15+ and usable on non-One UI ROMs.",
    default = true,
) {
    compatibleWith(COMPATIBILITY_SAMSUNG_VIDEO_LIBRARY)
    dependsOn(
        manifestPatch, sprDrawablesPatch, versionTablePatch, oneUiMembersPatch, systemServicesPatch, mediaQueriesPatch,
        targetSdkCallsPatch, playerIntentPatch, updateCheckPatch,
    )
    extendWith(SAMSUNG_VIDEO_LIBRARY_EXTENSION)
}

