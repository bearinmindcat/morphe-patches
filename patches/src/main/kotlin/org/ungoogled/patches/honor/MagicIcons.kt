package org.ungoogled.patches.honor

import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.ResourcePatchContext

private const val FRAMEWORK_ICON_DRAWABLE = "com.hihonor.android.widget.HnIconVectorDrawable"

/** MagicOS's framework attributes of its icon drawable (androidhnext attr ids) and their names. */
private val FRAMEWORK_ICON_ATTRIBUTES = mapOf(
    "0201011e" to "iconSrc",
    "0201011f" to "firstLayerColor",
    "02010120" to "secondLayerColor",
    "02010122" to "firstLayerAlpha",
    "0201013b" to "width",
)

private val FRAMEWORK_ATTRIBUTE = Regex("""androidhnext:r0x(02[0-9a-f]{6})="([^"]*)"""", RegexOption.IGNORE_CASE)
private val ROOT = Regex("""<(\w[\w.]*)\b([^>]*?)(/?)>""")
private fun attribute(attributes: String, name: String) = Regex("""\b$name="([^"]*)"""").find(attributes)?.groupValues?.get(1)

/**
 * MagicOS draws some icons (notification icons above all) with its framework's own icon drawable,
 * `<drawable class="com.hihonor.android.widget.HnIconVectorDrawable" androidhnext:iconSrc="@androidhnext:drawable/...">`:
 * a framework class and framework icon sources, so off MagicOS the drawable does not inflate, and whatever shows it
 * fails (a notification, which another process draws, is refused). The icon sources are vector paths ("layers" coloured
 * by the drawable), and the Honor UI kit the apps bundle carries copies under MagicOS's names. Rewrites each such
 * drawable into Android's own `<vector>`: the app's copy of the source ([sources]: framework drawable id "@0x0203..." to
 * the app's drawable name), its first and second layer in the colours the drawable gives them. A plain vector
 * inflates anywhere, also in the system's notification views. A source the app's build shrank away (an empty `<x/>`)
 * stops the patch: the port draws that one itself. Returns the drawables rewritten.
 */
internal fun ResourcePatchContext.replaceMagicIconDrawables(sources: Map<String, String>): Int {
    fun source(name: String, seen: Set<String> = emptySet()): Pair<String, String> {
        if (name in seen) throw PatchException("icon source drawable/$name refers to itself")
        val text = get("res/drawable/$name.xml").takeIf { it.isFile }?.readText()
            ?: throw PatchException("icon source drawable/$name not in the app")
        val root = ROOT.findAll(text).first { !it.groupValues[1].startsWith("?") }
        if (root.groupValues[1] != "drawable" && root.groupValues[1] != "vector") {
            throw PatchException("icon source drawable/$name is <${root.groupValues[1]}> (shrunk by the app's build?)")
        }
        val attributes = root.groupValues[2]
        if (root.groupValues[3] == "/") {
            // an icon with weights: the regular one
            val regular = attribute(attributes, "app:iconRegular") ?: throw PatchException("icon source drawable/$name is empty")
            return source(regular.removePrefix("@drawable/"), seen + name)
        }
        val body = text.substring(root.range.last + 1, text.lastIndexOf("</"))
        return attributes to body
    }

    var rewritten = 0
    for (path in listDecodableFiles("res/**").filter { it.endsWith(".xml") }) {
        val file = get(path)
        if (!file.isFile) continue
        val text = file.readText()
        if (!text.contains("\"$FRAMEWORK_ICON_DRAWABLE\"")) continue
        val root = ROOT.findAll(text).first { it.groupValues[1] == "drawable" && it.value.contains(FRAMEWORK_ICON_DRAWABLE) }
        val magic = FRAMEWORK_ATTRIBUTE.findAll(root.groupValues[2]).associate { match ->
            val id = match.groupValues[1].lowercase()
            (FRAMEWORK_ICON_ATTRIBUTES[id] ?: throw PatchException("$path: MagicOS icon attribute 0x$id unknown")) to match.groupValues[2]
        }
        val src = magic["iconSrc"] ?: throw PatchException("$path: icon drawable without a source")
        val name = sources[src.lowercase()] ?: throw PatchException("$path: no app icon source for $src")
        val (sourceAttributes, body) = source(name)
        val own = root.groupValues[2]
        val width = attribute(own, "android:width") ?: magic["width"] ?: "24.0dp"
        val height = attribute(own, "android:height") ?: magic["width"] ?: width
        val vector = buildString {
            append("<vector xmlns:android=\"http://schemas.android.com/apk/res/android\"")
            append(" android:width=\"$width\" android:height=\"$height\"")
            append(" android:viewportWidth=\"${attribute(sourceAttributes, "android:viewportWidth") ?: "96"}\"")
            append(" android:viewportHeight=\"${attribute(sourceAttributes, "android:viewportHeight") ?: "96"}\"")
            attribute(own, "app:autoMirrored")?.let { append(" android:autoMirrored=\"$it\"") }
            magic["firstLayerAlpha"]?.let { append(" android:alpha=\"$it\"") }
            append(">")
            var layers = body
            magic["firstLayerColor"]?.let { layers = layers.replace("\"@color/ic_layer_01\"", "\"$it\"") }
            magic["secondLayerColor"]?.let { layers = layers.replace("\"@color/ic_layer_02\"", "\"$it\"") }
            append(layers)
            append("</vector>")
        }
        val end = if (root.groupValues[3] == "/") root.range.last + 1 else text.indexOf("</drawable>", root.range.last) + "</drawable>".length
        file.writeText(text.substring(0, root.range.first) + vector + text.substring(end))
        rewritten++
    }
    return rewritten
}
