package org.ungoogled.patches.maps.gms

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.InstructionLocation.MatchAfterWithin
import app.morphe.patcher.methodCall
import app.morphe.patcher.string
import com.android.tools.smali.dexlib2.Opcode

/**
 * The gRPC interceptor that attaches `X-Android-Package` / `X-Android-Cert` to
 * Maps' backend calls. The Maps API key is restricted server-side to Google's
 * own (package, certificate) pair, checked against exactly these two headers.
 *
 * Each header name is followed, within a few instructions, by the one getter
 * that supplies its value. Those getters live on an R8 god-class and are
 * byte-for-byte identical apart from the field they read, so they cannot be
 * told apart by shape. Anchoring on the header name and taking whichever
 * method supplies it defines the target by meaning instead.
 *
 * Nine classes mention these header names; only this one is a constructor
 * taking (Application, Executor, _, _).
 */
internal object IdentityHeadersFingerprint : Fingerprint(
    name = "<init>",
    parameters = listOf("Landroid/app/Application;", "Ljava/util/concurrent/Executor;", "L", "L"),
    filters = listOf(
        string("X-Android-Package"),
        methodCall(opcode = Opcode.INVOKE_VIRTUAL, returnType = "Ljava/lang/String;", location = MatchAfterWithin(5)),
        string("X-Android-Cert"),
        methodCall(opcode = Opcode.INVOKE_VIRTUAL, returnType = "Ljava/lang/String;", location = MatchAfterWithin(5)),
    ),
)

/**
 * The bundled BaseGmsClient's getRemoteService: binds a Play services service
 * through IGmsServiceBroker. Its SecurityException handler rethrows, which
 * kills the process when Play services rejects a re-signed caller, while the
 * sibling RemoteException/RuntimeException handler degrades gracefully.
 *
 * The broker's interface descriptor appears in three methods; only this one
 * has the (_, Set) signature.
 */
internal object GetRemoteServiceFingerprint : Fingerprint(
    returnType = "V",
    parameters = listOf("L", "Ljava/util/Set;"),
    filters = listOf(string("com.google.android.gms.common.internal.IGmsServiceBroker")),
)

/**
 * GooglePlayServicesUtil's "is this package signed by Google" check. The error
 * string occurs exactly once in the APK.
 */
internal object PlayServicesSignatureCheckFingerprint : Fingerprint(
    returnType = "Z",
    parameters = listOf("Landroid/content/pm/PackageInfo;", "Z"),
    filters = listOf(string("Unable to obtain package certificate history.")),
)

/**
 * GooglePlayServicesUtil's isGooglePlayServicesAvailable: returns a
 * ConnectionResult code that decides whether Maps shows the non-dismissable
 * "Enable Google Play services" dialog. The manifest-version key it reads also
 * appears in one unrelated six-argument constructor, which the signature rules
 * out.
 */
internal object PlayServicesAvailabilityFingerprint : Fingerprint(
    returnType = "I",
    parameters = listOf("Landroid/content/Context;", "I"),
    filters = listOf(string("com.google.android.gms.version")),
)

/**
 * The view DSL's property binder: loops over a view's properties and applies
 * each, and on any exception rethrows it wrapped with the failing property and
 * view model ("property", "viewModel") -- a crash. On a re-signed Maps, Play
 * services refuses the app's Phenotype flag requests, so a conditional view
 * stub can come back null, its constructor throws, and this turns that into a
 * fatal exception on resume.
 */
internal object ViewPropertyBinderFingerprint : Fingerprint(
    returnType = "V",
    // In the order they occur: the handler walks to the root cause, then names the property and view model.
    filters = listOf(
        methodCall(definingClass = "Ljava/lang/Throwable;", name = "getCause"),
        string("property"),
        string("viewModel"),
    ),
    custom = { method, _ -> method.parameterTypes.size == 4 && method.parameterTypes[3] == "I" },
)

/**
 * The bundled Maps SDK's API key reader: getApplicationInfo(package, GET_META_DATA)
 * and the key from that package's manifest meta-data, with a NameNotFoundException
 * rethrown as an AssertionError. Maps passes it the package name from the same
 * getter that supplies X-Android-Package. The error string occurs once in the APK.
 */
internal object ApiKeyReaderFingerprint : Fingerprint(
    returnType = "Ljava/lang/String;",
    parameters = listOf("Landroid/content/Context;", "Ljava/lang/String;"),
    filters = listOf(string("API key not found.  Check that <meta-data android:name=\"")),
)
