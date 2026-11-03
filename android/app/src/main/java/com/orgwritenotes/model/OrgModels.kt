package com.orgwritenotes.model

/**
 * Summary item displayed in the left sidebar (Agenda / Open Org Buffers / Search).
 */
data class OrgNodeSummary(
    val nodeId: String,
    val filePath: String,
    val level: Int,
    val todoKeyword: String?,
    val priority: String?,
    val title: String,
    val tags: List<String> = emptyList(),
    val scheduled: String? = null,
    val deadline: String? = null,
    val sketchCount: Int = 0
)

data class OrgSubheadingItem(
    val nodeId: String,
    val level: Int,
    val todoKeyword: String?,
    val priority: String?,
    val title: String,
    val tags: List<String> = emptyList(),
    val sketchCount: Int = 0
)

data class OrgGalleryItem(
    val fileName: String,
    val nodeId: String?,
    val nodeTitle: String,
    val modifiedTime: String = "",
    val width: Int = 1200,
    val height: Int = 600,
    val svgzBase64: String? = null,
    val ocrText: String = ""
)

data class OrgEmacsTheme(
    val isDark: Boolean = true,
    val bgColor: String = "#1E293B",
    val fgColor: String = "#F8FAFC",
    val accentColor: String = "#38BDF8"
) {
    fun resolvedDarkBgArgb(): Int {
        val parsed = parseColorOrNull(bgColor)
        return if (parsed != null && isColorDark(parsed)) parsed else 0xFF181825.toInt()
    }

    fun resolvedDarkFgArgb(): Int {
        val parsed = parseColorOrNull(fgColor)
        return if (parsed != null && !isColorDark(parsed)) parsed else 0xFFCDD6F4.toInt()
    }

    companion object {
        fun isColorDark(argb: Int): Boolean {
            val r = (argb ushr 16) and 0xFF
            val g = (argb ushr 8) and 0xFF
            val b = argb and 0xFF
            val luma = (r * 299 + g * 587 + b * 114) / 1000
            return luma < 128
        }

        fun parseColorOrNull(raw: String?): Int? {
            if (raw.isNullOrBlank()) return null
            val trimmed = raw.trim()
            if (trimmed.startsWith("#")) {
                val hex = trimmed.substring(1)
                when (hex.length) {
                    6 -> hex.toLongOrNull(16)?.let { return (0xFF000000L or it).toInt() }
                    8 -> hex.toLongOrNull(16)?.let { return (0xFF000000L or (it and 0xFFFFFFL)).toInt() }
                    3 -> {
                        val r = hex[0].digitToIntOrNull(16) ?: return null
                        val g = hex[1].digitToIntOrNull(16) ?: return null
                        val b = hex[2].digitToIntOrNull(16) ?: return null
                        val rgb = ((r * 17) shl 16) or ((g * 17) shl 8) or (b * 17)
                        return 0xFF000000.toInt() or rgb
                    }
                }
            }
            return when (trimmed.lowercase()) {
                "black" -> 0xFF000000.toInt()
                "white" -> 0xFFFFFFFF.toInt()
                else -> runCatching {
                    android.graphics.Color.parseColor(trimmed) or 0xFF000000.toInt()
                }.getOrNull()
            }
        }
    }
}

/**
 * Full block-interleaved snapshot of an Org-mode subtree.
 */
data class OrgNodeSnapshot(
    val nodeId: String,
    val etag: String,
    val filePath: String,
    val header: OrgNodeHeader,
    val blocks: List<OrgBlock>,
    val subheadings: List<OrgSubheadingItem> = emptyList()
)

data class OrgNodeHeader(
    val level: Int,
    val todoKeyword: String?,
    val todoKeywordsAll: List<String>,
    val priority: String?,
    val title: String,
    val tags: List<String>,
    val scheduled: String?,
    val deadline: String?,
    val checkboxProgress: String?,
    val properties: Map<String, String> = emptyMap(),
    val breadcrumbs: List<String> = emptyList(),
    val parentNodeId: String? = null,
    val clockedIn: Boolean = false,
    val clockedMinutes: Int = 0
)

sealed class OrgBlock {
    abstract val blockId: String
    abstract val startLine: Int
    abstract val endLine: Int

    data class ChecklistBlock(
        override val blockId: String,
        override val startLine: Int,
        override val endLine: Int = startLine,
        val items: List<ChecklistItem>
    ) : OrgBlock()

    data class InkCanvasBlock(
        override val blockId: String,
        override val startLine: Int,
        override val endLine: Int = startLine,
        val fileName: String,
        val linkText: String,
        val width: Int = 1200,
        val height: Int = 600,
        val xRuling: Int = 40,
        val yRuling: Int = 40,
        val svgzBase64: String? = null
    ) : OrgBlock()

    data class ParagraphBlock(
        override val blockId: String,
        override val startLine: Int,
        override val endLine: Int = startLine,
        val text: String
    ) : OrgBlock()

    data class CodeBlock(
        override val blockId: String,
        override val startLine: Int,
        override val endLine: Int = startLine,
        val language: String = "text",
        val code: String,
        val results: String? = null
    ) : OrgBlock()

    data class TableBlock(
        override val blockId: String,
        override val startLine: Int,
        override val endLine: Int = startLine,
        val rows: List<List<String>>
    ) : OrgBlock()
}

data class ChecklistItem(
    val index: Int,
    val line: Int,
    val state: ChecklistState,
    val text: String
)

enum class ChecklistState {
    UNCHECKED,
    PARTIAL,
    CHECKED;

    companion object {
        fun fromWire(value: String?): ChecklistState = when (value) {
            "checked" -> CHECKED
            "partial" -> PARTIAL
            else -> UNCHECKED
        }
    }

    fun toWire(): String = when (this) {
        CHECKED -> "checked"
        PARTIAL -> "partial"
        UNCHECKED -> "unchecked"
    }
}

