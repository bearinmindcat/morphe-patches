package org.ungoogled.patches.xiaomigallery.misc

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.bytecodePatch
import org.ungoogled.patches.samsung.redirectCalls
import org.ungoogled.patches.samsung.rewriteStringLiterals

private const val PRIVATE_ALBUM = "Lorg/ungoogled/xiaomigallery/PrivateAlbum;"
private const val LAUNCHER = "Landroidx/activity/result/ActivityResultLauncher;"

/** Gallery's own folder for private items, relative to the phone's storage (MIUI/Gallery/cloud/secretAlbum on HyperOS). */
private const val PRIVATE_FOLDER = "Android/data/com.miui.gallery/files/secretAlbum"

/** The non-MIUI privacy password helper's three screens (the app "com.xiaomi.privacypassword", HyperOS only). */
private abstract class PrivacyScreenFingerprint(firstParameter: String, action: String) : Fingerprint(
    definingClass = "Lcom/miui/privacy/XmsHelper;",
    returnType = "V",
    parameters = listOf(firstParameter, LAUNCHER),
    strings = listOf("com.xiaomi.privacypassword", action),
)

private object ConfirmFromActivityFingerprint : PrivacyScreenFingerprint(
    "Landroid/app/Activity;", "android.app.action.PRIVACY_PASSWORD_CONFIRM_PASSWORD",
)

private object ConfirmFromFragmentFingerprint : PrivacyScreenFingerprint(
    "Landroidx/fragment/app/Fragment;", "android.app.action.PRIVACY_PASSWORD_CONFIRM_PASSWORD",
)

private object SetPasswordFingerprint : PrivacyScreenFingerprint(
    "Landroidx/fragment/app/Fragment;", "android.app.action.PRIVACY_PASSWORD_SET_NEW_PASSWORD",
)

/** "Is a privacy password set": a secure setting HyperOS's password app writes. */
private object PasswordEnabledFingerprint : Fingerprint(
    definingClass = "Lcom/miui/privacy/XmsWrapper;",
    returnType = "Z",
    parameters = listOf(),
    strings = listOf("privacy_password_is_open"),
)

/**
 * Off HyperOS Gallery's private album ("Pull down to open private folder", "Add to private album") starts HyperOS's
 * privacy password app, which is not there: ActivityNotFoundException, so Gallery froze and crashed (from the second
 * try on while resuming its activity). The album now opens with the phone's screen lock through Android's
 * confirm-credential screen (no lock: Android's "set screen lock"), and its items move to Gallery's own app folder:
 * Gallery's MIUI folder is shared storage that any app with "All files access" can read, and the "encryption" there
 * is keyed by Gallery's public signing certificate. The app folder is deleted with the app; the first unlock says so.
 */
internal val privateAlbumPatch = bytecodePatch(
    description = "Opens the private album with the phone's screen lock and keeps its items in Gallery's own folder.",
) {
    extendWith(XIAOMI_GALLERY_EXTENSION)

    execute {
        ConfirmFromActivityFingerprint.method.addInstructions(
            0,
            """
                invoke-static { p1, p2 }, $PRIVATE_ALBUM->unlock(Landroid/app/Activity;Ljava/lang/Object;)V
                return-void
            """,
        )
        for (fingerprint in listOf(ConfirmFromFragmentFingerprint, SetPasswordFingerprint)) {
            fingerprint.method.apply {
                if (implementation!!.registerCount - 3 < 1) throw PatchException("no local register in XmsHelper.$name")
                addInstructions(
                    0,
                    """
                        invoke-virtual { p1 }, Landroidx/fragment/app/Fragment;->getActivity()Landroidx/fragment/app/FragmentActivity;
                        move-result-object v0
                        invoke-static { v0, p2 }, $PRIVATE_ALBUM->unlock(Landroid/app/Activity;Ljava/lang/Object;)V
                        return-void
                    """,
                )
            }
        }
        PasswordEnabledFingerprint.method.apply {
            if (implementation!!.registerCount - 1 < 1) throw PatchException("no local register in XmsWrapper.$name")
            addInstructions(
                0,
                """
                    iget-object v0, p0, Lcom/miui/privacy/XmsWrapper;->mContext:Landroid/content/Context;
                    invoke-static { v0 }, $PRIVATE_ALBUM->isPasswordEnabled(Landroid/content/Context;)Z
                    move-result v0
                    return v0
                """,
            )
        }

        // Gallery's first-use dialogs describe Xiaomi's "privacy protection password"; PrivateAlbum says what applies.
        val firstUse = redirectCalls(
            PRIVATE_ALBUM,
            mapOf("Lcom/miui/gallery/preference/GalleryPreferences\$Secret;" to setOf("isFirstUsePrivacyPassword()Z")),
        )
        // Measured against 4.3.1.15-global: both in AuthenticatePrivacyPasswordFragment.authenticatePrivacyPassword.
        if (firstUse < 2) throw PatchException("only $firstUse first-use password checks found")

        val moved = rewriteStringLiterals { if (it == "MIUI/Gallery/cloud/secretAlbum") PRIVATE_FOLDER else null }
        java.util.logging.Logger.getLogger("XiaomiGallery").info("Private folder literals: $moved")
        // Measured against 4.3.1.15-global: 25 (storage, scanner, recovery, migration, permission checks).
        if (moved < 20) throw PatchException("only $moved private folder paths found")
    }
}
