package org.ungoogled.patches.maps.ui.rectangle

import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.patch.resourcePatch
import org.ungoogled.patches.maps.ui.ShapeResources
import org.ungoogled.patches.maps.ui.activityContextHookPatch
import org.ungoogled.patches.maps.ui.markPatched
import org.ungoogled.patches.maps.ui.shapeShimsPatch
import org.ungoogled.patches.maps.ui.sharedExtensionPatch
import org.ungoogled.patches.shared.Constants.COMPATIBILITY_MAPS
import java.io.File
import java.util.logging.Logger

/** The navigation buttons' round face: a PNG mask whose alpha is a filled circle. */
private val FAB_MASKS = listOf("ic_qu_fab_circle.png", "ic_qu_fab_shadow.png")

/**
 * Sharp-cornered copies of every corner-bearing resource, as -mnc9999 variants:
 * ~115 corner/radius dimens, every style with a cornerSize/cornerRadius item
 * (which is how Material 3's shapeCornerSize* reach the UI), every shape
 * drawable with <corners> or an oval, the pill backgrounds some vectors bake
 * into their path data, and the navigation buttons' circular PNG masks.
 */
private val rectangleShapesResourcePatch = resourcePatch(
    description = "Adds sharp-cornered variants of the app's corner-bearing resources.",
) {
    execute {
        val res = this["res"]
        val dirs = res.listFiles()!!.filter { it.isDirectory && !ShapeResources.isGenerated(it) }
        var dimens = 0; var styles = 0; var drawables = 0; var masks = 0
        for (dir in dirs) {
            val out = File(res, ShapeResources.qualified(dir.name, ShapeResources.RECT))
            when {
                dir.name == "values" || dir.name.startsWith("values-") -> {
                    File(dir, "dimens.xml").takeIf { it.isFile }?.let {
                        val items = ShapeResources.rectDimens(it.readText())
                        if (items.isNotEmpty()) { ShapeResources.writeValues(File(out, "dimens.xml"), items); dimens += items.size }
                    }
                    File(dir, "styles.xml").takeIf { it.isFile }?.let {
                        val items = ShapeResources.rectStyles(it.readText())
                        if (items.isNotEmpty()) { ShapeResources.writeValues(File(out, "styles.xml"), items); styles += items.size }
                    }
                }
                dir.name == "drawable" || dir.name.startsWith("drawable-") -> {
                    for (file in dir.listFiles()!!) {
                        if (file.name.endsWith(".xml")) {
                            val sharp = ShapeResources.rectDrawable(file.readText()) ?: continue
                            out.mkdirs()
                            File(out, file.name).writeText(sharp)
                            drawables++
                        } else if (file.name in FAB_MASKS) {
                            out.mkdirs()
                            File(out, file.name).writeBytes(ShapeResources.squareMask(file.readBytes()))
                            masks++
                        }
                    }
                }
            }
        }
        // Measured against this Maps version's own resources; a large drop means
        // the resource layout changed and the variants no longer reach the UI.
        if (dimens < 50 || styles < 50 || drawables < 50) {
            throw PatchException("only $dimens dimens, $styles styles, $drawables drawables had corners to square")
        }
        if (masks != FAB_MASKS.size) throw PatchException("found $masks of the ${FAB_MASKS.size} navigation button masks")
        Logger.getLogger("RectangleShapes").info("Rectangle shapes: $dimens dimens, $styles styles, $drawables drawables, $masks masks")
    }
}

@Suppress("unused")
val rectangleShapesPatch = bytecodePatch(
    name = "Rectangle shapes",
    description = "Squares off rounded corners across the UI, including the two round navigation buttons.",
    default = true,
) {
    compatibleWith(COMPATIBILITY_MAPS)
    dependsOn(sharedExtensionPatch, activityContextHookPatch, shapeShimsPatch, rectangleShapesResourcePatch)

    execute {
        markPatched("rectShapesPatched")
    }
}
