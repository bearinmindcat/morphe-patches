package org.ungoogled.patches.waze

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.extensions.InstructionExtensions.addInstruction
import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.extensions.InstructionExtensions.addInstructionsWithLabels
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.string
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod.Companion.toMutable
import app.morphe.patcher.util.smali.ExternalLabel
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.builder.MutableMethodImplementation
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.NarrowLiteralInstruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.iface.reference.TypeReference
import com.android.tools.smali.dexlib2.immutable.ImmutableMethod
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.patch.intOption
import org.ungoogled.patches.waze.WazeConstants.COMPATIBILITY_WAZE

/** org.ungoogled.waze.Bridge, the runtime half (extensions/waze). */
private const val BRIDGE = "Lorg/ungoogled/waze/Bridge;"

/**
 * AlerterNativeManager.updateAlertersRepository(NativeAlertRepositoryUpdate): the native side's
 * list of the alerts Waze is about to warn about, each time it changes -- AlerterNativeManager's one
 * method taking that JNI proto (renamed in Waze 5.26, where the JNI half parses the bytes first).
 */
private object UpdateAlertersRepositoryFingerprint : Fingerprint(
    definingClass = "Lcom/waze/alerters/AlerterNativeManager;",
    returnType = "V",
    parameters = listOf("Lcom/waze/jni/protos/alerters/NativeAlertRepositoryUpdate;"),
)

/**
 * Waze's start-up (WazeInitializer.initialize, a suspend function on the Application): once, it launches the
 * coroutine that loads the native engine, starts WazeAppService and goes online -- what the splash screen
 * waits for. The only method with this log line.
 */
private object WazeInitializerFingerprint : Fingerprint(
    returnType = "Ljava/lang/Object;",
    filters = listOf(string("initialization already started")),
    custom = { method, _ -> method.parameterTypes.size == 1 },
)

/** com.waze.AIDLService (exported, no permission, in the stock manifest): Maps binds it to start Waze. */
private object AidlServiceBindFingerprint : Fingerprint(
    definingClass = "Lcom/waze/AIDLService;",
    name = "onBind",
    returnType = "Landroid/os/IBinder;",
    parameters = listOf("Landroid/content/Intent;"),
)

private object AidlServiceUnbindFingerprint : Fingerprint(
    definingClass = "Lcom/waze/AIDLService;",
    name = "onUnbind",
    returnType = "Z",
    parameters = listOf("Landroid/content/Intent;"),
)

@Suppress("unused")
val wazeShareAlertsPatch = bytecodePatch(
    name = "Share alerts with Maps",
    description = "Shows Waze alerts in Ungoogled Maps on this phone, which can start Waze for them. Waze must be signed in.",
) {
    compatibleWith(COMPATIBILITY_WAZE)
    // The extension, the Application hook and a switch on Waze's Customization screen.
    dependsOn(wazeCustomizationPatch)

    execute {
        // p0 is the manager, p1 the update; the Bridge takes them as Objects and reads the update by name.
        UpdateAlertersRepositoryFingerprint.method.addInstruction(
            0,
            "invoke-static/range { p0 .. p1 }, $BRIDGE->alerts(Ljava/lang/Object;Ljava/lang/Object;)V",
        )
        WazeApplicationCreateFingerprint.method.addInstruction(
            0,
            "invoke-static { p0 }, $BRIDGE->init(Landroid/app/Application;)V",
        )
        declareWazeToggle("shareAlerts")

        // Issue #207: Maps can start Waze. It binds AIDLService -- exported in Waze's own manifest, so this works on a
        // root mount install too -- with its own action; the bind starts Waze's process (Application.onCreate ->
        // Bridge.init) and, while Maps is on screen, counts as Waze being in the foreground. Bridge.bound answers
        // that action with a binder of its own and runs Waze's start-up, uaInitialize(): the launch half of
        // WazeInitializer.initialize, without waiting for it.
        val initializer = WazeInitializerFingerprint.method
        val app = initializer.definingClass
        val code = initializer.implementation!!.instructions.toList()
        fun Instruction.ref() = (this as? ReferenceInstruction)?.reference
        val started = code.getOrNull(0)?.takeIf { it.opcode == Opcode.IGET_OBJECT }?.ref() as? FieldReference
        val launchAt = code.indexOfFirst { it.opcode == Opcode.INVOKE_STATIC }
        val scope = code.getOrNull(5)?.ref() as? FieldReference
        val lambda = (code.getOrNull(6)?.ref() as? TypeReference)?.type
        val lambdaInit = code.getOrNull(9)?.ref() as? MethodReference
        val function = (code.getOrNull(10)?.ref() as? TypeReference)?.type
        val launch = code.getOrNull(launchAt)?.ref() as? MethodReference
        val shape = listOf(
            Opcode.IGET_OBJECT, Opcode.CONST_4, Opcode.INVOKE_VIRTUAL, Opcode.MOVE_RESULT, Opcode.IF_NEZ,
            Opcode.IGET_OBJECT, Opcode.NEW_INSTANCE, Opcode.CONST_4, Opcode.CONST_4, Opcode.INVOKE_DIRECT,
            Opcode.CHECK_CAST, Opcode.CONST_4, Opcode.INVOKE_STATIC,
        )
        if (code.take(shape.size).map { it.opcode } != shape || started?.type != "Ljava/util/concurrent/atomic/AtomicBoolean;" ||
            scope == null || lambda == null || lambdaInit == null || function == null || launch == null || launchAt != 12 ||
            lambdaInit.parameterTypes.map { it.toString() } != listOf(app, initializer.parameterTypes[0].toString(), "I") ||
            launch.parameterTypes.size != 5
        ) throw PatchException("Waze's start-up no longer launches its coroutine the way this patch knows")
        // The lambda's int argument: whichever of the two constants loads the register the constructor takes last.
        val intRegister = (code[9] as com.android.tools.smali.dexlib2.iface.instruction.formats.Instruction35c).registerE
        val lambdaLabel = listOf(code[7], code[8]).single { (it as OneRegisterInstruction).registerA == intRegister }
            .let { (it as NarrowLiteralInstruction).narrowLiteral }
        val launchMode = (code[11] as NarrowLiteralInstruction).narrowLiteral
        mutableClassDefBy(app).methods.add(
            ImmutableMethod(app, "uaInitialize", emptyList(), "V", AccessFlags.PUBLIC.value, null, null, MutableMethodImplementation(5))
                .toMutable().apply {
                    addInstructions(
                        0,
                        """
                            iget-object v0, p0, $app->${started.name}:${started.type}
                            const/4 v1, 0x1
                            invoke-virtual { v0, v1 }, Ljava/util/concurrent/atomic/AtomicBoolean;->getAndSet(Z)Z
                            move-result v0
                            if-nez v0, :done
                            iget-object v0, p0, $app->${scope.name}:${scope.type}
                            new-instance v1, $lambda
                            const/4 v2, $lambdaLabel
                            const/4 v3, 0x0
                            invoke-direct { v1, p0, v3, v2 }, $lambdaInit
                            check-cast v1, $function
                            const/4 v2, $launchMode
                            invoke-static { v0, v3, v3, v1, v2 }, $launch
                            :done
                            return-void
                        """,
                    )
                },
        )
        // onBind ends `iget-object p0, p0, binder; return-object p0`: Maps' action gets the Bridge's binder instead.
        AidlServiceBindFingerprint.method.apply {
            val ret = implementation!!.instructions.indexOfLast { it.opcode == Opcode.RETURN_OBJECT }
            if (ret < 0 || (implementation!!.instructions[ret] as OneRegisterInstruction).registerA != implementation!!.registerCount - 2) {
                throw PatchException("AIDLService.onBind no longer returns its binder from p0")
            }
            addInstructions(
                ret,
                """
                    invoke-static { p0, p1 }, $BRIDGE->bound(Ljava/lang/Object;Landroid/content/Intent;)Landroid/os/IBinder;
                    move-result-object p0
                """,
            )
        }
        // onUnbind clears the AIDL client's state; not when Maps lets go (p0 is not used again there).
        AidlServiceUnbindFingerprint.method.apply {
            addInstructionsWithLabels(
                0,
                """
                    invoke-static { p1 }, $BRIDGE->ours(Landroid/content/Intent;)Z
                    move-result p0
                    if-eqz p0, :aidl_unbind
                    const/4 p0, 0x0
                    return p0
                """,
                ExternalLabel("aidl_unbind", implementation!!.instructions.first()),
            )
        }
    }
}

@Suppress("unused")
val wazeStayAwakePatch = bytecodePatch(
    name = "Stay awake in the background",
    description = "Keeps Waze running without a route so its alerts keep reaching Maps. Uses more battery.",
    default = false,
) {
    compatibleWith(COMPATIBILITY_WAZE)
    dependsOn(wazeConfigOverridesPatch)

    val minutes by intOption(
        key = "minutes",
        default = 180,
        title = "Minutes in the background",
        description = "Waze: 10.",
        required = true,
    )

    execute {
        keepWazeConfig("stayAwake", "CONFIG_VALUE_POWER_BACKGROUND_TIMEOUT_MS", minutes!! * 60_000)
    }
}
