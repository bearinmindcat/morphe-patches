package org.ungoogled.patches.shared

import app.morphe.patcher.patch.Compatibility
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.bytecodePatch

private val ANDROID_VERSIONS = mapOf(
    29 to "10", 30 to "11", 31 to "12", 32 to "12L", 33 to "13", 34 to "14", 35 to "15", 36 to "16", 37 to "17",
)

/** "Android 13" for API 33. */
internal fun androidName(sdk: Int) = ANDROID_VERSIONS[sdk]?.let { "Android $it" } ?: "Android API $sdk"

/** The API level of the phone running the patcher (Morphe Manager), or null off Android (the desktop CLI). */
private fun patchingPhoneSdk(): Int? = try {
    Class.forName("android.os.Build\$VERSION").getField("SDK_INT").getInt(null).takeIf { it > 0 }
} catch (e: Throwable) {
    null
}

/**
 * Stops with a clear message before anything else runs, for a port pinned to exact app versions (its generated
 * hidden-API and stand-in lists are that build's own; another build fails deep inside a fingerprint, or patches and
 * crashes): another version names the supported ones and what Android they need, and patching in Morphe Manager on a
 * phone older than every supported version needs says so. Make it the main patch's first dependency.
 */
internal fun supportedVersionCheck(compatibility: Compatibility) = bytecodePatch(
    description = "Checks that the app version and the phone's Android version are supported.",
) {
    execute {
        val name = compatibility.name ?: compatibility.packageName
        val targets = compatibility.targets.filter { it.version != null }
        if (targets.isEmpty()) return@execute
        fun label(version: String?, minSdk: Int?) =
            "$version" + (minSdk?.let { " (${androidName(it)} or newer)" } ?: "")
        val supported = targets.joinToString(" or ") { label(it.version, it.minSdk) }
        val version = packageMetadata.versionName
        val phone = patchingPhoneSdk()
        // The version to patch instead: the newest one this phone can run (targets are listed newest first).
        val fitting = targets.firstOrNull { phone == null || (it.minSdk ?: 0) <= phone }
        if (targets.none { it.version == version }) {
            throw PatchException(
                "This patch supports $name $supported; $version isn't supported. Patch $name " +
                    "${(fitting ?: targets.first()).version} (see docs/APK-SOURCES.md in the patches repository).",
            )
        }
        val needed = targets.first { it.version == version }.minSdk ?: return@execute
        if (phone == null || phone >= needed) return@execute
        throw PatchException(
            "$name $version needs ${androidName(needed)} or newer; this phone runs ${androidName(phone)} " +
                "(API $phone), so the patched app couldn't run on it." +
                (fitting?.let { " Patch $name ${it.version} instead." } ?: ""),
        )
    }
}
