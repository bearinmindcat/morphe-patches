package org.ungoogled.patches.maps.ui

import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.resource.ResourceType
import app.morphe.patcher.resource.hasResourceId
import app.morphe.patcher.resource.resourceId
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod.Companion.toMutable
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.builder.MutableMethodImplementation
import com.android.tools.smali.dexlib2.immutable.ImmutableMethod

/** org.ungoogled.ui.Res: the extension's Maps resource ids, set at patch time. */
internal const val RES = "Lorg/ungoogled/ui/Res;"

/**
 * The id of Maps' resource [type]/[name] in the APK being patched. Resource ids move with
 * every Maps build, so patches never hard-code them; a name Maps no longer has stops the
 * patch with its name.
 */
internal fun mapsId(type: ResourceType, name: String): Int {
    if (!hasResourceId(type, name)) throw PatchException("Maps has no resource ${type.value}/$name")
    return resourceId(type, name).toInt()
}

private val TYPES = ResourceType.entries.associateBy { it.value }

/**
 * Gives Res a static initializer that sets each of its fields, `<type>_<name>`, to the id
 * Maps' resource table gives that name:
 *
 *     static { drawable_gs_close_vd_theme_24 = 0x7f080524; ... }
 */
internal fun BytecodePatchContext.setExtensionResourceIds(): Int {
    val res = mutableClassDefBy(RES)
    if (res.methods.any { it.name == "<clinit>" }) throw PatchException("$RES already has a static initializer")
    val fields = res.fields.filter { AccessFlags.STATIC.isSet(it.accessFlags) && it.type == "I" }
    if (fields.isEmpty()) throw PatchException("$RES lists no resources")
    val missing = mutableListOf<String>()
    val sets = fields.joinToString("\n") { field ->
        val split = field.name.indexOf('_')
        val type = TYPES[field.name.substring(0, maxOf(split, 0))]
            ?: throw PatchException("$RES->${field.name} does not start with a resource type")
        val name = field.name.substring(split + 1)
        val id = if (hasResourceId(type, name)) resourceId(type, name).toInt() else 0.also { missing += "${type.value}/$name" }
        "const v0, $id\nsput v0, $RES->${field.name}:I"
    }
    if (missing.isNotEmpty()) throw PatchException("Maps has no resource ${missing.joinToString()}")
    res.methods.add(
        ImmutableMethod(
            RES, "<clinit>", emptyList(), "V",
            AccessFlags.STATIC.value or AccessFlags.CONSTRUCTOR.value, null, null, MutableMethodImplementation(1),
        ).toMutable().apply { addInstructions(0, "$sets\nreturn-void") },
    )
    return fields.size
}
