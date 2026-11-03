package com.orgwritenotes.ink

import android.graphics.Rect
import android.os.Build
import android.view.MotionEvent
import android.view.View
import java.lang.reflect.InvocationHandler
import java.lang.reflect.Method
import java.lang.reflect.Proxy

/**
 * Hardware detection, EPD waveform refresh control, and direct-to-screen raw stylus drawing
 * for Onyx Boox E-Ink devices (Tier 1, Tier 2, Tier 3).
 *
 * Uses cached reflection against Onyx's system/SDK classes (`com.onyx.android.sdk.api.device.epd.EpdController`
 * and `com.onyx.android.sdk.pen.TouchHelper` / `RawInputCallback`) so:
 *  1. Zero proprietary Maven dependencies or APK bloat are required.
 *  2. On regular LCD/OLED tablets (`isEInkDevice == false`), every method is an inline no-op
 *     with zero runtime overhead, zero allocations, and 100% original 60/120Hz behavior.
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
        if (mfr.contains("onyx") || brand.contains("onyx") || brand.contains("boox") ||
            device.contains("onyx") || product.contains("onyx") || product.contains("boox")
        ) {
            return true
        }
        // Fallback check if Onyx EPD framework class is present in the boot/system classpath
        return try {
            Class.forName("com.onyx.android.sdk.api.device.epd.EpdController")
            true
        } catch (_: Throwable) {
            false
        }
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
     * Wraps an Onyx `TouchHelper` instance for a specific [WriteCanvasBlockView].
     * If the Onyx Pen SDK classes are present on the device/runtime, this binds the hardware
     * direct-to-EPD ink trace (~15ms latency) while forwarding every touch point to [RawStrokeListener]
     * so the C++ Stylus Labs Write vector engine records the exact `.svgz` stroke.
     */
    class OnyxRawTouchSession private constructor(
        private val touchHelperInstance: Any,
        private val setLimitRectMethod: Method?,
        private val setStrokeWidthMethod: Method?,
        private val setStrokeStyleMethod: Method?,
        private val setStrokeColorMethod: Method?,
        private val openRawDrawingMethod: Method?,
        private val closeRawDrawingMethod: Method?,
        private val setRawDrawingEnabledMethod: Method?,
        private val setRawDrawingRenderEnabledMethod: Method?,
        private val stylePencilOrBrush: Int
    ) : AutoCloseable {
        var isRawDrawingActive: Boolean = false
            private set

        fun updateLimitRect(view: View, excludeRects: List<Rect> = emptyList()) {
            try {
                val rect = Rect()
                view.getGlobalVisibleRect(rect)
                if (!rect.isEmpty) {
                    setLimitRectMethod?.invoke(touchHelperInstance, rect, ArrayList(excludeRects))
                }
            } catch (_: Throwable) {
            }
        }

        fun configureStroke(widthPx: Float, colorArgb: Int) {
            try {
                setStrokeStyleMethod?.invoke(touchHelperInstance, stylePencilOrBrush)
                setStrokeWidthMethod?.invoke(touchHelperInstance, widthPx.coerceAtLeast(1.0f))
                setStrokeColorMethod?.invoke(touchHelperInstance, colorArgb)
            } catch (_: Throwable) {
            }
        }

        fun setRawDrawingEnabled(enabled: Boolean, renderToEpd: Boolean = true) {
            try {
                if (enabled && !isRawDrawingActive) {
                    openRawDrawingMethod?.invoke(touchHelperInstance)
                    setRawDrawingEnabledMethod?.invoke(touchHelperInstance, true)
                    setRawDrawingRenderEnabledMethod?.invoke(touchHelperInstance, renderToEpd)
                    isRawDrawingActive = true
                } else if (!enabled && isRawDrawingActive) {
                    setRawDrawingEnabledMethod?.invoke(touchHelperInstance, false)
                    isRawDrawingActive = false
                }
            } catch (_: Throwable) {
            }
        }

        fun release() {
            try {
                setRawDrawingEnabledMethod?.invoke(touchHelperInstance, false)
                closeRawDrawingMethod?.invoke(touchHelperInstance)
                isRawDrawingActive = false
            } catch (_: Throwable) {
            }
        }

        override fun close() = release()

        companion object {
            fun createIfSupported(view: View, listener: RawStrokeListener): OnyxRawTouchSession? {
                if (!isEInkDevice) return null
                return try {
                    val touchHelperCls = Class.forName("com.onyx.android.sdk.pen.TouchHelper")
                    val callbackCls = Class.forName("com.onyx.android.sdk.pen.RawInputCallback")

                    val proxy = Proxy.newProxyInstance(
                        callbackCls.classLoader,
                        arrayOf(callbackCls),
                        RawInputInvocationHandler(listener)
                    )

                    val createMethod = touchHelperCls.getMethod("create", View::class.java, callbackCls)
                    val helperObj = createMethod.invoke(null, view, proxy) ?: return null

                    val setLimitRect = runCatching {
                        touchHelperCls.getMethod("setLimitRect", Rect::class.java, java.util.List::class.java)
                    }.getOrNull()
                    val setStrokeWidth = runCatching {
                        touchHelperCls.getMethod("setStrokeWidth", Float::class.javaPrimitiveType)
                    }.getOrNull()
                    val setStrokeStyle = runCatching {
                        touchHelperCls.getMethod("setStrokeStyle", Int::class.javaPrimitiveType)
                    }.getOrNull()
                    val setStrokeColor = runCatching {
                        touchHelperCls.getMethod("setStrokeColor", Int::class.javaPrimitiveType)
                    }.getOrNull()
                    val openRaw = runCatching {
                        touchHelperCls.getMethod("openRawDrawing")
                    }.getOrNull()
                    val closeRaw = runCatching {
                        touchHelperCls.getMethod("closeRawDrawing")
                    }.getOrNull()
                    val setEnabled = runCatching {
                        touchHelperCls.getMethod("setRawDrawingEnabled", Boolean::class.javaPrimitiveType)
                    }.getOrNull()
                    val setRenderEnabled = runCatching {
                        touchHelperCls.getMethod("setRawDrawingRenderEnabled", Boolean::class.javaPrimitiveType)
                    }.getOrNull()

                    val brushConst = runCatching {
                        touchHelperCls.getField("STROKE_STYLE_FOUNTAIN").getInt(null)
                    }.getOrDefault(0)

                    OnyxRawTouchSession(
                        touchHelperInstance = helperObj,
                        setLimitRectMethod = setLimitRect,
                        setStrokeWidthMethod = setStrokeWidth,
                        setStrokeStyleMethod = setStrokeStyle,
                        setStrokeColorMethod = setStrokeColor,
                        openRawDrawingMethod = openRaw,
                        closeRawDrawingMethod = closeRaw,
                        setRawDrawingEnabledMethod = setEnabled,
                        setRawDrawingRenderEnabledMethod = setRenderEnabled,
                        stylePencilOrBrush = brushConst
                    )
                } catch (_: Throwable) {
                    null
                }
            }
        }
    }

    private class RawInputInvocationHandler(
        private val listener: RawStrokeListener
    ) : InvocationHandler {
        private var xField: java.lang.reflect.Field? = null
        private var yField: java.lang.reflect.Field? = null
        private var pressureField: java.lang.reflect.Field? = null
        private var tiltXField: java.lang.reflect.Field? = null
        private var tiltYField: java.lang.reflect.Field? = null
        private var timestampField: java.lang.reflect.Field? = null
        private var getPointsMethod: Method? = null
        private var fieldsResolved = false

        private fun resolveTouchPointFields(pointObj: Any) {
            if (fieldsResolved) return
            fieldsResolved = true
            val cls = pointObj.javaClass
            xField = runCatching { cls.getField("x").apply { isAccessible = true } }.getOrNull()
            yField = runCatching { cls.getField("y").apply { isAccessible = true } }.getOrNull()
            pressureField = runCatching { cls.getField("pressure").apply { isAccessible = true } }.getOrNull()
            tiltXField = runCatching { cls.getField("tiltX").apply { isAccessible = true } }.getOrNull()
            tiltYField = runCatching { cls.getField("tiltY").apply { isAccessible = true } }.getOrNull()
            timestampField = runCatching { cls.getField("timestamp").apply { isAccessible = true } }.getOrNull()
        }

        private fun extractPoint(pointObj: Any, out5: FloatArray): Long {
            resolveTouchPointFields(pointObj)
            out5[0] = runCatching { xField?.getFloat(pointObj) ?: 0f }.getOrDefault(0f)
            out5[1] = runCatching { yField?.getFloat(pointObj) ?: 0f }.getOrDefault(0f)
            out5[2] = runCatching { pressureField?.getFloat(pointObj) ?: 0.5f }.getOrDefault(0.5f)
            out5[3] = runCatching { tiltXField?.getFloat(pointObj) ?: 0f }.getOrDefault(0f)
            out5[4] = runCatching { tiltYField?.getFloat(pointObj) ?: 0f }.getOrDefault(0f)
            return runCatching { timestampField?.getLong(pointObj) ?: System.currentTimeMillis() }
                .getOrDefault(System.currentTimeMillis())
        }

        private val tmpPt = FloatArray(5)

        override fun invoke(proxy: Any, method: Method, args: Array<out Any?>?): Any? {
            when (method.name) {
                "onBeginRawDrawing" -> {
                    val pt = args?.getOrNull(1) ?: return null
                    val ts = extractPoint(pt, tmpPt)
                    listener.onRawStrokeBegin(tmpPt[0], tmpPt[1], tmpPt[2], tmpPt[3], tmpPt[4], ts)
                }
                "onRawDrawingTouchPointMoveReceived" -> {
                    val pt = args?.getOrNull(0) ?: return null
                    val ts = extractPoint(pt, tmpPt)
                    listener.onRawStrokeMove(tmpPt[0], tmpPt[1], tmpPt[2], tmpPt[3], tmpPt[4], ts)
                }
                "onRawDrawingTouchPointListReceived" -> {
                    val listObj = args?.getOrNull(0) ?: return null
                    val points: Iterable<*>? = when (listObj) {
                        is Iterable<*> -> listObj
                        else -> {
                            if (getPointsMethod == null) {
                                getPointsMethod = runCatching { listObj.javaClass.getMethod("getPoints") }.getOrNull()
                            }
                            runCatching { getPointsMethod?.invoke(listObj) as? Iterable<*> }.getOrNull()
                        }
                    }
                    points?.forEach { pt ->
                        if (pt != null) {
                            val ts = extractPoint(pt, tmpPt)
                            listener.onRawStrokeMove(tmpPt[0], tmpPt[1], tmpPt[2], tmpPt[3], tmpPt[4], ts)
                        }
                    }
                }
                "onEndRawDrawing" -> {
                    listener.onRawStrokeEnd()
                }
                "onBeginRawErasing" -> {
                    val pt = args?.getOrNull(1) ?: return null
                    val ts = extractPoint(pt, tmpPt)
                    listener.onRawEraseBegin(tmpPt[0], tmpPt[1], ts)
                }
                "onRawErasingTouchPointMoveReceived" -> {
                    val pt = args?.getOrNull(0) ?: return null
                    val ts = extractPoint(pt, tmpPt)
                    listener.onRawEraseMove(tmpPt[0], tmpPt[1], ts)
                }
                "onEndRawErasing" -> {
                    listener.onRawEraseEnd()
                }
                "hashCode" -> return System.identityHashCode(proxy)
                "equals" -> return proxy === args?.getOrNull(0)
                "toString" -> return "OnyxRawInputProxy"
            }
            return null
        }
    }
}
