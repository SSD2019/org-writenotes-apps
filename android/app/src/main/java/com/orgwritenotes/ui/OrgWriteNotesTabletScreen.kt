package com.orgwritenotes.ui

import android.content.res.Configuration
import androidx.activity.compose.BackHandler
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
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
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
    val bufferOutline by syncClient.bufferOutline.collectAsState()
    val agendaNodes by syncClient.agendaNodes.collectAsState()
    val searchNodes by syncClient.searchNodes.collectAsState()
    val galleryItems by syncClient.galleryItems.collectAsState()
    val emacsTheme by syncClient.emacsTheme.collectAsState()
    val activeSnapshot by syncClient.activeSnapshot.collectAsState()
    val remoteZoomedTarget by syncClient.zoomedInkBlockTarget.collectAsState()
    val pendingOfflineCount by syncClient.pendingOfflineCount.collectAsState()
    val conflictedFiles by syncClient.conflictedFiles.collectAsState()
    SideEffect { syncConflictCount.intValue = conflictedFiles.size }

    var activeTab by rememberSaveable { mutableStateOf(0) } // 0=History, 1=Outline, 2=Agenda, 3=Search, 4=Gallery
    var showNewNodeDialog by remember { mutableStateOf(false) }

    var showPairingDialog by remember { mutableStateOf(false) }
    var zoomedInkBlockFileName by remember { mutableStateOf<String?>(null) }
    var zoomedSourceView by remember { mutableStateOf<WriteCanvasBlockView?>(null) }

    val context = androidx.compose.ui.platform.LocalContext.current
    LaunchedEffect(zoomedInkBlockFileName) {
        if (!zoomedInkBlockFileName.isNullOrEmpty()) {
            val prefs = context.getSharedPreferences("org_writenotes_prefs", android.content.Context.MODE_PRIVATE)
            prefs.edit().putString("last_opened_sketch", zoomedInkBlockFileName).apply()
        }
    }

    // B2: restore the last-edited sketch once, after the first snapshot that contains it arrives.
    var lastSketchRestored by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(activeSnapshot, lastSketchRestored) {
        if (lastSketchRestored || zoomedInkBlockFileName != null) return@LaunchedEffect
        val snapshot = activeSnapshot ?: return@LaunchedEffect
        val prefs = context.getSharedPreferences("org_writenotes_prefs", android.content.Context.MODE_PRIVATE)
        // One attempt only: if the first snapshot lacks the sketch, don't pop it open on a later node.
        lastSketchRestored = true
        val last = prefs.getString("last_opened_sketch", null) ?: return@LaunchedEffect
        if (snapshot.blocks.filterIsInstance<OrgBlock.InkCanvasBlock>().any { it.fileName == last }) {
            zoomedInkBlockFileName = last
        }
    }

    LaunchedEffect(remoteZoomedTarget) {
        if (!remoteZoomedTarget.isNullOrEmpty()) {
            zoomedInkBlockFileName = remoteZoomedTarget
            syncClient.clearZoomedInkBlockTarget()
        }
    }
    var searchQuery by remember { mutableStateOf("") }

    LaunchedEffect(searchQuery, activeTab) {
        if (activeTab == 3 && searchQuery.trim().length >= 2) {
            delay(250L)
            syncClient.searchNodes(searchQuery.trim())
        }
    }

    val targetSummaries = when (activeTab) {
        1 -> bufferOutline
        2 -> agendaNodes
        3 -> searchNodes
        else -> summaries
    }
    val filteredSummaries = remember(targetSummaries, searchQuery, activeTab) {
        val q = searchQuery.trim()
        if (q.isEmpty() || activeTab == 3) {
            targetSummaries
        } else {
            targetSummaries.filter {
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

    BackHandler(enabled = !isPinned && isDrawerOpen) {
        isDrawerOpen = false
    }

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

    val isEInk = OnyxEInkBridge.isEInkDevice
    val contentPadding = if (isPortrait) 12.dp else 20.dp
    val sidebarWidth = if (isPortrait) 290.dp else 310.dp

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(if (isEInk) Color(0xFFF1F5F9) else Color(0xFF0F172A))
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
                        activeTab = activeTab,
                        onTabChange = {
                            activeTab = it
                            when (it) {
                                1 -> syncClient.fetchBufferOutline()
                                2 -> syncClient.fetchAgendaNodes()
                                3 -> if (searchQuery.isNotBlank()) syncClient.searchNodes(searchQuery)
                                4 -> syncClient.fetchGallery()
                            }
                        },
                        onOpenNewNodeDialog = { showNewNodeDialog = true },
                        summaries = summaries,
                        filteredSummaries = filteredSummaries,
                        galleryItems = galleryItems,
                        activeNodeId = activeSnapshot?.nodeId,
                        onSelectNode = { nodeId -> syncClient.fetchNode(nodeId) },
                        onSelectGalleryItem = { item ->
                            item.nodeId?.let { syncClient.fetchNode(it) }
                            zoomedInkBlockFileName = item.fileName
                        },
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
            val drawerPanel: @Composable () -> Unit = {
                Box(
                    modifier = Modifier
                        .width(320.dp)
                        .fillMaxHeight()
                        .background(if (isEInk) Color.White else Color(0xFF1E293B))
                        .border(
                            width = 1.dp,
                            color = if (isEInk) Color.Black else Color(0xFF334155),
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
                        activeTab = activeTab,
                        onTabChange = {
                            activeTab = it
                            when (it) {
                                1 -> syncClient.fetchBufferOutline()
                                2 -> syncClient.fetchAgendaNodes()
                                3 -> if (searchQuery.isNotBlank()) syncClient.searchNodes(searchQuery)
                                4 -> syncClient.fetchGallery()
                            }
                        },
                        onOpenNewNodeDialog = { showNewNodeDialog = true },
                        summaries = summaries,
                        filteredSummaries = filteredSummaries,
                        galleryItems = galleryItems,
                        activeNodeId = activeSnapshot?.nodeId,
                        onSelectNode = { nodeId ->
                            syncClient.fetchNode(nodeId)
                            isDrawerOpen = false // Auto-hide upon selection!
                        },
                        onSelectGalleryItem = { item ->
                            item.nodeId?.let { syncClient.fetchNode(it) }
                            zoomedInkBlockFileName = item.fileName
                            isDrawerOpen = false
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

            if (isEInk) {
                if (isDrawerOpen) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .clickable { isDrawerOpen = false }
                    )
                    Box(modifier = Modifier.align(Alignment.CenterStart)) {
                        drawerPanel()
                    }
                }
            } else {
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
                    drawerPanel()
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
        val lastError by syncClient.lastErrorMessage.collectAsState()
        PairingModalDialog(
            errorMessage = lastError,
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

    if (showNewNodeDialog) {
        var newTitle by remember { mutableStateOf("") }
        var newTodo by remember { mutableStateOf("TODO") }
        var newMode by remember { mutableStateOf("child") }
        var includeBlankSketch by remember { mutableStateOf(false) }
        AlertDialog(
            onDismissRequest = { showNewNodeDialog = false },
            title = { Text("Create New Org Heading / Quick Capture", color = if (isEInk) Color.Black else Color.White) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedTextField(
                        value = newTitle,
                        onValueChange = { newTitle = it },
                        label = { Text("Heading Title") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Text("Insertion Mode:", color = if (isEInk) Color.Black else Color(0xFF94A3B8), fontSize = 12.sp)
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        listOf("child" to "Child Subheading", "sibling" to "Sibling", "capture" to "Quick Capture").forEach { (m, label) ->
                            FilterChip(
                                selected = newMode == m,
                                onClick = { newMode = m },
                                label = { Text(label, fontSize = 11.sp) }
                            )
                        }
                    }
                    Text("TODO State:", color = if (isEInk) Color.Black else Color(0xFF94A3B8), fontSize = 12.sp)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf("TODO", "NEXT", "IDEA").forEach { kw ->
                            FilterChip(
                                selected = newTodo == kw,
                                onClick = { newTodo = kw },
                                label = { Text(kw) }
                            )
                        }
                    }
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { includeBlankSketch = !includeBlankSketch }
                    ) {
                        Checkbox(
                            checked = includeBlankSketch,
                            onCheckedChange = { includeBlankSketch = it }
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Immediately attach blank .svgz sketch", color = if (isEInk) Color.Black else Color(0xFFE2E8F0), fontSize = 13.sp)
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        val t = newTitle.trim().ifEmpty { "New Note" }
                        syncClient.createNode(
                            title = t,
                            todoKeyword = newTodo,
                            parentId = activeSnapshot?.nodeId,
                            mode = newMode,
                            createSketch = includeBlankSketch
                        )
                        showNewNodeDialog = false
                    }
                ) {
                    Text("Create")
                }
            },
            dismissButton = {
                TextButton(onClick = { showNewNodeDialog = false }) {
                    Text("Cancel")
                }
            },
            containerColor = if (isEInk) Color.White else Color(0xFF1E293B)
        )
    }
}

fun connectionDotColor(state: ConnectionState): Color {
    if (OnyxEInkBridge.isEInkDevice) {
        return when (state) {
            ConnectionState.CONNECTED_ADB, ConnectionState.CONNECTED_NETWORK -> Color.Black
            else -> Color(0xFF64748B)
        }
    }
    return when (state) {
        ConnectionState.CONNECTED_ADB -> Color(0xFF22C55E)
        ConnectionState.CONNECTED_NETWORK -> Color(0xFF38BDF8)
        ConnectionState.OFFLINE_CACHED -> Color(0xFFF59E0B)
        ConnectionState.CONNECTING -> Color(0xFFFACC15)
        ConnectionState.ERROR -> Color(0xFFEF4444)
        ConnectionState.DISCONNECTED -> Color(0xFF94A3B8)
    }
}

@Composable
private fun RecentNodesSidebarContent(
    connectionState: ConnectionState,
    pendingOfflineCount: Int,
    onOpenPairing: () -> Unit,
    searchQuery: String,
    onSearchQueryChange: (String) -> Unit,
    activeTab: Int,
    onTabChange: (Int) -> Unit,
    onOpenNewNodeDialog: () -> Unit,
    summaries: List<OrgNodeSummary>,
    filteredSummaries: List<OrgNodeSummary>,
    galleryItems: List<OrgGalleryItem> = emptyList(),
    activeNodeId: String?,
    onSelectNode: (String) -> Unit,
    onSelectGalleryItem: (OrgGalleryItem) -> Unit = {},
    onClearHistory: () -> Unit,
    isPinned: Boolean,
    onTogglePin: () -> Unit,
    onClose: (() -> Unit)? = null
) {
    val isEInk = OnyxEInkBridge.isEInkDevice
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(if (isEInk) Color.White else Color(0xFF1E293B))
            .then(if (isEInk && isPinned) Modifier.border(1.dp, Color.Black) else Modifier)
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
                    .background(
                        if (isEInk) (if (isPinned) Color(0xFFE2E8F0) else Color.White)
                        else (if (isPinned) Color(0xFF0284C7) else Color(0xFF334155))
                    )
                    .then(if (isEInk) Modifier.border(1.dp, Color.Black, RoundedCornerShape(8.dp)) else Modifier)
            ) {
                WriteSvgIcon(
                    asset = WriteIconAsset.PIN,
                    tint = if (isEInk) Color.Black else (if (isPinned) Color.White else Color(0xFF94A3B8)),
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
                        .background(if (isEInk) Color.White else Color(0xFF334155))
                        .then(if (isEInk) Modifier.border(1.dp, Color.Black, RoundedCornerShape(8.dp)) else Modifier)
                ) {
                    WriteSvgIcon(
                        asset = WriteIconAsset.CLOSE,
                        tint = if (isEInk) Color.Black else Color(0xFF94A3B8),
                        size = 16.dp
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(10.dp))

        // Tab Selector: History, Outline, Agenda, Search, Gallery + New Heading
        val tabDefs = listOf(
            0 to "Hist",
            1 to "Outl",
            2 to "Agda",
            3 to "Find",
            4 to "Gal"
        )
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(8.dp))
                .background(if (isEInk) Color(0xFFF1F5F9) else Color(0xFF0F172A))
                .then(if (isEInk) Modifier.border(1.dp, Color.Black, RoundedCornerShape(8.dp)) else Modifier)
                .padding(3.dp)
        ) {
            tabDefs.forEach { (idx, label) ->
                val active = activeTab == idx
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(6.dp))
                        .background(
                            if (isEInk) (if (active) Color.White else Color.Transparent)
                            else (if (active) Color(0xFF334155) else Color.Transparent)
                        )
                        .then(if (isEInk && active) Modifier.border(1.dp, Color.Black, RoundedCornerShape(6.dp)) else Modifier)
                        .clickable { onTabChange(idx) }
                        .padding(vertical = 6.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = label,
                        color = if (isEInk) Color.Black else (if (active) Color.White else Color(0xFF94A3B8)),
                        fontSize = 11.sp,
                        fontWeight = if (active) FontWeight.Bold else FontWeight.SemiBold
                    )
                }
            }

            IconButton(
                onClick = onOpenNewNodeDialog,
                modifier = Modifier
                    .size(28.dp)
                    .clip(RoundedCornerShape(6.dp))
                    .background(if (isEInk) Color.White else Color(0xFF0284C7))
                    .then(if (isEInk) Modifier.border(1.dp, Color.Black, RoundedCornerShape(6.dp)) else Modifier)
            ) {
                Text("＋", color = if (isEInk) Color.Black else Color.White, fontSize = 14.sp, fontWeight = FontWeight.Bold)
            }
        }

        Spacer(modifier = Modifier.height(10.dp))

        OutlinedTextField(
            value = searchQuery,
            onValueChange = onSearchQueryChange,
            placeholder = {
                Text(
                    text = when (activeTab) {
                        1 -> "Filter buffer outline..."
                        2 -> "Filter scheduled / deadline..."
                        3 -> "Full-text + OCR search..."
                        4 -> "Filter sketch gallery..."
                        else -> "Filter pushed history..."
                    },
                    color = if (isEInk) Color(0xFF475569) else Color(0xFF94A3B8),
                    fontSize = 12.sp
                )
            },
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
                text = when (activeTab) {
                    1 -> "BUFFER OUTLINE (${filteredSummaries.size})"
                    2 -> "AGENDA / DUE (${filteredSummaries.size})"
                    3 -> "SEARCH RESULTS (${filteredSummaries.size})"
                    4 -> "SKETCH GALLERY (${galleryItems.size})"
                    else -> "PUSHED HISTORY (${filteredSummaries.size})"
                },
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
                color = if (isEInk) Color.Black else Color(0xFF94A3B8)
            )

            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = if (isPinned) "Docked" else "Auto-hide",
                    fontSize = 10.sp,
                    color = if (isEInk) Color.Black else (if (isPinned) Color(0xFF38BDF8) else Color(0xFFF59E0B)),
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier
                        .clip(RoundedCornerShape(4.dp))
                        .background(if (isEInk) Color(0xFFF1F5F9) else Color(0xFF0F172A))
                        .then(if (isEInk) Modifier.border(1.dp, Color.Black, RoundedCornerShape(4.dp)) else Modifier)
                        .clickable { onTogglePin() }
                        .padding(horizontal = 6.dp, vertical = 3.dp)
                )

                if (activeTab == 0 && summaries.size > 1) {
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "Clear",
                        fontSize = 11.sp,
                        color = if (isEInk) Color.Black else Color(0xFF38BDF8),
                        fontWeight = if (isEInk) FontWeight.Bold else FontWeight.Normal,
                        modifier = Modifier
                            .clip(RoundedCornerShape(4.dp))
                            .clickable { onClearHistory() }
                            .padding(horizontal = 4.dp, vertical = 2.dp)
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        if (activeTab == 4) {
            val filteredGallery = remember(galleryItems, searchQuery) {
                val q = searchQuery.trim()
                if (q.isEmpty()) galleryItems
                else galleryItems.filter {
                    it.fileName.contains(q, ignoreCase = true) ||
                        it.nodeTitle.contains(q, ignoreCase = true) ||
                        it.ocrText.contains(q, ignoreCase = true)
                }
            }
            if (filteredGallery.isEmpty()) {
                Text(
                    text = "No .svgz sketches found in buffer.",
                    color = if (isEInk) Color(0xFF334155) else Color(0xFF64748B),
                    fontSize = 12.sp,
                    modifier = Modifier.padding(top = 8.dp)
                )
            } else {
                LazyColumn(
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.weight(1f)
                ) {
                    items(filteredGallery, key = { "${it.nodeId}:${it.fileName}" }) { item ->
                        GalleryItemCard(item = item, onClick = { onSelectGalleryItem(item) })
                    }
                }
            }
        } else if (filteredSummaries.isEmpty()) {
            Text(
                text = when (activeTab) {
                    1 -> "No outline headings found in active buffer."
                    2 -> "No scheduled, deadline, or active TODO headings."
                    3 -> "Type 2+ characters above to search Org headings and handwriting OCR."
                    else -> "No nodes pushed yet.\nRun M-x org-writenotes-push-node in Emacs."
                },
                color = if (isEInk) Color(0xFF334155) else Color(0xFF64748B),
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
                        indentByLevel = activeTab == 1,
                        showAgendaDates = activeTab == 2,
                        onClick = { onSelectNode(item.nodeId) }
                    )
                }
            }
        }
    }
}

@Composable
private fun GalleryItemCard(
    item: OrgGalleryItem,
    onClick: () -> Unit
) {
    val isEInk = OnyxEInkBridge.isEInkDevice
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(if (isEInk) Color.White else Color(0xFF0F172A))
            .border(1.dp, if (isEInk) Color.Black else Color(0xFF334155), RoundedCornerShape(8.dp))
            .clickable { onClick() }
            .padding(10.dp)
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(
                text = "🎨 ${item.fileName}",
                color = if (isEInk) Color.Black else Color(0xFF38BDF8),
                fontSize = 12.sp,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                modifier = Modifier.weight(1f)
            )
            Text(
                text = "${item.width}×${item.height}",
                color = if (isEInk) Color(0xFF334155) else Color(0xFF64748B),
                fontSize = 10.sp,
                fontFamily = FontFamily.Monospace
            )
        }
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = item.nodeTitle,
            color = if (isEInk) Color.Black else Color(0xFFF1F5F9),
            fontSize = 13.sp,
            fontWeight = FontWeight.Medium,
            maxLines = 1
        )
        if (item.ocrText.isNotBlank()) {
            Spacer(modifier = Modifier.height(3.dp))
            Text(
                text = "OCR: ${item.ocrText}",
                color = if (isEInk) Color(0xFF334155) else Color(0xFF94A3B8),
                fontSize = 11.sp,
                maxLines = 2
            )
        }
    }
}

/** A7: number of sketches whose last commit conflicted with a newer copy on the Emacs side. */
private val syncConflictCount = mutableIntStateOf(0)

@Composable
private fun ConnectionStatusPill(
    state: ConnectionState,
    pendingOfflineCount: Int,
    onClick: () -> Unit
) {
    val isEInk = OnyxEInkBridge.isEInkDevice
    val conflicts = syncConflictCount.intValue
    val queueSuffix = (if (pendingOfflineCount > 0) " • $pendingOfflineCount queued" else "") +
        (if (conflicts > 0) " • ⚠ $conflicts conflict${if (conflicts > 1) "s" else ""}" else "")
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
            .background(if (isEInk) Color(0xFFF1F5F9) else Color(0xFF0F172A))
            .then(if (isEInk) Modifier.border(1.dp, Color.Black, RoundedCornerShape(8.dp)) else Modifier)
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
            color = if (isEInk) Color.Black else Color(0xFFE2E8F0),
            fontSize = 12.sp,
            fontWeight = FontWeight.Medium
        )
    }
}

@Composable
private fun SidebarNodeRow(
    summary: OrgNodeSummary,
    isSelected: Boolean,
    indentByLevel: Boolean = false,
    showAgendaDates: Boolean = false,
    onClick: () -> Unit
) {
    val isEInk = OnyxEInkBridge.isEInkDevice
    val bgColor = if (isEInk) {
        if (isSelected) Color(0xFFE2E8F0) else Color.White
    } else {
        if (isSelected) Color(0xFF334155) else Color.Transparent
    }
    val levelIndent = if (indentByLevel) ((summary.level - 1).coerceAtLeast(0) * 10).dp else 0.dp
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = levelIndent)
            .clip(RoundedCornerShape(8.dp))
            .background(bgColor)
            .then(
                if (isEInk && isSelected) Modifier.border(1.5.dp, Color.Black, RoundedCornerShape(8.dp))
                else if (isEInk) Modifier.border(0.5.dp, Color(0xFFCBD5E1), RoundedCornerShape(8.dp))
                else Modifier
            )
            .clickable { onClick() }
            .padding(horizontal = 10.dp, vertical = 8.dp)
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth()
        ) {
            summary.todoKeyword?.let { kw ->
                TodoBadge(keyword = kw)
                Spacer(modifier = Modifier.width(8.dp))
            }
            Text(
                text = summary.title,
                color = if (isEInk) Color.Black else Color(0xFFF8FAFC),
                fontSize = 14.sp,
                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                maxLines = 1,
                modifier = Modifier.weight(1f)
            )
            if (summary.sketchCount > 0) {
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = "🎨${summary.sketchCount}",
                    color = if (isEInk) Color.Black else Color(0xFF38BDF8),
                    fontSize = 11.sp,
                    fontFamily = FontFamily.Monospace
                )
            }
        }
        if (showAgendaDates && (summary.scheduled != null || summary.deadline != null)) {
            Spacer(modifier = Modifier.height(3.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                summary.scheduled?.let {
                    Text(
                        text = "📅 $it",
                        color = if (isEInk) Color.Black else Color(0xFF38BDF8),
                        fontSize = 10.sp,
                        fontFamily = FontFamily.Monospace
                    )
                }
                summary.deadline?.let {
                    Text(
                        text = "⏰ $it",
                        color = if (isEInk) Color.Black else Color(0xFFF87171),
                        fontSize = 10.sp,
                        fontFamily = FontFamily.Monospace
                    )
                }
            }
        }
    }
}

/**
 * Owns the live org-clock tick so only this chip recomposes each tick, not the whole editor pane
 * (and its ink canvases). E-Ink panels tick once a minute to avoid a screen refresh every second.
 */
@Composable
private fun ClockChip(
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
private fun HybridNodeEditorPane(
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
        if (activeInkBlock != null) {
            Spacer(modifier = Modifier.height(8.dp))

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
private fun PropertiesDrawerPanel(
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

@Composable
private fun ChecklistBlockCard(
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
private fun CodeBlockCard(
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
private fun TableBlockCard(
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
private fun InkCanvasBlockCard(
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
private fun ParagraphBlockCard(
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

@Composable
private fun TodoBadge(keyword: String) {
    val isEInk = OnyxEInkBridge.isEInkDevice
    val bg = if (isEInk) {
        if (keyword == "DONE") Color.White else Color.Black
    } else {
        when (keyword) {
            "DONE" -> Color(0xFF16A34A)
            "NEXT" -> Color(0xFF2563EB)
            else -> Color(0xFFD97706)
        }
    }
    val fg = if (isEInk && keyword == "DONE") Color.Black else Color.White
    Text(
        text = keyword,
        color = fg,
        fontSize = 11.sp,
        fontWeight = FontWeight.Bold,
        fontFamily = FontFamily.Monospace,
        modifier = Modifier
            .clip(RoundedCornerShape(4.dp))
            .background(bg)
            .then(if (isEInk) Modifier.border(1.dp, Color.Black, RoundedCornerShape(4.dp)) else Modifier)
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
    val isEInk = OnyxEInkBridge.isEInkDevice
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
                    .background(if (isEInk) Color.White else Color(0xFF1E293B))
                    .border(1.dp, if (isEInk) Color.Black else Color(0xFF334155), RoundedCornerShape(8.dp))
                    .clickable { onOpenRecentNodes() }
                    .padding(horizontal = 14.dp, vertical = 8.dp)
            ) {
                WriteSvgIcon(asset = WriteIconAsset.MENU, tint = if (isEInk) Color.Black else Color(0xFF38BDF8), size = 18.dp)
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = "Open Recent Nodes",
                    color = if (isEInk) Color.Black else Color(0xFFE2E8F0),
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
            color = if (isEInk) Color.Black else Color(0xFFF8FAFC),
            fontSize = 22.sp,
            fontWeight = FontWeight.Bold
        )
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = "Run M-x org-writenotes-connect-adb in Emacs or select an Org heading from the sidebar.",
            color = if (isEInk) Color(0xFF334155) else Color(0xFF94A3B8),
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
    errorMessage: String? = null,
    onDismiss: () -> Unit,
    onConnectAdb: (Int) -> Unit,
    onConnectNetwork: (String, String?) -> Unit
) {
    val isEInk = OnyxEInkBridge.isEInkDevice
    var wsUrlOrQr by remember { mutableStateOf("ws://192.168.1.42:9876") }
    var token by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Pair with Running Emacs Instance", color = if (isEInk) Color.Black else Color.White) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                if (!errorMessage.isNullOrBlank()) {
                    Text(
                        text = "⚠ $errorMessage",
                        color = if (isEInk) Color.Black else Color(0xFFEF4444),
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Medium,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(6.dp))
                            .background(if (isEInk) Color(0xFFF1F5F9) else Color(0x33EF4444))
                            .then(if (isEInk) Modifier.border(1.dp, Color.Black, RoundedCornerShape(6.dp)) else Modifier)
                            .padding(8.dp)
                    )
                }

                Text(
                    text = "Option 1: USB Debugging (ADB Reverse Tunnel)",
                    fontWeight = FontWeight.SemiBold,
                    color = if (isEInk) Color.Black else Color(0xFFF8FAFC)
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
                    fontWeight = FontWeight.SemiBold,
                    color = if (isEInk) Color.Black else Color(0xFFF8FAFC)
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

                var showLogs by remember { mutableStateOf(false) }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    TextButton(onClick = { showLogs = !showLogs }) {
                        Text(if (showLogs) "Hide Diagnostics" else "View Diagnostics")
                    }
                    val ctx = androidx.compose.ui.platform.LocalContext.current
                    TextButton(onClick = {
                        val bundle = com.orgwritenotes.sync.AppLogger.dumpDebugBundle()
                        val clipboard = ctx.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                        clipboard.setPrimaryClip(android.content.ClipData.newPlainText("OrgWriteNotes Logs", bundle))
                        android.widget.Toast.makeText(ctx, "Debug bundle copied to clipboard", android.widget.Toast.LENGTH_SHORT).show()
                    }) {
                        Text("Copy Debug Bundle")
                    }
                }

                if (showLogs) {
                    val logs by com.orgwritenotes.sync.AppLogger.logsFlow.collectAsState()
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(140.dp)
                            .clip(RoundedCornerShape(6.dp))
                            .background(if (isEInk) Color(0xFFF1F5F9) else Color(0xFF0F172A))
                            .then(if (isEInk) Modifier.border(1.dp, Color.Black, RoundedCornerShape(6.dp)) else Modifier)
                            .verticalScroll(rememberScrollState())
                            .padding(8.dp)
                    ) {
                        if (logs.isEmpty()) {
                            Text("No internal logs recorded yet.", color = if (isEInk) Color.Black else Color.Gray, fontSize = 11.sp)
                        } else {
                            logs.takeLast(40).forEach { item ->
                                val color = if (isEInk) Color.Black else when (item.level) {
                                    "E" -> Color(0xFFEF4444)
                                    "W" -> Color(0xFFF59E0B)
                                    else -> Color(0xFF94A3B8)
                                }
                                Text(item.format(), color = color, fontSize = 10.sp, fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace)
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onConnectNetwork(wsUrlOrQr, token.ifBlank { null }) }) {
                Text("Connect Network")
            }
        },
        dismissButton = {
            TextButton(onClick = { onDismiss() }) {
                Text("Cancel")
            }
        },
        containerColor = if (isEInk) Color.White else Color(0xFF1E293B)
    )
}
