package com.orgwritenotes.ink

import android.content.Context
import android.graphics.RectF
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import java.io.File

/**
 * Cross-sketch stroke clipboard for Copy / Cut / Paste between any two canvases.
 *
 * Holds the copied SVG fragment plus its source bounds (so a paste can be re-anchored into the
 * target sketch's visible area). Backed by Compose state so Paste buttons appear as soon as
 * something is copied, and mirrored to a cache file so the clipboard survives an app restart.
 */
object StrokeClipboard {
    private const val FILE_NAME = "stroke_clipboard.svg"

    var svg by mutableStateOf("")
        private set
    var strokeCount by mutableStateOf(0)
        private set

    /** Source bounds in the copying canvas' coordinates, or null when unknown. */
    var sourceBounds: RectF? = null
        private set

    val hasStrokes: Boolean get() = svg.isNotBlank()

    private var file: File? = null

    /** Restores the last clipboard from disk; call once on startup. */
    fun load(context: Context) {
        val f = File(context.cacheDir, FILE_NAME).also { file = it }
        runCatching {
            if (!f.exists()) return
            val lines = f.readText().split('\n', limit = 3)
            if (lines.size < 3) return
            val b = lines[0].split(',').mapNotNull { it.toFloatOrNull() }
            sourceBounds = if (b.size == 4) RectF(b[0], b[1], b[2], b[3]) else null
            strokeCount = lines[1].toIntOrNull() ?: 0
            svg = lines[2]
        }
    }

    fun set(svgFragment: String, count: Int, bounds: RectF?) {
        svg = svgFragment
        strokeCount = count
        sourceBounds = bounds?.let { RectF(it) }
        val f = file ?: return
        val header = bounds?.let { "${it.left},${it.top},${it.right},${it.bottom}" } ?: ""
        // Write off the UI thread; a large selection can be several hundred KB of path data.
        Thread { runCatching { f.writeText("$header\n$count\n$svgFragment") } }.start()
    }
}
