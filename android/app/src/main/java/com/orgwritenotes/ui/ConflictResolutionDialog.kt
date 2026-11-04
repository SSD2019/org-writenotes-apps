package com.orgwritenotes.ui

import android.graphics.Bitmap
import android.util.Base64
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.orgwritenotes.ink.OnyxEInkBridge
import com.orgwritenotes.ink.SketchMerge
import com.orgwritenotes.ink.WriteNativeBridge
import com.orgwritenotes.sync.EmacsSyncClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private class SketchPreview(val bitmap: Bitmap?, val strokes: Int)

private class LoadedConflict(
    val conflict: EmacsSyncClient.SketchConflict,
    val emacs: SketchPreview,
    val tablet: SketchPreview,
    val mergedBase64: String?,
    val mergeAdds: Int
)

private enum class CompareMode(val label: String) { SIDE_BY_SIDE("Side by side"), OVERLAY("Overlay"), FLIP("Flip") }

/** Renders a `.svgz` (base64) to a bitmap at most [maxWidth] px wide, with its stroke count. */
private fun renderPreview(base64: String, maxWidth: Int): SketchPreview {
    if (!WriteNativeBridge.isNativeLoaded || base64.isEmpty()) return SketchPreview(null, 0)
    val bytes = runCatching { Base64.decode(base64, Base64.DEFAULT) }.getOrNull() ?: return SketchPreview(null, 0)
    val handle = WriteNativeBridge.nativeCreateSession(1200, 600, 0, 40)
    if (handle == 0L) return SketchPreview(null, 0)
    try {
        if (!WriteNativeBridge.nativeLoadSvgz(handle, bytes)) return SketchPreview(null, 0)
        val info = IntArray(10)
        WriteNativeBridge.nativeGetPageInfo(handle, info)
        val w = info[0].coerceAtLeast(1)
        // Cap the full-size render (~24 MP); very tall sketches preview their top part.
        val h = info[1].coerceIn(1, 24_000_000 / w)
        val full = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        WriteNativeBridge.nativeRenderToBitmap(handle, full)
        val outW = minOf(maxWidth, w)
        val scaled = if (outW == w) full else Bitmap.createScaledBitmap(full, outW, (h.toLong() * outW / w).toInt().coerceAtLeast(1), true)
        if (scaled !== full) full.recycle()
        return SketchPreview(scaled, info[7])
    } finally {
        WriteNativeBridge.nativeDestroySession(handle)
    }
}

/**
 * Reviews sketches whose tablet edits conflicted with a newer copy in Emacs: shows both versions
 * side by side, overlaid, or flipping between them, then keeps one or merges every stroke.
 */
@Composable
internal fun ConflictResolutionDialog(
    syncClient: EmacsSyncClient,
    conflictedFiles: Set<String>,
    onDismiss: () -> Unit
) {
    val isEInk = OnyxEInkBridge.isEInkDevice
    val files = conflictedFiles.sorted()
    var current by remember { mutableStateOf(files.firstOrNull()) }
    if (current !in files) current = files.firstOrNull()
    var loaded by remember { mutableStateOf<LoadedConflict?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var mode by remember { mutableStateOf(if (isEInk) CompareMode.FLIP else CompareMode.SIDE_BY_SIDE) }
    var flipShowsTablet by remember { mutableStateOf(true) }
    var reloadKey by remember { mutableIntStateOf(0) }

    // Last conflict resolved: close.
    LaunchedEffect(files.isEmpty()) { if (files.isEmpty()) onDismiss() }

    LaunchedEffect(current, reloadKey) {
        loaded = null
        error = null
        val file = current ?: return@LaunchedEffect
        var info: EmacsSyncClient.SketchConflict? = null
        var err: String? = null
        kotlinx.coroutines.suspendCancellableCoroutine { cont ->
            syncClient.fetchConflictInfo(file) { c, e ->
                info = c
                err = e
                if (cont.isActive) cont.resumeWith(Result.success(Unit))
            }
        }
        val c = info
        if (c == null) {
            error = err ?: "Could not load conflict"
            return@LaunchedEffect
        }
        loaded = withContext(Dispatchers.Default) {
            val emacsPreview = renderPreview(c.emacsSvgzBase64, 1100)
            val tabletPreview = renderPreview(c.tabletSvgzBase64, 1100)
            val emacsBytes = runCatching { Base64.decode(c.emacsSvgzBase64, Base64.DEFAULT) }.getOrNull()
            val tabletBytes = runCatching { Base64.decode(c.tabletSvgzBase64, Base64.DEFAULT) }.getOrNull()
            val mergeResult = if (emacsBytes != null && tabletBytes != null) {
                val a = SketchMerge.gunzip(emacsBytes)
                val b = SketchMerge.gunzip(tabletBytes)
                if (a != null && b != null) SketchMerge.mergeSvg(a, b) else null
            } else {
                null
            }
            LoadedConflict(
                conflict = c,
                emacs = emacsPreview,
                tablet = tabletPreview,
                mergedBase64 = mergeResult?.let { Base64.encodeToString(SketchMerge.gzip(it.svg), Base64.NO_WRAP) },
                mergeAdds = mergeResult?.addedStrokes ?: 0
            )
        }
    }

    fun resolve(choice: String) {
        val l = loaded ?: return
        busy = true
        syncClient.resolveConflict(l.conflict, choice, if (choice == "merged") l.mergedBase64 else null) { e ->
            busy = false
            // On success the file leaves the conflict set (moving on to the next one) unless older
            // conflict copies remain; reloading covers both.
            if (e != null) error = e else reloadKey++
        }
    }

    val bg = if (isEInk) Color.White else Color(0xFF0F172A)
    val fg = if (isEInk) Color.Black else Color(0xFFF1F5F9)
    val muted = if (isEInk) Color(0xFF334155) else Color(0xFF94A3B8)

    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(bg)
                .padding(16.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Resolve sketch conflicts", color = fg, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                Spacer(modifier = Modifier.weight(1f))
                ActionChip("Later", isEInk, primary = false, onClick = onDismiss)
            }
            Text(
                "Emacs had a newer copy when the tablet saved. Pick a version, or merge to keep every stroke " +
                    "(strokes erased on one side come back). The replaced copy is kept in .history/.",
                color = muted,
                fontSize = 12.sp,
                modifier = Modifier.padding(top = 4.dp, bottom = 8.dp)
            )
            if (files.size > 1) {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.horizontalScroll(rememberScrollState())) {
                    files.forEach { f -> ActionChip(f, isEInk, primary = f == current, onClick = { current = f }) }
                }
                Spacer(modifier = Modifier.height(8.dp))
            }
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                CompareMode.values().forEach { m -> ActionChip(m.label, isEInk, primary = mode == m, onClick = { mode = m }) }
                if (mode == CompareMode.FLIP) {
                    Spacer(modifier = Modifier.width(8.dp))
                    ActionChip(if (flipShowsTablet) "Showing: Tablet ⇄" else "Showing: Emacs ⇄", isEInk, primary = false) {
                        flipShowsTablet = !flipShowsTablet
                    }
                }
            }
            Spacer(modifier = Modifier.height(8.dp))

            val l = loaded
            Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
                when {
                    error != null -> Text("⚠ $error", color = fg, fontSize = 14.sp)
                    l == null -> Text("Loading both versions…", color = muted, fontSize = 14.sp)
                    else -> Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                        when (mode) {
                            CompareMode.SIDE_BY_SIDE -> Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                PreviewPane("Emacs (on disk) · ${l.emacs.strokes} strokes", l.emacs.bitmap, fg, isEInk, Modifier.weight(1f))
                                PreviewPane("Tablet (your copy) · ${l.tablet.strokes} strokes", l.tablet.bitmap, fg, isEInk, Modifier.weight(1f))
                            }
                            CompareMode.OVERLAY -> Column {
                                Text("Emacs underneath, tablet on top at half opacity", color = fg, fontSize = 12.sp)
                                Box(modifier = Modifier.fillMaxWidth().border(1.dp, if (isEInk) Color.Black else Color(0xFF334155))) {
                                    l.emacs.bitmap?.let { Image(it.asImageBitmap(), null, contentScale = ContentScale.FillWidth, modifier = Modifier.fillMaxWidth()) }
                                    l.tablet.bitmap?.let { Image(it.asImageBitmap(), null, alpha = 0.5f, contentScale = ContentScale.FillWidth, modifier = Modifier.fillMaxWidth()) }
                                }
                            }
                            CompareMode.FLIP -> {
                                val showTablet = flipShowsTablet
                                PreviewPane(
                                    if (showTablet) "Tablet (your copy) · ${l.tablet.strokes} strokes" else "Emacs (on disk) · ${l.emacs.strokes} strokes",
                                    if (showTablet) l.tablet.bitmap else l.emacs.bitmap,
                                    fg,
                                    isEInk,
                                    Modifier.fillMaxWidth().clickable { flipShowsTablet = !flipShowsTablet }
                                )
                            }
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                val ready = loaded != null && !busy
                ActionChip("Keep Emacs version", isEInk, primary = false, enabled = ready) { resolve("emacs") }
                ActionChip("Keep tablet version", isEInk, primary = false, enabled = ready) { resolve("tablet") }
                ActionChip(
                    "Merge both (+${loaded?.mergeAdds ?: 0} strokes)",
                    isEInk,
                    primary = true,
                    enabled = ready && loaded?.mergedBase64 != null
                ) { resolve("merged") }
                if (busy) Text("Saving…", color = muted, fontSize = 12.sp)
            }
        }
    }
}

@Composable
private fun PreviewPane(title: String, bitmap: Bitmap?, fg: Color, isEInk: Boolean, modifier: Modifier) {
    Column(modifier = modifier) {
        Text(title, color = fg, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
        Spacer(modifier = Modifier.height(4.dp))
        Box(modifier = Modifier.fillMaxWidth().border(1.dp, if (isEInk) Color.Black else Color(0xFF334155))) {
            if (bitmap != null) {
                Image(bitmap.asImageBitmap(), contentDescription = title, contentScale = ContentScale.FillWidth, modifier = Modifier.fillMaxWidth())
            } else {
                Text("Preview unavailable", color = fg, fontSize = 12.sp, modifier = Modifier.padding(16.dp))
            }
        }
    }
}

@Composable
private fun ActionChip(label: String, isEInk: Boolean, primary: Boolean, enabled: Boolean = true, onClick: () -> Unit) {
    val bg = when {
        primary -> if (isEInk) Color.Black else Color(0xFF0284C7)
        else -> if (isEInk) Color.White else Color(0xFF1E293B)
    }
    val fg = when {
        !enabled -> if (isEInk) Color(0xFF64748B) else Color(0xFF64748B)
        primary -> Color.White
        else -> if (isEInk) Color.Black else Color(0xFFE2E8F0)
    }
    Text(
        text = label,
        color = fg,
        fontSize = 13.sp,
        fontWeight = FontWeight.SemiBold,
        modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .background(bg)
            .border(1.dp, if (isEInk) Color.Black else Color(0xFF334155), RoundedCornerShape(8.dp))
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 8.dp)
    )
}
