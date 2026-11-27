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
import com.android.tools.smali.dexlib2.immutable.ImmutableMethod
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

        // 5. Android Auto's private car service (issue #52). When the car connects, Maps' car connection handler
        //    reads the car's details through calls Android Auto answers only for a Google-signed app; at a
        //    re-signed Maps the first of them throws "Wrong signature - go/gearhead-retail-device" on the main
        //    thread and Maps dies. The handler's body moves to uaOnCarConnected() and the handler becomes
        //    `try { uaOnCarConnected() } catch (SecurityException e) { log }`: Maps carries on without the car's
        //    details, as before that connection is up. A Google-signed install (a root mount install keeps
        //    stock Maps' signature) is answered and runs the whole handler as before. The refused call comes
        //    before the handler changes any state its disconnect side would have to undo.
        //    Idea from "Skip private Android Auto car connection" in shuili1013's Maps-AA-AnyMode-Morphe
        //    (GPLv3), which skips the handler outright.
        CarConnectedFingerprint.method.let { handler ->
            if (AccessFlags.STATIC.isSet(handler.accessFlags)) throw PatchException("car connection handler is static")
            val type = handler.definingClass
            val owner = mutableClassDefBy(type)
            if (owner.methods.any { it.name == MOVED_CAR_HANDLER }) throw PatchException("car connection handler already wrapped")
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
