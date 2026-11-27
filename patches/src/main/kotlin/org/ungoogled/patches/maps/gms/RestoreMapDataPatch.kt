package org.ungoogled.patches.maps.gms

import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.extensions.InstructionExtensions.addInstructionsWithLabels
import app.morphe.patcher.extensions.InstructionExtensions.getInstruction
import app.morphe.patcher.extensions.InstructionExtensions.removeInstruction
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.util.smali.ExternalLabel
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod.Companion.toMutable
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.builder.MutableMethodImplementation
import com.android.tools.smali.dexlib2.builder.instruction.BuilderInstruction10t
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.iface.reference.TypeReference
import com.android.tools.smali.dexlib2.immutable.ImmutableMethod
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodParameter
import org.ungoogled.patches.shared.Constants.COMPATIBILITY_MAPS

/** The package the Maps API key is restricted to. */
private const val GENUINE_PACKAGE = "com.google.android.apps.maps"

/**
 * SHA-1 of the certificate the key is restricted to, as Maps sends it: Google's
 * shared legacy app-signing certificate, upper-case hex without separators.
 */
private const val GENUINE_CERT = "38918A453D07199354F8B19AF05EC6562CED5788"

/** Where the car connection handler's own body goes (step 5). */
private const val MOVED_CAR_HANDLER = "uaOnCarConnected"

/** Attaches the navigation focus client after a refused car connection (step 6). */
private const val NAVIGATION_FOCUS = "uaNavigationFocus"

/** Where the navigation focus client's request body goes (step 6). */
private const val MOVED_FOCUS_REQUEST = "uaFocusRequest"

/** A SecurityException's message as a "UA" warning: `<prefix>` + the exception, in v0/v1. */
private fun logRefusal(prefix: String) = """
    invoke-virtual { v0 }, Ljava/lang/Object;->toString()Ljava/lang/String;
    move-result-object v0
    const-string v1, "$prefix"
    invoke-virtual { v1, v0 }, Ljava/lang/String;->concat(Ljava/lang/String;)Ljava/lang/String;
    move-result-object v0
    const-string v1, "UA"
    invoke-static { v1, v0 }, Landroid/util/Log;->w(Ljava/lang/String;Ljava/lang/String;)I
"""

/**
 * What the car connection handler's last step -- "createAndStartCarMessageManaging", Maps' navigation focus
 * channel -- touches, read from its own instructions:
 * `besw = (besw) client.e.b(new beqm(client, 0)); pxq.f = besw; pya = pxq.b; pya.c = besw; besw.d = pya.e;
 * if (pya.b) { pya.b = false; pya.a(); }` with `client = pxu.c.b()`, and `pxu.d = true` at the very end.
 */
private class CarMessageRefs(handler: Method) {
    private val code: List<Instruction> = handler.implementation!!.instructions.toList()
    private fun field(i: Int) = (code[i] as ReferenceInstruction).reference as FieldReference
    private fun method(i: Int) = (code[i] as ReferenceInstruction).reference as MethodReference
    private fun fail(what: String): Nothing = throw PatchException("car connection handler: $what")
    private fun next(from: Int, what: String, test: (Instruction) -> Boolean) =
        (from until code.size).firstOrNull { test(code[it]) } ?: fail("$what not found")
    private fun Instruction.field() = (this as? ReferenceInstruction)?.reference as? FieldReference
    private fun Instruction.method() = (this as? ReferenceInstruction)?.reference as? MethodReference

    /** check-cast to the message manager, then (after a getClass null check) its store into the holder. */
    private val cast = (0 until code.size - 2).singleOrNull { i ->
        code[i].opcode == Opcode.CHECK_CAST && code[i + 2].opcode == Opcode.IPUT_OBJECT &&
            code[i + 2].field()?.type == ((code[i] as ReferenceInstruction).reference as TypeReference).type
    } ?: fail("message manager store not found")
    val manager = ((code[cast] as ReferenceInstruction).reference as TypeReference).type
    val holderManager = field(cast + 2)
    val holder = holderManager.definingClass
    val call = method(cast - 2).also {
        if (code[cast - 2].opcode != Opcode.INVOKE_VIRTUAL || it.parameterTypes.size != 1 || it.returnType != "Ljava/lang/Object;") fail("executor call changed")
    }
    val callable = method(cast - 3).also {
        if (code[cast - 3].opcode != Opcode.INVOKE_DIRECT || it.name != "<init>" ||
            it.parameterTypes.map(CharSequence::toString) != listOf("Ljava/lang/Object;", "I")) fail("message manager callable changed")
    }
    val executor = field(cast - 5).also {
        if (code[cast - 5].opcode != Opcode.IGET_OBJECT || it.type != call.definingClass) fail("executor field changed")
    }
    val client = method(cast - 7).also {
        if (code[cast - 7].opcode != Opcode.INVOKE_VIRTUAL || it.parameterTypes.isNotEmpty()) fail("car client getter changed")
    }
    private val holderFocus = next(cast + 3, "focus client") { it.opcode == Opcode.IGET_OBJECT && it.field()?.definingClass == holder }
    val holderClient = field(holderFocus)
    val focus = holderClient.type
    val focusManager = field(next(holderFocus, "focus client's manager") {
        it.opcode == Opcode.IPUT_OBJECT && it.field()?.definingClass == focus && it.field()?.type == manager
    })
    private val callbackRead = next(holderFocus, "focus callback") { it.opcode == Opcode.IGET_OBJECT && it.field()?.definingClass == focus && it.field()?.type != manager }
    val focusCallback = field(callbackRead)
    val managerCallback = field(next(callbackRead, "manager's callback") { it.opcode == Opcode.IPUT_OBJECT && it.field()?.definingClass == manager })
    val focusPending = field(next(callbackRead, "pending focus request") { it.opcode == Opcode.IGET_BOOLEAN && it.field()?.definingClass == focus })
    val focusRequest = method(next(callbackRead, "focus request") {
        it.opcode == Opcode.INVOKE_VIRTUAL && it.method()?.definingClass == focus && it.method()?.parameterTypes?.isEmpty() == true &&
            it.method()?.returnType == "V"
    })
    private val outer = field(next(0, "outer handler") { it.opcode == Opcode.IGET_OBJECT && it.field()?.definingClass == handler.definingClass })
    val owner = outer
    val ownerHolder = field(next(0, "holder field") { it.opcode == Opcode.IGET_OBJECT && it.field()?.definingClass == outer.type && it.field()?.type == holder })
    val ownerToken = field(next(0, "token field") { it.opcode == Opcode.IGET_OBJECT && it.field()?.definingClass == outer.type && it.field()?.type == client.definingClass })
    val ownerConnected = code.filter { it.opcode == Opcode.IPUT_BOOLEAN && it.field()?.definingClass == outer.type }
        .singleOrNull()?.field() ?: fail("connected flag not found")
}

internal val restoreMapDataPatch = bytecodePatch(
    description = "Lets a re-signed Maps load tiles, search and routing, by sending Google's own " +
        "package and certificate in the identity headers the Maps backend checks, and by " +
        "degrading instead of crashing when Play services or Android Auto rejects the re-signed app -- " +
        "including skipping a view property that fails for that reason instead of crashing the screen.",
) {
    compatibleWith(COMPATIBILITY_MAPS)

    execute {
        // 1. Identity headers. Make whichever getter supplies each header return
        //    the genuine value. The getters are one-register methods (only
        //    `this`), so v0 is p0 — safe to overwrite because we return at once.
        val headers = IdentityHeadersFingerprint.instructionMatches
        listOf(
            headers[1] to GENUINE_PACKAGE, // X-Android-Package
            headers[3] to GENUINE_CERT,    // X-Android-Cert
        ).forEach { (supplier, genuineValue) ->
            supplier.getMethodCalled().addInstructions(
                0,
                """
                    const-string v0, "$genuineValue"
                    return-object v0
                """,
            )
        }

        // 2. SecurityException from Play services. Route that handler into the
        //    RemoteException handler, which reports the failure to the client
        //    and returns. Both handlers are found through the method's own
        //    try-table by exception type, so no obfuscated name is involved.
        GetRemoteServiceFingerprint.method.apply {
            val tryBlocks = implementation!!.tryBlocks
            fun handlerFor(type: String) = tryBlocks
                .firstOrNull { it.exceptionHandler.exceptionType == type }
                ?.exceptionHandler?.handler?.location
                ?: throw PatchException("getRemoteService has no $type handler")

            val securityHandler = handlerFor("Ljava/lang/SecurityException;")
            val degradeHandler = handlerFor("Landroid/os/RemoteException;").instruction
                ?: throw PatchException("RemoteException handler has no instruction")

            // The handler is `move-exception vX` followed by `throw vX`.
            val throwIndex = securityHandler.index + 1
            if (getInstruction(throwIndex).opcode != Opcode.THROW) {
                throw PatchException("SecurityException handler no longer just rethrows")
            }
            removeInstruction(throwIndex)
            addInstructionsWithLabels(
                throwIndex,
                "goto :degrade",
                ExternalLabel("degrade", degradeHandler),
            )
        }

        // 3. The view DSL's property binder. Its catch handler rethrows any failure
        //    to apply one property as a fatal exception; send it to the loop's
        //    increment instead, so the failing property is skipped and the rest are
        //    still applied. (Returning instead would abandon every remaining property
        //    on the view -- measurably worse: the search field never took focus.)
        //    Only the handler's first instruction is replaced; the rest of it is left
        //    behind as unreachable code, so no label moves.
        ViewPropertyBinderFingerprint.method.apply {
            val tryBlock = implementation!!.tryBlocks.singleOrNull { it.exceptionHandler.exceptionType == "Ljava/lang/Exception;" }
                ?: throw PatchException("property binder no longer has exactly one Exception handler")
            val handler = tryBlock.exceptionHandler.handler.location.index
            val increment = tryBlock.end.location.index
            val instructions = implementation!!.instructions
            if (instructions[handler].opcode != Opcode.MOVE_EXCEPTION) throw PatchException("property binder's handler no longer starts with move-exception")
            if (instructions[increment].opcode != Opcode.ADD_INT_LIT8) throw PatchException("property binder's try range is no longer followed by the loop increment")
            implementation!!.replaceInstruction(handler, BuilderInstruction10t(Opcode.GOTO, implementation!!.newLabelForIndex(increment)))
        }

        // 4. The API key. Maps reads it from the manifest of the package the
        //    X-Android-Package getter names -- Google's, after step 1 -- so it was
        //    really reading stock Maps' manifest, and with stock Maps not installed
        //    the lookup threw and the app died on start. Read it from the app's own
        //    manifest instead, as unpatched Maps does: same key, no dependency on
        //    stock Maps. Inserted ahead of the method's monitor-enter, where p1 is
        //    still the Context (the method reuses that register right after).
        ApiKeyReaderFingerprint.method.addInstructions(
            0,
            """
                invoke-virtual { p1 }, Landroid/content/Context;->getPackageName()Ljava/lang/String;
                move-result-object p2
            """,
        )

        // 5. Android Auto's private car service (issue #10). When the car connects, Maps' car connection handler
        //    reads the car's details through calls Android Auto answers only for a Google-signed app; at a
        //    re-signed Maps the first of them throws "Wrong signature - go/gearhead-retail-device" on the main
        //    thread and Maps dies. The handler's body moves to uaOnCarConnected() and the handler becomes
        //    `try { uaOnCarConnected() } catch (SecurityException e) { log }`: Maps carries on without the car's
        //    details, as before that connection is up. A Google-signed install (a root mount install keeps
        //    stock Maps' signature) is answered and runs the whole handler as before. The refused call comes
        //    before the handler changes any state its disconnect side would have to undo.
        //    Idea from "Skip private Android Auto car connection" in shuili1013's Maps-AA-AnyMode-Morphe
        //    (GPLv3), which skips the handler outright.
        //
        // 6. Navigation focus (issue #52). Maps asks the car for navigation focus before it starts guidance there and
        //    waits up to 15 s for it ("Timed out waiting for navigation focus"), then shows "Something went wrong"
        //    with Retry on the car's route card. Its focus client is handed to the navigation code when Android Auto
        //    starts, but its channel to the car -- Android Auto's car messaging, category 1 -- is attached only by
        //    the handler's last step, which the refused call above skips, so every Navigate timed out. Android Auto
        //    lets any app hold navigation focus (only the other message categories need Google's signature), so
        //    after a refusal uaNavigationFocus() runs just that last step, as the handler would, and marks the
        //    connection up so the disconnect side releases it. Should Android Auto ever refuse that channel or a
        //    focus request too (SecurityException), Maps navigates without the car's focus instead of waiting
        //    for it: the focus client reports focus held (its own state 3), nothing is faked towards the car.
        CarConnectedFingerprint.method.let { handler ->
            if (AccessFlags.STATIC.isSet(handler.accessFlags)) throw PatchException("car connection handler is static")
            val type = handler.definingClass
            val owner = mutableClassDefBy(type)
            if (owner.methods.any { it.name == MOVED_CAR_HANDLER }) throw PatchException("car connection handler already wrapped")
            val refs = CarMessageRefs(handler)
            val focusClass = mutableClassDefBy(refs.focus)
            val focusState = focusClass.methods.singleOrNull {
                it.parameterTypes.map(CharSequence::toString) == listOf("I") && it.returnType == "V" && !AccessFlags.STATIC.isSet(it.accessFlags)
            } ?: throw PatchException("navigation focus client's state setter not found")
            val focusSend = focusClass.methods.singleOrNull {
                it.parameterTypes.map(CharSequence::toString) == listOf("Z") && it.returnType == "Z" && !AccessFlags.STATIC.isSet(it.accessFlags)
            } ?: throw PatchException("navigation focus client's request not found")
            if (focusClass.methods.any { it.name == MOVED_FOCUS_REQUEST }) throw PatchException("navigation focus request already wrapped")
            val setState = "${refs.focus}->${focusState.name}(I)V"
            fun f(r: FieldReference) = "${r.definingClass}->${r.name}:${r.type}"

            val attach = ImmutableMethod(
                type, NAVIGATION_FOCUS, emptyList(), "V", AccessFlags.PRIVATE.value or AccessFlags.FINAL.value,
                null, null, MutableMethodImplementation(8),
            ).toMutable()
            attach.addInstructions(
                0,
                """
                    iget-object v0, p0, ${f(refs.owner)}
                    iget-object v1, v0, ${f(refs.ownerHolder)}
                    iget-object v2, v1, ${f(refs.holderClient)}
                    iget-object v3, v2, ${f(refs.focusManager)}
                    if-nez v3, :done
                    iget-object v3, v0, ${f(refs.ownerToken)}
                    invoke-virtual { v3 }, ${refs.client}
                    move-result-object v3
                    iget-object v4, v3, ${f(refs.executor)}
                    new-instance v5, ${refs.callable.definingClass}
                    const/4 v6, 0x0
                    invoke-direct { v5, v3, v6 }, ${refs.callable}
                    invoke-virtual { v4, v5 }, ${refs.call}
                    move-result-object v3
                    check-cast v3, ${refs.manager}
                    if-eqz v3, :done
                    iput-object v3, v1, ${f(refs.holderManager)}
                    iput-object v3, v2, ${f(refs.focusManager)}
                    iget-object v4, v2, ${f(refs.focusCallback)}
                    iput-object v4, v3, ${f(refs.managerCallback)}
                    const/4 v4, 0x1
                    iput-boolean v4, v0, ${f(refs.ownerConnected)}
                    const-string v4, "UA"
                    const-string v5, "navigation focus: attached to Android Auto's car messaging"
                    invoke-static { v4, v5 }, Landroid/util/Log;->i(Ljava/lang/String;Ljava/lang/String;)I
                    iget-boolean v4, v2, ${f(refs.focusPending)}
                    if-eqz v4, :done
                    const/4 v4, 0x0
                    iput-boolean v4, v2, ${f(refs.focusPending)}
                    invoke-virtual { v2 }, ${refs.focusRequest}
                    :done
                    return-void
                    move-exception v0
                    ${logRefusal("navigation focus refused by Android Auto, navigating without it: ")}
                    const/4 v0, 0x3
                    invoke-virtual { v2, v0 }, $setState
                    return-void
                    move-exception v0
                    ${logRefusal("navigation focus unavailable: ")}
                    return-void
                """,
            )
            attach.implementation!!.apply {
                val code = instructions.toList()
                val start = code.indexOfFirst { (it as? ReferenceInstruction)?.reference == refs.client }
                val end = code.indexOfFirst { it.opcode == Opcode.RETURN_VOID }
                val handlers = code.indices.filter { code[it].opcode == Opcode.MOVE_EXCEPTION }
                if (start < 0 || end <= start || handlers.size != 2) throw PatchException("navigation focus attach did not assemble as expected")
                addCatch("Ljava/lang/SecurityException;", newLabelForIndex(start), newLabelForIndex(end), newLabelForIndex(handlers[0]))
                addCatch("Ljava/lang/Exception;", newLabelForIndex(start), newLabelForIndex(end), newLabelForIndex(handlers[1]))
            }
            owner.methods.add(attach)

            focusClass.methods.add(
                ImmutableMethod(
                    refs.focus, MOVED_FOCUS_REQUEST, listOf(ImmutableMethodParameter("Z", null, null)), "Z",
                    AccessFlags.PRIVATE.value or AccessFlags.FINAL.value, null, null, MutableMethodImplementation(focusSend.implementation!!),
                ).toMutable(),
            )
            val sendWrapper = ImmutableMethod(
                refs.focus, focusSend.name, listOf(ImmutableMethodParameter("Z", null, null)), "Z", focusSend.accessFlags,
                focusSend.annotations, null, MutableMethodImplementation(4),
            ).toMutable()
            sendWrapper.addInstructions(
                0,
                """
                    invoke-direct { p0, p1 }, ${refs.focus}->$MOVED_FOCUS_REQUEST(Z)Z
                    move-result v0
                    return v0
                    move-exception v0
                    ${logRefusal("navigation focus refused by Android Auto, navigating without it: ")}
                    const/4 v0, 0x3
                    invoke-virtual { p0, v0 }, $setState
                    const/4 v0, 0x1
                    return v0
                """,
            )
            sendWrapper.implementation!!.apply {
                addCatch("Ljava/lang/SecurityException;", newLabelForIndex(0), newLabelForIndex(1), newLabelForIndex(3))
            }
            if (!focusClass.methods.removeIf { it.name == focusSend.name && it.parameterTypes.map(CharSequence::toString) == listOf("Z") && it.returnType == "Z" }) {
                throw PatchException("navigation focus request not found in ${refs.focus}")
            }
            focusClass.methods.add(sendWrapper)

            owner.methods.add(
                ImmutableMethod(
                    type, MOVED_CAR_HANDLER, emptyList(), "V", AccessFlags.PRIVATE.value or AccessFlags.FINAL.value,
                    null, null, MutableMethodImplementation(handler.implementation!!),
                ).toMutable(),
            )
            val wrapper = ImmutableMethod(
                type, handler.name, emptyList(), "V", handler.accessFlags, handler.annotations, null,
                MutableMethodImplementation(3),
            ).toMutable()
            wrapper.addInstructions(
                0,
                """
                    invoke-direct { p0 }, $type->$MOVED_CAR_HANDLER()V
                    return-void
                    move-exception v0
                    invoke-virtual { v0 }, Ljava/lang/Object;->toString()Ljava/lang/String;
                    move-result-object v0
                    const-string v1, "car connection refused by Android Auto: "
                    invoke-virtual { v1, v0 }, Ljava/lang/String;->concat(Ljava/lang/String;)Ljava/lang/String;
                    move-result-object v0
                    const-string v1, "UA"
                    invoke-static { v1, v0 }, Landroid/util/Log;->w(Ljava/lang/String;Ljava/lang/String;)I
                    invoke-direct { p0 }, $type->$NAVIGATION_FOCUS()V
                    return-void
                """,
            )
            // Only the call (index 0) is covered; the handler starts at the move-exception.
            wrapper.implementation!!.apply {
                addCatch("Ljava/lang/SecurityException;", newLabelForIndex(0), newLabelForIndex(1), newLabelForIndex(2))
            }
            if (!owner.methods.removeIf { it.name == handler.name && it.parameterTypes.isEmpty() && it.returnType == "V" }) {
                throw PatchException("car connection handler not found in $type")
            }
            owner.methods.add(wrapper)
        }
    }
}
