package com.orgwritenotes.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.orgwritenotes.ink.StylusToolMode
import com.orgwritenotes.ink.WriteCanvasBlockView
import com.orgwritenotes.model.ChecklistState
import com.orgwritenotes.model.OrgBlock
import com.orgwritenotes.sync.EmacsSyncClient

/** What the split pane shows next to the main sketch. */
sealed class SplitTarget {
    /** The node's text (paragraphs, checklists, code, tables) as a reference. */
    object Text : SplitTarget()

    /** A second, fully editable sketch from the same node. */
    data class Sketch(val fileName: String) : SplitTarget()
}

/**
 * B7: side pane next to the zoomed editor — either the node's text or a second sketch.
 * [onClose] hides the pane.
 */
@Composable
fun WriteSplitPane(
    nodeId: String,
    currentFileName: String,
    target: SplitTarget,
    onTargetChange: (SplitTarget) -> Unit,
    onClose: () -> Unit,
    syncClient: EmacsSyncClient,
    modifier: Modifier = Modifier
) {
    val snapshot by syncClient.activeSnapshot.collectAsState()
    val blocks = snapshot?.takeIf { it.nodeId == nodeId }?.blocks.orEmpty()
    val otherSketches = blocks.filterIsInstance<OrgBlock.InkCanvasBlock>().filter { it.fileName != currentFileName }

    Column(modifier = modifier.background(Color(0xF2111827))) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            PaneTab("Text", target is SplitTarget.Text, "Show node text beside the sketch") {
                onTargetChange(SplitTarget.Text)
            }
            otherSketches.forEachIndexed { i, b ->
                PaneTab(
                    "Sketch ${i + 1}",
                    target is SplitTarget.Sketch && target.fileName == b.fileName,
                    "Show sketch ${b.fileName} beside this sketch"
                ) { onTargetChange(SplitTarget.Sketch(b.fileName)) }
            }
            Box(modifier = Modifier.weight(1f))
            PaneTab("✕", false, "Close split view", onClose)
        }
        when (target) {
            is SplitTarget.Text -> TextPane(blocks)
            is SplitTarget.Sketch -> {
                val block = otherSketches.firstOrNull { it.fileName == target.fileName }
                if (block == null) {
                    Text("Sketch not found in this node.", color = Color(0xFF94A3B8), modifier = Modifier.padding(12.dp))
                } else {
                    SketchPane(nodeId, block, syncClient)
                }
            }
        }
    }
}

@Composable
private fun PaneTab(label: String, selected: Boolean, description: String, onClick: () -> Unit) {
    Text(
        text = label,
        color = if (selected) Color(0xFF38BDF8) else Color(0xFFCBD5E1),
        fontSize = 12.sp,
        fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
        modifier = Modifier
            .clickable(onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 6.dp)
            .semantics { contentDescription = description }
    )
}

@Composable
private fun TextPane(blocks: List<OrgBlock>) {
    val textBlocks = blocks.filter { it !is OrgBlock.InkCanvasBlock }
    if (textBlocks.isEmpty()) {
        Text("This node has no text.", color = Color(0xFF94A3B8), modifier = Modifier.padding(12.dp))
        return
    }
    LazyColumn(modifier = Modifier.fillMaxSize().padding(horizontal = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        items(textBlocks) { b ->
            when (b) {
                is OrgBlock.ParagraphBlock -> Text(b.text, color = Color(0xFFE2E8F0), fontSize = 14.sp)
                is OrgBlock.ChecklistBlock -> Column {
                    b.items.forEach {
                        val box = when (it.state) {
                            ChecklistState.CHECKED -> "☑"
                            ChecklistState.PARTIAL -> "◪"
                            ChecklistState.UNCHECKED -> "☐"
                        }
                        Text("$box ${it.text}", color = Color(0xFFE2E8F0), fontSize = 14.sp)
                    }
                }
                is OrgBlock.CodeBlock -> Text(
                    b.code, color = Color(0xFFA7F3D0), fontSize = 12.sp, fontFamily = FontFamily.Monospace
                )
                is OrgBlock.TableBlock -> Column {
                    b.rows.forEach { row -> Text(row.joinToString("  |  "), color = Color(0xFFE2E8F0), fontSize = 13.sp) }
                }
                is OrgBlock.InkCanvasBlock -> Unit
            }
        }
    }
}

@Composable
private fun SketchPane(nodeId: String, block: OrgBlock.InkCanvasBlock, syncClient: EmacsSyncClient) {
    var ref by remember(block.fileName) { mutableStateOf<WriteCanvasBlockView?>(null) }
    val emacsTheme by syncClient.emacsTheme.collectAsState()
    val bgArgb = remember(emacsTheme) { emacsTheme.resolvedDarkBgArgb() }
    val fgArgb = remember(emacsTheme) { emacsTheme.resolvedDarkFgArgb() }
    AndroidView(
        modifier = Modifier.fillMaxSize(),
        factory = { ctx ->
            WriteCanvasBlockView(
                context = ctx,
                initialWidth = block.width,
                initialHeight = block.height,
                xRuling = block.xRuling,
                yRuling = block.yRuling,
                isZoomedMode = true,
                onStrokeCommitted = { pathXml, w, h, export ->
                    syncClient.streamStrokeAndScheduleCommit(nodeId, block.fileName, pathXml, w, h, export)
                },
                onHeightExpanded = {},
                onFlushPendingChanges = { w, h, b64 ->
                    syncClient.flushPendingInkCommit(nodeId, block.fileName, w, h, b64)
                }
            ).apply {
                setAdaptiveTheme(emacsTheme.isDark, bgArgb, fgArgb)
                loadFromBase64Svgz(block.svgzBase64)
                configureTool(StylusToolMode.PEN, 0xFF1E293B.toInt(), 2.6f)
                ref = this
            }
        },
        update = { view ->
            view.setAdaptiveTheme(emacsTheme.isDark, bgArgb, fgArgb)
        }
    )
}

