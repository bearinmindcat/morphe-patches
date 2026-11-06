package org.ungoogled.patches.asuslauncher.misc

import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.extensions.InstructionExtensions.replaceInstruction
import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.patch.resourcePatch
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.iface.reference.StringReference
import com.android.tools.smali.dexlib2.iface.reference.TypeReference
import org.ungoogled.patches.asuslauncher.AsusLauncherConstants.COMPATIBILITY_ASUS_LAUNCHER
import org.ungoogled.patches.asuslauncher.HIDDEN_CONSTRUCTORS
import org.ungoogled.patches.asuslauncher.HIDDEN_CONSTRUCTORS_16_17
import org.ungoogled.patches.asuslauncher.HIDDEN_MEMBERS
import org.ungoogled.patches.asuslauncher.HIDDEN_MEMBERS_16_17
import org.ungoogled.patches.asuslauncher.HIDDEN_SUPERCLASSES
import org.ungoogled.patches.asuslauncher.SUPER_CONSTRUCTORS
import org.ungoogled.patches.coloros.exportDynamicReceivers
import org.ungoogled.patches.coloroslauncher.misc.rebaseSubclasses
import org.ungoogled.patches.coloroslauncher.misc.redirectConstructors
import org.ungoogled.patches.coloroslauncher.misc.redirectHiddenMembers
import org.ungoogled.patches.coloroslauncher.misc.skipCastOfNull
import org.ungoogled.patches.coloroslauncher.misc.substituteSuperConstructors
import org.ungoogled.patches.samsung.redirectCalls

internal const val ASUS_LAUNCHER_EXTENSION = "extensions/asuslauncher.mpe"

private const val EXT = "Lorg/ungoogled/asuslauncher"

private val log = java.util.logging.Logger.getLogger("AsusLauncher")

private val SETTINGS_SHAPES = listOf(
    "getString(Landroid/content/ContentResolver;Ljava/lang/String;)Ljava/lang/String;" to "GetString",
    "getInt(Landroid/content/ContentResolver;Ljava/lang/String;I)I" to "GetInt",
    "getInt(Landroid/content/ContentResolver;Ljava/lang/String;)I" to "GetInt",
    "getLong(Landroid/content/ContentResolver;Ljava/lang/String;J)J" to "GetLong",
    "getLong(Landroid/content/ContentResolver;Ljava/lang/String;)J" to "GetLong",
    "getFloat(Landroid/content/ContentResolver;Ljava/lang/String;F)F" to "GetFloat",
    "getFloat(Landroid/content/ContentResolver;Ljava/lang/String;)F" to "GetFloat",
    "putString(Landroid/content/ContentResolver;Ljava/lang/String;Ljava/lang/String;)Z" to "PutString",
    "putInt(Landroid/content/ContentResolver;Ljava/lang/String;I)Z" to "PutInt",
    "putLong(Landroid/content/ContentResolver;Ljava/lang/String;J)Z" to "PutLong",
    "putFloat(Landroid/content/ContentResolver;Ljava/lang/String;F)Z" to "PutFloat",
    // The per-user calls (hidden or system-only where not public): for the app's own user.
    "getIntForUser(Landroid/content/ContentResolver;Ljava/lang/String;II)I" to "GetIntForUser",
    "getIntForUser(Landroid/content/ContentResolver;Ljava/lang/String;I)I" to "GetIntForUser",
    "getLongForUser(Landroid/content/ContentResolver;Ljava/lang/String;JI)J" to "GetLongForUser",
    "getStringForUser(Landroid/content/ContentResolver;Ljava/lang/String;I)Ljava/lang/String;" to "GetStringForUser",
    "putIntForUser(Landroid/content/ContentResolver;Ljava/lang/String;II)Z" to "PutIntForUser",
    "putStringForUser(Landroid/content/ContentResolver;Ljava/lang/String;Ljava/lang/String;I)Z" to "PutStringForUser",
)

/** Every settings call goes through LocalSettings, which keeps a refused write in the app's own preferences. */
private val SETTINGS_CALLS: Map<String, String> = buildMap {
    for (table in listOf("Global", "Secure", "System")) {
        for ((shape, suffix) in SETTINGS_SHAPES) {
            put(
                "Landroid/provider/Settings\$$table;->$shape",
                "$EXT/LocalSettings;->${table.lowercase()}$suffix${shape.substring(shape.indexOf('('))}",
            )
        }
    }
}

/** Window contexts and windows of types only the system may use (see Windows). */
private val WINDOW_CALLS = listOf(
    "Landroid/content/Context;" to "createWindowContext(ILandroid/os/Bundle;)Landroid/content/Context;",
    "Landroid/content/Context;" to "createWindowContext(Landroid/view/Display;ILandroid/os/Bundle;)Landroid/content/Context;",
    "Landroid/view/ViewManager;" to "addView(Landroid/view/View;Landroid/view/ViewGroup\$LayoutParams;)V",
    "Landroid/view/ViewManager;" to "updateViewLayout(Landroid/view/View;Landroid/view/ViewGroup\$LayoutParams;)V",
    "Landroid/view/ViewManager;" to "removeView(Landroid/view/View;)V",
    "Landroid/view/WindowManager;" to "removeViewImmediate(Landroid/view/View;)V",
    "Landroid/content/Context;" to "getDisplay()Landroid/view/Display;",
    "Landroid/window/OnBackInvokedDispatcher;" to "registerOnBackInvokedCallback(ILandroid/window/OnBackInvokedCallback;)V",
    "Landroid/content/ContextWrapper;" to "getDisplay()Landroid/view/Display;",
).associate { (owner, shape) ->
    "$owner->$shape" to "$EXT/Windows;->${shape.substringBefore('(')}($owner${shape.substringAfter('(')}"
}

/** Reflection on Android's hidden parts (see Reflect). */
private val REFLECTION_CALLS = listOf(
    "getField(Ljava/lang/String;)Ljava/lang/reflect/Field;",
    "getDeclaredField(Ljava/lang/String;)Ljava/lang/reflect/Field;",
    "getMethod(Ljava/lang/String;[Ljava/lang/Class;)Ljava/lang/reflect/Method;",
    "getDeclaredMethod(Ljava/lang/String;[Ljava/lang/Class;)Ljava/lang/reflect/Method;",
).associate { shape ->
    "Ljava/lang/Class;->$shape" to "$EXT/Reflect;->${shape.substringBefore('(')}(Ljava/lang/Class;${shape.substringAfter('(')}"
}

/** Calls an installed app may make but that fail for it (see Calls). */
private val GUARDED_CALLS = mapOf(
    "Landroid/content/ContentResolver;->registerContentObserver(Landroid/net/Uri;ZLandroid/database/ContentObserver;)V" to
        "$EXT/Calls;->registerContentObserver(Landroid/content/ContentResolver;Landroid/net/Uri;ZLandroid/database/ContentObserver;)V",
    "Landroid/app/ActivityManager;->getCurrentUser()I" to "$EXT/Calls;->getCurrentUser()I",
)

/**
 * ASUS Launcher is a system app built against the full Android framework; an installed app may not link to its hidden
 * parts. Each hidden member it uses goes through Members (used where Android allows it, "absent" elsewhere), each
 * `new` of a class an app may not construct through Members.construct, and subclasses of such classes are rebased.
 * The lists are generated (.github/scripts/coloros-launcher/hidden.py), the helpers are the OnePlus System Launcher
 * port's (the same Launcher3 / Quickstep base).
 */
private val hiddenApisPatch = bytecodePatch(
    description = "Answers Android's hidden parts where an installed app may not use them.",
) {
    extendWith(ASUS_LAUNCHER_EXTENSION)

    execute {
        val (calls, fields) = redirectHiddenMembers(
            "$EXT/Members;", "$EXT/Hidden;", HIDDEN_MEMBERS + HIDDEN_MEMBERS_16_17,
        )
        val constructors = redirectConstructors(
            "$EXT/Members;", "$EXT/Hidden;", HIDDEN_CONSTRUCTORS + HIDDEN_CONSTRUCTORS_16_17,
        )
        val superCalls = substituteSuperConstructors(SUPER_CONSTRUCTORS)
        val rebased = rebaseSubclasses(HIDDEN_SUPERCLASSES)
        val casts = nullCastsToRebasedBases(HIDDEN_SUPERCLASSES.keys)
        skipCastOfNull("$EXT/Hidden;")
        log.info("hidden members: $calls calls, $fields fields; constructors: $constructors; super calls: $superCalls; rebased classes: $rebased; casts to their old bases: $casts")
        if (calls < 100) throw PatchException("only $calls hidden member calls found")
        if (constructors == 0) throw PatchException("hidden constructors not found")
        if (rebased == 0) throw PatchException("no subclasses of hidden Android classes found")
    }
}

/** The methods of the app (not our extensions) that cast to one of [types]. */
private fun BytecodePatchContext.castSites(types: Set<String>): List<Pair<String, Method>> {
    val sites = mutableListOf<Pair<String, Method>>()
    classDefForEach { c ->
        if (c.type.startsWith("Lorg/ungoogled/")) return@classDefForEach
        for (m in c.methods) {
            if (m.implementation?.instructions?.any {
                    it.opcode == Opcode.CHECK_CAST && ((it as ReferenceInstruction).reference as TypeReference).type in types
                } == true
            ) sites += c.type to m
        }
    }
    return sites
}

private fun BytecodePatchContext.mutableMethod(type: String, method: Method) = mutableClassDefBy(type).methods.first {
    it.name == method.name && it.parameterTypes == method.parameterTypes && it.returnType == method.returnType
}

/**
 * A rebased object (see rebaseSubclasses) is no longer of its old Android base class, so a cast to that class throws.
 * rebaseSubclasses leaves fields of that type empty; the cast now gives null too. Returns the casts changed.
 */
private fun BytecodePatchContext.nullCastsToRebasedBases(bases: Set<String>): Int {
    var changed = 0
    for ((type, method) in castSites(bases)) {
        val m = mutableMethod(type, method)
        val instructions = m.implementation!!.instructions
        for (index in instructions.indices) {
            val insn = instructions[index]
            if (insn.opcode != Opcode.CHECK_CAST || ((insn as ReferenceInstruction).reference as TypeReference).type !in bases) continue
            val r = (insn as OneRegisterInstruction).registerA
            m.replaceInstruction(index, if (r < 16) "const/4 v$r, 0x0" else "const/16 v$r, 0x0")
            changed++
        }
    }
    return changed
}

/**
 * What a system home screen may do and an installed app may not: keep options in Android's settings tables, register
 * receivers without saying who may send, open system-only windows, ask the app prediction service.
 */
private val systemPrivilegesPatch = bytecodePatch(
    description = "Keeps its options locally and skips what only the system's home screen may do.",
) {
    extendWith(ASUS_LAUNCHER_EXTENSION)

    execute {
        val receivers = exportDynamicReceivers("$EXT/Receivers;")
        val settings = redirectCalls(SETTINGS_CALLS)
        val windows = redirectCalls(WINDOW_CALLS)
        val reflection = redirectCalls(REFLECTION_CALLS)
        val guarded = redirectCalls(GUARDED_CALLS)
        log.info("receivers: $receivers, settings calls: $settings, windows: $windows, reflection: $reflection, guarded: $guarded")
        if (settings < 20) throw PatchException("only $settings settings calls found")
        if (guarded < 2) throw PatchException("only $guarded content observer / user calls found")

        // Suggested apps come from Android's app prediction service, which only answers a caller with usage access: each
        // lookup of the service sees none without it (the launcher checks for null), so it skips its predictions.
        val predictionManager = "Landroid/app/prediction/AppPredictionManager;"
        var gated = 0
        for ((type, method) in castSites(setOf(predictionManager))) {
            val m = mutableMethod(type, method)
            val instructions = m.implementation!!.instructions
            for (index in instructions.indices.reversed()) {
                val insn = instructions[index]
                if (insn.opcode != Opcode.CHECK_CAST || ((insn as ReferenceInstruction).reference as TypeReference).type != predictionManager) continue
                val r = (insn as OneRegisterInstruction).registerA
                m.addInstructions(
                    index + 1,
                    """
                        invoke-static/range { v$r .. v$r }, $EXT/Launch;->predictions(Ljava/lang/Object;)Ljava/lang/Object;
                        move-result-object v$r
                        check-cast v$r, $predictionManager
                    """,
                )
                gated++
            }
        }
        log.info("app prediction lookups: $gated")
        if (gated == 0) throw PatchException("no app prediction service lookups found")
    }
}

/**
 * ASUS Launcher goes online without asking: a provider in its own process (FirebaseProvider) starts Firebase Analytics
 * with the phone's model, carrier and ASUS SKU, sends the launcher's usage events through it and fetches Firebase Remote
 * Config at every start, and the app categories come from each installed app's Google Play page. The provider stays (the
 * launcher calls it) but starts nothing and answers every call with nothing; Firebase's own measurement components are
 * removed; the Play page parser answers "not found" without going online.
 */
private val telemetryPatch = bytecodePatch(
    description = "Keeps the launcher offline: no Firebase, no app lookups on Google Play.",
) {
    dependsOn(
        resourcePatch {
            execute {
                document("AndroidManifest.xml").use { manifest ->
                    val drop = setOf(
                        "com.google.firebase.components.ComponentDiscoveryService",
                        "com.google.android.gms.measurement.AppMeasurementReceiver",
                        "com.google.android.gms.measurement.AppMeasurementService",
                        "com.google.android.gms.measurement.AppMeasurementJobService",
                    )
                    val application = manifest.getElementsByTagName("application").item(0)
                    var removed = 0
                    for (tag in listOf("provider", "service", "receiver")) {
                        val nodes = manifest.getElementsByTagName(tag)
                        for (i in nodes.length - 1 downTo 0) {
                            val node = nodes.item(i) as org.w3c.dom.Element
                            if (node.getAttribute("android:name") in drop) {
                                application.removeChild(node)
                                removed++
                            }
                        }
                    }
                    if (removed != drop.size) throw PatchException("removed $removed of ${drop.size} Firebase components")
                }
            }
        },
    )

    execute {
        val provider = mutableClassDefByOrNull("Lcom/asus/launcher/analytics/FirebaseProvider;")
            ?: throw PatchException("FirebaseProvider not found")
        // The provider's call() dispatches on these actions (send_event, reset_local, add_remote_config_listener).
        val call = provider.methods.firstOrNull {
            it.name == "call" && it.returnType == "Landroid/os/Bundle;" &&
                it.parameterTypes.joinToString("") == "Ljava/lang/String;Ljava/lang/String;Landroid/os/Bundle;"
        } ?: throw PatchException("FirebaseProvider.call(String, String, Bundle) not found")
        if (call.implementation?.instructions?.none {
                ((it as? ReferenceInstruction)?.reference as? StringReference)?.string == "send_event"
            } != false
        ) throw PatchException("FirebaseProvider.call does not handle send_event")
        call.addInstructions(
            0,
            """
                const/4 v0, 0x0
                return-object v0
            """,
        )
        val onCreate = provider.methods.firstOrNull { it.name == "onCreate" && it.returnType == "Z" && it.parameterTypes.isEmpty() }
            ?: throw PatchException("FirebaseProvider.onCreate not found")
        onCreate.addInstructions(
            0,
            """
                const/4 v0, 0x1
                return v0
            """,
        )

        // App categories (the drawer's smart groups, folder names): for every installed app the launcher downloads its
        // Google Play page ("[GooglePlayParser]", jsoup), which tells Google the phone's app list. The parser now
        // answers at once what it answers for an app Play doesn't know (no category), so only the launcher's bundled
        // category data is used.
        val playPage = "https://play.google.com/store/apps/details?id="
        val parsers = mutableListOf<Pair<String, Method>>()
        classDefForEach { c ->
            if (c.type.startsWith("Lorg/ungoogled/")) return@classDefForEach
            for (m in c.methods) {
                val strings = m.implementation?.instructions
                    ?.mapNotNull { ((it as? ReferenceInstruction)?.reference as? StringReference)?.string }.orEmpty()
                if (playPage in strings && "[GooglePlayParser]" in strings) parsers += c.type to m
            }
        }
        val (parserType, parserMethod) = parsers.singleOrNull()
            ?: throw PatchException("expected one Google Play page parser, found ${parsers.size}")
        val parser = mutableMethod(parserType, parserMethod)
        val resultType = parser.returnType
        // The result wraps the app's info, made from six strings: package, image, category, website, label, tags.
        val infoInit = parser.implementation!!.instructions.mapNotNull { (it as? ReferenceInstruction)?.reference as? MethodReference }
            .firstOrNull { it.name == "<init>" && it.parameterTypes.size == 6 && it.parameterTypes.all { t -> t == "Ljava/lang/String;" } }
            ?: throw PatchException("Google Play parser: no app info made from six strings")
        val resultInit = parser.implementation!!.instructions.mapNotNull { (it as? ReferenceInstruction)?.reference as? MethodReference }
            .firstOrNull { it.name == "<init>" && it.definingClass == resultType && it.parameterTypes.singleOrNull() == infoInit.definingClass }
            ?: throw PatchException("Google Play parser: no $resultType made from the app info")
        val packageField = mutableClassDefBy(parserType).fields.singleOrNull { it.type == "Ljava/lang/String;" && !AccessFlags.STATIC.isSet(it.accessFlags) }
            ?: throw PatchException("Google Play parser: no package name field")
        if (parser.implementation!!.registerCount - 1 < 8) throw PatchException("Google Play parser has too few registers")
        parser.addInstructions(
            0,
            """
                new-instance v0, $resultType
                new-instance v1, ${infoInit.definingClass}
                move-object/from16 v2, p0
                iget-object v2, v2, $parserType->${packageField.name}:Ljava/lang/String;
                const/4 v3, 0x0
                const/4 v4, 0x0
                const/4 v5, 0x0
                const/4 v6, 0x0
                const/4 v7, 0x0
                invoke-direct/range { v1 .. v7 }, $infoInit
                invoke-direct { v0, v1 }, $resultInit
                return-object v0
            """,
        )
        log.info("Google Play page parser: $parserType->${parserMethod.name}")
    }
}

/**
 * Off ZenUI, ASUS Launcher dies on Android's hidden API, which only a system app may use (it has no ASUS framework
 * classes). The hidden API goes through Members (see hiddenApisPatch). Recents and navigation gestures stay the
 * phone's own: Android gives them only to the system's home app.
 */
@Suppress("unused")
val enableNonZenUiPatch = bytecodePatch(
    name = "Enable non-ZenUI ROM support",
    description = "Makes ASUS Launcher usable as a home screen on non-ZenUI ROMs.",
    default = true,
) {
    compatibleWith(COMPATIBILITY_ASUS_LAUNCHER)
    dependsOn(hiddenApisPatch, systemPrivilegesPatch, telemetryPatch)
    extendWith(ASUS_LAUNCHER_EXTENSION)
}
