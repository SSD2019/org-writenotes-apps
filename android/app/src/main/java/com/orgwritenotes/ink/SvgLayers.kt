package com.orgwritenotes.ink

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.Base64
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream

/**
 * B4: logical layers over a Write SVG document.
 *
 * Layers: template (native lock/unlock APIs), ink (default), highlights. The highlight layer is
 * derived from the document: a `<path>` whose `stroke-opacity` is below 0.95 is a highlight. Hiding
 * sets `display="none"` on those paths (persisted in the file, reversible).
 */
object SvgLayers {
    private val pathTag = Regex("""<path\b[^>]*>""")
    private val strokeOpacity = Regex("""stroke-opacity\s*=\s*"([0-9.]+)"""")
    private val hiddenAttr = Regex("""\s+display\s*=\s*"none"""")

    fun isHighlight(tag: String): Boolean {
        val op = strokeOpacity.find(tag)?.groupValues?.get(1)?.toFloatOrNull() ?: return false
        return op < 0.95f
    }

    fun countHighlights(svg: String): Int = pathTag.findAll(svg).count { isHighlight(it.value) }

    fun highlightsHidden(svg: String): Boolean =
        pathTag.findAll(svg).filter { isHighlight(it.value) }.let { seq ->
            val tags = seq.toList()
            tags.isNotEmpty() && tags.all { hiddenAttr.containsMatchIn(it.value) }
        }

    fun setHighlightsHidden(svg: String, hidden: Boolean): String =
        pathTag.replace(svg) { m ->
            val tag = m.value
            if (!isHighlight(tag)) tag
            else {
                val cleaned = hiddenAttr.replace(tag, "")
                if (!hidden) cleaned
                else {
                    val selfClosing = cleaned.endsWith("/>")
                    val body = cleaned.removeSuffix(if (selfClosing) "/>" else ">").trimEnd()
                    body + " display=\"none\"" + if (selfClosing) "/>" else ">"
                }
            }
        }

    fun gunzipBase64(b64: String): String =
        GZIPInputStream(ByteArrayInputStream(Base64.getMimeDecoder().decode(b64))).use { String(it.readBytes(), Charsets.UTF_8) }

    fun gzipToBase64(svg: String): String {
        val out = ByteArrayOutputStream()
        GZIPOutputStream(out).use { it.write(svg.toByteArray(Charsets.UTF_8)) }
        return Base64.getEncoder().encodeToString(out.toByteArray())
    }

    /** Convenience: toggles highlight visibility on a base64 `.svgz`, returning the new base64. */
    fun toggleHighlightsBase64(svgzBase64: String, hidden: Boolean): String =
        gzipToBase64(setHighlightsHidden(gunzipBase64(svgzBase64), hidden))
}
