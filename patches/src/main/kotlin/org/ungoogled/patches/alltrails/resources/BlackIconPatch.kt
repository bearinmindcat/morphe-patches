package org.ungoogled.patches.alltrails.resources

import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.resourcePatch
import org.ungoogled.patches.shared.Constants.COMPATIBILITY_ALLTRAILS
import org.w3c.dom.Element
import java.io.File

private const val BACKGROUND = "morphe_launcher_background"
private const val FOREGROUND = "morphe_launcher_foreground"

/**
 * The launcher icon, and the splash screen that shows the same logo, in white on
 * black instead of AllTrails' neon green (#94F477) on onyx (#161F13), so this
 * build is easy to tell apart from the stock app.
 *
 * AllTrails' icon is adaptive only (minSdk 28): mipmap-anydpi/ic_launcher and
 * ic_launcher_round put color_brand_onyx behind ic_launcher_foreground, a
 * one-path vector. The app uses both elsewhere (the Denali brand colour), so the
 * icon and the splash get their own copies rather than AllTrails' being changed.
 */
@Suppress("unused")
val blackIconPatch = resourcePatch(
    name = "Black icon",
    description = "Uses a white AllTrails logo on black for the launcher icon and the splash screen, instead of " +
        "green on dark green, so this build is easy to tell apart from the stock app.",
    default = true,
) {
    compatibleWith(COMPATIBILITY_ALLTRAILS)

    execute {
        // Patcher 2.0 decodes resources on request: decode what is read below.
        decode("res/drawable/ic_launcher_foreground.xml", "res/mipmap*")
        val res = this["res"]

        // The logo, white. The decoded vector keeps each attribute on its own line; only the
        // fill values change, so its XML declaration stays where it is.
        val logo = File(res, "drawable/ic_launcher_foreground.xml")
        if (!logo.isFile) throw PatchException("drawable/ic_launcher_foreground.xml not found")
        val fills = Regex("android:fillColor=\"[^\"]*\"")
        val text = logo.readText()
        if (!fills.containsMatchIn(text)) throw PatchException("ic_launcher_foreground no longer has a fill colour")
        File(res, "drawable/$FOREGROUND.xml").writeText(fills.replace(text, "android:fillColor=\"#ffffffff\""))

        // The background, black.
        document("res/values/colors.xml").use { xml ->
            val colour = xml.createElement("color")
            colour.setAttribute("name", BACKGROUND)
            colour.textContent = "#ff000000"
            xml.documentElement.appendChild(colour)
        }

        // The adaptive icons.
        val icons = res.listFiles()!!.filter { it.isDirectory && it.name.startsWith("mipmap") }
            .flatMap { dir -> listOf("ic_launcher.xml", "ic_launcher_round.xml").map { File(dir, it) } }
            .filter { it.isFile }
        if (icons.isEmpty()) throw PatchException("no adaptive launcher icon found")
        for (icon in icons) {
            document("res/${icon.parentFile.name}/${icon.name}").use { xml ->
                val background = xml.getElementsByTagName("background").item(0) as? Element
                val foreground = xml.getElementsByTagName("foreground").item(0) as? Element
                if (background == null || foreground == null) throw PatchException("${icon.name} is no longer an adaptive icon")
                background.setAttribute("android:drawable", "@color/$BACKGROUND")
                foreground.setAttribute("android:drawable", "@drawable/$FOREGROUND")
            }
        }

        // The splash screen (StartActivity's theme), which animates the same logo.
        document("res/values/styles.xml").use { xml ->
            val styles = xml.getElementsByTagName("style")
            val splash = (0 until styles.length).map { styles.item(it) as Element }
                .singleOrNull { it.getAttribute("name") == "StartActivityTheme" }
                ?: throw PatchException("StartActivityTheme not found")
            val items = splash.getElementsByTagName("item")
            val set = (0 until items.length).map { items.item(it) as Element }.mapNotNull { item ->
                when (item.getAttribute("name")) {
                    "windowSplashScreenAnimatedIcon" -> item.also { it.textContent = "@drawable/$FOREGROUND" }
                    "windowSplashScreenBackground" -> item.also { it.textContent = "@color/$BACKGROUND" }
                    else -> null
                }
            }
            if (set.size != 2) throw PatchException("StartActivityTheme no longer sets the splash icon and background")
        }
    }
}
