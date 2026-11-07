package com.orgwritenotes.ui

import kotlin.math.roundToInt
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.BiasAlignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.orgwritenotes.ink.OnyxEInkBridge
import com.orgwritenotes.ink.StylusToolMode
import com.orgwritenotes.ink.WriteCanvasBlockView
import com.orgwritenotes.ink.WritePageSetupState
import com.orgwritenotes.ink.WritePenTip
import com.orgwritenotes.ink.WriteSelectionState
import com.orgwritenotes.model.OrgBlock
import com.orgwritenotes.sync.EmacsSyncClient

/**
 * Full-Screen Stylus Labs Write UI when the user chooses to zoom into a specific `.svgz` drawing.
 */
@Composable
fun WriteZoomedEditorOverlay(
    nodeId: String,
    block: OrgBlock.InkCanvasBlock,
    syncClient: EmacsSyncClient,
    sourceInlineView: WriteCanvasBlockView? = null,
    onCloseZoom: (updatedWidth: Int, updatedHeight: Int) -> Unit
) {
    val isEInk = OnyxEInkBridge.isEInkDevice
    var canvasViewRef by remember(block.blockId) { mutableStateOf<WriteCanvasBlockView?>(null) }
    val emacsTheme by syncClient.emacsTheme.collectAsState()
    val effectiveDark = if (isEInk) false else emacsTheme.isDark
    val bgArgb = remember(emacsTheme) { emacsTheme.resolvedDarkBgArgb() }
    val fgArgb = remember(emacsTheme) { emacsTheme.resolvedDarkFgArgb() }
    LaunchedEffect(effectiveDark, bgArgb, fgArgb, canvasViewRef) {
        canvasViewRef?.setAdaptiveTheme(effectiveDark, bgArgb, fgArgb)
    }

    var activeMode by remember(block.blockId) { mutableStateOf(StylusToolMode.PEN) }
    var lastEraserMode by remember(block.blockId) { mutableStateOf(StylusToolMode.ERASER) }
    var lastSelectMode by remember(block.blockId) { mutableStateOf(StylusToolMode.SELECT_LASSO) }
    var lastInsertSpaceMode by remember(block.blockId) { mutableStateOf(StylusToolMode.INSERT_SPACE_RULED) }

    var showPenToolbar by remember(block.blockId) { mutableStateOf(true) }
    var selectedColor by remember(block.blockId) {
        mutableStateOf(if (isEInk) 0xFF000000.toInt() else 0xFF1E293B.toInt())
    }
    var selectedWidth by remember(block.blockId) { mutableStateOf(2.6f) }
    var selectedPenTip by remember(block.blockId) { mutableStateOf(WritePenTip.ROUND) }
    var chiselAngle by remember(block.blockId) { mutableStateOf(45f) }
    var pressureEnabled by remember(block.blockId) { mutableStateOf(true) }
    var snapToGrid by remember(block.blockId) { mutableStateOf(false) }
    var lineDrawing by remember(block.blockId) { mutableStateOf(false) }
    var singleTouchPan by remember(block.blockId) { mutableStateOf(true) }

    var showPageSetupDialog by remember { mutableStateOf(false) }
    var showDeleteConfirmDialog by remember(block.blockId) { mutableStateOf(false) }
    var showOcrDialog by remember { mutableStateOf(false) }
    var ocrDraftText by remember { mutableStateOf("") }
    var splitTarget by remember(block.blockId) { mutableStateOf<SplitTarget?>(null) }

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

    val closeZoomWithAdopt: () -> Unit = {
        val zoomedView = canvasViewRef
        if (sourceInlineView != null && zoomedView != null) {
            sourceInlineView.adoptSessionAndBitmapFrom(zoomedView, transferBitmapOwnership = true)
        }
        onCloseZoom(pageSetup.width, pageSetup.height)
    }

    BackHandler {
        closeZoomWithAdopt()
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(if (isEInk) Color(0xFFF1F5F9) else Color(0xFF18181B))
    ) {
        val applyToolMode: (StylusToolMode, Int, Float, WritePenTip, StylusToolMode?, StylusToolMode?, StylusToolMode?) -> Unit =
            { newMode, newColor, newWidth, newTip, eraseDef, selectDef, insDef ->
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
            }
        RadialPenMenuHost(
            accepts = { it === canvasViewRef },
            activeMode = activeMode,
            selectedColor = selectedColor,
            selectedWidth = selectedWidth,
            selectedPenTip = selectedPenTip,
            lastEraserMode = lastEraserMode,
            lastSelectMode = lastSelectMode,
            onSelectToolMode = applyToolMode
        )
        val dock = UiPrefs.toolbarDock
        val leftHanded = UiPrefs.leftHanded
        val toolbar: @Composable () -> Unit = {
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
                onZoomOrCloseFullScreen = {
                    closeZoomWithAdopt()
                },
                onSelectToolMode = applyToolMode,
                onTogglePenToolbar = { showPenToolbar = !showPenToolbar },
                onSelectAllStrokes = { canvasViewRef?.selectAllStrokes() },
                onApplyStyleToSelection = { canvasViewRef?.applyCurrentStyleToSelection() },
                onUndo = { canvasViewRef?.performUndo() },
                onRedo = { canvasViewRef?.performRedo() },
                onExpandDown = { canvasViewRef?.expandDown() },
                onExpandRight = { canvasViewRef?.expandRight() },
                onTrimWhitespace = { canvasViewRef?.trimWhitespace() },
                onOpenPageSetup = { showPageSetupDialog = true },
                onDeleteImage = { showDeleteConfirmDialog = true },
                isDarkCanvasTheme = effectiveDark,
                onToggleDarkCanvasTheme = if (isEInk) null else ({ syncClient.toggleCanvasDarkMode() })
            )
        }
        if (dock == ToolbarDock.TOP) toolbar()

        val overlayRects = remember { mutableStateMapOf<String, android.graphics.Rect>() }
        // Emacs replaced this sketch (conflict resolved or peer updated): show the new version.
        LaunchedEffect(block.fileName) {
            syncClient.sketchReloads.collect { (file, b64) ->
                if (file == block.fileName) canvasViewRef?.loadFromBase64Svgz(b64)
            }
        }
        var lastLoadedSvgzBase64 by remember(block.blockId) { mutableStateOf(block.svgzBase64) }
        var showMinimap by remember { mutableStateOf(false) }
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
                        adoptFromView = sourceInlineView,
                        onStrokeCommitted = { pathXml, w, h, exportSvgzBase64 ->
                            com.orgwritenotes.ink.AudioSketchRecorder.noteStroke(block.fileName)
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
                            canvasViewRef?.let { cv ->
                                WritePenPresetStorage.saveViewport(context, block.fileName, cv.zoomScale, cv.panOffsetX, cv.panOffsetY)
                            }
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
                        singleTouchPanInZoomedMode = singleTouchPan
                        setAdaptiveTheme(effectiveDark, bgArgb, fgArgb)
                        if (sourceInlineView == null) {
                            loadFromBase64Svgz(block.svgzBase64)
                        } else {
                            canUndo = canUndoNow()
                            canRedo = canRedoNow()
                            pageSetup = queryPageSetupState()
                        }
                        WritePenPresetStorage.loadViewport(context, block.fileName)?.let { (savedScale, savedPanX, savedPanY) ->
                            zoomScale = savedScale
                            panOffsetX = savedPanX
                            panOffsetY = savedPanY
                            invalidate()
                        }
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
                update = { view ->
                    view.setAdaptiveTheme(effectiveDark, bgArgb, fgArgb)
                    view.rawExcludeWindowRects = overlayRects.values.toList()
                    if (block.svgzBase64 != lastLoadedSvgzBase64 && !block.svgzBase64.isNullOrEmpty()) {
                        lastLoadedSvgzBase64 = block.svgzBase64
                        if (block.svgzBase64 != view.lastSyncedSvgzBase64) {
                            view.loadFromBase64Svgz(block.svgzBase64)
                        }
                    }
                },
                modifier = Modifier.fillMaxSize()
            )

            splitTarget?.let { target ->
                WriteSplitPane(
                    nodeId = nodeId,
                    currentFileName = block.fileName,
                    target = target,
                    onTargetChange = { splitTarget = it },
                    onClose = { splitTarget = null },
                    syncClient = syncClient,
                    modifier = Modifier
                        .align(if (leftHanded) Alignment.CenterStart else Alignment.CenterEnd)
                        .fillMaxHeight()
                        .fillMaxWidth(0.45f)
                        .trackWindowRect(overlayRects, "split")
                )
            }

            WriteExtrasBar(
                canvas = canvasViewRef,
                nodeId = nodeId,
                fileName = block.fileName,
                syncClient = syncClient,
                splitActive = splitTarget != null,
                onToggleSplit = { splitTarget = if (splitTarget == null) SplitTarget.Text else null },
                modifier = Modifier
                    .align(if (leftHanded) Alignment.TopStart else Alignment.TopEnd)
                    .padding(12.dp)
                    .trackWindowRect(overlayRects, "extras")
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
                    onConvertToText = {
                        ocrDraftText = canvasViewRef?.recognizeSelectionOcr().orEmpty()
                        showOcrDialog = true
                    },
                    onDelete = { canvasViewRef?.deleteSelectedStrokes() },
                    onClearSelection = { canvasViewRef?.clearSelection() },
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .padding(top = 12.dp)
                        .trackWindowRect(overlayRects, "selection")
                )
            }

            // Bottom-Left Stylus Labs Write Mini Statusbar
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                modifier = Modifier
                    .align(if (leftHanded) Alignment.BottomEnd else Alignment.BottomStart)
                    .padding(12.dp)
                    .trackWindowRect(overlayRects, "statusbar")
                    .clip(RoundedCornerShape(8.dp))
                    .background(if (isEInk) Color.White else Color(0xD9222222))
                    .border(1.dp, if (isEInk) Color.Black else Color(0xFF3F3F46), RoundedCornerShape(8.dp))
                    .padding(horizontal = 10.dp, vertical = 6.dp)
            ) {
                WriteSvgIcon(
                    asset = WriteIconAsset.ZOOM,
                    tint = if (isEInk) Color.Black else Color(0xFF94A3B8),
                    size = 16.dp
                )
                Text(
                    text = "${(zoomScale * 100).roundToInt()}%",
                    color = if (isEInk) Color.Black else Color(0xFFF8FAFC),
                    fontFamily = FontFamily.Monospace,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold
                )
                StatusbarMiniPill(label = "−") { canvasViewRef?.zoomBy(0.8f) }
                StatusbarMiniPill(label = "100%") { canvasViewRef?.resetZoom100() }
                StatusbarMiniPill(label = "Fit Width") { canvasViewRef?.zoomFitWidth() }
                StatusbarMiniPill(label = "Fit Page") { canvasViewRef?.zoomFitPage() }
                StatusbarMiniPill(label = "+") { canvasViewRef?.zoomBy(1.25f) }

                if (isEInk) {
                    WriteToolbarDivider()
                    StatusbarMiniPill(label = "PgUp") { canvasViewRef?.pageScrollVertical(-1) }
                    StatusbarMiniPill(label = "PgDn") { canvasViewRef?.pageScrollVertical(1) }
                }

                WriteToolbarDivider()

                StatusbarMiniPill(
                    label = if (singleTouchPan) "Finger: Pan" else "Finger: Tool",
                    highlighted = !singleTouchPan
                ) {
                    singleTouchPan = !singleTouchPan
                    canvasViewRef?.singleTouchPanInZoomedMode = singleTouchPan
                }

                // B2: Minimap viewport indicator toggle
                StatusbarMiniPill(
                    label = if (showMinimap) "Map: On" else "Map",
                    highlighted = showMinimap
                ) {
                    showMinimap = !showMinimap
                }
            }

            // B2: Canvas Minimap / Viewport navigator
            canvasViewRef?.takeIf { showMinimap }?.let { cv ->
                Box(
                    modifier = Modifier
                        .align(if (leftHanded) Alignment.BottomStart else Alignment.BottomEnd)
                        .padding(12.dp)
                        .trackWindowRect(overlayRects, "minimap")
                        .size(width = 110.dp, height = 75.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(if (isEInk) Color.White else Color(0xCC0F172A))
                        .border(1.dp, if (isEInk) Color.Black else Color(0xFF38BDF8), RoundedCornerShape(8.dp))
                ) {
                    val mapAspect = (pageSetup.width.toFloat() / pageSetup.height.coerceAtLeast(100).toFloat()).coerceIn(0.5f, 2.5f)
                    val vpLeftNorm = (-cv.panOffsetX / (pageSetup.width * cv.zoomScale)).coerceIn(0f, 1f)
                    val vpTopNorm = (-cv.panOffsetY / (pageSetup.height * cv.zoomScale)).coerceIn(0f, 1f)
                    val vpWNorm = (cv.width / (pageSetup.width * cv.zoomScale).coerceAtLeast(1f)).coerceIn(0.05f, 1f)
                    val vpHNorm = (cv.height / (pageSetup.height * cv.zoomScale).coerceAtLeast(1f)).coerceIn(0.05f, 1f)
                    val vpXBias = if (vpWNorm >= 0.999f) 0f else (vpLeftNorm.coerceIn(0f, 1f - vpWNorm) / (1f - vpWNorm)) * 2f - 1f
                    val vpYBias = if (vpHNorm >= 0.999f) 0f else (vpTopNorm.coerceIn(0f, 1f - vpHNorm) / (1f - vpHNorm)) * 2f - 1f

                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(4.dp)
                    ) {
                        // Canvas outline
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .border(0.5.dp, if (isEInk) Color.Black else Color(0x6694A3B8), RoundedCornerShape(4.dp))
                        )
                        // Viewport window box in minimap
                        Box(
                            modifier = Modifier
                                .align(BiasAlignment(vpXBias, vpYBias))
                                .fillMaxWidth(vpWNorm)
                                .fillMaxHeight(vpHNorm)
                                .border(1.dp, if (isEInk) Color.Black else Color(0xFF38BDF8), RoundedCornerShape(2.dp))
                                .background(if (isEInk) Color.Transparent else Color(0x3338BDF8))
                        )
                    }
                }
            }

            if (dock == ToolbarDock.FLOATING) {
                FloatingToolbarFrame(overlayRects = overlayRects, selectedColor = selectedColor, toolbar = toolbar)
            }
        }
        if (dock == ToolbarDock.BOTTOM) toolbar()
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
            },
            onSetTemplateOpacity = { op ->
                canvasViewRef?.setTemplateImageOpacity(op)
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
                        color = if (isEInk) Color(0xFF334155) else Color(0xFF94A3B8),
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
                    // E3: drawn "[ ]" -> checklist item; plain-text maths -> LaTeX
                    val drawnCheckbox = com.orgwritenotes.ink.OcrSmartInsert.parseCheckbox(ocrDraftText)
                    if (drawnCheckbox != null) {
                        OutlinedButton(
                            onClick = {
                                syncClient.insertOcrText(nodeId, drawnCheckbox.text, "checklist", block.fileName)
                                showOcrDialog = false
                            }
                        ) {
                            Text("Checkbox detected: add \"${drawnCheckbox.text}\" as checklist item", fontSize = 12.sp)
                        }
                    } else if (com.orgwritenotes.ink.OcrSmartInsert.looksLikeMath(ocrDraftText)) {
                        val latex = com.orgwritenotes.ink.OcrSmartInsert.toLatexParagraph(ocrDraftText)
                        OutlinedButton(
                            onClick = {
                                syncClient.insertOcrText(nodeId, latex, "paragraph", block.fileName)
                                showOcrDialog = false
                            }
                        ) {
                            Text("Math detected: insert $latex", fontSize = 12.sp)
                        }
                    }
                }
            },
            confirmButton = {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(
                        onClick = {
                            if (ocrDraftText.isNotBlank()) {
                                syncClient.insertOcrText(nodeId, ocrDraftText.trim(), "checklist", block.fileName)
                            }
                            showOcrDialog = false
                        }
                    ) {
                        Text("Insert as Checklist", fontSize = 12.sp)
                    }
                    Button(
                        onClick = {
                            if (ocrDraftText.isNotBlank()) {
                                syncClient.insertOcrText(nodeId, ocrDraftText.trim(), "paragraph", block.fileName)
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
                    color = if (isEInk) Color(0xFF334155) else Color(0xFF94A3B8),
                    fontSize = 14.sp
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        showDeleteConfirmDialog = false
                        syncClient.deleteInkBlock(nodeId, block.fileName, block.linkText)
                        onCloseZoom(pageSetup.width, pageSetup.height)
                    },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (isEInk) Color.Black else Color(0xFFDC2626)
                    )
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
