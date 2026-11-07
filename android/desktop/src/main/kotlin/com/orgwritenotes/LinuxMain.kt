@file:Suppress("ktlint")

package com.orgwritenotes

import android.content.Context
import android.content.res.Configuration
import android.util.Base64
import android.view.KeyEvent
import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isAltPressed
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.viewinterop.mapComposeKeyToAndroidKeyCode
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.WindowPosition
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import com.orgwritenotes.ink.LinuxStylusBridge
import com.orgwritenotes.ink.OnyxEInkBridge
import com.orgwritenotes.ink.StrokeClipboard
import com.orgwritenotes.ink.StrokeTuning
import com.orgwritenotes.ink.StylusToolMode
import com.orgwritenotes.ink.WriteCanvasBlockView
import com.orgwritenotes.ink.WriteNativeBridge
import com.orgwritenotes.ink.WritePenTip
import com.orgwritenotes.ink.WriteTemplateStyle
import com.orgwritenotes.sync.ConnectionState
import com.orgwritenotes.sync.EmacsSyncClient
import com.orgwritenotes.ui.OrgWriteNotesTabletScreen
import com.orgwritenotes.ui.TabletKeyDispatcher
import com.orgwritenotes.ui.UiPrefs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import java.io.File
import kotlin.system.exitProcess

data class LinuxCliConfig(
    val sketchPath: String? = null,
    val nodeId: String? = null,
    val orgFilePath: String? = null,
    val wsUrl: String = System.getenv("ORG_WRITENOTES_URL") ?: "ws://127.0.0.1:9876",
    val token: String = System.getenv("ORG_WRITENOTES_TOKEN") ?: "",
    val einkMode: Boolean = System.getenv("ORG_WRITENOTES_EINK") == "1",
    val printDevicesAndExit: Boolean = false,
    val showHelp: Boolean = false
) {
    val isPopupSketchMode: Boolean
        get() = !sketchPath.isNullOrBlank()
}

fun parseLinuxCliArgs(args: Array<String>): LinuxCliConfig {
    var sketchPath: String? = null
    var nodeId: String? = null
    var orgFilePath: String? = null
    var wsUrl: String = System.getenv("ORG_WRITENOTES_URL") ?: "ws://127.0.0.1:9876"
    var token: String = System.getenv("ORG_WRITENOTES_TOKEN") ?: ""
    var einkMode: Boolean = System.getenv("ORG_WRITENOTES_EINK") == "1"
    var printDevices = false
    var showHelp = false

    var i = 0
    while (i < args.size) {
        when (val arg = args[i]) {
            "--sketch", "-s" -> {
                sketchPath = args.getOrNull(++i)
            }
            "--node", "-n" -> {
                nodeId = args.getOrNull(++i)
            }
            "--org-file", "-f" -> {
                orgFilePath = args.getOrNull(++i)
            }
            "--url", "-u" -> {
                wsUrl = args.getOrNull(++i) ?: wsUrl
            }
            "--token", "-t" -> {
                token = args.getOrNull(++i) ?: token
            }
            "--eink" -> {
                einkMode = true
            }
            "--devices", "--list-devices" -> {
                printDevices = true
            }
            "--help", "-h" -> {
                showHelp = true
            }
            else -> {
                if (arg.startsWith("org-writenotes://")) {
                    val parsed = parsePairingUriCli(arg)
                    if (parsed != null) {
                        wsUrl = parsed.first
                        token = parsed.second
                    }
                } else if (arg.endsWith(".svgz") || arg.endsWith(".svg")) {
                    sketchPath = arg
                }
            }
        }
        i++
    }
    return LinuxCliConfig(
        sketchPath = sketchPath,
        nodeId = nodeId,
        orgFilePath = orgFilePath,
        wsUrl = wsUrl,
        token = token,
        einkMode = einkMode,
        printDevicesAndExit = printDevices,
        showHelp = showHelp
    )
}

private fun parsePairingUriCli(raw: String): Pair<String, String>? {
    if (!raw.startsWith("org-writenotes://")) return null
    val queryIdx = raw.indexOf('?')
    if (queryIdx < 0) return null
    val params = raw.substring(queryIdx + 1).split('&').associate { part ->
        val eq = part.indexOf('=')
        if (eq >= 0) part.substring(0, eq) to part.substring(eq + 1) else part to ""
    }
    val url = java.net.URLDecoder.decode(params["url"] ?: return null, "UTF-8")
    val token = java.net.URLDecoder.decode(params["token"] ?: "", "UTF-8")
    return url to token
}

@OptIn(ExperimentalComposeUiApi::class)
fun main(args: Array<String>) {
    val cli = parseLinuxCliArgs(args)
    if (cli.showHelp) {
        println(
            """
            Org-WriteNotes for Linux (Stylus Labs Write C++ Engine + Compose Desktop)
            Usage:
              org-writenotes-linux [options] [sketch.svgz]

            Modes:
              1. Full Companion App (default):
                 Opens the full 5-tab sidebar + Org block editor + inline/zoomed sketches
                 and auto-connects to local or remote Emacs (default: ws://127.0.0.1:9876).

              2. Quick Single-Sketch Popup Mode (--sketch <file.svgz>):
                 Opens a single .svgz/.svg sketch directly in full-screen zoomed stylus mode.
                 Saves directly to disk (gzipped .svgz or plain .svg) and syncs live with
                 Emacs over WebSocket when connected.

            Options:
              --sketch, -s <path>     Open <path> (.svgz or .svg) in Quick Single-Sketch Popup Mode
              --node, -n <nodeId>     Sketch node ID for live Emacs WebSocket sync
              --org-file, -f <path>   Parent .org file path for live Emacs WebSocket sync
              --url, -u <ws://...>    Emacs WebSocket URL (default: ws://127.0.0.1:9876)
              --token, -t <token>     Emacs authentication token (optional on localhost)
              --eink                  Enable high-contrast E-Ink display theme
              --devices               Print detected Linux XInput2 / evdev stylus & touch devices
              --help, -h              Show this help message
            """.trimIndent()
        )
        return
    }

    if (cli.einkMode) {
        OnyxEInkBridge.forceEInkMode = true
    }

    if (cli.printDevicesAndExit) {
        println("WriteNativeBridge ready: ${WriteNativeBridge.isNativeLoaded}")
        println("Detected Linux stylus/touch devices:\n${LinuxStylusBridge.getDetectedDevicesSummary()}")
        LinuxStylusBridge.shutdown()
        return
    }

    val context = Context.instance
    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    StrokeTuning.load(context)
    UiPrefs.load(context)
    StrokeClipboard.load(context)

    val syncClient = EmacsSyncClient(appScope, context)
    if (cli.token.isNotBlank() || (cli.wsUrl != "ws://127.0.0.1:9876" && cli.wsUrl.isNotBlank())) {
        syncClient.connectNetwork(wsUrlOrQrPayload = cli.wsUrl, token = cli.token.ifBlank { null })
    } else {
        syncClient.connectAdbUsb(port = 9876)
    }

    application {
        val windowState = rememberWindowState(
            position = WindowPosition(Alignment.Center),
            size = if (cli.isPopupSketchMode) DpSize(1220.dp, 840.dp) else DpSize(1380.dp, 880.dp)
        )
        val windowTitle = if (cli.isPopupSketchMode) {
            "Org-WriteNotes Sketch — ${File(cli.sketchPath!!).name}"
        } else {
            "Org-WriteNotes (Linux)"
        }

        Window(
            onCloseRequest = {
                syncClient.flushAllPendingCommits()
                LinuxStylusBridge.shutdown()
                syncClient.shutdown()
                appScope.cancel()
                exitApplication()
            },
            state = windowState,
            title = windowTitle,
            onPreviewKeyEvent = { composeKey ->
                val isDown = composeKey.type == KeyEventType.KeyDown
                val code = mapComposeKeyToAndroidKeyCode(composeKey.key)
                if (code != null) {
                    val androidEv = KeyEvent(
                        action = if (isDown) KeyEvent.ACTION_DOWN else KeyEvent.ACTION_UP,
                        keyCode = code,
                        isCtrlPressed = composeKey.isCtrlPressed || composeKey.isMetaPressed,
                        isShiftPressed = composeKey.isShiftPressed,
                        isAltPressed = composeKey.isAltPressed,
                        isMetaPressed = composeKey.isMetaPressed
                    )
                    TabletKeyDispatcher.handler?.invoke(androidEv) == true
                } else {
                    false
                }
            }
        ) {
            val wDp = windowState.size.width.value.toInt().coerceAtLeast(640)
            val hDp = windowState.size.height.value.toInt().coerceAtLeast(480)
            val configuration = remember(wDp, hDp) {
                Configuration(
                    screenWidthDp = wDp,
                    screenHeightDp = hDp,
                    densityDpi = 160,
                    orientation = if (wDp >= hDp) Configuration.ORIENTATION_LANDSCAPE else Configuration.ORIENTATION_PORTRAIT
                )
            }
            val baseDensity = LocalDensity.current
            val effectiveDensity = Density(baseDensity.density, baseDensity.fontScale * UiPrefs.fontMultiplier)
            val colorScheme = if (OnyxEInkBridge.isEInkDevice) {
                lightColorScheme(
                    primary = Color.Black,
                    onPrimary = Color.White,
                    secondary = Color(0xFF1E293B),
                    onSecondary = Color.White,
                    background = Color.White,
                    onBackground = Color.Black,
                    surface = Color(0xFFF8FAFC),
                    onSurface = Color.Black,
                    surfaceVariant = Color(0xFFF1F5F9),
                    onSurfaceVariant = Color(0xFF1E293B),
                    outline = Color.Black
                )
            } else {
                darkColorScheme()
            }

            CompositionLocalProvider(
                LocalContext provides context,
                LocalConfiguration provides configuration,
                LocalDensity provides effectiveDensity
            ) {
                MaterialTheme(colorScheme = colorScheme) {
                    Box(modifier = Modifier.fillMaxSize()) {
                        if (cli.isPopupSketchMode) {
                            LinuxPopupSketchEditorScreen(
                                cli = cli,
                                syncClient = syncClient,
                                onDoneAndClose = {
                                    syncClient.flushAllPendingCommits()
                                    LinuxStylusBridge.shutdown()
                                    syncClient.shutdown()
                                    appScope.cancel()
                                    exitApplication()
                                    exitProcess(0)
                                }
                            )
                        } else {
                            OrgWriteNotesTabletScreen(syncClient = syncClient)
                        }

                        // Global Toast overlay for Desktop
                        val toastMsg by Toast.currentToastMessage
                        AnimatedVisibility(
                            visible = toastMsg != null,
                            enter = fadeIn(),
                            exit = fadeOut(),
                            modifier = Modifier
                                .align(Alignment.BottomCenter)
                                .padding(bottom = 28.dp)
                        ) {
                            Surface(
                                shape = RoundedCornerShape(20.dp),
                                color = Color(0xE6202228),
                                tonalElevation = 6.dp,
                                shadowElevation = 8.dp
                            ) {
                                Text(
                                    text = toastMsg ?: "",
                                    color = Color.White,
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.Medium,
                                    modifier = Modifier.padding(horizontal = 18.dp, vertical = 10.dp)
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * Quick Single-Sketch Popup Mode (`--sketch <path.svgz>`):
 * Opens a single `.svgz` or `.svg` file directly in full-screen stylus mode with all
 * pen presets, pressure/tilt, lasso, ruler/shape snapping, and undo/redo, saving directly
 * to disk and syncing live to Emacs when connected.
 */
@Composable
fun LinuxPopupSketchEditorScreen(
    cli: LinuxCliConfig,
    syncClient: EmacsSyncClient,
    onDoneAndClose: () -> Unit
) {
    val sketchFile = remember(cli.sketchPath) { File(cli.sketchPath!!) }
    val nodeId = remember(cli.nodeId, cli.orgFilePath, sketchFile) {
        cli.nodeId?.takeIf { it.isNotBlank() }
            ?: cli.orgFilePath?.takeIf { it.isNotBlank() }?.let { "file:$it::1" }
            ?: ""
    }
    val connectionState by syncClient.connectionState.collectAsState()

    var initialSvgzBase64 by remember { mutableStateOf("") }
    var activeCanvasView by remember { mutableStateOf<WriteCanvasBlockView?>(null) }
    var activeMode by remember { mutableStateOf(StylusToolMode.PEN) }
    var activeColorArgb by remember { mutableIntStateOf(0xFF1A1A1A.toInt()) }
    var activeStrokeWidth by remember { mutableStateOf(2.6f) }
    var templateIndex by remember { mutableIntStateOf(0) }
    var lastSavedTimeText by remember { mutableStateOf("Ready") }

    val penPresets = remember {
        listOf(
            "Black" to 0xFF1A1A1A.toInt(),
            "Blue" to 0xFF1565C0.toInt(),
            "Red" to 0xFFC62828.toInt(),
            "Green" to 0xFF2E7D32.toInt(),
            "Amber" to 0xFFEF6C00.toInt()
        )
    }

    fun readSketchBase64FromDisk(file: File): String {
        if (!file.exists() || file.length() == 0L) return ""
        return try {
            val bytes = file.readBytes()
            Base64.encodeToString(bytes, Base64.NO_WRAP)
        } catch (_: Exception) {
            ""
        }
    }

    fun writeSketchBase64ToDiskAndSync(svgzBase64: String, w: Int, h: Int) {
        if (svgzBase64.isBlank()) return
        try {
            val bytes = Base64.decode(svgzBase64, Base64.DEFAULT)
            if (bytes.isEmpty()) return
            sketchFile.parentFile?.mkdirs()
            sketchFile.writeBytes(bytes)
            val isConnected = connectionState == ConnectionState.CONNECTED_ADB ||
                connectionState == ConnectionState.CONNECTED_NETWORK
            if (nodeId.isNotEmpty() && isConnected) {
                syncClient.flushPendingInkCommit(
                    nodeId = nodeId,
                    fileName = sketchFile.name,
                    canvasWidth = w,
                    canvasHeight = h,
                    svgzBase64 = svgzBase64
                )
            }
            val nowStr = java.time.LocalTime.now().withNano(0).toString()
            lastSavedTimeText = "Saved $nowStr"
        } catch (e: Exception) {
            lastSavedTimeText = "Save error: ${e.message}"
        }
    }

    LaunchedEffect(sketchFile) {
        initialSvgzBase64 = readSketchBase64FromDisk(sketchFile)
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
    ) {
        // Top Compact Stylus Toolbar for Popup Sketch Mode
        Surface(
            tonalElevation = 3.dp,
            shadowElevation = 2.dp,
            modifier = Modifier.fillMaxWidth()
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 14.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Edit,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(18.dp)
                    )
                    Text(
                        text = sketchFile.name,
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 14.sp,
                        fontFamily = FontFamily.Monospace
                    )
                    Text(
                        text = "• $lastSavedTimeText",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                // Pen color presets + Eraser + Lasso + Ruling
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    penPresets.forEach { (label, colorInt) ->
                        val selected = activeMode == StylusToolMode.PEN && activeColorArgb == colorInt
                        Button(
                            onClick = {
                                activeMode = StylusToolMode.PEN
                                activeColorArgb = colorInt
                                activeCanvasView?.configureTool(
                                    mode = StylusToolMode.PEN,
                                    colorArgb = colorInt,
                                    strokeWidth = activeStrokeWidth,
                                    tip = WritePenTip.ROUND
                                )
                            },
                            colors = ButtonDefaults.buttonColors(
                                containerColor = if (selected) {
                                    MaterialTheme.colorScheme.primaryContainer
                                } else {
                                    MaterialTheme.colorScheme.surfaceVariant
                                },
                                contentColor = if (selected) {
                                    MaterialTheme.colorScheme.onPrimaryContainer
                                } else {
                                    MaterialTheme.colorScheme.onSurfaceVariant
                                }
                            ),
                            shape = RoundedCornerShape(8.dp)
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(10.dp)
                                    .background(Color(colorInt), CircleShape)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(label, fontSize = 12.sp)
                        }
                    }

                    val isEraser = activeMode == StylusToolMode.ERASER || activeMode == StylusToolMode.ERASER_RULED
                    Button(
                        onClick = {
                            activeMode = if (isEraser) StylusToolMode.PEN else StylusToolMode.ERASER
                            activeCanvasView?.configureTool(
                                mode = activeMode,
                                colorArgb = activeColorArgb,
                                strokeWidth = activeStrokeWidth
                            )
                        },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = if (isEraser) {
                                MaterialTheme.colorScheme.errorContainer
                            } else {
                                MaterialTheme.colorScheme.surfaceVariant
                            },
                            contentColor = if (isEraser) {
                                MaterialTheme.colorScheme.onErrorContainer
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            }
                        ),
                        shape = RoundedCornerShape(8.dp)
                    ) {
                        Text("Eraser (E)", fontSize = 12.sp)
                    }

                    val isLasso = activeMode == StylusToolMode.SELECT_LASSO
                    Button(
                        onClick = {
                            activeMode = if (isLasso) StylusToolMode.PEN else StylusToolMode.SELECT_LASSO
                            activeCanvasView?.configureTool(
                                mode = activeMode,
                                colorArgb = activeColorArgb,
                                strokeWidth = activeStrokeWidth
                            )
                        },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = if (isLasso) {
                                MaterialTheme.colorScheme.tertiaryContainer
                            } else {
                                MaterialTheme.colorScheme.surfaceVariant
                            },
                            contentColor = if (isLasso) {
                                MaterialTheme.colorScheme.onTertiaryContainer
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            }
                        ),
                        shape = RoundedCornerShape(8.dp)
                    ) {
                        Text("Lasso (L)", fontSize = 12.sp)
                    }

                    val styles = WriteTemplateStyle.values()
                    Button(
                        onClick = {
                            templateIndex = (templateIndex + 1) % styles.size
                            val st = styles[templateIndex]
                            activeCanvasView?.let { cv ->
                                val cur = cv.queryPageSetupState()
                                cv.applyPageSetup(
                                    newWidth = cur.width,
                                    newHeight = cur.height,
                                    newXRuling = if (st == WriteTemplateStyle.STANDARD) 0 else 40,
                                    newYRuling = 40,
                                    newMarginLeft = cur.marginLeft,
                                    newPaperColorArgb = cur.paperColorArgb,
                                    newRuleColorArgb = cur.ruleColorArgb,
                                    newRulingStyle = st.nativeCode
                                )
                            }
                        },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.surfaceVariant,
                            contentColor = MaterialTheme.colorScheme.onSurfaceVariant
                        ),
                        shape = RoundedCornerShape(8.dp)
                    ) {
                        Text(styles[templateIndex].label.substringBefore(" "), fontSize = 12.sp)
                    }
                }

                // Undo / Redo / Save & Close
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Button(
                        onClick = {
                            activeCanvasView?.performUndo()
                        },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.surfaceVariant,
                            contentColor = MaterialTheme.colorScheme.onSurfaceVariant
                        ),
                        shape = RoundedCornerShape(8.dp)
                    ) {
                        Text("Undo", fontSize = 12.sp)
                    }

                    Button(
                        onClick = {
                            activeCanvasView?.performRedo()
                        },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.surfaceVariant,
                            contentColor = MaterialTheme.colorScheme.onSurfaceVariant
                        ),
                        shape = RoundedCornerShape(8.dp)
                    ) {
                        Text("Redo", fontSize = 12.sp)
                    }

                    Button(
                        onClick = {
                            activeCanvasView?.let { cv ->
                                val b64 = cv.exportSvgzBase64()
                                writeSketchBase64ToDiskAndSync(b64, cv.canvasWidthPx, cv.canvasHeightPx)
                            }
                            onDoneAndClose()
                        },
                        shape = RoundedCornerShape(8.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Check,
                            contentDescription = "Save & Close",
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Save & Close", fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                    }
                }
            }
        }

        // Full-Screen Write C++ Stylus Canvas
        Box(modifier = Modifier.fillMaxSize()) {
            AndroidView(
                modifier = Modifier.fillMaxSize(),
                factory = { ctx ->
                    WriteCanvasBlockView(
                        context = ctx,
                        initialWidth = 1200,
                        initialHeight = 800,
                        xRuling = 0,
                        yRuling = 40,
                        isZoomedMode = true,
                        onStrokeCommitted = { _, w, h, exportFn ->
                            val b64 = exportFn(true)
                            writeSketchBase64ToDiskAndSync(b64, w, h)
                        },
                        onHeightExpanded = { }
                    ).apply {
                        configureTool(
                            mode = activeMode,
                            colorArgb = activeColorArgb,
                            strokeWidth = activeStrokeWidth,
                            tip = WritePenTip.ROUND
                        )
                        if (initialSvgzBase64.isNotBlank()) {
                            loadFromBase64Svgz(initialSvgzBase64)
                        }
                        activeCanvasView = this
                    }
                },
                update = { view ->
                    activeCanvasView = view
                }
            )
        }
    }
}
