package com.orgwritenotes.ui

import com.orgwritenotes.ink.StrokeClipboard
import com.orgwritenotes.ink.WriteTemplateStyle
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
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.composed
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.runtime.snapshots.SnapshotStateMap
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.orgwritenotes.ink.OnyxEInkBridge
import com.orgwritenotes.ink.StylusToolMode
import com.orgwritenotes.ink.WriteNativeBridge
import com.orgwritenotes.ink.WritePageSetupState
import com.orgwritenotes.ink.WritePenTip
import kotlin.math.roundToInt

// Write-style toolbar chrome, selection popup bar and their buttons.

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
    onTrimWhitespace: (() -> Unit)? = null,
    onOpenPageSetup: () -> Unit,
    onDeleteImage: (() -> Unit)? = null,
    pressureEnabled: Boolean = true,
    chiselAngle: Float = 45f,
    onTogglePressure: (() -> Unit)? = null,
    onChangeChiselAngle: ((Float) -> Unit)? = null,
    onInsertImage: (() -> Unit)? = null,
    onDeleteSelection: (() -> Unit)? = null,
    isDarkCanvasTheme: Boolean = false,
    onToggleDarkCanvasTheme: (() -> Unit)? = null,
    onUserInteracted: () -> Unit = {}
) {
    val context = LocalContext.current
    val isEInk = OnyxEInkBridge.isEInkDevice
    val barBg = if (isEInk) Color.White else Color(0xFF222222)
    val penBarBg = if (isEInk) Color(0xFFF8FAFC) else Color(0xFF323232)
    val chipBg = if (isEInk) Color.White else Color(0xFF1F2937)
    val chipActiveBg = if (isEInk) Color.Black else Color(0xFF0284C7)
    val chipBorder = if (isEInk) Color.Black else Color(0xFF4B5563)
    val chipActiveBorder = if (isEInk) Color.Black else Color(0xFF38BDF8)
    val textPrimary = if (isEInk) Color.Black else Color.White
    val textSecondary = if (isEInk) Color.Black else Color(0xFFCBD5E1)
    val textMuted = if (isEInk) Color(0xFF334155) else Color(0xFF94A3B8)
    val accentColor = if (isEInk) Color.Black else Color(0xFF38BDF8)

    var showEraseMenu by remember { mutableStateOf(false) }
    var showSelectMenu by remember { mutableStateOf(false) }
    var showInsertSpaceMenu by remember { mutableStateOf(false) }
    var customPens by remember { mutableStateOf(WritePenPresetStorage.loadCustomPens(context)) }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(if (isZoomedFullScreen) RoundedCornerShape(0.dp) else RoundedCornerShape(10.dp))
            .border(
                width = if (isZoomedFullScreen) 0.dp else 1.dp,
                color = if (isZoomedFullScreen) Color.Transparent else accentColor,
                shape = if (isZoomedFullScreen) RoundedCornerShape(0.dp) else RoundedCornerShape(10.dp)
            )
            .background(barBg)
    ) {
        // ====================================================================
        // 1. Stylus Labs Write Main Toolbar
        // ====================================================================
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            modifier = Modifier
                .fillMaxWidth()
                .background(barBg)
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 10.dp, vertical = 6.dp)
        ) {
            if (isZoomedFullScreen) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .clip(RoundedCornerShape(6.dp))
                        .background(if (isEInk) Color.White else Color(0xFF334155))
                        .border(1.dp, if (isEInk) Color.Black else Color.Transparent, RoundedCornerShape(6.dp))
                        .clickable { onZoomOrCloseFullScreen() }
                        .padding(horizontal = if (showLabels) 10.dp else 8.dp, vertical = 6.dp)
                        .semantics(mergeDescendants = true) { contentDescription = "Done" }
                ) {
                    WriteSvgIcon(asset = WriteIconAsset.BACK, tint = textPrimary, size = 18.dp)
                    if (showLabels) {
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = "Done",
                            color = textPrimary,
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
                    color = accentColor,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = "${pageSetup.width}×${pageSetup.height} • ${pageSetup.strokeCount} strokes",
                    color = textMuted,
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
                            enabled = StrokeClipboard.hasStrokes,
                            onClick = {
                                onUserInteracted()
                                onPasteClipboard()
                                showSelectMenu = false
                            }
                        )
                    }
                }
            }

            // One-tap Paste whenever strokes were copied in any sketch (also offered in the Select menu).
            if (onPasteClipboard != null && StrokeClipboard.hasStrokes) {
                WriteMainToolButton(
                    icon = WriteIconAsset.DUPLICATE,
                    label = "Paste (${StrokeClipboard.strokeCount})",
                    selected = false,
                    showLabel = true,
                    onClick = {
                        onUserInteracted()
                        onPasteClipboard()
                    }
                )
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

            // Ephemeral Laser Pointer Tool
            val isLaserActive = activeMode == StylusToolMode.LASER
            Text(
                text = if (isEInk) "◉ Laser" else "🔴 Laser",
                color = when {
                    isEInk -> if (isLaserActive) Color.White else Color.Black
                    isLaserActive -> Color.White
                    else -> Color(0xFFFCA5A5)
                },
                fontSize = 11.sp,
                fontWeight = if (isLaserActive) FontWeight.Bold else FontWeight.SemiBold,
                modifier = Modifier
                    .clip(RoundedCornerShape(6.dp))
                    .background(
                        when {
                            isEInk -> if (isLaserActive) Color.Black else Color.White
                            isLaserActive -> Color(0xFFDC2626)
                            else -> Color(0xFF2E2E2E)
                        }
                    )
                    .border(
                        1.dp,
                        when {
                            isEInk -> Color.Black
                            isLaserActive -> Color(0xFFF87171)
                            else -> Color(0xFF3F3F46)
                        },
                        RoundedCornerShape(6.dp)
                    )
                    .clickable {
                        onUserInteracted()
                        onSelectToolMode(
                            if (isLaserActive) StylusToolMode.PEN else StylusToolMode.LASER,
                            selectedColor,
                            selectedWidth,
                            selectedPenTip,
                            null,
                            null,
                            null
                        )
                    }
                    .padding(horizontal = 8.dp, vertical = 6.dp)
            )

            Spacer(modifier = Modifier.weight(1f))

            // Expand Down, Expand Right & Trim Fit
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
            if (onTrimWhitespace != null) {
                Text(
                    text = "✂ Trim Fit",
                    color = if (isEInk) Color.Black else Color(0xFFE2E8F0),
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier
                        .clip(RoundedCornerShape(6.dp))
                        .background(if (isEInk) Color.White else Color(0xFF2E2E2E))
                        .border(1.dp, if (isEInk) Color.Black else Color(0xFF3F3F46), RoundedCornerShape(6.dp))
                        .clickable {
                            onUserInteracted()
                            onTrimWhitespace()
                        }
                        .padding(horizontal = 8.dp, vertical = 6.dp)
                )
            }

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

            // On E-Ink devices, dark/light theme option is intentionally hidden: E-Ink is always black on light background.
            if (!isEInk && onToggleDarkCanvasTheme != null) {
                Text(
                    text = if (isDarkCanvasTheme) "☾ Dark" else "☀ Light",
                    color = if (isDarkCanvasTheme) Color(0xFF38BDF8) else Color(0xFFFDE047),
                    fontSize = 11.sp,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier
                        .clip(RoundedCornerShape(6.dp))
                        .background(Color(0xFF2E2E2E))
                        .border(
                            1.dp,
                            if (isDarkCanvasTheme) Color(0xFF38BDF8).copy(alpha = 0.6f) else Color(0xFF3F3F46),
                            RoundedCornerShape(6.dp)
                        )
                        .clickable {
                            onUserInteracted()
                            onToggleDarkCanvasTheme()
                        }
                        .padding(horizontal = 8.dp, vertical = 6.dp)
                )
            }

            // Toolbar layout: dock position and left-handed mirroring.
            Box {
                var showLayoutMenu by remember { mutableStateOf(false) }
                Text(
                    text = "Layout ▾",
                    color = textSecondary,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier
                        .clip(RoundedCornerShape(6.dp))
                        .background(chipBg)
                        .border(1.dp, chipBorder, RoundedCornerShape(6.dp))
                        .clickable {
                            onUserInteracted()
                            showLayoutMenu = true
                        }
                        .padding(horizontal = 8.dp, vertical = 6.dp)
                )
                DropdownMenu(expanded = showLayoutMenu, onDismissRequest = { showLayoutMenu = false }) {
                    listOf(
                        ToolbarDock.TOP to "Dock at top",
                        ToolbarDock.BOTTOM to "Dock at bottom",
                        ToolbarDock.FLOATING to "Floating palette (full-screen editor)"
                    ).forEach { (dock, label) ->
                        DropdownMenuItem(
                            text = { Text((if (UiPrefs.toolbarDock == dock) "✓ " else "    ") + label) },
                            onClick = {
                                UiPrefs.setToolbarDock(context, dock)
                                showLayoutMenu = false
                            }
                        )
                    }
                    DropdownMenuItem(
                        text = { Text((if (UiPrefs.leftHanded) "✓ " else "    ") + "Left-handed (mirror layout)") },
                        onClick = {
                            UiPrefs.setLeftHanded(context, !UiPrefs.leftHanded)
                            showLayoutMenu = false
                        }
                    )
                }
            }

            if (hasSelection && onDeleteSelection != null) {
                WriteToolbarDivider()
                WriteMainToolButton(
                    icon = WriteIconAsset.DELETE,
                    label = "Delete",
                    selected = false,
                    tint = if (isEInk) Color.Black else Color(0xFFEF4444),
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
                    tint = textMuted,
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
                        color = textMuted,
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
        // 2. Stylus Labs Write Second-Row `PenToolbar`
        // ====================================================================
        if (isEInk) {
            if (showPenToolbar) {
                HorizontalDivider(color = Color.Black, thickness = 1.dp)
            }
        }
        val penToolbarContent: @Composable () -> Unit = {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .background(penBarBg)
                    .horizontalScroll(rememberScrollState())
                    .padding(horizontal = 12.dp, vertical = 6.dp)
            ) {
                // Saved Pen Presets + User Custom Saved Pens (persisted in SharedPreferences)
                (WRITE_SAVED_PENS + customPens).forEachIndexed { idx, preset ->
                    val isCustomPreset = idx >= WRITE_SAVED_PENS.size
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
                            .background(if (isPresetActive) chipActiveBg else chipBg)
                            .border(
                                1.dp,
                                if (isPresetActive) chipActiveBorder else chipBorder,
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
                        val displayDotColor = if (!isEInk && isDarkCanvasTheme && !preset.isHighlighter && preset.colorArgb == 0xFF1E293B.toInt()) {
                            Color(0xFFF8FAFC)
                        } else if (isEInk && isPresetActive && !preset.isHighlighter) {
                            Color.White
                        } else {
                            Color(preset.colorArgb)
                        }
                        Box(
                            modifier = Modifier
                                .size(10.dp)
                                .clip(CircleShape)
                                .background(displayDotColor)
                                .border(0.5.dp, if (isEInk) Color.Black else Color.Transparent, CircleShape)
                        )
                        Spacer(modifier = Modifier.width(5.dp))
                        Text(
                            text = preset.name,
                            color = if (isPresetActive) Color.White else textPrimary,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Medium
                        )
                        if (isCustomPreset) {
                            Spacer(modifier = Modifier.width(5.dp))
                            Text(
                                text = "×",
                                color = if (isPresetActive) Color.White else textMuted,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.clickable {
                                    onUserInteracted()
                                    val customIdx = idx - WRITE_SAVED_PENS.size
                                    val updated = customPens.filterIndexed { i, _ -> i != customIdx }
                                    customPens = updated
                                    WritePenPresetStorage.saveCustomPens(context, updated)
                                }
                            )
                        }
                    }
                }

                // + Save Current Pen Preset
                Text(
                    text = "+ Save Pen",
                    color = accentColor,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier
                        .clip(RoundedCornerShape(4.dp))
                        .background(chipBg)
                        .border(1.dp, if (isEInk) Color.Black else accentColor.copy(alpha = 0.5f), RoundedCornerShape(4.dp))
                        .clickable {
                            onUserInteracted()
                            val newName = "Pen ${customPens.size + 1} (${selectedWidth}px)"
                            val updated = customPens + SavedWritePenPreset(
                                name = newName,
                                colorArgb = selectedColor,
                                widthPx = selectedWidth,
                                tip = selectedPenTip,
                                isHighlighter = activeMode == StylusToolMode.HIGHLIGHTER
                            )
                            customPens = updated
                            WritePenPresetStorage.saveCustomPens(context, updated)
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
                        val swatchColor = if (!isEInk && isDarkCanvasTheme && argb == 0xFF1E293B.toInt()) {
                            Color(0xFFF8FAFC)
                        } else {
                            Color(argb)
                        }
                        val onSwatchClick = {
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
                        if (isEInk) {
                            // Grayscale panel: label each swatch and mark the selected one with a check.
                            Column(
                                horizontalAlignment = Alignment.CenterHorizontally,
                                modifier = Modifier
                                    .clip(RoundedCornerShape(4.dp))
                                    .background(if (isSelected) Color.Black else Color.White)
                                    .border(1.dp, Color.Black, RoundedCornerShape(4.dp))
                                    .clickable { onSwatchClick() }
                                    .semantics { contentDescription = "Pen color ${penColorName(argb)}" }
                                    .padding(horizontal = 4.dp, vertical = 2.dp)
                            ) {
                                Box(
                                    contentAlignment = Alignment.Center,
                                    modifier = Modifier
                                        .size(16.dp)
                                        .clip(CircleShape)
                                        .background(swatchColor)
                                        .border(1.dp, if (isSelected) Color.White else Color.Black, CircleShape)
                                ) {
                                    if (isSelected) Text("✓", color = Color.White, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                                }
                                Text(
                                    text = penColorName(argb),
                                    color = if (isSelected) Color.White else Color.Black,
                                    fontSize = 9.sp,
                                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium
                                )
                            }
                        } else {
                            Box(
                                modifier = Modifier
                                    .size(if (isSelected) 24.dp else 20.dp)
                                    .clip(RoundedCornerShape(4.dp))
                                    .background(swatchColor)
                                    .border(
                                        width = if (isSelected) 2.5.dp else 1.dp,
                                        color = if (isSelected) accentColor else Color(0xFF64748B),
                                        shape = RoundedCornerShape(4.dp)
                                    )
                                    .clickable { onSwatchClick() }
                                    .semantics { contentDescription = "Pen color ${penColorName(argb)}" }
                            )
                        }
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
                        color = if (isTipActive) Color.White else textSecondary,
                        fontSize = 11.sp,
                        fontWeight = if (isTipActive) FontWeight.Bold else FontWeight.Normal,
                        modifier = Modifier
                            .clip(RoundedCornerShape(4.dp))
                            .background(if (isTipActive) chipActiveBg else chipBg)
                            .border(1.dp, if (isEInk) Color.Black else Color.Transparent, RoundedCornerShape(4.dp))
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
                    color = when {
                        isEInk -> if (isHighlight) Color.White else Color.Black
                        isHighlight -> Color(0xFF0F172A)
                        else -> Color(0xFFFDE047)
                    },
                    fontSize = 11.sp,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier
                        .clip(RoundedCornerShape(4.dp))
                        .background(
                            when {
                                isEInk -> if (isHighlight) Color.Black else Color.White
                                isHighlight -> Color(0xFFFACC15)
                                else -> Color(0xFF1F2937)
                            }
                        )
                        .border(1.dp, if (isEInk) Color.Black else Color.Transparent, RoundedCornerShape(4.dp))
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
                        color = when {
                            isEInk -> if (pressureEnabled) Color.White else Color.Black
                            pressureEnabled -> Color(0xFF38BDF8)
                            else -> Color(0xFF94A3B8)
                        },
                        fontSize = 11.sp,
                        fontWeight = if (pressureEnabled) FontWeight.SemiBold else FontWeight.Normal,
                        modifier = Modifier
                            .clip(RoundedCornerShape(4.dp))
                            .background(
                                when {
                                    isEInk -> if (pressureEnabled) Color.Black else Color.White
                                    pressureEnabled -> Color(0x330284C7)
                                    else -> Color(0xFF1F2937)
                                }
                            )
                            .border(
                                1.dp,
                                when {
                                    isEInk -> Color.Black
                                    pressureEnabled -> Color(0xFF0284C7)
                                    else -> Color(0xFF4B5563)
                                },
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
                        color = textMuted,
                        fontSize = 11.sp,
                        modifier = Modifier.padding(end = 2.dp)
                    )
                    listOf(30f, 45f, 60f).forEach { ang ->
                        val isAngActive = kotlin.math.abs(chiselAngle - ang) < 2f
                        Text(
                            text = "${ang.toInt()}°",
                            color = if (isAngActive) Color.White else textSecondary,
                            fontSize = 11.sp,
                            fontWeight = if (isAngActive) FontWeight.Bold else FontWeight.Normal,
                            modifier = Modifier
                                .clip(RoundedCornerShape(4.dp))
                                .background(if (isAngActive) chipActiveBg else chipBg)
                                .border(1.dp, if (isEInk) Color.Black else Color.Transparent, RoundedCornerShape(4.dp))
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
                        color = if (lineDrawing) Color.White else textSecondary,
                        fontSize = 11.sp,
                        fontWeight = if (lineDrawing) FontWeight.Bold else FontWeight.Normal,
                        modifier = Modifier
                            .clip(RoundedCornerShape(4.dp))
                            .background(if (lineDrawing) chipActiveBg else chipBg)
                            .border(
                                1.dp,
                                if (lineDrawing) chipActiveBorder else chipBorder,
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
                        color = if (snapToGrid) Color.White else textSecondary,
                        fontSize = 11.sp,
                        fontWeight = if (snapToGrid) FontWeight.Bold else FontWeight.Normal,
                        modifier = Modifier
                            .clip(RoundedCornerShape(4.dp))
                            .background(if (snapToGrid) chipActiveBg else chipBg)
                            .border(
                                1.dp,
                                if (snapToGrid) chipActiveBorder else chipBorder,
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
        if (isEInk) {
            if (showPenToolbar) {
                penToolbarContent()
                HorizontalDivider(color = Color.Black, thickness = 1.dp)
            }
        } else {
            AnimatedVisibility(
                visible = showPenToolbar,
                enter = expandVertically() + fadeIn(),
                exit = shrinkVertically() + fadeOut()
            ) {
                penToolbarContent()
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
    onConvertToText: (() -> Unit)? = null,
    onApplyStyle: () -> Unit,
    onDelete: () -> Unit,
    onClearSelection: () -> Unit,
    modifier: Modifier = Modifier
) {
    val isEInk = OnyxEInkBridge.isEInkDevice
    val barBg = if (isEInk) Color.White else Color(0xEE1E293B)
    val barBorder = if (isEInk) Color.Black else Color(0xFF38BDF8)
    val pillBg = if (isEInk) Color.White else Color(0xFF334155)
    val pillText = if (isEInk) Color.Black else Color.White
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        modifier = modifier
            .clip(RoundedCornerShape(10.dp))
            .background(barBg)
            .border(if (isEInk) 1.5.dp else 1.dp, barBorder, RoundedCornerShape(10.dp))
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 12.dp, vertical = 8.dp)
    ) {
        Text(
            text = "$selectionCount selected (Drag box to move, corners to scale, top handle to rotate)",
            color = if (isEInk) Color.Black else Color(0xFF38BDF8),
            fontSize = 11.sp,
            fontWeight = FontWeight.SemiBold
        )

        WriteToolbarDivider()

        if (onConvertToText != null) {
            Text(
                text = "✨ Convert to Text",
                color = if (isEInk) Color.White else Color(0xFFFDE047),
                fontSize = 11.sp,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier
                    .clip(RoundedCornerShape(6.dp))
                    .background(if (isEInk) Color.Black else Color(0xFF1E3A8A))
                    .border(1.dp, barBorder, RoundedCornerShape(6.dp))
                    .clickable { onConvertToText() }
                    .padding(horizontal = 8.dp, vertical = 5.dp)
            )
        }

        if (onCopy != null) {
            SelectionActionPill("Copy", onClick = onCopy)
        }
        if (onCut != null) {
            SelectionActionPill("Cut", onClick = onCut)
        }
        if (onPaste != null && StrokeClipboard.hasStrokes) {
            SelectionActionPill("Paste", onClick = onPaste)
        }

        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .clip(RoundedCornerShape(6.dp))
                .background(pillBg)
                .border(1.dp, if (isEInk) Color.Black else Color.Transparent, RoundedCornerShape(6.dp))
                .clickable { onDuplicate() }
                .padding(horizontal = 8.dp, vertical = 5.dp)
        ) {
            WriteSvgIcon(asset = WriteIconAsset.DUPLICATE, tint = pillText, size = 14.dp)
            Spacer(modifier = Modifier.width(4.dp))
            Text("Duplicate", color = pillText, fontSize = 11.sp)
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
                .background(pillBg)
                .border(1.dp, if (isEInk) Color.Black else Color.Transparent, RoundedCornerShape(6.dp))
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
            Text("Apply Pen Style", color = pillText, fontSize = 11.sp)
        }

        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .clip(RoundedCornerShape(6.dp))
                .background(if (isEInk) Color.White else Color(0xFF7F1D1D))
                .border(1.dp, if (isEInk) Color.Black else Color.Transparent, RoundedCornerShape(6.dp))
                .clickable { onDelete() }
                .padding(horizontal = 8.dp, vertical = 5.dp)
        ) {
            WriteSvgIcon(asset = WriteIconAsset.DELETE, tint = if (isEInk) Color.Black else Color(0xFFF1F5F9), size = 14.dp)
            Spacer(modifier = Modifier.width(4.dp))
            Text("Delete", color = if (isEInk) Color.Black else Color(0xFFFECACA), fontSize = 11.sp)
        }

        Text(
            text = "✕",
            color = if (isEInk) Color.Black else Color(0xFF94A3B8),
            fontSize = 14.sp,
            modifier = Modifier
                .clip(RoundedCornerShape(4.dp))
                .clickable { onClearSelection() }
                .padding(horizontal = 6.dp, vertical = 2.dp)
        )
    }
}

@Composable
internal fun SelectionActionPill(
    label: String,
    onClick: () -> Unit
) {
    val isEInk = OnyxEInkBridge.isEInkDevice
    Text(
        text = label,
        color = if (isEInk) Color.Black else Color.White,
        fontSize = 11.sp,
        fontWeight = FontWeight.Medium,
        modifier = Modifier
            .clip(RoundedCornerShape(6.dp))
            .background(if (isEInk) Color.White else Color(0xFF334155))
            .border(1.dp, if (isEInk) Color.Black else Color.Transparent, RoundedCornerShape(6.dp))
            .clickable { onClick() }
            .padding(horizontal = 8.dp, vertical = 5.dp)
    )
}


@Composable
internal fun WriteMainToolButton(
    icon: WriteIconAsset,
    label: String,
    selected: Boolean,
    enabled: Boolean = true,
    tint: Color? = null,
    showLabel: Boolean = false,
    onClick: () -> Unit
) {
    val isEInk = OnyxEInkBridge.isEInkDevice
    val bgColor = when {
        !enabled -> if (isEInk) Color(0xFFF1F5F9) else Color.Transparent
        selected -> if (isEInk) Color.Black else Color(0xFF0284C7)
        else -> if (isEInk) Color.White else Color(0xFF2E2E2E)
    }
    val contentTint = when {
        !enabled -> if (isEInk) Color(0xFF94A3B8) else Color(0xFF52525B)
        selected -> Color.White
        tint != null -> tint
        else -> if (isEInk) Color.Black else Color(0xFFE2E8F0)
    }
    val borderColor = when {
        isEInk -> if (!enabled) Color(0xFF64748B) else Color.Black
        selected -> Color(0xFF38BDF8)
        else -> Color(0xFF3F3F46)
    }
    val hasDropdown = label.contains("▾")
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .clip(RoundedCornerShape(6.dp))
            .background(bgColor)
            .border(
                width = 1.dp,
                color = borderColor,
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
internal fun WriteWidthPreviewButton(
    strokeWidthPx: Float,
    selected: Boolean,
    onClick: () -> Unit
) {
    val isEInk = OnyxEInkBridge.isEInkDevice
    val bgColor = when {
        isEInk -> if (selected) Color.Black else Color.White
        else -> if (selected) Color(0xFF0284C7) else Color(0xFF1F2937)
    }
    val fgColor = when {
        isEInk -> if (selected) Color.White else Color.Black
        else -> Color.White
    }
    val borderColor = when {
        isEInk -> Color.Black
        selected -> Color.White
        else -> Color(0xFF4B5563)
    }
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .clip(RoundedCornerShape(4.dp))
            .background(bgColor)
            .border(
                width = if (selected) 1.5.dp else 1.dp,
                color = borderColor,
                shape = RoundedCornerShape(4.dp)
            )
            .clickable { onClick() }
            .padding(horizontal = 6.dp, vertical = 4.dp)
    ) {
        Canvas(modifier = Modifier.size(width = 20.dp, height = 12.dp)) {
            drawLine(
                color = fgColor,
                start = Offset(2f, size.height / 2f),
                end = Offset(size.width - 2f, size.height / 2f),
                strokeWidth = strokeWidthPx.coerceIn(1.5f, 10f),
                cap = StrokeCap.Round
            )
        }
        Text(
            text = strokeWidthPx.toString(),
            color = if (isEInk) fgColor else Color(0xFFE2E8F0),
            fontSize = 9.sp,
            fontFamily = FontFamily.Monospace
        )
    }
}

@Composable
internal fun StatusbarMiniPill(
    label: String,
    highlighted: Boolean = false,
    onClick: () -> Unit
) {
    val isEInk = OnyxEInkBridge.isEInkDevice
    Text(
        text = label,
        color = when {
            isEInk -> if (highlighted) Color.White else Color.Black
            highlighted -> Color(0xFF38BDF8)
            else -> Color(0xFFE2E8F0)
        },
        fontSize = 11.sp,
        fontWeight = FontWeight.Medium,
        modifier = Modifier
            .clip(RoundedCornerShape(4.dp))
            .background(
                when {
                    isEInk -> if (highlighted) Color.Black else Color.White
                    highlighted -> Color(0xFF1E3A8A)
                    else -> Color(0xFF333333)
                }
            )
            .border(1.dp, if (isEInk) Color.Black else Color.Transparent, RoundedCornerShape(4.dp))
            .clickable { onClick() }
            .padding(horizontal = 7.dp, vertical = 3.dp)
    )
}

/** Reports this element's window bounds into [rects] while composed, so the Onyx pen layer can avoid it. */
internal fun Modifier.trackWindowRect(rects: SnapshotStateMap<String, android.graphics.Rect>, key: String): Modifier =
    composed {
        DisposableEffect(key) { onDispose { rects.remove(key) } }
        onGloballyPositioned { c ->
            val b = c.boundsInWindow()
            val r = android.graphics.Rect(b.left.roundToInt(), b.top.roundToInt(), b.right.roundToInt(), b.bottom.roundToInt())
            if (rects[key] != r) rects[key] = r
        }
    }

@Composable
fun WriteToolbarDivider() {
    Box(
        modifier = Modifier
            .width(1.dp)
            .height(22.dp)
            .background(if (OnyxEInkBridge.isEInkDevice) Color.Black else Color(0xFF4B5563))
    )
}
