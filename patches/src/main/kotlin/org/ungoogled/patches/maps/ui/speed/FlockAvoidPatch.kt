package org.ungoogled.patches.maps.ui.speed

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.string
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod.Companion.toMutable
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.builder.MutableMethodImplementation
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.NarrowLiteralInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.TwoRegisterInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.iface.reference.StringReference
import com.android.tools.smali.dexlib2.iface.reference.TypeReference
import com.android.tools.smali.dexlib2.immutable.ImmutableMethod
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodParameter
import org.ungoogled.patches.maps.ui.markPatched
import org.ungoogled.patches.maps.ui.sharedExtensionPatch
import org.ungoogled.patches.shared.Constants.COMPATIBILITY_MAPS

private const val STEER = "Lorg/ungoogled/ui/Steer;"
private const val IMMUTABLE_LIST = "Lcom/google/common/collect/ImmutableList;"
private const val LISTENABLE_FUTURE = "Lcom/google/common/util/concurrent/ListenableFuture;"

/** The directions request parameters' toString: "options=", "\nwaypoints=" (j), "\nviapoints=" (k). */
private object DirectionsParamsToStringFingerprint : Fingerprint(
    name = "toString",
    returnType = "Ljava/lang/String;",
    parameters = emptyList(),
    filters = listOf(string("options="), string("\nwaypoints="), string("\nviapoints=")),
)

/** DirectionsFetcherImpl, by its trace labels. */
private object DirectionsFetcherFingerprint : Fingerprint(
    filters = listOf(string("DirectionsFetcherImpl.fetchDirectionsInternal")),
)

/** RequestTriggeringControllerImpl.issueDirectionsRequest(record). */
private object IssueDirectionsRequestFingerprint : Fingerprint(
    returnType = "V",
    parameters = listOf("L"),
    filters = listOf(string("RequestTriggeringControllerImpl.issueDirectionsRequest")),
)

/** A LatLng's toString: "lat/lng: (" latitude "," longitude ")". */
private object LatLngToStringFingerprint : Fingerprint(
    name = "toString",
    returnType = "Ljava/lang/String;",
    parameters = emptyList(),
    filters = listOf(string("lat/lng: (")),
)

/** A route polyline's toString ("Polyline{"). */
private object PolylineToStringFingerprint : Fingerprint(
    name = "toString",
    returnType = "Ljava/lang/String;",
    parameters = emptyList(),
    filters = listOf(string("Polyline{")),
)

/** The register of parameter [index] (0 = this) of an instance method. */
private fun MutableMethod.parameterRegister(index: Int): Int {
    val sizes = listOf(1) + parameterTypes.map { if (it.toString() == "J" || it.toString() == "D") 2 else 1 }
    return implementation!!.registerCount - sizes.sum() + sizes.take(index).sum()
}

private fun Instruction.field() = (this as? ReferenceInstruction)?.reference as? FieldReference
private fun Instruction.call() = (this as? ReferenceInstruction)?.reference as? MethodReference
private fun Method.params() = parameterTypes.map { it.toString() }
private fun Method.code() = implementation?.instructions?.toList() ?: emptyList()

/**
 * The hooks Avoid Flock cameras works through: Maps' directions request builder reads its (always empty)
 * pass-through list through Steer.vias(); the directions fetcher reports every answer it gets
 * (Steer.response); the directions screen reports its request session as it starts (Steer.session),
 * whose controller can ask again; and the extension's stubs are written against Maps' request, answer
 * and controller classes. Every Maps name is found from trace labels, toString texts and signatures.
 */
internal val steerHookPatch = bytecodePatch(
    description = "Lets the extension add pass-through points to directions requests.",
) {
    dependsOn(sharedExtensionPatch)

    execute {
        fun missing(what: String): Nothing = throw PatchException("Avoid Flock cameras: $what not found")

        // The request parameters and their waypoint / pass-through lists, from its toString.
        val paramsToString = DirectionsParamsToStringFingerprint.method
        val params = paramsToString.definingClass
        fun listAfter(label: String): FieldReference {
            val code = paramsToString.code()
            val at = code.indexOfFirst { (it.opcode == Opcode.CONST_STRING) && ((it as ReferenceInstruction).reference as StringReference).string == label }
            return code.drop(at).firstNotNullOfOrNull { i -> i.field()?.takeIf { i.opcode == Opcode.IGET_OBJECT && it.definingClass == params } }
                ?.takeIf { it.type == IMMUTABLE_LIST } ?: missing("the request's $label list")
        }
        val waypointsField = listAfter("\nwaypoints=")
        val viasField = listAfter("\nviapoints=")

        // The request builder: the method taking the parameters first that iterates their pass-through list.
        val builderRef = run {
            var found: Pair<String, Method>? = null
            classDefForEach { cd ->
                if (cd.type.startsWith("Lorg/ungoogled/")) return@classDefForEach
                cd.methods.forEach { m ->
                    if (m.params().firstOrNull() != params || m.params().size < 2) return@forEach
                    val code = m.code()
                    val read = code.indexOfFirst { it.opcode == Opcode.IGET_OBJECT && it.field() == viasField }
                    if (read >= 0 && code.getOrNull(read + 1)?.call()?.name == "iterator") {
                        if (found != null) throw PatchException("Avoid Flock cameras: more than one directions request builder")
                        found = cd.type to m
                    }
                }
            }
            found ?: missing("the directions request builder")
        }
        val builder = mutableClassDefBy(builderRef.first).methods.single {
            it.name == builderRef.second.name && it.params() == builderRef.second.params() && it.returnType == builderRef.second.returnType
        }
        val builderCode = builder.code()
        val viasRead = builderCode.withIndex().singleOrNull { (_, i) -> i.opcode == Opcode.IGET_OBJECT && i.field() == viasField }
            ?: throw PatchException("request builder no longer reads the pass-through list once")
        // In its pass-through loop: each element is a VIA, and lands in the request body's pass-through list.
        val via = builderCode.drop(viasRead.index).firstOrNull { it.opcode == Opcode.CHECK_CAST }
            ?.let { ((it as ReferenceInstruction).reference as TypeReference).type } ?: missing("the pass-through point type")
        val viaAdd = (viasRead.index until builderCode.size).firstOrNull { i ->
            builderCode[i].opcode == Opcode.INVOKE_INTERFACE && builderCode[i].call()?.name == "add" &&
                builderCode.subList(viasRead.index, i).any { it.opcode == Opcode.IGET_OBJECT && it.field()?.type?.startsWith("L") == true }
        } ?: missing("the request body's pass-through list")
        val bodyViaList = builderCode.subList(viasRead.index, viaAdd).last { it.opcode == Opcode.IGET_OBJECT }.field()!!
        val body = bodyViaList.definingClass
        // Its waypoint loop hands each converted waypoint to the body builder's adder, which adds it to the body's list.
        val waypointsRead = builderCode.indexOfFirst { it.opcode == Opcode.IGET_OBJECT && it.field() == waypointsField }
        if (waypointsRead < 0 || waypointsRead > viasRead.index) missing("the request builder's waypoint loop")
        val bodyWaypointList = builderCode.subList(waypointsRead, viasRead.index).mapNotNull { i ->
            i.call()?.takeIf { i.opcode == Opcode.INVOKE_VIRTUAL && it.returnType == "V" && it.parameterTypes.size == 1 }
        }.firstNotNullOfOrNull { adder ->
            val m = runCatching { classDefBy(adder.definingClass) }.getOrNull()?.methods?.firstOrNull {
                it.name == adder.name && it.params() == adder.parameterTypes.map { t -> t.toString() }
            } ?: return@firstNotNullOfOrNull null
            val c = m.code()
            val add = c.indexOfFirst { it.opcode == Opcode.INVOKE_INTERFACE && it.call()?.name == "add" }
            if (add < 0) null else c.take(add).lastOrNull { it.opcode == Opcode.IGET_OBJECT }?.field()?.takeIf { it.definingClass == body }
        } ?: missing("the request body's waypoint list")

        val read = viasRead.value
        val target = (read as TwoRegisterInstruction).registerA
        val paramsRegister = read.registerB
        builder.addInstructions(
            viasRead.index + 1,
            """
                invoke-static { v$paramsRegister, v$target }, $STEER->vias(Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;
                move-result-object v$target
                check-cast v$target, $IMMUTABLE_LIST
            """,
        )

        // The controller: issueDirectionsRequest(record); its record factory's one (session key) -> record method
        // makes an OPTIONS_CHANGED (type 3) record, what Maps sends when a route option changes.
        val issue = IssueDirectionsRequestFingerprint.method
        val controller = issue.definingClass
        val record = issue.params().single()
        val controllerClass = classDefBy(controller)
        val (factoryField, factoryMethod) = controllerClass.fields.firstNotNullOfOrNull { f ->
            val cd = runCatching { classDefBy(f.type) }.getOrNull() ?: return@firstNotNullOfOrNull null
            cd.methods.singleOrNull { it.returnType == record && it.parameterTypes.size == 1 && it.params().single().startsWith("L") }
                ?.takeIf { m -> m.code().any { it.opcode == Opcode.CONST_4 && (it as NarrowLiteralInstruction).narrowLiteral == 3 } }
                ?.let { f to it }
        } ?: missing("the directions request record factory")
        val sessionKey = factoryMethod.params().single()
        val controllerKey = controllerClass.fields.singleOrNull { it.type == sessionKey } ?: missing("the controller's session key")

        // The directions screen's request session: holds the controller; onStart starts it with b(session key).
        val sessionRef = run {
            var found: Pair<String, Method>? = null
            classDefForEach { cd ->
                if (cd.type == controller || cd.fields.none { it.type == controller }) return@classDefForEach
                val start = cd.methods.singleOrNull { it.returnType == "V" && it.params() == listOf(sessionKey) } ?: return@classDefForEach
                if (found != null) throw PatchException("Avoid Flock cameras: more than one directions request session")
                found = cd.type to start
            }
            found ?: missing("the directions request session")
        }
        val session = sessionRef.first
        val sessionController = classDefBy(session).fields.single { it.type == controller }
        val sessionStart = mutableClassDefBy(session).methods.single {
            it.name == sessionRef.second.name && it.params() == listOf(sessionKey) && it.returnType == "V"
        }
        val sessionRegister = sessionStart.parameterRegister(0)
        sessionStart.addInstructions(
            0,
            "invoke-static/range { v$sessionRegister .. v$sessionRegister }, $STEER->session(Ljava/lang/Object;)V",
        )

        // Every answer, online and offline, prefetched or not, passes the fetcher's f(request, answer, z).
        val answer = DirectionsFetcherFingerprint.classDef.methods.singleOrNull {
            it.returnType == LISTENABLE_FUTURE && it.parameterTypes.size == 3 && it.params()[2] == "Z" &&
                it.params().take(2).all { t -> t.startsWith("L") && !t.startsWith("Ljava/") }
        } ?: missing("the directions fetcher's answer method")
        val request = answer.params()[0]
        val response = answer.params()[1]
        val requestBody = classDefBy(request).fields.singleOrNull { it.type == body } ?: missing("the request's body")
        val mutableAnswer = mutableClassDefBy(answer.definingClass).methods.single {
            it.name == answer.name && it.params() == answer.params() && it.returnType == answer.returnType
        }
        val self = mutableAnswer.parameterRegister(0)
        mutableAnswer.addInstructions(
            0,
            "invoke-static/range { v$self .. v${self + 2} }, " +
                "$STEER->response(Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/Object;)V",
        )

        // The answer's trips (an array), each trip's polyline, its points and their positions.
        val polyline = PolylineToStringFingerprint.classDef
        val responseClass = classDefBy(response)
        val polylineOf = responseClass.methods.singleOrNull { it.returnType == polyline.type && it.parameterTypes.size == 1 && it.params().single().startsWith("L") }
            ?: missing("the answer's polyline of a trip")
        val trip = polylineOf.params().single()
        val trips = responseClass.fields.singleOrNull { it.type == "[$trip" } ?: missing("the answer's trips")
        val tripAt = responseClass.methods.singleOrNull { it.returnType == trip && it.params() == listOf("I") } ?: missing("the answer's trip getter")
        val pointCount = polyline.methods.singleOrNull { it.returnType == "I" && it.parameterTypes.isEmpty() && it.name != "hashCode" }
            ?: missing("the polyline's point count")
        val pointAt = polyline.methods.singleOrNull { it.params() == listOf("I") && it.returnType.startsWith("L") && !it.returnType.startsWith("Ljava/") }
            ?: missing("the polyline's point getter")
        val point = pointAt.returnType
        val latLng = LatLngToStringFingerprint.method.definingClass
        val latLngOf = classDefBy(point).methods.singleOrNull { it.returnType == latLng && it.parameterTypes.isEmpty() }
            ?: missing("a point's position")
        val (lat, lng) = LatLngToStringFingerprint.method.code().mapNotNull { i -> i.field()?.takeIf { i.opcode == Opcode.IGET_WIDE } }
            .takeIf { it.size == 2 } ?: missing("a position's latitude and longitude")
        val viaOf = classDefBy(via).methods.singleOrNull {
            AccessFlags.STATIC.isSet(it.accessFlags) && it.returnType == via && it.params() == listOf(latLng, "I")
        } ?: missing("the pass-through point factory")

        // A trip's travel mode: Maps reads it as TravelMode.forNumber(trip.summary().mode).
        val travelMode = run {
            val counts = HashMap<Pair<String, String>, Int>()
            classDefForEach { cd ->
                if (cd.type.startsWith("Lorg/ungoogled/")) return@classDefForEach
                cd.methods.forEach { m ->
                    val c = m.code()
                    for (i in 0 until c.size - 3) {
                        val get = c[i].call() ?: continue
                        if (c[i].opcode != Opcode.INVOKE_VIRTUAL || get.definingClass != trip || get.parameterTypes.isNotEmpty()) continue
                        if (c[i + 1].opcode != Opcode.MOVE_RESULT_OBJECT || c[i + 2].opcode != Opcode.IGET) continue
                        val mode = c[i + 2].field() ?: continue
                        val forNumber = c[i + 3].call() ?: continue
                        if (c[i + 3].opcode != Opcode.INVOKE_STATIC || mode.definingClass != get.returnType ||
                            forNumber.parameterTypes.map { it.toString() } != listOf("I") || forNumber.returnType != forNumber.definingClass
                        ) continue
                        val isTravelMode = runCatching { classDefBy(forNumber.definingClass) }.getOrNull()?.methods
                            ?.firstOrNull { it.name == "<clinit>" }?.code()?.mapNotNull { (it.takeIf { x -> x.opcode == Opcode.CONST_STRING } as? ReferenceInstruction)?.reference?.toString() }
                            ?.containsAll(listOf("DRIVE", "WALK", "TWO_WHEELER")) == true
                        if (isTravelMode) counts.merge(get.name to mode.name, 1, Int::plus)
                    }
                }
            }
            counts.maxByOrNull { it.value }?.key ?: missing("a trip's travel mode")
        }
        val summary = classDefBy(trip).methods.single { it.name == travelMode.first && it.parameterTypes.isEmpty() }.returnType

        // The extension's stubs, written against Maps' classes.
        val steer = mutableClassDefBy(STEER)
        fun replace(name: String, parameters: List<String>, returnType: String, registers: Int, smali: String) {
            steer.methods.remove(steer.methods.singleOrNull { it.name == name } ?: throw PatchException("$STEER->$name not found"))
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
                check-cast p0, $request
                iget-object v0, p0, $request->${requestBody.name}:$body
                if-eqz v0, :none
                iget-object v1, v0, $body->${bodyWaypointList.name}:${bodyWaypointList.type}
                invoke-interface { v1 }, Ljava/util/List;->size()I
                move-result v1
                iget-object v2, v0, $body->${bodyViaList.name}:${bodyViaList.type}
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
                new-instance v0, $latLng
                invoke-direct { v0, p0, p1, p2, p3 }, $latLng-><init>(DD)V
                invoke-static { v0, p4 }, $via->${viaOf.name}($latLng I)$via
                move-result-object v0
                return-object v0
            """,
        )
        replace(
            "waypoints", listOf("Ljava/lang/Object;"), "Ljava/util/List;", 2,
            """
                check-cast p0, $params
                iget-object v0, p0, $params->${waypointsField.name}:$IMMUTABLE_LIST
                return-object v0
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
                check-cast p0, $response
                iget-object v0, p0, $response->${trips.name}:[$trip
                array-length v0, v0
                return v0
            """,
        )
        replace(
            "tripMode", listOf("Ljava/lang/Object;", "I"), "I", 3,
            """
                check-cast p0, $response
                invoke-virtual { p0, p1 }, $response->${tripAt.name}(I)$trip
                move-result-object v0
                invoke-virtual { v0 }, $trip->${travelMode.first}()$summary
                move-result-object v0
                iget v0, v0, $summary->${travelMode.second}:I
                return v0
            """,
        )
        replace(
            "polyline", listOf("Ljava/lang/Object;", "I"), "[D", 11,
            """
                check-cast p0, $response
                invoke-virtual { p0, p1 }, $response->${tripAt.name}(I)$trip
                move-result-object v0
                invoke-virtual { p0, v0 }, $response->${polylineOf.name}($trip)${polyline.type}
                move-result-object v1
                if-eqz v1, :none
                invoke-virtual { v1 }, ${polyline.type}->${pointCount.name}()I
                move-result v2
                mul-int/lit8 v3, v2, 0x2
                new-array v3, v3, [D
                const/4 v4, 0x0
                :loop
                if-ge v4, v2, :done
                invoke-virtual { v1, v4 }, ${polyline.type}->${pointAt.name}(I)$point
                move-result-object v5
                invoke-virtual { v5 }, $point->${latLngOf.name}()$latLng
                move-result-object v5
                iget-wide v6, v5, $latLng->${lat.name}:D
                mul-int/lit8 v8, v4, 0x2
                aput-wide v6, v3, v8
                iget-wide v6, v5, $latLng->${lng.name}:D
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
                check-cast p0, $session
                iget-object v0, p0, $session->${sessionController.name}:$controller
                if-eqz v0, :no
                iget-object v1, v0, $controller->${controllerKey.name}:$sessionKey
                if-eqz v1, :no
                const/4 v0, 0x1
                return v0
                :no
                const/4 v0, 0x0
                return v0
            """,
        )
        // What Maps does when a route option changes (RequestTriggeringControllerImpl.onDirectionsOptionsUpdate):
        // the controller issues a type-3 (OPTIONS_CHANGED) record for its session key. Its
        // issueDirectionsRequestInternal starts a fresh fetcher and listener, so the screen shows the answer.
        replace(
            "refetch", listOf("Ljava/lang/Object;"), "V", 4,
            """
                check-cast p0, $session
                iget-object v0, p0, $session->${sessionController.name}:$controller
                if-eqz v0, :none
                iget-object v1, v0, $controller->${controllerKey.name}:$sessionKey
                if-eqz v1, :none
                iget-object v2, v0, $controller->${factoryField.name}:${factoryField.type}
                invoke-virtual { v2, v1 }, ${factoryField.type}->${factoryMethod.name}($sessionKey)$record
                move-result-object v2
                invoke-virtual { v0, v2 }, $controller->${issue.name}($record)V
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
