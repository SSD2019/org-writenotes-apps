package com.orgwritenotes.ink

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream

/**
 * Merges two versions of a Write `.svgz` sketch by stroke union, for conflict resolution.
 *
 * Strokes (`<path>`) and pasted images (`<image>`) inside the `write-content` group are matched by
 * geometry (`d`, or `href` + position) or by identity (same `id` and `__timestamp`, i.e. the same
 * stroke moved or restyled; the base copy's version wins); every stroke found in only one copy is kept. The copy
 * with the larger page is the base, so nothing falls outside the page. A union cannot know about
 * deletions: a stroke erased on one side but still present on the other comes back.
 */
object SketchMerge {
    data class Result(val svg: String, val baseStrokes: Int, val addedStrokes: Int)

    private val ELEMENT = Regex("""<(path|image)\b[^>]*?/>""")
    private val ATTR_D = Regex("""\sd="([^"]*)"""")
    private val ATTR_HREF = Regex("""\s(?:xlink:)?href="([^"]*)"""")
    private val ATTR_X = Regex("""\sx="([^"]*)"""")
    private val ATTR_Y = Regex("""\sy="([^"]*)"""")
    private val ATTR_ID = Regex("""\sid="([^"]*)"""")
    private val ATTR_TIMESTAMP = Regex("""\s__timestamp="([^"]*)"""")
    private val PAGE_SIZE = Regex("""<svg\b[^>]*class="write-page"[^>]*\swidth="([0-9.]+)(?:px)?"[^>]*\sheight="([0-9.]+)(?:px)?"""")

    /** Merges two gzip-compressed sketches and returns the gzip-compressed union. */
    fun mergeSvgz(a: ByteArray, b: ByteArray): ByteArray? {
        val merged = mergeSvg(gunzip(a) ?: return null, gunzip(b) ?: return null) ?: return null
        return gzip(merged.svg)
    }

    /** Merges two SVG documents; null when either lacks a `write-content` group. */
    fun mergeSvg(a: String, b: String): Result? {
        val (base, other) = if (pageArea(b) > pageArea(a)) b to a else a to b
        val baseRange = contentRange(base) ?: return null
        val otherRange = contentRange(other) ?: return null

        val baseElements = ELEMENT.findAll(base.substring(baseRange.first, baseRange.last)).map { it.value }.toList()
        val seen = HashSet<String>()
        baseElements.forEach { seen.addAll(keys(it)) }
        val additions = StringBuilder()
        var added = 0
        for (m in ELEMENT.findAll(other.substring(otherRange.first, otherRange.last))) {
            val el = m.value
            val k = keys(el)
            if (k.any { it in seen }) continue
            seen.addAll(k)
            added += 1
            // Fresh ids: both copies number their strokes s_1, s_2, … independently.
            val renamed = ATTR_ID.replace(el) { r -> " id=\"m${added}_${r.groupValues[1]}\"" }
            additions.append("    ").append(renamed).append('\n')
        }
        val svg = if (added == 0) base else base.substring(0, baseRange.last) + additions + base.substring(baseRange.last)
        return Result(svg, baseElements.count { it.startsWith("<path") || it.startsWith("<image") }, added)
    }

    private fun keys(el: String): List<String> {
        val geometry = ATTR_D.find(el)?.groupValues?.get(1)?.let { "d:$it" }
            ?: ("img:" + (ATTR_HREF.find(el)?.groupValues?.get(1) ?: el) +
                "@" + (ATTR_X.find(el)?.groupValues?.get(1) ?: "") + "," + (ATTR_Y.find(el)?.groupValues?.get(1) ?: ""))
        val id = ATTR_ID.find(el)?.groupValues?.get(1)
        val ts = ATTR_TIMESTAMP.find(el)?.groupValues?.get(1)
        return if (id != null && ts != null) listOf(geometry, "t:$ts#$id") else listOf(geometry)
    }

    /**
     * Range inside `write-content`: from the end of its opening tag to the start of its closing
     * `</g>` (the last `</g>` before the page's `</svg>`).
     */
    private fun contentRange(svg: String): IntRange? {
        val open = svg.indexOf("class=\"write-content")
        if (open < 0) return null
        val openEnd = svg.indexOf('>', open)
        val pageEnd = svg.indexOf("</svg>", openEnd)
        if (openEnd < 0 || pageEnd < 0) return null
        val close = svg.lastIndexOf("</g>", pageEnd)
        if (close <= openEnd) return null
        return (openEnd + 1)..close
    }

    private fun pageArea(svg: String): Double {
        val m = PAGE_SIZE.find(svg) ?: return 0.0
        return (m.groupValues[1].toDoubleOrNull() ?: 0.0) * (m.groupValues[2].toDoubleOrNull() ?: 0.0)
    }

    fun gunzip(bytes: ByteArray): String? = runCatching {
        GZIPInputStream(ByteArrayInputStream(bytes)).bufferedReader(Charsets.UTF_8).use { it.readText() }
    }.getOrNull()

    fun gzip(text: String): ByteArray {
        val out = ByteArrayOutputStream()
        GZIPOutputStream(out).use { it.write(text.toByteArray(Charsets.UTF_8)) }
        return out.toByteArray()
    }
}
