package com.orgwritenotes.ink

import android.graphics.Rect
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.View
import com.onyx.android.sdk.api.device.epd.EpdController
import com.onyx.android.sdk.data.note.TouchPoint
import com.onyx.android.sdk.pen.RawInputCallback
import com.onyx.android.sdk.pen.TouchHelper
import com.onyx.android.sdk.pen.data.TouchPointList
import java.lang.reflect.Method

/**
 * Hardware detection, EPD waveform refresh control, and direct-to-screen raw stylus drawing
 * for Onyx Boox E-Ink devices (Tier 1, Tier 2, Tier 3).
 *
 * The Onyx SDK is bundled in the APK. The EPD waveform controls use cached reflection and the
 * raw stylus path calls `TouchHelper` directly, but both are only reached when
 * `isEInkDevice == true`. On regular LCD/OLED tablets every method is an early-return no-op, so
 * the Onyx classes are never loaded and the original 60/120Hz behavior is unchanged.
 */
object OnyxEInkBridge {

    /**
     * Auto-detected once at class load time. True on Onyx Boox (and other E-Ink Android tablets).
     */
    val isEInkDevice: Boolean = detectEInkHardware()

    private fun detectEInkHardware(): Boolean {
        val mfr = Build.MANUFACTURER?.lowercase().orEmpty()
        val brand = Build.BRAND?.lowercase().orEmpty()
        val device = Build.DEVICE?.lowercase().orEmpty()
        val product = Build.PRODUCT?.lowercase().orEmpty()
        // Hardware-only check: the Onyx SDK is bundled in the APK, so class presence says nothing.
        return mfr.contains("onyx") || brand.contains("onyx") || brand.contains("boox") ||
            device.contains("onyx") || product.contains("onyx") || product.contains("boox")
    }

    // =========================================================================
    // Tier 2: Onyx EPD Waveform Refresh Controller (Fast A2/DU vs Regal/GC16)
    // =========================================================================

    private var epdInitialized = false
    private var epdControllerClass: Class<*>? = null
    private var setViewDefaultUpdateModeMethod: Method? = null
    private var resetViewUpdateModeMethod: Method? = null
    private var invalidateViewMethod: Method? = null
    private var applyTransientUpdateMethod: Method? = null
    private var clearTransientUpdateMethod: Method? = null

    private var modeDuOrA2: Any? = null
    private var modeRegalOrGc: Any? = null
    private var modeGu: Any? = null

    @Synchronized
    private fun ensureEpdReflection() {
        if (epdInitialized || !isEInkDevice) return
        epdInitialized = true
        try {
            val epdCls = Class.forName("com.onyx.android.sdk.api.device.epd.EpdController")
            val updateModeCls = Class.forName("com.onyx.android.sdk.api.device.epd.UpdateMode")
            epdControllerClass = epdCls

            val enumConstants = updateModeCls.enumConstants?.associateBy { (it as Enum<*>).name }.orEmpty()
            modeDuOrA2 = enumConstants["DU"] ?: enumConstants["A2"] ?: enumConstants["ANIMATION_QUALITY"]
            modeGu = enumConstants["GU"] ?: enumConstants["GU_FAST"] ?: modeDuOrA2
            modeRegalOrGc = enumConstants["REGAL"] ?: enumConstants["REGAL_PLUS"] ?: enumConstants["GC"] ?: enumConstants["GC16"]

            setViewDefaultUpdateModeMethod = runCatching {
                epdCls.getMethod("setViewDefaultUpdateMode", View::class.java, updateModeCls)
            }.getOrNull()

            resetViewUpdateModeMethod = runCatching {
                epdCls.getMethod("resetViewUpdateMode", View::class.java)
            }.getOrNull()

            invalidateViewMethod = runCatching {
                epdCls.getMethod("invalidate", View::class.java, updateModeCls)
            }.getOrNull()

            applyTransientUpdateMethod = runCatching {
                epdCls.getMethod("applyApplicationFastMode", String::class.java, Boolean::class.javaPrimitiveType, Boolean::class.javaPrimitiveType)
            }.getOrNull()
        } catch (_: Throwable) {
            // Device is Onyx by Build.MANUFACTURER without SDK classes in bootclasspath; safe fallback.
        }
    }

    /**
     * Switches [view] (or the application window) into fast binary/A2/DU waveform mode
     * during continuous pan, pinch-zoom, or list scrolling to avoid E-Ink frame backlog.
     */
    fun enterFastPanMode(view: View) {
        if (!isEInkDevice) return
        ensureEpdReflection()
        try {
            val fastMode = modeDuOrA2
            if (fastMode != null && setViewDefaultUpdateModeMethod != null) {
                setViewDefaultUpdateModeMethod?.invoke(null, view, fastMode)
            } else {
                applyTransientUpdateMethod?.invoke(null, "orgwritenotes", true, true)
            }
        } catch (_: Throwable) {
        }
    }

    /**
     * Exits fast A2/DU mode after pan/zoom/scroll finishes and triggers a clean REGAL
     * (or GC16) de-ghosting refresh on [view].
     */
    fun exitFastPanModeAndRefresh(view: View) {
        if (!isEInkDevice) return
        ensureEpdReflection()
        try {
            resetViewUpdateModeMethod?.invoke(null, view)
            applyTransientUpdateMethod?.invoke(null, "orgwritenotes", false, true)
            val regal = modeRegalOrGc
            if (regal != null && invalidateViewMethod != null) {
                invalidateViewMethod?.invoke(null, view, regal)
            } else {
                view.invalidate()
            }
        } catch (_: Throwable) {
            view.invalidate()
        }
    }

    /**
     * Triggers an explicit clean REGAL/GC16 refresh on [view] to clear any E-Ink ghosting
     * (e.g., after erasing strokes, closing a modal, or switching Org headings).
     */
    fun requestCleanRefresh(view: View) {
        if (!isEInkDevice) {
            view.invalidate()
            return
        }
        ensureEpdReflection()
        try {
            val regal = modeRegalOrGc
            if (regal != null && invalidateViewMethod != null) {
                invalidateViewMethod?.invoke(null, view, regal)
            } else {
                view.invalidate()
            }
        } catch (_: Throwable) {
            view.invalidate()
        }
    }

    // =========================================================================
    // Tier 3: Onyx TouchHelper Direct-to-EPD Hardware Stylus Fast-Path
    // =========================================================================

    interface RawStrokeListener {
        fun onRawStrokeBegin(screenX: Float, screenY: Float, pressure: Float, tiltX: Float, tiltY: Float, timestampMs: Long)
        fun onRawStrokeMove(screenX: Float, screenY: Float, pressure: Float, tiltX: Float, tiltY: Float, timestampMs: Long)
        fun onRawStrokeEnd()
        fun onRawEraseBegin(screenX: Float, screenY: Float, timestampMs: Long)
        fun onRawEraseMove(screenX: Float, screenY: Float, timestampMs: Long)
        fun onRawEraseEnd()
    }

    /**
     * Wraps an Onyx `TouchHelper` for a specific [WriteCanvasBlockView]. The hardware renders the
     * ink trace directly to the EPD (~15ms latency) while every touch point is forwarded to
     * [RawStrokeListener] so the C++ Stylus Labs Write vector engine records the exact stroke.
     *
     * Only ever constructed when [isEInkDevice] is true, so the Onyx classes are never loaded
     * on other devices.
     */
    class OnyxRawTouchSession private constructor(
        private val touchHelper: TouchHelper
    ) : AutoCloseable {
        var isRawDrawingActive: Boolean = false
            private set

        fun updateLimitRect(view: View, excludeRects: List<Rect> = emptyList()) {
            try {
                // The SDK reports points in the host view's local coordinates, so the limit region
                // must be the view's local visible rect, not its on-screen (global) rect.
                val rect = Rect()
                if (!view.getLocalVisibleRect(rect) || rect.isEmpty) return
                applyLimitRect(rect, excludeRects)
            } catch (_: Throwable) {
            }
        }

        /** Sets the pen layer's drawing region (view-local coordinates) and exclusions explicitly. */
        fun applyLimitRect(rect: Rect, excludeRects: List<Rect>) {
            try {
                // Limit changes only take effect when raw drawing is (re)armed.
                val wasActive = isRawDrawingActive
                if (wasActive) touchHelper.setRawDrawingEnabled(false)
                touchHelper.setLimitRect(rect, ArrayList(excludeRects))
                if (wasActive) {
                    touchHelper.setRawDrawingEnabled(true)
                    touchHelper.enableFingerTouch(true)
                }
            } catch (_: Throwable) {
            }
        }

        fun configureStroke(widthPx: Float, colorArgb: Int) {
            try {
                touchHelper.setStrokeStyle(TouchHelper.STROKE_STYLE_FOUNTAIN)
                touchHelper.setStrokeWidth(widthPx.coerceAtLeast(1.0f))
                touchHelper.setStrokeColor(colorArgb)
            } catch (_: Throwable) {
            }
        }

        fun setRawDrawingEnabled(enabled: Boolean, renderToEpd: Boolean = true) {
            try {
                if (enabled && !isRawDrawingActive) {
                    touchHelper.openRawDrawing()
                    touchHelper.setRawDrawingEnabled(true)
                    touchHelper.setRawDrawingRenderEnabled(renderToEpd)
                    // Armed raw drawing otherwise swallows finger touches (pan/zoom, buttons).
                    touchHelper.enableFingerTouch(true)
                    isRawDrawingActive = true
                } else if (!enabled && isRawDrawingActive) {
                    touchHelper.setRawDrawingEnabled(false)
                    isRawDrawingActive = false
                }
            } catch (_: Throwable) {
            }
        }

        fun release() {
            try {
                touchHelper.setRawDrawingEnabled(false)
                touchHelper.closeRawDrawing()
                isRawDrawingActive = false
            } catch (_: Throwable) {
            }
        }

        override fun close() = release()

        companion object {
            fun createIfSupported(view: View, listener: RawStrokeListener): OnyxRawTouchSession? {
                if (!isEInkDevice) return null
                return try {
                    // Onyx's pen SDK calls hidden framework APIs; exempt them before first use.
                    runCatching { org.lsposed.hiddenapibypass.HiddenApiBypass.addHiddenApiExemptions("") }
                    OnyxRawTouchSession(TouchHelper.create(view, RawCallback(listener)))
                } catch (t: Throwable) {
                    android.util.Log.w("OnyxEInkBridge", "Raw stylus session unavailable", t)
                    null
                }
            }
        }
    }

    private class RawCallback(private val listener: RawStrokeListener) : RawInputCallback() {
        // Onyx reports pressure in 0..getMaxTouchPressure(); the stroke engine expects 0..1.
        private val maxPressure: Float =
            runCatching { EpdController.getMaxTouchPressure() }.getOrDefault(0f)
                .let { if (it > 0f) it else 4096f }

        // The SDK delivers callbacks on its own thread; the listener touches views and Compose
        // state, so hop to the main looper (FIFO keeps stroke order).
        private val main = Handler(Looper.getMainLooper())

        private fun TouchPoint.pressure01() = (pressure / maxPressure).coerceIn(0f, 1f)

        override fun onBeginRawDrawing(shortcutDrawing: Boolean, point: TouchPoint) {
            val x = point.x; val y = point.y; val p = point.pressure01()
            val tx = point.tiltX.toFloat(); val ty = point.tiltY.toFloat(); val t = point.timestamp
            main.post { listener.onRawStrokeBegin(x, y, p, tx, ty, t) }
        }

        override fun onRawDrawingTouchPointMoveReceived(point: TouchPoint) {
            val x = point.x; val y = point.y; val p = point.pressure01()
            val tx = point.tiltX.toFloat(); val ty = point.tiltY.toFloat(); val t = point.timestamp
            main.post { listener.onRawStrokeMove(x, y, p, tx, ty, t) }
        }

        override fun onRawDrawingTouchPointListReceived(pointList: TouchPointList) {
            // Per-point moves are already delivered above; the list is the whole stroke on pen-up.
        }

        override fun onEndRawDrawing(outLimitRegion: Boolean, point: TouchPoint) {
            main.post { listener.onRawStrokeEnd() }
        }

        override fun onBeginRawErasing(shortcutErasing: Boolean, point: TouchPoint) {
            val x = point.x; val y = point.y; val t = point.timestamp
            main.post { listener.onRawEraseBegin(x, y, t) }
        }

        override fun onRawErasingTouchPointMoveReceived(point: TouchPoint) {
            val x = point.x; val y = point.y; val t = point.timestamp
            main.post { listener.onRawEraseMove(x, y, t) }
        }

        override fun onRawErasingTouchPointListReceived(pointList: TouchPointList) {}

        override fun onEndRawErasing(outLimitRegion: Boolean, point: TouchPoint) {
            main.post { listener.onRawEraseEnd() }
        }
    }
}
