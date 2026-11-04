package com.orgwritenotes.ui

import androidx.compose.ui.platform.LocalContext
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.orgwritenotes.ink.OnyxEInkBridge
import com.orgwritenotes.ink.StylusToolMode
import com.orgwritenotes.ink.WriteCanvasBlockView
import com.orgwritenotes.ink.WritePageSetupState
import com.orgwritenotes.ink.WritePenTip
import com.orgwritenotes.ink.WriteSelectionState
import com.orgwritenotes.model.*
import com.orgwritenotes.sync.EmacsSyncClient

// Cards for the Org AST blocks of a node: checklists, src blocks, tables, ink canvases, paragraphs.

@Composable
internal fun ChecklistBlockCard(
    block: OrgBlock.ChecklistBlock,
    onToggle: (ChecklistItem, Boolean) -> Unit,
    onSetPartial: (ChecklistItem) -> Unit = {},
    onUpdateItem: (ChecklistItem, String?, Int, Int) -> Unit = { _, _, _, _ -> },
    onDeleteItem: (ChecklistItem) -> Unit = {},
    onInsertSketchAfterItem: (ChecklistItem) -> Unit,
    onInsertSketchAfterBlock: () -> Unit,
    onInsertSketchBeforeBlock: () -> Unit,
    onAddItem: (String) -> Unit
) {
    val isEInk = OnyxEInkBridge.isEInkDevice
    var newItemText by remember(block.blockId) { mutableStateOf("") }
    var showCardMenu by remember(block.blockId) { mutableStateOf(false) }
    var cardMenuOffset by remember(block.blockId) { mutableStateOf(DpOffset.Zero) }
    var editingItem by remember(block.blockId) { mutableStateOf<ChecklistItem?>(null) }
    var editingItemText by remember(block.blockId) { mutableStateOf("") }
    val density = LocalDensity.current

    Box {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(10.dp))
                .background(if (isEInk) Color.White else Color(0xFF1E293B))
                .then(if (isEInk) Modifier.border(1.dp, Color.Black, RoundedCornerShape(10.dp)) else Modifier)
                .pointerInput(block.blockId) {
                    detectTapGestures(
                        onLongPress = { offset ->
                            cardMenuOffset = DpOffset(with(density) { offset.x.toDp() }, with(density) { offset.y.toDp() })
                            showCardMenu = true
                        }
                    )
                }
                .padding(14.dp)
        ) {
            block.items.forEach { item ->
                val checked = item.state == ChecklistState.CHECKED
                val isPartial = item.state == ChecklistState.PARTIAL
                var showItemMenu by remember(item.line) { mutableStateOf(false) }
                var itemMenuOffset by remember(item.line) { mutableStateOf(DpOffset.Zero) }

                Box(modifier = Modifier.fillMaxWidth()) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(6.dp))
                            .pointerInput(item.line, checked, isPartial) {
                                detectTapGestures(
                                    onTap = { onToggle(item, !checked) },
                                    onLongPress = { offset ->
                                        itemMenuOffset = DpOffset(
                                            with(density) { offset.x.toDp() },
                                            with(density) { offset.y.toDp() }
                                        )
                                        showItemMenu = true
                                    }
                                )
                            }
                            .padding(vertical = 4.dp, horizontal = 2.dp)
                    ) {
                        Checkbox(
                            checked = checked,
                            onCheckedChange = { onToggle(item, it) }
                        )
                        if (isPartial) {
                            Text(
                                text = "[-]",
                                color = if (isEInk) Color.Black else Color(0xFFF59E0B),
                                fontSize = 12.sp,
                                fontFamily = FontFamily.Monospace,
                                fontWeight = FontWeight.Bold
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                        }
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = item.text,
                            color = if (isEInk) {
                                if (checked) Color(0xFF475569) else Color.Black
                            } else {
                                when {
                                    checked -> Color(0xFF94A3B8)
                                    isPartial -> Color(0xFFFDE68A)
                                    else -> Color(0xFFF1F5F9)
                                }
                            },
                            textDecoration = if (checked) TextDecoration.LineThrough else TextDecoration.None,
                            fontSize = 15.sp,
                            modifier = Modifier.weight(1f)
                        )
                        IconButton(
                            onClick = { showItemMenu = true },
                            modifier = Modifier.size(28.dp)
                        ) {
                            Text("⋯", color = if (isEInk) Color.Black else Color(0xFF64748B), fontSize = 14.sp, fontWeight = FontWeight.Bold)
                        }
                    }

                    DropdownMenu(
                        expanded = showItemMenu,
                        onDismissRequest = { showItemMenu = false },
                        offset = itemMenuOffset,
                        modifier = Modifier
                            .background(if (isEInk) Color.White else Color(0xFF0F172A), RoundedCornerShape(8.dp))
                            .border(1.dp, if (isEInk) Color.Black else Color(0xFF38BDF8).copy(alpha = 0.6f), RoundedCornerShape(8.dp))
                    ) {
                        DropdownMenuItem(
                            text = { Text("✎ Edit Item Text", color = if (isEInk) Color.Black else Color(0xFFF1F5F9), fontSize = 13.sp) },
                            onClick = {
                                showItemMenu = false
                                editingItem = item
                                editingItemText = item.text
                            }
                        )
                        DropdownMenuItem(
                            text = { Text("[-] Set Partial / In-Progress", color = if (isEInk) Color.Black else Color(0xFFF59E0B), fontSize = 13.sp) },
                            onClick = {
                                showItemMenu = false
                                onSetPartial(item)
                            }
                        )
                        DropdownMenuItem(
                            text = { Text("→ Indent Item", color = if (isEInk) Color.Black else Color(0xFFCBD5E1), fontSize = 13.sp) },
                            onClick = {
                                showItemMenu = false
                                onUpdateItem(item, null, 2, 0)
                            }
                        )
                        DropdownMenuItem(
                            text = { Text("← Outdent Item", color = if (isEInk) Color.Black else Color(0xFFCBD5E1), fontSize = 13.sp) },
                            onClick = {
                                showItemMenu = false
                                onUpdateItem(item, null, -2, 0)
                            }
                        )
                        DropdownMenuItem(
                            text = { Text("↑ Move Item Up", color = if (isEInk) Color.Black else Color(0xFFCBD5E1), fontSize = 13.sp) },
                            onClick = {
                                showItemMenu = false
                                onUpdateItem(item, null, 0, -1)
                            }
                        )
                        DropdownMenuItem(
                            text = { Text("↓ Move Item Down", color = if (isEInk) Color.Black else Color(0xFFCBD5E1), fontSize = 13.sp) },
                            onClick = {
                                showItemMenu = false
                                onUpdateItem(item, null, 0, 1)
                            }
                        )
                        DropdownMenuItem(
                            text = { Text("＋ Add Sketch Below Item", color = if (isEInk) Color.Black else Color(0xFF38BDF8), fontSize = 13.sp) },
                            onClick = {
                                showItemMenu = false
                                onInsertSketchAfterItem(item)
                            }
                        )
                        HorizontalDivider(color = if (isEInk) Color.Black else Color(0xFF334155))
                        DropdownMenuItem(
                            text = { Text("✕ Delete Checklist Item", color = if (isEInk) Color.Black else Color(0xFFEF4444), fontSize = 13.sp) },
                            onClick = {
                                showItemMenu = false
                                onDeleteItem(item)
                            }
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(6.dp))

            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = newItemText,
                    onValueChange = { newItemText = it },
                    placeholder = { Text("Add checklist item (- [ ] ...)", color = if (isEInk) Color(0xFF475569) else Color(0xFF64748B)) },
                    singleLine = true,
                    modifier = Modifier.weight(1f)
                )
                Spacer(modifier = Modifier.width(8.dp))
                TextButton(onClick = {
                    if (newItemText.isNotBlank()) {
                        onAddItem(newItemText.trim())
                        newItemText = ""
                    }
                }) {
                    Text("Add")
                }
            }
        }

        SketchInsertionPopupMenu(
            expanded = showCardMenu,
            onDismissRequest = { showCardMenu = false },
            offset = cardMenuOffset,
            belowLabel = "＋ Add Sketch Below",
            aboveLabel = "＋ Add Sketch Above",
            onInsertBelow = onInsertSketchAfterBlock,
            onInsertAbove = onInsertSketchBeforeBlock
        )
    }

    if (editingItem != null) {
        val targetItem = editingItem!!
        AlertDialog(
            onDismissRequest = { editingItem = null },
            title = { Text("Edit Checklist Item", color = if (isEInk) Color.Black else Color.White) },
            text = {
                OutlinedTextField(
                    value = editingItemText,
                    onValueChange = { editingItemText = it },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            },
            confirmButton = {
                Button(onClick = {
                    if (editingItemText.isNotBlank()) {
                        onUpdateItem(targetItem, editingItemText.trim(), 0, 0)
                    }
                    editingItem = null
                }) {
                    Text("Save")
                }
            },
            dismissButton = {
                TextButton(onClick = { editingItem = null }) {
                    Text("Cancel")
                }
            },
            containerColor = if (isEInk) Color.White else Color(0xFF1E293B)
        )
    }
}

@Composable
internal fun CodeBlockCard(
    block: OrgBlock.CodeBlock,
    onExecute: () -> Unit,
    onInsertSketchAfterBlock: () -> Unit,
    onInsertSketchBeforeBlock: () -> Unit
) {
    val isEInk = OnyxEInkBridge.isEInkDevice
    val context = LocalContext.current
    var showMenu by remember(block.blockId) { mutableStateOf(false) }
    var menuOffset by remember(block.blockId) { mutableStateOf(DpOffset.Zero) }
    val density = LocalDensity.current

    Box {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(8.dp))
                .background(if (isEInk) Color.White else Color(0xFF0F172A))
                .border(1.dp, if (isEInk) Color.Black else Color(0xFF334155), RoundedCornerShape(8.dp))
                .pointerInput(block.blockId) {
                    detectTapGestures(
                        onLongPress = { offset ->
                            menuOffset = DpOffset(with(density) { offset.x.toDp() }, with(density) { offset.y.toDp() })
                            showMenu = true
                        }
                    )
                }
                .padding(12.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    text = "#+BEGIN_SRC ${block.language.ifEmpty { "text" }}",
                    color = if (isEInk) Color.Black else Color(0xFF38BDF8),
                    fontFamily = FontFamily.Monospace,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        text = "📋 Copy",
                        color = if (isEInk) Color.Black else Color(0xFF94A3B8),
                        fontSize = 11.sp,
                        modifier = Modifier
                            .clip(RoundedCornerShape(4.dp))
                            .background(if (isEInk) Color(0xFFF1F5F9) else Color(0xFF1E293B))
                            .then(if (isEInk) Modifier.border(1.dp, Color.Black, RoundedCornerShape(4.dp)) else Modifier)
                            .clickable {
                                val cm = context.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as? android.content.ClipboardManager
                                cm?.setPrimaryClip(android.content.ClipData.newPlainText("Org Src", block.code))
                            }
                            .padding(horizontal = 8.dp, vertical = 3.dp)
                    )
                    Text(
                        text = "▶ Run Babel",
                        color = if (isEInk) Color.Black else Color(0xFF22C55E),
                        fontSize = 11.sp,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier
                            .clip(RoundedCornerShape(4.dp))
                            .background(if (isEInk) Color(0xFFF1F5F9) else Color(0xFF064E3B))
                            .then(if (isEInk) Modifier.border(1.dp, Color.Black, RoundedCornerShape(4.dp)) else Modifier)
                            .clickable { onExecute() }
                            .padding(horizontal = 8.dp, vertical = 3.dp)
                    )
                }
            }
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = block.code,
                color = if (isEInk) Color.Black else Color(0xFFE2E8F0),
                fontFamily = FontFamily.Monospace,
                fontSize = 13.sp,
                lineHeight = 19.sp,
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
            )
            if (!block.results.isNullOrBlank()) {
                Spacer(modifier = Modifier.height(8.dp))
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(6.dp))
                        .background(if (isEInk) Color(0xFFF1F5F9) else Color(0xFF1E293B))
                        .then(if (isEInk) Modifier.border(1.dp, Color.Black, RoundedCornerShape(6.dp)) else Modifier)
                        .padding(8.dp)
                ) {
                    Text(
                        text = "#+RESULTS:",
                        color = if (isEInk) Color.Black else Color(0xFF22C55E),
                        fontFamily = FontFamily.Monospace,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = block.results,
                        color = if (isEInk) Color.Black else Color(0xFFCBD5E1),
                        fontFamily = FontFamily.Monospace,
                        fontSize = 12.sp
                    )
                }
            }
        }

        SketchInsertionPopupMenu(
            expanded = showMenu,
            onDismissRequest = { showMenu = false },
            offset = menuOffset,
            belowLabel = "＋ Add Sketch Below Code",
            aboveLabel = "＋ Add Sketch Above Code",
            onInsertBelow = onInsertSketchAfterBlock,
            onInsertAbove = onInsertSketchBeforeBlock
        )
    }
}

@Composable
internal fun TableBlockCard(
    block: OrgBlock.TableBlock,
    onUpdateCell: (rowIndex: Int, colIndex: Int, value: String) -> Unit,
    onInsertSketchAfterBlock: () -> Unit,
    onInsertSketchBeforeBlock: () -> Unit
) {
    val isEInk = OnyxEInkBridge.isEInkDevice
    var editingCell by remember(block.blockId) { mutableStateOf<Triple<Int, Int, String>?>(null) }
    var showMenu by remember(block.blockId) { mutableStateOf(false) }
    var menuOffset by remember(block.blockId) { mutableStateOf(DpOffset.Zero) }
    val density = LocalDensity.current

    Box {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(8.dp))
                .background(if (isEInk) Color.White else Color(0xFF1E293B))
                .border(1.dp, if (isEInk) Color.Black else Color(0xFF334155), RoundedCornerShape(8.dp))
                .pointerInput(block.blockId) {
                    detectTapGestures(
                        onLongPress = { offset ->
                            menuOffset = DpOffset(with(density) { offset.x.toDp() }, with(density) { offset.y.toDp() })
                            showMenu = true
                        }
                    )
                }
                .padding(12.dp)
                .horizontalScroll(rememberScrollState())
        ) {
            block.rows.forEachIndexed { rIdx, row ->
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .background(
                            if (rIdx == 0) (if (isEInk) Color(0xFFE2E8F0) else Color(0xFF0F172A))
                            else Color.Transparent
                        )
                        .border(0.5.dp, if (isEInk) Color.Black else Color(0xFF334155))
                ) {
                    row.forEachIndexed { cIdx, cellVal ->
                        Box(
                            modifier = Modifier
                                .widthIn(min = 90.dp)
                                .border(0.5.dp, if (isEInk) Color.Black else Color(0xFF334155))
                                .clickable {
                                    editingCell = Triple(rIdx, cIdx, cellVal)
                                }
                                .padding(horizontal = 10.dp, vertical = 6.dp)
                        ) {
                            Text(
                                text = cellVal,
                                color = if (isEInk) Color.Black else (if (rIdx == 0) Color(0xFF38BDF8) else Color(0xFFE2E8F0)),
                                fontFamily = FontFamily.Monospace,
                                fontSize = 13.sp,
                                fontWeight = if (rIdx == 0) FontWeight.Bold else FontWeight.Normal
                            )
                        }
                    }
                }
            }
        }

        SketchInsertionPopupMenu(
            expanded = showMenu,
            onDismissRequest = { showMenu = false },
            offset = menuOffset,
            belowLabel = "＋ Add Sketch Below Table",
            aboveLabel = "＋ Add Sketch Above Table",
            onInsertBelow = onInsertSketchAfterBlock,
            onInsertAbove = onInsertSketchBeforeBlock
        )
    }

    if (editingCell != null) {
        val (rIdx, cIdx, initialVal) = editingCell!!
        var draftVal by remember(rIdx, cIdx) { mutableStateOf(initialVal) }
        AlertDialog(
            onDismissRequest = { editingCell = null },
            title = { Text("Edit Org Table Cell (${rIdx + 1}, ${cIdx + 1})", color = if (isEInk) Color.Black else Color.White) },
            text = {
                OutlinedTextField(
                    value = draftVal,
                    onValueChange = { draftVal = it },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            },
            confirmButton = {
                Button(onClick = {
                    onUpdateCell(rIdx, cIdx, draftVal)
                    editingCell = null
                }) {
                    Text("Save & Align Table")
                }
            },
            dismissButton = {
                TextButton(onClick = { editingCell = null }) {
                    Text("Cancel")
                }
            },
            containerColor = if (isEInk) Color.White else Color(0xFF1E293B)
        )
    }
}

/**
 * Clean Inline Paper Card for `.svgz` drawings — no per-card toolbar clutter.
 * Touching or hovering with the S-Pen activates this drawing and wakes up the shared
 * Stylus Labs Write auto-hiding toolbar at the top of the editor.
 */
@Composable
internal fun InkCanvasBlockCard(
    nodeId: String,
    block: OrgBlock.InkCanvasBlock,
    isActiveDrawing: Boolean,
    emacsTheme: OrgEmacsTheme,
    activeMode: StylusToolMode,
    selectedColor: Int,
    selectedWidth: Float,
    selectedPenTip: WritePenTip,
    pressureEnabled: Boolean,
    chiselAngle: Float = 45f,
    snapToGrid: Boolean = false,
    lineDrawing: Boolean = false,
    syncClient: EmacsSyncClient,
    onRegisterView: (WriteCanvasBlockView) -> Unit,
    onUnregisterView: (WriteCanvasBlockView) -> Unit,
    onActivateDrawing: (WriteCanvasBlockView) -> Unit,
    onActiveStateUpdated: (
        canUndo: Boolean,
        canRedo: Boolean,
        pageSetup: WritePageSetupState,
        selectionState: WriteSelectionState
    ) -> Unit,
    onZoomIntoDrawing: () -> Unit,
    onInsertSketchAfterBlock: () -> Unit,
    onInsertSketchBeforeBlock: () -> Unit
) {
    val isEInk = OnyxEInkBridge.isEInkDevice
    var canvasHeightPx by remember(block.blockId) { mutableStateOf(block.height) }
    var dragResizeDeltaPx by remember(block.blockId) { mutableStateOf(0) }
    var canvasViewRef by remember(block.blockId) { mutableStateOf<WriteCanvasBlockView?>(null) }
    var lastLoadedSvgzBase64 by remember(block.blockId) { mutableStateOf(block.svgzBase64) }
    var localSelection by remember(block.blockId) { mutableStateOf(WriteSelectionState()) }
    var showMenu by remember(block.blockId) { mutableStateOf(false) }
    var menuOffset by remember(block.blockId) { mutableStateOf(DpOffset.Zero) }
    var showDeleteConfirmDialog by remember(block.blockId) { mutableStateOf(false) }

    val bgArgb = remember(emacsTheme) { emacsTheme.resolvedDarkBgArgb() }
    val fgArgb = remember(emacsTheme) { emacsTheme.resolvedDarkFgArgb() }
    val effectiveDark = !isEInk && emacsTheme.isDark

    LaunchedEffect(emacsTheme, canvasViewRef, isEInk) {
        canvasViewRef?.setAdaptiveTheme(effectiveDark, bgArgb, fgArgb)
    }

    val density = LocalDensity.current
    val heightDp = with(density) { canvasHeightPx.toDp() }

    val borderColor = if (isEInk) Color.Black else (if (isActiveDrawing) Color(0xFF38BDF8) else Color(0xFF334155))
    val borderWidth = if (isActiveDrawing) 1.5.dp else 1.dp

    Box {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(10.dp))
                .border(borderWidth, borderColor, RoundedCornerShape(10.dp))
                .background(if (isEInk) Color.White else Color(0xFF0F172A))
        ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(heightDp)
        ) {
            // Embedded Stylus Labs Write C++ Canvas View
            AndroidView(
                factory = { ctx ->
                    WriteCanvasBlockView(
                        context = ctx,
                        initialWidth = block.width,
                        initialHeight = canvasHeightPx,
                        xRuling = block.xRuling,
                        yRuling = block.yRuling,
                        isZoomedMode = false,
                        onCanvasActivated = {
                            canvasViewRef?.let { onActivateDrawing(it) }
                        },
                        onStrokeCommitted = { pathXml, w, h, exportSvgzBase64 ->
                            syncClient.streamStrokeAndScheduleCommit(
                                nodeId = nodeId,
                                fileName = block.fileName,
                                pathXml = pathXml,
                                canvasWidth = w,
                                canvasHeight = h,
                                exportSvgzBase64Provider = exportSvgzBase64
                            )
                        },
                        onHeightExpanded = { newHeight ->
                            canvasHeightPx = newHeight
                        },
                        onSelectionChanged = { sel ->
                            localSelection = sel
                            canvasViewRef?.let { view ->
                                onActiveStateUpdated(
                                    view.canUndoNow(),
                                    view.canRedoNow(),
                                    view.queryPageSetupState(),
                                    sel
                                )
                            }
                        },
                        onViewportChanged = { _, undoAvail, redoAvail, setup ->
                            onActiveStateUpdated(undoAvail, redoAvail, setup, localSelection)
                        },
                        onFlushPendingChanges = { w, h, b64 ->
                            syncClient.flushPendingInkCommit(
                                nodeId = nodeId,
                                fileName = block.fileName,
                                canvasWidth = w,
                                canvasHeight = h,
                                svgzBase64 = b64
                            )
                        },
                        onLaserPoint = { lx, ly ->
                            syncClient.sendLaserPoint(nodeId, block.fileName, lx, ly)
                        }
                    ).apply {
                        setAdaptiveTheme(effectiveDark, bgArgb, fgArgb)
                        loadFromBase64Svgz(block.svgzBase64)
                        lastLoadedSvgzBase64 = block.svgzBase64
                        configureTool(
                            activeMode,
                            selectedColor,
                            selectedWidth,
                            selectedPenTip,
                            pressureEnabled,
                            snapToGrid,
                            lineDrawing,
                            chiselAngle
                        )
                        canvasViewRef = this
                        onRegisterView(this)
                    }
                },
                update = { view ->
                    onRegisterView(view)
                    view.setAdaptiveTheme(effectiveDark, bgArgb, fgArgb)
                    if (block.svgzBase64 != lastLoadedSvgzBase64 && !block.svgzBase64.isNullOrEmpty()) {
                        lastLoadedSvgzBase64 = block.svgzBase64
                        if (block.svgzBase64 != view.lastSyncedSvgzBase64) {
                            view.loadFromBase64Svgz(block.svgzBase64)
                        }
                    }
                    if (view.canvasHeightPx != canvasHeightPx) {
                        canvasHeightPx = view.canvasHeightPx
                    }
                },
                onRelease = { view -> onUnregisterView(view) },
                modifier = Modifier.fillMaxSize()
            )

            // Subtle corner badge on the paper sheet (shows active indicator + 1-tap full-screen zoom)
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(8.dp)
                    .clip(RoundedCornerShape(6.dp))
                    .background(if (isEInk) Color.White else (if (isActiveDrawing) Color(0xCC0F172A) else Color(0x991E293B)))
                    .border(
                        1.dp,
                        if (isEInk) Color.Black else (if (isActiveDrawing) Color(0xFF38BDF8) else Color(0xFF475569)),
                        RoundedCornerShape(6.dp)
                    )
                    .pointerInput(block.blockId) {
                        detectTapGestures(
                            onTap = {
                                canvasViewRef?.let { onActivateDrawing(it) }
                            },
                            onLongPress = { offset ->
                                menuOffset = DpOffset(with(density) { offset.x.toDp() }, with(density) { offset.y.toDp() })
                                showMenu = true
                            }
                        )
                    }
                    .padding(horizontal = 8.dp, vertical = 4.dp)
            ) {
                if (isActiveDrawing) {
                    Text(
                        text = "●",
                        color = if (isEInk) Color.Black else Color(0xFF38BDF8),
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.clickable {
                            canvasViewRef?.let { onActivateDrawing(it) }
                        }
                    )
                    Box(
                        modifier = Modifier
                            .width(1.dp)
                            .height(12.dp)
                            .background(if (isEInk) Color.Black else Color(0xFF475569))
                    )
                }
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .clip(RoundedCornerShape(4.dp))
                        .clickable {
                            onZoomIntoDrawing()
                        }
                        .padding(horizontal = 2.dp)
                ) {
                    WriteSvgIcon(asset = WriteIconAsset.FULLSCREEN, tint = if (isEInk) Color.Black else Color.White, size = 12.dp)
                    Spacer(modifier = Modifier.width(3.dp))
                    Text(
                        text = "Zoom",
                        color = if (isEInk) Color.Black else Color.White,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                }
                Box(
                    modifier = Modifier
                        .width(1.dp)
                        .height(12.dp)
                        .background(if (isEInk) Color.Black else Color(0xFF475569))
                )
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .clip(RoundedCornerShape(4.dp))
                        .clickable { showDeleteConfirmDialog = true }
                        .padding(horizontal = 2.dp)
                ) {
                    WriteSvgIcon(asset = WriteIconAsset.DELETE, tint = if (isEInk) Color.Black else Color(0xFFF87171), size = 12.dp)
                    Spacer(modifier = Modifier.width(3.dp))
                    Text(
                        text = "Delete",
                        color = if (isEInk) Color.Black else Color(0xFFF87171),
                        fontSize = 10.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                }
            }
        }

        // Minimal Bottom Drag-to-Expand Handle
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .fillMaxWidth()
                .height(18.dp)
                .background(if (isEInk) Color(0xFFF1F5F9) else Color(0xFF0F172A))
                .pointerInput(block.blockId) {
                    detectVerticalDragGestures(
                        onVerticalDrag = { change, dragAmount ->
                            change.consume()
                            val nextDelta = dragResizeDeltaPx + dragAmount.toInt()
                            val clampedTarget = (canvasHeightPx + nextDelta).coerceIn(300, 6000)
                            dragResizeDeltaPx = clampedTarget - canvasHeightPx
                        },
                        onDragEnd = {
                            if (dragResizeDeltaPx != 0) {
                                val targetHeight = (canvasHeightPx + dragResizeDeltaPx).coerceIn(300, 6000)
                                dragResizeDeltaPx = 0
                                canvasHeightPx = targetHeight
                                canvasViewRef?.resizeCanvasHeight(targetHeight)
                            }
                        },
                        onDragCancel = {
                            dragResizeDeltaPx = 0
                        }
                    )
                }
        ) {
            if (dragResizeDeltaPx != 0) {
                val targetHeight = (canvasHeightPx + dragResizeDeltaPx).coerceIn(300, 6000)
                val sign = if (dragResizeDeltaPx > 0) "+" else ""
                Text(
                    text = "Height: ${targetHeight}px (${sign}${dragResizeDeltaPx}px)",
                    color = if (isEInk) Color.Black else Color(0xFF38BDF8),
                    fontSize = 10.sp,
                    fontWeight = FontWeight.SemiBold
                )
            } else {
                Box(
                    modifier = Modifier
                        .width(42.dp)
                        .height(3.dp)
                        .clip(RoundedCornerShape(1.5.dp))
                        .background(if (isEInk) Color.Black else (if (isActiveDrawing) Color(0xFF38BDF8) else Color(0xFF475569)))
                )
            }
        }
    }

    SketchInsertionPopupMenu(
        expanded = showMenu,
        onDismissRequest = { showMenu = false },
        offset = menuOffset,
        belowLabel = "＋ Add Sketch Below",
        aboveLabel = "＋ Add Sketch Above",
        onInsertBelow = onInsertSketchAfterBlock,
        onInsertAbove = onInsertSketchBeforeBlock,
        onDelete = { showDeleteConfirmDialog = true }
    )

    if (showDeleteConfirmDialog) {
        AlertDialog(
            onDismissRequest = { showDeleteConfirmDialog = false },
            title = {
                Text(
                    text = "Delete Image?",
                    color = if (isEInk) Color.Black else Color(0xFFF1F5F9),
                    fontWeight = FontWeight.Bold
                )
            },
            text = {
                Text(
                    text = "Are you sure you want to delete this canvas?\nThis will remove it from the Org note and delete the file from disk.",
                    color = if (isEInk) Color.Black else Color(0xFF94A3B8),
                    fontSize = 14.sp
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        showDeleteConfirmDialog = false
                        syncClient.deleteInkBlock(nodeId, block.fileName, block.linkText)
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = if (isEInk) Color.Black else Color(0xFFDC2626))
                ) {
                    Text("Delete", color = Color.White, fontWeight = FontWeight.SemiBold)
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteConfirmDialog = false }) {
                    Text("Cancel", color = if (isEInk) Color.Black else Color(0xFF94A3B8))
                }
            },
            containerColor = if (isEInk) Color.White else Color(0xFF1E293B),
            shape = RoundedCornerShape(12.dp)
        )
    }
}
}

@Composable
internal fun ParagraphBlockCard(
    block: OrgBlock.ParagraphBlock,
    onUpdateParagraph: (String) -> Unit,
    onInsertSketchAtSplit: (String, String) -> Unit,
    onInsertSketchAfterBlock: () -> Unit,
    onInsertSketchBeforeBlock: () -> Unit
) {
    val isEInk = OnyxEInkBridge.isEInkDevice
    var isEditing by remember(block.blockId) { mutableStateOf(false) }
    var showMenu by remember(block.blockId) { mutableStateOf(false) }
    var menuOffset by remember(block.blockId) { mutableStateOf(DpOffset.Zero) }
    val density = LocalDensity.current

    var textFieldValue by remember(block.blockId, block.text) {
        mutableStateOf(
            TextFieldValue(
                text = block.text,
                selection = TextRange(block.text.length)
            )
        )
    }

    Box {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(8.dp))
                .background(if (isEInk) Color.White else Color(0xFF1E293B))
                .then(if (isEInk) Modifier.border(1.dp, Color.Black, RoundedCornerShape(8.dp)) else Modifier)
                .then(
                    if (!isEditing) {
                        Modifier.pointerInput(block.blockId) {
                            detectTapGestures(
                                onTap = { isEditing = true },
                                onLongPress = { offset ->
                                    menuOffset = DpOffset(with(density) { offset.x.toDp() }, with(density) { offset.y.toDp() })
                                    showMenu = true
                                }
                            )
                        }
                    } else Modifier
                )
                .padding(14.dp)
        ) {
            if (isEditing) {
                OutlinedTextField(
                    value = textFieldValue,
                    onValueChange = { textFieldValue = it },
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(modifier = Modifier.height(6.dp))
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    TextButton(
                        onClick = {
                            val fullText = textFieldValue.text
                            val cursor = textFieldValue.selection.start.coerceIn(0, fullText.length)
                            val before = fullText.substring(0, cursor)
                            val after = fullText.substring(cursor)
                            onInsertSketchAtSplit(before, after)
                            isEditing = false
                        }
                    ) {
                        Text("+ Add Sketch at Cursor", color = if (isEInk) Color.Black else Color(0xFF38BDF8))
                    }

                    Row {
                        TextButton(onClick = {
                            textFieldValue = TextFieldValue(
                                text = block.text,
                                selection = TextRange(block.text.length)
                            )
                            isEditing = false
                        }) {
                            Text("Cancel")
                        }
                        TextButton(onClick = {
                            onUpdateParagraph(textFieldValue.text)
                            isEditing = false
                        }) {
                            Text("Save to Emacs")
                        }
                    }
                }
            } else {
                Row(
                    verticalAlignment = Alignment.Top,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text = block.text,
                        color = if (isEInk) Color.Black else Color(0xFFE2E8F0),
                        fontSize = 15.sp,
                        lineHeight = 22.sp,
                        modifier = Modifier.weight(1f)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "✎ Edit / Split",
                        color = if (isEInk) Color.Black else Color(0xFF94A3B8),
                        fontSize = 11.sp,
                        modifier = Modifier
                            .clip(RoundedCornerShape(6.dp))
                            .background(if (isEInk) Color(0xFFF1F5F9) else Color(0xFF0F172A))
                            .border(1.dp, if (isEInk) Color.Black else Color(0xFF334155), RoundedCornerShape(6.dp))
                            .clickable { isEditing = true }
                            .padding(horizontal = 8.dp, vertical = 4.dp)
                    )
                }
            }
        }

        SketchInsertionPopupMenu(
            expanded = showMenu,
            onDismissRequest = { showMenu = false },
            offset = menuOffset,
            belowLabel = "＋ Add Sketch Below",
            aboveLabel = "＋ Add Sketch Above",
            onInsertBelow = onInsertSketchAfterBlock,
            onInsertAbove = onInsertSketchBeforeBlock
        )
    }
}
