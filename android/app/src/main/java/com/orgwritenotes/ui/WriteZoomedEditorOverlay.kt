package com.orgwritenotes.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.orgwritenotes.ink.StylusToolMode
import com.orgwritenotes.ink.WriteCanvasBlockView
import com.orgwritenotes.ink.WriteNativeBridge
import com.orgwritenotes.ink.WritePageSetupState
import com.orgwritenotes.ink.WritePenTip
import com.orgwritenotes.ink.WriteSelectionState
import com.orgwritenotes.ink.WriteTemplateStyle
import com.orgwritenotes.model.OrgBlock
import com.orgwritenotes.sync.EmacsSyncClient
import kotlin.math.roundToInt

/**
 * Vector icons extracted directly from Stylus Labs Write (`android/cpp/Write/scribbleres/icons/`).
 */
enum class WriteIconAsset(
    val viewBoxW: Float,
    val viewBoxH: Float,
    val pathData: List<String>,
    val isLassoIcon: Boolean = false,
    val isExpandDownIcon: Boolean = false,
    val isExpandRightIcon: Boolean = false
) {
    BACK(
        96f, 96f,
        listOf("M46.031,79.76 L51.531,74.258 L29.162,51.885 L81.73,51.885 L81.73,44.11 L29.162,44.11 L51.531,21.739 L46.031,16.24 L14.27,47.998 Z")
    ),
    PAN(
        32f, 32f,
        listOf("M27 9.5V24c0 2.2-1.8 4-4 4h-7.3c-1.08 0-2.1-.43-2.85-1.19L5 18.83s1.26-1.23 1.3-1.25c.22-.19.49-.29.79-.29.22 0 .42.06.6.16.04.01 4.31 2.46 4.31 2.46V8c0-.83.67-1.5 1.5-1.5S15 7.17 15 8v7h1V5.5c0-.83.67-1.5 1.5-1.5S19 4.67 19 5.5V15h1V6.5c0-.83.67-1.5 1.5-1.5s1.5.67 1.5 1.5V15h1V9.5c0-.83.67-1.5 1.5-1.5s1.5.67 1.5 1.5z")
    ),
    DRAW(
        24f, 24f,
        listOf("M3 17.25V21h3.75L17.81 9.94l-3.75-3.75L3 17.25z M20.71 7.04c.39-.39.39-1.02 0-1.41l-2.34-2.34c-.39-.39-1.02-.39-1.41 0l-1.83 1.83 3.75 3.75 1.83-1.83z")
    ),
    SET_PEN(
        24f, 24f,
        listOf(
            "M3 17.25V21h3.75L17.81 9.94l-3.75-3.75L3 17.25z M20.71 7.04c.39-.39.39-1.02 0-1.41l-2.34-2.34c-.39-.39-1.02-.39-1.41 0l-1.83 1.83 3.75 3.75 1.83-1.83z",
            "M16.5 15.5 a3 3 0 1 0 6 0 a3 3 0 1 0 -6 0"
        )
    ),
    ERASE_STROKE(
        96f, 96f,
        listOf("M 84,27 V 12 H 69 l -44.24,44.24 15,15 z M 13.16,67.84 c -1.56,1.56 -1.56,4.08 0,5.64 l 9.36,9.36 c 1.56,1.56 4.08,1.56 5.64,0 l 7.32,-7.32 -15,-15 z")
    ),
    ERASE_RULED(
        96f, 96f,
        listOf(
            "M 84,27 V 12 H 69 l -44.24,44.24 15,15 z M 13.16,67.84 c -1.56,1.56 -1.56,4.08 0,5.64 l 9.36,9.36 c 1.56,1.56 4.08,1.56 5.64,0 l 7.32,-7.32 -15,-15 z",
            "M14.38,37.7 H81.6 V41.7 H14.38 Z",
            "M14.38,53.7 H81.6 V57.7 H14.38 Z"
        )
    ),
    SELECT_RECT(
        96f, 96f,
        listOf("M68.71,84h-3.771v-4h3.771V84z M61.18,84h-3.77v-4h3.77V84z M53.65,84h-3.771v-4h3.771V84z M38.59,84h-3.77v-4h3.77V84z M31.061,84H27.29v-4h3.771V84z M45,69H27V51h18V69z M12,12h4v4h-4V12z M12,27.29h4v3.77h-4V27.29z M12,42.35h4v3.77h-4V42.35z M12,57.41h4v3.77h-4V57.41z M12,72.471h4v3.771h-4V72.471z M12,80h4v4h-4V80z M45,45H27V27h18V45z M27.29,12h3.771v4H27.29V12z M42.35,12h3.771v4H42.35V12z M57.41,12h3.77v4h-3.77V12z M51,27h18v18H51V27z M51,51h18v18H51V51z M80,12h4v4h-4V12z M80,27.29h4v3.77h-4V27.29z M80,42.35h4v3.77h-4V42.35z M80,57.41h4v3.77h-4V57.41z M80,72.471h4v3.771h-4V72.471z M80,80h4v4h-4V80z")
    ),
    SELECT_LASSO(
        96f, 96f,
        listOf(
            "M27,51 h18 v18 h-18 z",
            "M27,27 h18 v18 h-18 z",
            "M51,27 h18 v18 h-18 z",
            "M51,51 h18 v18 h-18 z"
        ),
        isLassoIcon = true
    ),
    SELECT_RULED(
        96f, 96f,
        listOf(
            "M18.5,39.7 H77.5 V43.7 H18.5 Z",
            "M18.5,51.7 H77.5 V55.7 H18.5 Z",
            "M18.5,63.7 H58.0 V67.7 H18.5 Z",
            "M41.8,27.7 H77.5 V31.7 H41.8 Z"
        )
    ),
    INSERT_SPACE(
        96f, 96f,
        listOf(
            "M14.381,75.493v6.014h67.238l-0.016-6.014H14.381z M47.716,69.654L31.349,47.329h10.209V26.493h12.213v20.836h10.313L47.716,69.654z",
            "M14.381,12.993 L14.381,19.007 L81.619,19.007 L81.604,12.993 Z"
        )
    ),
    UNDO(
        24f, 24f,
        listOf("M12.5 8c-2.65 0-5.05.99-6.9 2.6L2 7v9h9l-3.62-3.62c1.39-1.16 3.16-1.88 5.12-1.88 3.54 0 6.55 2.31 7.6 5.5l2.37-.78C21.08 11.03 17.15 8 12.5 8z")
    ),
    REDO(
        96f, 96f,
        listOf("M43.148,40c-22.086,0-27.373,15.467-28.59,21.645l15.229,8.24c0-2.519,0.866-6.097,2.274-8.24c2.521-3.85,6.832-5.674,11.03-5.674h7.897v22.043l30.45-30.026L50.99,17.986V40H43.148")
    ),
    EXPAND_DOWN(
        96f, 96f,
        listOf(
            "M24.75,45.76 h24.56 v2 h-24.56 z",
            "M24.75,39.75 h14.45 v2 h-14.45 z",
            "M24.75,33.75 h18.78 v2 h-18.78 z",
            "M24.75,27.75 h26.00 v2 h-26.00 z"
        ),
        isExpandDownIcon = true
    ),
    EXPAND_RIGHT(
        96f, 96f,
        listOf(
            "M24.75,45.76 h24.56 v2 h-24.56 z",
            "M24.75,39.75 h14.45 v2 h-14.45 z",
            "M24.75,33.75 h18.78 v2 h-18.78 z",
            "M24.75,27.75 h26.00 v2 h-26.00 z"
        ),
        isExpandRightIcon = true
    ),
    PAGE_SETUP(
        96f, 96f,
        listOf("M61.599,15.805H21.74v64.39h52.52v-64.39H61.599z M68.378,69.199H27.624v-3.672h40.754V69.199z M68.378,59.652H27.462v-3.834h40.916V59.652z M68.541,49.945H27.624v-3.999h40.917V49.945z M68.541,39.911H27.624v-3.668h40.917V39.911z M68.541,30.203H27.624v-3.672h40.917V30.203z")
    ),
    DUPLICATE(
        28f, 28f,
        listOf("M5 7H3v16c0 1.1.9 2 2 2h16v-2H5V7zm18-4H9c-1.1 0-2 .9-2 2v14c0 1.1.9 2 2 2h14c1.1 0 2-.9 2-2V5c0-1.1-.9-2-2-2zm0 16H9V5h14v14z")
    ),
    DELETE(
        96f, 96f,
        listOf(
            "M68.917,41.842c-0.046-0.281-0.218-0.557-0.476-0.82c-2.079,2.112-10.444,2.462-20.445,2.462s-18.358-0.35-20.446-2.462c-0.257,0.263-0.417,0.539-0.476,0.82L28.7,75.518c0.145,2.615,3.139,6.568,19.292,6.568c16.155,0,19.151-3.953,19.292-6.568L68.917,41.842z",
            "M57.95,24.386v-7.875c0-1.422-1.164-2.597-2.595-2.597H39.769c-1.421,0-2.59,1.175-2.59,2.597v7.965c-7.706,0.874-12.981,2.644-12.981,4.694v3.893c0,0.799,0.809,1.559,2.26,2.244c3.801,1.786,12.017,3.024,21.538,3.024c9.528,0,17.741-1.238,21.547-3.024c1.442-0.685,2.259-1.445,2.259-2.244v-3.893C71.802,27.048,66.12,25.221,57.95,24.386z"
        )
    ),
    ZOOM(
        24f, 24f,
        listOf("M15.5 14h-.79l-.28-.27C15.41 12.59 16 11.11 16 9.5 16 5.91 13.09 3 9.5 3S3 5.91 3 9.5 5.91 16 9.5 16c1.61 0 3.09-.59 4.23-1.57l.27.28v.79l5 4.99L20.49 19l-4.99-5zm-6 0C7.01 14 5 11.99 5 9.5S7.01 5 9.5 5 14 7.01 14 9.5 11.99 14 9.5 14z")
    ),
    FULLSCREEN(
        24f, 24f,
        listOf("M7 14H5v5h5v-2H7v-3zm-2-4h2V7h3V5H5v5zm12 7h-3v2h5v-5h-2v3zM14 5v2h3v3h2V5h-5z")
    ),
    PIN(
        96f, 96f,
        listOf("M64,16 L32,16 L32,24 L38,24 L38,48 L24,60 L24,66 L44,66 L44,86 L52,86 L52,66 L72,66 L72,60 L58,48 L58,24 L64,24 Z")
    ),
    MENU(
        24f, 24f,
        listOf("M3 18h18v-2H3v2zm0-5h18v-2H3v2zm0-7v2h18V6H3z")
    ),
    IMAGE(
        24f, 24f,
        listOf("M21 19V5c0-1.1-.9-2-2-2H5c-1.1 0-2 .9-2 2v14c0 1.1.9 2 2 2h14c1.1 0 2-.9 2-2zM8.5 13.5l2.5 3.01L14.5 12l4.5 6H5l3.5-4.5z")
    ),
    CLOSE(
        24f, 24f,
        listOf("M19 6.41L17.59 5 12 10.59 6.41 5 5 6.41 10.59 12 5 17.59 6.41 19 12 13.41 17.59 19 19 17.59 13.41 12z")
    );
}

@Composable
fun WriteSvgIcon(
    asset: WriteIconAsset,
    tint: Color = Color(0xFFF1F5F9),
    size: Dp = 20.dp
) {
    val parsedPaths = remember(asset) {
        asset.pathData.map { d -> PathParser().parsePathString(d).toPath() }
    }
    Canvas(modifier = Modifier.size(size)) {
        val sx = this.size.width / asset.viewBoxW
        val sy = this.size.height / asset.viewBoxH
        withTransform({
            scale(scaleX = sx, scaleY = sy, pivot = Offset.Zero)
        }) {
            parsedPaths.forEach { p ->
                drawPath(path = p, color = tint)
            }
            if (asset.isLassoIcon) {
                drawCircle(
                    color = tint,
                    radius = 34.377f,
                    center = Offset(48f, 48f),
                    style = Stroke(
                        width = 4f,
                        pathEffect = PathEffect.dashPathEffect(floatArrayOf(6f, 5f), 0f)
                    )
                )
            }
            if (asset.isExpandDownIcon) {
                drawRect(
                    color = tint,
                    topLeft = Offset(16.75f, 11.74f),
                    size = Size(42.0f, 72.9f),
                    style = Stroke(width = 4f)
                )
                drawLine(
                    color = tint,
                    start = Offset(18f, 58.75f),
                    end = Offset(56.8f, 58.75f),
                    strokeWidth = 4f,
                    pathEffect = PathEffect.dashPathEffect(floatArrayOf(5f, 4f), 0f)
                )
            }
            if (asset.isExpandRightIcon) {
                drawRect(
                    color = tint,
                    topLeft = Offset(16.75f, 11.74f),
                    size = Size(62.4f, 47.0f),
                    style = Stroke(width = 4f)
                )
                drawLine(
                    color = tint,
                    start = Offset(58.75f, 13.5f),
                    end = Offset(58.75f, 56.87f),
                    strokeWidth = 4f,
                    pathEffect = PathEffect.dashPathEffect(floatArrayOf(5f, 4f), 0f)
                )
            }
        }
    }
}

private data class SavedWritePenPreset(
    val name: String,
    val colorArgb: Int,
    val widthPx: Float,
    val tip: WritePenTip,
    val isHighlighter: Boolean = false
)

// Exact width progression from Stylus Labs Write `PenToolbar::PEN_WIDTHS`
private val WRITE_PEN_WIDTH_PALETTE = listOf(1.0f, 1.6f, 2.4f, 3.2f, 4.5f, 6.5f, 12.0f)

private val WRITE_COLOR_PALETTE = listOf(
    0xFF1E293B.toInt(), // Slate / Black
    0xFF1D4ED8.toInt(), // Write Blue
    0xFFDC2626.toInt(), // Write Red
    0xFF15803D.toInt(), // Write Green
    0xFFD97706.toInt(), // Amber / Orange
    0xFF7E22CE.toInt(), // Purple
    0xFF0E7490.toInt(), // Cyan / Teal
    0xFF64748B.toInt(), // Gray
    0xFFFFFFFF.toInt()  // White
)

private val WRITE_SAVED_PENS = listOf(
    SavedWritePenPreset("Fine Black", 0xFF1E293B.toInt(), 1.8f, WritePenTip.ROUND),
    SavedWritePenPreset("Ballpoint Blue", 0xFF1D4ED8.toInt(), 2.6f, WritePenTip.ROUND),
    SavedWritePenPreset("Markup Red", 0xFFDC2626.toInt(), 2.6f, WritePenTip.ROUND),
    SavedWritePenPreset("Chisel Fountain", 0xFF1E293B.toInt(), 4.2f, WritePenTip.CHISEL),
    SavedWritePenPreset("Highlighter", 0xFFFACC15.toInt(), 4.5f, WritePenTip.FLAT, isHighlighter = true)
)

/**
 * Shared Stylus Labs Write Top Toolbar (`MainWindow` + `PenToolbar`) used BOTH:
 * 1. As an **auto-hiding top toolbar** in the normal non-zoomed Org view for the currently active /
 *    last-selected drawing block (replacing the old repetitive per-card toolbars).
 * 2. At the top of the **Full-Screen Zoom Mode** (`WriteZoomedEditorOverlay`) when the user zooms into
 *    a specific drawing.
 */
@Composable
fun WriteSharedToolbarChrome(
    activeFileName: String,
    pageSetup: WritePageSetupState,
    activeMode: StylusToolMode,
    lastEraserMode: StylusToolMode,
    lastSelectMode: StylusToolMode,
    lastInsertSpaceMode: StylusToolMode,
    showPenToolbar: Boolean,
    selectedColor: Int,
    selectedWidth: Float,
    selectedPenTip: WritePenTip,
    canUndo: Boolean,
    canRedo: Boolean,
    hasSelection: Boolean,
    isZoomedFullScreen: Boolean,
    showLabels: Boolean = false,
    isAutoHidePinned: Boolean = false,
    snapToGrid: Boolean = false,
    lineDrawing: Boolean = false,
    onToggleSnapToGrid: (() -> Unit)? = null,
    onToggleLineDrawing: (() -> Unit)? = null,
    onPasteClipboard: (() -> Unit)? = null,
    onTogglePin: (() -> Unit)? = null,
    onAutoHideDismiss: (() -> Unit)? = null,
    onZoomOrCloseFullScreen: () -> Unit,
    onSelectToolMode: (
        newMode: StylusToolMode,
        newColor: Int,
        newWidth: Float,
        newTip: WritePenTip,
        updateEraserDefault: StylusToolMode?,
        updateSelectDefault: StylusToolMode?,
        updateInsertSpaceDefault: StylusToolMode?
    ) -> Unit,
    onTogglePenToolbar: () -> Unit,
    onSelectAllStrokes: () -> Unit,
    onApplyStyleToSelection: () -> Unit,
    onUndo: () -> Unit,
    onRedo: () -> Unit,
    onExpandDown: () -> Unit,
    onExpandRight: () -> Unit,
    onOpenPageSetup: () -> Unit,
    onDeleteImage: (() -> Unit)? = null,
    pressureEnabled: Boolean = true,
    chiselAngle: Float = 45f,
    onTogglePressure: (() -> Unit)? = null,
    onChangeChiselAngle: ((Float) -> Unit)? = null,
    onInsertImage: (() -> Unit)? = null,
    onDeleteSelection: (() -> Unit)? = null,
    onUserInteracted: () -> Unit = {}
) {
    var showEraseMenu by remember { mutableStateOf(false) }
    var showSelectMenu by remember { mutableStateOf(false) }
    var showInsertSpaceMenu by remember { mutableStateOf(false) }
    var customPens by remember { mutableStateOf(listOf<SavedWritePenPreset>()) }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(if (isZoomedFullScreen) RoundedCornerShape(0.dp) else RoundedCornerShape(10.dp))
            .border(
                width = if (isZoomedFullScreen) 0.dp else 1.dp,
                color = if (isZoomedFullScreen) Color.Transparent else Color(0xFF38BDF8),
                shape = if (isZoomedFullScreen) RoundedCornerShape(0.dp) else RoundedCornerShape(10.dp)
            )
            .background(Color(0xFF222222))
    ) {
        // ====================================================================
        // 1. Stylus Labs Write Main Toolbar (`#222222`)
        // ====================================================================
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            modifier = Modifier
                .fillMaxWidth()
                .background(Color(0xFF222222))
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 10.dp, vertical = 6.dp)
        ) {
            if (isZoomedFullScreen) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .clip(RoundedCornerShape(6.dp))
                        .background(Color(0xFF334155))
                        .clickable { onZoomOrCloseFullScreen() }
                        .padding(horizontal = if (showLabels) 10.dp else 8.dp, vertical = 6.dp)
                        .semantics(mergeDescendants = true) { contentDescription = "Done" }
                ) {
                    WriteSvgIcon(asset = WriteIconAsset.BACK, tint = Color.White, size = 18.dp)
                    if (showLabels) {
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = "Done",
                            color = Color.White,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                }
                Spacer(modifier = Modifier.width(4.dp))
            }

            // Active Drawing Title Badge
            Column(modifier = Modifier.padding(end = 6.dp)) {
                Text(
                    text = "Canvas • ${WriteTemplateStyle.fromNativeCode(pageSetup.rulingStyle).label}",
                    color = Color(0xFF38BDF8),
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = "${pageSetup.width}×${pageSetup.height} • ${pageSetup.strokeCount} strokes",
                    color = Color(0xFF94A3B8),
                    fontFamily = FontFamily.Monospace,
                    fontSize = 10.sp
                )
            }

            WriteToolbarDivider()

            if (isZoomedFullScreen) {
                // Pan Tool (in zoomed mode)
                WriteMainToolButton(
                    icon = WriteIconAsset.PAN,
                    label = "Pan",
                    selected = activeMode == StylusToolMode.PAN,
                    showLabel = showLabels,
                    onClick = {
                        onUserInteracted()
                        onSelectToolMode(
                            StylusToolMode.PAN,
                            selectedColor,
                            selectedWidth,
                            selectedPenTip,
                            null,
                            null,
                            null
                        )
                    }
                )
            }

            // Draw Tool (Pen / Highlighter)
            WriteMainToolButton(
                icon = WriteIconAsset.DRAW,
                label = if (activeMode == StylusToolMode.HIGHLIGHTER) "Highlight" else "Draw",
                selected = activeMode == StylusToolMode.PEN || activeMode == StylusToolMode.HIGHLIGHTER,
                showLabel = showLabels,
                onClick = {
                    onUserInteracted()
                    val target = if (activeMode == StylusToolMode.HIGHLIGHTER) {
                        StylusToolMode.HIGHLIGHTER
                    } else {
                        StylusToolMode.PEN
                    }
                    onSelectToolMode(
                        target,
                        selectedColor,
                        selectedWidth,
                        selectedPenTip,
                        null,
                        null,
                        null
                    )
                }
            )

            // Pen Toolbar Toggle (`actionCustom_Pen`)
            WriteMainToolButton(
                icon = WriteIconAsset.SET_PEN,
                label = "Pen Bar",
                selected = showPenToolbar,
                showLabel = showLabels,
                onClick = {
                    onUserInteracted()
                    onTogglePenToolbar()
                }
            )

            // Erase Tool + Dropdown (Stroke Eraser / Ruled Eraser / Free Eraser)
            Box {
                val isEraseActive = activeMode == StylusToolMode.ERASER ||
                    activeMode == StylusToolMode.ERASER_RULED ||
                    activeMode == StylusToolMode.ERASER_FREE
                val currentEraseType = if (isEraseActive) activeMode else lastEraserMode
                val eraseLabel = when (currentEraseType) {
                    StylusToolMode.ERASER_RULED -> "Ruled Erase ▾"
                    StylusToolMode.ERASER_FREE -> "Free Erase ▾"
                    else -> "Erase ▾"
                }
                WriteMainToolButton(
                    icon = if (currentEraseType == StylusToolMode.ERASER_RULED) {
                        WriteIconAsset.ERASE_RULED
                    } else {
                        WriteIconAsset.ERASE_STROKE
                    },
                    label = eraseLabel,
                    selected = isEraseActive,
                    showLabel = showLabels,
                    onClick = {
                        onUserInteracted()
                        if (isEraseActive) {
                            showEraseMenu = true
                        } else {
                            onSelectToolMode(
                                lastEraserMode,
                                selectedColor,
                                selectedWidth,
                                selectedPenTip,
                                null,
                                null,
                                null
                            )
                        }
                    }
                )
                DropdownMenu(
                    expanded = showEraseMenu,
                    onDismissRequest = { showEraseMenu = false }
                ) {
                    DropdownMenuItem(
                        text = { Text("Stroke Eraser (Erase whole strokes)") },
                        onClick = {
                            onUserInteracted()
                            onSelectToolMode(
                                StylusToolMode.ERASER,
                                selectedColor,
                                selectedWidth,
                                selectedPenTip,
                                StylusToolMode.ERASER,
                                null,
                                null
                            )
                            showEraseMenu = false
                        }
                    )
                    DropdownMenuItem(
                        text = { Text("Free Eraser (Split stroke segments / image pixels)") },
                        onClick = {
                            onUserInteracted()
                            onSelectToolMode(
                                StylusToolMode.ERASER_FREE,
                                selectedColor,
                                selectedWidth,
                                selectedPenTip,
                                StylusToolMode.ERASER_FREE,
                                null,
                                null
                            )
                            showEraseMenu = false
                        }
                    )
                    DropdownMenuItem(
                        text = { Text("Ruled Eraser (Erase entire ruled line)") },
                        onClick = {
                            onUserInteracted()
                            onSelectToolMode(
                                StylusToolMode.ERASER_RULED,
                                selectedColor,
                                selectedWidth,
                                selectedPenTip,
                                StylusToolMode.ERASER_RULED,
                                null,
                                null
                            )
                            showEraseMenu = false
                        }
                    )
                }
            }

            // Select Tool + Dropdown (Lasso / Rect / Ruled / Select All / Paste)
            Box {
                val isSelectActive = activeMode == StylusToolMode.SELECT_LASSO ||
                    activeMode == StylusToolMode.SELECT_RECT ||
                    activeMode == StylusToolMode.SELECT_RULED
                val currentSelectType = if (isSelectActive) activeMode else lastSelectMode
                val selIcon = when (currentSelectType) {
                    StylusToolMode.SELECT_RECT -> WriteIconAsset.SELECT_RECT
                    StylusToolMode.SELECT_RULED -> WriteIconAsset.SELECT_RULED
                    else -> WriteIconAsset.SELECT_LASSO
                }
                val selLabel = when (currentSelectType) {
                    StylusToolMode.SELECT_RECT -> "Rect Sel ▾"
                    StylusToolMode.SELECT_RULED -> "Ruled Sel ▾"
                    else -> "Lasso Sel ▾"
                }
                WriteMainToolButton(
                    icon = selIcon,
                    label = selLabel,
                    selected = isSelectActive,
                    showLabel = showLabels,
                    onClick = {
                        onUserInteracted()
                        if (isSelectActive) {
                            showSelectMenu = true
                        } else {
                            onSelectToolMode(
                                lastSelectMode,
                                selectedColor,
                                selectedWidth,
                                selectedPenTip,
                                null,
                                null,
                                null
                            )
                        }
                    }
                )
                DropdownMenu(
                    expanded = showSelectMenu,
                    onDismissRequest = { showSelectMenu = false }
                ) {
                    DropdownMenuItem(
                        text = { Text("Lasso Select (Select within path)") },
                        onClick = {
                            onUserInteracted()
                            onSelectToolMode(
                                StylusToolMode.SELECT_LASSO,
                                selectedColor,
                                selectedWidth,
                                selectedPenTip,
                                null,
                                StylusToolMode.SELECT_LASSO,
                                null
                            )
                            showSelectMenu = false
                        }
                    )
                    DropdownMenuItem(
                        text = { Text("Rectangle Select (Select within box)") },
                        onClick = {
                            onUserInteracted()
                            onSelectToolMode(
                                StylusToolMode.SELECT_RECT,
                                selectedColor,
                                selectedWidth,
                                selectedPenTip,
                                null,
                                StylusToolMode.SELECT_RECT,
                                null
                            )
                            showSelectMenu = false
                        }
                    )
                    DropdownMenuItem(
                        text = { Text("Ruled Select (Select handwritten lines)") },
                        onClick = {
                            onUserInteracted()
                            onSelectToolMode(
                                StylusToolMode.SELECT_RULED,
                                selectedColor,
                                selectedWidth,
                                selectedPenTip,
                                null,
                                StylusToolMode.SELECT_RULED,
                                null
                            )
                            showSelectMenu = false
                        }
                    )
                    HorizontalDivider()
                    DropdownMenuItem(
                        text = { Text("Select All Strokes") },
                        onClick = {
                            onUserInteracted()
                            onSelectToolMode(
                                lastSelectMode,
                                selectedColor,
                                selectedWidth,
                                selectedPenTip,
                                null,
                                null,
                                null
                            )
                            onSelectAllStrokes()
                            showSelectMenu = false
                        }
                    )
                    if (onPasteClipboard != null) {
                        DropdownMenuItem(
                            text = { Text("Paste Clipboard Strokes") },
                            enabled = WriteNativeBridge.clipboardSvgXml.isNotBlank(),
                            onClick = {
                                onUserInteracted()
                                onPasteClipboard()
                                showSelectMenu = false
                            }
                        )
                    }
                }
            }

            // Insert Space Tool + Dropdown (Ruled Insert Space / Vertical Space / Horizontal Space)
            Box {
                val isInsSpaceActive = activeMode == StylusToolMode.INSERT_SPACE_VERT ||
                    activeMode == StylusToolMode.INSERT_SPACE_HORZ ||
                    activeMode == StylusToolMode.INSERT_SPACE_RULED
                val currentInsType = if (isInsSpaceActive) activeMode else lastInsertSpaceMode
                val insLabel = when (currentInsType) {
                    StylusToolMode.INSERT_SPACE_RULED -> "Ruled Space ▾"
                    StylusToolMode.INSERT_SPACE_HORZ -> "Horz Space ▾"
                    else -> "Vert Space ▾"
                }
                WriteMainToolButton(
                    icon = WriteIconAsset.INSERT_SPACE,
                    label = insLabel,
                    selected = isInsSpaceActive,
                    showLabel = showLabels,
                    onClick = {
                        onUserInteracted()
                        if (isInsSpaceActive) {
                            showInsertSpaceMenu = true
                        } else {
                            onSelectToolMode(
                                lastInsertSpaceMode,
                                selectedColor,
                                selectedWidth,
                                selectedPenTip,
                                null,
                                null,
                                null
                            )
                        }
                    }
                )
                DropdownMenu(
                    expanded = showInsertSpaceMenu,
                    onDismissRequest = { showInsertSpaceMenu = false }
                ) {
                    DropdownMenuItem(
                        text = { Text("Ruled Insert Space (Intra-line word reflow & line spacing)") },
                        onClick = {
                            onUserInteracted()
                            onSelectToolMode(
                                StylusToolMode.INSERT_SPACE_RULED,
                                selectedColor,
                                selectedWidth,
                                selectedPenTip,
                                null,
                                null,
                                StylusToolMode.INSERT_SPACE_RULED
                            )
                            showInsertSpaceMenu = false
                        }
                    )
                    DropdownMenuItem(
                        text = { Text("Insert Vertical Space (Continuous expand/collapse height)") },
                        onClick = {
                            onUserInteracted()
                            onSelectToolMode(
                                StylusToolMode.INSERT_SPACE_VERT,
                                selectedColor,
                                selectedWidth,
                                selectedPenTip,
                                null,
                                null,
                                StylusToolMode.INSERT_SPACE_VERT
                            )
                            showInsertSpaceMenu = false
                        }
                    )
                    DropdownMenuItem(
                        text = { Text("Insert Horizontal Space (Continuous expand/collapse width)") },
                        onClick = {
                            onUserInteracted()
                            onSelectToolMode(
                                StylusToolMode.INSERT_SPACE_HORZ,
                                selectedColor,
                                selectedWidth,
                                selectedPenTip,
                                null,
                                null,
                                StylusToolMode.INSERT_SPACE_HORZ
                            )
                            showInsertSpaceMenu = false
                        }
                    )
                }
            }

            WriteToolbarDivider()

            // Undo & Redo
            WriteMainToolButton(
                icon = WriteIconAsset.UNDO,
                label = "Undo",
                selected = false,
                enabled = canUndo,
                showLabel = showLabels,
                onClick = {
                    onUserInteracted()
                    onUndo()
                }
            )
            WriteMainToolButton(
                icon = WriteIconAsset.REDO,
                label = "Redo",
                selected = false,
                enabled = canRedo,
                showLabel = showLabels,
                onClick = {
                    onUserInteracted()
                    onRedo()
                }
            )

            Spacer(modifier = Modifier.weight(1f))

            // Expand Down & Expand Right (`actionExpand_Down`, `actionExpand_Right`)
            WriteMainToolButton(
                icon = WriteIconAsset.EXPAND_DOWN,
                label = "+Down",
                selected = false,
                showLabel = showLabels,
                onClick = {
                    onUserInteracted()
                    onExpandDown()
                }
            )
            WriteMainToolButton(
                icon = WriteIconAsset.EXPAND_RIGHT,
                label = "+Right",
                selected = false,
                showLabel = showLabels,
                onClick = {
                    onUserInteracted()
                    onExpandRight()
                }
            )

            // Page, Template & Custom Designer Setup (`actionPage_Setup`)
            WriteMainToolButton(
                icon = WriteIconAsset.PAGE_SETUP,
                label = "Template",
                selected = false,
                showLabel = showLabels,
                onClick = {
                    onUserInteracted()
                    onOpenPageSetup()
                }
            )

            if (onInsertImage != null) {
                WriteMainToolButton(
                    icon = WriteIconAsset.IMAGE,
                    label = "Image",
                    selected = false,
                    showLabel = showLabels,
                    onClick = {
                        onUserInteracted()
                        onInsertImage()
                    }
                )
            }

            if (hasSelection && onDeleteSelection != null) {
                WriteToolbarDivider()
                WriteMainToolButton(
                    icon = WriteIconAsset.DELETE,
                    label = "Delete",
                    selected = false,
                    tint = Color(0xFFEF4444),
                    showLabel = showLabels,
                    onClick = {
                        onUserInteracted()
                        onDeleteSelection()
                    }
                )
            } else if (isZoomedFullScreen && onDeleteImage != null) {
                WriteToolbarDivider()
                WriteMainToolButton(
                    icon = WriteIconAsset.DELETE,
                    label = "Canvas",
                    selected = false,
                    tint = Color(0xFF94A3B8),
                    showLabel = showLabels,
                    onClick = {
                        onUserInteracted()
                        onDeleteImage()
                    }
                )
            }

            if (!isZoomedFullScreen) {
                // Zoom into Full-Screen Stylus Labs Write View
                WriteMainToolButton(
                    icon = WriteIconAsset.FULLSCREEN,
                    label = "Zoom",
                    selected = false,
                    showLabel = showLabels,
                    onClick = { onZoomOrCloseFullScreen() }
                )

                // Pin / Auto-hide controls (`actionClippingsPin` / `actionBookmarksPin` style)
                if (onTogglePin != null) {
                    WriteMainToolButton(
                        icon = WriteIconAsset.PIN,
                        label = if (isAutoHidePinned) "Pinned" else "Auto-Hide",
                        selected = isAutoHidePinned,
                        showLabel = showLabels,
                        onClick = {
                            onUserInteracted()
                            onTogglePin()
                        }
                    )
                }

                if (onAutoHideDismiss != null) {
                    Text(
                        text = "✕",
                        color = Color(0xFF94A3B8),
                        fontSize = 14.sp,
                        modifier = Modifier
                            .clip(RoundedCornerShape(4.dp))
                            .clickable { onAutoHideDismiss() }
                            .padding(horizontal = 6.dp, vertical = 4.dp)
                    )
                }
            }
        }

        // ====================================================================
        // 2. Stylus Labs Write Second-Row `PenToolbar` (`#323232` graybar)
        // ====================================================================
        AnimatedVisibility(
            visible = showPenToolbar,
            enter = expandVertically() + fadeIn(),
            exit = shrinkVertically() + fadeOut()
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Color(0xFF323232))
                    .horizontalScroll(rememberScrollState())
                    .padding(horizontal = 12.dp, vertical = 6.dp)
            ) {
                // Saved Pen Presets + User Custom Saved Pens
                (WRITE_SAVED_PENS + customPens).forEach { preset ->
                    val isPresetActive = (
                        (preset.isHighlighter && activeMode == StylusToolMode.HIGHLIGHTER) ||
                            (!preset.isHighlighter && activeMode == StylusToolMode.PEN &&
                                selectedColor == preset.colorArgb &&
                                selectedWidth == preset.widthPx &&
                                selectedPenTip == preset.tip)
                        )
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .clip(RoundedCornerShape(6.dp))
                            .background(if (isPresetActive) Color(0xFF0284C7) else Color(0xFF1F2937))
                            .border(
                                1.dp,
                                if (isPresetActive) Color(0xFF38BDF8) else Color(0xFF4B5563),
                                RoundedCornerShape(6.dp)
                            )
                            .clickable {
                                onUserInteracted()
                                onSelectToolMode(
                                    if (preset.isHighlighter) StylusToolMode.HIGHLIGHTER else StylusToolMode.PEN,
                                    preset.colorArgb,
                                    preset.widthPx,
                                    preset.tip,
                                    null,
                                    null,
                                    null
                                )
                            }
                            .padding(horizontal = 8.dp, vertical = 4.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .size(10.dp)
                                .clip(CircleShape)
                                .background(Color(preset.colorArgb))
                        )
                        Spacer(modifier = Modifier.width(5.dp))
                        Text(
                            text = preset.name,
                            color = Color.White,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Medium
                        )
                    }
                }

                // + Save Current Pen Preset
                Text(
                    text = "+ Save Pen",
                    color = Color(0xFF38BDF8),
                    fontSize = 10.sp,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier
                        .clip(RoundedCornerShape(4.dp))
                        .background(Color(0xFF1F2937))
                        .border(1.dp, Color(0xFF38BDF8).copy(alpha = 0.5f), RoundedCornerShape(4.dp))
                        .clickable {
                            onUserInteracted()
                            val newName = "Pen ${customPens.size + 1} (${selectedWidth}px)"
                            customPens = customPens + SavedWritePenPreset(
                                name = newName,
                                colorArgb = selectedColor,
                                widthPx = selectedWidth,
                                tip = selectedPenTip,
                                isHighlighter = activeMode == StylusToolMode.HIGHLIGHTER
                            )
                        }
                        .padding(horizontal = 7.dp, vertical = 4.dp)
                )

                WriteToolbarDivider()

                // Color Palette Swatches (`PenToolbar::colorPalette`)
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(5.dp)
                ) {
                    WRITE_COLOR_PALETTE.forEach { argb ->
                        val isSelected = selectedColor == argb
                        Box(
                            modifier = Modifier
                                .size(if (isSelected) 24.dp else 20.dp)
                                .clip(RoundedCornerShape(4.dp))
                                .background(Color(argb))
                                .border(
                                    width = if (isSelected) 2.dp else 1.dp,
                                    color = if (isSelected) Color(0xFF38BDF8) else Color(0xFF64748B),
                                    shape = RoundedCornerShape(4.dp)
                                )
                                .clickable {
                                    onUserInteracted()
                                    val targetMode = if (
                                        activeMode != StylusToolMode.PEN &&
                                        activeMode != StylusToolMode.HIGHLIGHTER
                                    ) {
                                        StylusToolMode.PEN
                                    } else {
                                        activeMode
                                    }
                                    onSelectToolMode(
                                        targetMode,
                                        argb,
                                        selectedWidth,
                                        selectedPenTip,
                                        null,
                                        null,
                                        null
                                    )
                                    if (hasSelection) {
                                        onApplyStyleToSelection()
                                    }
                                }
                        )
                    }
                }

                WriteToolbarDivider()

                // Width Palette Boxes (`PenToolbar::widthBtnSVG` style)
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    WRITE_PEN_WIDTH_PALETTE.forEach { w ->
                        val isSelected = selectedWidth == w
                        WriteWidthPreviewButton(
                            strokeWidthPx = w,
                            selected = isSelected,
                            onClick = {
                                onUserInteracted()
                                onSelectToolMode(
                                    activeMode,
                                    selectedColor,
                                    w,
                                    selectedPenTip,
                                    null,
                                    null,
                                    null
                                )
                                if (hasSelection) {
                                    onApplyStyleToSelection()
                                }
                            }
                        )
                    }
                }

                WriteToolbarDivider()

                // Pen Tip Selector (`Flat`, `Round`, `Chisel`, `Highlight`)
                WritePenTip.values().forEach { tip ->
                    val isTipActive = activeMode == StylusToolMode.PEN && selectedPenTip == tip
                    Text(
                        text = tip.label,
                        color = if (isTipActive) Color.White else Color(0xFFCBD5E1),
                        fontSize = 11.sp,
                        fontWeight = if (isTipActive) FontWeight.Bold else FontWeight.Normal,
                        modifier = Modifier
                            .clip(RoundedCornerShape(4.dp))
                            .background(if (isTipActive) Color(0xFF0284C7) else Color(0xFF1F2937))
                            .clickable {
                                onUserInteracted()
                                onSelectToolMode(
                                    StylusToolMode.PEN,
                                    selectedColor,
                                    selectedWidth,
                                    tip,
                                    null,
                                    null,
                                    null
                                )
                            }
                            .padding(horizontal = 7.dp, vertical = 4.dp)
                    )
                }

                val isHighlight = activeMode == StylusToolMode.HIGHLIGHTER
                Text(
                    text = "Highlighter",
                    color = if (isHighlight) Color(0xFF0F172A) else Color(0xFFFDE047),
                    fontSize = 11.sp,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier
                        .clip(RoundedCornerShape(4.dp))
                        .background(if (isHighlight) Color(0xFFFACC15) else Color(0xFF1F2937))
                        .clickable {
                            onUserInteracted()
                            onSelectToolMode(
                                if (isHighlight) StylusToolMode.PEN else StylusToolMode.HIGHLIGHTER,
                                selectedColor,
                                selectedWidth,
                                selectedPenTip,
                                null,
                                null,
                                null
                            )
                        }
                        .padding(horizontal = 7.dp, vertical = 4.dp)
                )

                // Pressure Sensitivity Toggle
                if (onTogglePressure != null) {
                    WriteToolbarDivider()
                    Text(
                        text = if (pressureEnabled) "Pressure: ON" else "Pressure: OFF",
                        color = if (pressureEnabled) Color(0xFF38BDF8) else Color(0xFF94A3B8),
                        fontSize = 11.sp,
                        fontWeight = if (pressureEnabled) FontWeight.SemiBold else FontWeight.Normal,
                        modifier = Modifier
                            .clip(RoundedCornerShape(4.dp))
                            .background(if (pressureEnabled) Color(0x330284C7) else Color(0xFF1F2937))
                            .border(
                                1.dp,
                                if (pressureEnabled) Color(0xFF0284C7) else Color(0xFF4B5563),
                                RoundedCornerShape(4.dp)
                            )
                            .clickable {
                                onUserInteracted()
                                onTogglePressure()
                            }
                            .padding(horizontal = 7.dp, vertical = 4.dp)
                    )
                }

                // Chisel Tip Nib Angle Presets (30°, 45°, 60°)
                if (selectedPenTip == WritePenTip.CHISEL && onChangeChiselAngle != null) {
                    WriteToolbarDivider()
                    Text(
                        text = "Nib Angle:",
                        color = Color(0xFF94A3B8),
                        fontSize = 11.sp,
                        modifier = Modifier.padding(end = 2.dp)
                    )
                    listOf(30f, 45f, 60f).forEach { ang ->
                        val isAngActive = kotlin.math.abs(chiselAngle - ang) < 2f
                        Text(
                            text = "${ang.toInt()}°",
                            color = if (isAngActive) Color.White else Color(0xFFCBD5E1),
                            fontSize = 11.sp,
                            fontWeight = if (isAngActive) FontWeight.Bold else FontWeight.Normal,
                            modifier = Modifier
                                .clip(RoundedCornerShape(4.dp))
                                .background(if (isAngActive) Color(0xFF0284C7) else Color(0xFF1F2937))
                                .clickable {
                                    onUserInteracted()
                                    onChangeChiselAngle(ang)
                                }
                                .padding(horizontal = 6.dp, vertical = 4.dp)
                        )
                    }
                }

                if (onToggleLineDrawing != null || onToggleSnapToGrid != null) {
                    WriteToolbarDivider()
                }

                // Straight Line / Ruler Mode (`LINE_DRAWING`)
                if (onToggleLineDrawing != null) {
                    Text(
                        text = "╱ Line",
                        color = if (lineDrawing) Color.White else Color(0xFFCBD5E1),
                        fontSize = 11.sp,
                        fontWeight = if (lineDrawing) FontWeight.Bold else FontWeight.Normal,
                        modifier = Modifier
                            .clip(RoundedCornerShape(4.dp))
                            .background(if (lineDrawing) Color(0xFF0284C7) else Color(0xFF1F2937))
                            .border(
                                1.dp,
                                if (lineDrawing) Color(0xFF38BDF8) else Color(0xFF4B5563),
                                RoundedCornerShape(4.dp)
                            )
                            .clickable {
                                onUserInteracted()
                                onToggleLineDrawing()
                            }
                            .padding(horizontal = 7.dp, vertical = 4.dp)
                    )
                }

                // Snap to Grid (`SNAP_TO_GRID`)
                if (onToggleSnapToGrid != null) {
                    Text(
                        text = "⊞ Snap Grid",
                        color = if (snapToGrid) Color.White else Color(0xFFCBD5E1),
                        fontSize = 11.sp,
                        fontWeight = if (snapToGrid) FontWeight.Bold else FontWeight.Normal,
                        modifier = Modifier
                            .clip(RoundedCornerShape(4.dp))
                            .background(if (snapToGrid) Color(0xFF0284C7) else Color(0xFF1F2937))
                            .border(
                                1.dp,
                                if (snapToGrid) Color(0xFF38BDF8) else Color(0xFF4B5563),
                                RoundedCornerShape(4.dp)
                            )
                            .clickable {
                                onUserInteracted()
                                onToggleSnapToGrid()
                            }
                            .padding(horizontal = 7.dp, vertical = 4.dp)
                    )
                }
            }
        }
    }
}

/**
 * Floating Selection Action Bar (`selPopup` in Stylus Labs Write) shown whenever strokes are selected
 * in either Inline Mode or Zoomed Mode. Supports Move, Scale, Rotate, Copy, Cut, Paste, Duplicate,
 * Apply Pen Style, and Delete.
 */
@Composable
fun WriteSelectionPopupBar(
    selectionCount: Int,
    selectedColor: Int,
    onDuplicate: () -> Unit,
    onCopy: (() -> Unit)? = null,
    onCut: (() -> Unit)? = null,
    onPaste: (() -> Unit)? = null,
    onScaleSmaller: (() -> Unit)? = null,
    onScaleLarger: (() -> Unit)? = null,
    onRotateCcw: (() -> Unit)? = null,
    onRotateCw: (() -> Unit)? = null,
    onApplyStyle: () -> Unit,
    onDelete: () -> Unit,
    onClearSelection: () -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        modifier = modifier
            .clip(RoundedCornerShape(10.dp))
            .background(Color(0xEE1E293B))
            .border(1.dp, Color(0xFF38BDF8), RoundedCornerShape(10.dp))
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 12.dp, vertical = 8.dp)
    ) {
        Text(
            text = "$selectionCount selected (Drag box to move, corners to scale, top handle to rotate)",
            color = Color(0xFF38BDF8),
            fontSize = 11.sp,
            fontWeight = FontWeight.SemiBold
        )

        WriteToolbarDivider()

        if (onCopy != null) {
            SelectionActionPill("Copy", onClick = onCopy)
        }
        if (onCut != null) {
            SelectionActionPill("Cut", onClick = onCut)
        }
        if (onPaste != null && WriteNativeBridge.clipboardSvgXml.isNotBlank()) {
            SelectionActionPill("Paste", onClick = onPaste)
        }

        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .clip(RoundedCornerShape(6.dp))
                .background(Color(0xFF334155))
                .clickable { onDuplicate() }
                .padding(horizontal = 8.dp, vertical = 5.dp)
        ) {
            WriteSvgIcon(asset = WriteIconAsset.DUPLICATE, size = 14.dp)
            Spacer(modifier = Modifier.width(4.dp))
            Text("Duplicate", color = Color.White, fontSize = 11.sp)
        }

        if (onScaleSmaller != null && onScaleLarger != null) {
            SelectionActionPill("Scale −", onClick = onScaleSmaller)
            SelectionActionPill("Scale +", onClick = onScaleLarger)
        }

        if (onRotateCcw != null && onRotateCw != null) {
            SelectionActionPill("⟲ −15°", onClick = onRotateCcw)
            SelectionActionPill("⟳ +15°", onClick = onRotateCw)
        }

        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .clip(RoundedCornerShape(6.dp))
                .background(Color(0xFF334155))
                .clickable { onApplyStyle() }
                .padding(horizontal = 8.dp, vertical = 5.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(10.dp)
                    .clip(CircleShape)
                    .background(Color(selectedColor))
            )
            Spacer(modifier = Modifier.width(5.dp))
            Text("Apply Pen Style", color = Color.White, fontSize = 11.sp)
        }

        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .clip(RoundedCornerShape(6.dp))
                .background(Color(0xFF7F1D1D))
                .clickable { onDelete() }
                .padding(horizontal = 8.dp, vertical = 5.dp)
        ) {
            WriteSvgIcon(asset = WriteIconAsset.DELETE, size = 14.dp)
            Spacer(modifier = Modifier.width(4.dp))
            Text("Delete", color = Color(0xFFFECACA), fontSize = 11.sp)
        }

        Text(
            text = "✕",
            color = Color(0xFF94A3B8),
            fontSize = 14.sp,
            modifier = Modifier
                .clip(RoundedCornerShape(4.dp))
                .clickable { onClearSelection() }
                .padding(horizontal = 6.dp, vertical = 2.dp)
        )
    }
}

@Composable
private fun SelectionActionPill(
    label: String,
    onClick: () -> Unit
) {
    Text(
        text = label,
        color = Color.White,
        fontSize = 11.sp,
        fontWeight = FontWeight.Medium,
        modifier = Modifier
            .clip(RoundedCornerShape(6.dp))
            .background(Color(0xFF334155))
            .clickable { onClick() }
            .padding(horizontal = 8.dp, vertical = 5.dp)
    )
}

/**
 * Full-Screen Stylus Labs Write UI when the user chooses to zoom into a specific `.svgz` drawing.
 */
@Composable
fun WriteZoomedEditorOverlay(
    nodeId: String,
    block: OrgBlock.InkCanvasBlock,
    syncClient: EmacsSyncClient,
    onCloseZoom: (updatedWidth: Int, updatedHeight: Int) -> Unit
) {
    var canvasViewRef by remember(block.blockId) { mutableStateOf<WriteCanvasBlockView?>(null) }

    var activeMode by remember(block.blockId) { mutableStateOf(StylusToolMode.PEN) }
    var lastEraserMode by remember(block.blockId) { mutableStateOf(StylusToolMode.ERASER) }
    var lastSelectMode by remember(block.blockId) { mutableStateOf(StylusToolMode.SELECT_LASSO) }
    var lastInsertSpaceMode by remember(block.blockId) { mutableStateOf(StylusToolMode.INSERT_SPACE_RULED) }

    var showPenToolbar by remember(block.blockId) { mutableStateOf(true) }
    var selectedColor by remember(block.blockId) { mutableStateOf(0xFF1E293B.toInt()) }
    var selectedWidth by remember(block.blockId) { mutableStateOf(2.6f) }
    var selectedPenTip by remember(block.blockId) { mutableStateOf(WritePenTip.ROUND) }
    var chiselAngle by remember(block.blockId) { mutableStateOf(45f) }
    var pressureEnabled by remember(block.blockId) { mutableStateOf(true) }
    var snapToGrid by remember(block.blockId) { mutableStateOf(false) }
    var lineDrawing by remember(block.blockId) { mutableStateOf(false) }
    var singleTouchPan by remember(block.blockId) { mutableStateOf(true) }

    var showPageSetupDialog by remember { mutableStateOf(false) }
    var showDeleteConfirmDialog by remember(block.blockId) { mutableStateOf(false) }

    var selectionState by remember { mutableStateOf(WriteSelectionState()) }
    var zoomScale by remember { mutableStateOf(1.0f) }
    var canUndo by remember { mutableStateOf(false) }
    var canRedo by remember { mutableStateOf(false) }
    var pageSetup by remember {
        mutableStateOf(
            WritePageSetupState(
                width = block.width,
                height = block.height,
                xRuling = block.xRuling,
                yRuling = block.yRuling
            )
        )
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
                        canvasViewRef?.insertImageBytes(bytes, asBackground = false)
                    }
                }
            } catch (_: Exception) {}
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF18181B))
    ) {
        WriteSharedToolbarChrome(
            activeFileName = block.fileName,
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
            isZoomedFullScreen = true,
            snapToGrid = snapToGrid,
            lineDrawing = lineDrawing,
            pressureEnabled = pressureEnabled,
            chiselAngle = chiselAngle,
            onTogglePressure = {
                pressureEnabled = !pressureEnabled
                canvasViewRef?.configureTool(
                    activeMode,
                    selectedColor,
                    selectedWidth,
                    selectedPenTip,
                    pressureEnabled,
                    snapToGrid,
                    lineDrawing,
                    chiselAngle
                )
            },
            onChangeChiselAngle = { newAngle ->
                chiselAngle = newAngle
                canvasViewRef?.configureTool(
                    activeMode,
                    selectedColor,
                    selectedWidth,
                    selectedPenTip,
                    pressureEnabled,
                    snapToGrid,
                    lineDrawing,
                    newAngle
                )
            },
            onInsertImage = {
                imagePickerLauncher.launch("image/*")
            },
            onDeleteSelection = {
                canvasViewRef?.deleteSelectedStrokes()
            },
            onToggleSnapToGrid = {
                snapToGrid = !snapToGrid
                canvasViewRef?.configureTool(
                    activeMode,
                    selectedColor,
                    selectedWidth,
                    selectedPenTip,
                    pressureEnabled,
                    snapToGrid,
                    lineDrawing,
                    chiselAngle
                )
            },
            onToggleLineDrawing = {
                lineDrawing = !lineDrawing
                canvasViewRef?.configureTool(
                    activeMode,
                    selectedColor,
                    selectedWidth,
                    selectedPenTip,
                    pressureEnabled,
                    snapToGrid,
                    lineDrawing,
                    chiselAngle
                )
            },
            onPasteClipboard = {
                canvasViewRef?.pasteClipboardStrokes()
            },
            onZoomOrCloseFullScreen = { onCloseZoom(pageSetup.width, pageSetup.height) },
            onSelectToolMode = { newMode, newColor, newWidth, newTip, eraseDef, selectDef, insDef ->
                activeMode = newMode
                selectedColor = newColor
                selectedWidth = newWidth
                selectedPenTip = newTip
                if (eraseDef != null) lastEraserMode = eraseDef
                if (selectDef != null) lastSelectMode = selectDef
                if (insDef != null) lastInsertSpaceMode = insDef
                canvasViewRef?.configureTool(
                    newMode,
                    newColor,
                    newWidth,
                    newTip,
                    pressureEnabled,
                    snapToGrid,
                    lineDrawing,
                    chiselAngle
                )
            },
            onTogglePenToolbar = { showPenToolbar = !showPenToolbar },
            onSelectAllStrokes = { canvasViewRef?.selectAllStrokes() },
            onApplyStyleToSelection = { canvasViewRef?.applyCurrentStyleToSelection() },
            onUndo = { canvasViewRef?.performUndo() },
            onRedo = { canvasViewRef?.performRedo() },
            onExpandDown = { canvasViewRef?.expandDown() },
            onExpandRight = { canvasViewRef?.expandRight() },
            onOpenPageSetup = { showPageSetupDialog = true },
            onDeleteImage = { showDeleteConfirmDialog = true }
        )

        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
        ) {
            AndroidView(
                factory = { ctx ->
                    WriteCanvasBlockView(
                        context = ctx,
                        initialWidth = block.width,
                        initialHeight = block.height,
                        xRuling = block.xRuling,
                        yRuling = block.yRuling,
                        isZoomedMode = true,
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
                        onHeightExpanded = { newH ->
                            pageSetup = pageSetup.copy(height = newH)
                        },
                        onSelectionChanged = { sel ->
                            selectionState = sel
                        },
                        onViewportChanged = { scale, undoAvail, redoAvail, setup ->
                            zoomScale = scale
                            canUndo = undoAvail
                            canRedo = redoAvail
                            pageSetup = setup
                        }
                    ).apply {
                        singleTouchPanInZoomedMode = singleTouchPan
                        loadFromBase64Svgz(block.svgzBase64)
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
                    }
                },
                modifier = Modifier.fillMaxSize()
            )

            if (selectionState.hasSelection) {
                WriteSelectionPopupBar(
                    selectionCount = selectionState.count,
                    selectedColor = selectedColor,
                    onDuplicate = { canvasViewRef?.duplicateSelectedStrokes() },
                    onCopy = { canvasViewRef?.copySelectedStrokes() },
                    onCut = { canvasViewRef?.cutSelectedStrokes() },
                    onPaste = { canvasViewRef?.pasteClipboardStrokes() },
                    onScaleSmaller = { canvasViewRef?.scaleSelectedStrokes(0.9f) },
                    onScaleLarger = { canvasViewRef?.scaleSelectedStrokes(1.1f) },
                    onRotateCcw = { canvasViewRef?.rotateSelectedStrokes(-15f) },
                    onRotateCw = { canvasViewRef?.rotateSelectedStrokes(15f) },
                    onApplyStyle = { canvasViewRef?.applyCurrentStyleToSelection() },
                    onDelete = { canvasViewRef?.deleteSelectedStrokes() },
                    onClearSelection = { canvasViewRef?.clearSelection() },
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .padding(top = 12.dp)
                )
            }

            // Bottom-Left Stylus Labs Write Mini Statusbar
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .padding(12.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(Color(0xD9222222))
                    .border(1.dp, Color(0xFF3F3F46), RoundedCornerShape(8.dp))
                    .padding(horizontal = 10.dp, vertical = 6.dp)
            ) {
                WriteSvgIcon(asset = WriteIconAsset.ZOOM, tint = Color(0xFF94A3B8), size = 16.dp)
                Text(
                    text = "${(zoomScale * 100).roundToInt()}%",
                    color = Color(0xFFF8FAFC),
                    fontFamily = FontFamily.Monospace,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold
                )
                StatusbarMiniPill(label = "−") { canvasViewRef?.zoomBy(0.8f) }
                StatusbarMiniPill(label = "100%") { canvasViewRef?.resetZoom100() }
                StatusbarMiniPill(label = "Fit Width") { canvasViewRef?.zoomFitWidth() }
                StatusbarMiniPill(label = "+") { canvasViewRef?.zoomBy(1.25f) }

                WriteToolbarDivider()

                StatusbarMiniPill(
                    label = if (singleTouchPan) "Finger: Pan" else "Finger: Tool",
                    highlighted = !singleTouchPan
                ) {
                    singleTouchPan = !singleTouchPan
                    canvasViewRef?.singleTouchPanInZoomedMode = singleTouchPan
                }
            }
        }
    }

    if (showPageSetupDialog) {
        WritePageSetupModal(
            currentSetup = pageSetup,
            onDismiss = { showPageSetupDialog = false },
            onApplyFull = { w, h, xr, yr, ml, paperColor, ruleColor, style ->
                canvasViewRef?.applyPageSetup(
                    newWidth = w,
                    newHeight = h,
                    newXRuling = xr,
                    newYRuling = yr,
                    newMarginLeft = ml,
                    newPaperColorArgb = paperColor,
                    newRuleColorArgb = ruleColor,
                    newRulingStyle = style.nativeCode
                )
                showPageSetupDialog = false
            },
            onLockContentAsTemplate = {
                canvasViewRef?.lockCurrentDrawingAsTemplate()
                pageSetup = canvasViewRef?.queryPageSetupState() ?: pageSetup
            },
            onUnlockTemplateToContent = {
                canvasViewRef?.unlockTemplateBackgroundToEdit()
                pageSetup = canvasViewRef?.queryPageSetupState() ?: pageSetup
            },
            onClearCustomTemplate = {
                canvasViewRef?.clearCustomTemplateBackground()
                pageSetup = canvasViewRef?.queryPageSetupState() ?: pageSetup
            },
            onInsertImage = { bytes, asBackground ->
                canvasViewRef?.insertImageBytes(bytes, asBackground = asBackground)
                pageSetup = canvasViewRef?.queryPageSetupState() ?: pageSetup
            }
        )
    }

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
                        onCloseZoom(pageSetup.width, pageSetup.height)
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

@Composable
private fun WriteMainToolButton(
    icon: WriteIconAsset,
    label: String,
    selected: Boolean,
    enabled: Boolean = true,
    tint: Color? = null,
    showLabel: Boolean = false,
    onClick: () -> Unit
) {
    val bgColor = when {
        !enabled -> Color.Transparent
        selected -> Color(0xFF0284C7)
        else -> Color(0xFF2E2E2E)
    }
    val contentTint = when {
        !enabled -> Color(0xFF52525B)
        selected -> Color.White
        tint != null -> tint
        else -> Color(0xFFE2E8F0)
    }
    val hasDropdown = label.contains("▾")
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .clip(RoundedCornerShape(6.dp))
            .background(bgColor)
            .border(
                width = 1.dp,
                color = if (selected) Color(0xFF38BDF8) else Color(0xFF3F3F46),
                shape = RoundedCornerShape(6.dp)
            )
            .clickable(enabled = enabled) { onClick() }
            .padding(
                horizontal = if (showLabel) 8.dp else if (hasDropdown) 6.dp else 8.dp,
                vertical = 6.dp
            )
            .semantics(mergeDescendants = true) {
                contentDescription = label.replace("▾", "").trim()
            }
    ) {
        WriteSvgIcon(asset = icon, tint = contentTint, size = 18.dp)
        if (showLabel) {
            Spacer(modifier = Modifier.width(5.dp))
            Text(
                text = label,
                color = contentTint,
                fontSize = 11.sp,
                fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium
            )
        } else if (hasDropdown) {
            Spacer(modifier = Modifier.width(2.dp))
            Text(
                text = "▾",
                color = contentTint,
                fontSize = 9.sp,
                fontWeight = FontWeight.Bold
            )
        }
    }
}

@Composable
private fun WriteWidthPreviewButton(
    strokeWidthPx: Float,
    selected: Boolean,
    onClick: () -> Unit
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .clip(RoundedCornerShape(4.dp))
            .background(if (selected) Color(0xFF0284C7) else Color(0xFF1F2937))
            .border(
                width = if (selected) 1.5.dp else 1.dp,
                color = if (selected) Color.White else Color(0xFF4B5563),
                shape = RoundedCornerShape(4.dp)
            )
            .clickable { onClick() }
            .padding(horizontal = 6.dp, vertical = 4.dp)
    ) {
        Canvas(modifier = Modifier.size(width = 20.dp, height = 12.dp)) {
            drawLine(
                color = Color.White,
                start = Offset(2f, size.height / 2f),
                end = Offset(size.width - 2f, size.height / 2f),
                strokeWidth = strokeWidthPx.coerceIn(1.5f, 10f),
                cap = StrokeCap.Round
            )
        }
        Text(
            text = strokeWidthPx.toString(),
            color = Color(0xFFE2E8F0),
            fontSize = 9.sp,
            fontFamily = FontFamily.Monospace
        )
    }
}

@Composable
private fun StatusbarMiniPill(
    label: String,
    highlighted: Boolean = false,
    onClick: () -> Unit
) {
    Text(
        text = label,
        color = if (highlighted) Color(0xFF38BDF8) else Color(0xFFE2E8F0),
        fontSize = 11.sp,
        fontWeight = FontWeight.Medium,
        modifier = Modifier
            .clip(RoundedCornerShape(4.dp))
            .background(if (highlighted) Color(0xFF1E3A8A) else Color(0xFF333333))
            .clickable { onClick() }
            .padding(horizontal = 7.dp, vertical = 3.dp)
    )
}

@Composable
fun WriteToolbarDivider() {
    Box(
        modifier = Modifier
            .width(1.dp)
            .height(22.dp)
            .background(Color(0xFF4B5563))
    )
}

/**
 * Stylus Labs Write Ruling, Template Background & Custom Template Designer Dialog (`RulingDialog`).
 */
@Composable
fun WritePageSetupModal(
    currentSetup: WritePageSetupState,
    onDismiss: () -> Unit,
    onApply: ((xRuling: Int, yRuling: Int, marginLeft: Int, paperColorArgb: Int, ruleColorArgb: Int) -> Unit)? = null,
    onApplyFull: ((
        width: Int,
        height: Int,
        xRuling: Int,
        yRuling: Int,
        marginLeft: Int,
        paperColorArgb: Int,
        ruleColorArgb: Int,
        rulingStyle: WriteTemplateStyle
    ) -> Unit)? = null,
    onLockContentAsTemplate: (() -> Unit)? = null,
    onUnlockTemplateToContent: (() -> Unit)? = null,
    onClearCustomTemplate: (() -> Unit)? = null,
    onInsertImage: ((imageBytes: ByteArray, asBackgroundTemplate: Boolean) -> Unit)? = null
) {
    var width by remember { mutableStateOf(currentSetup.width) }
    var height by remember { mutableStateOf(currentSetup.height) }
    var xRuling by remember { mutableStateOf(currentSetup.xRuling) }
    var yRuling by remember { mutableStateOf(currentSetup.yRuling) }
    var marginLeft by remember { mutableStateOf(currentSetup.marginLeft) }
    var paperColor by remember { mutableStateOf(currentSetup.paperColorArgb) }
    var ruleColor by remember { mutableStateOf(currentSetup.ruleColorArgb) }
    var rulingStyle by remember { mutableStateOf(WriteTemplateStyle.fromNativeCode(currentSetup.rulingStyle)) }

    val context = LocalContext.current
    var pendingImageAsBackground by remember { mutableStateOf(true) }
    val imagePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri ->
        if (uri != null && onInsertImage != null) {
            try {
                context.contentResolver.openInputStream(uri)?.use { stream ->
                    val bytes = stream.readBytes()
                    if (bytes.isNotEmpty()) {
                        onInsertImage(bytes, pendingImageAsBackground)
                        if (pendingImageAsBackground) {
                            rulingStyle = WriteTemplateStyle.CUSTOM_DESIGNER
                        }
                    }
                }
            } catch (_: Exception) {
            }
        }
    }

    val paperPresets = listOf(
        "Warm Cream" to 0xFFFFFDF8.toInt(),
        "Pure White" to 0xFFFFFFFF.toInt(),
        "Legal Pad" to 0xFFFFFBEB.toInt(),
        "Sepia" to 0xFFF5EFE6.toInt(),
        "Blueprint" to 0xFF0F2942.toInt(),
        "Blackboard" to 0xFF1E293B.toInt()
    )

    val rulePresets = listOf(
        "Classic Blue" to 0xFF4A90E2.toInt(),
        "Light Gray" to 0xFF94A3B8.toInt(),
        "Graph Green" to 0xFF16A34A.toInt(),
        "Sepia Brown" to 0xFFA16207.toInt(),
        "Dark Slate" to 0xFF475569.toInt()
    )

    val sizePresets = listOf(
        Triple("Current (${currentSetup.width}×${currentSetup.height})", currentSetup.width, currentSetup.height),
        Triple("Inline Card (1200×600)", 1200, 600),
        Triple("Letter (1224×1584)", 1224, 1584),
        Triple("Letter Landscape (1584×1224)", 1584, 1224),
        Triple("A4 (1190×1684)", 1190, 1684),
        Triple("A4 Landscape (1684×1190)", 1684, 1190)
    )

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Stylus Labs Write — Template, Page & Custom Designer") },
        text = {
            Column(
                verticalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 520.dp)
                    .verticalScroll(rememberScrollState())
            ) {
                // 1. Template Style Selector
                Text("1. Canvas Template Background:", fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.horizontalScroll(rememberScrollState())
                ) {
                    TemplateChip(
                        label = "Blank",
                        selected = rulingStyle == WriteTemplateStyle.STANDARD && xRuling == 0 && yRuling == 0,
                        onClick = {
                            rulingStyle = WriteTemplateStyle.STANDARD
                            xRuling = 0
                            yRuling = 0
                            marginLeft = 0
                        }
                    )
                    TemplateChip(
                        label = "Ruled Lines",
                        selected = rulingStyle == WriteTemplateStyle.STANDARD && xRuling == 0 && yRuling > 0 && marginLeft == 0,
                        onClick = {
                            rulingStyle = WriteTemplateStyle.STANDARD
                            xRuling = 0
                            if (yRuling <= 0) yRuling = 40
                            marginLeft = 0
                        }
                    )
                    TemplateChip(
                        label = "Square Grid",
                        selected = rulingStyle == WriteTemplateStyle.STANDARD && xRuling > 0 && yRuling > 0,
                        onClick = {
                            rulingStyle = WriteTemplateStyle.STANDARD
                            if (xRuling <= 0) xRuling = 40
                            if (yRuling <= 0) yRuling = 40
                            marginLeft = 0
                        }
                    )
                    TemplateChip(
                        label = "Dotted Grid",
                        selected = rulingStyle == WriteTemplateStyle.DOTTED,
                        onClick = {
                            rulingStyle = WriteTemplateStyle.DOTTED
                            if (xRuling <= 0) xRuling = 40
                            if (yRuling <= 0) yRuling = 40
                        }
                    )
                    TemplateChip(
                        label = "Engineering 5×5",
                        selected = rulingStyle == WriteTemplateStyle.ENGINEER_GRID,
                        onClick = {
                            rulingStyle = WriteTemplateStyle.ENGINEER_GRID
                            xRuling = 25
                            yRuling = 25
                            marginLeft = 60
                        }
                    )
                    TemplateChip(
                        label = "Isometric Grid",
                        selected = rulingStyle == WriteTemplateStyle.ISOMETRIC,
                        onClick = {
                            rulingStyle = WriteTemplateStyle.ISOMETRIC
                            if (yRuling <= 0) yRuling = 40
                            marginLeft = 0
                        }
                    )
                    TemplateChip(
                        label = "Music Staff",
                        selected = rulingStyle == WriteTemplateStyle.MUSIC_STAFF,
                        onClick = {
                            rulingStyle = WriteTemplateStyle.MUSIC_STAFF
                            yRuling = 14
                            marginLeft = 40
                        }
                    )
                    TemplateChip(
                        label = "Cornell Notes",
                        selected = rulingStyle == WriteTemplateStyle.CORNELL_SUMMARY,
                        onClick = {
                            rulingStyle = WriteTemplateStyle.CORNELL_SUMMARY
                            xRuling = 0
                            if (yRuling <= 0) yRuling = 40
                            marginLeft = 200
                        }
                    )
                    TemplateChip(
                        label = "Custom Designer",
                        selected = rulingStyle == WriteTemplateStyle.CUSTOM_DESIGNER,
                        onClick = {
                            rulingStyle = WriteTemplateStyle.CUSTOM_DESIGNER
                        }
                    )
                }

                // 2. Custom Designer & Image Template Tools
                Column(
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(8.dp))
                        .background(Color(0xFF1E293B))
                        .border(1.dp, Color(0xFF334155), RoundedCornerShape(8.dp))
                        .padding(10.dp)
                ) {
                    Text(
                        text = "Custom Template & Image Designer (Stylus Labs Write `contentToRuling`):",
                        color = Color(0xFF38BDF8),
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 12.sp
                    )
                    Text(
                        text = "Draw any layout, axes, form, or import an image, then lock it into the non-erasable background layer.",
                        color = Color(0xFF94A3B8),
                        fontSize = 11.sp
                    )
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.horizontalScroll(rememberScrollState())
                    ) {
                        if (onInsertImage != null) {
                            OutlinedButton(
                                onClick = {
                                    pendingImageAsBackground = true
                                    imagePickerLauncher.launch("image/*")
                                }
                            ) {
                                Text("Set Custom Background Image", fontSize = 11.sp)
                            }
                            OutlinedButton(
                                onClick = {
                                    pendingImageAsBackground = false
                                    imagePickerLauncher.launch("image/*")
                                }
                            ) {
                                Text("Insert Image on Canvas", fontSize = 11.sp)
                            }
                        }
                        if (onLockContentAsTemplate != null) {
                            OutlinedButton(
                                onClick = {
                                    onLockContentAsTemplate()
                                    rulingStyle = WriteTemplateStyle.CUSTOM_DESIGNER
                                }
                            ) {
                                Text("Lock Drawing as Template", fontSize = 11.sp)
                            }
                        }
                        if (onUnlockTemplateToContent != null) {
                            OutlinedButton(onClick = onUnlockTemplateToContent) {
                                Text("Unlock Template to Edit", fontSize = 11.sp)
                            }
                        }
                        if (onClearCustomTemplate != null) {
                            OutlinedButton(
                                onClick = {
                                    onClearCustomTemplate()
                                    rulingStyle = WriteTemplateStyle.STANDARD
                                }
                            ) {
                                Text("Reset Custom Template", fontSize = 11.sp)
                            }
                        }
                    }
                }

                // 3. Spacing & Margin Fine Controls
                Text(
                    text = "Spacing & Margins: X=${xRuling}px • Y=${yRuling}px • Left Margin=${marginLeft}px • Page=${width}×${height}",
                    fontSize = 11.sp,
                    fontFamily = FontFamily.Monospace,
                    color = Color(0xFF64748B)
                )
                Row(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    modifier = Modifier.horizontalScroll(rememberScrollState())
                ) {
                    OutlinedButton(onClick = { yRuling = (yRuling - 5).coerceAtLeast(0) }) {
                        Text("Y Spacing −5", fontSize = 11.sp)
                    }
                    OutlinedButton(onClick = { yRuling = (yRuling + 5).coerceAtMost(160) }) {
                        Text("Y Spacing +5", fontSize = 11.sp)
                    }
                    OutlinedButton(onClick = { xRuling = (xRuling - 5).coerceAtLeast(0) }) {
                        Text("X Spacing −5", fontSize = 11.sp)
                    }
                    OutlinedButton(onClick = { xRuling = (xRuling + 5).coerceAtMost(160) }) {
                        Text("X Spacing +5", fontSize = 11.sp)
                    }
                    OutlinedButton(onClick = { marginLeft = if (marginLeft == 0) 80 else if (marginLeft == 80) 180 else 0 }) {
                        Text("Margin (${marginLeft}px)", fontSize = 11.sp)
                    }
                }

                // 4. Paper Size Presets
                Text("2. Paper Dimensions:", fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                Row(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    modifier = Modifier.horizontalScroll(rememberScrollState())
                ) {
                    sizePresets.forEach { (label, w, h) ->
                        TemplateChip(
                            label = label,
                            selected = width == w && height == h,
                            onClick = {
                                width = w
                                height = h
                            }
                        )
                    }
                }

                // 5. Paper Background & Rule Line Color Presets
                Text("3. Paper Background Color:", fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.horizontalScroll(rememberScrollState())
                ) {
                    paperPresets.forEach { (name, argb) ->
                        val selected = paperColor == argb
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            modifier = Modifier
                                .clip(RoundedCornerShape(6.dp))
                                .border(
                                    width = if (selected) 2.dp else 1.dp,
                                    color = if (selected) Color(0xFF0284C7) else Color(0xFF94A3B8),
                                    shape = RoundedCornerShape(6.dp)
                                )
                                .clickable {
                                    paperColor = argb
                                    if (argb == 0xFF1E293B.toInt() || argb == 0xFF0F2942.toInt()) {
                                        ruleColor = 0xFF475569.toInt()
                                    } else if (ruleColor == 0xFF475569.toInt()) {
                                        ruleColor = 0xFF4A90E2.toInt()
                                    }
                                }
                                .padding(6.dp)
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(24.dp)
                                    .clip(CircleShape)
                                    .background(Color(argb))
                                    .border(1.dp, Color.Gray, CircleShape)
                            )
                            Spacer(modifier = Modifier.height(3.dp))
                            Text(name, fontSize = 10.sp)
                        }
                    }
                }

                Text("4. Rule / Grid Line Color:", fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.horizontalScroll(rememberScrollState())
                ) {
                    rulePresets.forEach { (name, argb) ->
                        val selected = ruleColor == argb
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            modifier = Modifier
                                .clip(RoundedCornerShape(6.dp))
                                .border(
                                    width = if (selected) 2.dp else 1.dp,
                                    color = if (selected) Color(0xFF0284C7) else Color(0xFF94A3B8),
                                    shape = RoundedCornerShape(6.dp)
                                )
                                .clickable { ruleColor = argb }
                                .padding(6.dp)
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(20.dp)
                                    .clip(CircleShape)
                                    .background(Color(argb))
                                    .border(1.dp, Color.Gray, CircleShape)
                            )
                            Spacer(modifier = Modifier.height(3.dp))
                            Text(name, fontSize = 10.sp)
                        }
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    if (onApplyFull != null) {
                        onApplyFull(width, height, xRuling, yRuling, marginLeft, paperColor, ruleColor, rulingStyle)
                    } else if (onApply != null) {
                        onApply(xRuling, yRuling, marginLeft, paperColor, ruleColor)
                    }
                }
            ) {
                Text("Apply Template & Setup")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        }
    )
}

@Composable
private fun TemplateChip(
    label: String,
    selected: Boolean,
    onClick: () -> Unit
) {
    Text(
        text = label,
        color = if (selected) Color.White else Color(0xFFCBD5E1),
        fontSize = 12.sp,
        fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
        modifier = Modifier
            .clip(RoundedCornerShape(6.dp))
            .background(if (selected) Color(0xFF0284C7) else Color(0xFF1E293B))
            .border(
                width = 1.dp,
                color = if (selected) Color(0xFF38BDF8) else Color(0xFF475569),
                shape = RoundedCornerShape(6.dp)
            )
            .clickable { onClick() }
            .padding(horizontal = 10.dp, vertical = 6.dp)
    )
}
