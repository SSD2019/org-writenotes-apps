package com.orgwritenotes.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import com.orgwritenotes.ink.OnyxEInkBridge
import com.orgwritenotes.ink.WritePenTip

// Pen presets, saved favorite pens and the width/color palettes.

internal data class SavedWritePenPreset(
    val name: String,
    val colorArgb: Int,
    val widthPx: Float,
    val tip: WritePenTip,
    val isHighlighter: Boolean = false
)

internal object WritePenPresetStorage {
    private const val PREFS_NAME = "org_writenotes_prefs"
    private const val KEY_CUSTOM_PENS = "custom_pen_presets_v1"

    fun loadCustomPens(context: android.content.Context): List<SavedWritePenPreset> {
        return runCatching {
            val prefs = context.getSharedPreferences(PREFS_NAME, android.content.Context.MODE_PRIVATE)
            val raw = prefs.getString(KEY_CUSTOM_PENS, null) ?: return emptyList()
            val arr = org.json.JSONArray(raw)
            val list = mutableListOf<SavedWritePenPreset>()
            for (i in 0 until arr.length()) {
                val obj = arr.getJSONObject(i)
                list.add(
                    SavedWritePenPreset(
                        name = obj.optString("name", "Custom Pen"),
                        colorArgb = obj.optInt("colorArgb", 0xFF1E293B.toInt()),
                        widthPx = obj.optDouble("widthPx", 2.6).toFloat(),
                        tip = WritePenTip.values().firstOrNull { it.nativeCode == obj.optInt("tip", 1) } ?: WritePenTip.ROUND,
                        isHighlighter = obj.optBoolean("isHighlighter", false)
                    )
                )
            }
            list
        }.getOrDefault(emptyList())
    }

    fun saveCustomPens(context: android.content.Context, pens: List<SavedWritePenPreset>) {
        runCatching {
            val prefs = context.getSharedPreferences(PREFS_NAME, android.content.Context.MODE_PRIVATE)
            val arr = org.json.JSONArray()
            for (p in pens) {
                arr.put(
                    org.json.JSONObject().apply {
                        put("name", p.name)
                        put("colorArgb", p.colorArgb)
                        put("widthPx", p.widthPx.toDouble())
                        put("tip", p.tip.nativeCode)
                        put("isHighlighter", p.isHighlighter)
                    }
                )
            }
            prefs.edit().putString(KEY_CUSTOM_PENS, arr.toString()).apply()
        }
    }

    // B3: Persisted per-sketch viewport (zoom + pan offset)
    fun loadViewport(context: android.content.Context, fileName: String): Triple<Float, Float, Float>? {
        return runCatching {
            val prefs = context.getSharedPreferences(PREFS_NAME, android.content.Context.MODE_PRIVATE)
            val raw = prefs.getString("viewport_$fileName", null) ?: return null
            val parts = raw.split(",")
            if (parts.size == 3) {
                Triple(parts[0].toFloat(), parts[1].toFloat(), parts[2].toFloat())
            } else null
        }.getOrNull()
    }

    fun saveViewport(context: android.content.Context, fileName: String, zoomScale: Float, panX: Float, panY: Float) {
        runCatching {
            val prefs = context.getSharedPreferences(PREFS_NAME, android.content.Context.MODE_PRIVATE)
            prefs.edit().putString("viewport_$fileName", "$zoomScale,$panX,$panY").apply()
        }
    }
}

// Exact width progression from Stylus Labs Write `PenToolbar::PEN_WIDTHS`
internal val WRITE_PEN_WIDTH_PALETTE = listOf(1.0f, 1.6f, 2.4f, 3.2f, 4.5f, 6.5f, 12.0f)

internal val WRITE_COLOR_PALETTE = if (OnyxEInkBridge.isEInkDevice) {
    listOf(
        0xFF000000.toInt(), // Pure Black (primary E-Ink ink)
        0xFF334155.toInt(), // Dark Slate
        0xFF64748B.toInt(), // Medium Gray
        0xFF1D4ED8.toInt(), // Write Blue (for Kaleido color E-Ink & SVG export)
        0xFFDC2626.toInt(), // Write Red
        0xFF15803D.toInt(), // Write Green
        0xFFD97706.toInt(), // Amber / Orange
        0xFF7E22CE.toInt()  // Purple
    )
} else {
    listOf(
        0xFF1E293B.toInt(), // Slate / Black
        0xFF1D4ED8.toInt(), // Write Blue
        0xFFDC2626.toInt(), // Write Red
        0xFF15803D.toInt(), // Write Green
        0xFFD97706.toInt(), // Amber / Orange
        0xFF7E22CE.toInt(), // Purple
        0xFF0E7490.toInt(), // Cyan / Teal
        0xFF64748B.toInt(), // Gray
        0xFFFFFFFF.toInt()  // White
    )
}

/** Names for palette colors; E-Ink panels show these because the swatches all render as grays. */
internal val WRITE_COLOR_NAMES: Map<Int, String> = mapOf(
    0xFF000000.toInt() to "Black",
    0xFF1E293B.toInt() to "Black",
    0xFF334155.toInt() to "Slate",
    0xFF64748B.toInt() to "Gray",
    0xFF1D4ED8.toInt() to "Blue",
    0xFFDC2626.toInt() to "Red",
    0xFF15803D.toInt() to "Green",
    0xFFD97706.toInt() to "Amber",
    0xFF7E22CE.toInt() to "Purple",
    0xFF0E7490.toInt() to "Teal",
    0xFFFFFFFF.toInt() to "White",
    0xFFFACC15.toInt() to "Yellow"
)

internal fun penColorName(argb: Int): String =
    WRITE_COLOR_NAMES[argb] ?: String.format("#%06X", argb and 0xFFFFFF)

internal val WRITE_SAVED_PENS = listOf(
    SavedWritePenPreset(
        "Fine Black",
        if (OnyxEInkBridge.isEInkDevice) 0xFF000000.toInt() else 0xFF1E293B.toInt(),
        1.8f,
        WritePenTip.ROUND
    ),
    SavedWritePenPreset("Ballpoint Blue", 0xFF1D4ED8.toInt(), 2.6f, WritePenTip.ROUND),
    SavedWritePenPreset("Markup Red", 0xFFDC2626.toInt(), 2.6f, WritePenTip.ROUND),
    SavedWritePenPreset(
        "Chisel Fountain",
        if (OnyxEInkBridge.isEInkDevice) 0xFF000000.toInt() else 0xFF1E293B.toInt(),
        4.2f,
        WritePenTip.CHISEL
    ),
    SavedWritePenPreset("Highlighter", 0xFFFACC15.toInt(), 4.5f, WritePenTip.FLAT, isHighlighter = true)
)
