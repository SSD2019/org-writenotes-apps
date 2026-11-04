package com.orgwritenotes.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.orgwritenotes.ink.OnyxEInkBridge
import com.orgwritenotes.model.*
import com.orgwritenotes.sync.ConnectionState

// Left sidebar: connection pill, History/Outline/Agenda/Search/Gallery tabs and node rows.

@Composable
internal fun RecentNodesSidebarContent(
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
    onOpenConflicts: () -> Unit = {},
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

        val conflicts = syncConflictCount.intValue
        if (conflicts > 0) {
            Text(
                text = "⚠ $conflicts sketch conflict${if (conflicts > 1) "s" else ""} · Review",
                color = if (isEInk) Color.White else Color(0xFF1E293B),
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(8.dp))
                    .background(if (isEInk) Color.Black else Color(0xFFFBBF24))
                    .clickable { onOpenConflicts() }
                    .padding(horizontal = 12.dp, vertical = 8.dp)
            )
            Spacer(modifier = Modifier.height(10.dp))
        }

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
                            if (isEInk) (if (active) Color.Black else Color.Transparent)
                            else (if (active) Color(0xFF334155) else Color.Transparent)
                        )
                        .then(if (isEInk && active) Modifier.border(1.dp, Color.Black, RoundedCornerShape(6.dp)) else Modifier)
                        .clickable { onTabChange(idx) }
                        .padding(vertical = 6.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = label,
                        color = if (isEInk) (if (active) Color.White else Color.Black) else (if (active) Color.White else Color(0xFF94A3B8)),
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
                        2 -> "Filter today's agenda..."
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
                    2 -> "AGENDA / TODAY (${filteredSummaries.size})"
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
                text = if (searchQuery.isNotBlank() && activeTab != 3) {
                    "No matches for \"${searchQuery.trim()}\" — clear the filter above to see everything."
                } else when (activeTab) {
                    1 -> "No outline headings found in active buffer."
                    2 -> "Nothing on today's Emacs agenda."
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
internal fun GalleryItemCard(
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
internal val syncConflictCount = mutableIntStateOf(0)

@Composable
internal fun ConnectionStatusPill(
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
        ConnectionDot(state = state, size = 10.dp)
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
internal fun SidebarNodeRow(
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
                if (isEInk && isSelected) Modifier.border(2.5.dp, Color.Black, RoundedCornerShape(8.dp))
                else if (isEInk) Modifier.border(1.dp, Color(0xFF64748B), RoundedCornerShape(8.dp))
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
        if (showAgendaDates && (summary.agendaLabel != null || summary.scheduled != null || summary.deadline != null)) {
            Spacer(modifier = Modifier.height(3.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                summary.agendaLabel?.let {
                    Text(
                        text = it,
                        color = if (isEInk) Color.Black else Color(0xFFFBBF24),
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold,
                        fontFamily = FontFamily.Monospace
                    )
                }
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
