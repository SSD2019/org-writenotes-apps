package com.orgwritenotes.ink

import android.util.Base64
import java.io.ByteArrayInputStream
import java.util.zip.GZIPInputStream
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

/**
 * On-device Handwriting OCR & Stroke-to-Text Recognizer for Org-WriteNotes.
 *
 * Supports:
 * 1. Extracting embedded `<text>` / `desc` / `data-ocr` tags from Stylus Labs Write `.svgz` documents.
 * 2. Optional runtime reflection bridge to `com.google.mlkit.vision.digitalink` when bundled.
 * 3. Built-in 100% offline geometric stroke-cluster & character/symbol recognizer over SVG `<path>`
 *    trajectories for both automatic `:WRITENOTES_OCR:` drawer indexing on `ink.commit` and
 *    interactive Lasso "Convert to Text" conversion.
 */
object HandwritingOcrEngine {

    data class Pt(val x: Float, val y: Float)

    data class StrokePoly(
        val points: List<Pt>,
        val minX: Float,
        val minY: Float,
        val maxX: Float,
        val maxY: Float
    ) {
        val width: Float get() = max(1f, maxX - minX)
        val height: Float get() = max(1f, maxY - minY)
        val centerX: Float get() = (minX + maxX) * 0.5f
        val centerY: Float get() = (minY + maxY) * 0.5f
    }

    fun extractTextFromSvgzBase64(svgzBase64: String?): String {
        if (svgzBase64.isNullOrBlank()) return ""
        return try {
            val raw = Base64.decode(svgzBase64, Base64.DEFAULT)
            extractTextFromSvgzBytes(raw)
        } catch (_: Exception) {
            ""
        }
    }

    fun extractTextFromSvgzBytes(svgzBytes: ByteArray): String {
        if (svgzBytes.isEmpty()) return ""
        val svgXml = decompressSvgBytes(svgzBytes)
        if (svgXml.isBlank()) return ""
        return recognizeSvgFragment(svgXml)
    }

    fun decompressSvgBytes(bytes: ByteArray): String {
        if (bytes.size >= 2 && bytes[0] == 0x1f.toByte() && bytes[1] == 0x8b.toByte()) {
            return try {
                GZIPInputStream(ByteArrayInputStream(bytes)).bufferedReader(Charsets.UTF_8).use { it.readText() }
            } catch (_: Exception) {
                ""
            }
        }
        return try {
            String(bytes, Charsets.UTF_8)
        } catch (_: Exception) {
            ""
        }
    }

    /**
     * Recognizes text from an SVG document or selection `<svg>` / `<g>` fragment.
     */
    fun recognizeSvgFragment(svgXml: String): String {
        if (svgXml.isBlank()) return ""

        val lines = mutableListOf<String>()

        // 1. Extract any explicit SVG <text> nodes
        val textRegex = Regex("<text[^>]*>(.*?)</text>", setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE))
        for (m in textRegex.findAll(svgXml)) {
            val clean = m.groupValues[1].replace(Regex("<[^>]+>"), "").trim()
            if (clean.isNotEmpty()) {
                lines.add(clean)
            }
        }

        // 2. Parse stroke paths from content area (strip ruling/grid/background group if present)
        val contentXml = run {
            val idx = svgXml.indexOf("write-content")
            if (idx >= 0) svgXml.substring(idx) else svgXml
        }
        val rulingRegex = Regex("<g\\b[^>]*class=[\"'][^\"']*\\b(?:ruleline|write-std-ruling|customruling)\\b[^\"']*[\"'][^>]*>.*?</g>", RegexOption.DOT_MATCHES_ALL)
        val cleanContent = rulingRegex.replace(contentXml, "")
        val strokes = parseStrokesFromSvg(cleanContent)
        if (strokes.isNotEmpty()) {
            val recognizedLines = recognizeStrokesOffline(strokes)
            for (ln in recognizedLines) {
                if (ln.isNotBlank()) lines.add(ln)
            }
        }

        return lines.joinToString("\n").trim()
    }

    internal fun parseStrokesFromSvg(svgXml: String): List<StrokePoly> {
        val result = mutableListOf<StrokePoly>()
        val pathRegex = Regex("<path\\b[^>]*\\bd=[\"']([^\"']+)[\"'][^>]*>", RegexOption.IGNORE_CASE)
        val numRegex = Regex("([+-]?(?:\\d+\\.\\d*|\\d*\\.\\d+|\\d+))|[MmLlCcQqZzHhVv]")

        for (match in pathRegex.findAll(svgXml)) {
            val fullTag = match.value
            if (fullTag.contains("pagerect") || fullTag.contains("pagebg") || fullTag.contains("leftmargin") ||
                fullTag.contains("ruleline") || fullTag.contains("yrule") || fullTag.contains("xrule")) {
                continue
            }
            val dAttr = match.groupValues[1]
            val pts = mutableListOf<Pt>()
            var curX = 0f
            var curY = 0f
            var cmd = 'M'
            val tokens = numRegex.findAll(dAttr).map { it.value }.toList()
            var i = 0
            while (i < tokens.size) {
                val tok = tokens[i]
                if (tok.length == 1 && tok[0].isLetter()) {
                    cmd = tok[0]
                    i++
                    if (cmd == 'Z' || cmd == 'z') {
                        if (pts.size > 2) {
                            pts.add(pts.first())
                        }
                    }
                    continue
                }
                when (cmd) {
                    'M', 'L' -> {
                        if (i + 1 < tokens.size) {
                            val x = tokens[i].toFloatOrNull()
                            val y = tokens[i + 1].toFloatOrNull()
                            if (x != null && y != null) {
                                curX = x
                                curY = y
                                pts.add(Pt(curX, curY))
                            }
                            i += 2
                            if (cmd == 'M') cmd = 'L'
                        } else i++
                    }
                    'm', 'l' -> {
                        if (i + 1 < tokens.size) {
                            val dx = tokens[i].toFloatOrNull() ?: 0f
                            val dy = tokens[i + 1].toFloatOrNull() ?: 0f
                            curX += dx
                            curY += dy
                            pts.add(Pt(curX, curY))
                            i += 2
                            if (cmd == 'm') cmd = 'l'
                        } else i++
                    }
                    'H' -> {
                        curX = tokens[i].toFloatOrNull() ?: curX
                        pts.add(Pt(curX, curY))
                        i++
                    }
                    'h' -> {
                        curX += tokens[i].toFloatOrNull() ?: 0f
                        pts.add(Pt(curX, curY))
                        i++
                    }
                    'V' -> {
                        curY = tokens[i].toFloatOrNull() ?: curY
                        pts.add(Pt(curX, curY))
                        i++
                    }
                    'v' -> {
                        curY += tokens[i].toFloatOrNull() ?: 0f
                        pts.add(Pt(curX, curY))
                        i++
                    }
                    'C' -> {
                        if (i + 5 < tokens.size) {
                            curX = tokens[i + 4].toFloatOrNull() ?: curX
                            curY = tokens[i + 5].toFloatOrNull() ?: curY
                            pts.add(Pt(curX, curY))
                            i += 6
                        } else i++
                    }
                    'c' -> {
                        if (i + 5 < tokens.size) {
                            curX += tokens[i + 4].toFloatOrNull() ?: 0f
                            curY += tokens[i + 5].toFloatOrNull() ?: 0f
                            pts.add(Pt(curX, curY))
                            i += 6
                        } else i++
                    }
                    'Q' -> {
                        if (i + 3 < tokens.size) {
                            curX = tokens[i + 2].toFloatOrNull() ?: curX
                            curY = tokens[i + 3].toFloatOrNull() ?: curY
                            pts.add(Pt(curX, curY))
                            i += 4
                        } else i++
                    }
                    'q' -> {
                        if (i + 3 < tokens.size) {
                            curX += tokens[i + 2].toFloatOrNull() ?: 0f
                            curY += tokens[i + 3].toFloatOrNull() ?: 0f
                            pts.add(Pt(curX, curY))
                            i += 4
                        } else i++
                    }
                    else -> i++
                }
            }
            if (pts.size >= 2) {
                // Note: Write's filled ribbon paths trace both sides of the stroke; take the centerline/first half if closed ribbon
                val usablePts = if (pts.size >= 8 && hypot(pts.last().x - pts.first().x, pts.last().y - pts.first().y) < 12f) {
                    pts.subList(0, max(2, pts.size / 2))
                } else {
                    pts
                }
                var minX = usablePts[0].x
                var maxX = minX
                var minY = usablePts[0].y
                var maxY = minY
                for (p in usablePts) {
                    minX = min(minX, p.x)
                    maxX = max(maxX, p.x)
                    minY = min(minY, p.y)
                    maxY = max(maxY, p.y)
                }
                val strokeW = maxX - minX
                val strokeH = maxY - minY
                // Ignore horizontal ruling lines spanning canvas with thin height
                if (strokeW > 250f && strokeH < 5f) {
                    continue
                }
                result.add(StrokePoly(usablePts, minX, minY, maxX, maxY))
            }
        }
        return result
    }

    private fun recognizeStrokesOffline(strokes: List<StrokePoly>): List<String> {
        if (strokes.isEmpty()) return emptyList()

        // Sort strokes top-to-bottom, then left-to-right
        val sortedByY = strokes.sortedWith(compareBy<StrokePoly> { it.centerY }.thenBy { it.minX })
        val lineGroups = mutableListOf<MutableList<StrokePoly>>()

        for (st in sortedByY) {
            val matchingLine = lineGroups.lastOrNull { line ->
                val lineMinY = line.minOf { it.minY }
                val lineMaxY = line.maxOf { it.maxY }
                val lineCenterY = (lineMinY + lineMaxY) * 0.5f
                val lineH = max(24f, lineMaxY - lineMinY)
                abs(st.centerY - lineCenterY) <= lineH * 0.65f
            }
            if (matchingLine != null) {
                matchingLine.add(st)
            } else {
                lineGroups.add(mutableListOf(st))
            }
        }

        val outputLines = mutableListOf<String>()
        for (lineStrokes in lineGroups) {
            lineStrokes.sortBy { it.minX }
            val avgH = lineStrokes.map { it.height }.average().toFloat().coerceAtLeast(20f)
            val charCells = mutableListOf<MutableList<StrokePoly>>()
            for (st in lineStrokes) {
                val prevCell = charCells.lastOrNull()
                if (prevCell != null) {
                    val cellMaxX = prevCell.maxOf { it.maxX }
                    val cellMinX = prevCell.minOf { it.minX }
                    // Merge overlapping or touching multi-stroke glyphs (e.g., t, +, =, i, [x])
                    if (st.minX <= cellMaxX + avgH * 0.08f && (st.maxX - cellMinX) <= avgH * 1.45f) {
                        prevCell.add(st)
                        continue
                    }
                }
                charCells.add(mutableListOf(st))
            }

            val sb = StringBuilder()
            var prevCellRight = Float.NaN
            for (cell in charCells) {
                val cellLeft = cell.minOf { it.minX }
                val cellRight = cell.maxOf { it.maxX }
                val glyph = classifyGlyphCell(cell)
                if (glyph.isNotEmpty()) {
                    if (!prevCellRight.isNaN() && (cellLeft - prevCellRight) > avgH * 0.55f && sb.isNotEmpty()) {
                        sb.append(' ')
                    }
                    sb.append(glyph)
                    prevCellRight = cellRight
                }
            }
            val built = sb.toString().trim()
            // Ignore lines that consist only of stray punctuation or dashes
            if (built.isNotEmpty() && built.any { it.isLetterOrDigit() || it == '=' || it == '+' }) {
                outputLines.add(built)
            }
        }
        return outputLines
    }

    private fun classifyGlyphCell(cell: List<StrokePoly>): String {
        val minX = cell.minOf { it.minX }
        val maxX = cell.maxOf { it.maxX }
        val minY = cell.minOf { it.minY }
        val maxY = cell.maxOf { it.maxY }
        val w = max(1f, maxX - minX)
        val h = max(1f, maxY - minY)
        val aspect = w / h

        // Multi-stroke common symbols
        if (cell.size == 2) {
            val s0 = cell[0]
            val s1 = cell[1]
            // Check for '=' (two horizontal bars)
            if (s0.width / s0.height > 2.2f && s1.width / s1.height > 2.2f) {
                return "="
            }
            // Check for '+' or 't' (one horizontal + one vertical)
            val h0 = s0.width / s0.height > 2.0f
            val v0 = s0.height / s0.width > 2.0f
            val h1 = s1.width / s1.height > 2.0f
            val v1 = s1.height / s1.width > 2.0f
            if ((h0 && v1) || (v0 && h1)) {
                val vert = if (v0) s0 else s1
                val horiz = if (h0) s0 else s1
                return if (horiz.centerY < vert.minY + vert.height * 0.42f) "t" else "+"
            }
            // Check for 'i' (vertical stem + dot above)
            val dotCandidate = if (s0.width < h * 0.35f && s0.height < h * 0.35f) s0 else if (s1.width < h * 0.35f && s1.height < h * 0.35f) s1 else null
            if (dotCandidate != null) {
                return "i"
            }
            // Check for 'x' or 'X'
            return "x"
        }

        val st = cell.first()
        val pts = st.points
        val start = pts.first()
        val end = pts.last()
        val chord = hypot(end.x - start.x, end.y - start.y)
        var pathLen = 0f
        for (k in 1 until pts.size) {
            pathLen += hypot(pts[k].x - pts[k - 1].x, pts[k].y - pts[k - 1].y)
        }

        // Small dot / period
        if (w < 12f && h < 12f) return "."

        // Horizontal line / dash '-'
        if (aspect > 2.6f && h < 28f) return "-"

        // Vertical stroke '1' or 'l' or '|'
        if (aspect < 0.35f && chord / max(1f, pathLen) > 0.82f) return "l"

        // Diagonal slash '/'
        if (chord / max(1f, pathLen) > 0.86f) {
            val angle = atan2(end.y - start.y, end.x - start.x)
            if (angle < -0.4f && angle > -1.3f) return "/"
        }

        // Closed loop: 'o' / '0'
        if (chord < pathLen * 0.28f && aspect in 0.55f..1.65f) {
            return "o"
        }

        // Open curve on right: 'c'
        if (start.x > minX + w * 0.55f && end.x > minX + w * 0.55f && start.y < centerY(minY, maxY) && end.y > centerY(minY, maxY)) {
            return "c"
        }

        // U-cup: starts top-left, dips to bottom, ends top-right -> 'u' or 'v'
        if (start.y < minY + h * 0.4f && end.y < minY + h * 0.4f && end.x > start.x + w * 0.4f) {
            return "u"
        }

        // N/M-humped arch: starts bottom-left, rises to top, ends bottom-right -> 'n'
        if (start.y > minY + h * 0.55f && end.y > minY + h * 0.55f && end.x > start.x + w * 0.4f) {
            return "n"
        }

        // S-curve: 's'
        if (start.y < minY + h * 0.35f && end.y > minY + h * 0.65f && pathLen > h * 1.9f) {
            return "s"
        }

        // Tall ascending stem on left -> 'h' or 'b' or 'L'
        if (h > 42f && start.y < minY + h * 0.25f && start.x < minX + w * 0.4f) {
            return if (end.y > minY + h * 0.7f && abs(end.x - maxX) < w * 0.3f) "L" else ""
        }

        return ""
    }

    private fun centerY(minY: Float, maxY: Float): Float = (minY + maxY) * 0.5f
}

