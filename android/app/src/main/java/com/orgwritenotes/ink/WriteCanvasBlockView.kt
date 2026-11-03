package com.orgwritenotes.ink

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.util.Base64
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

enum class StylusToolMode {
    PAN,
    PEN,
    HIGHLIGHTER,
    ERASER,
    ERASER_RULED,
    ERASER_FREE,
    SELECT_RECT,
    SELECT_LASSO,
    SELECT_RULED,
    INSERT_SPACE_VERT,
    INSERT_SPACE_RULED,
    INSERT_SPACE_HORZ
}

enum class WritePenTip(val nativeCode: Int, val label: String) {
    FLAT(0, "Flat"),
    ROUND(1, "Round"),
    CHISEL(2, "Chisel")
}

enum class WriteTemplateStyle(val nativeCode: Int, val label: String) {
    STANDARD(0, "Standard (Ruled / Grid)"),
    DOTTED(1, "Dotted Grid"),
    ENGINEER_GRID(2, "Engineering 5×5"),
    ISOMETRIC(3, "Isometric Grid"),
    MUSIC_STAFF(4, "Music Staff"),
    CORNELL_SUMMARY(5, "Cornell Notes"),
    CUSTOM_DESIGNER(6, "Custom Designer");

    companion object {
        fun fromNativeCode(code: Int): WriteTemplateStyle =
            values().firstOrNull { it.nativeCode == code } ?: STANDARD
    }
}

data class WriteSelectionState(
    val count: Int = 0,
    val bounds: RectF = RectF()
) {
    val hasSelection: Boolean get() = count > 0 && !bounds.isEmpty
}

data class WritePageSetupState(
    val width: Int = 1200,
    val height: Int = 600,
    val xRuling: Int = 40,
    val yRuling: Int = 40,
    val marginLeft: Int = 0,
    val paperColorArgb: Int = 0xFFFFFDF8.toInt(),
    val ruleColorArgb: Int = 0xFF4A90E2.toInt(),
    val strokeCount: Int = 0,
    val rulingStyle: Int = WriteTemplateStyle.STANDARD.nativeCode,
    val hasCustomTemplateNodes: Boolean = false
)

/**
 * JNI bridge to `liborgwritenotes_jni.so` (which compiles Stylus Labs Write's
 * `Document`, `Page`, `StrokeBuilder`, `Painter`, `usvg`, and `miniz` BGZ `.svgz` serializer).
 */
object WriteNativeBridge {
    var isNativeLoaded = false
        private set

    /** Cross-canvas SVG clipboard for Copy / Cut / Paste between sketches */
    var clipboardSvgXml: String = ""

    init {
        try {
            System.loadLibrary("orgwritenotes_jni")
            isNativeLoaded = true
        } catch (_: UnsatisfiedLinkError) {
            isNativeLoaded = false
        }
    }

    external fun nativeCreateSession(width: Int, height: Int, xRuling: Int, yRuling: Int): Long
    external fun nativeDestroySession(handle: Long)
    external fun nativeLoadSvgz(handle: Long, svgzBytes: ByteArray): Boolean
    external fun nativeExportSvgz(handle: Long): ByteArray?
    external fun nativeRenderToBitmap(handle: Long, bitmap: Bitmap): Boolean
    external fun nativeSetPen(
        handle: Long,
        colorArgb: Int,
        strokeWidth: Float,
        pressureSensitive: Boolean,
        tipType: Int,
        snapToGrid: Boolean,
        lineDrawing: Boolean,
        chiselAngle: Float = 45.0f
    )
    external fun nativeBeginStroke(
        handle: Long,
        x: Float,
        y: Float,
        pressure: Float,
        tiltX: Float,
        tiltY: Float,
        timestampMs: Double
    )
    external fun nativeMoveStroke(
        handle: Long,
        x: Float,
        y: Float,
        pressure: Float,
        tiltX: Float,
        tiltY: Float,
        timestampMs: Double
    )
    external fun nativeEndStroke(handle: Long, outNewDimensions: IntArray): String
    external fun nativeEraseAt(handle: Long, x: Float, y: Float, radius: Float): Boolean
    external fun nativeEraseRuledLineAt(handle: Long, y: Float): Boolean
    external fun nativeEraseFreeSegment(
        handle: Long,
        prevX: Float,
        prevY: Float,
        x: Float,
        y: Float,
        radius: Float
    ): Boolean

    external fun nativeSelectRect(
        handle: Long,
        left: Float,
        top: Float,
        right: Float,
        bottom: Float,
        outBounds4: FloatArray
    ): Int
    external fun nativeSelectLasso(handle: Long, xyPoints: FloatArray, outBounds4: FloatArray): Int
    external fun nativeSelectRuled(handle: Long, startY: Float, endY: Float, outBounds4: FloatArray): Int
    external fun nativeSelectAll(handle: Long, outBounds4: FloatArray): Int
    external fun nativeClearSelection(handle: Long)
    external fun nativeTranslateSelection(
        handle: Long,
        dx: Float,
        dy: Float,
        outBounds4: FloatArray,
        outNewDimensions: IntArray
    ): Boolean
    external fun nativeScaleSelection(
        handle: Long,
        sx: Float,
        sy: Float,
        pivotX: Float,
        pivotY: Float,
        scaleWidths: Boolean,
        outBounds4: FloatArray,
        outNewDimensions: IntArray
    ): Boolean
    external fun nativeRotateSelection(
        handle: Long,
        angleRad: Float,
        pivotX: Float,
        pivotY: Float,
        outBounds4: FloatArray,
        outNewDimensions: IntArray
    ): Boolean
    external fun nativeDeleteSelection(handle: Long): Boolean
    external fun nativeDuplicateSelection(
        handle: Long,
        offsetX: Float,
        offsetY: Float,
        outBounds4: FloatArray
    ): Int
    external fun nativeRestyleSelection(handle: Long, colorArgb: Int, strokeWidth: Float): Boolean
    external fun nativeCopySelectionSvg(handle: Long): String
    external fun nativePasteSelectionSvg(
        handle: Long,
        svgFragment: String,
        offsetX: Float,
        offsetY: Float,
        outBounds4: FloatArray,
        outNewDimensions: IntArray
    ): Int

    external fun nativeInsertVerticalSpace(
        handle: Long,
        yThreshold: Float,
        deltaY: Float,
        snapToRuling: Boolean,
        outNewDimensions: IntArray
    ): Boolean
    external fun nativeInsertHorizontalSpace(
        handle: Long,
        xThreshold: Float,
        deltaX: Float,
        snapToRuling: Boolean,
        outNewDimensions: IntArray
    ): Boolean
    external fun nativeInsertRuledSpace(
        handle: Long,
        startX: Float,
        startY: Float,
        endX: Float,
        endY: Float,
        enableReflow: Boolean,
        outNewDimensions: IntArray
    ): Boolean

    external fun nativeSetPageSetup(
        handle: Long,
        width: Int,
        height: Int,
        xRuling: Int,
        yRuling: Int,
        marginLeft: Int,
        paperColorArgb: Int,
        ruleColorArgb: Int,
        rulingStyle: Int
    ): Boolean
    external fun nativeLockContentAsTemplate(handle: Long): Boolean
    external fun nativeUnlockTemplateToContent(handle: Long): Boolean
    external fun nativeClearCustomTemplate(handle: Long): Boolean
    external fun nativeInsertImageBytes(
        handle: Long,
        imageBytes: ByteArray,
        asBackground: Boolean,
        outBounds4: FloatArray
    ): Boolean
    external fun nativeGetPageInfo(handle: Long, outInfo10: IntArray)

    external fun nativeUndo(handle: Long, outNewDimensions: IntArray): Boolean
    external fun nativeRedo(handle: Long, outNewDimensions: IntArray): Boolean
    external fun nativeCanUndo(handle: Long): Boolean
    external fun nativeCanRedo(handle: Long): Boolean
    external fun nativeResizeCanvas(handle: Long, newWidth: Int, newHeight: Int): Boolean
}

/**
 * Embedded and Zoomable Stylus Labs Write canvas view for an Org-mode `.svgz` ink block.
 */
class WriteCanvasBlockView(
    context: Context,
    initialWidth: Int = 1200,
    initialHeight: Int = 600,
    private var xRuling: Int = 40,
    private var yRuling: Int = 40,
    val isZoomedMode: Boolean = false,
    private val onStrokeCommitted: (pathXml: String, width: Int, height: Int, exportSvgzBase64: () -> String) -> Unit,
    private val onHeightExpanded: (newHeightPx: Int) -> Unit,
    var onSelectionChanged: ((WriteSelectionState) -> Unit)? = null,
    var onViewportChanged: ((zoomScale: Float, canUndo: Boolean, canRedo: Boolean, pageSetup: WritePageSetupState) -> Unit)? = null,
    var onCanvasActivated: (() -> Unit)? = null
) : View(context) {

    private enum class SelectionTransformInteraction {
        NONE,
        TRANSLATE,
        SCALE_CORNER,
        ROTATE_HANDLE
    }

    private var nativeHandle: Long = 0L
    var canvasWidthPx: Int = initialWidth
        private set
    var canvasHeightPx: Int = initialHeight
        private set
    private var backingBitmap: Bitmap? = null

    var toolMode: StylusToolMode = StylusToolMode.PEN
    var singleTouchPanInZoomedMode: Boolean = true
    var enableRuledReflow: Boolean = true
    private var penColorArgb: Int = 0xFF1E293B.toInt()
    private var penWidthPx: Float = 2.8f
    private var penTip: WritePenTip = WritePenTip.ROUND
    private var pressureSensitive: Boolean = true
    var snapToGrid: Boolean = false
        private set
    var lineDrawing: Boolean = false
        private set
    var penChiselAngle: Float = 45.0f
        private set

    // Viewport Pan & Zoom state (active when isZoomedMode == true)
    var zoomScale: Float = 1.0f
        private set
    var panOffsetX: Float = 24f
        private set
    var panOffsetY: Float = 24f
        private set

    // Selection & interactive tool overlay state (in canvas coordinates)
    private var selectionState = WriteSelectionState()
    private var activeSelTransform = SelectionTransformInteraction.NONE
    private var selDragStartCanvasX = 0f
    private var selDragStartCanvasY = 0f
    private var selAccumulatedDx = 0f
    private var selAccumulatedDy = 0f
    private var selPivotX = 0f
    private var selPivotY = 0f
    private var selPreviewScaleX = 1f
    private var selPreviewScaleY = 1f
    private var selPreviewRotateRad = 0f

    private var activeMarqueeStart: Pair<Float, Float>? = null
    private var activeMarqueeCurrent: Pair<Float, Float>? = null
    private val activeLassoPoints = mutableListOf<Float>()
    private val activeLassoPath = Path()

    private var activeInsertSpaceStartX: Float? = null
    private var activeInsertSpaceStartY: Float? = null
    private var activeInsertSpaceCurrentX: Float? = null
    private var activeInsertSpaceCurrentY: Float? = null

    private var freeEraseLastX: Float = 0f
    private var freeEraseLastY: Float = 0f
    private var liveStrokeStartX: Float = 0f
    private var liveStrokeStartY: Float = 0f

    // Pan gesture tracking
    private var isPanningGesture = false
    private var panLastTouchX = 0f
    private var panLastTouchY = 0f

    // Fallback path list when running in JVM preview without NDK loaded
    private val fallbackPaths = mutableListOf<Pair<Path, Paint>>()
    private var activeLiveStrokePath: Path? = null

    private val bitmapPaint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val workspaceBgPaint = Paint().apply {
        color = 0xFF18181B.toInt()
        style = Paint.Style.FILL
    }
    private val pageShadowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0x66000000
        style = Paint.Style.FILL
    }
    private val paperPaint = Paint().apply {
        color = 0xFFFFFDF8.toInt()
        style = Paint.Style.FILL
    }
    private val gridPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0x264A90E2
        strokeWidth = 1.2f
        style = Paint.Style.STROKE
    }
    private var currentLiveStrokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = penColorArgb
        strokeWidth = penWidthPx
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val selectionFillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0x2238BDF8
        style = Paint.Style.FILL
    }
    private val selectionBorderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF0284C7.toInt()
        strokeWidth = 2.0f
        style = Paint.Style.STROKE
        pathEffect = DashPathEffect(floatArrayOf(10f, 8f), 0f)
    }
    private val selectionHandlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF0284C7.toInt()
        style = Paint.Style.FILL
    }
    private val rotateHandlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF10B981.toInt()
        style = Paint.Style.FILL
    }
    private val insertSpaceGuidePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFEA580C.toInt()
        strokeWidth = 2.5f
        style = Paint.Style.STROKE
        pathEffect = DashPathEffect(floatArrayOf(14f, 8f), 0f)
    }
    private val insertSpaceFillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0x24EA580C
        style = Paint.Style.FILL
    }

    private val scaleDetector = ScaleGestureDetector(
        context,
        object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScaleBegin(detector: ScaleGestureDetector): Boolean {
                if (!isZoomedMode) return false
                activeLiveStrokePath = null
                return true
            }

            override fun onScale(detector: ScaleGestureDetector): Boolean {
                if (!isZoomedMode) return false
                val prevScale = zoomScale
                val newScale = (zoomScale * detector.scaleFactor).coerceIn(0.25f, 4.0f)
                val focusX = detector.focusX
                val focusY = detector.focusY
                panOffsetX = focusX - (focusX - panOffsetX) * (newScale / prevScale)
                panOffsetY = focusY - (focusY - panOffsetY) * (newScale / prevScale)
                zoomScale = newScale
                notifyViewportUpdated()
                invalidate()
                return true
            }
        }
    )

    init {
        if (WriteNativeBridge.isNativeLoaded) {
            nativeHandle = WriteNativeBridge.nativeCreateSession(
                initialWidth, initialHeight, xRuling, yRuling
            )
            WriteNativeBridge.nativeSetPen(
                nativeHandle,
                penColorArgb,
                penWidthPx,
                pressureSensitive,
                penTip.nativeCode,
                snapToGrid,
                lineDrawing,
                penChiselAngle
            )
            refreshBackingBitmap()
        }
    }

    private fun screenToCanvasX(screenX: Float): Float =
        if (isZoomedMode) (screenX - panOffsetX) / zoomScale else screenX

    private fun screenToCanvasY(screenY: Float): Float =
        if (isZoomedMode) (screenY - panOffsetY) / zoomScale else screenY

    private fun snapCoordIfEnabled(x: Float, y: Float): Pair<Float, Float> {
        if (!snapToGrid) return x to y
        val yr = if (yRuling > 0) yRuling.toFloat() else 40f
        val xr = if (xRuling > 0) xRuling.toFloat() else yr
        val sx = floor(x / xr + 0.5f) * xr
        val sy = floor(y / yr + 0.5f) * yr
        return sx to sy
    }

    private fun ensureBackingBitmap(): Bitmap {
        val w = canvasWidthPx.coerceAtLeast(100)
        val h = canvasHeightPx.coerceAtLeast(100)
        val existing = backingBitmap
        if (existing != null && existing.width == w && existing.height == h) {
            return existing
        }
        existing?.recycle()
        val created = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        backingBitmap = created
        return created
    }

    fun refreshBackingBitmap() {
        if (nativeHandle == 0L) return
        val info = queryPageSetupState()
        canvasWidthPx = info.width.coerceAtLeast(100)
        canvasHeightPx = info.height.coerceAtLeast(100)
        xRuling = info.xRuling
        yRuling = info.yRuling
        val bmp = ensureBackingBitmap()
        WriteNativeBridge.nativeRenderToBitmap(nativeHandle, bmp)
        notifyViewportUpdated()
        invalidate()
    }

    fun queryPageSetupState(): WritePageSetupState {
        if (nativeHandle == 0L) {
            return WritePageSetupState(
                width = canvasWidthPx,
                height = canvasHeightPx,
                xRuling = xRuling,
                yRuling = yRuling
            )
        }
        val info = IntArray(10)
        WriteNativeBridge.nativeGetPageInfo(nativeHandle, info)
        return WritePageSetupState(
            width = info[0],
            height = info[1],
            xRuling = info[2],
            yRuling = info[3],
            marginLeft = info[4],
            paperColorArgb = info[5],
            ruleColorArgb = info[6],
            strokeCount = info[7],
            rulingStyle = info[8],
            hasCustomTemplateNodes = info[9] != 0
        )
    }

    fun canUndoNow(): Boolean =
        nativeHandle != 0L && WriteNativeBridge.nativeCanUndo(nativeHandle)

    fun canRedoNow(): Boolean =
        nativeHandle != 0L && WriteNativeBridge.nativeCanRedo(nativeHandle)

    fun queryPageSetup(): WritePageSetupState = queryPageSetupState()

    private fun notifyViewportUpdated() {
        onViewportChanged?.invoke(zoomScale, canUndoNow(), canRedoNow(), queryPageSetupState())
    }

    private fun updateSelectionFromBounds(count: Int, bounds4: FloatArray) {
        selectionState = if (count > 0 && bounds4[2] > bounds4[0] && bounds4[3] > bounds4[1]) {
            WriteSelectionState(
                count = count,
                bounds = RectF(bounds4[0], bounds4[1], bounds4[2], bounds4[3])
            )
        } else {
            WriteSelectionState()
        }
        onSelectionChanged?.invoke(selectionState)
        invalidate()
    }

    fun loadFromBase64Svgz(base64: String?) {
        if (base64.isNullOrEmpty() || nativeHandle == 0L) return
        val bytes = Base64.decode(base64, Base64.DEFAULT)
        if (WriteNativeBridge.nativeLoadSvgz(nativeHandle, bytes)) {
            refreshBackingBitmap()
        }
    }

    fun exportSvgzBase64(): String {
        if (nativeHandle == 0L) return ""
        val bytes = WriteNativeBridge.nativeExportSvgz(nativeHandle) ?: return ""
        return Base64.encodeToString(bytes, Base64.NO_WRAP)
    }

    fun configureTool(
        mode: StylusToolMode,
        colorArgb: Int,
        strokeWidth: Float,
        tip: WritePenTip = penTip,
        pressure: Boolean = pressureSensitive,
        snapGrid: Boolean = snapToGrid,
        straightLine: Boolean = lineDrawing,
        chiselAngle: Float = penChiselAngle
    ) {
        if (toolMode != mode && !mode.name.startsWith("SELECT")) {
            clearSelection()
        }
        toolMode = mode
        penTip = tip
        pressureSensitive = pressure
        snapToGrid = snapGrid
        lineDrawing = straightLine
        penChiselAngle = chiselAngle
        penColorArgb = if (mode == StylusToolMode.HIGHLIGHTER) {
            (colorArgb and 0x00FFFFFF) or 0x55000000
        } else {
            colorArgb or 0xFF000000.toInt()
        }
        penWidthPx = if (mode == StylusToolMode.HIGHLIGHTER) strokeWidth * 4.5f else strokeWidth

        currentLiveStrokePaint = Paint(currentLiveStrokePaint).apply {
            color = penColorArgb
            this.strokeWidth = penWidthPx
        }
        if (nativeHandle != 0L) {
            WriteNativeBridge.nativeSetPen(
                nativeHandle,
                penColorArgb,
                penWidthPx,
                pressureSensitive && mode == StylusToolMode.PEN,
                if (mode == StylusToolMode.HIGHLIGHTER) WritePenTip.FLAT.nativeCode else penTip.nativeCode,
                snapToGrid,
                lineDrawing,
                penChiselAngle
            )
        }
        invalidate()
    }

    // Zoom & Viewport Controls
    fun zoomBy(factor: Float) {
        if (!isZoomedMode) return
        val cx = width / 2f
        val cy = height / 2f
        val prev = zoomScale
        val next = (zoomScale * factor).coerceIn(0.25f, 4.0f)
        panOffsetX = cx - (cx - panOffsetX) * (next / prev)
        panOffsetY = cy - (cy - panOffsetY) * (next / prev)
        zoomScale = next
        notifyViewportUpdated()
        invalidate()
    }

    fun resetZoom100() {
        if (!isZoomedMode) return
        zoomScale = 1.0f
        panOffsetX = ((width - canvasWidthPx) / 2f).coerceAtLeast(16f)
        panOffsetY = 24f
        notifyViewportUpdated()
        invalidate()
    }

    fun zoomFitWidth() {
        if (!isZoomedMode || width <= 0) return
        val targetScale = ((width - 48f) / canvasWidthPx.toFloat()).coerceIn(0.25f, 3.0f)
        zoomScale = targetScale
        panOffsetX = (width - canvasWidthPx * zoomScale) / 2f
        panOffsetY = 24f
        notifyViewportUpdated()
        invalidate()
    }

    // Selection Actions (Translate, Scale, Rotate, Copy, Cut, Paste, Duplicate, Restyle, Delete)
    fun selectAllStrokes() {
        if (nativeHandle == 0L) return
        val bounds = FloatArray(4)
        val count = WriteNativeBridge.nativeSelectAll(nativeHandle, bounds)
        updateSelectionFromBounds(count, bounds)
    }

    fun clearSelection() {
        if (nativeHandle != 0L) {
            WriteNativeBridge.nativeClearSelection(nativeHandle)
        }
        selectionState = WriteSelectionState()
        onSelectionChanged?.invoke(selectionState)
        invalidate()
    }

    fun deleteSelectedStrokes() {
        if (nativeHandle == 0L || !selectionState.hasSelection) return
        if (WriteNativeBridge.nativeDeleteSelection(nativeHandle)) {
            clearSelection()
            refreshBackingBitmap()
            onStrokeCommitted("<delete-selection/>", canvasWidthPx, canvasHeightPx) { exportSvgzBase64() }
        }
    }

    fun duplicateSelectedStrokes() {
        if (nativeHandle == 0L || !selectionState.hasSelection) return
        val bounds = FloatArray(4)
        val step = if (yRuling > 0) yRuling.toFloat() else 32f
        val count = WriteNativeBridge.nativeDuplicateSelection(nativeHandle, step, step, bounds)
        if (count > 0) {
            refreshBackingBitmap()
            updateSelectionFromBounds(count, bounds)
            onStrokeCommitted("<duplicate-selection/>", canvasWidthPx, canvasHeightPx) { exportSvgzBase64() }
        }
    }

    fun scaleSelectedStrokes(scaleFactor: Float, scaleStrokeWidths: Boolean = true) {
        if (nativeHandle == 0L || !selectionState.hasSelection) return
        val b = selectionState.bounds
        val bounds = FloatArray(4)
        val dims = IntArray(2)
        if (WriteNativeBridge.nativeScaleSelection(
                nativeHandle,
                scaleFactor,
                scaleFactor,
                b.centerX(),
                b.centerY(),
                scaleStrokeWidths,
                bounds,
                dims
            )
        ) {
            if (dims[1] > canvasHeightPx) {
                canvasHeightPx = dims[1]
                onHeightExpanded(canvasHeightPx)
            }
            refreshBackingBitmap()
            updateSelectionFromBounds(selectionState.count, bounds)
            onStrokeCommitted("<scale-selection/>", canvasWidthPx, canvasHeightPx) { exportSvgzBase64() }
        }
    }

    fun rotateSelectedStrokes(degrees: Float) {
        if (nativeHandle == 0L || !selectionState.hasSelection) return
        val b = selectionState.bounds
        val rad = (degrees * PI / 180.0).toFloat()
        val bounds = FloatArray(4)
        val dims = IntArray(2)
        if (WriteNativeBridge.nativeRotateSelection(
                nativeHandle,
                rad,
                b.centerX(),
                b.centerY(),
                bounds,
                dims
            )
        ) {
            if (dims[1] > canvasHeightPx) {
                canvasHeightPx = dims[1]
                onHeightExpanded(canvasHeightPx)
            }
            refreshBackingBitmap()
            updateSelectionFromBounds(selectionState.count, bounds)
            onStrokeCommitted("<rotate-selection/>", canvasWidthPx, canvasHeightPx) { exportSvgzBase64() }
        }
    }

    fun copySelectedStrokes(): Boolean {
        if (nativeHandle == 0L || !selectionState.hasSelection) return false
        val svg = WriteNativeBridge.nativeCopySelectionSvg(nativeHandle)
        if (svg.isNotBlank()) {
            WriteNativeBridge.clipboardSvgXml = svg
            return true
        }
        return false
    }

    fun cutSelectedStrokes(): Boolean {
        if (!copySelectedStrokes()) return false
        deleteSelectedStrokes()
        return true
    }

    fun hasClipboardStrokes(): Boolean = WriteNativeBridge.clipboardSvgXml.isNotBlank()

    fun pasteClipboardStrokes(): Boolean {
        if (nativeHandle == 0L || WriteNativeBridge.clipboardSvgXml.isBlank()) return false
        val bounds = FloatArray(4)
        val dims = IntArray(2)
        val step = if (yRuling > 0) yRuling.toFloat() else 32f
        val count = WriteNativeBridge.nativePasteSelectionSvg(
            nativeHandle,
            WriteNativeBridge.clipboardSvgXml,
            step,
            step,
            bounds,
            dims
        )
        if (count > 0) {
            if (dims[1] > canvasHeightPx) {
                canvasHeightPx = dims[1]
                onHeightExpanded(canvasHeightPx)
            }
            refreshBackingBitmap()
            updateSelectionFromBounds(count, bounds)
            onStrokeCommitted("<paste-selection/>", canvasWidthPx, canvasHeightPx) { exportSvgzBase64() }
            return true
        }
        return false
    }

    fun applyCurrentStyleToSelection() {
        if (nativeHandle == 0L || !selectionState.hasSelection) return
        if (WriteNativeBridge.nativeRestyleSelection(nativeHandle, penColorArgb, penWidthPx)) {
            refreshBackingBitmap()
            onStrokeCommitted("<restyle-selection/>", canvasWidthPx, canvasHeightPx) { exportSvgzBase64() }
        }
    }

    // Page, Ruling & Template Designer Setup
    fun applyPageSetup(
        newWidth: Int = canvasWidthPx,
        newHeight: Int = canvasHeightPx,
        newXRuling: Int,
        newYRuling: Int,
        newMarginLeft: Int,
        newPaperColorArgb: Int,
        newRuleColorArgb: Int,
        newRulingStyle: Int = -1
    ) {
        if (nativeHandle == 0L) return
        if (WriteNativeBridge.nativeSetPageSetup(
                nativeHandle,
                newWidth,
                newHeight,
                newXRuling,
                newYRuling,
                newMarginLeft,
                newPaperColorArgb,
                newRuleColorArgb,
                newRulingStyle
            )
        ) {
            refreshBackingBitmap()
            onHeightExpanded(canvasHeightPx)
            onStrokeCommitted("<page-setup/>", canvasWidthPx, canvasHeightPx) { exportSvgzBase64() }
        }
    }

    fun lockCurrentDrawingAsTemplate(): Boolean {
        if (nativeHandle == 0L) return false
        if (WriteNativeBridge.nativeLockContentAsTemplate(nativeHandle)) {
            clearSelection()
            refreshBackingBitmap()
            onStrokeCommitted("<lock-template/>", canvasWidthPx, canvasHeightPx) { exportSvgzBase64() }
            return true
        }
        return false
    }

    fun unlockTemplateBackgroundToEdit(): Boolean {
        if (nativeHandle == 0L) return false
        if (WriteNativeBridge.nativeUnlockTemplateToContent(nativeHandle)) {
            refreshBackingBitmap()
            onStrokeCommitted("<unlock-template/>", canvasWidthPx, canvasHeightPx) { exportSvgzBase64() }
            return true
        }
        return false
    }

    fun clearCustomTemplateBackground(): Boolean {
        if (nativeHandle == 0L) return false
        if (WriteNativeBridge.nativeClearCustomTemplate(nativeHandle)) {
            refreshBackingBitmap()
            onStrokeCommitted("<clear-template/>", canvasWidthPx, canvasHeightPx) { exportSvgzBase64() }
            return true
        }
        return false
    }

    fun insertImageBytes(imageBytes: ByteArray, asBackground: Boolean): Boolean {
        if (nativeHandle == 0L || imageBytes.isEmpty()) return false
        val bounds = FloatArray(4)
        if (WriteNativeBridge.nativeInsertImageBytes(nativeHandle, imageBytes, asBackground, bounds)) {
            refreshBackingBitmap()
            if (!asBackground && bounds[2] > bounds[0]) {
                updateSelectionFromBounds(1, bounds)
            }
            onStrokeCommitted("<insert-image bg=\"$asBackground\"/>", canvasWidthPx, canvasHeightPx) {
                exportSvgzBase64()
            }
            return true
        }
        return false
    }

    fun expandDown() {
        val step = if (yRuling > 0) yRuling * 8 else 320
        resizeCanvasDimensions(canvasWidthPx, canvasHeightPx + step)
    }

    fun expandRight() {
        val step = if (xRuling > 0) xRuling * 8 else 320
        resizeCanvasDimensions(canvasWidthPx + step, canvasHeightPx)
    }

    fun performUndo(): Boolean {
        val dims = IntArray(2)
        if (nativeHandle != 0L && WriteNativeBridge.nativeUndo(nativeHandle, dims)) {
            clearSelection()
            refreshBackingBitmap()
            if (dims[1] > 0 && dims[1] != canvasHeightPx) {
                canvasHeightPx = dims[1]
                onHeightExpanded(canvasHeightPx)
            }
            onStrokeCommitted("<undo/>", canvasWidthPx, canvasHeightPx) { exportSvgzBase64() }
            return true
        } else if (fallbackPaths.isNotEmpty()) {
            fallbackPaths.removeLast()
            invalidate()
            return true
        }
        return false
    }

    fun performRedo(): Boolean {
        val dims = IntArray(2)
        if (nativeHandle != 0L && WriteNativeBridge.nativeRedo(nativeHandle, dims)) {
            clearSelection()
            refreshBackingBitmap()
            if (dims[1] > 0 && dims[1] != canvasHeightPx) {
                canvasHeightPx = dims[1]
                onHeightExpanded(canvasHeightPx)
            }
            onStrokeCommitted("<redo/>", canvasWidthPx, canvasHeightPx) { exportSvgzBase64() }
            return true
        }
        return false
    }

    fun resizeCanvasHeight(newHeightPx: Int) {
        resizeCanvasDimensions(canvasWidthPx, newHeightPx)
    }

    fun resizeCanvasDimensions(newWidthPx: Int, newHeightPx: Int) {
        val clampedW = newWidthPx.coerceIn(400, 6000)
        val clampedH = newHeightPx.coerceIn(300, 8000)
        canvasWidthPx = clampedW
        canvasHeightPx = clampedH
        if (nativeHandle != 0L) {
            WriteNativeBridge.nativeResizeCanvas(nativeHandle, clampedW, clampedH)
            refreshBackingBitmap()
        }
        onHeightExpanded(clampedH)
        onStrokeCommitted("<resize width=\"$clampedW\" height=\"$clampedH\"/>", canvasWidthPx, canvasHeightPx) {
            exportSvgzBase64()
        }
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (isZoomedMode && oldw == 0 && w > 0) {
            zoomFitWidth()
        }
    }

    override fun onHoverEvent(event: MotionEvent): Boolean {
        if (event.actionMasked == MotionEvent.ACTION_HOVER_ENTER) {
            onCanvasActivated?.invoke()
        }
        return super.onHoverEvent(event)
    }

    private fun hitTestSelectionTransform(cx: Float, cy: Float): SelectionTransformInteraction {
        if (!selectionState.hasSelection) return SelectionTransformInteraction.NONE
        val b = RectF(selectionState.bounds).apply { inset(-6f, -6f) }
        val hitRadius = 22f / zoomScale.coerceAtLeast(0.5f)

        // 1. Check top rotation handle stem (located 28px above top-center of selection box)
        val rotHandleX = b.centerX()
        val rotHandleY = b.top - 28f
        if (hypot(cx - rotHandleX, cy - rotHandleY) <= hitRadius) {
            selPivotX = b.centerX()
            selPivotY = b.centerY()
            return SelectionTransformInteraction.ROTATE_HANDLE
        }

        // 2. Check 4 corner scale handles (pivot is the opposite corner)
        val corners = listOf(
            Triple(b.left, b.top, b.right to b.bottom),
            Triple(b.right, b.top, b.left to b.bottom),
            Triple(b.left, b.bottom, b.right to b.top),
            Triple(b.right, b.bottom, b.left to b.top)
        )
        for ((hx, hy, oppositePivot) in corners) {
            if (hypot(cx - hx, cy - hy) <= hitRadius) {
                selPivotX = oppositePivot.first
                selPivotY = oppositePivot.second
                return SelectionTransformInteraction.SCALE_CORNER
            }
        }

        // 3. Check inside selection box for translation
        val paddedBounds = RectF(b).apply { inset(-14f, -14f) }
        if (paddedBounds.contains(cx, cy)) {
            return SelectionTransformInteraction.TRANSLATE
        }
        return SelectionTransformInteraction.NONE
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (event.actionMasked == MotionEvent.ACTION_DOWN) {
            onCanvasActivated?.invoke()
        }
        if (isZoomedMode) {
            scaleDetector.onTouchEvent(event)
            if (scaleDetector.isInProgress || event.pointerCount > 1) {
                if (event.actionMasked == MotionEvent.ACTION_MOVE && event.pointerCount >= 2) {
                    val avgX = (event.getX(0) + event.getX(1)) * 0.5f
                    val avgY = (event.getY(0) + event.getY(1)) * 0.5f
                    if (isPanningGesture) {
                        panOffsetX += (avgX - panLastTouchX)
                        panOffsetY += (avgY - panLastTouchY)
                        invalidate()
                    }
                    isPanningGesture = true
                    panLastTouchX = avgX
                    panLastTouchY = avgY
                } else if (event.actionMasked == MotionEvent.ACTION_UP ||
                    event.actionMasked == MotionEvent.ACTION_POINTER_UP ||
                    event.actionMasked == MotionEvent.ACTION_CANCEL
                ) {
                    isPanningGesture = false
                }
                return true
            }
        }

        val action = when (event.actionMasked) {
            MotionEvent.ACTION_BUTTON_PRESS -> MotionEvent.ACTION_DOWN
            MotionEvent.ACTION_BUTTON_RELEASE -> MotionEvent.ACTION_UP
            else -> event.actionMasked
        }

        val toolType = event.getToolType(0)
        val buttonState = event.buttonState
        val isStylusButton = (buttonState and (
            MotionEvent.BUTTON_STYLUS_PRIMARY or
            MotionEvent.BUTTON_SECONDARY or
            MotionEvent.BUTTON_STYLUS_SECONDARY or
            MotionEvent.BUTTON_TERTIARY
        )) != 0
        val isHardwareEraser = toolType == MotionEvent.TOOL_TYPE_ERASER || isStylusButton

        val isStylusOrMouse = toolType == MotionEvent.TOOL_TYPE_STYLUS ||
            toolType == MotionEvent.TOOL_TYPE_ERASER ||
            toolType == MotionEvent.TOOL_TYPE_MOUSE ||
            isStylusButton

        // If the user clicks the stylus eraser button mid-stroke, cancel drawing immediately
        if (isHardwareEraser && activeLiveStrokePath != null) {
            activeLiveStrokePath = null
            if (nativeHandle != 0L) {
                WriteNativeBridge.nativeEndStroke(nativeHandle, IntArray(2))
            }
        }

        // In Inline Mode, single-finger touch passes straight to the parent LazyColumn for scrolling
        if (!isZoomedMode && !isStylusOrMouse) {
            parent?.requestDisallowInterceptTouchEvent(false)
            return false
        }

        parent?.requestDisallowInterceptTouchEvent(true)

        val shouldPanViewport = isZoomedMode && !isHardwareEraser && (
            toolMode == StylusToolMode.PAN ||
                (!isStylusOrMouse && singleTouchPanInZoomedMode)
            )
        if (shouldPanViewport) {
            when (action) {
                MotionEvent.ACTION_DOWN -> {
                    isPanningGesture = true
                    panLastTouchX = event.x
                    panLastTouchY = event.y
                    return true
                }
                MotionEvent.ACTION_MOVE -> {
                    if (isPanningGesture) {
                        panOffsetX += (event.x - panLastTouchX)
                        panOffsetY += (event.y - panLastTouchY)
                        panLastTouchX = event.x
                        panLastTouchY = event.y
                        invalidate()
                    }
                    return true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    isPanningGesture = false
                    return true
                }
            }
            return true
        }

        val cx = screenToCanvasX(event.x)
        val cy = screenToCanvasY(event.y)
        val pressure = event.pressure.coerceIn(0.05f, 1.0f)
        val tiltX = event.getAxisValue(MotionEvent.AXIS_TILT)
        val ts = event.eventTime.toDouble()

        // 1. Check if interacting with an active selection box (Translate, Scale Corner, or Rotate Handle)
        if (selectionState.hasSelection && !isHardwareEraser) {
            if (action == MotionEvent.ACTION_DOWN) {
                val hitMode = hitTestSelectionTransform(cx, cy)
                if (hitMode != SelectionTransformInteraction.NONE) {
                    activeSelTransform = hitMode
                    selDragStartCanvasX = cx
                    selDragStartCanvasY = cy
                    selAccumulatedDx = 0f
                    selAccumulatedDy = 0f
                    selPreviewScaleX = 1f
                    selPreviewScaleY = 1f
                    selPreviewRotateRad = 0f
                    return true
                }
            }
            if (activeSelTransform != SelectionTransformInteraction.NONE) {
                when (action) {
                    MotionEvent.ACTION_MOVE -> {
                        when (activeSelTransform) {
                            SelectionTransformInteraction.TRANSLATE -> {
                                selAccumulatedDx = cx - selDragStartCanvasX
                                selAccumulatedDy = cy - selDragStartCanvasY
                            }
                            SelectionTransformInteraction.SCALE_CORNER -> {
                                val startDx = selDragStartCanvasX - selPivotX
                                val startDy = selDragStartCanvasY - selPivotY
                                val currDx = cx - selPivotX
                                val currDy = cy - selPivotY
                                selPreviewScaleX = if (abs(startDx) > 4f) (currDx / startDx).coerceIn(0.15f, 6.0f) else 1f
                                selPreviewScaleY = if (abs(startDy) > 4f) (currDy / startDy).coerceIn(0.15f, 6.0f) else 1f
                            }
                            SelectionTransformInteraction.ROTATE_HANDLE -> {
                                val a0 = atan2(selDragStartCanvasY - selPivotY, selDragStartCanvasX - selPivotX)
                                val a1 = atan2(cy - selPivotY, cx - selPivotX)
                                selPreviewRotateRad = a1 - a0
                            }
                            else -> {}
                        }
                        invalidate()
                        return true
                    }
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                        val finishedMode = activeSelTransform
                        activeSelTransform = SelectionTransformInteraction.NONE
                        val bounds = FloatArray(4)
                        val dims = IntArray(2)
                        var changed = false

                        if (nativeHandle != 0L) {
                            when (finishedMode) {
                                SelectionTransformInteraction.TRANSLATE -> {
                                    val totalDx = cx - selDragStartCanvasX
                                    val totalDy = cy - selDragStartCanvasY
                                    if (abs(totalDx) > 1f || abs(totalDy) > 1f) {
                                        changed = WriteNativeBridge.nativeTranslateSelection(
                                            nativeHandle, totalDx, totalDy, bounds, dims
                                        )
                                    }
                                }
                                SelectionTransformInteraction.SCALE_CORNER -> {
                                    if (abs(selPreviewScaleX - 1f) > 0.02f || abs(selPreviewScaleY - 1f) > 0.02f) {
                                        changed = WriteNativeBridge.nativeScaleSelection(
                                            nativeHandle,
                                            selPreviewScaleX,
                                            selPreviewScaleY,
                                            selPivotX,
                                            selPivotY,
                                            true,
                                            bounds,
                                            dims
                                        )
                                    }
                                }
                                SelectionTransformInteraction.ROTATE_HANDLE -> {
                                    if (abs(selPreviewRotateRad) > 0.02f) {
                                        changed = WriteNativeBridge.nativeRotateSelection(
                                            nativeHandle,
                                            selPreviewRotateRad,
                                            selPivotX,
                                            selPivotY,
                                            bounds,
                                            dims
                                        )
                                    }
                                }
                                else -> {}
                            }
                        }

                        selAccumulatedDx = 0f
                        selAccumulatedDy = 0f
                        selPreviewScaleX = 1f
                        selPreviewScaleY = 1f
                        selPreviewRotateRad = 0f

                        if (changed) {
                            if (dims[0] > canvasWidthPx) canvasWidthPx = dims[0]
                            if (dims[1] > canvasHeightPx) {
                                canvasHeightPx = dims[1]
                                onHeightExpanded(canvasHeightPx)
                            }
                            refreshBackingBitmap()
                            updateSelectionFromBounds(selectionState.count, bounds)
                            onStrokeCommitted("<transform-selection/>", canvasWidthPx, canvasHeightPx) {
                                exportSvgzBase64()
                            }
                        } else {
                            invalidate()
                        }
                        return true
                    }
                }
            }
        }

        // 2. Check S-Pen tail eraser / side button or Eraser modes (Stroke, Ruled, or Free Segment Eraser)
        if (isHardwareEraser ||
            toolMode == StylusToolMode.ERASER ||
            toolMode == StylusToolMode.ERASER_RULED ||
            toolMode == StylusToolMode.ERASER_FREE
        ) {
            val effectiveMode = if (isHardwareEraser &&
                toolMode != StylusToolMode.ERASER_RULED &&
                toolMode != StylusToolMode.ERASER_FREE
            ) {
                StylusToolMode.ERASER
            } else {
                toolMode
            }
            when (action) {
                MotionEvent.ACTION_DOWN -> {
                    freeEraseLastX = cx
                    freeEraseLastY = cy
                    val erased = if (nativeHandle != 0L) {
                        when (effectiveMode) {
                            StylusToolMode.ERASER_RULED -> WriteNativeBridge.nativeEraseRuledLineAt(nativeHandle, cy)
                            StylusToolMode.ERASER_FREE -> WriteNativeBridge.nativeEraseFreeSegment(
                                nativeHandle, cx, cy, cx, cy, 18f
                            )
                            else -> WriteNativeBridge.nativeEraseAt(nativeHandle, cx, cy, 22f)
                        }
                    } else false
                    if (erased) refreshBackingBitmap()
                    return true
                }
                MotionEvent.ACTION_MOVE -> {
                    val erased = if (nativeHandle != 0L) {
                        when (effectiveMode) {
                            StylusToolMode.ERASER_RULED -> WriteNativeBridge.nativeEraseRuledLineAt(nativeHandle, cy)
                            StylusToolMode.ERASER_FREE -> WriteNativeBridge.nativeEraseFreeSegment(
                                nativeHandle, freeEraseLastX, freeEraseLastY, cx, cy, 18f
                            )
                            else -> WriteNativeBridge.nativeEraseAt(nativeHandle, cx, cy, 22f)
                        }
                    } else false
                    freeEraseLastX = cx
                    freeEraseLastY = cy
                    if (erased) refreshBackingBitmap()
                    return true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    onStrokeCommitted("<erase/>", canvasWidthPx, canvasHeightPx) { exportSvgzBase64() }
                    return true
                }
            }
            return true
        }

        // 3. Selection Tools (Rect, Lasso, Ruled)
        if (toolMode == StylusToolMode.SELECT_RECT ||
            toolMode == StylusToolMode.SELECT_RULED ||
            toolMode == StylusToolMode.SELECT_LASSO
        ) {
            when (action) {
                MotionEvent.ACTION_DOWN -> {
                    clearSelection()
                    if (toolMode == StylusToolMode.SELECT_LASSO) {
                        activeLassoPoints.clear()
                        activeLassoPoints.add(cx)
                        activeLassoPoints.add(cy)
                        activeLassoPath.reset()
                        activeLassoPath.moveTo(cx, cy)
                    } else {
                        activeMarqueeStart = cx to cy
                        activeMarqueeCurrent = cx to cy
                    }
                    invalidate()
                    return true
                }
                MotionEvent.ACTION_MOVE -> {
                    if (toolMode == StylusToolMode.SELECT_LASSO) {
                        activeLassoPoints.add(cx)
                        activeLassoPoints.add(cy)
                        activeLassoPath.lineTo(cx, cy)
                    } else {
                        activeMarqueeCurrent = cx to cy
                    }
                    invalidate()
                    return true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    val bounds = FloatArray(4)
                    var count = 0
                    if (nativeHandle != 0L) {
                        when (toolMode) {
                            StylusToolMode.SELECT_LASSO -> {
                                if (activeLassoPoints.size >= 6) {
                                    count = WriteNativeBridge.nativeSelectLasso(
                                        nativeHandle,
                                        activeLassoPoints.toFloatArray(),
                                        bounds
                                    )
                                }
                            }
                            StylusToolMode.SELECT_RECT -> {
                                val start = activeMarqueeStart
                                val end = activeMarqueeCurrent
                                if (start != null && end != null) {
                                    count = WriteNativeBridge.nativeSelectRect(
                                        nativeHandle,
                                        start.first,
                                        start.second,
                                        end.first,
                                        end.second,
                                        bounds
                                    )
                                }
                            }
                            StylusToolMode.SELECT_RULED -> {
                                val start = activeMarqueeStart
                                val end = activeMarqueeCurrent
                                if (start != null && end != null) {
                                    count = WriteNativeBridge.nativeSelectRuled(
                                        nativeHandle,
                                        start.second,
                                        end.second,
                                        bounds
                                    )
                                }
                            }
                            else -> {}
                        }
                    }
                    activeLassoPoints.clear()
                    activeLassoPath.reset()
                    activeMarqueeStart = null
                    activeMarqueeCurrent = null
                    updateSelectionFromBounds(count, bounds)
                    return true
                }
            }
            return true
        }

        // 4. Insert White Space Tools (Vertical, Ruled with Intra-Line Reflow, and Horizontal)
        if (toolMode == StylusToolMode.INSERT_SPACE_VERT ||
            toolMode == StylusToolMode.INSERT_SPACE_RULED ||
            toolMode == StylusToolMode.INSERT_SPACE_HORZ
        ) {
            when (action) {
                MotionEvent.ACTION_DOWN -> {
                    activeInsertSpaceStartX = cx
                    activeInsertSpaceStartY = cy
                    activeInsertSpaceCurrentX = cx
                    activeInsertSpaceCurrentY = cy
                    invalidate()
                    return true
                }
                MotionEvent.ACTION_MOVE -> {
                    activeInsertSpaceCurrentX = cx
                    activeInsertSpaceCurrentY = cy
                    invalidate()
                    return true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    val startX = activeInsertSpaceStartX
                    val startY = activeInsertSpaceStartY
                    val endX = activeInsertSpaceCurrentX
                    val endY = activeInsertSpaceCurrentY
                    activeInsertSpaceStartX = null
                    activeInsertSpaceStartY = null
                    activeInsertSpaceCurrentX = null
                    activeInsertSpaceCurrentY = null
                    if (startX != null && startY != null && endX != null && endY != null && nativeHandle != 0L) {
                        val dims = IntArray(2)
                        val applied = when (toolMode) {
                            StylusToolMode.INSERT_SPACE_HORZ -> {
                                WriteNativeBridge.nativeInsertHorizontalSpace(
                                    nativeHandle,
                                    startX,
                                    endX - startX,
                                    snapToGrid,
                                    dims
                                )
                            }
                            StylusToolMode.INSERT_SPACE_RULED -> {
                                WriteNativeBridge.nativeInsertRuledSpace(
                                    nativeHandle,
                                    startX,
                                    startY,
                                    endX,
                                    endY,
                                    enableRuledReflow,
                                    dims
                                )
                            }
                            else -> {
                                WriteNativeBridge.nativeInsertVerticalSpace(
                                    nativeHandle,
                                    startY,
                                    endY - startY,
                                    false,
                                    dims
                                )
                            }
                        }
                        if (applied) {
                            if (dims[0] > 0 && dims[0] != canvasWidthPx) {
                                canvasWidthPx = dims[0]
                            }
                            if (dims[1] > 0 && dims[1] != canvasHeightPx) {
                                canvasHeightPx = dims[1]
                                onHeightExpanded(canvasHeightPx)
                            }
                            refreshBackingBitmap()
                            onStrokeCommitted("<insert-space/>", canvasWidthPx, canvasHeightPx) {
                                exportSvgzBase64()
                            }
                        }
                    }
                    invalidate()
                    return true
                }
            }
            return true
        }

        // 5. Standard Pen / Highlighter / Line-Drawing / Grid-Snapped Drawing
        val (snappedX, snappedY) = snapCoordIfEnabled(cx, cy)
        when (action) {
            MotionEvent.ACTION_DOWN -> {
                liveStrokeStartX = snappedX
                liveStrokeStartY = snappedY
                activeLiveStrokePath = Path().apply { moveTo(snappedX, snappedY) }
                if (nativeHandle != 0L) {
                    WriteNativeBridge.nativeBeginStroke(nativeHandle, snappedX, snappedY, pressure, tiltX, 0f, ts)
                }
                invalidate()
                return true
            }

            MotionEvent.ACTION_MOVE -> {
                if (lineDrawing) {
                    activeLiveStrokePath = Path().apply {
                        moveTo(liveStrokeStartX, liveStrokeStartY)
                        lineTo(snappedX, snappedY)
                    }
                    if (nativeHandle != 0L) {
                        WriteNativeBridge.nativeMoveStroke(nativeHandle, snappedX, snappedY, pressure, tiltX, 0f, ts)
                    }
                } else {
                    for (h in 0 until event.historySize) {
                        val (hx, hy) = snapCoordIfEnabled(
                            screenToCanvasX(event.getHistoricalX(h)),
                            screenToCanvasY(event.getHistoricalY(h))
                        )
                        val hp = event.getHistoricalPressure(h)
                        val hts = event.getHistoricalEventTime(h).toDouble()
                        activeLiveStrokePath?.lineTo(hx, hy)
                        if (nativeHandle != 0L) {
                            WriteNativeBridge.nativeMoveStroke(nativeHandle, hx, hy, hp, tiltX, 0f, hts)
                        }
                    }
                    activeLiveStrokePath?.lineTo(snappedX, snappedY)
                    if (nativeHandle != 0L) {
                        WriteNativeBridge.nativeMoveStroke(nativeHandle, snappedX, snappedY, pressure, tiltX, 0f, ts)
                    }
                }
                invalidate()
                return true
            }

            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                activeLiveStrokePath?.let { finished ->
                    if (nativeHandle == 0L) {
                        fallbackPaths.add(finished to Paint(currentLiveStrokePaint))
                    }
                }
                activeLiveStrokePath = null

                val dims = IntArray(2) { 0 }
                val pathXml = if (nativeHandle != 0L) {
                    WriteNativeBridge.nativeEndStroke(nativeHandle, dims)
                } else {
                    "<path class=\"ug-path\" />"
                }

                if (dims[0] > canvasWidthPx) {
                    canvasWidthPx = dims[0]
                }
                val newHeight = if (dims[1] > 0) dims[1] else canvasHeightPx
                if (newHeight > canvasHeightPx) {
                    canvasHeightPx = newHeight
                    onHeightExpanded(newHeight)
                }

                if (nativeHandle != 0L) {
                    refreshBackingBitmap()
                } else {
                    invalidate()
                }

                onStrokeCommitted(pathXml, canvasWidthPx, canvasHeightPx) {
                    exportSvgzBase64()
                }
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        if (isZoomedMode) {
            canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), workspaceBgPaint)
            canvas.save()
            canvas.translate(panOffsetX, panOffsetY)
            canvas.scale(zoomScale, zoomScale)
            canvas.drawRect(
                6f,
                6f,
                canvasWidthPx.toFloat() + 6f,
                canvasHeightPx.toFloat() + 6f,
                pageShadowPaint
            )
        }

        val bmp = backingBitmap
        if (bmp != null && nativeHandle != 0L) {
            canvas.drawBitmap(bmp, 0f, 0f, bitmapPaint)
        } else {
            canvas.drawRect(0f, 0f, canvasWidthPx.toFloat(), canvasHeightPx.toFloat(), paperPaint)
            if (yRuling > 0) {
                var y = yRuling.toFloat()
                while (y < canvasHeightPx) {
                    canvas.drawLine(0f, y, canvasWidthPx.toFloat(), y, gridPaint)
                    y += yRuling
                }
            }
            if (xRuling > 0) {
                var x = xRuling.toFloat()
                while (x < canvasWidthPx) {
                    canvas.drawLine(x, 0f, x, canvasHeightPx.toFloat(), gridPaint)
                    x += xRuling
                }
            }
            for ((path, paint) in fallbackPaths) {
                canvas.drawPath(path, paint)
            }
        }

        // Active live stroke preview
        activeLiveStrokePath?.let { canvas.drawPath(it, currentLiveStrokePaint) }

        // Active Selection Bounding Box + 4 Scale Corner Handles + Top Rotation Handle
        if (selectionState.hasSelection) {
            canvas.save()
            val b = RectF(selectionState.bounds)
            b.offset(selAccumulatedDx, selAccumulatedDy)
            b.inset(-6f, -6f)

            if (activeSelTransform == SelectionTransformInteraction.SCALE_CORNER) {
                canvas.scale(selPreviewScaleX, selPreviewScaleY, selPivotX, selPivotY)
            } else if (activeSelTransform == SelectionTransformInteraction.ROTATE_HANDLE) {
                val deg = (selPreviewRotateRad * 180.0 / PI).toFloat()
                canvas.rotate(deg, selPivotX, selPivotY)
            }

            canvas.drawRect(b, selectionFillPaint)
            canvas.drawRect(b, selectionBorderPaint)

            // Top rotation handle stem + circle
            val rotX = b.centerX()
            val rotY = b.top - 28f
            canvas.drawLine(rotX, b.top, rotX, rotY, selectionBorderPaint)
            canvas.drawCircle(rotX, rotY, 7.5f, rotateHandlePaint)

            // 4 corner scale handles
            val r = 6.5f
            canvas.drawCircle(b.left, b.top, r, selectionHandlePaint)
            canvas.drawCircle(b.right, b.top, r, selectionHandlePaint)
            canvas.drawCircle(b.left, b.bottom, r, selectionHandlePaint)
            canvas.drawCircle(b.right, b.bottom, r, selectionHandlePaint)
            canvas.restore()
        }

        // Active Selection Marquee / Lasso Preview
        if (!activeLassoPath.isEmpty) {
            canvas.drawPath(activeLassoPath, selectionFillPaint)
            canvas.drawPath(activeLassoPath, selectionBorderPaint)
        }
        val mStart = activeMarqueeStart
        val mCurr = activeMarqueeCurrent
        if (mStart != null && mCurr != null) {
            val rect = if (toolMode == StylusToolMode.SELECT_RULED) {
                RectF(
                    0f,
                    min(mStart.second, mCurr.second),
                    canvasWidthPx.toFloat(),
                    max(mStart.second, mCurr.second)
                )
            } else {
                RectF(
                    min(mStart.first, mCurr.first),
                    min(mStart.second, mCurr.second),
                    max(mStart.first, mCurr.first),
                    max(mStart.second, mCurr.second)
                )
            }
            canvas.drawRect(rect, selectionFillPaint)
            canvas.drawRect(rect, selectionBorderPaint)
        }

        // Active Insert Space Split Guides (Vertical, Horizontal, and Ruled L-Split)
        val insStartX = activeInsertSpaceStartX
        val insStartY = activeInsertSpaceStartY
        val insCurrX = activeInsertSpaceCurrentX
        val insCurrY = activeInsertSpaceCurrentY
        if (insStartX != null && insStartY != null && insCurrX != null && insCurrY != null) {
            when (toolMode) {
                StylusToolMode.INSERT_SPACE_HORZ -> {
                    val leftX = min(insStartX, insCurrX)
                    val rightX = max(insStartX, insCurrX)
                    canvas.drawRect(leftX, 0f, rightX, canvasHeightPx.toFloat(), insertSpaceFillPaint)
                    canvas.drawLine(insStartX, 0f, insStartX, canvasHeightPx.toFloat(), insertSpaceGuidePaint)
                    canvas.drawLine(insCurrX, 0f, insCurrX, canvasHeightPx.toFloat(), insertSpaceGuidePaint)
                }
                StylusToolMode.INSERT_SPACE_RULED -> {
                    val yr = if (yRuling > 0) yRuling.toFloat() else 40f
                    val startLineTop = floor(insStartY / yr) * yr
                    val startLineBot = startLineTop + yr
                    val dLines = ((insCurrY - insStartY) / yr).roundToInt()
                    val shiftedTop = startLineTop + dLines * yr

                    // Intra-line horizontal shift box on the active ruled line
                    val leftX = min(insStartX, insCurrX)
                    val rightX = max(insStartX, insCurrX)
                    canvas.drawRect(leftX, startLineTop, rightX, startLineBot, insertSpaceFillPaint)
                    canvas.drawLine(insStartX, startLineTop, insStartX, startLineBot, insertSpaceGuidePaint)
                    canvas.drawLine(insCurrX, startLineTop, insCurrX, startLineBot, insertSpaceGuidePaint)

                    // Full-width ruled vertical shift band below
                    val topY = min(startLineBot, shiftedTop + yr)
                    val botY = max(startLineBot, shiftedTop + yr)
                    canvas.drawRect(0f, topY, canvasWidthPx.toFloat(), botY, insertSpaceFillPaint)
                    canvas.drawLine(0f, startLineBot, canvasWidthPx.toFloat(), startLineBot, insertSpaceGuidePaint)
                    canvas.drawLine(0f, shiftedTop + yr, canvasWidthPx.toFloat(), shiftedTop + yr, insertSpaceGuidePaint)
                }
                else -> {
                    val topY = min(insStartY, insCurrY)
                    val botY = max(insStartY, insCurrY)
                    canvas.drawRect(0f, topY, canvasWidthPx.toFloat(), botY, insertSpaceFillPaint)
                    canvas.drawLine(0f, insStartY, canvasWidthPx.toFloat(), insStartY, insertSpaceGuidePaint)
                    canvas.drawLine(0f, insCurrY, canvasWidthPx.toFloat(), insCurrY, insertSpaceGuidePaint)
                }
            }
        }

        if (isZoomedMode) {
            canvas.restore()
        }
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        if (nativeHandle != 0L) {
            WriteNativeBridge.nativeDestroySession(nativeHandle)
            nativeHandle = 0L
        }
        backingBitmap?.recycle()
        backingBitmap = null
    }
}
