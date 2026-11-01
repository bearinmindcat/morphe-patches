package org.ungoogled.patches.honor

import app.morphe.patcher.patch.ResourcePatchContext

private val CUBIC_BEZIER = Regex("""<cubicBezierInterpolator\b([^>]*)/>""")
private fun attribute(attributes: String, name: String) =
    Regex("""android:$name="([^"]*)"""").find(attributes)?.groupValues?.get(1)

/**
 * Honor's apps (and the Honor UI kit they bundle) define some interpolators as `<cubicBezierInterpolator>`, a tag only
 * MagicOS's AnimationUtils knows (its control points in TranslateAnimation's attributes: first point fromXDelta /
 * fromYDelta, second toXDelta / toYDelta); elsewhere loading one throws "Unknown interpolator name". Rewrites each into
 * Android's `<pathInterpolator>` with the same control points. Returns the files rewritten.
 */
internal fun ResourcePatchContext.replaceMagicInterpolators(): Int {
    var rewritten = 0
    for (path in listDecodableFiles("res/**").filter { it.endsWith(".xml") }) {
        val file = get(path)
        if (!file.isFile) continue
        val text = file.readText()
        if (!text.contains("<cubicBezierInterpolator")) continue
        val changed = CUBIC_BEZIER.replace(text) { match ->
            val a = match.groupValues[1]
            """<pathInterpolator xmlns:android="http://schemas.android.com/apk/res/android"""" +
                """ android:controlX1="${attribute(a, "fromXDelta") ?: "0"}" android:controlY1="${attribute(a, "fromYDelta") ?: "0"}"""" +
                """ android:controlX2="${attribute(a, "toXDelta") ?: "1"}" android:controlY2="${attribute(a, "toYDelta") ?: "1"}" />"""
        }
        if (changed != text) {
            file.writeText(changed)
            rewritten++
        }
    }
    return rewritten
}
