package org.ungoogled.patches.maps.ui.black

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.InstructionLocation.MatchAfterImmediately
import app.morphe.patcher.extensions.InstructionExtensions.addInstruction
import app.morphe.patcher.extensions.InstructionExtensions.replaceInstruction
import app.morphe.patcher.fieldAccess
import app.morphe.patcher.literal
import app.morphe.patcher.methodCall
import app.morphe.patcher.opcode
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.patch.resourcePatch
import app.morphe.patcher.string
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import org.ungoogled.patches.maps.ui.SHAPES
import org.ungoogled.patches.maps.ui.ShapeResources
import org.ungoogled.patches.maps.ui.activityContextHookPatch
import org.ungoogled.patches.maps.ui.markPatched
import org.ungoogled.patches.maps.ui.shapeShimsPatch
import org.ungoogled.patches.maps.ui.sharedExtensionPatch
import org.ungoogled.patches.shared.Constants.COMPATIBILITY_MAPS
import org.ungoogled.patches.shared.addInstructionsAtLabel
import java.io.File

/**
 * -mcc9999 copies of every dark neutral colour: values/colors.xml (every dark
 * neutral literal -- night themes reference plenty without "dark" in the
 * name), values-night*, and every drawable with a dark neutral literal fill.
 * Where a drawable also has a Rectangle shapes variant, the combined
 * -mcc9999-mnc9999 copy is written from that squared version, so switching
 * both on never loses the corners (mcc outranks mnc). The squared version is
 * recomputed from the original here rather than read from the other patch's
 * output, so the two resource passes can run in either order, or alone.
 */
private val blackThemeResourcePatch = resourcePatch(
    description = "Adds black variants of the app's dark colour resources.",
) {
    execute {
        val res = this["res"]
        var colours = 0; var drawables = 0; var combined = 0
        val sources = res.listFiles()!!.filter { it.isDirectory && !ShapeResources.isGenerated(it) }

        // Colours. mcc outranks every other qualifier, so once a colour has any -mcc9999
        // variant, that variant beats the colour's own -night definition too. Writing only
        // the colours that change made a day text colour (dark, so blackened into
        // values-mcc9999) win over its light values-night twin on every screen that follows
        // the configuration's night mode -- Settings pages drew black labels on black. So a
        // colour Black theme touches anywhere gets an -mcc9999 copy in EVERY directory that
        // defines it, blackened where that directory's value is a dark neutral and as-is
        // otherwise, and day/night selection works under Black theme exactly as without it.
        val colourDirs = sources.filter { it.name == "values" || it.name.startsWith("values-night") }
            .mapNotNull { dir -> File(dir, "colors.xml").takeIf { it.isFile }?.let { dir to ShapeResources.colourEntries(it.readText()) } }
        val touched = colourDirs.flatMap { (_, entries) -> entries.filter { ShapeResources.blackens(it.second) }.map { it.first } }.toSet()
        for ((dir, entries) in colourDirs) {
            val items = entries.filter { it.first in touched }.map { (name, value) ->
                "    <color name=\"$name\">${if (ShapeResources.blackens(value)) ShapeResources.remap(value) else value}</color>"
            }
            if (items.isEmpty()) continue
            ShapeResources.writeValues(File(res, ShapeResources.qualified(dir.name, ShapeResources.BLACK) + "/colors.xml"), items)
            colours += items.size
        }

        // Drawables: the same rule. A drawable Black theme blackens anywhere gets an
        // -mcc9999 copy in every directory that defines it, and -- because mcc outranks
        // mnc -- where Rectangle shapes squares it anywhere, an -mcc9999-mnc9999 copy in
        // every directory as well (squared where that directory's version has corners), so
        // switching both on never loses the corners or the day/night choice.
        fun resName(file: File) = file.name.substringBefore('.')
        val drawableDirs = sources.filter { it.name == "drawable" || it.name.startsWith("drawable-") }
        val xmls = drawableDirs.flatMap { dir -> dir.listFiles()!!.filter { it.name.endsWith(".xml") } }
        val blackened = xmls.filter { ShapeResources.blackDrawable(it.readText()) != null }.map(::resName).toSet()
        val squared = xmls.filter { resName(it) in blackened && ShapeResources.rectDrawable(it.readText()) != null }.map(::resName).toSet()
        for (dir in drawableDirs) {
            for (file in dir.listFiles()!!.filter { resName(it) in blackened }) {
                val xml = if (file.name.endsWith(".xml")) file.readText() else null
                val black = File(res, ShapeResources.qualified(dir.name, ShapeResources.BLACK)).apply { mkdirs() }.resolve(file.name)
                if (xml != null) black.writeText(ShapeResources.blackDrawable(xml) ?: xml) else file.copyTo(black, overwrite = true)
                drawables++
                if (resName(file) !in squared) continue
                val both = File(res, ShapeResources.qualified(dir.name, ShapeResources.BLACK, ShapeResources.RECT)).apply { mkdirs() }.resolve(file.name)
                if (xml != null) {
                    val sharp = ShapeResources.rectDrawable(xml) ?: xml
                    both.writeText(ShapeResources.blackDrawable(sharp) ?: sharp)
                } else {
                    file.copyTo(both, overwrite = true)
                }
                combined++
            }
        }
        if (colours < 100 || drawables < 10) throw PatchException("only $colours colours and $drawables drawables to blacken")
    }
}

/** Every colour spec leaf returns its int in a register; route each return through Shapes.color(). */
private fun MutableMethod.routeColourReturns(): Int {
    val returns = implementation!!.instructions.withIndex().filter { it.value.opcode == Opcode.RETURN }.map { it.index }
    for (i in returns.reversed()) {
        val register = (implementation!!.instructions[i] as OneRegisterInstruction).registerA
        addInstructionsAtLabel(i, "invoke-static { v$register }, $SHAPES->color(I)I\nmove-result v$register")
    }
    return returns.size
}

/*
 * Maps' colour specs bottom out in three leaves whose b(Context) yields the int
 * the UI paints with. Nine other subclasses of the same base delegate to these
 * or to ColorStateLists (which the shared shims already cover).
 */

/** A literal ARGB colour: the palette, every day/night pair. */
private object LiteralColourSpecFingerprint : Fingerprint(
    name = "b",
    returnType = "I",
    parameters = listOf("Landroid/content/Context;"),
    filters = listOf(
        methodCall(definingClass = "Ljava/lang/Object;", name = "getClass"),
        fieldAccess(opcode = Opcode.IGET, definingClass = "this", type = "I", location = MatchAfterImmediately()),
        opcode(Opcode.RETURN, MatchAfterImmediately()),
    ),
    custom = { _, classDef -> classDef.superclass == "Lbhai;" },
)

/** An @color resource. */
private object ResourceColourSpecFingerprint : Fingerprint(
    name = "b",
    returnType = "I",
    parameters = listOf("Landroid/content/Context;"),
    filters = listOf(
        fieldAccess(opcode = Opcode.IGET_OBJECT, definingClass = "this"),
        fieldAccess(opcode = Opcode.IGET, type = "I", location = MatchAfterImmediately()),
        methodCall(definingClass = "Landroid/content/Context;", name = "getColor", location = MatchAfterImmediately()),
    ),
    custom = { _, classDef -> classDef.superclass == "Lbhai;" },
)

/** A ?attr theme attribute -- the route Material You / system dynamic colours take. */
private object AttributeColourSpecFingerprint : Fingerprint(
    name = "b",
    returnType = "I",
    parameters = listOf("Landroid/content/Context;"),
    filters = listOf(string("%s holds an unexpected type: %s (TypedValue.type = %d)")),
    custom = { _, classDef -> classDef.superclass == "Lbhai;" },
)

/**
 * The account sheet is shown with bundle.putBoolean("enableDynamicColors",
 * true): its ground is then an Android 12+ SYSTEM colour that no app resource
 * can override, so it stayed grey while everything on it went black.
 */
private object AccountSheetDynamicColoursFingerprint : Fingerprint(
    name = "b",
    returnType = "V",
    parameters = emptyList(),
    filters = listOf(
        string("enableDynamicColors"),
        literal(1, location = MatchAfterImmediately()),
        methodCall(definingClass = "Landroid/os/Bundle;", name = "putBoolean", location = MatchAfterImmediately()),
    ),
)

@Suppress("unused")
val blackThemePatch = bytecodePatch(
    name = "Black theme",
    description = "AMOLED-black theme. Pins Maps' own dark mode and its separate navigation colour scheme, " +
        "and remaps colour resources, drawable fills and draw-time paints so no surface is left grey.",
    default = true,
) {
    compatibleWith(COMPATIBILITY_MAPS)
    dependsOn(sharedExtensionPatch, activityContextHookPatch, shapeShimsPatch, blackThemeResourcePatch)

    execute {
        markPatched("blackThemePatched")

        val leaves = listOf(LiteralColourSpecFingerprint, ResourceColourSpecFingerprint, AttributeColourSpecFingerprint)
        if (leaves.map { it.classDef.type }.toSet().size != 3) throw PatchException("colour spec leaves are not three distinct classes")
        val returns = leaves.sumOf { it.method.routeColourReturns() }
        if (returns != 5) throw PatchException("expected 5 colour-spec return sites (1 + 1 + 3), found $returns")

        // Dynamic colours only while Black theme is actually on: `true` becomes !Shapes.BLACK.
        AccountSheetDynamicColoursFingerprint.let { fp ->
            val literal = fp.instructionMatches[1].index
            val register = (fp.method.implementation!!.instructions[literal] as OneRegisterInstruction).registerA
            fp.method.replaceInstruction(literal, "sget-boolean v$register, $SHAPES->BLACK:Z")
            fp.method.addInstruction(literal + 1, "xor-int/lit8 v$register, v$register, 0x1")
        }
    }
}
