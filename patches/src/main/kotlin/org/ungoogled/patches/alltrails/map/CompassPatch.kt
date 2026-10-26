package org.ungoogled.patches.alltrails.map

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.extensions.InstructionExtensions.addInstructionsWithLabels
import app.morphe.patcher.fieldAccess
import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.patch.resourcePatch
import app.morphe.patcher.util.smali.ExternalLabel
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.NarrowLiteralInstruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.TwoRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.WideLiteralInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.iface.reference.StringReference
import com.android.tools.smali.dexlib2.iface.reference.TypeReference
import org.ungoogled.patches.alltrails.profile.experimentalScreenPatch
import org.ungoogled.patches.alltrails.profile.markPatched
import org.ungoogled.patches.shared.Constants.COMPATIBILITY_ALLTRAILS
import org.ungoogled.patches.shared.addInstructionsAtLabel
import java.io.File

private const val COMPASS = "Lorg/ungoogled/alltrails/Compass;"
private const val NORTH_ICON = "morphe_compass_north"

/** 360f, the compass rotation's `360 - bearing`. */
private const val FULL_TURN = 0x43b40000L

/** 45.0, the recording camera's heading-up tilt. */
private const val TILT_45 = 0x4046800000000000L

/** MapCameraState.copy's default-mask for "all but pitch and navigatorIsRecording", as pause and resume use it. */
private const val PITCH_AND_RECORDING = 0x37f7f

private val Instruction.methodReference get() = (this as? ReferenceInstruction)?.reference as? MethodReference
private val Instruction.fieldReference get() = (this as? ReferenceInstruction)?.reference as? FieldReference
private fun Method.code() = implementation?.instructions?.toList() ?: emptyList()

/**
 * The north-up compass: AllTrails' needle (an arrow above the N and one below)
 * without its south arrow, moved down to stay centred -- a red tip over the N,
 * the icon Google Maps shows while its navigation is north-up. Made from each
 * version of the needle (day, and night with its light N), next to it.
 */
private val northIconPatch = resourcePatch(
    description = "Adds the north-up compass icon.",
) {
    execute {
        decode("res/drawable*/compass_arrow.xml")
        val needles = this["res"].listFiles()!!.filter { it.isDirectory && it.name.startsWith("drawable") }
            .map { File(it, "compass_arrow.xml") }.filter { it.isFile }
        if (needles.none { it.parentFile.name == "drawable" }) throw PatchException("drawable/compass_arrow.xml not found")
        for (needle in needles) {
            val text = needle.readText()
            val paths = Regex("<path\\b[^>]*/>").findAll(text).map { it.value }.toList()
            if (paths.size != 3 || !text.contains("android:viewportHeight=\"24.0\"")) {
                throw PatchException("${needle.parentFile.name}/compass_arrow is no longer a 24-unit north arrow, N and south arrow")
            }
            // Built line by line: the decoded needle puts each attribute on its own line, and a
            // multi-line path inside a trimIndent() template would indent the XML declaration.
            val north = listOf(
                "<?xml version=\"1.0\" encoding=\"utf-8\"?>",
                "<vector xmlns:android=\"http://schemas.android.com/apk/res/android\"",
                "    android:width=\"24.0dp\" android:height=\"24.0dp\"",
                "    android:viewportWidth=\"24.0\" android:viewportHeight=\"24.0\">",
                "    <group android:translateY=\"3.7\">",
                "        " + paths[0].replace(Regex("\\s+"), " "),
                "        " + paths[1].replace(Regex("\\s+"), " "),
                "    </group>",
                "</vector>",
            )
            File(needle.parentFile, "$NORTH_ICON.xml").writeText(north.joinToString("\n", postfix = "\n"))
        }
    }
}

/** CameraConfiguration's reducer types; the TILT_AND_PERSPECTIVE constant carries the recording camera's reducer. */
private object CameraReducerTypesFingerprint : Fingerprint(
    name = "<clinit>",
    strings = listOf("CLASSIC", "TILT_AND_PERSPECTIVE"),
)

/** MapDisplayFragmentViewModel: the class of its post-view-created setup. */
private object MapDisplayViewModelFingerprint : Fingerprint(
    strings = listOf("MapDisplayFragmentViewModel", "postViewCreatedSetup"),
)

/** CompassButtonComponent(onClick, modifier, rotation): the compass button of every Compose map screen. */
private object CompassButtonFingerprint : Fingerprint(
    returnType = "V",
    parameters = listOf(
        "Lkotlin/jvm/functions/Function0;",
        "Landroidx/compose/ui/Modifier;",
        "I",
        "Landroidx/compose/runtime/Composer;",
        "I",
        "I",
    ),
    filters = listOf(fieldAccess(opcode = Opcode.SGET, name = "compass_arrow", type = "I")),
)

private fun MethodReference.matches(other: MethodReference) = name == other.name && returnType == other.returnType &&
    parameterTypes.map(CharSequence::toString) == other.parameterTypes.map(CharSequence::toString)

/** What [reference] runs: the function a synthetic accessor forwards to, or the method itself. */
private fun BytecodePatchContext.forwardedTo(reference: MethodReference): MethodReference {
    val method = classDefBy(reference.definingClass).methods.single { it.matches(reference) }
    if (!AccessFlags.SYNTHETIC.isSet(method.accessFlags)) return reference
    return method.code().mapNotNull { it.methodReference }.singleOrNull()
        ?: throw PatchException("${reference.name} is no longer an accessor")
}

private fun BytecodePatchContext.method(reference: MethodReference) =
    mutableClassDefBy(reference.definingClass).methods.single { it.matches(reference) }

private fun Method.hasWideLiteral(value: Long) = code().any { (it as? WideLiteralInstruction)?.wideLiteral == value }

/**
 * The navigator's compass switches 3D / 2D the way Google Maps' navigation
 * compass does (see Compass in the extension). Once a navigation has started,
 * AllTrails runs its camera with the "tilt and perspective" reducer
 * (CameraStateReducerTiltAndPerspective):
 *
 * 1. Its compass action: a real tap (the view model's compass handler marks
 *    it) switches the mode and follows you in it -- north-up and flat, or
 *    heading-up and tilted -- from any orientation, panned or paused.
 * 2. Its heading-up entry (a navigation starting, re-centring) goes north-up
 *    instead while 2D is the mode, and tilts even when not recording.
 * 3. A pause keeps heading-up tilted.
 * 4. The view model's controls state adds two full turns to the compass
 *    rotation while the navigator's camera is in 2D, and CompassButtonComponent
 *    draws the north-up icon for such a rotation.
 */
@Suppress("unused")
val compassPatch = bytecodePatch(
    name = "2D / 3D compass",
    description = "Adds a 2D / 3D compass switch to the Experimental screen. With it on, tapping the compass in " +
        "navigate mode switches the map between the tilted 3D view and a flat, north-up 2D map the way Google Maps' " +
        "does, from any state (following you, panned away, paused), and brings the map back to you. The compass " +
        "shows a single north arrow in 2D, and the next navigation starts in the mode you used last.",
    default = true,
) {
    compatibleWith(COMPATIBILITY_ALLTRAILS)
    dependsOn(experimentalScreenPatch, northIconPatch)

    execute {
        markPatched(COMPASS)

        // The navigator's camera reducer: TILT_AND_PERSPECTIVE(reducer, setter).
        val types = CameraReducerTypesFingerprint.method
        val tiltName = types.code().indexOfFirst { ((it as? ReferenceInstruction)?.reference as? StringReference)?.string == "TILT_AND_PERSPECTIVE" }
        val constructor = types.code().withIndex().drop(tiltName).first { it.value.opcode == Opcode.INVOKE_DIRECT }
        val constructorRef = constructor.value.methodReference!!
        val reducerArgument = constructorRef.parameterTypes.indexOfFirst { it.toString().let { t -> t != "Ljava/lang/String;" && t != "I" && t != "Lkotlin/jvm/functions/Function2;" } }
        if (reducerArgument < 0) throw PatchException("reducer type constructor no longer takes a reducer: ${constructorRef.parameterTypes}")
        val call = constructor.value as FiveRegisterInstruction
        // Registers C, D, E... hold `this` and then the arguments in order.
        val reducerRegister = listOf(call.registerC, call.registerD, call.registerE, call.registerF, call.registerG)[reducerArgument + 1]
        val reducerType = types.code().take(constructor.index).last {
            it.opcode == Opcode.SGET_OBJECT && (it as OneRegisterInstruction).registerA == reducerRegister
        }.fieldReference!!.type
        val reduce = classDefBy(reducerType).methods.single {
            it.parameterTypes.size == 3 && it.returnType == it.parameterTypes[0].toString() && it.returnType != "Ljava/lang/Object;" &&
                !AccessFlags.BRIDGE.isSet(it.accessFlags)
        }
        val state = reduce.returnType
        val code = reduce.code()

        // The compass branch comes first: `if (action is Compass) { val o = state.orientation; ... }`.
        val compassCheck = code.indexOfFirst { it.opcode == Opcode.INSTANCE_OF }
        val compassAction = ((code[compassCheck] as ReferenceInstruction).reference as TypeReference).type
        val branch = compassCheck + 2
        val orientationGetter = code[branch].methodReference?.takeIf {
            code[compassCheck + 1].opcode == Opcode.IF_EQZ && code[branch].opcode == Opcode.INVOKE_VIRTUAL &&
                it.definingClass == state && it.parameterTypes.isEmpty()
        } ?: throw PatchException("compass branch no longer starts by reading the orientation")
        val stateRegister = (code[branch] as FiveRegisterInstruction).registerC
        // Both are written by the branch before it reads them: the orientation, then its first type check.
        val orientationRegister = (code[branch + 1] as? OneRegisterInstruction)?.registerA?.takeIf { code[branch + 1].opcode == Opcode.MOVE_RESULT_OBJECT }
        val scratchRegister = (code[branch + 2] as? TwoRegisterInstruction)?.takeIf {
            code[branch + 2].opcode == Opcode.INSTANCE_OF && it.registerB == orientationRegister
        }?.registerA
        if (orientationRegister == null || scratchRegister == null || maxOf(orientationRegister, scratchRegister, stateRegister) > 15 ||
            stateRegister in setOf(orientationRegister, scratchRegister)
        ) throw PatchException("compass branch no longer frees two registers")

        // Its two transitions, in order: to north-up (flat) following, to heading-up following.
        val transitions = code.drop(branch).filter { insn ->
            insn.opcode == Opcode.INVOKE_STATIC && insn.methodReference!!.let {
                it.returnType == state && it.parameterTypes.size == 2 && it.parameterTypes[1].toString() == state
            }
        }.take(2).map { it.methodReference!! }
        if (transitions.size != 2) throw PatchException("navigator camera no longer switches orientation on a compass tap")
        val (toNorthUp, toHeadingUp) = transitions
        val northUpFunction = method(forwardedTo(toNorthUp))
        val headingUpFunction = method(forwardedTo(toHeadingUp))
        if (northUpFunction.hasWideLiteral(TILT_45) || !headingUpFunction.hasWideLiteral(TILT_45)) {
            throw PatchException("compass transitions are no longer north-up (flat) then heading-up (tilted 45°)")
        }
        fun instance(type: String) = classDefBy(type).fields.single { AccessFlags.STATIC.isSet(it.accessFlags) && it.type == type }
            .let { "$type->${it.name}:$type" }
        val northUp = instance(toNorthUp.parameterTypes[0].toString())
        val headingUp = instance(toHeadingUp.parameterTypes[0].toString())

        // 3. The pause: in heading-up, the state copy that sets pitch 0.0 and clears navigatorIsRecording
        //    (the copy mask 0x37f7f; resume uses the same mask with 45.0).
        val pausePitch = code.indices.filter { i ->
            code[i].opcode == Opcode.INVOKE_STATIC &&
                code[i].methodReference!!.let { it.definingClass == "Ljava/lang/Double;" && it.name == "valueOf" } &&
                code[i + 1].opcode == Opcode.MOVE_RESULT_OBJECT &&
                // The state copy it feeds uses the pause/resume mask...
                code.drop(i + 1).takeWhile { it.methodReference?.returnType != state }
                    .any { (it as? NarrowLiteralInstruction)?.narrowLiteral == PITCH_AND_RECORDING } &&
                // ...and its value is 0.0 (resume's is 45.0).
                (code[i] as FiveRegisterInstruction).registerC.let { input ->
                    (code.take(i).lastOrNull { it.opcode.setsRegister() && (it as? OneRegisterInstruction)?.registerA == input }
                        as? WideLiteralInstruction)?.wideLiteral == 0L
                }
        }.singleOrNull() ?: throw PatchException("the pause no longer flattens heading-up in one place")
        val pitchRegister = (code[pausePitch + 1] as OneRegisterInstruction).registerA
        if (pitchRegister > 15) throw PatchException("pause pitch register out of range")

        method(reduce).apply {
            // Later sites first, so the earlier indices stay put.
            addInstructionsAtLabel(
                pausePitch + 2,
                """
                    invoke-static { v$pitchRegister }, $COMPASS->pausedPitch(Ljava/lang/Double;)Ljava/lang/Double;
                    move-result-object v$pitchRegister
                """,
            )
            // 1. The compass action.
            addInstructionsWithLabels(
                branch,
                """
                    invoke-virtual { v$stateRegister }, $orientationGetter
                    move-result-object v$orientationRegister
                    sget-object v$scratchRegister, $northUp
                    invoke-static { v$orientationRegister, v$scratchRegister }, $COMPASS->tap(Ljava/lang/Object;Ljava/lang/Object;)I
                    move-result v$scratchRegister
                    if-eqz v$scratchRegister, :compass_stock
                    if-ltz v$scratchRegister, :compass_3d
                    sget-object v$orientationRegister, $northUp
                    invoke-static { v$orientationRegister, v$stateRegister }, $toNorthUp
                    move-result-object v$orientationRegister
                    return-object v$orientationRegister
                    :compass_3d
                    sget-object v$orientationRegister, $headingUp
                    invoke-static { v$orientationRegister, v$stateRegister }, $toHeadingUp
                    move-result-object v$orientationRegister
                    return-object v$orientationRegister
                """,
                ExternalLabel("compass_stock", implementation!!.instructions[branch]),
            )
        }

        // The reducer type: the enum's TILT_AND_PERSPECTIVE constant, and the map view model's camera
        // controller, which holds the current type (CameraConfiguration) and switches it (k).
        val reducerEnum = types.definingClass
        val tiltConstant = types.code().drop(constructor.index).first { it.opcode == Opcode.SPUT_OBJECT }.fieldReference!!
        val viewModel = mutableClassDefBy(MapDisplayViewModelFingerprint.classDef.type)
        fun Method.takesReducerType() = returnType == "V" && parameterTypes.map(CharSequence::toString) == listOf(reducerEnum)
        val controllerField = viewModel.fields.firstOrNull { f -> classDefByOrNull(f.type)?.methods?.any { it.takesReducerType() } == true }
            ?: throw PatchException("map view model no longer holds a camera controller")
        val controller = classDefBy(controllerField.type)
        val setReducer = controller.methods.single { it.takesReducerType() }
        val (configField, reducerGetter) = controller.methods.map { it.code() }.firstNotNullOfOrNull { c ->
            val cast = c.indexOfFirst { it.opcode == Opcode.CHECK_CAST && (it as ReferenceInstruction).reference.toString() == reducerEnum }
            if (cast < 0) return@firstNotNullOfOrNull null
            val getter = c.take(cast).last { it.opcode == Opcode.INVOKE_VIRTUAL }.methodReference!!
            val config = c.take(cast).lastOrNull { it.opcode == Opcode.IGET_OBJECT && it.fieldReference!!.type == getter.definingClass }?.fieldReference
            config?.let { it to getter }
        } ?: throw PatchException("camera controller no longer reads its reducer type from its configuration")

        // The map's compass handler (onCompassButtonClicked). After a tap on the navigator's compass it
        // first turns on the navigation camera, which AllTrails itself does only at Start and Resume --
        // a recording it reopens paused still has the plain map camera, which only turns the map north.
        val handler = viewModel.methods.filter { m ->
            m.parameterTypes.isEmpty() && m.code().any { it.opcode == Opcode.SGET_OBJECT && it.fieldReference?.definingClass == compassAction }
        }.singleOrNull() ?: throw PatchException("view model no longer has one compass handler")
        if (handler.implementation!!.registerCount - 1 < 2) throw PatchException("compass handler has no free registers")
        handler.addInstructionsWithLabels(
            0,
            """
                invoke-static { }, $COMPASS->useNavigationCamera()Z
                move-result v0
                if-eqz v0, :compass_camera
                iget-object v0, p0, $controllerField
                sget-object v1, $tiltConstant
                invoke-virtual { v0, v1 }, $setReducer
            """,
            ExternalLabel("compass_camera", handler.implementation!!.instructions[0]),
        )

        // The navigator's compass (NavigatorTopControls, both layouts): its onClick lambdas call the
        // handler; each marks the tap as the navigator's first.
        val navigatorCalls = mutableListOf<Pair<String, Method>>()
        classDefForEach { classDef ->
            if (classDef.sourceFile != "NavigatorTopControls.kt") return@classDefForEach
            classDef.methods.filter { m -> m.code().any { it.methodReference?.let { r -> r.definingClass == viewModel.type && r.matches(handler) } == true } }
                .forEach { navigatorCalls += classDef.type to it }
        }
        if (navigatorCalls.isEmpty()) throw PatchException("navigator's compass no longer calls the map's compass handler")
        for ((type, found) in navigatorCalls) {
            val lambda = mutableClassDefBy(type).methods.single { it.matches(found) }
            lambda.code().withIndex().filter { (_, insn) -> insn.methodReference?.let { r -> r.definingClass == viewModel.type && r.matches(handler) } == true }
                .map { it.index }.reversed().forEach { lambda.addInstructionsAtLabel(it, "invoke-static { }, $COMPASS->navigatorTapped()V") }
        }

        // 2. Heading-up entry. A static of (orientation, state): at index 0 every local is free.
        headingUpFunction.apply {
            val headingCode = code()
            val recording = headingCode.indices.firstOrNull { i ->
                (headingCode[i].opcode == Opcode.INVOKE_VIRTUAL || headingCode[i].opcode == Opcode.INVOKE_VIRTUAL_RANGE) &&
                    headingCode[i].methodReference!!.let { m -> m.definingClass == state && m.parameterTypes.isEmpty() && m.returnType == "Z" } &&
                    headingCode[i + 1].opcode == Opcode.MOVE_RESULT
            } ?: throw PatchException("heading-up no longer tilts by navigatorIsRecording")
            val recordingRegister = (headingCode[recording + 1] as OneRegisterInstruction).registerA
            addInstructionsAtLabel(
                recording + 2,
                """
                    invoke-static { v$recordingRegister }, $COMPASS->tilt(Z)Z
                    move-result v$recordingRegister
                """,
            )
            if (implementation!!.registerCount - 2 < 2) throw PatchException("heading-up function has no free registers")
            addInstructionsWithLabels(
                0,
                """
                    invoke-static { }, $COMPASS->keepNorthUp()Z
                    move-result v0
                    if-eqz v0, :heading_up
                    sget-object v0, $northUp
                    move-object/from16 v1, p1
                    invoke-static { v0, v1 }, $toNorthUp
                    move-result-object v0
                    return-object v0
                """,
                ExternalLabel("heading_up", implementation!!.instructions[0]),
            )
        }

        // 4. The controls state's compass rotation, `360f - bearing`, and this map camera's reducer type:
        //    the navigation camera ("tilt and perspective") is only ever the navigator's.
        val controls = viewModel.methods.filter { m ->
            m.code().zipWithNext().any { (a, b) -> (a as? NarrowLiteralInstruction)?.narrowLiteral?.toLong() == FULL_TURN && b.opcode == Opcode.SUB_FLOAT_2ADDR }
        }.singleOrNull() ?: throw PatchException("controls state no longer computes 360 - bearing once")
        controls.apply {
            val controlsCode = code()
            val subtract = controlsCode.indices.first { i ->
                i > 0 && controlsCode[i].opcode == Opcode.SUB_FLOAT_2ADDR &&
                    (controlsCode[i - 1] as? NarrowLiteralInstruction)?.narrowLiteral?.toLong() == FULL_TURN
            }
            val rotation = (controlsCode[subtract] as TwoRegisterInstruction).registerA
            val bearing = (controlsCode[subtract] as TwoRegisterInstruction).registerB
            // The bearing register is written again right after (the next call's result), so it's free.
            val next = controlsCode[subtract + 2]
            if (next.opcode != Opcode.MOVE_RESULT || (next as OneRegisterInstruction).registerA != bearing) {
                throw PatchException("the bearing register is no longer reused after 360 - bearing")
            }
            if (maxOf(rotation, bearing) > 15) throw PatchException("controls state registers out of range")
            addInstructionsAtLabel(
                subtract + 1,
                """
                    move-object/from16 v$bearing, p0
                    iget-object v$bearing, v$bearing, $controllerField
                    iget-object v$bearing, v$bearing, $configField
                    invoke-virtual { v$bearing }, $reducerGetter
                    move-result-object v$bearing
                    invoke-interface { v$bearing }, Lkotlinx/coroutines/flow/MutableStateFlow;->getValue()Ljava/lang/Object;
                    move-result-object v$bearing
                    invoke-static { v$rotation, v$bearing }, $COMPASS->rotation(FLjava/lang/Object;)F
                    move-result v$rotation
                """,
            )
        }

        // 5. The icon: after `sget vX, R.drawable.compass_arrow`, the rotation is the int the
        //    component just turned into the rotate() angle.
        CompassButtonFingerprint.let { fp ->
            val iconCode = fp.method.code()
            val load = fp.instructionMatches.first().index
            val icon = (iconCode[load] as OneRegisterInstruction).registerA
            val toFloat = iconCode.take(load).indexOfLast { it.opcode == Opcode.INT_TO_FLOAT }
            if (toFloat < 0 || iconCode[toFloat + 1].methodReference?.name != "rotate") {
                throw PatchException("compass button no longer rotates by its rotation argument before loading the icon")
            }
            val angle = (iconCode[toFloat] as TwoRegisterInstruction).registerB
            if (maxOf(icon, angle) > 15) throw PatchException("compass button registers out of range")
            fp.method.addInstructionsAtLabel(
                load + 1,
                """
                    invoke-static { v$icon, v$angle }, $COMPASS->icon(II)I
                    move-result v$icon
                """,
            )
        }
    }
}
