package com.orgwritenotes.ui

import android.content.res.Configuration
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.ui.platform.LocalContext
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
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
import com.orgwritenotes.ink.StylusToolMode
import com.orgwritenotes.ink.WriteCanvasBlockView
import com.orgwritenotes.ink.WritePageSetupState
import com.orgwritenotes.ink.WritePenTip
import com.orgwritenotes.ink.WriteSelectionState
import com.orgwritenotes.model.*
import com.orgwritenotes.sync.ConnectionState
import com.orgwritenotes.sync.EmacsSyncClient
import kotlinx.coroutines.delay

/**
 * Main Landscape Tablet UI for Org-WriteNotes (Phase 2 & Phase 3 Complete):
 * - Collapsible Left Sidebar: Connection status pill (ADB USB / Network / Offline Queue count),
 *   search bar, and list of Org-mode headings with TODO/NEXT badges.
 * - Right Hybrid Editor Pane:
 *   - Interactive Org Header card: tap TODO badge to cycle state, tap `[#A]` chip to cycle
 *     priority, tap title to edit inline, and expand `:PROPERTIES:` drawer to view/add properties.
 *   - Interleaved Checklist blocks, editable Paragraph blocks, and expanding Stylus Labs Write
 *     `.svgz` Ink Canvas blocks with a full S-Pen toolbar (Pen, Highlighter, Eraser, Color presets,
 *     Stroke widths, Undo/Redo, and bottom-edge manual resize drag bar).
 */
@Composable
fun OrgWriteNotesTabletScreen(
    syncClient: EmacsSyncClient
) {
    val configuration = LocalConfiguration.current
    val isPortrait = configuration.orientation == Configuration.ORIENTATION_PORTRAIT

    val connectionState by syncClient.connectionState.collectAsState()
    val summaries by syncClient.nodeSummaries.collectAsState()
    val activeSnapshot by syncClient.activeSnapshot.collectAsState()
    val pendingOfflineCount by syncClient.pendingOfflineCount.collectAsState()

    var showPairingDialog by remember { mutableStateOf(false) }
    var zoomedInkBlockFileName by remember { mutableStateOf<String?>(null) }
    var zoomedSourceView by remember { mutableStateOf<WriteCanvasBlockView?>(null) }
    var searchQuery by remember { mutableStateOf("") }
    val filteredSummaries = remember(summaries, searchQuery) {
        val q = searchQuery.trim()
        if (q.isEmpty()) {
            summaries
        } else {
            summaries.filter {
                it.title.contains(q, ignoreCase = true) ||
                    (it.todoKeyword?.contains(q, ignoreCase = true) == true) ||
                    it.tags.any { tag -> tag.contains(q, ignoreCase = true) }
            }
        }
    }

    // Auto-hide vs Pinned (Docked) state:
    // In portrait / vertical mode: defaults to auto-hide (isPinnedPortrait = false)
    // In landscape mode: defaults to docked (isPinnedLandscape = true)
    var isPinnedLandscape by rememberSaveable { mutableStateOf(true) }
    var isPinnedPortrait by rememberSaveable { mutableStateOf(false) }
    val isPinned = if (isPortrait) isPinnedPortrait else isPinnedLandscape
    var isDrawerOpen by rememberSaveable { mutableStateOf(false) }

    val onTogglePin = {
        if (isPortrait) {
            isPinnedPortrait = !isPinnedPortrait
        } else {
            isPinnedLandscape = !isPinnedLandscape
        }
    }

    val zoomedBlock = remember(activeSnapshot, zoomedInkBlockFileName) {
        val targetFile = zoomedInkBlockFileName ?: return@remember null
        activeSnapshot?.blocks?.filterIsInstance<OrgBlock.InkCanvasBlock>()?.find {
            it.fileName == targetFile
        }
    }

    val contentPadding = if (isPortrait) 12.dp else 20.dp
    val sidebarWidth = if (isPortrait) 290.dp else 310.dp

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF0F172A))
    ) {
        Row(modifier = Modifier.fillMaxSize()) {
            // 1. Docked Sidebar (when isPinned is true)
            if (isPinned) {
                Box(
                    modifier = Modifier
                        .width(sidebarWidth)
                        .fillMaxHeight()
                ) {
                    RecentNodesSidebarContent(
                        connectionState = connectionState,
                        pendingOfflineCount = pendingOfflineCount,
                        onOpenPairing = { showPairingDialog = true },
                        searchQuery = searchQuery,
                        onSearchQueryChange = { searchQuery = it },
                        summaries = summaries,
                        filteredSummaries = filteredSummaries,
                        activeNodeId = activeSnapshot?.nodeId,
                        onSelectNode = { nodeId -> syncClient.fetchNode(nodeId) },
                        onClearHistory = { syncClient.clearHistory() },
                        isPinned = true,
                        onTogglePin = onTogglePin,
                        onClose = null
                    )
                }
            }

            // 2. Main Editor Pane (takes full remaining width)
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .padding(contentPadding)
            ) {
                val snapshot = activeSnapshot
                if (snapshot == null) {
                    EmptyEditorPlaceholder(
                        isPinned = isPinned,
                        connectionState = connectionState,
                        onOpenRecentNodes = { isDrawerOpen = true },
                        onConnectAdb = { syncClient.connectAdbUsb() }
                    )
                } else {
                    HybridNodeEditorPane(
                        snapshot = snapshot,
                        syncClient = syncClient,
                        isPinned = isPinned,
                        connectionState = connectionState,
                        onOpenRecentNodes = { isDrawerOpen = true },
                        onZoomInkBlock = { fileName, sourceView ->
                            syncClient.flushPendingInkCommits()
                            zoomedSourceView = sourceView
                            zoomedInkBlockFileName = fileName
                        }
                    )
                }
            }
        }

        // 3. Auto-hide Overlay Drawer (active when !isPinned)
        if (!isPinned) {
            AnimatedVisibility(
                visible = isDrawerOpen,
                enter = fadeIn(),
                exit = fadeOut()
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Color.Black.copy(alpha = 0.55f))
                        .clickable { isDrawerOpen = false }
                )
            }

            AnimatedVisibility(
                visible = isDrawerOpen,
                enter = slideInHorizontally(initialOffsetX = { -it }) + fadeIn(),
                exit = slideOutHorizontally(targetOffsetX = { -it }) + fadeOut(),
                modifier = Modifier.align(Alignment.CenterStart)
            ) {
                Box(
                    modifier = Modifier
                        .width(320.dp)
                        .fillMaxHeight()
                        .background(Color(0xFF1E293B))
                        .border(
                            width = 1.dp,
                            color = Color(0xFF334155),
                            shape = RoundedCornerShape(topEnd = 16.dp, bottomEnd = 16.dp)
                        )
                        .clip(RoundedCornerShape(topEnd = 16.dp, bottomEnd = 16.dp))
                        .pointerInput(Unit) {
                            detectTapGestures { /* block background touch through */ }
                        }
                ) {
                    RecentNodesSidebarContent(
                        connectionState = connectionState,
                        pendingOfflineCount = pendingOfflineCount,
                        onOpenPairing = {
                            isDrawerOpen = false
                            showPairingDialog = true
                        },
                        searchQuery = searchQuery,
                        onSearchQueryChange = { searchQuery = it },
                        summaries = summaries,
                        filteredSummaries = filteredSummaries,
                        activeNodeId = activeSnapshot?.nodeId,
                        onSelectNode = { nodeId ->
                            syncClient.fetchNode(nodeId)
                            isDrawerOpen = false // Auto-hide upon selection!
                        },
                        onClearHistory = { syncClient.clearHistory() },
                        isPinned = false,
                        onTogglePin = {
                            onTogglePin()
                            isDrawerOpen = false
                        },
                        onClose = { isDrawerOpen = false }
                    )
                }
            }
        }

        // 4. Full-Screen Zoomed Editor Overlay (rendered on top so inline views stay alive underneath)
        if (activeSnapshot != null && zoomedBlock != null) {
            WriteZoomedEditorOverlay(
                nodeId = activeSnapshot!!.nodeId,
                block = zoomedBlock,
                syncClient = syncClient,
                sourceInlineView = zoomedSourceView,
                onCloseZoom = { _, _ ->
                    syncClient.flushPendingInkCommits()
                    zoomedSourceView = null
                    zoomedInkBlockFileName = null
                }
            )
        }
    }

    if (showPairingDialog) {
        PairingModalDialog(
            onDismiss = { showPairingDialog = false },
            onConnectAdb = { port ->
                syncClient.connectAdbUsb(port)
                showPairingDialog = false
            },
            onConnectNetwork = { urlOrQr, token ->
                syncClient.connectNetwork(urlOrQr, token)
                showPairingDialog = false
            }
        )
    }
}

fun connectionDotColor(state: ConnectionState): Color = when (state) {
    ConnectionState.CONNECTED_ADB -> Color(0xFF22C55E)
    ConnectionState.CONNECTED_NETWORK -> Color(0xFF38BDF8)
    ConnectionState.OFFLINE_CACHED -> Color(0xFFF59E0B)
    ConnectionState.CONNECTING -> Color(0xFFFACC15)
    ConnectionState.ERROR -> Color(0xFFEF4444)
    ConnectionState.DISCONNECTED -> Color(0xFF94A3B8)
}

@Composable
private fun RecentNodesSidebarContent(
    connectionState: ConnectionState,
    pendingOfflineCount: Int,
    onOpenPairing: () -> Unit,
    searchQuery: String,
    onSearchQueryChange: (String) -> Unit,
    summaries: List<OrgNodeSummary>,
    filteredSummaries: List<OrgNodeSummary>,
    activeNodeId: String?,
    onSelectNode: (String) -> Unit,
    onClearHistory: () -> Unit,
    isPinned: Boolean,
    onTogglePin: () -> Unit,
    onClose: (() -> Unit)? = null
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF1E293B))
            .padding(16.dp)
    ) {
        // Top row: Connection status pill + Pin toggle / Close button
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth()
        ) {
            Box(modifier = Modifier.weight(1f)) {
                ConnectionStatusPill(
                    state = connectionState,
                    pendingOfflineCount = pendingOfflineCount,
                    onClick = onOpenPairing
                )
            }

            Spacer(modifier = Modifier.width(8.dp))

            // Pin / Auto-hide toggle button
            IconButton(
                onClick = onTogglePin,
                modifier = Modifier
                    .size(36.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(if (isPinned) Color(0xFF0284C7) else Color(0xFF334155))
            ) {
                WriteSvgIcon(
                    asset = WriteIconAsset.PIN,
                    tint = if (isPinned) Color.White else Color(0xFF94A3B8),
                    size = 18.dp
                )
            }

            if (onClose != null) {
                Spacer(modifier = Modifier.width(4.dp))
                IconButton(
                    onClick = onClose,
                    modifier = Modifier
                        .size(36.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(Color(0xFF334155))
                ) {
                    WriteSvgIcon(
                        asset = WriteIconAsset.CLOSE,
                        tint = Color(0xFF94A3B8),
                        size = 16.dp
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(12.dp))

        OutlinedTextField(
            value = searchQuery,
            onValueChange = onSearchQueryChange,
            placeholder = { Text("Filter pushed history...", color = Color(0xFF94A3B8)) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )

        Spacer(modifier = Modifier.height(12.dp))

        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(
                text = "PUSHED HISTORY (${filteredSummaries.size})",
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
                color = Color(0xFF94A3B8)
            )

            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = if (isPinned) "Docked" else "Auto-hide",
                    fontSize = 10.sp,
                    color = if (isPinned) Color(0xFF38BDF8) else Color(0xFFF59E0B),
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier
                        .clip(RoundedCornerShape(4.dp))
                        .background(Color(0xFF0F172A))
                        .clickable { onTogglePin() }
                        .padding(horizontal = 6.dp, vertical = 3.dp)
                )

                if (summaries.size > 1) {
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "Clear",
                        fontSize = 11.sp,
                        color = Color(0xFF38BDF8),
                        modifier = Modifier
                            .clip(RoundedCornerShape(4.dp))
                            .clickable { onClearHistory() }
                            .padding(horizontal = 4.dp, vertical = 2.dp)
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        if (filteredSummaries.isEmpty()) {
            Text(
                text = "No nodes pushed yet.\nRun M-x org-writenotes-push-node in Emacs.",
                color = Color(0xFF64748B),
                fontSize = 12.sp,
                lineHeight = 18.sp,
                modifier = Modifier.padding(top = 8.dp)
            )
        } else {
            LazyColumn(
                verticalArrangement = Arrangement.spacedBy(6.dp),
                modifier = Modifier.weight(1f)
            ) {
                items(filteredSummaries, key = { it.nodeId }) { item ->
                    val isSelected = activeNodeId == item.nodeId
                    SidebarNodeRow(
                        summary = item,
                        isSelected = isSelected,
                        onClick = { onSelectNode(item.nodeId) }
                    )
                }
            }
        }
    }
}

@Composable
private fun ConnectionStatusPill(
    state: ConnectionState,
    pendingOfflineCount: Int,
    onClick: () -> Unit
) {
    val queueSuffix = if (pendingOfflineCount > 0) " • $pendingOfflineCount queued" else ""
    val label = when (state) {
        ConnectionState.CONNECTED_ADB -> "Connected • ADB USB$queueSuffix"
        ConnectionState.CONNECTED_NETWORK -> "Connected • LAN / Tailscale$queueSuffix"
        ConnectionState.OFFLINE_CACHED -> "Offline Mode (Cached)$queueSuffix"
        ConnectionState.CONNECTING -> "Connecting to Emacs..."
        ConnectionState.ERROR -> "Connection Error (Tap to pair)$queueSuffix"
        ConnectionState.DISCONNECTED -> "Disconnected (Tap to pair)$queueSuffix"
    }
    val dotColor = connectionDotColor(state)

    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(Color(0xFF0F172A))
            .clickable { onClick() }
            .padding(horizontal = 12.dp, vertical = 10.dp)
    ) {
        Box(
            modifier = Modifier
                .size(10.dp)
                .clip(RoundedCornerShape(5.dp))
                .background(dotColor)
        )
        Spacer(modifier = Modifier.width(10.dp))
        Text(
            text = label,
            color = Color(0xFFE2E8F0),
            fontSize = 12.sp,
            fontWeight = FontWeight.Medium
        )
    }
}

@Composable
private fun SidebarNodeRow(
    summary: OrgNodeSummary,
    isSelected: Boolean,
    onClick: () -> Unit
) {
    val bgColor = if (isSelected) Color(0xFF334155) else Color.Transparent
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(bgColor)
            .clickable { onClick() }
            .padding(horizontal = 10.dp, vertical = 8.dp)
    ) {
        summary.todoKeyword?.let { kw ->
            TodoBadge(keyword = kw)
            Spacer(modifier = Modifier.width(8.dp))
        }
        Text(
            text = summary.title,
            color = Color(0xFFF8FAFC),
            fontSize = 14.sp,
            maxLines = 1,
            modifier = Modifier.weight(1f)
        )
    }
}

@Composable
private fun HybridNodeEditorPane(
    snapshot: OrgNodeSnapshot,
    syncClient: EmacsSyncClient,
    isPinned: Boolean = true,
    connectionState: ConnectionState = ConnectionState.DISCONNECTED,
    onOpenRecentNodes: () -> Unit = {},
    onZoomInkBlock: (String, WriteCanvasBlockView?) -> Unit
) {
    var showPropertiesDrawer by remember { mutableStateOf(false) }
    var isEditingTitle by remember { mutableStateOf(false) }
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

    // Shared Stylus Labs Write Tool State across inline drawings
    var activeMode by remember(snapshot.nodeId) { mutableStateOf(StylusToolMode.PEN) }
    var lastEraserMode by remember(snapshot.nodeId) { mutableStateOf(StylusToolMode.ERASER) }
    var lastSelectMode by remember(snapshot.nodeId) { mutableStateOf(StylusToolMode.SELECT_LASSO) }
    var lastInsertSpaceMode by remember(snapshot.nodeId) { mutableStateOf(StylusToolMode.INSERT_SPACE_RULED) }
    var showPenToolbar by remember(snapshot.nodeId) { mutableStateOf(true) }
    var selectedColor by remember(snapshot.nodeId) { mutableStateOf(0xFF1E293B.toInt()) }
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
                    .background(Color(0xFF1E293B))
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
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth()
            ) {
                if (!isPinned) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .clip(RoundedCornerShape(8.dp))
                            .background(Color(0xFF334155))
                            .clickable { onOpenRecentNodes() }
                            .padding(horizontal = 9.dp, vertical = 6.dp)
                    ) {
                        WriteSvgIcon(asset = WriteIconAsset.MENU, tint = Color(0xFF38BDF8), size = 15.dp)
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = "Nodes",
                            color = Color(0xFFE2E8F0),
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Medium
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Box(
                            modifier = Modifier
                                .size(7.dp)
                                .clip(CircleShape)
                                .background(connectionDotColor(connectionState))
                        )
                    }
                    Spacer(modifier = Modifier.width(10.dp))
                }

                Box(modifier = Modifier.clickable { syncClient.cycleTodoState(snapshot) }) {
                    TodoBadge(keyword = snapshot.header.todoKeyword ?: "TODO")
                }
                Spacer(modifier = Modifier.width(10.dp))

                Text(
                    text = "[#${snapshot.header.priority ?: "B"}]",
                    color = Color(0xFFF59E0B),
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
                        color = Color(0xFFF8FAFC),
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
                        color = Color(0xFF38BDF8),
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(horizontal = 8.dp)
                    )
                }

                TextButton(onClick = { showPropertiesDrawer = !showPropertiesDrawer }) {
                    Text(
                        text = if (showPropertiesDrawer) "▾ :PROPERTIES:" else "▸ :PROPERTIES:",
                        color = Color(0xFF94A3B8),
                        fontFamily = FontFamily.Monospace,
                        fontSize = 12.sp
                    )
                }
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
        if (activeInkBlock != null) {
            Spacer(modifier = Modifier.height(8.dp))

            AnimatedVisibility(
                visible = isToolbarVisible,
                enter = expandVertically() + fadeIn(),
                exit = shrinkVertically() + fadeOut()
            ) {
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
                        onSelectToolMode = { newMode, newColor, newWidth, newTip, eraseDef, selectDef, insDef ->
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
                        },
                        onTogglePenToolbar = { showPenToolbar = !showPenToolbar },
                        onSelectAllStrokes = { activeCanvasView?.selectAllStrokes() },
                        onApplyStyleToSelection = { activeCanvasView?.applyCurrentStyleToSelection() },
                        onUndo = { activeCanvasView?.performUndo() },
                        onRedo = { activeCanvasView?.performRedo() },
                        onExpandDown = { activeCanvasView?.expandDown() },
                        onExpandRight = { activeCanvasView?.expandRight() },
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

            // Sleek collapsed pill when the Write toolbar is auto-hidden
            AnimatedVisibility(
                visible = !isToolbarVisible,
                enter = fadeIn(),
                exit = fadeOut()
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(8.dp))
                        .background(Color(0xFF1E293B))
                        .border(1.dp, Color(0xFF334155), RoundedCornerShape(8.dp))
                        .clickable { wakeUpToolbar() }
                        .padding(horizontal = 12.dp, vertical = 6.dp)
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            modifier = Modifier
                                .size(10.dp)
                                .clip(CircleShape)
                                .background(Color(selectedColor))
                                .border(1.dp, Color.White, CircleShape)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "✎ Write Tools — Touch drawing with S-Pen or tap to show toolbar",
                            color = Color(0xFF94A3B8),
                            fontSize = 12.sp
                        )
                    }

                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .clip(RoundedCornerShape(6.dp))
                            .background(Color(0xFF0F172A))
                            .clickable { onZoomInkBlock(activeInkBlock.fileName, activeCanvasView) }
                            .padding(horizontal = 8.dp, vertical = 3.dp)
                    ) {
                        WriteSvgIcon(asset = WriteIconAsset.FULLSCREEN, tint = Color(0xFF38BDF8), size = 13.dp)
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(
                            text = "Zoom",
                            color = Color(0xFF38BDF8),
                            fontSize = 11.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        // Interleaved Blocks (Checklists, Editable Paragraphs, and Clean Stylus Labs Write Sheets)
        LazyColumn(
            state = lazyListState,
            verticalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.weight(1f)
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
                            .border(1.dp, Color(0xFF334155), RoundedCornerShape(12.dp))
                            .background(Color(0xFF0F172A).copy(alpha = 0.5f))
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
                                color = Color(0xFF64748B),
                                fontSize = 14.sp
                            )
                            Spacer(modifier = Modifier.height(6.dp))
                            Text(
                                text = "Press and hold anywhere to add a sketch",
                                color = Color(0xFF475569),
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
                                    canvasViews[block.blockId] = view
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
            }
        )
    }
}

@Composable
private fun SketchInsertionPopupMenu(
    expanded: Boolean,
    onDismissRequest: () -> Unit,
    offset: DpOffset = DpOffset.Zero,
    onInsertBelow: () -> Unit,
    onInsertAbove: (() -> Unit)? = null,
    onDelete: (() -> Unit)? = null,
    belowLabel: String = "＋ Add Sketch Here",
    aboveLabel: String = "＋ Add Sketch Above"
) {
    DropdownMenu(
        expanded = expanded,
        onDismissRequest = onDismissRequest,
        offset = offset,
        modifier = Modifier
            .background(Color(0xFF0F172A), RoundedCornerShape(8.dp))
            .border(1.dp, Color(0xFF38BDF8).copy(alpha = 0.6f), RoundedCornerShape(8.dp))
    ) {
        DropdownMenuItem(
            text = {
                Text(
                    text = belowLabel,
                    color = Color(0xFF38BDF8),
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
                        color = Color(0xFFCBD5E1),
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
            HorizontalDivider(color = Color(0xFF334155))
            DropdownMenuItem(
                text = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        WriteSvgIcon(asset = WriteIconAsset.DELETE, tint = Color(0xFFEF4444), size = 14.dp)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "Delete Drawing",
                            color = Color(0xFFEF4444),
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
private fun PropertiesDrawerPanel(
    nodeId: String,
    properties: Map<String, String>,
    scheduled: String?,
    deadline: String?,
    onSaveProperty: (String, String) -> Unit
) {
    var propKey by remember(nodeId) { mutableStateOf("") }
    var propVal by remember(nodeId) { mutableStateOf("") }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(Color(0xFF0F172A))
            .padding(12.dp)
    ) {
        if (scheduled != null || deadline != null) {
            Text(
                text = listOfNotNull(
                    scheduled?.let { "SCHEDULED: $it" },
                    deadline?.let { "DEADLINE: $it" }
                ).joinToString("   "),
                color = Color(0xFF38BDF8),
                fontFamily = FontFamily.Monospace,
                fontSize = 12.sp
            )
            Spacer(modifier = Modifier.height(6.dp))
        }

        properties.forEach { (k, v) ->
            Text(
                text = ":$k:  $v",
                color = Color(0xFFCBD5E1),
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

@Composable
private fun ChecklistBlockCard(
    block: OrgBlock.ChecklistBlock,
    onToggle: (ChecklistItem, Boolean) -> Unit,
    onInsertSketchAfterItem: (ChecklistItem) -> Unit,
    onInsertSketchAfterBlock: () -> Unit,
    onInsertSketchBeforeBlock: () -> Unit,
    onAddItem: (String) -> Unit
) {
    var newItemText by remember(block.blockId) { mutableStateOf("") }
    var showCardMenu by remember(block.blockId) { mutableStateOf(false) }
    var cardMenuOffset by remember(block.blockId) { mutableStateOf(DpOffset.Zero) }
    val density = LocalDensity.current

    Box {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(10.dp))
                .background(Color(0xFF1E293B))
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
                var showItemMenu by remember(item.line) { mutableStateOf(false) }
                var itemMenuOffset by remember(item.line) { mutableStateOf(DpOffset.Zero) }

                Box(modifier = Modifier.fillMaxWidth()) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(6.dp))
                            .pointerInput(item.line, checked) {
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
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = item.text,
                            color = if (checked) Color(0xFF94A3B8) else Color(0xFFF1F5F9),
                            textDecoration = if (checked) TextDecoration.LineThrough else TextDecoration.None,
                            fontSize = 15.sp,
                            modifier = Modifier.weight(1f)
                        )
                    }

                    SketchInsertionPopupMenu(
                        expanded = showItemMenu,
                        onDismissRequest = { showItemMenu = false },
                        offset = itemMenuOffset,
                        belowLabel = "＋ Add Sketch Here",
                        onInsertBelow = { onInsertSketchAfterItem(item) }
                    )
                }
            }

            Spacer(modifier = Modifier.height(6.dp))

            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = newItemText,
                    onValueChange = { newItemText = it },
                    placeholder = { Text("Add checklist item (- [ ] ...)", color = Color(0xFF64748B)) },
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
}

/**
 * Clean Inline Paper Card for `.svgz` drawings — no per-card toolbar clutter.
 * Touching or hovering with the S-Pen activates this drawing and wakes up the shared
 * Stylus Labs Write auto-hiding toolbar at the top of the editor.
 */
@Composable
private fun InkCanvasBlockCard(
    nodeId: String,
    block: OrgBlock.InkCanvasBlock,
    isActiveDrawing: Boolean,
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
    var canvasHeightPx by remember(block.blockId) { mutableStateOf(block.height) }
    var dragResizeDeltaPx by remember(block.blockId) { mutableStateOf(0) }
    var canvasViewRef by remember(block.blockId) { mutableStateOf<WriteCanvasBlockView?>(null) }
    var lastLoadedSvgzBase64 by remember(block.blockId) { mutableStateOf(block.svgzBase64) }
    var localSelection by remember(block.blockId) { mutableStateOf(WriteSelectionState()) }
    var showMenu by remember(block.blockId) { mutableStateOf(false) }
    var menuOffset by remember(block.blockId) { mutableStateOf(DpOffset.Zero) }
    var showDeleteConfirmDialog by remember(block.blockId) { mutableStateOf(false) }

    val density = LocalDensity.current
    val heightDp = with(density) { canvasHeightPx.toDp() }

    val borderColor = if (isActiveDrawing) Color(0xFF38BDF8) else Color(0xFF334155)
    val borderWidth = if (isActiveDrawing) 1.5.dp else 1.dp

    Box {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(10.dp))
                .border(borderWidth, borderColor, RoundedCornerShape(10.dp))
                .background(Color(0xFF0F172A))
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
                        }
                    ).apply {
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
                    .background(if (isActiveDrawing) Color(0xCC0F172A) else Color(0x991E293B))
                    .border(
                        1.dp,
                        if (isActiveDrawing) Color(0xFF38BDF8) else Color(0xFF475569),
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
                        color = Color(0xFF38BDF8),
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
                            .background(Color(0xFF475569))
                    )
                }
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .clip(RoundedCornerShape(4.dp))
                        .clickable { onZoomIntoDrawing() }
                        .padding(horizontal = 2.dp)
                ) {
                    WriteSvgIcon(asset = WriteIconAsset.FULLSCREEN, tint = Color.White, size = 12.dp)
                    Spacer(modifier = Modifier.width(3.dp))
                    Text(
                        text = "Zoom",
                        color = Color.White,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                }
                Box(
                    modifier = Modifier
                        .width(1.dp)
                        .height(12.dp)
                        .background(Color(0xFF475569))
                )
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .clip(RoundedCornerShape(4.dp))
                        .clickable { showDeleteConfirmDialog = true }
                        .padding(horizontal = 2.dp)
                ) {
                    WriteSvgIcon(asset = WriteIconAsset.DELETE, tint = Color(0xFFF87171), size = 12.dp)
                    Spacer(modifier = Modifier.width(3.dp))
                    Text(
                        text = "Delete",
                        color = Color(0xFFF87171),
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
                .background(Color(0xFF0F172A))
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
                    color = Color(0xFF38BDF8),
                    fontSize = 10.sp,
                    fontWeight = FontWeight.SemiBold
                )
            } else {
                Box(
                    modifier = Modifier
                        .width(42.dp)
                        .height(3.dp)
                        .clip(RoundedCornerShape(1.5.dp))
                        .background(if (isActiveDrawing) Color(0xFF38BDF8) else Color(0xFF475569))
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
                    color = Color(0xFFF1F5F9),
                    fontWeight = FontWeight.Bold
                )
            },
            text = {
                Text(
                    text = "Are you sure you want to delete this canvas?\nThis will remove it from the Org note and delete the file from disk.",
                    color = Color(0xFF94A3B8),
                    fontSize = 14.sp
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        showDeleteConfirmDialog = false
                        syncClient.deleteInkBlock(nodeId, block.fileName)
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFDC2626))
                ) {
                    Text("Delete", color = Color.White, fontWeight = FontWeight.SemiBold)
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteConfirmDialog = false }) {
                    Text("Cancel", color = Color(0xFF94A3B8))
                }
            },
            containerColor = Color(0xFF1E293B),
            shape = RoundedCornerShape(12.dp)
        )
    }
}
}

@Composable
private fun ParagraphBlockCard(
    block: OrgBlock.ParagraphBlock,
    onUpdateParagraph: (String) -> Unit,
    onInsertSketchAtSplit: (String, String) -> Unit,
    onInsertSketchAfterBlock: () -> Unit,
    onInsertSketchBeforeBlock: () -> Unit
) {
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
                .background(Color(0xFF1E293B))
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
                        Text("+ Add Sketch at Cursor", color = Color(0xFF38BDF8))
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
                        color = Color(0xFFE2E8F0),
                        fontSize = 15.sp,
                        lineHeight = 22.sp,
                        modifier = Modifier.weight(1f)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "✎ Edit / Split",
                        color = Color(0xFF94A3B8),
                        fontSize = 11.sp,
                        modifier = Modifier
                            .clip(RoundedCornerShape(6.dp))
                            .background(Color(0xFF0F172A))
                            .border(1.dp, Color(0xFF334155), RoundedCornerShape(6.dp))
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

@Composable
private fun TodoBadge(keyword: String) {
    val bg = when (keyword) {
        "DONE" -> Color(0xFF16A34A)
        "NEXT" -> Color(0xFF2563EB)
        else -> Color(0xFFD97706)
    }
    Text(
        text = keyword,
        color = Color.White,
        fontSize = 11.sp,
        fontWeight = FontWeight.Bold,
        fontFamily = FontFamily.Monospace,
        modifier = Modifier
            .clip(RoundedCornerShape(4.dp))
            .background(bg)
            .padding(horizontal = 6.dp, vertical = 2.dp)
    )
}

@Composable
private fun EmptyEditorPlaceholder(
    isPinned: Boolean = true,
    connectionState: ConnectionState = ConnectionState.DISCONNECTED,
    onOpenRecentNodes: () -> Unit = {},
    onConnectAdb: () -> Unit
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
        modifier = Modifier.fillMaxSize()
    ) {
        if (!isPinned) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .background(Color(0xFF1E293B))
                    .border(1.dp, Color(0xFF334155), RoundedCornerShape(8.dp))
                    .clickable { onOpenRecentNodes() }
                    .padding(horizontal = 14.dp, vertical = 8.dp)
            ) {
                WriteSvgIcon(asset = WriteIconAsset.MENU, tint = Color(0xFF38BDF8), size = 18.dp)
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = "Open Recent Nodes",
                    color = Color(0xFFE2E8F0),
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Medium
                )
                Spacer(modifier = Modifier.width(8.dp))
                Box(
                    modifier = Modifier
                        .size(8.dp)
                        .clip(CircleShape)
                        .background(connectionDotColor(connectionState))
                )
            }
            Spacer(modifier = Modifier.height(24.dp))
        }

        Text(
            text = "Org-WriteNotes Companion",
            color = Color(0xFFF8FAFC),
            fontSize = 22.sp,
            fontWeight = FontWeight.Bold
        )
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = "Run M-x org-writenotes-connect-adb in Emacs or select an Org heading from the sidebar.",
            color = Color(0xFF94A3B8),
            fontSize = 14.sp
        )
        Spacer(modifier = Modifier.height(16.dp))
        Button(onClick = onConnectAdb) {
            Text("Connect via ADB USB (localhost:9876)")
        }
    }
}

@Composable
private fun PairingModalDialog(
    onDismiss: () -> Unit,
    onConnectAdb: (Int) -> Unit,
    onConnectNetwork: (String, String?) -> Unit
) {
    var wsUrlOrQr by remember { mutableStateOf("ws://192.168.1.42:9876") }
    var token by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Pair with Running Emacs Instance") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    text = "Option 1: USB Debugging (ADB Reverse Tunnel)",
                    fontWeight = FontWeight.SemiBold
                )
                Button(
                    onClick = { onConnectAdb(9876) },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("One-Tap Connect via ADB (ws://127.0.0.1:9876)")
                }

                Divider()

                Text(
                    text = "Option 2: LAN / Tailscale / QR Payload (M-x org-writenotes-pair-qr)",
                    fontWeight = FontWeight.SemiBold
                )
                OutlinedTextField(
                    value = wsUrlOrQr,
                    onValueChange = { wsUrlOrQr = it },
                    label = { Text("Emacs WebSocket URL or QR JSON") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = token,
                    onValueChange = { token = it },
                    label = { Text("Session Token (optional)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onConnectNetwork(wsUrlOrQr, token.ifBlank { null }) }) {
                Text("Connect Network")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        }
    )
}
