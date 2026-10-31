package org.ungoogled.patches.samsung

import app.morphe.patcher.patch.ResourcePatchContext
import java.io.File

/** One UI's own vector format ("SPR"), which only One UI's framework can draw. */
private val SPR_MAGIC = byteArrayOf('S'.code.toByte(), 'P'.code.toByte(), 'R'.code.toByte(), 0)

/** Plain icons (24 x 24) for the media controls Samsung ships as SPR, by the last word of the drawable's name. */
private val ICONS = listOf(
    "pause" to "M6,5h4v14H6zM14,5h4v14h-4z",
    "play" to "M8,5v14l11,-7z",
    "next" to "M6,18l8.5,-6L6,6v12zM16,6v12h2V6h-2z",
    "prev" to "M6,6h2v12H6zM9.5,12l8.5,6V6z",
    "stop" to "M6,6h12v12H6z",
    "close" to "M19,6.41L17.59,5 12,10.59 6.41,5 5,6.41 10.59,12 5,17.59 6.41,19 12,13.41 17.59,19 19,17.59 13.41,12z",
    "mute" to "M16.5,12c0,-1.77 -1.02,-3.29 -2.5,-4.03v2.21l2.45,2.45c0.03,-0.2 0.05,-0.41 0.05,-0.63zM19,12c0,0.94 -0.2,1.82 -0.54,2.64l1.51,1.51C20.63,14.91 21,13.5 21,12c0,-4.28 -2.99,-7.86 -7,-8.77v2.06c2.89,0.86 5,3.54 5,6.71zM4.27,3L3,4.27 7.73,9H3v6h4l5,5v-6.73l4.25,4.25c-0.67,0.52 -1.42,0.93 -2.25,1.18v2.06c1.38,-0.31 2.63,-0.95 3.69,-1.81L19.73,21 21,19.73l-9,-9L4.27,3zM12,4L9.91,6.09 12,8.18V4z",
)

private fun vector(path: String?) = """<?xml version="1.0" encoding="utf-8"?>
<vector xmlns:android="http://schemas.android.com/apk/res/android" android:width="24dp" android:height="24dp" android:viewportWidth="24" android:viewportHeight="24">
    <path android:fillColor="${if (path != null) "#FFFFFFFF" else "#00000000"}" android:pathData="${path ?: "M0,0h1v1H0z"}" />
</vector>
"""

/**
 * Off One UI a drawable in Samsung's SPR vector format cannot be decoded: inflating it fails, in the app or (for a
 * notification's views) in the system UI, which then kills the app. Every SPR drawable is replaced with a plain vector
 * drawable of the same name: a media control icon where the name says which one, otherwise an empty one. Returns the
 * names replaced, with what each became ("" = empty).
 */
internal fun ResourcePatchContext.replaceSprDrawables(): Map<String, String> {
    // Patcher 2.0 decodes resources on request.
    decode("res/drawable*/*")
    val res = this["res"]
    val replaced = sortedMapOf<String, String>()
    for (dir in res.listFiles()!!.filter { it.isDirectory && (it.name == "drawable" || it.name.startsWith("drawable-")) }) {
        for (file in dir.listFiles()!!) {
            if (!file.isFile || file.name.endsWith(".xml")) continue
            val head = file.inputStream().use { it.readNBytes(4) }
            if (!head.contentEquals(SPR_MAGIC)) continue
            val name = file.name.substringBefore('.')
            // A control's icon ("..._control_ic_play", "..._btn_mute"), not an image that merely ends in a word.
            val control = name.contains("control") || name.contains("btn")
            val icon = ICONS.firstOrNull { (key, _) -> control && name.substringAfterLast('_') == key }
            file.delete()
            File(dir, "$name.xml").writeText(vector(icon?.second))
            replaced["${dir.name}/$name"] = icon?.first ?: ""
        }
    }
    return replaced
}
