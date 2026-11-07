@file:Suppress("ktlint")

package android.view

import android.content.Context
import android.graphics.Canvas
import android.graphics.Rect
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.compose.runtime.mutableIntStateOf
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.math.hypot

class KeyEvent(
    val action: Int,
    val keyCode: Int,
    val isCtrlPressed: Boolean = false,
    val isShiftPressed: Boolean = false,
    val isAltPressed: Boolean = false,
    val isMetaPressed: Boolean = false,
    val eventTime: Long = SystemClock.uptimeMillis()
) {
    companion object {
        const val ACTION_DOWN = 0
        const val ACTION_UP = 1

        const val KEYCODE_0 = 7
        const val KEYCODE_1 = 8
        const val KEYCODE_2 = 9
        const val KEYCODE_3 = 10
        const val KEYCODE_4 = 11
        const val KEYCODE_5 = 12
        const val KEYCODE_DPAD_LEFT = 21
        const val KEYCODE_DPAD_RIGHT = 22
        const val KEYCODE_A = 29
        const val KEYCODE_C = 31
        const val KEYCODE_E = 33
        const val KEYCODE_F = 34
        const val KEYCODE_K = 39
        const val KEYCODE_L = 40
        const val KEYCODE_M = 41
        const val KEYCODE_N = 42
        const val KEYCODE_P = 44
        const val KEYCODE_Q = 45
        const val KEYCODE_T = 48
        const val KEYCODE_V = 50
        const val KEYCODE_W = 51
        const val KEYCODE_X = 52
        const val KEYCODE_Y = 53
        const val KEYCODE_Z = 54
        const val KEYCODE_ALT_LEFT = 57
        const val KEYCODE_ALT_RIGHT = 58
        const val KEYCODE_SHIFT_LEFT = 59
        const val KEYCODE_SHIFT_RIGHT = 60
        const val KEYCODE_SPACE = 62
        const val KEYCODE_DEL = 67
        const val KEYCODE_MINUS = 69
        const val KEYCODE_EQUALS = 70
        const val KEYCODE_BACKSLASH = 73
        const val KEYCODE_PLUS = 81
        const val KEYCODE_ESCAPE = 111
        const val KEYCODE_FORWARD_DEL = 112
        const val KEYCODE_CTRL_LEFT = 113
        const val KEYCODE_CTRL_RIGHT = 114
        const val KEYCODE_META_LEFT = 117
        const val KEYCODE_META_RIGHT = 118
    }
}

class MotionEvent(
    val action: Int,
    val actionMasked: Int = action and 0xFF,
    val actionIndex: Int = (action ushr 8) and 0xFF,
    val downTime: Long = SystemClock.uptimeMillis(),
    val eventTime: Long = SystemClock.uptimeMillis(),
    val buttonState: Int = 0,
    val pointers: List<PointerSample> = emptyList(),
    val history: List<HistoricalBatch> = emptyList()
) {
    data class PointerSample(
        val id: Int = 0,
        val x: Float = 0f,
        val y: Float = 0f,
        val pressure: Float = 0.5f,
        val toolType: Int = TOOL_TYPE_STYLUS,
        val tiltRad: Float = 0f,
        val orientationRad: Float = 0f,
        val tiltXDeg: Float = 0f,
        val tiltYDeg: Float = 0f,
        val distance: Float = 0f
    )

    data class HistoricalBatch(
        val eventTime: Long,
        val pointers: List<PointerSample>
    )

    companion object {
        const val ACTION_DOWN = 0
        const val ACTION_UP = 1
        const val ACTION_MOVE = 2
        const val ACTION_CANCEL = 3
        const val ACTION_OUTSIDE = 4
        const val ACTION_POINTER_DOWN = 5
        const val ACTION_POINTER_UP = 6
        const val ACTION_HOVER_MOVE = 7
        const val ACTION_SCROLL = 8
        const val ACTION_HOVER_ENTER = 9
        const val ACTION_HOVER_EXIT = 10
        const val ACTION_BUTTON_PRESS = 11
        const val ACTION_BUTTON_RELEASE = 12

        const val TOOL_TYPE_UNKNOWN = 0
        const val TOOL_TYPE_FINGER = 1
        const val TOOL_TYPE_STYLUS = 2
        const val TOOL_TYPE_MOUSE = 3
        const val TOOL_TYPE_ERASER = 4

        const val AXIS_X = 0
        const val AXIS_Y = 1
        const val AXIS_PRESSURE = 2
        const val AXIS_SIZE = 3
        const val AXIS_ORIENTATION = 8
        const val AXIS_VSCROLL = 9
        const val AXIS_HSCROLL = 10
        const val AXIS_DISTANCE = 24
        const val AXIS_TILT = 25
        const val AXIS_TILT_X_DEG = 100
        const val AXIS_TILT_Y_DEG = 101

        const val BUTTON_PRIMARY = 1 shl 0
        const val BUTTON_SECONDARY = 1 shl 1
        const val BUTTON_TERTIARY = 1 shl 2
        const val BUTTON_BACK = 1 shl 3
        const val BUTTON_FORWARD = 1 shl 4
        const val BUTTON_STYLUS_PRIMARY = 1 shl 5
        const val BUTTON_STYLUS_SECONDARY = 1 shl 6
    }

    val pointerCount: Int
        get() = pointers.size.coerceAtLeast(1)

    val x: Float
        get() = getX(0)

    val y: Float
        get() = getY(0)

    val pressure: Float
        get() = getPressure(0)

    val historySize: Int
        get() = history.size

    fun getX(pointerIndex: Int = 0): Float =
        pointers.getOrNull(pointerIndex)?.x ?: 0f

    fun getY(pointerIndex: Int = 0): Float =
        pointers.getOrNull(pointerIndex)?.y ?: 0f

    fun getPressure(pointerIndex: Int = 0): Float =
        pointers.getOrNull(pointerIndex)?.pressure ?: 0.5f

    fun getToolType(pointerIndex: Int = 0): Int =
        pointers.getOrNull(pointerIndex)?.toolType ?: TOOL_TYPE_STYLUS

    fun getPointerId(pointerIndex: Int = 0): Int =
        pointers.getOrNull(pointerIndex)?.id ?: pointerIndex

    fun getAxisValue(axis: Int, pointerIndex: Int = 0): Float {
        val p = pointers.getOrNull(pointerIndex) ?: return 0f
        return when (axis) {
            AXIS_X -> p.x
            AXIS_Y -> p.y
            AXIS_PRESSURE -> p.pressure
            AXIS_TILT -> p.tiltRad
            AXIS_ORIENTATION -> p.orientationRad
            AXIS_DISTANCE -> p.distance
            AXIS_TILT_X_DEG -> p.tiltXDeg
            AXIS_TILT_Y_DEG -> p.tiltYDeg
            else -> 0f
        }
    }

    fun getHistoricalEventTime(pos: Int): Long =
        history.getOrNull(pos)?.eventTime ?: eventTime

    fun getHistoricalX(pos: Int): Float = getHistoricalX(0, pos)

    fun getHistoricalX(pointerIndex: Int, pos: Int): Float =
        history.getOrNull(pos)?.pointers?.getOrNull(pointerIndex)?.x ?: getX(pointerIndex)

    fun getHistoricalY(pos: Int): Float = getHistoricalY(0, pos)

    fun getHistoricalY(pointerIndex: Int, pos: Int): Float =
        history.getOrNull(pos)?.pointers?.getOrNull(pointerIndex)?.y ?: getY(pointerIndex)

    fun getHistoricalPressure(pos: Int): Float = getHistoricalPressure(0, pos)

    fun getHistoricalPressure(pointerIndex: Int, pos: Int): Float =
        history.getOrNull(pos)?.pointers?.getOrNull(pointerIndex)?.pressure ?: getPressure(pointerIndex)

    fun getHistoricalAxisValue(axis: Int, pointerIndex: Int, pos: Int): Float {
        val p = history.getOrNull(pos)?.pointers?.getOrNull(pointerIndex)
            ?: return getAxisValue(axis, pointerIndex)
        return when (axis) {
            AXIS_X -> p.x
            AXIS_Y -> p.y
            AXIS_PRESSURE -> p.pressure
            AXIS_TILT -> p.tiltRad
            AXIS_ORIENTATION -> p.orientationRad
            AXIS_DISTANCE -> p.distance
            AXIS_TILT_X_DEG -> p.tiltXDeg
            AXIS_TILT_Y_DEG -> p.tiltYDeg
            else -> 0f
        }
    }
}

class ScaleGestureDetector(
    private val context: Context,
    private val listener: OnScaleGestureListener
) {
    interface OnScaleGestureListener {
        fun onScale(detector: ScaleGestureDetector): Boolean
        fun onScaleBegin(detector: ScaleGestureDetector): Boolean
        fun onScaleEnd(detector: ScaleGestureDetector)
    }

    open class SimpleOnScaleGestureListener : OnScaleGestureListener {
        override fun onScale(detector: ScaleGestureDetector): Boolean = false
        override fun onScaleBegin(detector: ScaleGestureDetector): Boolean = true
        override fun onScaleEnd(detector: ScaleGestureDetector) {}
    }

    var focusX: Float = 0f
        private set
    var focusY: Float = 0f
        private set
    var scaleFactor: Float = 1f
        private set
    var isInProgress: Boolean = false
        private set
    var isQuickScaleEnabled: Boolean = false
    var isStylusScaleEnabled: Boolean = false

    private var prevSpan: Float = 0f

    fun onTouchEvent(event: MotionEvent): Boolean {
        val count = event.pointerCount
        if (count < 2) {
            if (isInProgress) {
                isInProgress = false
                prevSpan = 0f
                listener.onScaleEnd(this)
            }
            return false
        }
        val x0 = event.getX(0)
        val y0 = event.getY(0)
        val x1 = event.getX(1)
        val y1 = event.getY(1)
        focusX = (x0 + x1) * 0.5f
        focusY = (y0 + y1) * 0.5f
        val span = hypot(x1 - x0, y1 - y0).coerceAtLeast(1f)

        when (event.actionMasked) {
            MotionEvent.ACTION_POINTER_DOWN, MotionEvent.ACTION_DOWN -> {
                prevSpan = span
                scaleFactor = 1f
                isInProgress = listener.onScaleBegin(this)
            }
            MotionEvent.ACTION_MOVE -> {
                if (!isInProgress && prevSpan > 0f) {
                    isInProgress = listener.onScaleBegin(this)
                }
                if (isInProgress && prevSpan > 1f) {
                    scaleFactor = (span / prevSpan).coerceIn(0.5f, 2.0f)
                    if (listener.onScale(this)) {
                        prevSpan = span
                    }
                } else {
                    prevSpan = span
                }
            }
            MotionEvent.ACTION_POINTER_UP, MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (isInProgress) {
                    isInProgress = false
                    prevSpan = 0f
                    listener.onScaleEnd(this)
                }
            }
        }
        return true
    }

    fun dispatchSyntheticZoom(focusX: Float, focusY: Float, factor: Float) {
        this.focusX = focusX
        this.focusY = focusY
        this.scaleFactor = factor.coerceIn(0.5f, 2.0f)
        this.isInProgress = true
        if (listener.onScaleBegin(this)) {
            listener.onScale(this)
            listener.onScaleEnd(this)
        }
        this.isInProgress = false
    }
}

interface ViewParent {
    fun requestDisallowInterceptTouchEvent(disallowIntercept: Boolean)
}

class ViewTreeObserver {
    fun interface OnScrollChangedListener {
        fun onScrollChanged()
    }

    fun interface OnPreDrawListener {
        fun onPreDraw(): Boolean
    }

    private val scrollListeners = CopyOnWriteArrayList<OnScrollChangedListener>()
    private val preDrawListeners = CopyOnWriteArrayList<OnPreDrawListener>()

    val isAlive: Boolean = true

    fun addOnScrollChangedListener(listener: OnScrollChangedListener) {
        scrollListeners.addIfAbsent(listener)
    }

    fun removeOnScrollChangedListener(listener: OnScrollChangedListener) {
        scrollListeners.remove(listener)
    }

    fun addOnPreDrawListener(listener: OnPreDrawListener) {
        preDrawListeners.addIfAbsent(listener)
    }

    fun removeOnPreDrawListener(listener: OnPreDrawListener) {
        preDrawListeners.remove(listener)
    }

    fun dispatchScrollChanged() {
        scrollListeners.forEach { it.onScrollChanged() }
    }
}

open class View(val context: Context) {
    var width: Int = 0
        internal set
    var height: Int = 0
        internal set

    var isFocusable: Boolean = true
    var isFocusableInTouchMode: Boolean = true

    val parent: ViewParent = object : ViewParent {
        override fun requestDisallowInterceptTouchEvent(disallowIntercept: Boolean) {}
    }

    val viewTreeObserver: ViewTreeObserver = ViewTreeObserver()

    internal val invalidationCounter = mutableIntStateOf(0)
    internal var screenLocationX: Int = 0
    internal var screenLocationY: Int = 0
    private val mainHandler = Handler(Looper.getMainLooper())

    fun invalidate() {
        invalidationCounter.intValue++
    }

    fun postInvalidate() {
        invalidationCounter.intValue++
    }

    fun postInvalidateOnAnimation() {
        invalidationCounter.intValue++
    }

    fun post(action: Runnable): Boolean = mainHandler.post(action)

    fun postDelayed(action: Runnable, delayMillis: Long): Boolean =
        mainHandler.postDelayed(action, delayMillis)

    fun removeCallbacks(action: Runnable): Boolean {
        mainHandler.removeCallbacks(action)
        return true
    }

    fun requestFocus(): Boolean = true

    fun requestUnbufferedDispatch(event: MotionEvent) {}

    fun getLocalVisibleRect(r: Rect): Boolean {
        r.set(0, 0, width, height)
        return width > 0 && height > 0
    }

    fun getLocationOnScreen(outLocation: IntArray) {
        if (outLocation.size >= 2) {
            outLocation[0] = screenLocationX
            outLocation[1] = screenLocationY
        }
    }

    fun getLocationInWindow(outLocation: IntArray) {
        getLocationOnScreen(outLocation)
    }

    fun dispatchDesktopSizeChanged(newW: Int, newH: Int) {
        if (newW > 0 && newH > 0 && (newW != width || newH != height)) {
            val oldW = width
            val oldH = height
            width = newW
            height = newH
            onSizeChanged(newW, newH, oldW, oldH)
            invalidate()
        }
    }

    fun dispatchDesktopDraw(canvas: Canvas) {
        onDraw(canvas)
    }

    fun dispatchDesktopAttachedToWindow() {
        onAttachedToWindow()
    }

    fun dispatchDesktopDetachedFromWindow() {
        onDetachedFromWindow()
    }

    protected open fun onAttachedToWindow() {}
    protected open fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {}
    protected open fun onDetachedFromWindow() {}
    protected open fun onDraw(canvas: Canvas) {}
    open fun onTouchEvent(event: MotionEvent): Boolean = false
    open fun onHoverEvent(event: MotionEvent): Boolean = false
    open fun onGenericMotionEvent(event: MotionEvent): Boolean = false
    open fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean = false
}
