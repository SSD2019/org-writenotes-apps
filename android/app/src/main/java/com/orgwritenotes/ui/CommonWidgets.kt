package com.orgwritenotes.ui

import androidx.compose.ui.unit.max
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.foundation.Canvas
import androidx.compose.ui.platform.LocalContext
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
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

// Small shared widgets and dialogs: TODO badge, empty-editor placeholder, pairing dialog.

@Composable
internal fun TodoBadge(keyword: String) {
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
internal fun EmptyEditorPlaceholder(
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
                ConnectionDot(state = connectionState, size = 8.dp)
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
internal fun PairingModalDialog(
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

/**
 * Connection status dot. On E-Ink (grayscale) the state is told apart by shape, not color:
 * filled = connected, ring with a center dot = connecting, hollow ring = offline / disconnected,
 * ring with a cross = error.
 */
@Composable
internal fun ConnectionDot(state: ConnectionState, size: Dp) {
    if (!OnyxEInkBridge.isEInkDevice) {
        Box(
            modifier = Modifier
                .size(size)
                .clip(CircleShape)
                .background(connectionDotColor(state))
        )
        return
    }
    val d = max(size, 11.dp)
    Canvas(modifier = Modifier.size(d)) {
        val r = this.size.minDimension / 2f
        val stroke = Stroke(width = r * 0.35f)
        when (state) {
            ConnectionState.CONNECTED_ADB, ConnectionState.CONNECTED_NETWORK ->
                drawCircle(Color.Black, radius = r)
            ConnectionState.CONNECTING -> {
                drawCircle(Color.Black, radius = r - stroke.width / 2f, style = stroke)
                drawCircle(Color.Black, radius = r * 0.3f)
            }
            ConnectionState.ERROR -> {
                drawCircle(Color.Black, radius = r - stroke.width / 2f, style = stroke)
                val k = r * 0.45f
                drawLine(Color.Black, center.copy(x = center.x - k, y = center.y - k), center.copy(x = center.x + k, y = center.y + k), strokeWidth = stroke.width * 0.8f)
                drawLine(Color.Black, center.copy(x = center.x - k, y = center.y + k), center.copy(x = center.x + k, y = center.y - k), strokeWidth = stroke.width * 0.8f)
            }
            else -> drawCircle(Color.Black, radius = r - stroke.width / 2f, style = stroke)
        }
    }
}
