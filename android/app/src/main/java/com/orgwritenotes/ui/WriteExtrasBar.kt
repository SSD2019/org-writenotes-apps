package com.orgwritenotes.ui

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.os.Build
import android.provider.MediaStore
import android.util.Base64
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.clickable
import com.orgwritenotes.ink.AudioSession
import com.orgwritenotes.ink.AudioSketchRecorder
import com.orgwritenotes.ink.StrokeTuning
import com.orgwritenotes.ink.SvgLayers
import com.orgwritenotes.ink.WriteCanvasBlockView
import com.orgwritenotes.sync.EmacsSyncClient
import java.io.ByteArrayInputStream
import java.util.zip.GZIPInputStream

/**
 * Floating action strip for the zoomed sketch editor:
 *  - E6: export PNG / SVG, import `.svgz`
 *  - A2: sketch version history + revert
 *  - B6: smoothing / pressure curve / tilt
 * Every control carries a content description (B8).
 */
@Composable
fun WriteExtrasBar(
    canvas: WriteCanvasBlockView?,
    nodeId: String,
    fileName: String,
    syncClient: EmacsSyncClient,
    splitActive: Boolean = false,
    onToggleSplit: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    var showLayers by remember { mutableStateOf(false) }
    var showAudio by remember { mutableStateOf(false) }
    var menuOpen by remember { mutableStateOf(false) }
    var showTuning by remember { mutableStateOf(false) }
    var historyItems by remember { mutableStateOf<List<String>?>(null) }
    val largeSketches by syncClient.largeSketches.collectAsState()
    val presence by syncClient.presence.collectAsState()

    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        val view = canvas ?: return@rememberLauncherForActivityResult
        if (uri == null) return@rememberLauncherForActivityResult
        runCatching {
            val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() } ?: return@runCatching
            val b64 = Base64.encodeToString(bytes, Base64.NO_WRAP)
            view.loadFromBase64Svgz(b64)
            syncClient.flushPendingInkCommit(nodeId, fileName, view.canvasWidthPx, view.canvasHeightPx, b64)
            toast(context, "Imported sketch")
        }.onFailure { toast(context, "Import failed: ${it.message}") }
    }

    Row(
        modifier = modifier
            .clip(RoundedCornerShape(8.dp))
            .background(Color(0xD9222222))
            .padding(horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(2.dp)
    ) {
        if (fileName in largeSketches) {
            Text(
                text = "⚠ Large sketch",
                color = Color(0xFFFBBF24),
                fontSize = 11.sp,
                modifier = Modifier
                    .padding(horizontal = 6.dp)
                    .semantics { contentDescription = "Warning: this sketch is large and may render slowly" }
            )
        }
        if (presence.size > 1) {
            val names = presence.joinToString { it.name }
            Text(
                text = "👥 ${presence.size}",
                color = Color(0xFF38BDF8),
                fontSize = 12.sp,
                modifier = Modifier
                    .padding(horizontal = 6.dp)
                    .semantics { contentDescription = "${presence.size} clients connected: $names" }
            )
        }
        ExtrasChip(if (splitActive) "Unsplit" else "Split", "Toggle split view beside the sketch") { onToggleSplit() }
        ExtrasChip("Layers", "Template and highlight layers") { showLayers = true }
        ExtrasChip("Audio", "Record or play audio synced to strokes") { showAudio = true }
        ExtrasChip("Tune", "Pen smoothing and pressure settings") { showTuning = true }
        ExtrasChip("History", "Sketch version history") {
            syncClient.fetchSketchHistory(nodeId, fileName) { historyItems = it }
        }
        ExtrasChip("⋯", "Export and import menu") { menuOpen = true }
        DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
            DropdownMenuItem(
                text = { Text("Export PNG") },
                onClick = {
                    menuOpen = false
                    val bmp = canvas?.exportBitmap()
                    if (bmp == null) toast(context, "Nothing to export") else savePng(context, bmp, fileName)
                }
            )
            DropdownMenuItem(
                text = { Text("Export SVG") },
                onClick = {
                    menuOpen = false
                    canvas?.exportSvgzBase64Async { b64 -> saveSvg(context, b64, fileName) }
                }
            )
            DropdownMenuItem(
                text = { Text("Import .svgz") },
                onClick = {
                    menuOpen = false
                    importLauncher.launch("*/*")
                }
            )
        }
    }

    if (showTuning) {
        TuningDialog(context) { showTuning = false }
    }
    if (showLayers) {
        LayersDialog(canvas, nodeId, fileName, syncClient) { showLayers = false }
    }
    if (showAudio) {
        AudioDialog(fileName) { showAudio = false }
    }

    historyItems?.let { items ->
        AlertDialog(
            onDismissRequest = { historyItems = null },
            title = { Text("Sketch history") },
            text = {
                Column {
                    if (items.isEmpty()) Text("No earlier versions yet.")
                    items.take(20).forEach { snap ->
                        Text(
                            text = snap,
                            fontSize = 13.sp,
                            modifier = Modifier
                                .clickable {
                                    historyItems = null
                                    syncClient.revertSketch(nodeId, fileName, snap) { b64 ->
                                        if (b64.isNotEmpty()) canvas?.post { canvas.loadFromBase64Svgz(b64) }
                                    }
                                }
                                .padding(vertical = 8.dp)
                                .semantics { contentDescription = "Restore version $snap" }
                        )
                    }
                }
            },
            confirmButton = { TextButton(onClick = { historyItems = null }) { Text("Close") } }
        )
    }
}

@Composable
private fun ExtrasChip(label: String, description: String, onClick: () -> Unit) {
    Text(
        text = label,
        color = Color(0xFFF8FAFC),
        fontSize = 12.sp,
        fontWeight = FontWeight.SemiBold,
        modifier = Modifier
            .clickable(onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 8.dp)
            .semantics { contentDescription = description }
    )
}

@Composable
private fun TuningDialog(context: Context, onDismiss: () -> Unit) {
    var gamma by remember { mutableStateOf(StrokeTuning.pressureGamma) }
    var smoothing by remember { mutableStateOf(StrokeTuning.smoothing) }
    var tilt by remember { mutableStateOf(StrokeTuning.tiltEnabled) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Pen tuning") },
        text = {
            Column {
                Text("Pressure curve: %.2f (low = softer)".format(gamma), fontSize = 13.sp)
                Slider(
                    value = gamma,
                    onValueChange = { gamma = it; StrokeTuning.pressureGamma = it },
                    valueRange = 0.5f..2.0f,
                    modifier = Modifier.semantics { contentDescription = "Pressure curve" }
                )
                Text("Smoothing: %.0f%%".format(smoothing / 0.85f * 100), fontSize = 13.sp)
                Slider(
                    value = smoothing,
                    onValueChange = { smoothing = it; StrokeTuning.smoothing = it },
                    valueRange = 0f..0.85f,
                    modifier = Modifier.semantics { contentDescription = "Stroke smoothing" }
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Use stylus tilt", fontSize = 13.sp, modifier = Modifier.weight(1f))
                    Switch(checked = tilt, onCheckedChange = { tilt = it; StrokeTuning.tiltEnabled = it })
                }
                Text("App text size: %.0f%%".format(UiPrefs.fontMultiplier * 100), fontSize = 13.sp)
                Slider(
                    value = UiPrefs.fontMultiplier,
                    onValueChange = { UiPrefs.set(context, it) },
                    valueRange = UiPrefs.MIN..UiPrefs.MAX,
                    modifier = Modifier.semantics { contentDescription = "App text size" }
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { StrokeTuning.save(context); onDismiss() }) { Text("Save") }
        },
        dismissButton = {
            TextButton(onClick = {
                gamma = 1f; smoothing = 0f; tilt = true
                StrokeTuning.pressureGamma = 1f; StrokeTuning.smoothing = 0f; StrokeTuning.tiltEnabled = true
            }) { Text("Reset") }
        }
    )
}

private fun toast(context: Context, msg: String) =
    Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()

private fun baseName(fileName: String) = fileName.substringBeforeLast('.').ifEmpty { "sketch" }

private fun savePng(context: Context, bmp: Bitmap, fileName: String) {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
        toast(context, "PNG export needs Android 10+")
        return
    }
    runCatching {
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, baseName(fileName) + ".png")
            put(MediaStore.Images.Media.MIME_TYPE, "image/png")
            put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/OrgWriteNotes")
        }
        val uri = context.contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
            ?: error("MediaStore insert failed")
        context.contentResolver.openOutputStream(uri)?.use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
        toast(context, "Saved to Pictures/OrgWriteNotes")
    }.onFailure { toast(context, "PNG export failed: ${it.message}") }
}

private fun saveSvg(context: Context, svgzBase64: String, fileName: String) {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
        toast(context, "SVG export needs Android 10+")
        return
    }
    if (svgzBase64.isEmpty()) {
        toast(context, "Nothing to export")
        return
    }
    runCatching {
        val gz = Base64.decode(svgzBase64, Base64.DEFAULT)
        val svg = GZIPInputStream(ByteArrayInputStream(gz)).use { it.readBytes() }
        val values = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, baseName(fileName) + ".svg")
            put(MediaStore.Downloads.MIME_TYPE, "image/svg+xml")
            put(MediaStore.Downloads.RELATIVE_PATH, "Download/OrgWriteNotes")
        }
        val uri = context.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
            ?: error("MediaStore insert failed")
        context.contentResolver.openOutputStream(uri)?.use { it.write(svg) }
        toast(context, "Saved to Download/OrgWriteNotes")
    }.onFailure { toast(context, "SVG export failed: ${it.message}") }
}

/** B4: template layer (native lock/unlock) and highlight layer (visibility). Ink is always visible. */
@Composable
private fun LayersDialog(
    canvas: WriteCanvasBlockView?,
    nodeId: String,
    fileName: String,
    syncClient: EmacsSyncClient,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    var highlightsVisible by remember { mutableStateOf(true) }
    var opacity by remember { mutableStateOf(1f) }

    fun applyHighlights(visible: Boolean) {
        val view = canvas ?: return
        view.exportSvgzBase64Async { b64 ->
            runCatching {
                if (b64.isEmpty()) return@runCatching
                val svg = SvgLayers.gunzipBase64(b64)
                val n = SvgLayers.countHighlights(svg)
                if (n == 0) {
                    toast(context, "No highlighter strokes on this sketch")
                    highlightsVisible = true
                    return@runCatching
                }
                val updated = SvgLayers.toggleHighlightsBase64(b64, hidden = !visible)
                view.loadFromBase64Svgz(updated)
                syncClient.flushPendingInkCommit(nodeId, fileName, view.canvasWidthPx, view.canvasHeightPx, updated)
                highlightsVisible = visible
            }.onFailure { toast(context, "Layer change failed: ${it.message}") }
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Layers") },
        text = {
            Column {
                Text("Template", fontWeight = FontWeight.Bold, fontSize = 13.sp)
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    TextButton(onClick = {
                        if (canvas?.lockCurrentDrawingAsTemplate() == true) toast(context, "Ink locked as template")
                    }) { Text("Lock ink") }
                    TextButton(onClick = {
                        if (canvas?.unlockTemplateBackgroundToEdit() == true) toast(context, "Template unlocked")
                    }) { Text("Unlock") }
                    TextButton(onClick = { canvas?.clearCustomTemplateBackground() }) { Text("Clear") }
                }
                Text("Template image opacity", fontSize = 12.sp)
                Slider(
                    value = opacity,
                    onValueChange = { opacity = it; canvas?.setTemplateImageOpacity(it) },
                    valueRange = 0.1f..1f,
                    modifier = Modifier.semantics { contentDescription = "Template image opacity" }
                )
                Text("Ink", fontWeight = FontWeight.Bold, fontSize = 13.sp)
                Text("Always visible.", fontSize = 12.sp)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Highlights", fontWeight = FontWeight.Bold, fontSize = 13.sp, modifier = Modifier.weight(1f))
                    Switch(
                        checked = highlightsVisible,
                        onCheckedChange = { applyHighlights(it) },
                        modifier = Modifier.semantics { contentDescription = "Show highlighter strokes" }
                    )
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Done") } }
    )
}

/** E1: record audio while writing; playback can jump between the strokes made during the take. */
@Composable
private fun AudioDialog(fileName: String, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val recording by AudioSketchRecorder.recording.collectAsState()
    var sessions by remember { mutableStateOf(AudioSketchRecorder.sessions(context, fileName)) }
    var playing by remember { mutableStateOf<AudioSession?>(null) }

    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted && AudioSketchRecorder.start(context, fileName)) toast(context, "Recording — keep writing")
        else toast(context, "Microphone unavailable")
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Audio notes") },
        text = {
            Column {
                TextButton(onClick = {
                    if (recording) {
                        AudioSketchRecorder.stop(context)?.let { sessions = AudioSketchRecorder.sessions(context, fileName) }
                    } else if (context.checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) ==
                        android.content.pm.PackageManager.PERMISSION_GRANTED
                    ) {
                        if (!AudioSketchRecorder.start(context, fileName)) toast(context, "Could not start recording")
                    } else {
                        permission.launch(android.Manifest.permission.RECORD_AUDIO)
                    }
                }) { Text(if (recording) "■ Stop recording" else "● Record") }
                if (sessions.isEmpty()) Text("No recordings for this sketch yet.", fontSize = 12.sp)
                sessions.forEachIndexed { i, s ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            "Take ${i + 1} · ${s.durationMs / 1000}s · ${s.strokeOffsetsMs.size} strokes",
                            fontSize = 12.sp, modifier = Modifier.weight(1f)
                        )
                        TextButton(onClick = {
                            if (playing == s) { AudioSketchRecorder.stopPlayback(); playing = null }
                            else { AudioSketchRecorder.play(s) { playing = null }; playing = s }
                        }) { Text(if (playing == s) "Stop" else "Play") }
                        if (playing == s) {
                            TextButton(onClick = {
                                AudioSketchRecorder.seekTo(s.previousStroke(AudioSketchRecorder.positionMs()))
                            }) { Text("⏮") }
                            TextButton(onClick = {
                                s.nextStroke(AudioSketchRecorder.positionMs())?.let { AudioSketchRecorder.seekTo(it) }
                            }) { Text("⏭") }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { AudioSketchRecorder.stopPlayback(); onDismiss() }) { Text("Close") }
        }
    )
}
