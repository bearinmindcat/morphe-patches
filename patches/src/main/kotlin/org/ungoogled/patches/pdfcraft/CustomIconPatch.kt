package org.ungoogled.patches.pdfcraft

import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.booleanOption
import app.morphe.patcher.patch.resourcePatch
import app.morphe.patcher.patch.stringOption
import org.ungoogled.patches.pdfcraft.PdfCraftConstants.COMPATIBILITY_PDFCRAFT
import org.w3c.dom.Element
import java.io.File

private const val ICON = "pdfcraft_icon"
private const val FOREGROUND = "pdfcraft_icon_foreground"
private const val BACKGROUND = "pdfcraft_icon_background"
private const val IMAGE = "pdfcraft_icon_image"
private val COLOUR = Regex("^#([0-9A-Fa-f]{6}|[0-9A-Fa-f]{8})$")
private val IMAGE_TYPES = mapOf("png" to "png", "jpg" to "jpg", "jpeg" to "jpg", "webp" to "webp")

/**
 * PdfCraft's launcher icon (one adaptive icon: a background colour and the lion, inset 18 dp) replaced by one of
 * the built-in PDF icons or the user's own image (issues #15 and #27 of pdfcraft-android). The patch adds its own
 * adaptive icon and points the app's icon and round icon at it; the app's ic_launcher stays as it is for anything
 * else that shows it.
 */
@Suppress("unused")
val customIconPatch = resourcePatch(
    name = "Custom icon",
    description = "Replaces the launcher icon with a PDF icon or your own image.",
    default = false,
) {
    compatibleWith(COMPATIBILITY_PDFCRAFT)

    val design = stringOption(
        key = "design",
        default = BUILT_IN_ICONS.keys.first(),
        values = BUILT_IN_ICONS.entries.associate { (key, value) -> value.title to key },
        title = "Icon",
        description = "Built-in icon to use.",
        required = true,
    ) { it in BUILT_IN_ICONS }

    val image = stringOption(
        key = "image",
        default = null,
        title = "Your own image",
        description = "Path to a square PNG, JPG or WebP. Used instead of the icon above.",
        required = false,
    ) { it.isNullOrBlank() || it.substringAfterLast('.', "").lowercase() in IMAGE_TYPES }

    val background = stringOption(
        key = "background",
        default = "#FFFFFF",
        title = "Background",
        description = "Colour behind your own image.",
        required = true,
    ) { it != null && COLOUR.matches(it) }

    val fill = booleanOption(
        key = "fill",
        default = false,
        title = "Fill the whole icon",
        description = "Your own image covers the icon edge to edge instead of sitting inside it.",
        required = true,
    )

    execute {
        decode("res/mipmap*")
        val res = this["res"]
        val ownImage = image.value?.takeIf { it.isNotBlank() }?.let { File(it) }

        val backgroundColour: String
        if (ownImage != null) {
            if (!ownImage.isFile) throw PatchException("Your own image was not found: ${ownImage.path}")
            val type = IMAGE_TYPES[ownImage.extension.lowercase()]
                ?: throw PatchException("Your own image must be a PNG, JPG or WebP: ${ownImage.name}")
            File(res, "drawable-nodpi").mkdirs()
            ownImage.copyTo(File(res, "drawable-nodpi/$IMAGE.$type"), overwrite = true)
            // The adaptive icon's safe zone is the middle 66 of its 108 dp: 18 dp in from the edge, as PdfCraft's own.
            val inset = if (fill.value == true) "0dp" else "18dp"
            File(res, "drawable/$FOREGROUND.xml").apply { parentFile.mkdirs() }.writeText(
                """
                    <?xml version="1.0" encoding="utf-8"?>
                    <inset xmlns:android="http://schemas.android.com/apk/res/android"
                        android:drawable="@drawable/$IMAGE" android:inset="$inset" />
                """.trimIndent() + "\n",
            )
            backgroundColour = background.value!!
        } else {
            val builtIn = BUILT_IN_ICONS[design.value] ?: throw PatchException("Unknown icon: ${design.value}")
            File(res, "drawable/$FOREGROUND.xml").apply { parentFile.mkdirs() }.writeText(builtIn.foreground)
            backgroundColour = builtIn.background
        }

        document("res/values/colors.xml").use { xml ->
            xml.documentElement.appendChild(
                xml.createElement("color").apply {
                    setAttribute("name", BACKGROUND)
                    textContent = backgroundColour
                },
            )
        }

        File(res, "mipmap-anydpi").mkdirs()
        File(res, "mipmap-anydpi/$ICON.xml").writeText(
            """
                <?xml version="1.0" encoding="utf-8"?>
                <adaptive-icon xmlns:android="http://schemas.android.com/apk/res/android">
                    <background android:drawable="@color/$BACKGROUND" />
                    <foreground android:drawable="@drawable/$FOREGROUND" />
                </adaptive-icon>
            """.trimIndent() + "\n",
        )

        document("AndroidManifest.xml").use { manifest ->
            val application = manifest.getElementsByTagName("application").item(0) as? Element
                ?: throw PatchException("no application element in the manifest")
            application.setAttribute("android:icon", "@mipmap/$ICON")
            application.setAttribute("android:roundIcon", "@mipmap/$ICON")
        }
    }
}
