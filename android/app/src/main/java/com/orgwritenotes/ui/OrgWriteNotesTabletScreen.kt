package com.orgwritenotes.ui

import android.content.res.Configuration
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.orgwritenotes.ink.OnyxEInkBridge
import com.orgwritenotes.model.*
import com.orgwritenotes.sync.ConnectionState
import com.orgwritenotes.sync.EmacsSyncClient
import kotlinx.coroutines.delay

/**
 * Main tablet UI for Org-WriteNotes:
 * - Sidebar (docked, overlay drawer, or the focus-mode icon rail): connection status, the
 *   History / Outline / Agenda / Search / Gallery tabs and the node list.
 * - Editor pane ([HybridNodeEditorPane]) for the active node's header and blocks, with the
 *   full-screen [WriteZoomedEditorOverlay] on top for sketch editing.
 * - Ctrl+K command palette and hardware keyboard shortcuts (see [TabletUiState.handleKey]).
 * Left-handed mode mirrors the layout so the sidebar sits on the right.
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
    val activeSnapshot by syncClient.activeSnapshot.collectAsState()
    val remoteZoomedTarget by syncClient.zoomedInkBlockTarget.collectAsState()
    val pendingOfflineCount by syncClient.pendingOfflineCount.collectAsState()
    val conflictedFiles by syncClient.conflictedFiles.collectAsState()
    SideEffect { syncConflictCount.intValue = conflictedFiles.size }

    val ui = remember(syncClient) { TabletUiState(syncClient) }
    val context = LocalContext.current
    val focusMode = UiPrefs.focusMode
    val leftHanded = UiPrefs.leftHanded

    // Heading history for back / forward (rail buttons, swipes, Alt+arrows).
    LaunchedEffect(activeSnapshot?.nodeId) {
        activeSnapshot?.nodeId?.let { ui.onNodeShown(it) }
    }

    LaunchedEffect(ui.zoomedInkBlockFileName) {
        val name = ui.zoomedInkBlockFileName
        if (!name.isNullOrEmpty()) {
            val prefs = context.getSharedPreferences("org_writenotes_prefs", android.content.Context.MODE_PRIVATE)
            prefs.edit().putString("last_opened_sketch", name).apply()
        }
    }

    // B2: restore the last-edited sketch once, after the first snapshot that contains it arrives.
    var lastSketchRestored by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(activeSnapshot, lastSketchRestored) {
        if (lastSketchRestored || ui.zoomedInkBlockFileName != null) return@LaunchedEffect
        val snapshot = activeSnapshot ?: return@LaunchedEffect
        val prefs = context.getSharedPreferences("org_writenotes_prefs", android.content.Context.MODE_PRIVATE)
        // One attempt only: if the first snapshot lacks the sketch, don't pop it open on a later node.
        lastSketchRestored = true
        val last = prefs.getString("last_opened_sketch", null) ?: return@LaunchedEffect
        if (snapshot.blocks.filterIsInstance<OrgBlock.InkCanvasBlock>().any { it.fileName == last }) {
            ui.zoomedInkBlockFileName = last
        }
    }

    LaunchedEffect(remoteZoomedTarget) {
        if (!remoteZoomedTarget.isNullOrEmpty()) {
            ui.zoomedInkBlockFileName = remoteZoomedTarget
            syncClient.clearZoomedInkBlockTarget()
        }
    }

    LaunchedEffect(ui.searchQuery, ui.activeTab) {
        if (ui.activeTab == SidebarTab.SEARCH && ui.searchQuery.trim().length >= 2) {
            delay(250L)
            syncClient.searchNodes(ui.searchQuery.trim())
        }
    }

    val activeTab = ui.activeTab
    val searchQuery = ui.searchQuery
    val targetSummaries = when (activeTab) {
        SidebarTab.OUTLINE -> bufferOutline
        SidebarTab.AGENDA -> agendaNodes
        SidebarTab.SEARCH -> searchNodes
        else -> summaries
    }
    val filteredSummaries = remember(targetSummaries, searchQuery, activeTab) {
        val q = searchQuery.trim()
        if (q.isEmpty() || activeTab == SidebarTab.SEARCH) {
            targetSummaries
        } else {
            targetSummaries.filter {
                it.title.contains(q, ignoreCase = true) ||
                    (it.todoKeyword?.contains(q, ignoreCase = true) == true) ||
                    it.tags.any { tag -> tag.contains(q, ignoreCase = true) }
            }
        }
    }

    // Auto-hide vs Pinned (Docked): portrait defaults to auto-hide, landscape to docked.
    // Focus mode never docks the sidebar; it shows the icon rail and uses the drawer instead.
    val isPinned = !focusMode && (if (isPortrait) ui.isPinnedPortrait else ui.isPinnedLandscape)

    BackHandler(enabled = !isPinned && ui.isDrawerOpen) {
        ui.isDrawerOpen = false
    }

    val onTogglePin = {
        if (isPortrait) {
            ui.isPinnedPortrait = !ui.isPinnedPortrait
        } else {
            ui.isPinnedLandscape = !ui.isPinnedLandscape
        }
    }
    val toggleFocusMode = {
        UiPrefs.setFocusMode(context, !UiPrefs.focusMode)
        ui.isDrawerOpen = false
    }

    val zoomedBlock = remember(activeSnapshot, ui.zoomedInkBlockFileName) {
        val targetFile = ui.zoomedInkBlockFileName ?: return@remember null
        activeSnapshot?.blocks?.filterIsInstance<OrgBlock.InkCanvasBlock>()?.find {
            it.fileName == targetFile
        }
    }

    val shortcutActions = remember(syncClient) {
        ShortcutActions(
            toggleFocusMode = toggleFocusMode,
            cycleTodo = { syncClient.activeSnapshot.value?.let { syncClient.cycleTodoState(it) } },
            toggleClock = {
                syncClient.activeSnapshot.value?.let {
                    if (it.header.clockedIn) syncClient.clockOut(it.nodeId) else syncClient.clockIn(it.nodeId)
                }
            },
            flagFlashcard = { syncClient.activeSnapshot.value?.let { syncClient.flagAsFlashcard(it.nodeId) } },
            refresh = {
                syncClient.activeSnapshot.value?.let { syncClient.fetchNode(it.nodeId) }
                ui.selectTab(ui.activeTab)
            }
        )
    }
    DisposableEffect(ui) {
        TabletKeyDispatcher.handler = { ui.handleKey(it, shortcutActions) }
        onDispose { TabletKeyDispatcher.handler = null }
    }

    val isEInk = OnyxEInkBridge.isEInkDevice
    val contentPadding = if (isPortrait) 12.dp else 20.dp
    val sidebarWidth = if (isPortrait) 290.dp else 310.dp

    // One sidebar body for both the docked and the drawer presentation.
    val sidebarContent: @Composable (docked: Boolean) -> Unit = { docked ->
        RecentNodesSidebarContent(
            connectionState = connectionState,
            pendingOfflineCount = pendingOfflineCount,
            onOpenPairing = {
                if (!docked) ui.isDrawerOpen = false
                ui.showPairingDialog = true
            },
            searchQuery = searchQuery,
            onSearchQueryChange = { ui.searchQuery = it },
            activeTab = activeTab,
            onTabChange = { ui.selectTab(it) },
            onOpenNewNodeDialog = { ui.showNewNodeDialog = true },
            summaries = summaries,
            filteredSummaries = filteredSummaries,
            galleryItems = galleryItems,
            activeNodeId = activeSnapshot?.nodeId,
            onSelectNode = { nodeId ->
                syncClient.fetchNode(nodeId)
                if (!docked) ui.isDrawerOpen = false // Auto-hide upon selection
            },
            onSelectGalleryItem = { item ->
                item.nodeId?.let { syncClient.fetchNode(it) }
                ui.zoomedInkBlockFileName = item.fileName
                if (!docked) ui.isDrawerOpen = false
            },
            onClearHistory = { syncClient.clearHistory() },
            onOpenConflicts = {
                if (!docked) ui.isDrawerOpen = false
                ui.showConflictDialog = true
            },
            isPinned = docked,
            onTogglePin = {
                if (focusMode) toggleFocusMode() else onTogglePin()
                if (!docked) ui.isDrawerOpen = false
            },
            onClose = if (docked) null else ({ ui.isDrawerOpen = false })
        )
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(if (isEInk) Color(0xFFF1F5F9) else Color(0xFF0F172A))
    ) {
        Row(modifier = Modifier.fillMaxSize()) {
            // 1. Docked sidebar or focus-mode rail; on the right in left-handed mode.
            val sideChrome: @Composable () -> Unit = {
                if (focusMode) {
                    FocusRail(ui = ui, connectionState = connectionState, onExitFocus = toggleFocusMode)
                } else if (isPinned) {
                    Box(modifier = Modifier.width(sidebarWidth).fillMaxHeight()) {
                        sidebarContent(true)
                    }
                }
            }
            if (!leftHanded) sideChrome()

            // 2. Main Editor Pane (takes full remaining width)
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .padding(contentPadding)
                    .horizontalSwipeNav(
                        enabled = focusMode && ui.zoomedInkBlockFileName == null,
                        onSwipeRight = { ui.goBack() },
                        onSwipeLeft = { ui.goForward() }
                    )
            ) {
                val snapshot = activeSnapshot
                if (snapshot == null) {
                    EmptyEditorPlaceholder(
                        isPinned = isPinned || focusMode,
                        connectionState = connectionState,
                        onOpenRecentNodes = { ui.isDrawerOpen = true },
                        onConnectAdb = { syncClient.connectAdbUsb() }
                    )
                } else {
                    HybridNodeEditorPane(
                        snapshot = snapshot,
                        syncClient = syncClient,
                        isPinned = isPinned || focusMode,
                        connectionState = connectionState,
                        onOpenRecentNodes = { ui.isDrawerOpen = true },
                        onZoomInkBlock = { fileName, sourceView ->
                            syncClient.flushPendingInkCommits()
                            ui.zoomedSourceView = sourceView
                            ui.zoomedInkBlockFileName = fileName
                        }
                    )
                }
            }

            if (leftHanded) sideChrome()
        }

        // 3. Auto-hide Overlay Drawer (active when not docked)
        if (!isPinned) {
            val drawerAlign = if (leftHanded) Alignment.CenterEnd else Alignment.CenterStart
            val drawerShape: Shape = if (leftHanded) {
                RoundedCornerShape(topStart = 16.dp, bottomStart = 16.dp)
            } else {
                RoundedCornerShape(topEnd = 16.dp, bottomEnd = 16.dp)
            }
            val drawerPanel: @Composable () -> Unit = {
                Box(
                    modifier = Modifier
                        .padding(start = if (focusMode && !leftHanded) 56.dp else 0.dp, end = if (focusMode && leftHanded) 56.dp else 0.dp)
                        .width(320.dp)
                        .fillMaxHeight()
                        .background(if (isEInk) Color.White else Color(0xFF1E293B))
                        .border(width = 1.dp, color = if (isEInk) Color.Black else Color(0xFF334155), shape = drawerShape)
                        .clip(drawerShape)
                        .pointerInput(Unit) {
                            detectTapGestures { /* block background touch through */ }
                        }
                ) {
                    sidebarContent(false)
                }
            }

            if (isEInk) {
                if (ui.isDrawerOpen) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .clickable { ui.isDrawerOpen = false }
                    )
                    Box(modifier = Modifier.align(drawerAlign)) {
                        drawerPanel()
                    }
                }
            } else {
                AnimatedVisibility(
                    visible = ui.isDrawerOpen,
                    enter = fadeIn(),
                    exit = fadeOut()
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(Color.Black.copy(alpha = 0.55f))
                            .clickable { ui.isDrawerOpen = false }
                    )
                }

                AnimatedVisibility(
                    visible = ui.isDrawerOpen,
                    enter = slideInHorizontally(initialOffsetX = { if (leftHanded) it else -it }) + fadeIn(),
                    exit = slideOutHorizontally(targetOffsetX = { if (leftHanded) it else -it }) + fadeOut(),
                    modifier = Modifier.align(drawerAlign)
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
                sourceInlineView = ui.zoomedSourceView,
                onCloseZoom = { _, _ ->
                    syncClient.flushPendingInkCommits()
                    ui.zoomedSourceView = null
                    ui.zoomedInkBlockFileName = null
                }
            )
        }
    }

    if (ui.showPairingDialog) {
        val lastError by syncClient.lastErrorMessage.collectAsState()
        PairingModalDialog(
            errorMessage = lastError,
            onDismiss = { ui.showPairingDialog = false },
            onConnectAdb = { port ->
                syncClient.connectAdbUsb(port)
                ui.showPairingDialog = false
            },
            onConnectNetwork = { urlOrQr, token ->
                syncClient.connectNetwork(urlOrQr, token)
                ui.showPairingDialog = false
            }
        )
    }

    if (ui.showConflictDialog) {
        ConflictResolutionDialog(
            syncClient = syncClient,
            conflictedFiles = conflictedFiles,
            onDismiss = { ui.showConflictDialog = false }
        )
    }

    if (ui.showCommandPalette) {
        val paletteSearchNodes by syncClient.paletteSearchNodes.collectAsState()
        val paletteActions = buildList {
            add(PaletteAction(if (focusMode) "Exit focus mode" else "Enter focus mode", "Ctrl+\\") { toggleFocusMode() })
            add(PaletteAction("Previous heading", "Alt+←") { ui.goBack() })
            add(PaletteAction("Next heading", "Alt+→") { ui.goForward() })
            add(PaletteAction("Open history", "Ctrl+1") { ui.openTabInDrawer(SidebarTab.HISTORY) })
            add(PaletteAction("Open outline", "Ctrl+2") { ui.openTabInDrawer(SidebarTab.OUTLINE) })
            add(PaletteAction("Open today's agenda", "Ctrl+3") { ui.openTabInDrawer(SidebarTab.AGENDA) })
            add(PaletteAction("Search notes", "Ctrl+F") { ui.openTabInDrawer(SidebarTab.SEARCH) })
            add(PaletteAction("Open sketch gallery", "Ctrl+5") { ui.openTabInDrawer(SidebarTab.GALLERY) })
            add(PaletteAction("New heading / capture", "Ctrl+N") { ui.showNewNodeDialog = true })
            if (activeSnapshot != null) {
                add(PaletteAction("Cycle TODO state", "Ctrl+T") { shortcutActions.cycleTodo() })
                add(PaletteAction(if (activeSnapshot?.header?.clockedIn == true) "Clock out" else "Clock in", "Ctrl+Shift+C") { shortcutActions.toggleClock() })
                add(PaletteAction("Flag as flashcard", "C-c w f") { shortcutActions.flagFlashcard() })
                add(PaletteAction("Refresh node from Emacs", "C-c w p") { shortcutActions.refresh() })
            }
            add(PaletteAction(if (leftHanded) "Right-handed layout" else "Left-handed layout") { UiPrefs.setLeftHanded(context, !leftHanded) })
            if (conflictedFiles.isNotEmpty()) {
                add(PaletteAction("Resolve sketch conflicts (${conflictedFiles.size})") { ui.showConflictDialog = true })
            }
            add(PaletteAction("Pair / connect to Emacs", "C-c w q") { ui.showPairingDialog = true })
            add(PaletteAction("Reconnect over ADB USB") { syncClient.connectAdbUsb() })
        }
        CommandPaletteDialog(
            actions = paletteActions,
            headings = bufferOutline + summaries + agendaNodes + paletteSearchNodes,
            onRemoteSearch = { syncClient.searchNodesForPalette(it) },
            onPickHeading = { syncClient.fetchNode(it.nodeId) },
            onDismiss = { ui.showCommandPalette = false }
        )
    }

    if (ui.showNewNodeDialog) {
        var newTitle by remember { mutableStateOf("") }
        var newTodo by remember { mutableStateOf("TODO") }
        var newMode by remember { mutableStateOf("child") }
        var includeBlankSketch by remember { mutableStateOf(false) }
        AlertDialog(
            onDismissRequest = { ui.showNewNodeDialog = false },
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
                        ui.showNewNodeDialog = false
                    }
                ) {
                    Text("Create")
                }
            },
            dismissButton = {
                TextButton(onClick = { ui.showNewNodeDialog = false }) {
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
