package org.ungoogled.patches.honorweather.misc

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.extensions.InstructionExtensions.addInstructionsWithLabels
import app.morphe.patcher.extensions.InstructionExtensions.getInstruction
import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.patch.resourcePatch
import app.morphe.patcher.util.smali.ExternalLabel
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.iface.reference.StringReference

private const val API_INFO = "Lcom/hihonor/android/totemweather/entity/ApiInfo;"
private const val WEATHER = "$EXT/Weather;"

/** The app's Retrofit service factory (one cached service per interface, built on Honor's weather cloud). */
private object ServiceFactoryFingerprint : Fingerprint(
    returnType = "Ljava/lang/Object;",
    parameters = listOf("Ljava/lang/Class;"),
    strings = listOf("getApiService-error-tClass: "),
)

/** The weather supplier list (from Honor's config server, cached). */
private object SupplierListFingerprint : Fingerprint(
    returnType = "Ljava/util/List;",
    parameters = listOf("Landroid/content/Context;"),
    strings = listOf("readSupplierList-> vendorIds:"),
)

/** Asks Honor's config server for the supplier list (URLs, keys, secrets of every supplier). */
private object SupplierRequestFingerprint : Fingerprint(
    returnType = "V",
    parameters = listOf("Landroid/content/Context;"),
    strings = listOf("requestApifromCloud"),
)

/** The supplier last used (from the app's preferences). */
private object CurrentSupplierFingerprint : Fingerprint(
    returnType = API_INFO,
    parameters = listOf(),
    strings = listOf("current_api_vender_id", "current_api_url"),
)

/** "Do this city's detail pages open in the app?" (else the supplier's web pages, from its links). */
private object NativeDetailsFingerprint : Fingerprint(
    returnType = "Z",
    parameters = listOf("Lcom/hihonor/android/totemweather/entity/WeatherInfo;"),
    strings = listOf("VendorId = moji or VendorId = caiyun"),
)

private object ApplicationCreateFingerprint : Fingerprint(
    definingClass = "Lcom/hihonor/android/totemweather/WeatherApplication;",
    name = "onCreate",
    returnType = "V",
    parameters = listOf(),
)

private object HomeResumeFingerprint : Fingerprint(
    definingClass = "Lcom/hihonor/android/totemweather/WeatherHome;",
    name = "onResume",
    returnType = "V",
    parameters = listOf(),
)

private const val PRIVACY_POLICY = "Lcom/hihonor/android/totemweather/PrivacyPolicyActivity;"
private const val PRIVACY_HALF_POP = "Lcom/hihonor/android/totemweather/ExternalPrivacyHalfPopActivity;"

/**
 * The app's first-run notice (a full screen, and a half-screen version for other entry points) states what the app
 * does with the user's data and asks Cancel / Agree; on MagicOS that is Honor's statement (Honor's services, its
 * personalisation and news, links to Honor's agreements). Off MagicOS the only data that leaves the phone goes to
 * Open-Meteo, so the notice says that instead (Weather.notice: the notice text, and Allow / Don't allow on its
 * buttons), and both answers record the user's choice (Consent) and open the app: Don't allow keeps it offline instead
 * of closing it. Returns the notice screens changed.
 */
private fun BytecodePatchContext.useNoticeForOpenMeteo(): Int {
    // The notice text: the method both screens call with themselves for it.
    val onCreate = mutableClassDefBy(PRIVACY_POLICY).methods.first { it.name == "onCreate" }
    val text = onCreate.implementation!!.instructions.mapNotNull {
        ((it as? ReferenceInstruction)?.reference as? MethodReference)?.takeIf { r ->
            (it.opcode == Opcode.INVOKE_STATIC || it.opcode == Opcode.INVOKE_STATIC_RANGE) && r.parameterTypes.map(CharSequence::toString) == listOf("Landroid/app/Activity;") &&
                r.returnType == "Landroid/text/SpannableString;"
        }
    }.distinctBy { "${it.definingClass}->${it.name}" }
    if (text.size != 1) throw PatchException("${text.size} notice texts in $PRIVACY_POLICY.onCreate")
    mutableClassDefBy(text[0].definingClass).methods.first {
        it.name == text[0].name && it.parameterTypes.map(CharSequence::toString) == text[0].parameterTypes.map(CharSequence::toString) &&
            it.returnType == text[0].returnType
    }.addInstructions(
        0,
        """
            invoke-static/range {p0 .. p0}, $WEATHER->notice(Landroid/app/Activity;)Landroid/text/SpannableString;
            move-result-object v0
            return-object v0
        """,
    )

    var screens = 0
    for (screen in listOf(PRIVACY_POLICY, PRIVACY_HALF_POP)) {
        val methods = mutableClassDefBy(screen).methods
        // "Agreed" (with the service mode): what Agree runs.
        val agree = methods.filter { m ->
            m.parameterTypes.map(CharSequence::toString) == listOf("I") && m.returnType == "V" && m.implementation?.instructions?.any {
                ((it as? ReferenceInstruction)?.reference as? StringReference)?.string == "weather_refused_force_sign_agreement"
            } == true
        }
        if (agree.size != 1) throw PatchException("${agree.size} agree methods in $screen")
        val click = methods.firstOrNull { it.name == "onClick" && it.parameterTypes.map(CharSequence::toString) == listOf("Landroid/view/View;") }
            ?: throw PatchException("no onClick in $screen")
        if (click.implementation!!.registerCount - 2 < 2) throw PatchException("$screen.onClick has no free registers")
        click.addInstructionsWithLabels(
            0,
            """
                invoke-static/range {p0 .. p1}, $WEATHER->noticeAnswer(Landroid/app/Activity;Landroid/view/View;)Z
                move-result v0
                if-eqz v0, :app
                move-object/from16 v1, p0
                const/4 v0, 0x2
                invoke-virtual {v1, v0}, $screen->${agree[0].name}(I)V
                return-void
            """,
            ExternalLabel("app", click.getInstruction(0)),
        )
        screens++
    }
    return screens
}

/**
 * The data source the app names (main page footer "Data provided by", city search and add): for its international
 * supplier "Weather China" with that service's logo. The data comes from Open-Meteo, so the name is Open-Meteo's and
 * the logo is left out (an empty drawable: the app sets it by id). Its air quality index is the US one, as the air quality
 * page now says.
 */
private val attributionPatch = resourcePatch(
    description = "Names Open-Meteo as Honor Weather's data source.",
) {
    execute {
        var names = 0
        for (path in listDecodableFiles("res/**").filter { Regex("res/values[^/]*/strings\\.xml").matches(it) }) {
            if (!get(path).readText().contains("\"weather_china_accu\"")) continue
            document(path).use { strings ->
                val list = strings.getElementsByTagName("string")
                for (i in 0 until list.length) {
                    val string = list.item(i) as org.w3c.dom.Element
                    if (string.getAttribute("name") != "weather_china_accu") continue
                    string.textContent = "Open-Meteo"
                    names++
                }
            }
        }
        if (names < 1) throw PatchException("no weather_china_accu string")
        // The air quality page says "Current AQI (CN) is ..": the index is the US one (Open-Meteo's us_aqi).
        var labels = 0
        for (path in listDecodableFiles("res/**").filter { Regex("res/values[^/]*/strings\\.xml").matches(it) }) {
            if (!get(path).readText().contains("\"current_api\"")) continue
            document(path).use { strings ->
                val list = strings.getElementsByTagName("string")
                for (i in 0 until list.length) {
                    val string = list.item(i) as org.w3c.dom.Element
                    if (string.getAttribute("name") != "current_api" || !string.textContent.contains("(CN)")) continue
                    string.textContent = string.textContent.replace("(CN)", "(US)")
                    labels++
                }
            }
        }
        if (labels < 1) throw PatchException("no current_api string")
        val logo = get("res/drawable/ic_china_weather.xml")
        if (!logo.isFile) throw PatchException("no drawable/ic_china_weather")
        logo.writeText(
            "<?xml version=\"1.0\" encoding=\"utf-8\"?>\n<shape xmlns:android=\"http://schemas.android.com/apk/res/android\">" +
                "<size android:width=\"0dp\" android:height=\"0dp\"/></shape>\n",
        )
        log.info("Data source named Open-Meteo: $names strings, $labels AQI labels")
    }
}

/**
 * Honor Weather gets every supplier's URL, key and secret from Honor's weather config server, which refuses a phone
 * without MagicOS's device id and version (HTTP 400 E000103); the port does not fake those. Instead the app's one
 * supplier is Open-Meteo: the supplier list is the extension's (supplier 10001, the international one whose
 * AccuWeather-format API the app's parser and screens are built for, at a service url that never leaves the phone),
 * Honor's config server is never asked, and the app's Retrofit services are the extension's stand-ins, which answer the
 * supplier's requests from Open-Meteo and fail every request to Honor's servers at once. Each process also starts
 * with a proxy selector that lets only Open-Meteo through, and the weather screen asks once whether Open-Meteo may be
 * used.
 */
internal val openMeteoPatch = bytecodePatch(
    description = "Gets Honor Weather's forecasts from Open-Meteo instead of Honor's servers.",
) {
    dependsOn(attributionPatch)
    extendWith(HONOR_WEATHER_EXTENSION)

    execute {
        ServiceFactoryFingerprint.method.addInstructions(
            0,
            """
                invoke-static/range {p1 .. p1}, $WEATHER->service(Ljava/lang/Class;)Ljava/lang/Object;
                move-result-object v0
                return-object v0
            """,
        )
        SupplierListFingerprint.method.addInstructions(
            0,
            """
                invoke-static {}, $WEATHER->suppliers()Ljava/util/List;
                move-result-object v0
                return-object v0
            """,
        )
        SupplierRequestFingerprint.method.addInstructions(0, "return-void")

        // The current supplier and the default one ("no supplier"): Open-Meteo.
        val supplierClass = CurrentSupplierFingerprint.classDef.type
        val suppliers = mutableClassDefBy(supplierClass).methods.filter {
            AccessFlags.STATIC.isSet(it.accessFlags) && it.parameterTypes.isEmpty() && it.returnType == API_INFO
        }
        if (suppliers.size != 2) throw PatchException("${suppliers.size} supplier getters in $supplierClass")
        for (method in suppliers) {
            method.addInstructions(
                0,
                """
                    invoke-static {}, $WEATHER->supplier()$API_INFO
                    move-result-object v0
                    return-object v0
                """,
            )
        }

        // Detail pages (hourly, days, air quality, wind, ...): the international supplier's are its web pages, from the
        // links in its answers; Open-Meteo has none, so they are the app's own pages (as for its Chinese suppliers),
        // filled by the same supplier requests (Accu).
        NativeDetailsFingerprint.method.addInstructionsWithLabels(
            0,
            """
                if-eqz p0, :app
                const/4 v0, 0x1
                return v0
            """,
            ExternalLabel("app", NativeDetailsFingerprint.method.getInstruction(0)),
        )

        ApplicationCreateFingerprint.method.addInstructions(0, "invoke-static/range {p0 .. p0}, $WEATHER->start(Landroid/content/Context;)V")
        HomeResumeFingerprint.method.addInstructions(0, "invoke-static/range {p0 .. p0}, $WEATHER->home(Landroid/app/Activity;)V")
        val notices = useNoticeForOpenMeteo()
        log.info("First-run notice about Open-Meteo: $notices screens")
        log.info("Open-Meteo supplier: 2 supplier getters, service factory, supplier list and config requests replaced")
    }
}
