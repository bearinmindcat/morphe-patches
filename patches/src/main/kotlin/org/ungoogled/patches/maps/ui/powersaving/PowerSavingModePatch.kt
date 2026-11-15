package org.ungoogled.patches.maps.ui.powersaving

import org.ungoogled.patches.maps.ui.customization.accountSheet
import org.ungoogled.patches.maps.ui.mapsId
import app.morphe.patcher.resource.ResourceType
import app.morphe.patcher.Fingerprint
import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.extensions.InstructionExtensions.addInstructionsWithLabels
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.patch.resourcePatch
import app.morphe.patcher.string
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod.Companion.toMutable
import app.morphe.patcher.util.smali.ExternalLabel
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.builder.MutableMethodImplementation
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.TwoRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.formats.Instruction35c
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.iface.reference.StringReference
import com.android.tools.smali.dexlib2.iface.reference.TypeReference
import com.android.tools.smali.dexlib2.immutable.ImmutableMethod
import org.ungoogled.patches.maps.ui.activityContextHookPatch
import org.ungoogled.patches.maps.ui.customization.customizationScreenPatch
import org.ungoogled.patches.maps.ui.markPatched
import org.ungoogled.patches.maps.ui.navzoom.navigationCameraHookPatch
import org.ungoogled.patches.maps.ui.refreshrate.frameRateHookPatch
import org.ungoogled.patches.maps.ui.sharedExtensionPatch
import org.ungoogled.patches.shared.Constants.COMPATIBILITY_MAPS
import org.w3c.dom.Element
import org.ungoogled.patches.shared.addInstructionsAtLabel
import com.android.tools.smali.dexlib2.builder.BuilderOffsetInstruction
import com.android.tools.smali.dexlib2.iface.instruction.WideLiteralInstruction

/** org.ungoogled.ui.PowerSaving, the extension half: stands in for Pixel's SystemUI. */
private const val POWER_SAVING = "Lorg/ungoogled/ui/PowerSaving;"

/** The account sheet row's listener: opens Power Saving Options with the sheet left open behind it. */
private const val OPEN_POWER = "Lorg/ungoogled/ui/CustomizationActivity\$OpenPower;"
/** Builds the row's icon from [BATTERY_ICON]. */
private const val POWER_ROW_ICON = "Lorg/ungoogled/ui/CustomizationActivity;->powerRowIcon(Landroid/graphics/drawable/Drawable;)Landroid/graphics/drawable/Drawable;"
/** drawable/gs_battery_full_fill1_vd_theme_24: Maps' solid battery, the row's icon is cut from it. */
private val BATTERY_ICON get() = mapsId(ResourceType.DRAWABLE, "gs_battery_full_fill1_vd_theme_24")

/** Maps' own power saving screen. Its name is not obfuscated. */
private const val MIN_MODE_ACTIVITY = "Lcom/google/android/apps/gmm/features/minmode/MinModeActivity;"

/**
 * Pixel's device check: whether com.android.systemui ships
 * config_minmode_enabled. False on every other phone. The resource name occurs once.
 */
private object MinModeDeviceCheckFingerprint : Fingerprint(
    returnType = "Z",
    parameters = emptyList(),
    filters = listOf(string("config_minmode_enabled")),
)

/**
 * Where Maps arms or disarms min mode: writes "minModeOn" to SystemUI's
 * min-mode provider and, when arming, hands it a binder ("minmode_binder" via
 * "setBinder"). Called when navigation starts with Power saving mode on, and
 * again when it ends, the setting goes off or the window shrinks.
 */
private object MinModeArmFingerprint : Fingerprint(
    returnType = "V",
    parameters = listOf("Z"),
    filters = listOf(string("minModeOn"), string("minmode_binder"), string("setBinder")),
)

/**
 * The speedometer's dump (SpeedLimitManager), which prints the speed limit it holds
 * right after reading it: "speedLimit: " + state.limit.
 */
private object SpeedLimitDumpFingerprint : Fingerprint(
    filters = listOf(string("speedLimit: "), string("currentAverageSpeed: ")),
)

/**
 * Navigation's state, made for every location update while guiding: the routes and each one's progress.
 * Its constructor checks that the selected route is the guided one -- by reading that route's progress.
 */
private object NavigationStateFingerprint : Fingerprint(
    returnType = "V",
    filters = listOf(string("routes size == route states size"), string("selected route == guided route")),
    custom = { method, _ -> method.name == "<init>" },
)

/** A route's progress, by its dump: the distance to the next turn, the step being driven, on the route or not. */
private object RouteProgressDumpFingerprint : Fingerprint(
    returnType = "Ljava/lang/String;",
    parameters = emptyList(),
    filters = listOf(string("curStep"), string("metersToNextStep"), string("secondsToNextStep"), string("isOnRoute")),
    custom = { method, _ -> method.name == "toString" },
)

/** string/MIN_MODE_LANDSCAPE_DISABLED_SNACKBAR_TEXT: "Can't use landscape mode when power saving". */
private val LANDSCAPE_DISABLED_TEXT get() = mapsId(ResourceType.STRING, "MIN_MODE_LANDSCAPE_DISABLED_SNACKBAR_TEXT")

/** The power saving screen is declared portrait only; it follows the phone's rotation like navigation does. */
private val minModeOrientationPatch = resourcePatch(
    description = "Lets the power saving screen turn to landscape.",
) {
    execute {
        document("AndroidManifest.xml").use { manifest ->
            val activities = manifest.getElementsByTagName("activity")
            val minMode = (0 until activities.length).map { activities.item(it) as Element }
                .singleOrNull { it.getAttribute("android:name") == MIN_MODE_ACTIVITY.removePrefix("L").removeSuffix(";").replace('/', '.') }
                ?: throw PatchException("MinModeActivity is no longer declared")
            // MapsActivity's own setting: the sensor, unless the user locked the rotation.
            minMode.setAttribute("android:screenOrientation", "user")
        }
    }
}

@Suppress("unused")
val powerSavingModePatch = bytecodePatch(
    name = "Power saving mode",
    description = "Brings the Pixel-only power saving mode to every phone: while driving with navigation, " +
        "press the power button and Maps shows only key information such as the next turn on a black " +
        "screen. Turn it on or off in Settings > Navigation > Power saving mode. Pixels that have it " +
        "built in keep Google's own version unless Power saving mode is turned on in Power Saving Options, a " +
        "row on the account sheet under Customization, which also has: a navigation button that opens the power saving " +
        "screen without locking the phone, switching to it by itself when idle, the normal screen near turns, " +
        "a speedometer on it, a lower frame rate, and its black map in navigation or all over Maps.",
    default = true,
) {
    compatibleWith(COMPATIBILITY_MAPS)
    // The options' switches are refreshed at every Activity attach; the navigation button,
    // idle switch and frame rate run on the shared navigation and frame rate hooks.
    // Power Saving Options is a page of the Customization screen.
    dependsOn(
        sharedExtensionPatch, activityContextHookPatch, navigationCameraHookPatch, frameRateHookPatch, customizationScreenPatch,
        minModeOrientationPatch,
    )

    execute {
        markPatched("powerSavingPatched")

        // 1. Availability. Maps offers the feature only when a server flag is on AND Pixel's SystemUI has
        //    min mode. Since 26.43 the question is asked inline, in one case of a provider switch:
        //
        //        if (serverFlag) { ... available = deviceCheck(), cached ... } else available = false
        //        return Boolean.valueOf(available)
        //
        //    The boxed answer goes through the extension, which says yes when Power saving mode is on in
        //    Power Saving Options and leaves Google's answer otherwise. The box is the one the server
        //    flag's test jumps to: the first Boolean.valueOf at or after its target.
        val check = MinModeDeviceCheckFingerprint.method
        val callers = mutableListOf<Pair<String, com.android.tools.smali.dexlib2.iface.Method>>()
        classDefForEach { classDef ->
            if (classDef.type.startsWith("Lorg/ungoogled/")) return@classDefForEach
            for (method in classDef.methods) {
                val calls = method.implementation?.instructions?.any { insn ->
                    val ref = (insn as? ReferenceInstruction)?.reference as? MethodReference
                    ref != null && ref.definingClass == check.definingClass && ref.name == check.name &&
                        ref.returnType == "Z" && ref.parameterTypes.isEmpty()
                } ?: false
                if (calls && classDef.type != check.definingClass) callers += classDef.type to method
            }
        }
        val (ownerType, asker) = callers.singleOrNull()
            ?: throw PatchException("expected one caller of the min-mode device check, found ${callers.size}")
        mutableClassDefBy(ownerType).methods
            .single { it.name == asker.name && it.parameterTypes == asker.parameterTypes && it.returnType == asker.returnType }
            .apply {
                val code = implementation!!.instructions
                // From the device check call back to the server flag's test (an iget-boolean, then if-eqz).
                val first = code.indices.firstOrNull { i ->
                    ((code[i] as? ReferenceInstruction)?.reference as? MethodReference)?.let {
                        it.definingClass == check.definingClass && it.name == check.name && it.parameterTypes.isEmpty()
                    } == true
                } ?: throw PatchException("min-mode availability: the device check call not found")
                val flagTest = (first - 1 downTo 1).firstOrNull { i ->
                    code[i].opcode == Opcode.IF_EQZ && code[i - 1].opcode == Opcode.IGET_BOOLEAN
                } ?: throw PatchException("min-mode availability: the server flag's test not found")
                val target = (code[flagTest] as BuilderOffsetInstruction).target.location.index
                val box = (target until minOf(code.size, target + 6)).firstOrNull { i ->
                    ((code[i] as? ReferenceInstruction)?.reference as? MethodReference)?.let {
                        code[i].opcode == Opcode.INVOKE_STATIC && it.definingClass == "Ljava/lang/Boolean;" && it.name == "valueOf"
                    } == true
                } ?: throw PatchException("min-mode availability: the answer's Boolean.valueOf not found")
                val answer = (code[box] as Instruction35c).registerC
                addInstructionsAtLabel(
                    box,
                    """
                        invoke-static/range { v$answer .. v$answer }, $POWER_SAVING->available(Z)Z
                        move-result v$answer
                    """,
                )
            }

        // 2. Arming. Let the extension know, so it can take SystemUI's part: open the
        //    power saving screen when the power button turns the screen off.
        MinModeArmFingerprint.method.addInstructions(
            0,
            "invoke-static/range { p1 .. p1 }, $POWER_SAVING->armed(Z)V",
        )

        // 3. The power saving screen wakes the display over the lock screen and keeps it
        //    on, which Pixel's SystemUI would otherwise arrange. Right after super.onCreate.
        mutableClassDefBy(MIN_MODE_ACTIVITY).methods
            .single { it.name == "onCreate" && it.parameterTypes == listOf("Landroid/os/Bundle;") }
            .apply {
                // invoke-super/range here: `this` is v18, beyond the short form's reach.
                val superCall = implementation!!.instructions.indexOfFirst { insn ->
                    (insn.opcode == Opcode.INVOKE_SUPER || insn.opcode == Opcode.INVOKE_SUPER_RANGE) &&
                        ((insn as ReferenceInstruction).reference as MethodReference).name == "onCreate"
                }
                if (superCall < 0) throw PatchException("MinModeActivity.onCreate no longer calls super.onCreate")
                addInstructions(
                    superCall + 1,
                    "invoke-static/range { p0 .. p0 }, $POWER_SAVING->onMinModeCreate(Landroid/app/Activity;)V",
                )
            }

        // 4. Speedometer: the speed limit Maps' own speedometer is showing. Its dump reads
        //    the limit field just before printing "speedLimit: "; the one method that
        //    stores into that field takes (limit, units) -- the speedometer's setter, run
        //    for every location update while navigating. The extension gets both first.
        val limitField = SpeedLimitDumpFingerprint.let { fp ->
            val instructions = fp.method.implementation!!.instructions.toList()
            val label = fp.instructionMatches.first().index
            (label - 1 downTo 0).map { instructions[it] }.firstOrNull { insn ->
                insn.opcode == Opcode.IGET && ((insn as ReferenceInstruction).reference as FieldReference).type == "I"
            }?.let { (it as ReferenceInstruction).reference as FieldReference }
                ?: throw PatchException("speedometer dump no longer reads the speed limit before printing it")
        }
        val limitSetters = mutableListOf<Pair<String, com.android.tools.smali.dexlib2.iface.Method>>()
        classDefForEach { classDef ->
            if (classDef.type.startsWith("Lorg/ungoogled/")) return@classDefForEach
            for (method in classDef.methods) {
                if (method.returnType != "V" || method.parameterTypes.size != 2 || method.parameterTypes[0].toString() != "I") continue
                if (AccessFlags.STATIC.isSet(method.accessFlags)) continue
                val stores = method.implementation?.instructions?.any { insn ->
                    insn.opcode == Opcode.IPUT && ((insn as ReferenceInstruction).reference as FieldReference).let { f ->
                        f.definingClass == limitField.definingClass && f.name == limitField.name && f.type == "I"
                    }
                } ?: false
                if (stores) limitSetters += classDef.type to method
            }
        }
        val (setterType, setter) = limitSetters.singleOrNull()
            ?: throw PatchException("expected one speed limit setter, found ${limitSetters.size}")
        mutableClassDefBy(setterType).methods
            .single { it.name == setter.name && it.returnType == "V" &&
                it.parameterTypes.map { t -> t.toString() } == setter.parameterTypes.map { t -> t.toString() } }
            .addInstructions(0, "invoke-static/range { p1 .. p2 }, $POWER_SAVING->speedLimit(ILjava/lang/Object;)V")

        // 5. Power saving theme everywhere: the power saving screen's black map is one of
        //    Maps' base styles (NAVIGATION_MIN_MODE, and _AUTO while driving), picked by the
        //    map's style chooser when its "min mode" flag is set -- which MinModeActivity
        //    does while it shows. The flag goes through the extension on its way into that
        //    decision. Found from the style enum's own constant names: the chooser is the one
        //    method returning that enum which reads both min-mode constants.
        val styles = mutableListOf<Triple<String, String, String>>()   // enum, NAVIGATION_MIN_MODE, _AUTO
        classDefForEach { classDef ->
            val clinit = classDef.methods.firstOrNull { it.name == "<clinit>" } ?: return@classDefForEach
            val instructions = clinit.implementation?.instructions?.toList() ?: return@classDefForEach
            fun constantAfter(name: String): String? {
                val at = instructions.indexOfFirst { insn ->
                    (insn.opcode == Opcode.CONST_STRING || insn.opcode == Opcode.CONST_STRING_JUMBO) &&
                        ((insn as ReferenceInstruction).reference as StringReference).string == name
                }
                if (at < 0) return null
                val store = instructions.drop(at).firstOrNull { insn ->
                    insn.opcode == Opcode.SPUT_OBJECT && ((insn as ReferenceInstruction).reference as FieldReference).let { f ->
                        f.definingClass == classDef.type && f.type == classDef.type
                    }
                } ?: return null
                return ((store as ReferenceInstruction).reference as FieldReference).name
            }
            val minMode = constantAfter("NAVIGATION_MIN_MODE") ?: return@classDefForEach
            val minModeAuto = constantAfter("NAVIGATION_MIN_MODE_AUTO") ?: return@classDefForEach
            styles += Triple(classDef.type, minMode, minModeAuto)
        }
        val choosers = mutableListOf<Pair<Triple<String, String, String>, com.android.tools.smali.dexlib2.iface.Method>>()
        classDefForEach { classDef ->
            for (method in classDef.methods) {
                if (method.parameterTypes.isNotEmpty()) continue
                val style = styles.firstOrNull { it.first == method.returnType } ?: continue
                val reads = method.implementation?.instructions?.mapNotNull { insn ->
                    if (insn.opcode != Opcode.SGET_OBJECT) null
                    else ((insn as ReferenceInstruction).reference as FieldReference).takeIf { it.definingClass == style.first }?.name
                }.orEmpty()
                if (style.second in reads && style.third in reads) choosers += style to method
            }
        }
        val (style, chooserMethod) = choosers.singleOrNull()
            ?: throw PatchException("expected one map style chooser, found ${choosers.size}")
        mutableClassDefBy(chooserMethod.definingClass).methods
            .single { it.name == chooserMethod.name && it.parameterTypes.isEmpty() && it.returnType == style.first }
            .apply {
                // `if (minMode) return driving ? NAVIGATION_MIN_MODE_AUTO : NAVIGATION_MIN_MODE`:
                // the two tests right before the _AUTO constant are driving, then min mode,
                // and both flags are read from fields just before the first test.
                val instructions = implementation!!.instructions.toList()
                val auto = instructions.indexOfFirst { insn ->
                    insn.opcode == Opcode.SGET_OBJECT && ((insn as ReferenceInstruction).reference as FieldReference).let { f ->
                        f.definingClass == style.first && f.name == style.third
                    }
                }
                val tests = (auto - 1 downTo 0).filter { instructions[it].opcode == Opcode.IF_EQZ }.take(2)
                if (tests.size < 2) throw PatchException("map style chooser no longer tests min mode before its style")
                val driving = (instructions[tests[0]] as OneRegisterInstruction).registerA
                val minMode = (instructions[tests[1]] as OneRegisterInstruction).registerA
                fun readOf(register: Int) = (tests[1] - 1 downTo 0).firstOrNull { i ->
                    instructions[i].opcode == Opcode.IGET_BOOLEAN && (instructions[i] as TwoRegisterInstruction).registerA == register
                } ?: throw PatchException("map style chooser's flag in v$register is no longer a field read")
                if (driving == minMode) throw PatchException("map style chooser changed")
                readOf(driving)
                readOf(minMode)
                if (driving > 15 || minMode > 15) throw PatchException("map style chooser's flags are out of reach")
                // A branch landing on the test would skip whatever goes in front of it.
                if (implementation!!.instructions[tests[1]].location.labels.isNotEmpty()) {
                    throw PatchException("map style chooser's min mode test is a branch target")
                }
                // At the min mode test itself: both flags are read by then.
                addInstructions(
                    tests[1],
                    """
                        invoke-static { v$minMode, v$driving }, $POWER_SAVING->minModeStyle(ZZ)Z
                        move-result v$minMode
                    """,
                )
            }

        // 6. Landscape: Maps says "Can't use landscape mode when power saving" whenever the phone is held
        //    sideways; the screen turns with the phone now (manifest, and onMinModeCreate where the manifest
        //    stays stock). The message comes from an orientation listener of its own, silenced here.
        val landscapeListeners = mutableListOf<Pair<String, String>>()
        val landscapeText = LANDSCAPE_DISABLED_TEXT.toLong()
        classDefForEach { c ->
            if (c.superclass != "Landroid/view/OrientationEventListener;") return@classDefForEach
            c.methods.filter { m ->
                m.name == "onOrientationChanged" && m.parameterTypes.map { it.toString() } == listOf("I") &&
                    m.implementation?.instructions?.any { (it as? WideLiteralInstruction)?.wideLiteral == landscapeText } == true
            }.forEach { landscapeListeners += c.type to it.name }
        }
        val (listenerType, listenerMethod) = landscapeListeners.singleOrNull()
            ?: throw PatchException("power saving screen's landscape message: found ${landscapeListeners.size}")
        mutableClassDefBy(listenerType).methods
            .single { it.name == listenerMethod && it.parameterTypes.map { t -> t.toString() } == listOf("I") }
            .addInstructions(0, "return-void")

        // 7. Normal screen near turns: the guided route's distance to its next turn, for every navigation state
        //    Maps makes. The progress class gets a method that hands the distance, the step and "on the route"
        //    to the extension; the state's constructor calls it on the guided route's progress, which it has
        //    in a register right after checking it is the selected route.
        val progressType = RouteProgressDumpFingerprint.classDef.type
        val dump = RouteProgressDumpFingerprint.method.implementation!!.instructions.toList()
        fun labelAt(label: String) = dump.indexOfFirst { insn ->
            (insn.opcode == Opcode.CONST_STRING || insn.opcode == Opcode.CONST_STRING_JUMBO) &&
                ((insn as ReferenceInstruction).reference as StringReference).string == label
        }.also { if (it < 0) throw PatchException("route progress dump no longer prints $label") }
        fun fieldRead(label: String, opcode: Opcode, owner: String?): FieldReference =
            (labelAt(label) - 1 downTo 0).map { dump[it] }.firstNotNullOfOrNull { insn ->
                ((insn as? ReferenceInstruction)?.reference as? FieldReference)?.takeIf {
                    insn.opcode == opcode && (owner == null || it.definingClass == owner)
                }
            } ?: throw PatchException("route progress dump no longer reads a field for $label")
        val meters = fieldRead("metersToNextStep", Opcode.IGET, progressType)
        val onRoute = fieldRead("isOnRoute", Opcode.IGET_BOOLEAN, progressType)
        // `step == null ? -1 : step.index`: the step itself is the object field read for it.
        val step = fieldRead("curStep", Opcode.IGET_OBJECT, progressType)
        if (meters.type != "I" || onRoute.type != "Z") throw PatchException("route progress fields changed type")
        mutableClassDefBy(progressType).methods.add(
            ImmutableMethod(
                progressType, "uaProgress", emptyList(), "V",
                AccessFlags.PUBLIC.value or AccessFlags.FINAL.value, null, null, MutableMethodImplementation(4),
            ).toMutable().apply {
                addInstructions(
                    0,
                    """
                        iget v0, p0, $progressType->${meters.name}:I
                        iget-object v1, p0, $progressType->${step.name}:${step.type}
                        iget-boolean v2, p0, $progressType->${onRoute.name}:Z
                        invoke-static { v0, v1, v2 }, $POWER_SAVING->toNextStep(ILjava/lang/Object;Z)V
                        return-void
                    """,
                )
            },
        )
        NavigationStateFingerprint.method.apply {
            val casts = implementation!!.instructions.withIndex().filter { (_, insn) ->
                insn.opcode == Opcode.CHECK_CAST && ((insn as ReferenceInstruction).reference as TypeReference).type == progressType
            }
            val (at, cast) = casts.singleOrNull()
                ?: throw PatchException("navigation state reads the guided route's progress ${casts.size} times")
            val register = (cast as OneRegisterInstruction).registerA
            if (register > 15) throw PatchException("guided route's progress in v$register, out of invoke range")
            addInstructions(at + 1, "invoke-virtual { v$register }, $progressType->uaProgress()V")
        }
    }

    // A "Power Saving Options" row on the account sheet, right under Customization and so above
    // Local saved. In finalize, after every patch has run, so the sheet's other rows of ours are
    // in place whichever order the patches ran in. Built like the Customization row, its icon cut
    // from Maps' solid battery.
    finalize {
        accountSheet().apply {
            addRowBuilder(
                "uaPowerRow", "Power Saving Options", BATTERY_ICON, OPEN_POWER, listenerTakesContext = true,
                iconTransform = POWER_ROW_ICON,
            )
            placeRow("uaPowerRow", after = listOf("uaCustomizationRow"))
        }
    }
}
