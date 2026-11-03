package com.orgwritenotes.ink

import android.content.ClipboardManager
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
import kotlin.math.sqrt

enum class StylusToolMode {
    PAN,
    PEN,
    HIGHLIGHTER,
    LASER,
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
    CUSTOM_DESIGNER(6, "Custom Designer"),
    WEEKLY_PLANNER(7, "Weekly Planner");

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
    val paperColorArgb: Int = if (OnyxEInkBridge.isEInkDevice) 0xFFFFFFFF.toInt() else 0xFFFFFDF8.toInt(),
    val ruleColorArgb: Int = if (OnyxEInkBridge.isEInkDevice) 0xFF64748B.toInt() else 0xFF4A90E2.toInt(),
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
    external fun nativeRetainSession(handle: Long)
    external fun nativeDestroySession(handle: Long)
    external fun nativeSetRenderTheme(handle: Long, isDark: Boolean, darkBgArgb: Int, darkFgArgb: Int)
    external fun nativeLoadSvgz(handle: Long, svgzBytes: ByteArray): Boolean
    external fun nativeExportSvgz(handle: Long): ByteArray?
    external fun nativeRenderToBitmap(handle: Long, bitmap: Bitmap): Boolean
    /** D4: renders only the tile whose top-left is page point (originX, originY); tile size = bitmap size. */
    external fun nativeRenderTileToBitmap(handle: Long, bitmap: Bitmap, originX: Float, originY: Float): Boolean
    external fun nativeRenderDirtyRectToBitmap(
        handle: Long,
        bitmap: Bitmap,
        left: Float,
        top: Float,
        right: Float,
        bottom: Float
    ): Boolean
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
    external fun nativeMoveStrokeBatch(
        handle: Long,
        pointsData6: FloatArray,
        pointCount: Int
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
    external fun nativeEraseSegmentDirty(
        handle: Long,
        prevX: Float,
        prevY: Float,
        x: Float,
        y: Float,
        radius: Float,
        eraseMode: Int,
        outDirty4: FloatArray
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
        outBounds4: FloatArray,
        outNewDimensions: IntArray
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
    external fun nativeSnapActiveStrokeToShape(
        handle: Long,
        outSnappedPoints: FloatArray,
        outNumPoints1: IntArray
    ): Int
    external fun nativeTrimWhitespace(
        handle: Long,
        paddingPx: Int,
        outNewDimensions: IntArray
    ): Boolean
    external fun nativeSetTemplateImageOpacity(handle: Long, opacity: Float): Boolean
}

/** D4: pages above this many pixels are rendered in viewport-sized tiles. */
private const val LARGE_CANVAS_PIXELS = 16_000_000L
private const val TILE_MARGIN_PX = 256

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
    adoptFromView: WriteCanvasBlockView? = null,
    private val onStrokeCommitted: (pathXml: String, width: Int, height: Int, exportSvgzBase64: (Boolean) -> String) -> Unit,
    private val onHeightExpanded: (newHeightPx: Int) -> Unit,
    var onSelectionChanged: ((WriteSelectionState) -> Unit)? = null,
    var onViewportChanged: ((zoomScale: Float, canUndo: Boolean, canRedo: Boolean, pageSetup: WritePageSetupState) -> Unit)? = null,
    var onCanvasActivated: (() -> Unit)? = null,
    var onFlushPendingChanges: ((width: Int, height: Int, svgzBase64: String) -> Unit)? = null,
    var onLaserPoint: ((x: Float, y: Float) -> Unit)? = null
) : View(context) {

    private enum class SelectionTransformInteraction {
        NONE,
        TRANSLATE,
        SCALE_CORNER,
        ROTATE_HANDLE
    }

    private val strokeSmoother = StrokeTuning.Smoother()
    private val nativeLock = Any()
    private val exportExecutor = java.util.concurrent.Executors.newSingleThreadExecutor { r ->
        Thread(r, "write-native-io").apply { isDaemon = true }
    }
    private var nativeHandle: Long = 0L
    private var hasUncommittedChanges: Boolean = false
    private var cachedDetachedSvgzBase64: String? = null
    var canvasWidthPx: Int = initialWidth
        private set
    var canvasHeightPx: Int = initialHeight
        private set
    private var backingBitmap: Bitmap? = null
    private var backingScale: Float = 1.0f
    private var overviewBitmap: Bitmap? = null
    private var overviewCanvas: Canvas? = null
    private var overviewDirty: Boolean = true
    private val overviewDirtyRect = RectF()
    private val overviewSrcRect = android.graphics.Rect()
    private val overviewDstRect = RectF()
    private val dstPageRect = RectF()

    private var cachedPageSetup = WritePageSetupState(
        width = initialWidth,
        height = initialHeight,
        xRuling = xRuling,
        yRuling = yRuling
    )
    private var cachedCanUndo: Boolean = false
    private var cachedCanRedo: Boolean = false
    private var lastReportedZoomPct: Int = 100

    var toolMode: StylusToolMode = StylusToolMode.PEN
    var singleTouchPanInZoomedMode: Boolean = true

    /** B1: ignore finger touches shortly after stylus activity (palm rest while writing). */
    var palmRejectionEnabled: Boolean = true
    var palmRejectionWindowMs: Long = 700L

    /** B1: in zoomed mode, fingers never draw (they only pan if [singleTouchPanInZoomedMode]). */
    var stylusOnlyMode: Boolean = false
    private var lastStylusActivityMs: Long = 0L
    var enableRuledReflow: Boolean = true
    private var penColorArgb: Int = if (OnyxEInkBridge.isEInkDevice) 0xFF000000.toInt() else 0xFF1E293B.toInt()
    private var penWidthPx: Float = 2.8f
    private var penTip: WritePenTip = WritePenTip.ROUND
    private var pressureSensitive: Boolean = true
    var snapToGrid: Boolean = false
        private set
    var lineDrawing: Boolean = false
        private set
    var penChiselAngle: Float = 45.0f
        private set

    // Tier 3: Optional Onyx Boox direct-to-EPD TouchHelper session (null on non-Onyx or when unsupported)
    private var onyxRawSession: OnyxEInkBridge.OnyxRawTouchSession? = null
    private var isOnyxRawStrokeInProgress: Boolean = false

    private val onyxRawStrokeListener = object : OnyxEInkBridge.RawStrokeListener {
        override fun onRawStrokeBegin(
            screenX: Float,
            screenY: Float,
            pressure: Float,
            tiltX: Float,
            tiltY: Float,
            timestampMs: Long
        ) {
            isOnyxRawStrokeInProgress = true
            onCanvasActivated?.invoke()
            val snappedX = snapX(screenToCanvasX(screenX))
            val snappedY = snapY(screenToCanvasY(screenY))
            strokeSmoother.reset(snappedX, snappedY)
            if (nativeHandle != 0L) {
                WriteNativeBridge.nativeBeginStroke(
                    nativeHandle,
                    snappedX,
                    snappedY,
                    StrokeTuning.curve(pressure),
                    tiltX,
                    tiltY,
                    timestampMs.toDouble()
                )
            }
        }

        override fun onRawStrokeMove(
            screenX: Float,
            screenY: Float,
            pressure: Float,
            tiltX: Float,
            tiltY: Float,
            timestampMs: Long
        ) {
            if (!isOnyxRawStrokeInProgress) return
            val snappedX = snapX(screenToCanvasX(screenX))
            val snappedY = snapY(screenToCanvasY(screenY))
            val (smoothX, smoothY) = strokeSmoother.apply(snappedX, snappedY)
            if (nativeHandle != 0L) {
                WriteNativeBridge.nativeMoveStroke(
                    nativeHandle,
                    smoothX,
                    smoothY,
                    StrokeTuning.curve(pressure),
                    tiltX,
                    tiltY,
                    timestampMs.toDouble()
                )
            }
        }

        override fun onRawStrokeEnd() {
            if (!isOnyxRawStrokeInProgress) return
            isOnyxRawStrokeInProgress = false
            finalizeActiveNativeStroke()
            OnyxEInkBridge.requestCleanRefresh(this@WriteCanvasBlockView)
        }

        override fun onRawEraseBegin(screenX: Float, screenY: Float, timestampMs: Long) {
            onCanvasActivated?.invoke()
            val cx = screenToCanvasX(screenX)
            val cy = screenToCanvasY(screenY)
            freeEraseLastX = cx
            freeEraseLastY = cy
            val eraseRadius = if (toolMode == StylusToolMode.ERASER_FREE) 18f else 22f
            activeEraserCursor = Triple(cx, cy, eraseRadius)
            anyErasedInGesture = false
            if (nativeHandle != 0L) {
                val eraseModeCode = when (toolMode) {
                    StylusToolMode.ERASER_RULED -> 1
                    StylusToolMode.ERASER_FREE -> 2
                    else -> 0
                }
                if (WriteNativeBridge.nativeEraseSegmentDirty(
                        nativeHandle, cx, cy, cx, cy, eraseRadius, eraseModeCode, tmpDirty4
                    )
                ) {
                    anyErasedInGesture = true
                    refreshDirtyRect(tmpDirty4[0], tmpDirty4[1], tmpDirty4[2], tmpDirty4[3], notifyState = false)
                }
            }
        }

        override fun onRawEraseMove(screenX: Float, screenY: Float, timestampMs: Long) {
            val cx = screenToCanvasX(screenX)
            val cy = screenToCanvasY(screenY)
            if (freeEraseLastX == 0f && freeEraseLastY == 0f) {
                freeEraseLastX = cx
                freeEraseLastY = cy
            }
            val eraseRadius = if (toolMode == StylusToolMode.ERASER_FREE) 18f else 22f
            activeEraserCursor = Triple(cx, cy, eraseRadius)
            if (nativeHandle != 0L) {
                val eraseModeCode = when (toolMode) {
                    StylusToolMode.ERASER_RULED -> 1
                    StylusToolMode.ERASER_FREE -> 2
                    else -> 0
                }
                if (WriteNativeBridge.nativeEraseSegmentDirty(
                        nativeHandle, freeEraseLastX, freeEraseLastY, cx, cy, eraseRadius, eraseModeCode, tmpDirty4
                    )
                ) {
                    anyErasedInGesture = true
                    refreshDirtyRect(tmpDirty4[0], tmpDirty4[1], tmpDirty4[2], tmpDirty4[3], notifyState = false)
                }
            }
            freeEraseLastX = cx
            freeEraseLastY = cy
        }

        override fun onRawEraseEnd() {
            activeEraserCursor = null
            freeEraseLastX = 0f
            freeEraseLastY = 0f
            invalidate()
            if (anyErasedInGesture) {
                anyErasedInGesture = false
                notifyViewportUpdated()
                OnyxEInkBridge.requestCleanRefresh(this@WriteCanvasBlockView)
                onStrokeCommitted("<erase/>", canvasWidthPx, canvasHeightPx, createDeferredSvgzExporter())
            }
        }
    }

    private fun syncOnyxRawDrawingState() {
        val session = onyxRawSession ?: return
        val shouldUseRawDrawing = (toolMode == StylusToolMode.PEN || toolMode == StylusToolMode.HIGHLIGHTER) &&
            !selectionState.hasSelection &&
            !lineDrawing
        if (shouldUseRawDrawing) {
            val effZoom = if (isZoomedMode) zoomScale else 1.0f
            session.updateLimitRect(this)
            session.configureStroke(penWidthPx * effZoom, resolveLivePreviewStrokeColor())
            session.setRawDrawingEnabled(true, renderToEpd = true)
        } else {
            session.setRawDrawingEnabled(false)
        }
    }

    // Viewport Pan & Zoom state (active when isZoomedMode == true)
    var zoomScale: Float = 1.0f
        internal set
    var panOffsetX: Float = 24f
        internal set
    var panOffsetY: Float = 24f
        internal set

    var isDarkTheme: Boolean = false
        private set
    var darkBgColorArgb: Int = 0xFF181825.toInt()
    var darkFgColorArgb: Int = 0xFFCDD6F4.toInt()
    private val mainHandler = android.os.Handler(android.os.Looper.getMainLooper())

    private fun isArgbDark(argb: Int): Boolean {
        val r = (argb ushr 16) and 0xFF
        val g = (argb ushr 8) and 0xFF
        val b = argb and 0xFF
        return (r * 299 + g * 587 + b * 114) < 128_000
    }

    private fun resolveLivePreviewStrokeColor(): Int {
        if (OnyxEInkBridge.isEInkDevice && toolMode != StylusToolMode.HIGHLIGHTER) {
            return 0xFF000000.toInt()
        }
        if (toolMode == StylusToolMode.HIGHLIGHTER) return penColorArgb
        val r = (penColorArgb ushr 16) and 0xFF
        val g = (penColorArgb ushr 8) and 0xFF
        val b = penColorArgb and 0xFF
        return if (isDarkTheme) {
            if (r < 75 && g < 75 && b < 85) {
                darkFgColorArgb or 0xFF000000.toInt()
            } else {
                penColorArgb
            }
        } else {
            if (r > 205 && g > 205 && b > 205) {
                0xFF1E293B.toInt()
            } else {
                penColorArgb
            }
        }
    }

    fun setAdaptiveTheme(isDark: Boolean, bgArgb: Int = 0xFF181825.toInt(), fgArgb: Int = 0xFFCDD6F4.toInt()) {
        // On E-Ink devices, dark/light theme switching is disabled: always enforce crisp black ink on light paper.
        val effectiveDark = if (OnyxEInkBridge.isEInkDevice) false else isDark
        val safeBg = (bgArgb or 0xFF000000.toInt()).let {
            if (isArgbDark(it)) it else 0xFF181825.toInt()
        }
        val safeFg = (fgArgb or 0xFF000000.toInt()).let {
            if (!isArgbDark(it)) it else 0xFFCDD6F4.toInt()
        }
        val changed = isDarkTheme != effectiveDark || darkBgColorArgb != safeBg || darkFgColorArgb != safeFg
        isDarkTheme = effectiveDark
        darkBgColorArgb = safeBg
        darkFgColorArgb = safeFg
        workspaceBgPaint.color = when {
            OnyxEInkBridge.isEInkDevice -> 0xFFF1F5F9.toInt()
            effectiveDark -> 0xFF18181B.toInt()
            else -> 0xFFE2E8F0.toInt()
        }
        paperPaint.color = when {
            OnyxEInkBridge.isEInkDevice -> 0xFFFFFFFF.toInt()
            effectiveDark -> safeBg
            else -> 0xFFFFFDF8.toInt()
        }
        gridPaint.color = when {
            OnyxEInkBridge.isEInkDevice -> 0xFF64748B.toInt()
            effectiveDark -> 0x55334155
            else -> 0x264A90E2
        }
        currentLiveStrokePaint.color = resolveLivePreviewStrokeColor()
        synchronized(nativeLock) {
            if (nativeHandle != 0L) {
                WriteNativeBridge.nativeSetRenderTheme(nativeHandle, effectiveDark, safeBg, safeFg)
                if (changed) {
                    refreshBackingBitmap()
                }
            } else if (changed) {
                invalidate()
            }
        }
    }

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
    private var activeEraserCursor: Triple<Float, Float, Float>? = null
    private var isHardwareErasingActive: Boolean = false
    private var anyErasedInGesture: Boolean = false
    var barrelButtonLassoMode: Boolean = false
    private var liveStrokeStartX: Float = 0f
    private var liveStrokeStartY: Float = 0f
    private var liveStrokeLastX: Float = 0f
    private var liveStrokeLastY: Float = 0f
    private var liveStrokeLastTimeMs: Double = 0.0
    private var liveStrokeVelX: Float = 0f
    private var liveStrokeVelY: Float = 0f
    private var holdSnapLastX: Float = 0f
    private var holdSnapLastY: Float = 0f
    private var holdSnapTriggered: Boolean = false
    private val holdSnapRunnable = Runnable {
        if (nativeHandle != 0L && activeLiveStrokePath != null && !holdSnapTriggered && toolMode == StylusToolMode.PEN && !lineDrawing) {
            val snappedPts = FloatArray(256)
            val numPts = IntArray(1)
            val shapeType = WriteNativeBridge.nativeSnapActiveStrokeToShape(nativeHandle, snappedPts, numPts)
            if (shapeType > 0 && numPts[0] >= 2) {
                holdSnapTriggered = true
                predictedTipPath.reset()
                reusableLiveStrokePath.reset()
                reusableLiveStrokePath.moveTo(snappedPts[0], snappedPts[1])
                for (i in 1 until numPts[0]) {
                    reusableLiveStrokePath.lineTo(snappedPts[i * 2], snappedPts[i * 2 + 1])
                }
                activeLiveStrokePath = reusableLiveStrokePath
                invalidate()
            }
        }
    }

    // Laser pointer ephemeral trail points: Triple(x, y, timestampMs)
    private data class LaserPt(val x: Float, val y: Float, val timeMs: Long, val newSegment: Boolean = false)
    private val laserTrailPoints = mutableListOf<LaserPt>()
    private val laserCorePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFFF1744.toInt()
        strokeWidth = 4.5f
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val laserGlowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0x55FF1744
        strokeWidth = 14f
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }

    // Pre-allocated primitive buffers and paths to avoid per-frame / per-touch GC pressure
    private val tmpPageInfo10 = IntArray(10)
    private val tmpDimsAndDirty6 = IntArray(6)
    private val tmpDims2 = IntArray(2)
    private val tmpBounds4 = FloatArray(4)
    private val tmpDirty4 = FloatArray(4)
    private var strokeBatchBuf = FloatArray(64 * 6)
    private val reusableLiveStrokePath = Path()
    private val predictedTipPath = Path()
    private val tmpDrawRect = RectF()
    private val tmpHitRect = RectF()

    @Volatile
    var lastSyncedSvgzBase64: String? = null
        private set

    @Volatile
    private var adoptedPeer: WriteCanvasBlockView? = null

    // Pan & multi-finger tap gesture tracking
    private var isPanningGesture = false
    private var panLastTouchX = 0f
    private var panLastTouchY = 0f
    private var multiFingerTapStartTime = 0L
    private var multiFingerMaxPointers = 0

    // Fallback path list when running in JVM preview without NDK loaded
    private val fallbackPaths = mutableListOf<Pair<Path, Paint>>()
    private var activeLiveStrokePath: Path? = null

    private val bitmapPaint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val workspaceBgPaint = Paint().apply {
        color = if (OnyxEInkBridge.isEInkDevice) 0xFFF1F5F9.toInt() else 0xFF18181B.toInt()
        style = Paint.Style.FILL
    }
    private val pageShadowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = if (OnyxEInkBridge.isEInkDevice) 0xFF000000.toInt() else 0x66000000
        style = if (OnyxEInkBridge.isEInkDevice) Paint.Style.STROKE else Paint.Style.FILL
        strokeWidth = if (OnyxEInkBridge.isEInkDevice) 2.0f else 0f
    }
    private val paperPaint = Paint().apply {
        color = if (OnyxEInkBridge.isEInkDevice) 0xFFFFFFFF.toInt() else 0xFFFFFDF8.toInt()
        style = Paint.Style.FILL
    }
    private val gridPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = if (OnyxEInkBridge.isEInkDevice) 0xFF64748B.toInt() else 0x264A90E2
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
                OnyxEInkBridge.enterFastPanMode(this@WriteCanvasBlockView)
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
                val newPct = (newScale * 100f).roundToInt()
                if (newPct != lastReportedZoomPct) {
                    lastReportedZoomPct = newPct
                    notifyZoomOnlyUpdated()
                }
                invalidate()
                return true
            }

            override fun onScaleEnd(detector: ScaleGestureDetector) {
                if (!isZoomedMode) return
                lastReportedZoomPct = (zoomScale * 100f).roundToInt()
                notifyZoomOnlyUpdated()
                syncOnyxRawDrawingState()
                OnyxEInkBridge.exitFastPanModeAndRefresh(this@WriteCanvasBlockView)
            }
        }
    ).apply {
        isQuickScaleEnabled = false
        isStylusScaleEnabled = false
    }

    private fun applyEInkPaperDefaultsIfNeeded() {
        if (!OnyxEInkBridge.isEInkDevice || nativeHandle == 0L) return
        val info = tmpPageInfo10
        WriteNativeBridge.nativeGetPageInfo(nativeHandle, info)
        val curPaper = info[5]
        val curRule = info[6]
        val needsWhitePaper = isArgbDark(curPaper) || curPaper == 0xFFFFFDF8.toInt()
        if (needsWhitePaper) {
            val crispRule = if ((curRule ushr 24) < 0x80) 0xFF64748B.toInt() else curRule
            WriteNativeBridge.nativeSetPageSetup(
                nativeHandle,
                info[0].coerceAtLeast(100),
                info[1].coerceAtLeast(100),
                info[2],
                info[3],
                info[4],
                0xFFFFFFFF.toInt(),
                crispRule,
                info[8]
            )
        }
    }

    init {
        if (WriteNativeBridge.isNativeLoaded) {
            if (adoptFromView != null && adoptFromView.nativeHandle != 0L) {
                adoptSessionAndBitmapFrom(adoptFromView, transferBitmapOwnership = false)
            } else {
                nativeHandle = WriteNativeBridge.nativeCreateSession(
                    initialWidth, initialHeight, xRuling, yRuling
                )
                applyEInkPaperDefaultsIfNeeded()
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
    }

    private fun screenToCanvasX(screenX: Float): Float =
        if (isZoomedMode) (screenX - panOffsetX) / zoomScale else screenX

    private fun screenToCanvasY(screenY: Float): Float =
        if (isZoomedMode) (screenY - panOffsetY) / zoomScale else screenY

    private fun snapX(x: Float): Float {
        if (!snapToGrid) return x
        val yr = if (yRuling > 0) yRuling.toFloat() else 40f
        val xr = if (xRuling > 0) xRuling.toFloat() else yr
        return floor(x / xr + 0.5f) * xr
    }

    private fun snapY(y: Float): Float {
        if (!snapToGrid) return y
        val yr = if (yRuling > 0) yRuling.toFloat() else 40f
        return floor(y / yr + 0.5f) * yr
    }

    private fun computeTargetBackingScale(w: Int, h: Int): Float {
        val maxDim = max(w, h).coerceAtLeast(1).toFloat()
        val totalPixels = w.toFloat() * h.toFloat()
        val dimScale = 2560f / maxDim
        val areaScale = if (totalPixels > 3_600_000f) sqrt(3_600_000f / totalPixels) else 1.0f
        return min(1.0f, min(dimScale, areaScale)).coerceAtLeast(0.25f)
    }

    private fun computeTargetBitmapDims(w: Int, h: Int): Pair<Int, Int> {
        val cw = w.coerceAtLeast(100)
        val ch = h.coerceAtLeast(100)
        val scale = computeTargetBackingScale(cw, ch)
        backingScale = scale
        val bmpW = (cw * scale).roundToInt().coerceAtLeast(100)
        val bmpH = (ch * scale).roundToInt().coerceAtLeast(100)
        return bmpW to bmpH
    }

    // --- D4: tiled rendering for very large pages -------------------------------------------
    // Above LARGE_CANVAS_PIXELS the page is no longer rasterised in one piece (a 1200x40000 page
    // would need ~190 MB). Only the visible region plus a margin is rendered, and re-rendered when
    // the viewport leaves it.
    private var tileOriginX = 0
    private var tileOriginY = 0
    private var tileRefreshPending = false

    private fun isTiled(): Boolean =
        isZoomedMode && canvasWidthPx.toLong() * canvasHeightPx.toLong() > LARGE_CANVAS_PIXELS

    private fun visibleCanvasRect(): RectF {
        val z = if (zoomScale > 0f) zoomScale else 1f
        return RectF(
            (-panOffsetX / z).coerceAtLeast(0f),
            (-panOffsetY / z).coerceAtLeast(0f),
            ((width - panOffsetX) / z).coerceAtMost(canvasWidthPx.toFloat()),
            ((height - panOffsetY) / z).coerceAtMost(canvasHeightPx.toFloat())
        )
    }

    /** Returns [originX, originY, tileWidth, tileHeight] in page pixels. */
    private fun desiredTile(): IntArray {
        val vis = visibleCanvasRect()
        val tw = minOf(canvasWidthPx, (vis.width() + 2 * TILE_MARGIN_PX).toInt().coerceAtLeast(256))
        val th = minOf(canvasHeightPx, (vis.height() + 2 * TILE_MARGIN_PX).toInt().coerceAtLeast(256))
        val ox = (vis.left - TILE_MARGIN_PX).toInt().coerceIn(0, (canvasWidthPx - tw).coerceAtLeast(0))
        val oy = (vis.top - TILE_MARGIN_PX).toInt().coerceIn(0, (canvasHeightPx - th).coerceAtLeast(0))
        return intArrayOf(ox, oy, tw, th)
    }

    private fun tileCoversViewport(): Boolean {
        val bmp = backingBitmap ?: return false
        val vis = visibleCanvasRect()
        return vis.left >= tileOriginX && vis.top >= tileOriginY &&
            vis.right <= tileOriginX + bmp.width && vis.bottom <= tileOriginY + bmp.height
    }

    private fun scheduleTileRefresh() {
        if (tileRefreshPending) return
        tileRefreshPending = true
        post {
            tileRefreshPending = false
            synchronized(nativeLock) { refreshBackingBitmap() }
        }
    }

    private fun ensureBackingBitmap(): Bitmap {
        val (bmpW, bmpH) = computeTargetBitmapDims(canvasWidthPx, canvasHeightPx)
        val existing = backingBitmap
        if (existing != null && existing.width == bmpW && existing.height == bmpH) {
            return existing
        }
        existing?.recycle()
        val created = Bitmap.createBitmap(bmpW, bmpH, Bitmap.Config.ARGB_8888)
        backingBitmap = created
        return created
    }

    private fun invalidateOverviewFull() {
        overviewDirty = true
        overviewDirtyRect.set(0f, 0f, canvasWidthPx.toFloat(), canvasHeightPx.toFloat())
    }

    private fun invalidateOverviewRect(left: Float, top: Float, right: Float, bottom: Float) {
        if (!overviewDirty) {
            overviewDirty = true
            overviewDirtyRect.set(left, top, right, bottom)
        } else {
            overviewDirtyRect.union(left, top, right, bottom)
        }
    }

    private fun ensureOverviewBitmapIfNeeded(): Bitmap? {
        val primary = backingBitmap ?: return null
        val targetW = (primary.width / 2).coerceAtLeast(64)
        val targetH = (primary.height / 2).coerceAtLeast(64)
        var ov = overviewBitmap
        var fullRedraw = false
        if (ov == null || ov.isRecycled || ov.width != targetW || ov.height != targetH) {
            ov?.recycle()
            ov = Bitmap.createBitmap(targetW, targetH, Bitmap.Config.ARGB_8888)
            overviewBitmap = ov
            overviewCanvas = Canvas(ov)
            fullRedraw = true
        }
        if (fullRedraw || overviewDirty) {
            val c = overviewCanvas ?: Canvas(ov).also { overviewCanvas = it }
            if (fullRedraw ||
                overviewDirtyRect.width() >= canvasWidthPx * 0.65f ||
                overviewDirtyRect.height() >= canvasHeightPx * 0.65f
            ) {
                overviewSrcRect.set(0, 0, primary.width, primary.height)
                overviewDstRect.set(0f, 0f, targetW.toFloat(), targetH.toFloat())
                c.drawBitmap(primary, overviewSrcRect, overviewDstRect, bitmapPaint)
            } else {
                val sx = primary.width.toFloat() / canvasWidthPx.coerceAtLeast(1).toFloat()
                val sy = primary.height.toFloat() / canvasHeightPx.coerceAtLeast(1).toFloat()
                val pad = 4
                val ox0 = ((overviewDirtyRect.left * sx * 0.5f).toInt() - pad).coerceIn(0, targetW)
                val oy0 = ((overviewDirtyRect.top * sy * 0.5f).toInt() - pad).coerceIn(0, targetH)
                val ox1 = ((overviewDirtyRect.right * sx * 0.5f).toInt() + pad).coerceIn(0, targetW)
                val oy1 = ((overviewDirtyRect.bottom * sy * 0.5f).toInt() + pad).coerceIn(0, targetH)
                if (ox1 > ox0 && oy1 > oy0) {
                    val sx0 = (ox0 * 2).coerceIn(0, primary.width)
                    val sy0 = (oy0 * 2).coerceIn(0, primary.height)
                    val sx1 = (ox1 * 2).coerceIn(0, primary.width)
                    val sy1 = (oy1 * 2).coerceIn(0, primary.height)
                    overviewSrcRect.set(sx0, sy0, sx1, sy1)
                    overviewDstRect.set(ox0.toFloat(), oy0.toFloat(), ox1.toFloat(), oy1.toFloat())
                    c.save()
                    c.clipRect(overviewDstRect)
                    c.drawBitmap(primary, overviewSrcRect, overviewDstRect, bitmapPaint)
                    c.restore()
                }
            }
            overviewDirty = false
            overviewDirtyRect.setEmpty()
        }
        return ov
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
        invalidateOverviewFull()
        notifyViewportUpdated()
        invalidate()
    }

    private fun expandBackingBitmapOrRefresh(
        oldW: Int,
        oldH: Int,
        extraDirtyLeft: Float = 0f,
        extraDirtyTop: Float = 0f,
        extraDirtyRight: Float = 0f,
        extraDirtyBottom: Float = 0f
    ) {
        if (nativeHandle == 0L) return
        val newW = canvasWidthPx.coerceAtLeast(100)
        val newH = canvasHeightPx.coerceAtLeast(100)
        val existing = backingBitmap
        val newScale = computeTargetBackingScale(newW, newH)
        if (existing == null ||
            newW < oldW ||
            newH < oldH ||
            newScale < 0.999f ||
            existing.width != oldW ||
            existing.height != oldH
        ) {
            refreshBackingBitmap()
            return
        }
        if (newW == oldW && newH == oldH) {
            if (extraDirtyRight > extraDirtyLeft && extraDirtyBottom > extraDirtyTop) {
                refreshDirtyRect(extraDirtyLeft, extraDirtyTop, extraDirtyRight, extraDirtyBottom, notifyState = true)
            } else {
                notifyViewportUpdated()
                invalidate()
            }
            return
        }

        backingScale = 1.0f
        val created = Bitmap.createBitmap(newW, newH, Bitmap.Config.ARGB_8888)
        val copyCanvas = Canvas(created)
        copyCanvas.drawBitmap(existing, 0f, 0f, null)
        existing.recycle()
        backingBitmap = created

        if (newW > oldW) {
            WriteNativeBridge.nativeRenderDirtyRectToBitmap(
                nativeHandle,
                created,
                (oldW - 8).coerceAtLeast(0).toFloat(),
                0f,
                newW.toFloat(),
                oldH.toFloat()
            )
        }
        if (newH > oldH) {
            WriteNativeBridge.nativeRenderDirtyRectToBitmap(
                nativeHandle,
                created,
                0f,
                (oldH - 8).coerceAtLeast(0).toFloat(),
                newW.toFloat(),
                newH.toFloat()
            )
        }
        if (extraDirtyRight > extraDirtyLeft && extraDirtyBottom > extraDirtyTop) {
            WriteNativeBridge.nativeRenderDirtyRectToBitmap(
                nativeHandle,
                created,
                extraDirtyLeft,
                extraDirtyTop,
                extraDirtyRight,
                extraDirtyBottom
            )
        }
        invalidateOverviewFull()
        notifyViewportUpdated()
        invalidate()
    }

    private fun refreshDirtyRect(
        left: Float,
        top: Float,
        right: Float,
        bottom: Float,
        notifyState: Boolean = true
    ) {
        if (nativeHandle == 0L) return
        val existing = backingBitmap
        val (targetW, targetH) = computeTargetBitmapDims(canvasWidthPx, canvasHeightPx)
        if (existing == null ||
            existing.width != targetW ||
            existing.height != targetH ||
            right < left ||
            bottom < top
        ) {
            refreshBackingBitmap()
            return
        }
        WriteNativeBridge.nativeRenderDirtyRectToBitmap(nativeHandle, existing, left, top, right, bottom)
        invalidateOverviewRect(left, top, right, bottom)
        if (notifyState) {
            notifyViewportUpdated()
        }
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
        val info = tmpPageInfo10
        WriteNativeBridge.nativeGetPageInfo(nativeHandle, info)
        val state = WritePageSetupState(
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
        cachedPageSetup = state
        return state
    }

    fun canUndoNow(): Boolean {
        val res = nativeHandle != 0L && WriteNativeBridge.nativeCanUndo(nativeHandle)
        cachedCanUndo = res
        return res
    }

    fun canRedoNow(): Boolean {
        val res = nativeHandle != 0L && WriteNativeBridge.nativeCanRedo(nativeHandle)
        cachedCanRedo = res
        return res
    }

    fun queryPageSetup(): WritePageSetupState = queryPageSetupState()

    private fun notifyViewportUpdated() {
        lastReportedZoomPct = (zoomScale * 100f).roundToInt()
        onViewportChanged?.invoke(zoomScale, canUndoNow(), canRedoNow(), queryPageSetupState())
    }

    private fun notifyZoomOnlyUpdated() {
        onViewportChanged?.invoke(zoomScale, cachedCanUndo, cachedCanRedo, cachedPageSetup)
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
        syncOnyxRawDrawingState()
        onSelectionChanged?.invoke(selectionState)
        invalidate()
    }

    fun loadFromBase64Svgz(base64: String?) {
        if (base64.isNullOrEmpty()) return
        if (base64 == lastSyncedSvgzBase64) return
        synchronized(nativeLock) {
            cachedDetachedSvgzBase64 = base64
            hasUncommittedChanges = false
        }
        exportExecutor.execute {
            val loaded = synchronized(nativeLock) {
                if (nativeHandle == 0L || cachedDetachedSvgzBase64 != base64) {
                    false
                } else {
                    val ok = WriteNativeBridge.nativeLoadSvgz(nativeHandle, Base64.decode(base64, Base64.DEFAULT))
                    if (ok) {
                        lastSyncedSvgzBase64 = base64
                        applyEInkPaperDefaultsIfNeeded()
                        WriteNativeBridge.nativeSetRenderTheme(nativeHandle, isDarkTheme, darkBgColorArgb, darkFgColorArgb)
                    }
                    ok
                }
            }
            if (loaded) {
                mainHandler.post {
                    synchronized(nativeLock) { refreshBackingBitmap() }
                }
            }
        }
    }

    fun exportSvgzBase64(): String = synchronized(nativeLock) {
        if (nativeHandle == 0L) {
            return cachedDetachedSvgzBase64.orEmpty()
        }
        val bytes = WriteNativeBridge.nativeExportSvgz(nativeHandle) ?: return cachedDetachedSvgzBase64.orEmpty()
        val encoded = Base64.encodeToString(bytes, Base64.NO_WRAP)
        cachedDetachedSvgzBase64 = encoded
        lastSyncedSvgzBase64 = encoded
        hasUncommittedChanges = false
        encoded
    }

    /**
     * D1: runs the native export on a background thread and delivers the result on the main thread.
     * Safe if the session is destroyed meanwhile: [exportSvgzBase64] falls back to the cached copy.
     */
    fun exportSvgzBase64Async(onResult: (String) -> Unit) {
        exportExecutor.execute {
            val result = try {
                exportSvgzBase64()
            } catch (_: Exception) {
                cachedDetachedSvgzBase64.orEmpty()
            }
            post { onResult(result) }
        }
    }

    /** E6: Export current canvas drawing as a Bitmap for saving or sharing. */
    fun exportBitmap(): Bitmap? = synchronized(nativeLock) {
        if (canvasWidthPx <= 0 || canvasHeightPx <= 0) return null
        val bmp = try {
            Bitmap.createBitmap(canvasWidthPx, canvasHeightPx, Bitmap.Config.ARGB_8888)
        } catch (_: OutOfMemoryError) {
            return null // very large page: PNG export is skipped rather than crashing; SVG export still works
        }
        if (nativeHandle != 0L) {
            WriteNativeBridge.nativeRenderToBitmap(nativeHandle, bmp)
            bmp
        } else {
            backingBitmap?.let { src ->
                val canvas = Canvas(bmp)
                canvas.drawBitmap(src, 0f, 0f, null)
                bmp
            }
        }
    }

    /**
     * Shares the reference-counted C++ `NativeCanvasSession` and rendered `backingBitmap`
     * between the inline view and the full-screen zoomed view so opening or closing
     * a large canvas never parses `.svgz` XML or re-rasterizes on the UI thread.
     */
    fun adoptSessionAndBitmapFrom(
        source: WriteCanvasBlockView,
        transferBitmapOwnership: Boolean = false
    ): Boolean {
        val srcHandle = source.nativeHandle
        if (!WriteNativeBridge.isNativeLoaded || srcHandle == 0L) return false
        if (srcHandle != nativeHandle) {
            WriteNativeBridge.nativeRetainSession(srcHandle)
            if (nativeHandle != 0L) {
                WriteNativeBridge.nativeDestroySession(nativeHandle)
            }
            nativeHandle = srcHandle
        }

        adoptedPeer = source
        source.adoptedPeer = this
        lastSyncedSvgzBase64 = source.lastSyncedSvgzBase64
        cachedDetachedSvgzBase64 = source.cachedDetachedSvgzBase64

        canvasWidthPx = source.canvasWidthPx
        canvasHeightPx = source.canvasHeightPx
        xRuling = source.xRuling
        yRuling = source.yRuling
        cachedPageSetup = source.cachedPageSetup
        cachedCanUndo = source.cachedCanUndo
        cachedCanRedo = source.cachedCanRedo
        backingScale = source.backingScale

        val srcBmp = source.backingBitmap
        if (srcBmp != null && !srcBmp.isRecycled) {
            if (transferBitmapOwnership) {
                if (backingBitmap !== srcBmp) {
                    backingBitmap?.recycle()
                    backingBitmap = srcBmp
                }
                source.backingBitmap = null

                val srcOv = source.overviewBitmap
                if (srcOv != null && !srcOv.isRecycled) {
                    if (overviewBitmap !== srcOv) {
                        overviewBitmap?.recycle()
                        overviewBitmap = srcOv
                        overviewCanvas = source.overviewCanvas
                    }
                    source.overviewBitmap = null
                    source.overviewCanvas = null
                    overviewDirty = source.overviewDirty
                    overviewDirtyRect.set(source.overviewDirtyRect)
                } else {
                    invalidateOverviewFull()
                }
            } else {
                val existing = backingBitmap
                if (existing != null && !existing.isRecycled &&
                    existing.width == srcBmp.width && existing.height == srcBmp.height
                ) {
                    Canvas(existing).drawBitmap(srcBmp, 0f, 0f, null)
                } else {
                    existing?.recycle()
                    backingBitmap = srcBmp.copy(Bitmap.Config.ARGB_8888, true)
                }
                invalidateOverviewFull()
            }
        } else {
            refreshBackingBitmap()
        }

        WriteNativeBridge.nativeClearSelection(nativeHandle)
        selectionState = WriteSelectionState()
        onSelectionChanged?.invoke(selectionState)

        WriteNativeBridge.nativeSetPen(
            nativeHandle,
            penColorArgb,
            penWidthPx,
            pressureSensitive && toolMode == StylusToolMode.PEN,
            if (toolMode == StylusToolMode.HIGHLIGHTER) WritePenTip.FLAT.nativeCode else penTip.nativeCode,
            snapToGrid,
            lineDrawing,
            penChiselAngle
        )

        onHeightExpanded(canvasHeightPx)
        notifyViewportUpdated()
        invalidate()
        return true
    }

    /**
     * Creates a reference-counted background exporter closure that retains the native
     * C++ session handle until the export finishes (or is cancelled), so `.svgz`
     * compression never blocks the UI thread or races with `onDetachedFromWindow()`.
     */
    private fun createDeferredSvgzExporter(): (Boolean) -> String {
        val h = nativeHandle
        if (h == 0L) return { "" }
        WriteNativeBridge.nativeRetainSession(h)
        val released = java.util.concurrent.atomic.AtomicBoolean(false)
        return { shouldExport ->
            try {
                if (shouldExport) {
                    val bytes = WriteNativeBridge.nativeExportSvgz(h)
                    if (bytes != null && bytes.isNotEmpty()) {
                        val encoded = Base64.encodeToString(bytes, Base64.NO_WRAP)
                        lastSyncedSvgzBase64 = encoded
                        cachedDetachedSvgzBase64 = encoded
                        adoptedPeer?.lastSyncedSvgzBase64 = encoded
                        adoptedPeer?.cachedDetachedSvgzBase64 = encoded
                        encoded
                    } else {
                        ""
                    }
                } else {
                    ""
                }
            } finally {
                if (released.compareAndSet(false, true)) {
                    WriteNativeBridge.nativeDestroySession(h)
                }
            }
        }
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
        val newColorArgb = if (mode == StylusToolMode.HIGHLIGHTER) {
            (colorArgb and 0x00FFFFFF) or 0x55000000
        } else {
            colorArgb or 0xFF000000.toInt()
        }
        val newWidthPx = if (mode == StylusToolMode.HIGHLIGHTER) strokeWidth * 4.5f else strokeWidth

        if (toolMode == mode &&
            penTip == tip &&
            pressureSensitive == pressure &&
            snapToGrid == snapGrid &&
            lineDrawing == straightLine &&
            penChiselAngle == chiselAngle &&
            penColorArgb == newColorArgb &&
            penWidthPx == newWidthPx
        ) {
            return
        }

        toolMode = mode
        penTip = tip
        pressureSensitive = pressure
        snapToGrid = snapGrid
        lineDrawing = straightLine
        penChiselAngle = chiselAngle
        penColorArgb = newColorArgb
        penWidthPx = newWidthPx

        currentLiveStrokePaint.color = resolveLivePreviewStrokeColor()
        currentLiveStrokePaint.strokeWidth = penWidthPx
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
        syncOnyxRawDrawingState()
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
        lastReportedZoomPct = (zoomScale * 100f).roundToInt()
        notifyZoomOnlyUpdated()
        syncOnyxRawDrawingState()
        OnyxEInkBridge.requestCleanRefresh(this)
    }

    fun resetZoom100() {
        if (!isZoomedMode) return
        zoomScale = 1.0f
        panOffsetX = ((width - canvasWidthPx) / 2f).coerceAtLeast(16f)
        panOffsetY = 24f
        lastReportedZoomPct = 100
        notifyZoomOnlyUpdated()
        syncOnyxRawDrawingState()
        OnyxEInkBridge.requestCleanRefresh(this)
    }

    fun zoomFitWidth() {
        if (!isZoomedMode || width <= 0) return
        val targetScale = ((width - 48f) / canvasWidthPx.toFloat()).coerceIn(0.25f, 3.0f)
        zoomScale = targetScale
        panOffsetX = (width - canvasWidthPx * zoomScale) / 2f
        panOffsetY = 24f
        lastReportedZoomPct = (zoomScale * 100f).roundToInt()
        notifyZoomOnlyUpdated()
        syncOnyxRawDrawingState()
        OnyxEInkBridge.requestCleanRefresh(this)
    }

    fun zoomFitPage() {
        if (!isZoomedMode || width <= 0 || height <= 0) return
        val scaleX = (width - 48f) / canvasWidthPx.toFloat()
        val scaleY = (height - 48f) / canvasHeightPx.toFloat()
        val targetScale = minOf(scaleX, scaleY).coerceIn(0.2f, 3.0f)
        zoomScale = targetScale
        panOffsetX = (width - canvasWidthPx * zoomScale) / 2f
        panOffsetY = ((height - canvasHeightPx * zoomScale) / 2f).coerceAtLeast(16f)
        notifyViewportUpdated()
        syncOnyxRawDrawingState()
        OnyxEInkBridge.requestCleanRefresh(this)
    }

    /** Discrete vertical page scroll for E-Ink reading/writing without drag-pan ghosting. */
    fun pageScrollVertical(direction: Int) {
        if (!isZoomedMode || height <= 0) return
        val step = (height * 0.8f) * direction
        panOffsetY -= step
        notifyZoomOnlyUpdated()
        OnyxEInkBridge.requestCleanRefresh(this)
    }

    // Selection Actions (Translate, Scale, Rotate, Copy, Cut, Paste, Duplicate, Restyle, Delete)
    fun selectAllStrokes() {
        if (nativeHandle == 0L) return
        val bounds = tmpBounds4
        val count = WriteNativeBridge.nativeSelectAll(nativeHandle, bounds)
        updateSelectionFromBounds(count, bounds)
    }

    fun clearSelection() {
        if (nativeHandle != 0L) {
            WriteNativeBridge.nativeClearSelection(nativeHandle)
        }
        selectionState = WriteSelectionState()
        syncOnyxRawDrawingState()
        onSelectionChanged?.invoke(selectionState)
        invalidate()
    }

    fun deleteSelectedStrokes() {
        if (nativeHandle == 0L || !selectionState.hasSelection) return
        val oldBounds = RectF(selectionState.bounds)
        val pad = max(penWidthPx, 12f) + 8f
        if (WriteNativeBridge.nativeDeleteSelection(nativeHandle)) {
            clearSelection()
            refreshDirtyRect(
                oldBounds.left - pad,
                oldBounds.top - pad,
                oldBounds.right + pad,
                oldBounds.bottom + pad,
                notifyState = true
            )
            onStrokeCommitted("<delete-selection/>", canvasWidthPx, canvasHeightPx, createDeferredSvgzExporter())
        }
    }

    fun duplicateSelectedStrokes() {
        if (nativeHandle == 0L || !selectionState.hasSelection) return
        val oldW = canvasWidthPx
        val oldH = canvasHeightPx
        val bounds = tmpBounds4
        val dims = tmpDims2
        val step = if (yRuling > 0) yRuling.toFloat() else 32f
        val count = WriteNativeBridge.nativeDuplicateSelection(nativeHandle, step, step, bounds, dims)
        if (count > 0) {
            if (dims[0] > canvasWidthPx) {
                canvasWidthPx = dims[0]
            }
            if (dims[1] > canvasHeightPx) {
                canvasHeightPx = dims[1]
                onHeightExpanded(canvasHeightPx)
            }
            val pad = max(penWidthPx, 12f) + 8f
            if (canvasWidthPx != oldW || canvasHeightPx != oldH) {
                expandBackingBitmapOrRefresh(
                    oldW,
                    oldH,
                    bounds[0] - pad,
                    bounds[1] - pad,
                    bounds[2] + pad,
                    bounds[3] + pad
                )
            } else {
                refreshDirtyRect(
                    bounds[0] - pad,
                    bounds[1] - pad,
                    bounds[2] + pad,
                    bounds[3] + pad,
                    notifyState = true
                )
            }
            updateSelectionFromBounds(count, bounds)
            onStrokeCommitted("<duplicate-selection/>", canvasWidthPx, canvasHeightPx, createDeferredSvgzExporter())
        }
    }

    fun scaleSelectedStrokes(scaleFactor: Float, scaleStrokeWidths: Boolean = true) {
        if (nativeHandle == 0L || !selectionState.hasSelection) return
        val oldBounds = RectF(selectionState.bounds)
        val oldW = canvasWidthPx
        val oldH = canvasHeightPx
        val bounds = tmpBounds4
        val dims = tmpDims2
        if (WriteNativeBridge.nativeScaleSelection(
                nativeHandle,
                scaleFactor,
                scaleFactor,
                oldBounds.centerX(),
                oldBounds.centerY(),
                scaleStrokeWidths,
                bounds,
                dims
            )
        ) {
            if (dims[0] > canvasWidthPx) canvasWidthPx = dims[0]
            if (dims[1] > canvasHeightPx) {
                canvasHeightPx = dims[1]
                onHeightExpanded(canvasHeightPx)
            }
            val pad = max(penWidthPx, 12f) + 8f
            val uLeft = min(oldBounds.left, bounds[0]) - pad
            val uTop = min(oldBounds.top, bounds[1]) - pad
            val uRight = max(oldBounds.right, bounds[2]) + pad
            val uBottom = max(oldBounds.bottom, bounds[3]) + pad
            if (canvasWidthPx != oldW || canvasHeightPx != oldH) {
                expandBackingBitmapOrRefresh(oldW, oldH, uLeft, uTop, uRight, uBottom)
            } else {
                refreshDirtyRect(uLeft, uTop, uRight, uBottom, notifyState = true)
            }
            updateSelectionFromBounds(selectionState.count, bounds)
            onStrokeCommitted("<scale-selection/>", canvasWidthPx, canvasHeightPx, createDeferredSvgzExporter())
        }
    }

    fun rotateSelectedStrokes(degrees: Float) {
        if (nativeHandle == 0L || !selectionState.hasSelection) return
        val oldBounds = RectF(selectionState.bounds)
        val oldW = canvasWidthPx
        val oldH = canvasHeightPx
        val rad = (degrees * PI / 180.0).toFloat()
        val bounds = tmpBounds4
        val dims = tmpDims2
        if (WriteNativeBridge.nativeRotateSelection(
                nativeHandle,
                rad,
                oldBounds.centerX(),
                oldBounds.centerY(),
                bounds,
                dims
            )
        ) {
            if (dims[0] > canvasWidthPx) canvasWidthPx = dims[0]
            if (dims[1] > canvasHeightPx) {
                canvasHeightPx = dims[1]
                onHeightExpanded(canvasHeightPx)
            }
            val pad = max(penWidthPx, 12f) + 8f
            val uLeft = min(oldBounds.left, bounds[0]) - pad
            val uTop = min(oldBounds.top, bounds[1]) - pad
            val uRight = max(oldBounds.right, bounds[2]) + pad
            val uBottom = max(oldBounds.bottom, bounds[3]) + pad
            if (canvasWidthPx != oldW || canvasHeightPx != oldH) {
                expandBackingBitmapOrRefresh(oldW, oldH, uLeft, uTop, uRight, uBottom)
            } else {
                refreshDirtyRect(uLeft, uTop, uRight, uBottom, notifyState = true)
            }
            updateSelectionFromBounds(selectionState.count, bounds)
            onStrokeCommitted("<rotate-selection/>", canvasWidthPx, canvasHeightPx, createDeferredSvgzExporter())
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

    fun hasClipboardStrokes(): Boolean {
        if (WriteNativeBridge.clipboardSvgXml.isNotBlank()) return true
        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
        val clip = cm?.primaryClip
        if (clip != null && clip.itemCount > 0 && clip.getItemAt(0).uri != null) {
            return true
        }
        return false
    }

    fun pasteClipboardStrokes(): Boolean {
        if (nativeHandle == 0L) return false
        if (WriteNativeBridge.clipboardSvgXml.isNotBlank()) {
            val oldW = canvasWidthPx
            val oldH = canvasHeightPx
            val bounds = tmpBounds4
            val dims = tmpDims2
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
                if (dims[0] > canvasWidthPx) canvasWidthPx = dims[0]
                if (dims[1] > canvasHeightPx) {
                    canvasHeightPx = dims[1]
                    onHeightExpanded(canvasHeightPx)
                }
                val pad = max(penWidthPx, 12f) + 8f
                if (canvasWidthPx != oldW || canvasHeightPx != oldH) {
                    expandBackingBitmapOrRefresh(
                        oldW,
                        oldH,
                        bounds[0] - pad,
                        bounds[1] - pad,
                        bounds[2] + pad,
                        bounds[3] + pad
                    )
                } else {
                    refreshDirtyRect(
                        bounds[0] - pad,
                        bounds[1] - pad,
                        bounds[2] + pad,
                        bounds[3] + pad,
                        notifyState = true
                    )
                }
                updateSelectionFromBounds(count, bounds)
                onStrokeCommitted("<paste-selection/>", canvasWidthPx, canvasHeightPx, createDeferredSvgzExporter())
                return true
            }
        }
        // Fallback: check Android system ClipboardManager for a copied image URI
        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
        val uri = cm?.primaryClip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.uri
        if (uri != null) {
            val bytes = runCatching {
                context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
            }.getOrNull()
            if (bytes != null && bytes.isNotEmpty()) {
                return insertImageBytes(bytes, asBackground = false)
            }
        }
        return false
    }

    fun recognizeSelectionOcr(): String {
        if (nativeHandle == 0L) return ""
        val svgFrag = if (selectionState.hasSelection) {
            WriteNativeBridge.nativeCopySelectionSvg(nativeHandle)
        } else {
            val bytes = WriteNativeBridge.nativeExportSvgz(nativeHandle) ?: return ""
            HandwritingOcrEngine.decompressSvgBytes(bytes)
        }
        return HandwritingOcrEngine.recognizeSvgFragment(svgFrag)
    }

    fun trimWhitespace(paddingPx: Int = 40): Boolean {
        if (nativeHandle == 0L) return false
        val dims = tmpDims2
        if (WriteNativeBridge.nativeTrimWhitespace(nativeHandle, paddingPx, dims)) {
            if (dims[1] > 0 && dims[1] != canvasHeightPx) {
                canvasHeightPx = dims[1]
                onHeightExpanded(canvasHeightPx)
            }
            refreshBackingBitmap()
            onStrokeCommitted("<trim-whitespace height=\"$canvasHeightPx\"/>", canvasWidthPx, canvasHeightPx, createDeferredSvgzExporter())
            return true
        }
        return false
    }

    fun setTemplateImageOpacity(opacity: Float): Boolean {
        if (nativeHandle == 0L) return false
        if (WriteNativeBridge.nativeSetTemplateImageOpacity(nativeHandle, opacity)) {
            refreshBackingBitmap()
            onStrokeCommitted("<template-opacity val=\"$opacity\"/>", canvasWidthPx, canvasHeightPx, createDeferredSvgzExporter())
            return true
        }
        return false
    }

    fun applyCurrentStyleToSelection() {
        if (nativeHandle == 0L || !selectionState.hasSelection) return
        val b = selectionState.bounds
        val pad = max(penWidthPx, 16f) + 8f
        if (WriteNativeBridge.nativeRestyleSelection(nativeHandle, penColorArgb, penWidthPx)) {
            refreshDirtyRect(b.left - pad, b.top - pad, b.right + pad, b.bottom + pad, notifyState = false)
            onStrokeCommitted("<restyle-selection/>", canvasWidthPx, canvasHeightPx, createDeferredSvgzExporter())
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
        val effectivePaperColor = if (OnyxEInkBridge.isEInkDevice && isArgbDark(newPaperColorArgb)) {
            0xFFFFFFFF.toInt()
        } else {
            newPaperColorArgb
        }
        if (WriteNativeBridge.nativeSetPageSetup(
                nativeHandle,
                newWidth,
                newHeight,
                newXRuling,
                newYRuling,
                newMarginLeft,
                effectivePaperColor,
                newRuleColorArgb,
                newRulingStyle
            )
        ) {
            refreshBackingBitmap()
            onHeightExpanded(canvasHeightPx)
            onStrokeCommitted("<page-setup/>", canvasWidthPx, canvasHeightPx, createDeferredSvgzExporter())
        }
    }

    fun lockCurrentDrawingAsTemplate(): Boolean {
        if (nativeHandle == 0L) return false
        if (WriteNativeBridge.nativeLockContentAsTemplate(nativeHandle)) {
            clearSelection()
            refreshBackingBitmap()
            onStrokeCommitted("<lock-template/>", canvasWidthPx, canvasHeightPx, createDeferredSvgzExporter())
            return true
        }
        return false
    }

    fun unlockTemplateBackgroundToEdit(): Boolean {
        if (nativeHandle == 0L) return false
        if (WriteNativeBridge.nativeUnlockTemplateToContent(nativeHandle)) {
            refreshBackingBitmap()
            onStrokeCommitted("<unlock-template/>", canvasWidthPx, canvasHeightPx, createDeferredSvgzExporter())
            return true
        }
        return false
    }

    fun clearCustomTemplateBackground(): Boolean {
        if (nativeHandle == 0L) return false
        if (WriteNativeBridge.nativeClearCustomTemplate(nativeHandle)) {
            refreshBackingBitmap()
            onStrokeCommitted("<clear-template/>", canvasWidthPx, canvasHeightPx, createDeferredSvgzExporter())
            return true
        }
        return false
    }

    fun insertImageBytes(imageBytes: ByteArray, asBackground: Boolean, matchImageDimensions: Boolean = false): Boolean {
        if (nativeHandle == 0L || imageBytes.isEmpty()) return false
        if (asBackground || matchImageDimensions) {
            val opts = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
            android.graphics.BitmapFactory.decodeByteArray(imageBytes, 0, imageBytes.size, opts)
            val imgW = opts.outWidth
            val imgH = opts.outHeight
            if (imgW > 0 && imgH > 0) {
                // If matchImageDimensions or high-res template image, adapt canvas size so resolution is 1:1
                val targetW = if (matchImageDimensions) imgW.coerceIn(600, 6000) else maxOf(canvasWidthPx, imgW.coerceAtMost(3840))
                val targetH = if (matchImageDimensions) imgH.coerceIn(400, 8000) else maxOf(canvasHeightPx, (imgH.toFloat() * (targetW.toFloat() / imgW)).toInt().coerceIn(400, 8000))
                if (targetW != canvasWidthPx || targetH != canvasHeightPx) {
                    resizeCanvasDimensions(targetW, targetH)
                }
            }
        }
        val bounds = tmpBounds4
        if (WriteNativeBridge.nativeInsertImageBytes(nativeHandle, imageBytes, asBackground, bounds)) {
            refreshBackingBitmap()
            if (!asBackground && bounds[2] > bounds[0]) {
                updateSelectionFromBounds(1, bounds)
            }
            onStrokeCommitted(
                "<insert-image bg=\"$asBackground\"/>",
                canvasWidthPx,
                canvasHeightPx,
                createDeferredSvgzExporter()
            )
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
        val dims = tmpDims2
        if (nativeHandle != 0L && WriteNativeBridge.nativeUndo(nativeHandle, dims)) {
            clearSelection()
            refreshBackingBitmap()
            if (dims[1] > 0 && dims[1] != canvasHeightPx) {
                canvasHeightPx = dims[1]
                onHeightExpanded(canvasHeightPx)
            }
            onStrokeCommitted("<undo/>", canvasWidthPx, canvasHeightPx, createDeferredSvgzExporter())
            return true
        } else if (fallbackPaths.isNotEmpty()) {
            fallbackPaths.removeLast()
            invalidate()
            return true
        }
        return false
    }

    fun performRedo(): Boolean {
        val dims = tmpDims2
        if (nativeHandle != 0L && WriteNativeBridge.nativeRedo(nativeHandle, dims)) {
            clearSelection()
            refreshBackingBitmap()
            if (dims[1] > 0 && dims[1] != canvasHeightPx) {
                canvasHeightPx = dims[1]
                onHeightExpanded(canvasHeightPx)
            }
            onStrokeCommitted("<redo/>", canvasWidthPx, canvasHeightPx, createDeferredSvgzExporter())
            return true
        }
        return false
    }

    fun resizeCanvasHeight(newHeightPx: Int) {
        resizeCanvasDimensions(canvasWidthPx, newHeightPx)
    }

    fun resizeCanvasDimensions(newWidthPx: Int, newHeightPx: Int) {
        val oldW = canvasWidthPx
        val oldH = canvasHeightPx
        val clampedW = newWidthPx.coerceIn(400, 6000)
        val clampedH = newHeightPx.coerceIn(300, 8000)
        if (clampedW == oldW && clampedH == oldH) return
        canvasWidthPx = clampedW
        canvasHeightPx = clampedH
        if (nativeHandle != 0L) {
            WriteNativeBridge.nativeResizeCanvas(nativeHandle, clampedW, clampedH)
            if (clampedW >= oldW && clampedH >= oldH) {
                expandBackingBitmapOrRefresh(oldW, oldH)
            } else {
                refreshBackingBitmap()
            }
        }
        onHeightExpanded(clampedH)
        onStrokeCommitted(
            "<resize width=\"$clampedW\" height=\"$clampedH\"/>",
            canvasWidthPx,
            canvasHeightPx,
            createDeferredSvgzExporter()
        )
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (isZoomedMode && oldw == 0 && w > 0) {
            zoomFitWidth()
        }
        onyxRawSession?.updateLimitRect(this)
        syncOnyxRawDrawingState()
    }

    override fun onHoverEvent(event: MotionEvent): Boolean {
        if (event.pointerCount > 0 && event.getToolType(0) == MotionEvent.TOOL_TYPE_STYLUS) {
            lastStylusActivityMs = event.eventTime
        }
        if (event.actionMasked == MotionEvent.ACTION_HOVER_ENTER) {
            onCanvasActivated?.invoke()
        }
        return super.onHoverEvent(event)
    }

    private fun hitTestSelectionTransform(cx: Float, cy: Float): SelectionTransformInteraction {
        if (!selectionState.hasSelection) return SelectionTransformInteraction.NONE
        val b = tmpHitRect
        b.set(selectionState.bounds)
        b.inset(-6f, -6f)
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
        if (hypot(cx - b.left, cy - b.top) <= hitRadius) {
            selPivotX = b.right
            selPivotY = b.bottom
            return SelectionTransformInteraction.SCALE_CORNER
        }
        if (hypot(cx - b.right, cy - b.top) <= hitRadius) {
            selPivotX = b.left
            selPivotY = b.bottom
            return SelectionTransformInteraction.SCALE_CORNER
        }
        if (hypot(cx - b.left, cy - b.bottom) <= hitRadius) {
            selPivotX = b.right
            selPivotY = b.top
            return SelectionTransformInteraction.SCALE_CORNER
        }
        if (hypot(cx - b.right, cy - b.bottom) <= hitRadius) {
            selPivotX = b.left
            selPivotY = b.top
            return SelectionTransformInteraction.SCALE_CORNER
        }

        // 3. Check inside selection box for translation
        b.inset(-14f, -14f)
        if (b.contains(cx, cy)) {
            return SelectionTransformInteraction.TRANSLATE
        }
        return SelectionTransformInteraction.NONE
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (event.actionMasked == MotionEvent.ACTION_DOWN) {
            onCanvasActivated?.invoke()
            multiFingerMaxPointers = 1
        }

        // Identify stylus / eraser pointers vs finger pointers
        var stylusPointerIndex = -1
        var fingerCount = 0
        for (i in 0 until event.pointerCount) {
            val t = event.getToolType(i)
            if (t == MotionEvent.TOOL_TYPE_STYLUS || t == MotionEvent.TOOL_TYPE_ERASER) {
                if (stylusPointerIndex < 0) {
                    stylusPointerIndex = i
                }
            } else if (t == MotionEvent.TOOL_TYPE_FINGER) {
                fingerCount++
            }
        }
        val hasStylus = stylusPointerIndex >= 0

        if (hasStylus) {
            lastStylusActivityMs = event.eventTime
        }

        // Multi-finger tap detection (2-finger tap = Undo, 3-finger tap = Redo)
        // Multi-finger pinch zoom and two-finger pan ONLY when NO stylus is touching
        if (!hasStylus) {
            if (event.pointerCount > 1) {
                if (event.actionMasked == MotionEvent.ACTION_POINTER_DOWN) {
                    if (multiFingerMaxPointers < 2) {
                        multiFingerTapStartTime = System.currentTimeMillis()
                    }
                    multiFingerMaxPointers = max(multiFingerMaxPointers, event.pointerCount)
                } else if (event.actionMasked == MotionEvent.ACTION_POINTER_UP && event.pointerCount == 2) {
                    val duration = System.currentTimeMillis() - multiFingerTapStartTime
                    if (duration in 25..320) {
                        if (multiFingerMaxPointers >= 3) {
                            performRedo()
                        } else {
                            performUndo()
                        }
                        multiFingerMaxPointers = 0
                    }
                }
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
                        } else {
                            OnyxEInkBridge.enterFastPanMode(this)
                        }
                        isPanningGesture = true
                        panLastTouchX = avgX
                        panLastTouchY = avgY
                    } else if (event.actionMasked == MotionEvent.ACTION_UP ||
                        event.actionMasked == MotionEvent.ACTION_POINTER_UP ||
                        event.actionMasked == MotionEvent.ACTION_CANCEL
                    ) {
                        if (isPanningGesture) {
                            isPanningGesture = false
                            syncOnyxRawDrawingState()
                            OnyxEInkBridge.exitFastPanModeAndRefresh(this)
                        }
                    }
                    return true
                }
            } else if (event.pointerCount > 1) {
                return true
            }
        } else if (event.pointerCount > 1) {
            // When a stylus is active, ignore secondary finger/palm touch-down or touch-up events
            if (event.actionMasked == MotionEvent.ACTION_POINTER_DOWN && event.actionIndex != stylusPointerIndex) {
                return true
            }
            if (event.actionMasked == MotionEvent.ACTION_POINTER_UP && event.actionIndex != stylusPointerIndex) {
                return true
            }
        }

        val targetPointerIndex = if (hasStylus) stylusPointerIndex else 0

        val action = when (event.actionMasked) {
            MotionEvent.ACTION_BUTTON_PRESS -> if (event.pressure > 0f) MotionEvent.ACTION_DOWN else event.actionMasked
            MotionEvent.ACTION_BUTTON_RELEASE -> MotionEvent.ACTION_UP
            MotionEvent.ACTION_POINTER_DOWN -> if (hasStylus && event.actionIndex == stylusPointerIndex) MotionEvent.ACTION_DOWN else event.actionMasked
            MotionEvent.ACTION_POINTER_UP -> if (hasStylus && event.actionIndex == stylusPointerIndex) MotionEvent.ACTION_UP else event.actionMasked
            else -> event.actionMasked
        }

        val toolType = event.getToolType(targetPointerIndex)
        val buttonState = event.buttonState
        val isStylusButton = (buttonState and (
            MotionEvent.BUTTON_STYLUS_PRIMARY or
            MotionEvent.BUTTON_SECONDARY or
            MotionEvent.BUTTON_STYLUS_SECONDARY or
            MotionEvent.BUTTON_TERTIARY
        )) != 0
        val isHardwareLasso = isStylusButton && (
            barrelButtonLassoMode ||
                ((buttonState and MotionEvent.BUTTON_STYLUS_SECONDARY) != 0 &&
                 (buttonState and MotionEvent.BUTTON_STYLUS_PRIMARY) == 0)
        )
        val isHardwareEraser = toolType == MotionEvent.TOOL_TYPE_ERASER || (isStylusButton && !isHardwareLasso)

        val isStylusOrMouse = toolType == MotionEvent.TOOL_TYPE_STYLUS ||
            toolType == MotionEvent.TOOL_TYPE_ERASER ||
            toolType == MotionEvent.TOOL_TYPE_MOUSE ||
            isStylusButton

        if (action == MotionEvent.ACTION_DOWN && isStylusOrMouse) {
            requestUnbufferedDispatch(event)
        }

        // If the user clicks the stylus eraser button mid-stroke, cancel/commit drawing and switch to eraser immediately
        if (isHardwareEraser && activeLiveStrokePath != null) {
            activeLiveStrokePath = null
            predictedTipPath.reset()
            removeCallbacks(holdSnapRunnable)
            holdSnapTriggered = false
            val dims = tmpDimsAndDirty6
            dims.fill(0)
            val pathXml = if (nativeHandle != 0L) {
                WriteNativeBridge.nativeEndStroke(nativeHandle, dims)
            } else {
                "<path class=\"ug-path\" />"
            }
            onStrokeCommitted(pathXml, canvasWidthPx, canvasHeightPx, createDeferredSvgzExporter())
            isHardwareErasingActive = true
        }

        if (isHardwareEraser) {
            isPanningGesture = false
        }

        if (toolType == MotionEvent.TOOL_TYPE_STYLUS || toolType == MotionEvent.TOOL_TYPE_ERASER) {
            lastStylusActivityMs = event.eventTime
        }

        // B1: palm rejection -- ignore finger touches while the pen is hovering/touching,
        // and (optionally) ignore finger touches entirely for drawing in stylus-only mode.
        if (toolType == MotionEvent.TOOL_TYPE_FINGER) {
            val nearPen = event.eventTime - lastStylusActivityMs < palmRejectionWindowMs
            val blockedByStylusOnly = stylusOnlyMode && isZoomedMode && !singleTouchPanInZoomedMode
            if ((palmRejectionEnabled && nearPen) || blockedByStylusOnly) {
                parent?.requestDisallowInterceptTouchEvent(isZoomedMode)
                return isZoomedMode
            }
        }

        // In Inline Mode, single-finger touch passes straight to the parent LazyColumn for scrolling
        if (!isZoomedMode && !isStylusOrMouse) {
            parent?.requestDisallowInterceptTouchEvent(false)
            return false
        }

        parent?.requestDisallowInterceptTouchEvent(true)

        val touchX = event.getX(targetPointerIndex)
        val touchY = event.getY(targetPointerIndex)

        val shouldPanViewport = isZoomedMode && !isHardwareEraser && (
            toolMode == StylusToolMode.PAN ||
                (!isStylusOrMouse && singleTouchPanInZoomedMode)
            )
        if (shouldPanViewport) {
            when (action) {
                MotionEvent.ACTION_DOWN -> {
                    isPanningGesture = true
                    panLastTouchX = touchX
                    panLastTouchY = touchY
                    OnyxEInkBridge.enterFastPanMode(this)
                    return true
                }
                MotionEvent.ACTION_MOVE -> {
                    if (isPanningGesture) {
                        panOffsetX += (touchX - panLastTouchX)
                        panOffsetY += (touchY - panLastTouchY)
                        panLastTouchX = touchX
                        panLastTouchY = touchY
                        invalidate()
                    }
                    return true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    if (isPanningGesture) {
                        isPanningGesture = false
                        syncOnyxRawDrawingState()
                        OnyxEInkBridge.exitFastPanModeAndRefresh(this)
                    }
                    return true
                }
            }
            return true
        }

        val cx = screenToCanvasX(touchX)
        val cy = screenToCanvasY(touchY)
        val pressure = StrokeTuning.curve(event.pressure)
        val tiltX = StrokeTuning.tilt(event.getAxisValue(MotionEvent.AXIS_TILT))
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
                        val oldBounds = RectF(selectionState.bounds)
                        val oldW = canvasWidthPx
                        val oldH = canvasHeightPx
                        val bounds = tmpBounds4
                        val dims = tmpDims2
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
                            val pad = max(penWidthPx, 12f) + 8f
                            val uLeft = min(oldBounds.left, bounds[0]) - pad
                            val uTop = min(oldBounds.top, bounds[1]) - pad
                            val uRight = max(oldBounds.right, bounds[2]) + pad
                            val uBottom = max(oldBounds.bottom, bounds[3]) + pad
                            if (canvasWidthPx != oldW || canvasHeightPx != oldH) {
                                expandBackingBitmapOrRefresh(oldW, oldH, uLeft, uTop, uRight, uBottom)
                            } else {
                                refreshDirtyRect(uLeft, uTop, uRight, uBottom, notifyState = true)
                            }
                            updateSelectionFromBounds(selectionState.count, bounds)
                            onStrokeCommitted("<transform-selection/>", canvasWidthPx, canvasHeightPx, createDeferredSvgzExporter())
                        } else {
                            invalidate()
                        }
                        return true
                    }
                }
            }
        }

        // 1.5. Check hardware eraser button release transition:
        val isHardwareButtonRelease = event.actionMasked == MotionEvent.ACTION_BUTTON_RELEASE ||
            (isHardwareErasingActive && !isHardwareEraser)
        if (isHardwareButtonRelease && isHardwareErasingActive) {
            isHardwareErasingActive = false
            activeEraserCursor = null
            freeEraseLastX = 0f
            freeEraseLastY = 0f
            invalidate()
            if (anyErasedInGesture) {
                anyErasedInGesture = false
                notifyViewportUpdated()
                onStrokeCommitted("<erase/>", canvasWidthPx, canvasHeightPx, createDeferredSvgzExporter())
            }
            // If stylus is still in contact with the screen, immediately start drawing in pen mode
            if (event.pressure > 0.01f && (toolMode == StylusToolMode.PEN || toolMode == StylusToolMode.HIGHLIGHTER)) {
                val snappedX = snapX(cx)
                val snappedY = snapY(cy)
                liveStrokeStartX = snappedX
                liveStrokeStartY = snappedY
                liveStrokeLastX = snappedX
                liveStrokeLastY = snappedY
                liveStrokeLastTimeMs = ts
                liveStrokeVelX = 0f
                liveStrokeVelY = 0f
                holdSnapLastX = snappedX
                holdSnapLastY = snappedY
                holdSnapTriggered = false
                removeCallbacks(holdSnapRunnable)
                reusableLiveStrokePath.reset()
                reusableLiveStrokePath.moveTo(snappedX, snappedY)
                predictedTipPath.reset()
                activeLiveStrokePath = reusableLiveStrokePath
                strokeSmoother.reset(snappedX, snappedY)
                if (nativeHandle != 0L) {
                    WriteNativeBridge.nativeBeginStroke(nativeHandle, snappedX, snappedY, pressure, tiltX, 0f, ts)
                }
                invalidate()
                return true
            }
        }

        // 2. Check S-Pen tail eraser / side button or Eraser modes (Stroke, Ruled, or Free Segment Eraser)
        val isErasingNow = isHardwareEraser ||
            isHardwareErasingActive ||
            toolMode == StylusToolMode.ERASER ||
            toolMode == StylusToolMode.ERASER_RULED ||
            toolMode == StylusToolMode.ERASER_FREE

        if (isErasingNow) {
            if (isHardwareEraser) {
                isHardwareErasingActive = true
            }
            val effectiveMode = if ((isHardwareEraser || isHardwareErasingActive) &&
                toolMode != StylusToolMode.ERASER_RULED &&
                toolMode != StylusToolMode.ERASER_FREE
            ) {
                StylusToolMode.ERASER
            } else {
                toolMode
            }
            val eraseModeCode = when (effectiveMode) {
                StylusToolMode.ERASER_RULED -> 1
                StylusToolMode.ERASER_FREE -> 2
                else -> 0
            }
            val eraseRadius = if (effectiveMode == StylusToolMode.ERASER_FREE) 18f else 22f

            when (action) {
                MotionEvent.ACTION_DOWN -> {
                    freeEraseLastX = cx
                    freeEraseLastY = cy
                    activeEraserCursor = Triple(cx, cy, eraseRadius)
                    anyErasedInGesture = false
                    if (nativeHandle != 0L) {
                        val erased = WriteNativeBridge.nativeEraseSegmentDirty(
                            nativeHandle,
                            cx,
                            cy,
                            cx,
                            cy,
                            eraseRadius,
                            eraseModeCode,
                            tmpDirty4
                        )
                        if (erased) {
                            anyErasedInGesture = true
                            refreshDirtyRect(
                                tmpDirty4[0],
                                tmpDirty4[1],
                                tmpDirty4[2],
                                tmpDirty4[3],
                                notifyState = false
                            )
                        } else {
                            invalidate()
                        }
                    } else {
                        invalidate()
                    }
                    return true
                }
                MotionEvent.ACTION_MOVE -> {
                    if (freeEraseLastX == 0f && freeEraseLastY == 0f) {
                        freeEraseLastX = cx
                        freeEraseLastY = cy
                    }
                    activeEraserCursor = Triple(cx, cy, eraseRadius)
                    if (nativeHandle != 0L) {
                        var batchErased = false
                        var dLeft = 1e9f
                        var dTop = 1e9f
                        var dRight = -1e9f
                        var dBottom = -1e9f

                        val histSize = event.historySize
                        for (h in 0 until histSize) {
                            val hx = screenToCanvasX(event.getHistoricalX(h))
                            val hy = screenToCanvasY(event.getHistoricalY(h))
                            if (WriteNativeBridge.nativeEraseSegmentDirty(
                                    nativeHandle,
                                    freeEraseLastX,
                                    freeEraseLastY,
                                    hx,
                                    hy,
                                    eraseRadius,
                                    eraseModeCode,
                                    tmpDirty4
                                )
                            ) {
                                batchErased = true
                                dLeft = min(dLeft, tmpDirty4[0])
                                dTop = min(dTop, tmpDirty4[1])
                                dRight = max(dRight, tmpDirty4[2])
                                dBottom = max(dBottom, tmpDirty4[3])
                            }
                            freeEraseLastX = hx
                            freeEraseLastY = hy
                        }

                        if (WriteNativeBridge.nativeEraseSegmentDirty(
                                nativeHandle,
                                freeEraseLastX,
                                freeEraseLastY,
                                cx,
                                cy,
                                eraseRadius,
                                eraseModeCode,
                                tmpDirty4
                            )
                        ) {
                            batchErased = true
                            dLeft = min(dLeft, tmpDirty4[0])
                            dTop = min(dTop, tmpDirty4[1])
                            dRight = max(dRight, tmpDirty4[2])
                            dBottom = max(dBottom, tmpDirty4[3])
                        }
                        freeEraseLastX = cx
                        freeEraseLastY = cy

                        if (batchErased) {
                            anyErasedInGesture = true
                            refreshDirtyRect(dLeft, dTop, dRight, dBottom, notifyState = false)
                        } else {
                            invalidate()
                        }
                    } else {
                        freeEraseLastX = cx
                        freeEraseLastY = cy
                        invalidate()
                    }
                    return true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    isHardwareErasingActive = false
                    activeEraserCursor = null
                    freeEraseLastX = 0f
                    freeEraseLastY = 0f
                    invalidate()
                    if (anyErasedInGesture) {
                        anyErasedInGesture = false
                        notifyViewportUpdated()
                        OnyxEInkBridge.requestCleanRefresh(this)
                        onStrokeCommitted("<erase/>", canvasWidthPx, canvasHeightPx, createDeferredSvgzExporter())
                    }
                    return true
                }
            }
            return true
        }

        // 3. Selection Tools (Rect, Lasso, Ruled, or S-Pen Barrel Button Lasso)
        if (isHardwareLasso ||
            toolMode == StylusToolMode.SELECT_RECT ||
            toolMode == StylusToolMode.SELECT_RULED ||
            toolMode == StylusToolMode.SELECT_LASSO
        ) {
            val effectiveSelMode = if (isHardwareLasso) StylusToolMode.SELECT_LASSO else toolMode
            when (action) {
                MotionEvent.ACTION_DOWN -> {
                    clearSelection()
                    if (effectiveSelMode == StylusToolMode.SELECT_LASSO) {
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
                    if (effectiveSelMode == StylusToolMode.SELECT_LASSO) {
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
                    val bounds = tmpBounds4
                    var count = 0
                    if (nativeHandle != 0L) {
                        when (effectiveSelMode) {
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
                        val dims = tmpDims2
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
                            onStrokeCommitted("<insert-space/>", canvasWidthPx, canvasHeightPx, createDeferredSvgzExporter())
                        }
                    }
                    invalidate()
                    return true
                }
            }
            return true
        }

        // 4b. Ephemeral Laser Pointer Mode (fading red glow trail over 1.5s, never modifies .svgz)
        if (toolMode == StylusToolMode.LASER) {
            val now = System.currentTimeMillis()
            when (action) {
                MotionEvent.ACTION_DOWN -> {
                    if (OnyxEInkBridge.isEInkDevice) {
                        laserTrailPoints.clear()
                        OnyxEInkBridge.enterFastPanMode(this)
                    }
                    laserTrailPoints.add(LaserPt(cx, cy, now, newSegment = true))
                    onLaserPoint?.invoke(cx, cy)
                    if (OnyxEInkBridge.isEInkDevice) invalidate() else postInvalidateOnAnimation()
                    return true
                }
                MotionEvent.ACTION_MOVE -> {
                    laserTrailPoints.add(LaserPt(cx, cy, now, newSegment = false))
                    onLaserPoint?.invoke(cx, cy)
                    if (OnyxEInkBridge.isEInkDevice) invalidate() else postInvalidateOnAnimation()
                    return true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    if (OnyxEInkBridge.isEInkDevice) {
                        laserTrailPoints.clear()
                        OnyxEInkBridge.exitFastPanModeAndRefresh(this)
                    } else {
                        postInvalidateOnAnimation()
                    }
                    return true
                }
            }
            return true
        }

        // 5. Standard Pen / Highlighter / Line-Drawing / Grid-Snapped / Hold-to-Snap Drawing
        // If Onyx TouchHelper direct-to-EPD raw drawing is actively handling the stylus stroke,
        // skip duplicate recording here.
        if (isOnyxRawStrokeInProgress) {
            return true
        }
        val snappedX = snapX(cx)
        val snappedY = snapY(cy)
        when (action) {
            MotionEvent.ACTION_DOWN -> {
                OnyxEInkBridge.enterFastPanMode(this)
                liveStrokeStartX = snappedX
                liveStrokeStartY = snappedY
                liveStrokeLastX = snappedX
                liveStrokeLastY = snappedY
                liveStrokeLastTimeMs = ts
                liveStrokeVelX = 0f
                liveStrokeVelY = 0f
                holdSnapLastX = snappedX
                holdSnapLastY = snappedY
                holdSnapTriggered = false
                removeCallbacks(holdSnapRunnable)
                strokeSmoother.reset(snappedX, snappedY)
                reusableLiveStrokePath.reset()
                reusableLiveStrokePath.moveTo(snappedX, snappedY)
                predictedTipPath.reset()
                activeLiveStrokePath = reusableLiveStrokePath
                if (nativeHandle != 0L) {
                    WriteNativeBridge.nativeBeginStroke(nativeHandle, snappedX, snappedY, pressure, tiltX, 0f, ts)
                }
                invalidate()
                return true
            }

            MotionEvent.ACTION_MOVE -> {
                if (lineDrawing) {
                    reusableLiveStrokePath.reset()
                    reusableLiveStrokePath.moveTo(liveStrokeStartX, liveStrokeStartY)
                    reusableLiveStrokePath.lineTo(snappedX, snappedY)
                    predictedTipPath.reset()
                    activeLiveStrokePath = reusableLiveStrokePath
                    if (nativeHandle != 0L) {
                        WriteNativeBridge.nativeMoveStroke(nativeHandle, snappedX, snappedY, pressure, tiltX, 0f, ts)
                    }
                } else if (!holdSnapTriggered) {
                    val histSize = event.historySize
                    val totalPts = histSize + 1
                    if (strokeBatchBuf.size < totalPts * 6) {
                        strokeBatchBuf = FloatArray(max(totalPts * 6, strokeBatchBuf.size * 2))
                    }
                    val buf = strokeBatchBuf

                    for (h in 0 until histSize) {
                        val rawHx = snapX(screenToCanvasX(event.getHistoricalX(h)))
                        val rawHy = snapY(screenToCanvasY(event.getHistoricalY(h)))
                        val (hx, hy) = strokeSmoother.apply(rawHx, rawHy)
                        val hp = StrokeTuning.curve(event.getHistoricalPressure(h))
                        val hts = event.getHistoricalEventTime(h).toDouble()

                        val midX = (liveStrokeLastX + hx) * 0.5f
                        val midY = (liveStrokeLastY + hy) * 0.5f
                        reusableLiveStrokePath.quadTo(liveStrokeLastX, liveStrokeLastY, midX, midY)

                        if (hts > liveStrokeLastTimeMs) {
                            val dt = (hts - liveStrokeLastTimeMs).toFloat().coerceAtLeast(1f)
                            liveStrokeVelX = liveStrokeVelX * 0.35f + ((hx - liveStrokeLastX) / dt) * 0.65f
                            liveStrokeVelY = liveStrokeVelY * 0.35f + ((hy - liveStrokeLastY) / dt) * 0.65f
                            liveStrokeLastTimeMs = hts
                        }
                        liveStrokeLastX = hx
                        liveStrokeLastY = hy

                        val base = h * 6
                        buf[base] = hx
                        buf[base + 1] = hy
                        buf[base + 2] = hp
                        buf[base + 3] = tiltX
                        buf[base + 4] = 0f
                        buf[base + 5] = hts.toFloat()
                    }

                    val (smoothX, smoothY) = strokeSmoother.apply(snappedX, snappedY)
                    val midX = (liveStrokeLastX + smoothX) * 0.5f
                    val midY = (liveStrokeLastY + smoothY) * 0.5f
                    reusableLiveStrokePath.quadTo(liveStrokeLastX, liveStrokeLastY, midX, midY)

                    if (ts > liveStrokeLastTimeMs) {
                        val dt = (ts - liveStrokeLastTimeMs).toFloat().coerceAtLeast(1f)
                        liveStrokeVelX = liveStrokeVelX * 0.35f + ((smoothX - liveStrokeLastX) / dt) * 0.65f
                        liveStrokeVelY = liveStrokeVelY * 0.35f + ((smoothY - liveStrokeLastY) / dt) * 0.65f
                        liveStrokeLastTimeMs = ts
                    }
                    liveStrokeLastX = smoothX
                    liveStrokeLastY = smoothY

                    // Short velocity-extrapolated tip prediction (~8ms lookahead, clamped to 18px)
                    predictedTipPath.reset()
                    predictedTipPath.moveTo(midX, midY)
                    val rawDx = liveStrokeVelX * 8f
                    val rawDy = liveStrokeVelY * 8f
                    val dist = hypot(rawDx, rawDy)
                    val clampScale = if (dist > 18f) 18f / dist else 1f
                    predictedTipPath.quadTo(
                        smoothX,
                        smoothY,
                        smoothX + rawDx * clampScale,
                        smoothY + rawDy * clampScale
                    )

                    val lastBase = histSize * 6
                    buf[lastBase] = smoothX
                    buf[lastBase + 1] = smoothY
                    buf[lastBase + 2] = pressure
                    buf[lastBase + 3] = tiltX
                    buf[lastBase + 4] = 0f
                    buf[lastBase + 5] = ts.toFloat()

                    if (nativeHandle != 0L) {
                        WriteNativeBridge.nativeMoveStrokeBatch(nativeHandle, buf, totalPts)
                    }

                    // Hold-to-Snap geometric shape timer (400ms steady hold at end of stroke)
                    if (toolMode == StylusToolMode.PEN && hypot(snappedX - holdSnapLastX, snappedY - holdSnapLastY) > 6.5f) {
                        holdSnapLastX = snappedX
                        holdSnapLastY = snappedY
                        removeCallbacks(holdSnapRunnable)
                        postDelayed(holdSnapRunnable, 400L)
                    }
                }
                invalidate()
                return true
            }

            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                isHardwareErasingActive = false
                removeCallbacks(holdSnapRunnable)
                holdSnapTriggered = false
                activeLiveStrokePath?.let { finished ->
                    if (nativeHandle == 0L) {
                        fallbackPaths.add(Path(finished) to Paint(currentLiveStrokePaint))
                    }
                }
                activeLiveStrokePath = null
                predictedTipPath.reset()
                finalizeActiveNativeStroke()
                OnyxEInkBridge.exitFastPanModeAndRefresh(this)
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    private fun finalizeActiveNativeStroke() {
        val dims = tmpDimsAndDirty6
        dims.fill(0)
        val pathXml = if (nativeHandle != 0L) {
            WriteNativeBridge.nativeEndStroke(nativeHandle, dims)
        } else {
            "<path class=\"ug-path\" />"
        }

        val oldW = canvasWidthPx
        val oldH = canvasHeightPx
        var sizeExpanded = false
        if (dims[0] > canvasWidthPx) {
            canvasWidthPx = dims[0]
            sizeExpanded = true
        }
        val newHeight = if (dims[1] > 0) dims[1] else canvasHeightPx
        if (newHeight > canvasHeightPx) {
            canvasHeightPx = newHeight
            sizeExpanded = true
            onHeightExpanded(newHeight)
        }

        if (nativeHandle != 0L) {
            if (!sizeExpanded && dims[4] >= dims[2] && dims[5] >= dims[3]) {
                refreshDirtyRect(
                    dims[2].toFloat(),
                    dims[3].toFloat(),
                    dims[4].toFloat(),
                    dims[5].toFloat(),
                    notifyState = true
                )
            } else if (sizeExpanded && dims[4] >= dims[2] && dims[5] >= dims[3]) {
                expandBackingBitmapOrRefresh(
                    oldW,
                    oldH,
                    dims[2].toFloat(),
                    dims[3].toFloat(),
                    dims[4].toFloat(),
                    dims[5].toFloat()
                )
            } else {
                refreshBackingBitmap()
            }
        } else {
            invalidate()
        }

        onStrokeCommitted(pathXml, canvasWidthPx, canvasHeightPx, createDeferredSvgzExporter())
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
            val drawBmp = if (isZoomedMode && zoomScale <= 0.6f) {
                ensureOverviewBitmapIfNeeded() ?: bmp
            } else {
                bmp
            }
            if (drawBmp.width == canvasWidthPx && drawBmp.height == canvasHeightPx) {
                canvas.drawBitmap(drawBmp, 0f, 0f, bitmapPaint)
            } else {
                dstPageRect.set(0f, 0f, canvasWidthPx.toFloat(), canvasHeightPx.toFloat())
                canvas.drawBitmap(drawBmp, null, dstPageRect, bitmapPaint)
            }
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

        // Active live stroke preview + velocity-predicted tip
        activeLiveStrokePath?.let {
            canvas.drawPath(it, currentLiveStrokePaint)
            if (!predictedTipPath.isEmpty) {
                canvas.drawPath(predictedTipPath, currentLiveStrokePaint)
            }
        }

        // Ephemeral Laser Pointer trail (fades out over 1500ms on LCD; solid crisp line on E-Ink)
        if (laserTrailPoints.isNotEmpty()) {
            if (OnyxEInkBridge.isEInkDevice) {
                laserCorePaint.alpha = 255
                for (i in 1 until laserTrailPoints.size) {
                    val p0 = laserTrailPoints[i - 1]
                    val p1 = laserTrailPoints[i]
                    if (p1.newSegment) continue
                    canvas.drawLine(p0.x, p0.y, p1.x, p1.y, laserCorePaint)
                }
                val tip = laserTrailPoints.last()
                canvas.drawCircle(tip.x, tip.y, 4.5f, laserCorePaint)
            } else {
                val now = System.currentTimeMillis()
                laserTrailPoints.removeAll { now - it.timeMs > 1500L }
                if (laserTrailPoints.isNotEmpty()) {
                    for (i in 1 until laserTrailPoints.size) {
                        val p0 = laserTrailPoints[i - 1]
                        val p1 = laserTrailPoints[i]
                        if (p1.newSegment) continue
                        val age = (now - p1.timeMs).coerceIn(0L, 1500L)
                        val alphaFrac = 1f - (age / 1500f)
                        laserGlowPaint.alpha = (alphaFrac * 110).toInt().coerceIn(0, 255)
                        laserCorePaint.alpha = (alphaFrac * 255).toInt().coerceIn(0, 255)
                        canvas.drawLine(p0.x, p0.y, p1.x, p1.y, laserGlowPaint)
                        canvas.drawLine(p0.x, p0.y, p1.x, p1.y, laserCorePaint)
                    }
                    val tip = laserTrailPoints.last()
                    if (now - tip.timeMs < 400L) {
                        laserGlowPaint.alpha = 160
                        laserCorePaint.alpha = 255
                        canvas.drawCircle(tip.x, tip.y, 9f, laserGlowPaint)
                        canvas.drawCircle(tip.x, tip.y, 4.5f, laserCorePaint)
                    }
                    postInvalidateOnAnimation()
                }
            }
        }

        // Active eraser ring cursor preview
        activeEraserCursor?.let { (ex, ey, er) ->
            canvas.drawCircle(ex, ey, er, selectionFillPaint)
            canvas.drawCircle(ex, ey, er, selectionBorderPaint)
        }

        // Active Selection Bounding Box + 4 Scale Corner Handles + Top Rotation Handle
        if (selectionState.hasSelection) {
            canvas.save()
            val b = tmpDrawRect
            b.set(selectionState.bounds)
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

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        synchronized(nativeLock) {
            if (nativeHandle == 0L && WriteNativeBridge.isNativeLoaded) {
                nativeHandle = WriteNativeBridge.nativeCreateSession(
                    canvasWidthPx, canvasHeightPx, xRuling, yRuling
                )
                applyEInkPaperDefaultsIfNeeded()
                WriteNativeBridge.nativeSetRenderTheme(nativeHandle, isDarkTheme, darkBgColorArgb, darkFgColorArgb)
                WriteNativeBridge.nativeSetPen(
                    nativeHandle,
                    penColorArgb,
                    penWidthPx,
                    pressureSensitive && toolMode == StylusToolMode.PEN,
                    if (toolMode == StylusToolMode.HIGHLIGHTER) WritePenTip.FLAT.nativeCode else penTip.nativeCode,
                    snapToGrid,
                    lineDrawing,
                    penChiselAngle
                )
                val savedB64 = cachedDetachedSvgzBase64
                if (!savedB64.isNullOrEmpty()) {
                    val bytes = Base64.decode(savedB64, Base64.DEFAULT)
                    WriteNativeBridge.nativeLoadSvgz(nativeHandle, bytes)
                    applyEInkPaperDefaultsIfNeeded()
                }
                refreshBackingBitmap()
            }
        }
        if (OnyxEInkBridge.isEInkDevice && onyxRawSession == null) {
            onyxRawSession = OnyxEInkBridge.OnyxRawTouchSession.createIfSupported(this, onyxRawStrokeListener)
            syncOnyxRawDrawingState()
        }
    }

    override fun onDetachedFromWindow() {
        isOnyxRawStrokeInProgress = false
        onyxRawSession?.close()
        onyxRawSession = null
        super.onDetachedFromWindow()
        synchronized(nativeLock) {
            if (adoptedPeer?.adoptedPeer === this) {
                adoptedPeer?.adoptedPeer = null
            }
            if (nativeHandle != 0L) {
                WriteNativeBridge.nativeDestroySession(nativeHandle)
                nativeHandle = 0L
            }
        }
        backingBitmap?.recycle()
        backingBitmap = null
        overviewBitmap?.recycle()
        overviewBitmap = null
        overviewCanvas = null
    }
}
