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
    val tags: List<String> = emptyList()
)

/**
 * Full block-interleaved snapshot of an Org-mode subtree.
 */
data class OrgNodeSnapshot(
    val nodeId: String,
    val etag: String,
    val filePath: String,
    val header: OrgNodeHeader,
    val blocks: List<OrgBlock>
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
    val properties: Map<String, String> = emptyMap()
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

