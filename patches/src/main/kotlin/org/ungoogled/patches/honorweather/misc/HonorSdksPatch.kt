package org.ungoogled.patches.honorweather.misc

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.extensions.InstructionExtensions.addInstructionsWithLabels
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.bytecodePatch
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.reference.MethodReference

/** Sets up Honor's analytics (HiAnalytics, collect URL from Honor's GRS route service); it has its own "skip". */
private object AnalyticsInitFingerprint : Fingerprint(
    returnType = "V",
    parameters = listOf(),
    strings = listOf("initBiUtilsV2 skip"),
)

/** Registers with Honor's push service (MagicOS's push agent) and reports the token to Honor's servers. */
private object PushInitFingerprint : Fingerprint(
    definingClass = "Lcom/hihonor/android/totemweather/push/PushUtilInstance;",
    name = "initPushToken",
    returnType = "V",
    parameters = listOf("Landroid/content/Context;"),
)

/** The app's ad helper (Honor's ad SDK and the game-center attribution SDK it starts at launch). */
private object AdHelperFingerprint : Fingerprint(
    returnType = "V",
    parameters = listOf(),
    strings = listOf("init gameSDK"),
)

private const val FOLDABLE = "Lcom/hihonor/android/fsm/HwFoldScreenManagerEx;->isFoldable()Z"

private const val GRS_CLIENT = "Lcom/hihonor/framework/network/grs/GrsClient;"

/**
 * HiAnalytics' "may report" switch, which the app flips when the user agrees to its notice: it builds the SDK's
 * reporting core and stores the switch in the SDK's own database. Off MagicOS analytics is never set up, so the switch
 * is left alone too.
 */
private const val HI_ANALYTIC_TOOLS = "Lcom/hihonor/hianalytics/util/HiAnalyticTools;"

/**
 * Honor's route service client (GRS: which Honor server answers what, asked at grs.hihonorcloud.com when a client is
 * made and on every lookup), as if Honor's route service had no answer: no client state, no URL, no request.
 */
private val GRS_ANSWERS = mapOf(
    "<init>(Landroid/content/Context;Lcom/hihonor/framework/network/grs/GrsBaseInfo;)V" to
        "invoke-direct {p0}, Ljava/lang/Object;-><init>()V\nreturn-void",
    "ayncGetGrsUrl(Ljava/lang/String;Ljava/lang/String;Lcom/hihonor/framework/network/grs/IQueryUrlCallBack;)V" to
        "if-eqz p3, :none\nconst/4 v0, -0x6\ninvoke-interface {p3, v0}, Lcom/hihonor/framework/network/grs/IQueryUrlCallBack;->onCallBackFail(I)V\n:none\nreturn-void",
    "ayncGetGrsUrls(Ljava/lang/String;Lcom/hihonor/framework/network/grs/IQueryUrlsCallBack;)V" to
        "if-eqz p2, :none\nconst/4 v0, -0x6\ninvoke-interface {p2, v0}, Lcom/hihonor/framework/network/grs/IQueryUrlsCallBack;->onCallBackFail(I)V\n:none\nreturn-void",
    "clearSp()V" to "return-void",
    "forceExpire()Z" to "const/4 v0, 0x0\nreturn v0",
    "synGetGrsUrl(Ljava/lang/String;Ljava/lang/String;)Ljava/lang/String;" to "const/4 v0, 0x0\nreturn-object v0",
    "synGetGrsUrls(Ljava/lang/String;)Ljava/util/Map;" to
        "new-instance v0, Ljava/util/HashMap;\ninvoke-direct {v0}, Ljava/util/HashMap;-><init>()V\nreturn-object v0",
)

/**
 * Honor Weather bundles Honor's analytics (HiAnalytics), push, ad (HiAds), game-center attribution and route service
 * (GRS) SDKs. Off MagicOS they stay dormant: analytics is never set up (its own "skip" path) nor switched on, push
 * never registers, the app's two "may this phone show ads" checks answer no, as they do for children's accounts and
 * foldables, so the ad SDKs are never started and no ad slot is shown, and the route service client asks nothing. (They
 * could not reach Honor anyway: see HonorServers.)
 */
internal val honorSdksPatch = bytecodePatch(
    description = "Keeps Honor's analytics, push and ad SDKs in Honor Weather dormant.",
) {
    execute {
        AnalyticsInitFingerprint.method.addInstructions(0, "return-void")
        val reports = mutableClassDefBy(HI_ANALYTIC_TOOLS).methods.filter { it.name == "enableReport" && it.returnType == "V" }
        // Measured against 18.1.2.311: enableReport(Z) and enableReport(Context, Z).
        if (reports.size != 2) throw PatchException("${reports.size} enableReport methods in $HI_ANALYTIC_TOOLS")
        reports.forEach { it.addInstructions(0, "return-void") }
        PushInitFingerprint.method.addInstructions(0, "return-void")

        val helper = AdHelperFingerprint.classDef.type
        val gates = mutableClassDefBy(helper).methods.filter { m ->
            AccessFlags.STATIC.isSet(m.accessFlags) && m.parameterTypes.isEmpty() && m.returnType == "Z" &&
                m.implementation?.instructions?.any {
                    ((it as? ReferenceInstruction)?.reference as? MethodReference)?.let { r ->
                        "${r.definingClass}->${r.name}(${r.parameterTypes.joinToString("")})${r.returnType}"
                    } == FOLDABLE
                } == true
        }
        // Measured against 18.1.2.311: "ads allowed" and "game ads allowed".
        if (gates.size != 2) throw PatchException("${gates.size} ad checks in $helper")
        gates.forEach { it.addInstructions(0, "const/4 v0, 0x0\nreturn v0") }

        val grs = mutableClassDefBy(GRS_CLIENT).methods
        for ((shape, body) in GRS_ANSWERS) {
            val method = grs.firstOrNull { "${it.name}(${it.parameterTypes.joinToString("")})${it.returnType}" == shape }
                ?: throw PatchException("no $shape in $GRS_CLIENT")
            method.addInstructionsWithLabels(0, body)
        }
        log.info(
            "Honor SDKs: analytics (and its ${reports.size} report switches), push, ${gates.size} ad checks and " +
                "${GRS_ANSWERS.size} route service calls off",
        )
    }
}
