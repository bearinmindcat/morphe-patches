package org.ungoogled.patches.maps.resources

import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.bytecodePatch
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.formats.Instruction35c
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.iface.reference.StringReference
import org.ungoogled.patches.maps.ui.sharedExtensionPatch

private const val MAPS_PACKAGE = "Lorg/ungoogled/ui/Shapes;->mapsPackage(Ljava/lang/String;)Ljava/lang/String;"

/** Google's own Maps builds, the keys of the table this is about (stock Maps is 6, the internal ones 3, 4, 5 and 12). */
private val GOOGLE_BUILDS = setOf(
    "com.google.android.apps.maps", "com.google.android.apps.gmm", "com.google.android.apps.gmm.dev",
    "com.google.android.apps.gmm.fishfood", "com.google.android.apps.gmm.qp",
)

/**
 * Maps looks its own package name up in a table of Google's builds to number Location
 * sharing's client. Under a new package name the lookup came back empty and opening
 * Location sharing crashed Maps (issue #30, microG Maps, where the row is not trimmed).
 * The lookup now gets stock Maps' package name, the identity the app presents anyway.
 */
internal val stockPackageLookupPatch = bytecodePatch(
    description = "Opens Location sharing under a changed package name.",
) {
    dependsOn(sharedExtensionPatch)

    execute {
        // The table: the one class whose static initializer names Google's builds and nothing else
        // (another lists the same builds with their signing certificates, for trusted callers).
        val tables = mutableListOf<String>()
        classDefForEach { c ->
            val clinit = c.methods.firstOrNull { it.name == "<clinit>" } ?: return@classDefForEach
            val strings = clinit.implementation?.instructions?.mapNotNull {
                ((it as? ReferenceInstruction)?.reference as? StringReference)?.string
            }.orEmpty().toSet()
            if (strings == GOOGLE_BUILDS) tables += c.type
        }
        val table = tables.singleOrNull() ?: throw PatchException("table of Google's Maps builds: found ${tables.size}")
        // Read-only: the table itself is left as it is.
        val field = classDefByOrNull(table)!!.fields.singleOrNull { AccessFlags.STATIC.isSet(it.accessFlags) }
            ?: throw PatchException("$table no longer holds one static table")

        // Its lookups: the table read, the app's package name, then get(package) on the table.
        data class Site(val type: String, val name: String, val signature: String, val index: Int, val register: Int)
        val sites = mutableListOf<Site>()
        classDefForEach { c ->
            for (m in c.methods) {
                val ins = m.implementation?.instructions?.toList() ?: continue
                ins.forEachIndexed { i, read ->
                    val ref = (read as? ReferenceInstruction)?.reference as? FieldReference
                    if (read.opcode != Opcode.SGET_OBJECT || ref == null || ref.definingClass != table || ref.name != field.name) return@forEachIndexed
                    val tableRegister = (read as OneRegisterInstruction).registerA
                    val call = (i + 1..minOf(i + 4, ins.size - 3)).firstOrNull { j ->
                        ((ins[j] as? ReferenceInstruction)?.reference as? MethodReference)?.name == "getPackageName"
                    } ?: return@forEachIndexed
                    val result = ins[call + 1]
                    val get = ins[call + 2]
                    val getRef = (get as? ReferenceInstruction)?.reference as? MethodReference
                    if (result.opcode != Opcode.MOVE_RESULT_OBJECT || getRef?.name != "get" || get !is Instruction35c) return@forEachIndexed
                    val packageRegister = (result as OneRegisterInstruction).registerA
                    if (get.registerC != tableRegister || get.registerD != packageRegister) return@forEachIndexed
                    sites += Site(c.type, m.name, m.parameterTypes.joinToString("") + m.returnType, call + 2, packageRegister)
                }
            }
        }
        if (sites.isEmpty()) throw PatchException("no lookup in $table by the app's package name")
        // Last first, so an earlier site's index still holds when two share a method.
        for (site in sites.sortedByDescending { it.index }) {
            val method = mutableClassDefBy(site.type).methods.single {
                it.name == site.name && it.parameterTypes.joinToString("") + it.returnType == site.signature
            }
            val call = if (site.register <= 15) "invoke-static { v${site.register} }, $MAPS_PACKAGE"
                else "invoke-static/range { v${site.register} .. v${site.register} }, $MAPS_PACKAGE"
            // Right after the package name is read, before get(): no branch lands in between.
            method.addInstructions(
                site.index,
                """
                    $call
                    move-result-object v${site.register}
                """,
            )
        }
    }
}
