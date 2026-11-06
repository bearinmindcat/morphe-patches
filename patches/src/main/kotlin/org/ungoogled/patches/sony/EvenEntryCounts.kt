package org.ungoogled.patches.sony

import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.ResourcePatchContext
import org.w3c.dom.Element

private const val PAD = "ungoogled_alignment_pad"

/** Value types a pad entry can be declared for (in res/values/<type>s.xml, where Morphe looks for new ones), and its value. */
private val VALUE_PADS = mapOf(
    "bool" to "false",
    "color" to "#00000000",
    "dimen" to "0dp",
    "drawable" to "#00000000",
    "id" to "",
    "integer" to "0",
    "string" to "",
    "fraction" to "0%",
)

/** Types whose entries are neither plain values nor files (an alias of one would not be an entry of the type). */
private val UNPADDABLE = setOf("attr", "style", "styleable", "array", "plurals")

/**
 * Gives every resource type an even number of entries, adding one unused entry named [PAD] where a type has an odd
 * number. For apps built with aapt2's 16-bit entry offsets (Xperia Home 15 uses them for every type): when a patch
 * changes resources (even the manifest), Morphe rewrites the resource table, and its writer puts each type chunk's
 * entries right after its 2-byte offsets, without the padding to 4 bytes that aapt2 adds. With an odd entry count the
 * entries then start at an address Android refuses ("RES_TABLE_TYPE_TYPE entries start at unaligned address"), and the
 * APK does not install. With even counts every chunk stays aligned. Counts come from the decoded public.xml (highest
 * entry id + 1 per type, as the table stores them). Returns the types padded; an odd type no pad is known for stops
 * the patch.
 */
internal fun ResourcePatchContext.evenResourceEntryCounts(): List<String> {
    decode("res/values/public.xml")
    val counts = mutableMapOf<String, Int>()
    val names = mutableMapOf<String, String>()
    document("res/values/public.xml").use { xml ->
        val list = xml.getElementsByTagName("public")
        for (i in 0 until list.length) {
            val e = list.item(i) as Element
            val entry = (java.lang.Long.decode(e.getAttribute("id")).toInt()) and 0xffff
            val type = e.getAttribute("type")
            counts[type] = maxOf(counts[type] ?: 0, entry + 1)
            names.putIfAbsent(type, e.getAttribute("name"))
        }
    }
    if (counts.isEmpty()) throw PatchException("no resources listed in public.xml")
    val odd = counts.filterValues { it % 2 == 1 }.keys.sorted()
    val unknown = odd.filter { it in UNPADDABLE }
    if (unknown.isNotEmpty()) throw PatchException("no alignment pad known for resource types $unknown")

    // Patcher 2.0 decodes resources on request.
    decode(*odd.map { "res/values/${it}s.xml" }.toTypedArray())
    for (type in odd) {
        val path = "res/values/${type}s.xml"
        val file = get(path)
        if (!file.isFile) {
            file.parentFile.mkdirs()
            file.writeText("<?xml version=\"1.0\" encoding=\"utf-8\"?>\n<resources>\n</resources>\n")
        }
        document(path).use { xml ->
            val value = VALUE_PADS[type]
            // A value type gets its own element; a file type (anim, layout, xml, ...) an alias of one of its resources.
            val element = if (value != null && type != "id") xml.createElement(type).apply { textContent = value }
                else xml.createElement("item").apply {
                    setAttribute("type", type)
                    if (value == null) textContent = "@$type/${names.getValue(type)}"
                }
            element.setAttribute("name", PAD)
            xml.documentElement.appendChild(element)
        }
    }
    return odd
}
