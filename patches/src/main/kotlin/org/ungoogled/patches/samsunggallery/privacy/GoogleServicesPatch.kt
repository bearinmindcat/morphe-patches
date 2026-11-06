package org.ungoogled.patches.samsunggallery.privacy

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.string
import org.ungoogled.patches.samsunggallery.SamsungGalleryConstants.COMPATIBILITY_SAMSUNG_GALLERY

/**
 * The SUPPORT_GOOGLE_PLAY_SERVICE constant of Gallery's Features: whether Play services is there, which turns on
 * Google Maps (the map view, the location picker, the map in a photo's details, drawn by Play services' map module
 * inside Gallery) and Play services location.
 */
private object GooglePlayServiceFeatureFingerprint : Fingerprint(
    name = "<init>",
    parameters = listOf(),
    filters = listOf(string("SUPPORT_GOOGLE_PLAY_SERVICE")),
    custom = { _, classDef -> classDef.superclass == "Lcom/samsung/android/gallery/support/utils/Features;" },
)

@Suppress("unused")
val removeGoogleServicesPatch = bytecodePatch(
    name = "Remove Google services",
    description = "Turns off Google Maps and Play services location; maps and places are hidden.",
    default = false,
) {
    compatibleWith(COMPATIBILITY_SAMSUNG_GALLERY)

    execute {
        // Gallery's only Google parts: as on a phone without Play services, Gallery hides its map and location
        // features and never loads Google's map code.
        mutableClassDefBy(GooglePlayServiceFeatureFingerprint.classDef.type).methods.firstOrNull {
            it.name == "getEnabling" && it.parameterTypes.isEmpty() && it.returnType == "Z"
        }?.addInstructions(0, "const/4 v0, 0x0\nreturn v0")
            ?: throw PatchException("Gallery's SUPPORT_GOOGLE_PLAY_SERVICE feature has no getEnabling()")
    }
}
