package org.ungoogled.patches.samsungkeyboard.sboard

import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.patch.resourcePatch
import app.morphe.patcher.patch.stringOption
import org.ungoogled.patches.samsungkeyboard.SamsungKeyboardConstants.COMPATIBILITY_SAMSUNG_KEYBOARD
import org.ungoogled.patches.samsungkeyboard.misc.android13Patch
import org.ungoogled.patches.samsungkeyboard.misc.moveKeyboardLiterals
import org.ungoogled.patches.samsungkeyboard.misc.moveKeyboardManifest
import org.ungoogled.patches.samsungkeyboard.misc.relabelKeyboard
import org.ungoogled.patches.samsungkeyboard.misc.systemAccessPatch
import java.io.File

private const val STOCK_PACKAGE = "com.samsung.android.honeyboard"

/** The keyboard's two adaptive icons (app, round); both become SBoard's. */
private val ICONS = setOf("app_icon.xml", "ic_launcher_round.xml")


/** SBoard's icon: Samsung Keyboard's keyboard, white with violet keys, on near-black. 108 dp, safe zone 18-90. */
private const val ICON_BACKGROUND = "#FF16131F"
private const val KEY = "#FF8B5CF6"
private const val BODY = "M33,38 H75 Q79,38 79,42 V68 Q79,72 75,72 H33 Q29,72 29,68 V42 Q29,38 33,38 Z"
private val KEYS = buildList {
    for (row in 0..1) for (column in 0..5) {
        val x = 34 + column * 7
        val y = 43 + row * 7
        add("M$x,$y H${x + 5} V${y + 5} H$x Z")
    }
    add("M41,57 H67 Q68.5,57 68.5,58.5 V61.5 Q68.5,63 67,63 H41 Q39.5,63 39.5,61.5 V58.5 Q39.5,57 41,57 Z")
}

private fun vector(vararg paths: Pair<String, String>, evenOdd: Boolean = false) = """<?xml version="1.0" encoding="utf-8"?>
<vector xmlns:android="http://schemas.android.com/apk/res/android"
    android:width="108dp" android:height="108dp"
    android:viewportWidth="108" android:viewportHeight="108">
${paths.joinToString("\n") { (colour, data) ->
    "    <path android:fillColor=\"$colour\"${if (evenOdd) " android:fillType=\"evenOdd\"" else ""} android:pathData=\"$data\" />"
}}
</vector>
"""

private val FOREGROUND = vector("#FFFFFFFF" to BODY, *KEYS.map { KEY to it }.toTypedArray())

/** Themed (monochrome) icon: the keyboard with its keys cut out. */
private val MONOCHROME = vector("#FFFFFFFF" to (BODY + " " + KEYS.joinToString(" ")), evenOdd = true)

private const val ADAPTIVE_ICON = """<?xml version="1.0" encoding="utf-8"?>
<adaptive-icon xmlns:android="http://schemas.android.com/apk/res/android">
    <background android:drawable="@color/sboard_icon_background" />
    <foreground android:drawable="@drawable/sboard_icon_foreground" />
    <monochrome android:drawable="@drawable/sboard_icon_monochrome" />
</adaptive-icon>
"""

/**
 * SBoard: Samsung Keyboard as an app of its own, under its own package, name and icon, so it installs next to the
 * system keyboard on a Galaxy phone and stands on its own elsewhere. It moves the package the way "Change package name"
 * does (authorities, permissions and the code that names them), which -- like "Change app name" -- then leaves the
 * name and package to it.
 */
@Suppress("unused")
val buildSBoardPatch = bytecodePatch(
    name = "Build SBoard",
    description = "Builds SBoard, its own keyboard app with its own name and icon.",
    default = false,
) {
    compatibleWith(COMPATIBILITY_SAMSUNG_KEYBOARD)
    category(SBOARD)

    val packageName = stringOption(
        key = "packageName",
        default = "org.ungoogled.sboard",
        title = "Package name",
        description = "Must be a valid Android package name.",
        required = true,
    ) {
        it != null && it != STOCK_PACKAGE && it.matches(Regex("^[a-zA-Z][a-zA-Z0-9_]*(\\.[a-zA-Z][a-zA-Z0-9_]*)+$"))
    }

    val appName = stringOption(
        key = "appName",
        default = "SBoard",
        title = "App name",
        description = "Shown in the keyboard list and App info.",
        required = true,
    )

    dependsOn(
        systemAccessPatch,
        android13Patch,
        resourcePatch {
            execute {
                SBoardSelection.select(this)
                moveKeyboardManifest(packageName.value!!)
                relabelKeyboard(appName.value!!)

                // In the app too (the settings' title, About): the app's own name string reads the new name in every
                // language. Other texts that spell out "Samsung Keyboard" stay.
                decode("res/values*/strings.xml")
                var names = 0
                for (dir in this["res"].listFiles { dir -> dir.name.startsWith("values") }.orEmpty()) {
                    if (!java.io.File(dir, "strings.xml").isFile) continue
                    document("res/${dir.name}/strings.xml").use { xml ->
                        val list = xml.getElementsByTagName("string")
                        for (i in 0 until list.length) {
                            val string = list.item(i) as org.w3c.dom.Element
                            if (string.getAttribute("name") == "app_name") { string.textContent = appName.value!!; names++ }
                        }
                    }
                }
                if (names == 0) throw PatchException("the app's name string was not found")

                decode("res/mipmap-anydpi*")
                val res = this["res"]
                val icons = res.listFiles { dir -> dir.name.startsWith("mipmap-anydpi") }.orEmpty()
                    .flatMap { dir -> dir.listFiles { file -> file.name in ICONS }.orEmpty().toList() }
                if (icons.map { it.name }.toSet() != ICONS) throw PatchException("Samsung Keyboard's adaptive icons not found")
                icons.forEach { it.writeText(ADAPTIVE_ICON) }
                File(res, "drawable").mkdirs()
                File(res, "drawable/sboard_icon_foreground.xml").writeText(FOREGROUND)
                File(res, "drawable/sboard_icon_monochrome.xml").writeText(MONOCHROME)
                document("res/values/colors.xml").use { xml ->
                    xml.documentElement.appendChild(xml.createElement("color").apply {
                        setAttribute("name", "sboard_icon_background")
                        textContent = ICON_BACKGROUND
                    })
                }
            }
        },
    )

    execute {
        SBoardSelection.select(this)
        moveKeyboardLiterals()
    }
}
