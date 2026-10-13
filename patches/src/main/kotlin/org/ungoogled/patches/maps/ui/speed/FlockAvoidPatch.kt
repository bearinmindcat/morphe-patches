package org.ungoogled.patches.maps.ui.speed

import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod.Companion.toMutable
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.builder.MutableMethodImplementation
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.TwoRegisterInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.immutable.ImmutableMethod
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodParameter
import org.ungoogled.patches.maps.ui.markPatched
import org.ungoogled.patches.maps.ui.sharedExtensionPatch
import org.ungoogled.patches.shared.Constants.COMPATIBILITY_MAPS

private const val STEER = "Lorg/ungoogled/ui/Steer;"
private const val IMMUTABLE_LIST = "Lcom/google/common/collect/ImmutableList;"
private const val LISTENABLE_FUTURE = "Lcom/google/common/util/concurrent/ListenableFuture;"

/** The directions request builder (classes.dex) and the request parameters it reads. */
private const val BUILDER = "Lxgl;"
private const val PARAMS = "Lxxf;"
/** The directions fetcher (DirectionsFetcherImpl), a request, an answer, a trip and its travel mode. */
private const val FETCHER = "Lvvq;"
/** The directions request controller (RequestTriggeringControllerImpl) and its request records. */
private const val CONTROLLER = "Lwkw;"
private const val REQUEST_RECORD = "Lwjw;"
/** The directions screen's request session (its controller is g) and the request record factory. */
private const val SESSION = "Lwkn;"
private const val RECORD_FACTORY = "Lwkm;"
private const val REQUEST = "Lcqef;"
private const val REQUEST_BODY = "Lcqdw;"
private const val RESPONSE = "Lxus;"
private const val TRIP = "Lcqmm;"
private const val TRIP_MODE = "Lcjdj;"
/** A route's points, one point (b() latitude, d() longitude), a position in degrees, a pass-through point. */
private const val POLYLINE = "Lbjbi;"
private const val POINT = "Lbjbd;"
private const val LAT_LNG = "Lbjaw;"
private const val VIA = "Lxwj;"

/** The register of parameter [index] (0 = this) of an instance method. */
private fun MutableMethod.parameterRegister(index: Int): Int {
    val sizes = listOf(1) + parameterTypes.map { if (it.toString() == "J" || it.toString() == "D") 2 else 1 }
    return implementation!!.registerCount - sizes.sum() + sizes.take(index).sum()
}

/**
 * The hooks Avoid Flock cameras works through: Maps' directions request builder reads its (always empty)
 * pass-through list through Steer.vias(); the directions fetcher reports every answer it gets
 * (Steer.response); the directions screen reports its request session as it starts (Steer.session),
 * whose controller can ask again; and the extension's stubs are written against Maps' request, answer
 * and controller classes.
 */
internal val steerHookPatch = bytecodePatch(
    description = "Lets the extension add pass-through points to directions requests.",
) {
    dependsOn(sharedExtensionPatch)

    execute {
        // The request builder: its loop over the parameters' pass-through list.
        val builder = mutableClassDefBy(BUILDER).methods.single {
            it.name == "d" && it.returnType == "Lcmzm;" &&
                it.parameterTypes.map { t -> t.toString() } == listOf(PARAMS, "Lcqel;", "Ljava/util/List;", "Lcqed;", "Z")
        }
        val read = builder.implementation!!.instructions.withIndex().filter { (_, i) ->
            i.opcode == Opcode.IGET_OBJECT && ((i as ReferenceInstruction).reference as FieldReference).let {
                it.definingClass == PARAMS && it.name == "k" && it.type == IMMUTABLE_LIST
            }
        }.singleOrNull() ?: throw PatchException("request builder no longer reads the pass-through list once")
        val target = (read.value as TwoRegisterInstruction).registerA
        val params = (read.value as TwoRegisterInstruction).registerB
        builder.addInstructions(
            read.index + 1,
            """
                invoke-static { v$params, v$target }, $STEER->vias(Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;
                move-result-object v$target
                check-cast v$target, $IMMUTABLE_LIST
            """,
        )

        // The directions screen's onStart starts its request session with wkn.b(axrl), prefetched or not.
        val sessionStart = mutableClassDefBy(SESSION).methods.single {
            it.name == "b" && it.returnType == "V" && it.parameterTypes.map { t -> t.toString() } == listOf("Laxrl;")
        }
        val sessionRegister = sessionStart.parameterRegister(0)
        sessionStart.addInstructions(
            0,
            "invoke-static/range { v$sessionRegister .. v$sessionRegister }, $STEER->session(Ljava/lang/Object;)V",
        )
        // Every answer, online and offline, prefetched or not, passes the fetcher's f(request, answer, z).
        val answer = mutableClassDefBy(FETCHER).methods.single {
            it.name == "f" && it.returnType == LISTENABLE_FUTURE &&
                it.parameterTypes.map { t -> t.toString() } == listOf(REQUEST, RESPONSE, "Z")
        }
        val self = answer.parameterRegister(0)
        answer.addInstructions(
            0,
            "invoke-static/range { v$self .. v${self + 2} }, " +
                "$STEER->response(Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/Object;)V",
        )

        // The extension's stubs, written against Maps' classes.
        val steer = mutableClassDefBy(STEER)
        fun replace(name: String, parameters: List<String>, returnType: String, registers: Int, smali: String) {
            steer.methods.remove(steer.methods.single { it.name == name })
            steer.methods.add(
                ImmutableMethod(
                    STEER, name, parameters.map { ImmutableMethodParameter(it, null, null) }, returnType,
                    AccessFlags.STATIC.value, null, null, MutableMethodImplementation(registers),
                ).toMutable().apply { addInstructions(0, smali) },
            )
        }
        replace(
            "counts", listOf("Ljava/lang/Object;"), "[I", 6,
            """
                check-cast p0, $REQUEST
                iget-object v0, p0, $REQUEST->c:$REQUEST_BODY
                if-eqz v0, :none
                iget-object v1, v0, $REQUEST_BODY->e:Lcnak;
                invoke-interface { v1 }, Ljava/util/List;->size()I
                move-result v1
                iget-object v2, v0, $REQUEST_BODY->k:Lcnak;
                invoke-interface { v2 }, Ljava/util/List;->size()I
                move-result v2
                const/4 v3, 0x2
                new-array v3, v3, [I
                const/4 v4, 0x0
                aput v1, v3, v4
                const/4 v4, 0x1
                aput v2, v3, v4
                return-object v3
                :none
                const/4 v3, 0x0
                return-object v3
            """,
        )
        replace(
            "via", listOf("D", "D", "I"), "Ljava/lang/Object;", 7,
            """
                new-instance v0, $LAT_LNG
                invoke-direct { v0, p0, p1, p2, p3 }, $LAT_LNG-><init>(DD)V
                invoke-static { v0, p4 }, $VIA->a($LAT_LNG I)$VIA
                move-result-object v0
                return-object v0
            """,
        )
        replace(
            "waypoints", listOf("Ljava/lang/Object;"), "Ljava/util/List;", 2,
            """
                check-cast p0, $PARAMS
                iget-object v0, p0, $PARAMS->j:$IMMUTABLE_LIST
                return-object v0
            """,
        )
        replace(
            "requestMode", listOf("Ljava/lang/Object;"), "I", 2,
            """
                check-cast p0, $PARAMS
                iget-object v0, p0, $PARAMS->a:Lcqec;
                if-eqz v0, :none
                iget-object v0, v0, Lcqec;->g:Lcjfb;
                if-eqz v0, :none
                iget v0, v0, Lcjfb;->c:I
                return v0
                :none
                const/4 v0, -0x1
                return v0
            """,
        )
        replace(
            "immutable", listOf("Ljava/util/List;"), "Ljava/lang/Object;", 1,
            """
                invoke-static { p0 }, $IMMUTABLE_LIST->copyOf(Ljava/util/Collection;)$IMMUTABLE_LIST
                move-result-object p0
                return-object p0
            """,
        )
        replace(
            "tripCount", listOf("Ljava/lang/Object;"), "I", 2,
            """
                check-cast p0, $RESPONSE
                invoke-virtual { p0 }, $RESPONSE->c()I
                move-result v0
                return v0
            """,
        )
        replace(
            "tripMode", listOf("Ljava/lang/Object;", "I"), "I", 3,
            """
                check-cast p0, $RESPONSE
                invoke-virtual { p0, p1 }, $RESPONSE->w(I)$TRIP
                move-result-object v0
                invoke-virtual { v0 }, $TRIP->A()$TRIP_MODE
                move-result-object v0
                iget v0, v0, $TRIP_MODE->c:I
                return v0
            """,
        )
        replace(
            "polyline", listOf("Ljava/lang/Object;", "I"), "[D", 11,
            """
                check-cast p0, $RESPONSE
                invoke-virtual { p0, p1 }, $RESPONSE->w(I)$TRIP
                move-result-object v0
                invoke-virtual { p0, v0 }, $RESPONSE->x($TRIP)$POLYLINE
                move-result-object v1
                if-eqz v1, :none
                invoke-virtual { v1 }, $POLYLINE->g()I
                move-result v2
                mul-int/lit8 v3, v2, 0x2
                new-array v3, v3, [D
                const/4 v4, 0x0
                :loop
                if-ge v4, v2, :done
                invoke-virtual { v1, v4 }, $POLYLINE->n(I)$POINT
                move-result-object v5
                invoke-virtual { v5 }, $POINT->b()D
                move-result-wide v6
                mul-int/lit8 v8, v4, 0x2
                aput-wide v6, v3, v8
                invoke-virtual { v5 }, $POINT->d()D
                move-result-wide v6
                add-int/lit8 v8, v8, 0x1
                aput-wide v6, v3, v8
                add-int/lit8 v4, v4, 0x1
                goto :loop
                :done
                return-object v3
                :none
                const/4 v3, 0x0
                return-object v3
            """,
        )
        replace(
            "sessionActive", listOf("Ljava/lang/Object;"), "Z", 3,
            """
                check-cast p0, $SESSION
                iget-object v0, p0, $SESSION->g:$CONTROLLER
                if-eqz v0, :no
                iget-object v1, v0, $CONTROLLER->k:Laxrl;
                if-eqz v1, :no
                const/4 v0, 0x1
                return v0
                :no
                const/4 v0, 0x0
                return v0
            """,
        )
        // What Maps does when a route option changes (RequestTriggeringControllerImpl.onDirectionsOptionsUpdate):
        // the controller wkw issues a type-3 (OPTIONS_CHANGED) record for its session key wkw.k. Its
        // issueDirectionsRequestInternal starts a fresh fetcher and listener, so the screen shows the answer.
        replace(
            "refetch", listOf("Ljava/lang/Object;"), "V", 4,
            """
                check-cast p0, $SESSION
                iget-object v0, p0, $SESSION->g:$CONTROLLER
                if-eqz v0, :none
                iget-object v1, v0, $CONTROLLER->k:Laxrl;
                if-eqz v1, :none
                iget-object v2, v0, $CONTROLLER->d:$RECORD_FACTORY
                invoke-virtual { v2, v1 }, $RECORD_FACTORY->c(Laxrl;)$REQUEST_RECORD
                move-result-object v2
                invoke-virtual { v0, v2 }, $CONTROLLER->b($REQUEST_RECORD)V
                :none
                return-void
            """,
        )
    }
}

@Suppress("unused")
val flockAvoidPatch = bytecodePatch(
    name = "Avoid Flock cameras",
    description = "Steers driving directions around Flock cameras: when Google's route passes one, a camera-free " +
        "way is worked out with BRouter, an OpenStreetMap router, and Maps asks Google again for a route through " +
        "it, so Google still times and navigates the trip, and keeps it through reroutes. A detour longer than " +
        "half again the distance is dropped. Off until turned on in Waze & external data source, with Flock " +
        "cameras; your route's start and end go to brouter.de. Trips with stops are left as they are.",
    default = true,
) {
    compatibleWith(COMPATIBILITY_MAPS)
    dependsOn(flockCamerasPatch, steerHookPatch)

    execute {
        markPatched("flockAvoidPatched")
    }
}
