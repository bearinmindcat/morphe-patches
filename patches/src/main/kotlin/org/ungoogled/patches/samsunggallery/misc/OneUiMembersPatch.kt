package org.ungoogled.patches.samsunggallery.misc

import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.bytecodePatch
import org.ungoogled.patches.samsung.redirectOneUiMembers

/**
 * Sends Gallery's uses of One UI's additions to Android classes through OneUi (see redirectOneUiMembers); Settings'
 * pop-over options were the first to fail without it.
 */
internal val oneUiMembersPatch = bytecodePatch(
    description = "Answers One UI's additions to Android classes where the phone has none.",
) {
    extendWith(SAMSUNG_GALLERY_EXTENSION)

    execute {
        val (calls, fields) = redirectOneUiMembers("Lorg/ungoogled/samsunggallery/OneUi;", "Lorg/ungoogled/samsunggallery/Sem;")
        // Measured against 15.9.01.7: 106 calls and 46 field accesses.
        if (calls < 60 || fields < 20) throw PatchException("only $calls One UI calls and $fields field accesses found")
        java.util.logging.Logger.getLogger("SamsungGallery").info("One UI members: $calls calls, $fields fields")
    }
}
