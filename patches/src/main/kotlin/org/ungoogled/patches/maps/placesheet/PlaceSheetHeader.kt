package org.ungoogled.patches.maps.placesheet

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.string
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.reference.StringReference
import com.android.tools.smali.dexlib2.iface.reference.TypeReference

/**
 * The click handler of one directory-carousel card, the only code that opens the
 * "OnDirectoryCarouselItemClicked" trace section. Its class is the card's view model.
 */
private object DirectoryCarouselItemClickFingerprint : Fingerprint(
    filters = listOf(string("OnDirectoryCarouselItemClicked")),
)

/**
 * The place sheet header's view model: the one class that both mentions
 * "GeospatialContent" (one other class does too) and unpacks directory cards.
 */
internal fun BytecodePatchContext.placeSheetHeaderType(): String {
    val cardType = DirectoryCarouselItemClickFingerprint.method.definingClass
    val headers = mutableListOf<String>()
    classDefForEach { classDef ->
        if (classDef.type.startsWith("Lorg/ungoogled/")) return@classDefForEach
        var geospatial = false
        var cards = false
        for (method in classDef.methods) {
            for (insn in method.implementation?.instructions ?: continue) {
                val ref = (insn as? ReferenceInstruction)?.reference ?: continue
                if (ref is StringReference && ref.string == "GeospatialContent") geospatial = true
                if (insn.opcode == Opcode.CHECK_CAST && ref is TypeReference && ref.type == cardType) cards = true
            }
        }
        if (geospatial && cards) headers += classDef.type
    }
    return headers.singleOrNull()
        ?: throw PatchException("expected one place sheet header, found ${headers.size}")
}
