package com.orgwritenotes.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.orgwritenotes.ink.OnyxEInkBridge
import com.orgwritenotes.sync.ConnectionState

private val RAIL_TABS = listOf(
    SidebarTab.HISTORY to "Hist",
    SidebarTab.OUTLINE to "Outl",
    SidebarTab.AGENDA to "Agda",
    SidebarTab.SEARCH to "Find",
    SidebarTab.GALLERY to "Gal"
)

/**
 * Focus mode's narrow icon rail, replacing the docked sidebar. Each tab button opens that tab in the
 * overlay drawer; ‹ / › walk the heading history (as do one-finger swipes on the editor).
 */
@Composable
internal fun FocusRail(
    ui: TabletUiState,
    connectionState: ConnectionState,
    onExitFocus: () -> Unit
) {
    val isEInk = OnyxEInkBridge.isEInkDevice
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(6.dp),
        modifier = Modifier
            .width(56.dp)
            .fillMaxHeight()
            .background(if (isEInk) Color.White else Color(0xFF1E293B))
            .then(if (isEInk) Modifier.border(1.dp, Color.Black) else Modifier)
            .padding(vertical = 10.dp)
    ) {
        RailButton(label = "⤢", description = "Exit focus mode", onClick = onExitFocus)
        Spacer(modifier = Modifier.height(4.dp))
        RAIL_TABS.forEach { (tab, label) ->
            RailButton(
                label = label,
                description = "Open $label",
                selected = ui.isDrawerOpen && ui.activeTab == tab,
                onClick = { ui.openTabInDrawer(tab) }
            )
        }
        Spacer(modifier = Modifier.height(4.dp))
        RailButton(label = "‹", description = "Previous heading", enabled = ui.canGoBack, onClick = { ui.goBack() })
        RailButton(label = "›", description = "Next heading", enabled = ui.canGoForward, onClick = { ui.goForward() })
        RailButton(label = "⌘", description = "Command palette", onClick = { ui.showCommandPalette = true })
        RailButton(label = "＋", description = "New heading", onClick = { ui.showNewNodeDialog = true })
        val conflicts = syncConflictCount.intValue
        if (conflicts > 0) {
            RailButton(label = "⚠$conflicts", description = "Resolve $conflicts sketch conflicts", selected = true, onClick = { ui.showConflictDialog = true })
        }
        Spacer(modifier = Modifier.weight(1f))
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .size(40.dp)
                .clip(RoundedCornerShape(8.dp))
                .clickable { ui.showPairingDialog = true }
                .semantics { contentDescription = "Connection status" }
        ) {
            ConnectionDot(state = connectionState, size = 12.dp)
        }
    }
}

@Composable
private fun RailButton(
    label: String,
    description: String,
    selected: Boolean = false,
    enabled: Boolean = true,
    onClick: () -> Unit
) {
    val isEInk = OnyxEInkBridge.isEInkDevice
    val bg = when {
        selected -> if (isEInk) Color.Black else Color(0xFF0284C7)
        else -> if (isEInk) Color.White else Color(0xFF0F172A)
    }
    val fg = when {
        selected -> Color.White
        !enabled -> if (isEInk) Color(0xFF94A3B8) else Color(0xFF475569)
        else -> if (isEInk) Color.Black else Color(0xFFE2E8F0)
    }
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .size(width = 44.dp, height = 36.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(bg)
            .then(if (isEInk) Modifier.border(1.dp, if (enabled) Color.Black else Color(0xFF94A3B8), RoundedCornerShape(8.dp)) else Modifier)
            .clickable(enabled = enabled, onClick = onClick)
            .semantics { contentDescription = description }
    ) {
        Text(
            text = label,
            color = fg,
            fontSize = if (label.length > 1) 10.sp else 16.sp,
            fontWeight = FontWeight.Bold
        )
    }
}
