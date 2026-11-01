package org.ungoogled.patches.nothinggallery.misc

import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.bytecodePatch
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import org.ungoogled.patches.nothinggallery.NothingGalleryConstants.COMPATIBILITY_NOTHING_GALLERY

// The two local flags the Application sets at start. isPreInstalled is only true when the app carries FLAG_SYSTEM
// (i.e. it was shipped in the ROM); isNothingDevice reads Build.BRAND. Off a Nothing phone both are false, and
// EntryActivity.onResume then shows a blocking "Gallery can't operate as it's not pre-installed" dialog and quits.
private val GATE_FIELDS = listOf("isPreInstalled", "isNothingDevice")

/**
 * Off a Nothing phone, Nothing Gallery refuses to run: it checks that it was pre-installed in the ROM and that the
 * device is a Nothing device, and otherwise shows a dialog that closes the app. Both are local checks; this makes
 * the two getters report yes, so the gallery opens and shows the device's photos, albums and the viewer. Features
 * that need Nothing's own services (e.g. the cloud AI eraser) are out of scope.
 */
@Suppress("unused")
val enableNonNothingPatch = bytecodePatch(
    name = "Enable non-Nothing OS ROM support",
    description = "Makes Nothing Gallery usable on non-Nothing OS ROMs.",
    default = true,
) {
    compatibleWith(COMPATIBILITY_NOTHING_GALLERY)

    execute {
        fun Method.readsBooleanField(name: String): Boolean =
            implementation?.instructions?.any {
                it.opcode == Opcode.IGET_BOOLEAN &&
                    ((it as? ReferenceInstruction)?.reference as? FieldReference)?.name == name
            } == true

        for (field in GATE_FIELDS) {
            // The getter: a no-argument boolean method whose whole body reads that field and returns it.
            var done = false
            classDefForEach classes@{ classDef ->
                if (done) return@classes
                for (method in classDef.methods) {
                    if (method.returnType != "Z" || method.parameterTypes.isNotEmpty()) continue
                    val instructions = method.implementation?.instructions?.toList() ?: continue
                    if (instructions.size > 4 || !method.readsBooleanField(field)) continue
                    val mutable = mutableClassDefBy(classDef.type).methods.first {
                        it.name == method.name && it.parameterTypes == method.parameterTypes &&
                            it.returnType == method.returnType
                    }
                    // Return true before the field is ever read.
                    mutable.addInstructions(0, "const/4 v0, 0x1\nreturn v0")
                    done = true
                    break
                }
            }
            if (!done) throw PatchException("Nothing Gallery: no getter found for $field")
        }
    }
}
