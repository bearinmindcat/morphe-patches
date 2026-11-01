package org.ungoogled.patches.lghealth.privacy

import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.patch.resourcePatch
import com.android.tools.smali.dexlib2.AccessFlags
import org.ungoogled.patches.lghealth.LgHealthConstants.COMPATIBILITY_LG_HEALTH
import org.w3c.dom.Element

private const val FACEBOOK_SDK = "Lcom/facebook/FacebookSdk;"

/** LG account sign-in's "Continue with Facebook": the app's only use of the Facebook SDK. */
private const val FACEBOOK_LOGIN = "Lcom/lge/emp4health/ui/FacebookLoginActivity;"

/** Starts the Facebook SDK in every process as the app starts (a content provider): it then fetches its settings. */
private const val FACEBOOK_INIT_PROVIDER = "com.facebook.internal.FacebookInitProvider"

/**
 * The libraries' own off switches. Facebook: no automatic start, app events or advertising ID. Firebase Analytics:
 * off for good, even after the app's "agree" screen or its settings switch turn it on. Crash reports (the old Firebase
 * Crash) are already off in the app's manifest and only turned on in LG's debug builds; kept off.
 */
private val META_DATA = mapOf(
    "com.facebook.sdk.AutoInitEnabled" to "false",
    "com.facebook.sdk.AutoLogAppEventsEnabled" to "false",
    "com.facebook.sdk.AdvertiserIDCollectionEnabled" to "false",
    "firebase_analytics_collection_deactivated" to "true",
    "firebase_analytics_collection_enabled" to "false",
    "firebase_crash_collection_enabled" to "false",
)

/**
 * The Facebook SDK started itself at app start, before the app's intro was accepted, and asked graph.facebook.com for
 * its settings: the app's only connection on a fresh install. Its start-up provider is removed.
 */
private val manifestPatch = resourcePatch(
    description = "Removes the Facebook SDK's automatic start and switches off its and Firebase's data collection.",
) {
    execute {
        document("AndroidManifest.xml").use { manifest ->
            val application = manifest.getElementsByTagName("application").item(0) as Element
            val children = application.childNodes.let { nodes -> (0 until nodes.length).mapNotNull { nodes.item(it) as? Element } }

            val providers = children.filter { it.tagName == "provider" && it.getAttribute("android:name") == FACEBOOK_INIT_PROVIDER }
            if (providers.size != 1) throw PatchException("expected 1 $FACEBOOK_INIT_PROVIDER, found ${providers.size}")
            providers.forEach { application.removeChild(it) }

            val metaData = children.filter { it.tagName == "meta-data" }.associateBy { it.getAttribute("android:name") }
            if ("com.facebook.sdk.ApplicationId" !in metaData) throw PatchException("no Facebook app id: the app changed shape")
            for ((name, value) in META_DATA) {
                val element = metaData[name] ?: manifest.createElement("meta-data").also {
                    it.setAttribute("android:name", name)
                    application.appendChild(it)
                }
                element.setAttribute("android:value", value)
            }
        }
    }
}

@Suppress("unused")
val lgHealthRemoveTelemetryPatch = bytecodePatch(
    name = "Remove telemetry",
    description = "Stops the Facebook SDK and Firebase Analytics from contacting their servers.",
    default = true,
) {
    compatibleWith(COMPATIBILITY_LG_HEALTH)
    dependsOn(manifestPatch)

    execute {
        val sdk = classDefBy(FACEBOOK_SDK)
        for ((name, parameters) in listOf("sdkInitialize" to listOf("Landroid/content/Context;"), "fullyInitialize" to listOf())) {
            sdk.methods.firstOrNull {
                it.name == name && it.returnType == "V" && AccessFlags.STATIC.isSet(it.accessFlags) &&
                    it.parameterTypes.map(CharSequence::toString) == parameters
            } ?: throw PatchException("$FACEBOOK_SDK->$name not found")
        }

        // Without the start-up provider the SDK is not started, and Facebook sign-in (LoginManager) would throw. It is
        // started when the user picks Facebook sign-in instead, fully, since automatic start is now off.
        val onCreate = mutableClassDefBy(FACEBOOK_LOGIN).methods.firstOrNull {
            it.name == "onCreate" && it.parameterTypes.map(CharSequence::toString) == listOf("Landroid/os/Bundle;")
        } ?: throw PatchException("$FACEBOOK_LOGIN->onCreate not found")
        onCreate.addInstructions(
            0,
            """
                invoke-static { p0 }, $FACEBOOK_SDK->sdkInitialize(Landroid/content/Context;)V
                invoke-static { }, $FACEBOOK_SDK->fullyInitialize()V
            """,
        )
    }
}
