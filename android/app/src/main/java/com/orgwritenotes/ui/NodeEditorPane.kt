package com.orgwritenotes.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.ui.platform.LocalContext
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import com.orgwritenotes.ink.OnyxEInkBridge
import com.orgwritenotes.ink.StylusToolMode
import com.orgwritenotes.ink.WriteCanvasBlockView
import com.orgwritenotes.ink.WritePageSetupState
import com.orgwritenotes.ink.WritePenTip
import com.orgwritenotes.ink.WriteSelectionState
import com.orgwritenotes.model.*
import com.orgwritenotes.sync.ConnectionState
import com.orgwritenotes.sync.EmacsSyncClient
import kotlinx.coroutines.delay

// Right-hand editor pane for the active Org node: header card, clock, properties, blocks.

/**
 * Owns the live org-clock tick so only this chip recomposes each tick, not the whole editor pane
 * (and its ink canvases). E-Ink panels tick once a minute to avoid a screen refresh every second.
 */
@Composable
internal fun ClockChip(
    nodeId: String,
    clockedIn: Boolean,
    clockedMinutes: Int,
    isEInk: Boolean,
    onToggle: () -> Unit
) {
    var extraSeconds by remember(nodeId, clockedIn, clockedMinutes) { mutableStateOf(0) }
    val tickSeconds = if (isEInk) 60 else 1
    LaunchedEffect(nodeId, clockedIn, tickSeconds) {
        if (clockedIn) {
            while (true) {
                delay(tickSeconds * 1000L)
                extraSeconds += tickSeconds
            }
        }
    }

    val totalMinutes = clockedMinutes + (extraSeconds / 60)
    val clockLabel = if (clockedIn) {
        if (isEInk) "⏱ Clocked In (${totalMinutes}m) • Stop" else "⏱ Clocked In (${totalMinutes}m ${extraSeconds % 60}s) • Stop"
    } else if (clockedMinutes > 0) {
        "⏱ Clock In (${clockedMinutes}m)"
    } else {
        "⏱ Clock In"
    }
    Text(
        text = clockLabel,
        color = if (isEInk) Color.Black else (if (clockedIn) Color(0xFF22C55E) else Color(0xFF94A3B8)),
        fontSize = 11.sp,
        fontFamily = FontFamily.Monospace,
        fontWeight = if (clockedIn) FontWeight.Bold else FontWeight.Normal,
        modifier = Modifier
            .clip(RoundedCornerShape(6.dp))
            .background(
                if (isEInk) (if (clockedIn) Color(0xFFE2E8F0) else Color(0xFFF1F5F9))
                else (if (clockedIn) Color(0xFF064E3B) else Color(0xFF0F172A))
            )
            .then(if (isEInk) Modifier.border(1.dp, Color.Black, RoundedCornerShape(6.dp)) else Modifier)
            .clickable(onClick = onToggle)
            .padding(horizontal = 8.dp, vertical = 4.dp)
    )
}

@Composable
internal fun HybridNodeEditorPane(
    snapshot: OrgNodeSnapshot,
    syncClient: EmacsSyncClient,
    isPinned: Boolean = true,
    connectionState: ConnectionState = ConnectionState.DISCONNECTED,
    onOpenRecentNodes: () -> Unit = {},
    onZoomInkBlock: (String, WriteCanvasBlockView?) -> Unit
) {
    val isEInk = OnyxEInkBridge.isEInkDevice
    var showPropertiesDrawer by remember { mutableStateOf(false) }
    var isEditingTitle by remember { mutableStateOf(false) }
    var showPlanningDialog by remember { mutableStateOf<String?>(null) } // "scheduled", "deadline", or "tags"
    var editedTitle by remember(snapshot.nodeId, snapshot.header.title) {
        mutableStateOf(snapshot.header.title)
    }

    // All inline drawings in the current Org node
    val inkBlocks = remember(snapshot.blocks) {
        snapshot.blocks.filterIsInstance<OrgBlock.InkCanvasBlock>()
    }

    // Track the currently active / last-selected drawing block and live C++ view map
    var activeInkBlockId by remember(snapshot.nodeId) {
        mutableStateOf(inkBlocks.firstOrNull()?.blockId)
    }
    var previousInkBlockCount by remember(snapshot.nodeId) {
        mutableStateOf(inkBlocks.size)
    }
    val canvasViews = remember(snapshot.nodeId) {
        mutableStateMapOf<String, WriteCanvasBlockView>()
    }
    // Window bounds of the scrolling block list: the Onyx pen layer must not paint outside it (e.g. over the toolbar).
    var blockListClip by remember { mutableStateOf<android.graphics.Rect?>(null) }
    // Sync adaptive theme from Emacs to all active canvas views (always light on E-Ink)
    val emacsTheme by syncClient.emacsTheme.collectAsState()
    LaunchedEffect(emacsTheme, canvasViews.size, isEInk) {
        val effectiveDark = !isEInk && emacsTheme.isDark
        val bgArgb = emacsTheme.resolvedDarkBgArgb()
        val fgArgb = emacsTheme.resolvedDarkFgArgb()
        canvasViews.values.forEach { view ->
            view.setAdaptiveTheme(effectiveDark, bgArgb, fgArgb)
        }
    }

    // Shared Stylus Labs Write Tool State across inline drawings
    var activeMode by remember(snapshot.nodeId) { mutableStateOf(StylusToolMode.PEN) }
    var lastEraserMode by remember(snapshot.nodeId) { mutableStateOf(StylusToolMode.ERASER) }
    var lastSelectMode by remember(snapshot.nodeId) { mutableStateOf(StylusToolMode.SELECT_LASSO) }
    var lastInsertSpaceMode by remember(snapshot.nodeId) { mutableStateOf(StylusToolMode.INSERT_SPACE_RULED) }
    var showPenToolbar by remember(snapshot.nodeId) { mutableStateOf(true) }
    var selectedColor by remember(snapshot.nodeId) {
        mutableStateOf(if (isEInk) 0xFF000000.toInt() else 0xFF1E293B.toInt())
    }
    var selectedWidth by remember(snapshot.nodeId) { mutableStateOf(2.6f) }
    var selectedPenTip by remember(snapshot.nodeId) { mutableStateOf(WritePenTip.ROUND) }
    var chiselAngle by remember(snapshot.nodeId) { mutableStateOf(45f) }
    var pressureEnabled by remember(snapshot.nodeId) { mutableStateOf(true) }
    var snapToGrid by remember(snapshot.nodeId) { mutableStateOf(false) }
    var lineDrawing by remember(snapshot.nodeId) { mutableStateOf(false) }

    var selectionState by remember(snapshot.nodeId) { mutableStateOf(WriteSelectionState()) }
    var canUndo by remember(snapshot.nodeId) { mutableStateOf(false) }
    var canRedo by remember(snapshot.nodeId) { mutableStateOf(false) }
    var showPageSetupDialog by remember { mutableStateOf(false) }
    var showOcrDialog by remember { mutableStateOf(false) }
    var ocrDraftText by remember { mutableStateOf("") }

    // Auto-Hide Stylus Labs Write Toolbar state
    var isToolbarVisible by remember(snapshot.nodeId) { mutableStateOf(inkBlocks.isNotEmpty()) }
    var isToolbarPinned by remember { mutableStateOf(false) }
    var interactionTick by remember { mutableStateOf(System.currentTimeMillis()) }

    val wakeUpToolbar = {
        val now = System.currentTimeMillis()
        if (!isToolbarVisible) {
            isToolbarVisible = true
            interactionTick = now
        } else if (!isToolbarPinned && now - interactionTick > 1500L) {
            interactionTick = now
        }
    }

    // Automatically select newly inserted drawings or fallback to first drawing
    LaunchedEffect(inkBlocks) {
        if (inkBlocks.isEmpty()) {
            activeInkBlockId = null
        } else if (inkBlocks.size > previousInkBlockCount) {
            activeInkBlockId = inkBlocks.last().blockId
            wakeUpToolbar()
        } else if (activeInkBlockId == null || inkBlocks.none { it.blockId == activeInkBlockId }) {
            activeInkBlockId = inkBlocks.first().blockId
        }
        previousInkBlockCount = inkBlocks.size
    }

    val activeInkBlock = remember(inkBlocks, activeInkBlockId) {
        inkBlocks.find { it.blockId == activeInkBlockId } ?: inkBlocks.firstOrNull()
    }
    val activeCanvasView = activeInkBlock?.let { canvasViews[it.blockId] }

    // Consume pending shared image from Android Intent.ACTION_SEND (Tier 4.2)
    val pendingSharedImage by syncClient.pendingSharedImageBytes.collectAsState()
    LaunchedEffect(pendingSharedImage, activeCanvasView, inkBlocks.size) {
        val imgBytes = pendingSharedImage ?: return@LaunchedEffect
        if (activeCanvasView != null) {
            activeCanvasView.insertImageBytes(imgBytes, asBackground = false)
            syncClient.clearPendingSharedImageBytes()
        } else if (inkBlocks.isEmpty()) {
            syncClient.insertInkBlock(snapshot.nodeId, afterLine = 0)
        }
    }

    val context = LocalContext.current
    val imagePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri ->
        if (uri != null) {
            try {
                context.contentResolver.openInputStream(uri)?.use { stream ->
                    val bytes = stream.readBytes()
                    if (bytes.isNotEmpty()) {
                        activeCanvasView?.insertImageBytes(bytes, asBackground = false)
                    }
                }
            } catch (_: Exception) {}
        }
    }

    var pageSetup by remember(activeInkBlock?.blockId) {
        mutableStateOf(
            WritePageSetupState(
                width = activeInkBlock?.width ?: 1200,
                height = activeInkBlock?.height ?: 600,
                xRuling = activeInkBlock?.xRuling ?: 0,
                yRuling = activeInkBlock?.yRuling ?: 40
            )
        )
    }

    // Auto-hide timer: hides the shared Write toolbar after 4.5s of inactivity unless pinned
    // or a selection / modal dialog is active
    LaunchedEffect(
        interactionTick,
        isToolbarVisible,
        isToolbarPinned,
        selectionState.hasSelection,
        showPageSetupDialog
    ) {
        if (isToolbarVisible && !isToolbarPinned && !selectionState.hasSelection && !showPageSetupDialog) {
            delay(4500L)
            isToolbarVisible = false
        }
    }

    val lazyListState = rememberLazyListState()

    // Auto-hide the toolbar when the user scrolls the Org document (unless pinned)
    LaunchedEffect(lazyListState.isScrollInProgress) {
        if (lazyListState.isScrollInProgress && !isToolbarPinned && !selectionState.hasSelection) {
            isToolbarVisible = false
        }
    }

    val density = LocalDensity.current

    Column(modifier = Modifier.fillMaxSize()) {
        // Node Header Card (Tap TODO or Priority to cycle, tap Title to edit, Press & Hold to add sketch below heading)
        var showHeaderMenu by remember(snapshot.nodeId) { mutableStateOf(false) }
        var headerMenuOffset by remember(snapshot.nodeId) { mutableStateOf(DpOffset.Zero) }

        Box {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .background(if (isEInk) Color.White else Color(0xFF1E293B))
                    .then(if (isEInk) Modifier.border(1.dp, Color.Black, RoundedCornerShape(12.dp)) else Modifier)
                    .pointerInput(snapshot.nodeId) {
                        detectTapGestures(
                            onLongPress = { offset ->
                                headerMenuOffset = DpOffset(with(density) { offset.x.toDp() }, with(density) { offset.y.toDp() })
                                showHeaderMenu = true
                            }
                        )
                    }
                    .padding(horizontal = 16.dp, vertical = 12.dp)
            ) {
            if (snapshot.header.breadcrumbs.isNotEmpty()) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState())
                        .padding(bottom = 6.dp)
                ) {
                    Text(
                        text = "↑ ${snapshot.header.breadcrumbs.joinToString(" › ")}",
                        color = if (isEInk) Color.Black else Color(0xFF38BDF8),
                        fontSize = 11.sp,
                        fontFamily = FontFamily.Monospace,
                        modifier = Modifier
                            .clip(RoundedCornerShape(4.dp))
                            .background(if (isEInk) Color(0xFFF1F5F9) else Color(0xFF0F172A))
                            .then(if (isEInk) Modifier.border(0.5.dp, Color.Black, RoundedCornerShape(4.dp)) else Modifier)
                            .clickable(enabled = snapshot.header.parentNodeId != null) {
                                snapshot.header.parentNodeId?.let { syncClient.fetchNode(it) }
                            }
                            .padding(horizontal = 8.dp, vertical = 3.dp)
                    )
                }
            }

            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth()
            ) {
                if (!isPinned) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .clip(RoundedCornerShape(8.dp))
                            .background(if (isEInk) Color(0xFFF1F5F9) else Color(0xFF334155))
                            .then(if (isEInk) Modifier.border(1.dp, Color.Black, RoundedCornerShape(8.dp)) else Modifier)
                            .clickable { onOpenRecentNodes() }
                            .padding(horizontal = 9.dp, vertical = 6.dp)
                    ) {
                        WriteSvgIcon(asset = WriteIconAsset.MENU, tint = if (isEInk) Color.Black else Color(0xFF38BDF8), size = 15.dp)
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = "Nodes",
                            color = if (isEInk) Color.Black else Color(0xFFE2E8F0),
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Medium
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        ConnectionDot(state = connectionState, size = 7.dp)
                    }
                    Spacer(modifier = Modifier.width(10.dp))
                }

                Box(modifier = Modifier.clickable { syncClient.cycleTodoState(snapshot) }) {
                    TodoBadge(keyword = snapshot.header.todoKeyword ?: "TODO")
                }
                Spacer(modifier = Modifier.width(10.dp))

                Text(
                    text = "[#${snapshot.header.priority ?: "B"}]",
                    color = if (isEInk) Color.Black else Color(0xFFF59E0B),
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier
                        .clip(RoundedCornerShape(4.dp))
                        .clickable { syncClient.cyclePriority(snapshot) }
                        .padding(horizontal = 6.dp, vertical = 2.dp)
                )

                Spacer(modifier = Modifier.width(6.dp))

                if (isEditingTitle) {
                    OutlinedTextField(
                        value = editedTitle,
                        onValueChange = { editedTitle = it },
                        singleLine = true,
                        modifier = Modifier.weight(1f)
                    )
                    TextButton(onClick = {
                        syncClient.updateHeader(snapshot.nodeId, title = editedTitle.trim())
                        isEditingTitle = false
                    }) {
                        Text("Save")
                    }
                } else {
                    Text(
                        text = snapshot.header.title,
                        color = if (isEInk) Color.Black else Color(0xFFF8FAFC),
                        fontSize = 20.sp,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier
                            .weight(1f)
                            .clickable { isEditingTitle = true }
                    )
                }

                snapshot.header.checkboxProgress?.let { prog ->
                    Text(
                        text = "[$prog]",
                        color = if (isEInk) Color.Black else Color(0xFF38BDF8),
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(horizontal = 8.dp)
                    )
                }

                TextButton(onClick = { showPropertiesDrawer = !showPropertiesDrawer }) {
                    Text(
                        text = if (showPropertiesDrawer) "▾ :PROPERTIES:" else "▸ :PROPERTIES:",
                        color = if (isEInk) Color.Black else Color(0xFF94A3B8),
                        fontFamily = FontFamily.Monospace,
                        fontSize = 12.sp
                    )
                }
            }

            // Interactive Planning & Tags Chips Row (Scheduled, Deadline, Tags)
            Spacer(modifier = Modifier.height(6.dp))
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                val schedLabel = snapshot.header.scheduled?.let { "📅 Sched: $it" } ?: "📅 + Schedule"
                Text(
                    text = schedLabel,
                    color = if (isEInk) Color.Black else (if (snapshot.header.scheduled != null) Color(0xFF38BDF8) else Color(0xFF64748B)),
                    fontSize = 11.sp,
                    fontFamily = FontFamily.Monospace,
                    modifier = Modifier
                        .clip(RoundedCornerShape(6.dp))
                        .background(if (isEInk) Color(0xFFF1F5F9) else Color(0xFF0F172A))
                        .then(if (isEInk) Modifier.border(1.dp, Color.Black, RoundedCornerShape(6.dp)) else Modifier)
                        .clickable { showPlanningDialog = "scheduled" }
                        .padding(horizontal = 8.dp, vertical = 4.dp)
                )

                val deadlLabel = snapshot.header.deadline?.let { "⏰ Due: $it" } ?: "⏰ + Deadline"
                Text(
                    text = deadlLabel,
                    color = if (isEInk) Color.Black else (if (snapshot.header.deadline != null) Color(0xFFF87171) else Color(0xFF64748B)),
                    fontSize = 11.sp,
                    fontFamily = FontFamily.Monospace,
                    modifier = Modifier
                        .clip(RoundedCornerShape(6.dp))
                        .background(if (isEInk) Color(0xFFF1F5F9) else Color(0xFF0F172A))
                        .then(if (isEInk) Modifier.border(1.dp, Color.Black, RoundedCornerShape(6.dp)) else Modifier)
                        .clickable { showPlanningDialog = "deadline" }
                        .padding(horizontal = 8.dp, vertical = 4.dp)
                )

                val tagsLabel = if (snapshot.header.tags.isNotEmpty()) {
                    ":${snapshot.header.tags.joinToString(":")}:"
                } else {
                    "🏷 + Tags"
                }
                Text(
                    text = tagsLabel,
                    color = if (isEInk) Color.Black else (if (snapshot.header.tags.isNotEmpty()) Color(0xFFA78BFA) else Color(0xFF64748B)),
                    fontSize = 11.sp,
                    fontFamily = FontFamily.Monospace,
                    modifier = Modifier
                        .clip(RoundedCornerShape(6.dp))
                        .background(if (isEInk) Color(0xFFF1F5F9) else Color(0xFF0F172A))
                        .then(if (isEInk) Modifier.border(1.dp, Color.Black, RoundedCornerShape(6.dp)) else Modifier)
                        .clickable { showPlanningDialog = "tags" }
                        .padding(horizontal = 8.dp, vertical = 4.dp)
                )

                ClockChip(
                    nodeId = snapshot.nodeId,
                    clockedIn = snapshot.header.clockedIn,
                    clockedMinutes = snapshot.header.clockedMinutes,
                    isEInk = isEInk,
                    onToggle = {
                        if (snapshot.header.clockedIn) {
                            syncClient.clockOut(snapshot.nodeId)
                        } else {
                            syncClient.clockIn(snapshot.nodeId)
                        }
                    }
                )
            }

            if (showPropertiesDrawer) {
                Spacer(modifier = Modifier.height(10.dp))
                PropertiesDrawerPanel(
                    nodeId = snapshot.nodeId,
                    properties = snapshot.header.properties,
                    scheduled = snapshot.header.scheduled,
                    deadline = snapshot.header.deadline,
                    onSaveProperty = { k, v -> syncClient.setProperty(snapshot.nodeId, k, v) }
                )
            }
        }

        SketchInsertionPopupMenu(
            expanded = showHeaderMenu,
            onDismissRequest = { showHeaderMenu = false },
            offset = headerMenuOffset,
            belowLabel = "＋ Add Sketch Below Heading",
            onInsertBelow = {
                syncClient.insertInkBlock(snapshot.nodeId, afterLine = 0)
            }
        )
    }

        // ====================================================================
        // Shared Auto-Hiding Stylus Labs Write Toolbar for Active / Last-Selected Drawing
        // ====================================================================
        // Toolbar sits above the blocks, or below them when docked to the bottom (floating applies to the full-screen editor).
        val inlineToolbarAtBottom = UiPrefs.toolbarDock == ToolbarDock.BOTTOM
        val inlineToolbarSection: @Composable () -> Unit = {
            if (activeInkBlock != null) {
                Spacer(modifier = Modifier.height(8.dp))

                val applyToolMode: (StylusToolMode, Int, Float, WritePenTip, StylusToolMode?, StylusToolMode?, StylusToolMode?) -> Unit =
                    { newMode, newColor, newWidth, newTip, eraseDef, selectDef, insDef ->
                        activeMode = newMode
                        selectedColor = newColor
                        selectedWidth = newWidth
                        selectedPenTip = newTip
                        if (eraseDef != null) lastEraserMode = eraseDef
                        if (selectDef != null) lastSelectMode = selectDef
                        if (insDef != null) lastInsertSpaceMode = insDef
                        // Apply to all mounted inline canvases so whichever the user writes on next uses this tool
                        canvasViews.values.forEach { view ->
                            view.configureTool(
                                newMode,
                                newColor,
                                newWidth,
                                newTip,
                                pressureEnabled,
                                snapToGrid,
                                lineDrawing,
                                chiselAngle
                            )
                        }
                    }
                RadialPenMenuHost(
                    accepts = { view -> canvasViews.values.any { it === view } },
                    activeMode = activeMode,
                    selectedColor = selectedColor,
                    selectedWidth = selectedWidth,
                    selectedPenTip = selectedPenTip,
                    lastEraserMode = lastEraserMode,
                    lastSelectMode = lastSelectMode,
                    onSelectToolMode = applyToolMode
                )
                val toolbarContent: @Composable () -> Unit = {
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        WriteSharedToolbarChrome(
                            activeFileName = activeInkBlock.fileName,
                            pageSetup = pageSetup,
                            activeMode = activeMode,
                            lastEraserMode = lastEraserMode,
                            lastSelectMode = lastSelectMode,
                            lastInsertSpaceMode = lastInsertSpaceMode,
                            showPenToolbar = showPenToolbar,
                            selectedColor = selectedColor,
                            selectedWidth = selectedWidth,
                            selectedPenTip = selectedPenTip,
                            canUndo = canUndo,
                            canRedo = canRedo,
                            hasSelection = selectionState.hasSelection,
                            isZoomedFullScreen = false,
                            isAutoHidePinned = isToolbarPinned,
                            snapToGrid = snapToGrid,
                            lineDrawing = lineDrawing,
                            onToggleSnapToGrid = {
                                snapToGrid = !snapToGrid
                                wakeUpToolbar()
                                canvasViews.values.forEach { view ->
                                    view.configureTool(
                                        activeMode,
                                        selectedColor,
                                        selectedWidth,
                                        selectedPenTip,
                                        pressureEnabled,
                                        snapToGrid,
                                        lineDrawing,
                                        chiselAngle
                                    )
                                }
                            },
                            onToggleLineDrawing = {
                                lineDrawing = !lineDrawing
                                wakeUpToolbar()
                                canvasViews.values.forEach { view ->
                                    view.configureTool(
                                        activeMode,
                                        selectedColor,
                                        selectedWidth,
                                        selectedPenTip,
                                        pressureEnabled,
                                        snapToGrid,
                                        lineDrawing,
                                        chiselAngle
                                    )
                                }
                            },
                            onPasteClipboard = {
                                wakeUpToolbar()
                                activeCanvasView?.pasteClipboardStrokes()
                            },
                            onTogglePin = {
                                isToolbarPinned = !isToolbarPinned
                                wakeUpToolbar()
                            },
                            onAutoHideDismiss = {
                                isToolbarPinned = false
                                isToolbarVisible = false
                            },
                            onZoomOrCloseFullScreen = {
                                onZoomInkBlock(activeInkBlock.fileName, activeCanvasView)
                            },
                            onSelectToolMode = applyToolMode,
                            onTogglePenToolbar = { showPenToolbar = !showPenToolbar },
                            onSelectAllStrokes = { activeCanvasView?.selectAllStrokes() },
                            onApplyStyleToSelection = { activeCanvasView?.applyCurrentStyleToSelection() },
                            onUndo = { activeCanvasView?.performUndo() },
                            onRedo = { activeCanvasView?.performRedo() },
                            onExpandDown = { activeCanvasView?.expandDown() },
                            onExpandRight = { activeCanvasView?.expandRight() },
                            onTrimWhitespace = {
                                wakeUpToolbar()
                                activeCanvasView?.trimWhitespace()
                            },
                            onOpenPageSetup = { showPageSetupDialog = true },
                            pressureEnabled = pressureEnabled,
                            chiselAngle = chiselAngle,
                            onTogglePressure = {
                                pressureEnabled = !pressureEnabled
                                wakeUpToolbar()
                                canvasViews.values.forEach { view ->
                                    view.configureTool(
                                        activeMode,
                                        selectedColor,
                                        selectedWidth,
                                        selectedPenTip,
                                        pressureEnabled,
                                        snapToGrid,
                                        lineDrawing,
                                        chiselAngle
                                    )
                                }
                            },
                            onChangeChiselAngle = { newAngle ->
                                chiselAngle = newAngle
                                wakeUpToolbar()
                                canvasViews.values.forEach { view ->
                                    view.configureTool(
                                        activeMode,
                                        selectedColor,
                                        selectedWidth,
                                        selectedPenTip,
                                        pressureEnabled,
                                        snapToGrid,
                                        lineDrawing,
                                        newAngle
                                    )
                                }
                            },
                            onInsertImage = {
                                wakeUpToolbar()
                                imagePickerLauncher.launch("image/*")
                            },
                            onDeleteSelection = {
                                wakeUpToolbar()
                                activeCanvasView?.deleteSelectedStrokes()
                            },
                            isDarkCanvasTheme = !isEInk && emacsTheme.isDark,
                            onToggleDarkCanvasTheme = if (isEInk) null else {
                                {
                                    wakeUpToolbar()
                                    syncClient.toggleCanvasDarkMode()
                                }
                            },
                            onUserInteracted = { wakeUpToolbar() }
                        )

                        if (selectionState.hasSelection) {
                            WriteSelectionPopupBar(
                                selectionCount = selectionState.count,
                                selectedColor = selectedColor,
                                onDuplicate = {
                                    wakeUpToolbar()
                                    activeCanvasView?.duplicateSelectedStrokes()
                                },
                                onCopy = {
                                    wakeUpToolbar()
                                    activeCanvasView?.copySelectedStrokes()
                                },
                                onCut = {
                                    wakeUpToolbar()
                                    activeCanvasView?.cutSelectedStrokes()
                                },
                                onPaste = {
                                    wakeUpToolbar()
                                    activeCanvasView?.pasteClipboardStrokes()
                                },
                                onScaleSmaller = {
                                    wakeUpToolbar()
                                    activeCanvasView?.scaleSelectedStrokes(0.9f)
                                },
                                onScaleLarger = {
                                    wakeUpToolbar()
                                    activeCanvasView?.scaleSelectedStrokes(1.1f)
                                },
                                onRotateCcw = {
                                    wakeUpToolbar()
                                    activeCanvasView?.rotateSelectedStrokes(-15f)
                                },
                                onRotateCw = {
                                    wakeUpToolbar()
                                    activeCanvasView?.rotateSelectedStrokes(15f)
                                },
                                onApplyStyle = {
                                    wakeUpToolbar()
                                    activeCanvasView?.applyCurrentStyleToSelection()
                                },
                                onConvertToText = {
                                    wakeUpToolbar()
                                    ocrDraftText = activeCanvasView?.recognizeSelectionOcr().orEmpty()
                                    showOcrDialog = true
                                },
                                onDelete = {
                                    wakeUpToolbar()
                                    activeCanvasView?.deleteSelectedStrokes()
                                },
                                onClearSelection = {
                                    wakeUpToolbar()
                                    activeCanvasView?.clearSelection()
                                }
                            )
                        }
                    }
                }

                if (isEInk) {
                    if (isToolbarVisible) {
                        toolbarContent()
                    }
                } else {
                    AnimatedVisibility(
                        visible = isToolbarVisible,
                        enter = expandVertically() + fadeIn(),
                        exit = shrinkVertically() + fadeOut()
                    ) {
                        toolbarContent()
                    }
                }

                // Sleek collapsed pill when the Write toolbar is auto-hidden
                val collapsedPillContent: @Composable () -> Unit = {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(8.dp))
                            .background(if (isEInk) Color.White else Color(0xFF1E293B))
                            .border(1.dp, if (isEInk) Color.Black else Color(0xFF334155), RoundedCornerShape(8.dp))
                            .clickable { wakeUpToolbar() }
                            .padding(horizontal = 12.dp, vertical = 6.dp)
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(
                                modifier = Modifier
                                    .size(10.dp)
                                    .clip(CircleShape)
                                    .background(Color(selectedColor))
                                    .border(1.dp, if (isEInk) Color.Black else Color.White, CircleShape)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = "✎ Write Tools — Touch drawing with S-Pen or tap to show toolbar",
                                color = if (isEInk) Color.Black else Color(0xFF94A3B8),
                                fontSize = 12.sp
                            )
                        }

                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .clip(RoundedCornerShape(6.dp))
                                .background(if (isEInk) Color(0xFFF1F5F9) else Color(0xFF0F172A))
                                .then(if (isEInk) Modifier.border(1.dp, Color.Black, RoundedCornerShape(6.dp)) else Modifier)
                                .clickable { onZoomInkBlock(activeInkBlock.fileName, activeCanvasView) }
                                .padding(horizontal = 8.dp, vertical = 3.dp)
                        ) {
                            WriteSvgIcon(asset = WriteIconAsset.FULLSCREEN, tint = if (isEInk) Color.Black else Color(0xFF38BDF8), size = 13.dp)
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(
                                text = "Zoom",
                                color = if (isEInk) Color.Black else Color(0xFF38BDF8),
                                fontSize = 11.sp,
                                fontWeight = FontWeight.SemiBold
                            )
                        }
                    }
                }

                if (isEInk) {
                    if (!isToolbarVisible) {
                        collapsedPillContent()
                    }
                } else {
                    AnimatedVisibility(
                        visible = !isToolbarVisible,
                        enter = fadeIn(),
                        exit = fadeOut()
                    ) {
                        collapsedPillContent()
                    }
                }
            }
        }
        if (!inlineToolbarAtBottom) inlineToolbarSection()

        Spacer(modifier = Modifier.height(8.dp))

        // Interleaved Blocks (Checklists, Editable Paragraphs, and Clean Stylus Labs Write Sheets)
        LazyColumn(
            state = lazyListState,
            verticalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier
                .weight(1f)
                .onGloballyPositioned { c ->
                    val b = c.boundsInWindow()
                    val r = android.graphics.Rect(b.left.toInt(), b.top.toInt(), b.right.toInt(), b.bottom.toInt())
                    if (blockListClip != r) blockListClip = r
                }
        ) {
            if (snapshot.blocks.isEmpty()) {
                item(key = "empty-node-placeholder") {
                    var showEmptyMenu by remember(snapshot.nodeId) { mutableStateOf(false) }
                    var menuOffset by remember(snapshot.nodeId) { mutableStateOf(DpOffset.Zero) }

                    Box(
                        contentAlignment = Alignment.Center,
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 220.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .border(1.dp, if (isEInk) Color.Black else Color(0xFF334155), RoundedCornerShape(12.dp))
                            .background(if (isEInk) Color.White else Color(0xFF0F172A).copy(alpha = 0.5f))
                            .pointerInput(snapshot.nodeId) {
                                detectTapGestures(
                                    onTap = { offset ->
                                        menuOffset = DpOffset(with(density) { offset.x.toDp() }, with(density) { offset.y.toDp() })
                                        showEmptyMenu = true
                                    },
                                    onLongPress = { offset ->
                                        menuOffset = DpOffset(with(density) { offset.x.toDp() }, with(density) { offset.y.toDp() })
                                        showEmptyMenu = true
                                    }
                                )
                            }
                            .padding(24.dp)
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(
                                text = "No content in this heading yet",
                                color = if (isEInk) Color.Black else Color(0xFF64748B),
                                fontSize = 14.sp
                            )
                            Spacer(modifier = Modifier.height(6.dp))
                            Text(
                                text = "Press and hold anywhere to add a sketch",
                                color = if (isEInk) Color(0xFF334155) else Color(0xFF475569),
                                fontSize = 12.sp
                            )
                        }

                        SketchInsertionPopupMenu(
                            expanded = showEmptyMenu,
                            onDismissRequest = { showEmptyMenu = false },
                            offset = menuOffset,
                            belowLabel = "＋ Add Sketch Here",
                            onInsertBelow = {
                                syncClient.insertInkBlock(snapshot.nodeId, afterLine = 0)
                            }
                        )
                    }
                }
            } else {
                item(key = "top-space") {
                    var showTopMenu by remember(snapshot.nodeId) { mutableStateOf(false) }
                    var topMenuOffset by remember(snapshot.nodeId) { mutableStateOf(DpOffset.Zero) }

                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(14.dp)
                            .pointerInput(snapshot.nodeId) {
                                detectTapGestures(
                                    onLongPress = { offset ->
                                        topMenuOffset = DpOffset(with(density) { offset.x.toDp() }, with(density) { offset.y.toDp() })
                                        showTopMenu = true
                                    }
                                )
                            }
                    ) {
                        SketchInsertionPopupMenu(
                            expanded = showTopMenu,
                            onDismissRequest = { showTopMenu = false },
                            offset = topMenuOffset,
                            belowLabel = "＋ Add Sketch at Top",
                            onInsertBelow = {
                                syncClient.insertInkBlock(snapshot.nodeId, afterLine = 0)
                            }
                        )
                    }
                }

                items(snapshot.blocks, key = { it.blockId }) { block ->
                    when (block) {
                        is OrgBlock.ChecklistBlock -> {
                            ChecklistBlockCard(
                                block = block,
                                onToggle = { item, checked ->
                                    syncClient.toggleCheckbox(snapshot.nodeId, item.line, checked)
                                },
                                onSetPartial = { item ->
                                    syncClient.setCheckboxState(snapshot.nodeId, item.line, ChecklistState.PARTIAL)
                                },
                                onUpdateItem = { item, newText, deltaIndent, moveDir ->
                                    val act = when {
                                        deltaIndent > 0 -> "indent"
                                        deltaIndent < 0 -> "outdent"
                                        moveDir < 0 -> "moveUp"
                                        moveDir > 0 -> "moveDown"
                                        else -> null
                                    }
                                    syncClient.updateChecklistItem(
                                        nodeId = snapshot.nodeId,
                                        line = item.line,
                                        text = newText,
                                        action = act
                                    )
                                },
                                onDeleteItem = { item ->
                                    syncClient.deleteChecklistItem(snapshot.nodeId, item.line)
                                },
                                onInsertSketchAfterItem = { item ->
                                    syncClient.insertInkBlock(snapshot.nodeId, afterLine = item.line)
                                },
                                onInsertSketchAfterBlock = {
                                    syncClient.insertInkBlock(snapshot.nodeId, afterLine = block.endLine)
                                },
                                onInsertSketchBeforeBlock = {
                                    val lineBefore = (block.startLine - 1).coerceAtLeast(0)
                                    syncClient.insertInkBlock(snapshot.nodeId, afterLine = lineBefore)
                                },
                                onAddItem = { newText ->
                                    val lastLine = block.items.lastOrNull()?.line ?: block.endLine
                                    syncClient.addChecklistItem(
                                        nodeId = snapshot.nodeId,
                                        text = newText,
                                        afterLine = lastLine
                                    )
                                }
                            )
                        }

                        is OrgBlock.InkCanvasBlock -> {
                            val isActiveDrawing = activeInkBlock?.blockId == block.blockId
                            InkCanvasBlockCard(
                                nodeId = snapshot.nodeId,
                                block = block,
                                isActiveDrawing = isActiveDrawing,
                                emacsTheme = emacsTheme,
                                activeMode = activeMode,
                                selectedColor = selectedColor,
                                selectedWidth = selectedWidth,
                                selectedPenTip = selectedPenTip,
                                pressureEnabled = pressureEnabled,
                                chiselAngle = chiselAngle,
                                snapToGrid = snapToGrid,
                                lineDrawing = lineDrawing,
                                syncClient = syncClient,
                                onRegisterView = { view ->
                                    view.rawClipWindowRect = blockListClip
                                    if (canvasViews[block.blockId] !== view) canvasViews[block.blockId] = view
                                },
                                onUnregisterView = { view ->
                                    // Scrolled off / deleted: drop the reference so detached native views aren't retained.
                                    if (canvasViews[block.blockId] === view) canvasViews.remove(block.blockId)
                                },
                                onActivateDrawing = { view ->
                                    val switchedBlock = activeInkBlockId != block.blockId
                                    if (switchedBlock) {
                                        // Clear any selection on the previously active drawing
                                        activeCanvasView?.clearSelection()
                                        activeInkBlockId = block.blockId
                                    }
                                    view.configureTool(
                                        activeMode,
                                        selectedColor,
                                        selectedWidth,
                                        selectedPenTip,
                                        pressureEnabled,
                                        snapToGrid,
                                        lineDrawing,
                                        chiselAngle
                                    )
                                    if (switchedBlock || !isToolbarVisible) {
                                        canUndo = view.canUndoNow()
                                        canRedo = view.canRedoNow()
                                        pageSetup = view.queryPageSetupState()
                                    }
                                    wakeUpToolbar()
                                },
                                onActiveStateUpdated = { undoAvail, redoAvail, setup, sel ->
                                    if (activeInkBlock?.blockId == block.blockId) {
                                        canUndo = undoAvail
                                        canRedo = redoAvail
                                        pageSetup = setup
                                        selectionState = sel
                                    }
                                },
                                onZoomIntoDrawing = { onZoomInkBlock(block.fileName, canvasViews[block.blockId]) },
                                onInsertSketchAfterBlock = {
                                    syncClient.insertInkBlock(snapshot.nodeId, afterLine = block.endLine)
                                },
                                onInsertSketchBeforeBlock = {
                                    val lineBefore = (block.startLine - 1).coerceAtLeast(0)
                                    syncClient.insertInkBlock(snapshot.nodeId, afterLine = lineBefore)
                                }
                            )
                        }

                        is OrgBlock.ParagraphBlock -> {
                            ParagraphBlockCard(
                                block = block,
                                onUpdateParagraph = { newText ->
                                    syncClient.updateParagraph(
                                        nodeId = snapshot.nodeId,
                                        startLine = block.startLine,
                                        endLine = block.endLine,
                                        oldText = block.text,
                                        newText = newText
                                    )
                                },
                                onInsertSketchAtSplit = { textBefore, textAfter ->
                                    syncClient.insertInkBlockAtParagraphSplit(
                                        nodeId = snapshot.nodeId,
                                        startLine = block.startLine,
                                        endLine = block.endLine,
                                        textBefore = textBefore,
                                        textAfter = textAfter
                                    )
                                },
                                onInsertSketchAfterBlock = {
                                    syncClient.insertInkBlock(snapshot.nodeId, afterLine = block.endLine)
                                },
                                onInsertSketchBeforeBlock = {
                                    val lineBefore = (block.startLine - 1).coerceAtLeast(0)
                                    syncClient.insertInkBlock(snapshot.nodeId, afterLine = lineBefore)
                                }
                            )
                        }

                        is OrgBlock.CodeBlock -> {
                            CodeBlockCard(
                                block = block,
                                onExecute = {
                                    syncClient.executeCodeBlock(snapshot.nodeId, block.startLine)
                                },
                                onInsertSketchAfterBlock = {
                                    syncClient.insertInkBlock(snapshot.nodeId, afterLine = block.endLine)
                                },
                                onInsertSketchBeforeBlock = {
                                    val lineBefore = (block.startLine - 1).coerceAtLeast(0)
                                    syncClient.insertInkBlock(snapshot.nodeId, afterLine = lineBefore)
                                }
                            )
                        }

                        is OrgBlock.TableBlock -> {
                            TableBlockCard(
                                block = block,
                                onUpdateCell = { rowIdx, colIdx, newVal ->
                                    syncClient.updateTableCell(snapshot.nodeId, block.startLine, rowIdx, colIdx, newVal)
                                },
                                onInsertSketchAfterBlock = {
                                    syncClient.insertInkBlock(snapshot.nodeId, afterLine = block.endLine)
                                },
                                onInsertSketchBeforeBlock = {
                                    val lineBefore = (block.startLine - 1).coerceAtLeast(0)
                                    syncClient.insertInkBlock(snapshot.nodeId, afterLine = lineBefore)
                                }
                            )
                        }
                    }
                }

                if (snapshot.subheadings.isNotEmpty()) {
                    item(key = "child-subheadings") {
                        Column(
                            verticalArrangement = Arrangement.spacedBy(6.dp),
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(10.dp))
                                .background(if (isEInk) Color.White else Color(0xFF1E293B))
                                .border(1.dp, if (isEInk) Color.Black else Color(0xFF334155), RoundedCornerShape(10.dp))
                                .padding(12.dp)
                        ) {
                            Text(
                                text = "SUBHEADINGS (${snapshot.subheadings.size})",
                                color = if (isEInk) Color.Black else Color(0xFF94A3B8),
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold
                            )
                            snapshot.subheadings.forEach { sub ->
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clip(RoundedCornerShape(6.dp))
                                        .background(if (isEInk) Color(0xFFF1F5F9) else Color(0xFF0F172A))
                                        .then(if (isEInk) Modifier.border(1.dp, Color.Black, RoundedCornerShape(6.dp)) else Modifier)
                                        .clickable { syncClient.fetchNode(sub.nodeId) }
                                        .padding(horizontal = 10.dp, vertical = 8.dp)
                                ) {
                                    sub.todoKeyword?.let { kw ->
                                        TodoBadge(keyword = kw)
                                        Spacer(modifier = Modifier.width(8.dp))
                                    }
                                    Text(
                                        text = sub.title,
                                        color = if (isEInk) Color.Black else Color(0xFFF8FAFC),
                                        fontSize = 14.sp,
                                        fontWeight = FontWeight.Medium,
                                        modifier = Modifier.weight(1f)
                                    )
                                    if (sub.sketchCount > 0) {
                                        Text(
                                            text = "🎨 ${sub.sketchCount}",
                                            color = if (isEInk) Color.Black else Color(0xFF38BDF8),
                                            fontSize = 11.sp,
                                            fontFamily = FontFamily.Monospace
                                        )
                                        Spacer(modifier = Modifier.width(8.dp))
                                    }
                                    Text(
                                        text = "›",
                                        color = if (isEInk) Color.Black else Color(0xFF64748B),
                                        fontSize = 16.sp,
                                        fontWeight = FontWeight.Bold
                                    )
                                }
                            }
                        }
                    }
                }

                item(key = "bottom-empty-space") {
                    var showBottomMenu by remember(snapshot.nodeId) { mutableStateOf(false) }
                    var bottomMenuOffset by remember(snapshot.nodeId) { mutableStateOf(DpOffset.Zero) }
                    val lastLine = snapshot.blocks.lastOrNull()?.endLine ?: 0

                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(180.dp)
                            .pointerInput(snapshot.nodeId, lastLine) {
                                detectTapGestures(
                                    onLongPress = { offset ->
                                        bottomMenuOffset = DpOffset(with(density) { offset.x.toDp() }, with(density) { offset.y.toDp() })
                                        showBottomMenu = true
                                    }
                                )
                            }
                    ) {
                        SketchInsertionPopupMenu(
                            expanded = showBottomMenu,
                            onDismissRequest = { showBottomMenu = false },
                            offset = bottomMenuOffset,
                            belowLabel = "＋ Add Sketch Here",
                            onInsertBelow = {
                                syncClient.insertInkBlock(snapshot.nodeId, afterLine = lastLine)
                            }
                        )
                    }
                }
            }
        }
        if (inlineToolbarAtBottom) inlineToolbarSection()
    }

    if (showPageSetupDialog && activeCanvasView != null) {
        WritePageSetupModal(
            currentSetup = pageSetup,
            onDismiss = {
                showPageSetupDialog = false
                wakeUpToolbar()
            },
            onApplyFull = { w, h, xRule, yRule, marginL, paperBg, ruleFg, style ->
                activeCanvasView.applyPageSetup(
                    newWidth = w,
                    newHeight = h,
                    newXRuling = xRule,
                    newYRuling = yRule,
                    newMarginLeft = marginL,
                    newPaperColorArgb = paperBg,
                    newRuleColorArgb = ruleFg,
                    newRulingStyle = style.nativeCode
                )
                showPageSetupDialog = false
                wakeUpToolbar()
            },
            onLockContentAsTemplate = {
                activeCanvasView.lockCurrentDrawingAsTemplate()
                pageSetup = activeCanvasView.queryPageSetupState()
            },
            onUnlockTemplateToContent = {
                activeCanvasView.unlockTemplateBackgroundToEdit()
                pageSetup = activeCanvasView.queryPageSetupState()
            },
            onClearCustomTemplate = {
                activeCanvasView.clearCustomTemplateBackground()
                pageSetup = activeCanvasView.queryPageSetupState()
            },
            onInsertImage = { bytes, asBackground ->
                activeCanvasView.insertImageBytes(bytes, asBackground = asBackground)
                pageSetup = activeCanvasView.queryPageSetupState()
            },
            onSetTemplateOpacity = { op ->
                activeCanvasView.setTemplateImageOpacity(op)
            }
        )
    }

    if (showOcrDialog) {
        AlertDialog(
            onDismissRequest = { showOcrDialog = false },
            title = {
                Text(
                    text = "Handwriting OCR — Convert Selection to Org Text",
                    color = if (isEInk) Color.Black else Color(0xFFF1F5F9),
                    fontWeight = FontWeight.Bold,
                    fontSize = 16.sp
                )
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        text = "Review or edit the recognized text before inserting into your Org node:",
                        color = if (isEInk) Color.Black else Color(0xFF94A3B8),
                        fontSize = 12.sp
                    )
                    OutlinedTextField(
                        value = ocrDraftText,
                        onValueChange = { ocrDraftText = it },
                        label = { Text("Recognized Text") },
                        minLines = 3,
                        maxLines = 6,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            },
            confirmButton = {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(
                        onClick = {
                            if (ocrDraftText.isNotBlank()) {
                                syncClient.insertOcrText(
                                    nodeId = snapshot.nodeId,
                                    text = ocrDraftText.trim(),
                                    mode = "checklist",
                                    fileName = activeInkBlock?.fileName
                                )
                            }
                            showOcrDialog = false
                        }
                    ) {
                        Text("Insert as Checklist", fontSize = 12.sp)
                    }
                    Button(
                        onClick = {
                            if (ocrDraftText.isNotBlank()) {
                                syncClient.insertOcrText(
                                    nodeId = snapshot.nodeId,
                                    text = ocrDraftText.trim(),
                                    mode = "paragraph",
                                    fileName = activeInkBlock?.fileName
                                )
                            }
                            showOcrDialog = false
                        }
                    ) {
                        Text("Insert as Paragraph", fontSize = 12.sp)
                    }
                }
            },
            dismissButton = {
                TextButton(onClick = { showOcrDialog = false }) {
                    Text("Cancel", color = if (isEInk) Color.Black else Color(0xFF94A3B8))
                }
            },
            containerColor = if (isEInk) Color.White else Color(0xFF1E293B),
            shape = RoundedCornerShape(12.dp)
        )
    }

    val activePlanMode = showPlanningDialog
    if (activePlanMode != null) {
        var inputValue by remember(activePlanMode, snapshot.nodeId) {
            mutableStateOf(
                when (activePlanMode) {
                    "scheduled" -> snapshot.header.scheduled ?: ""
                    "deadline" -> snapshot.header.deadline ?: ""
                    else -> snapshot.header.tags.joinToString(", ")
                }
            )
        }
        AlertDialog(
            onDismissRequest = { showPlanningDialog = null },
            title = {
                Text(
                    text = when (activePlanMode) {
                        "scheduled" -> "Set SCHEDULED Date"
                        "deadline" -> "Set DEADLINE Date"
                        else -> "Edit Org Tags"
                    },
                    color = if (isEInk) Color.Black else Color.White
                )
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedTextField(
                        value = inputValue,
                        onValueChange = { inputValue = it },
                        label = {
                            Text(
                                if (activePlanMode == "tags") "Comma-separated tags (e.g. work, notes)"
                                else "Org Date (e.g. 2026-11-03 or +2d)"
                            )
                        },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    if (activePlanMode != "tags") {
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            listOf("today" to "+0d", "+1d" to "+1d", "+3d" to "+3d", "+1w" to "+1w").forEach { (lbl, expr) ->
                                FilterChip(
                                    selected = inputValue == expr,
                                    onClick = { inputValue = expr },
                                    label = { Text(lbl, fontSize = 11.sp) }
                                )
                            }
                        }
                    }
                }
            },
            confirmButton = {
                Button(onClick = {
                    when (activePlanMode) {
                        "scheduled" -> syncClient.updateHeader(snapshot.nodeId, scheduled = inputValue.trim())
                        "deadline" -> syncClient.updateHeader(snapshot.nodeId, deadline = inputValue.trim())
                        else -> {
                            val parsedTags = inputValue.split(",", ":", " ")
                                .map { it.trim() }
                                .filter { it.isNotEmpty() }
                            syncClient.updateHeader(snapshot.nodeId, tags = parsedTags)
                        }
                    }
                    showPlanningDialog = null
                }) {
                    Text("Save")
                }
            },
            dismissButton = {
                Row {
                    if (activePlanMode != "tags") {
                        TextButton(onClick = {
                            if (activePlanMode == "scheduled") {
                                syncClient.updateHeader(snapshot.nodeId, scheduled = "")
                            } else {
                                syncClient.updateHeader(snapshot.nodeId, deadline = "")
                            }
                            showPlanningDialog = null
                        }) {
                            Text("Clear", color = if (isEInk) Color.Black else Color(0xFFF87171))
                        }
                    }
                    TextButton(onClick = { showPlanningDialog = null }) {
                        Text("Cancel")
                    }
                }
            },
            containerColor = if (isEInk) Color.White else Color(0xFF1E293B)
        )
    }
}

@Composable
internal fun SketchInsertionPopupMenu(
    expanded: Boolean,
    onDismissRequest: () -> Unit,
    offset: DpOffset = DpOffset.Zero,
    onInsertBelow: () -> Unit,
    onInsertAbove: (() -> Unit)? = null,
    onDelete: (() -> Unit)? = null,
    belowLabel: String = "＋ Add Sketch Here",
    aboveLabel: String = "＋ Add Sketch Above"
) {
    val isEInk = OnyxEInkBridge.isEInkDevice
    DropdownMenu(
        expanded = expanded,
        onDismissRequest = onDismissRequest,
        offset = offset,
        modifier = Modifier
            .background(if (isEInk) Color.White else Color(0xFF0F172A), RoundedCornerShape(8.dp))
            .border(1.dp, if (isEInk) Color.Black else Color(0xFF38BDF8).copy(alpha = 0.6f), RoundedCornerShape(8.dp))
    ) {
        DropdownMenuItem(
            text = {
                Text(
                    text = belowLabel,
                    color = if (isEInk) Color.Black else Color(0xFF38BDF8),
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold
                )
            },
            onClick = {
                onDismissRequest()
                onInsertBelow()
            }
        )
        if (onInsertAbove != null) {
            DropdownMenuItem(
                text = {
                    Text(
                        text = aboveLabel,
                        color = if (isEInk) Color.Black else Color(0xFFCBD5E1),
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Medium
                    )
                },
                onClick = {
                    onDismissRequest()
                    onInsertAbove()
                }
            )
        }
        if (onDelete != null) {
            HorizontalDivider(color = if (isEInk) Color.Black else Color(0xFF334155))
            DropdownMenuItem(
                text = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        WriteSvgIcon(asset = WriteIconAsset.DELETE, tint = if (isEInk) Color.Black else Color(0xFFEF4444), size = 14.dp)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "Delete Drawing",
                            color = if (isEInk) Color.Black else Color(0xFFEF4444),
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Medium
                        )
                    }
                },
                onClick = {
                    onDismissRequest()
                    onDelete()
                }
            )
        }
    }
}

@Composable
internal fun PropertiesDrawerPanel(
    nodeId: String,
    properties: Map<String, String>,
    scheduled: String?,
    deadline: String?,
    onSaveProperty: (String, String) -> Unit
) {
    val isEInk = OnyxEInkBridge.isEInkDevice
    var propKey by remember(nodeId) { mutableStateOf("") }
    var propVal by remember(nodeId) { mutableStateOf("") }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(if (isEInk) Color(0xFFF1F5F9) else Color(0xFF0F172A))
            .then(if (isEInk) Modifier.border(1.dp, Color.Black, RoundedCornerShape(8.dp)) else Modifier)
            .padding(12.dp)
    ) {
        if (scheduled != null || deadline != null) {
            Text(
                text = listOfNotNull(
                    scheduled?.let { "SCHEDULED: $it" },
                    deadline?.let { "DEADLINE: $it" }
                ).joinToString("   "),
                color = if (isEInk) Color.Black else Color(0xFF38BDF8),
                fontFamily = FontFamily.Monospace,
                fontSize = 12.sp
            )
            Spacer(modifier = Modifier.height(6.dp))
        }

        properties.forEach { (k, v) ->
            Text(
                text = ":$k:  $v",
                color = if (isEInk) Color.Black else Color(0xFFCBD5E1),
                fontFamily = FontFamily.Monospace,
                fontSize = 12.sp
            )
        }

        Spacer(modifier = Modifier.height(8.dp))

        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = propKey,
                onValueChange = { propKey = it },
                placeholder = { Text("Property Key", fontSize = 12.sp) },
                singleLine = true,
                modifier = Modifier.width(160.dp)
            )
            Spacer(modifier = Modifier.width(8.dp))
            OutlinedTextField(
                value = propVal,
                onValueChange = { propVal = it },
                placeholder = { Text("Value", fontSize = 12.sp) },
                singleLine = true,
                modifier = Modifier.weight(1f)
            )
            Spacer(modifier = Modifier.width(8.dp))
            TextButton(onClick = {
                if (propKey.isNotBlank()) {
                    onSaveProperty(propKey.trim(), propVal.trim())
                    propKey = ""
                    propVal = ""
                }
            }) {
                Text("Set")
            }
        }
    }
}
