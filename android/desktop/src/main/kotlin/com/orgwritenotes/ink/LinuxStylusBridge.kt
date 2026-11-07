@file:Suppress("ktlint")

package com.orgwritenotes.ink

import android.os.SystemClock
import android.view.MotionEvent
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.atan2
import kotlin.math.hypot
import kotlin.math.tan

/**
 * Bridge to the native Linux stylus & touch subsystem (`linux_stylus_input.cpp`)
 * supporting Wacom EMR, USI, AES, MPP, XInput2, XWayland tablet devices, and
 * kernel `evdev` (`/dev/input/event*`).
 */
object LinuxStylusBridge {
    data class StylusSnapshot(
        val active: Boolean = false,
        val toolType: Int = MotionEvent.TOOL_TYPE_STYLUS,
        val inProximity: Boolean = false,
        val tipDown: Boolean = false,
        val pressure: Float = 0.5f,
        val tiltRad: Float = 0f,
        val tiltXDeg: Float = 0f,
        val tiltYDeg: Float = 0f,
        val buttonMask: Int = 0,
        val barrelPrimary: Boolean = false,
        val barrelSecondary: Boolean = false,
        val lastStylusTimestampMs: Float = 0f,
        val detectedDeviceCount: Int = 0
    ) {
        /**
         * Converts hardware X/Y tilt angles (degrees) into Android's spherical
         * `AXIS_TILT` (radians from vertical, `0..pi/2`) and `AXIS_ORIENTATION` (azimuth radians).
         */
        fun computeTiltAndOrientationRad(): Pair<Float, Float> {
            if (tiltXDeg == 0f && tiltYDeg == 0f) return tiltRad to 0f
            val txRad = Math.toRadians(tiltXDeg.toDouble())
            val tyRad = Math.toRadians(tiltYDeg.toDouble())
            val gx = tan(txRad)
            val gy = tan(tyRad)
            val computedTiltRad = if (tiltRad > 0.001f) {
                tiltRad
            } else {
                atan2(hypot(gx, gy), 1.0).toFloat().coerceIn(0f, (Math.PI / 2.0).toFloat())
            }
            val orientationRad = atan2(gx, -gy).toFloat()
            return computedTiltRad to orientationRad
        }
    }

    private val initialized = AtomicBoolean(false)
    private val stateBuffer8 = FloatArray(8)
    private val historyBufferFlat6 = FloatArray(64 * 6)

    fun ensureInitialized(): Boolean {
        if (!WriteNativeBridge.isNativeLoaded) return false
        if (initialized.compareAndSet(false, true)) {
            return try {
                nativeInit()
            } catch (_: Throwable) {
                false
            }
        }
        return true
    }

    fun shutdown() {
        if (initialized.compareAndSet(true, false)) {
            try {
                nativeShutdown()
            } catch (_: Throwable) {
            }
        }
    }

    @Synchronized
    fun pollSnapshot(): StylusSnapshot {
        if (!ensureInitialized()) return StylusSnapshot()
        return try {
            val histCount = nativePollStylusState(stateBuffer8, historyBufferFlat6)
            val toolType = stateBuffer8[0].toInt()
            val pressure = stateBuffer8[1].coerceIn(0f, 1f)
            val tiltRad = stateBuffer8[2]
            val tiltX = stateBuffer8[3].coerceIn(-90f, 90f)
            val tiltY = stateBuffer8[4].coerceIn(-90f, 90f)
            val btnMask = stateBuffer8[5].toInt()
            val lastTs = stateBuffer8[6]
            val devCount = stateBuffer8[7].toInt()

            val isStylusOrEraser = toolType == MotionEvent.TOOL_TYPE_STYLUS ||
                toolType == MotionEvent.TOOL_TYPE_ERASER
            val tipDown = (btnMask and MotionEvent.BUTTON_PRIMARY) != 0 || pressure > 0.005f
            val active = isStylusOrEraser || tipDown || histCount > 0 || lastTs > 0f

            StylusSnapshot(
                active = active,
                toolType = toolType,
                inProximity = isStylusOrEraser || tipDown,
                tipDown = tipDown,
                pressure = pressure,
                tiltRad = tiltRad,
                tiltXDeg = tiltX,
                tiltYDeg = tiltY,
                buttonMask = btnMask,
                barrelPrimary = (btnMask and MotionEvent.BUTTON_STYLUS_PRIMARY) != 0,
                barrelSecondary = (btnMask and MotionEvent.BUTTON_STYLUS_SECONDARY) != 0,
                lastStylusTimestampMs = lastTs,
                detectedDeviceCount = devCount
            )
        } catch (_: Throwable) {
            StylusSnapshot()
        }
    }

    fun getDetectedDevicesSummary(): String {
        if (!ensureInitialized()) return "JNI bridge unavailable"
        return try {
            val arr = nativeGetDetectedDevices()
            if (arr.isNullOrEmpty()) {
                "Standard pointer input (no dedicated XInput2/evdev tablet detected)"
            } else {
                arr.joinToString("\n") { "  • $it" }
            }
        } catch (_: Throwable) {
            "Standard pointer input"
        }
    }

    /**
     * Synthesizes an `android.view.MotionEvent` by fusing Compose/AWT pointer coordinates
     * with real-time Linux hardware stylus axes (pressure, tiltX, tiltY, eraser tool, barrel buttons).
     */
    fun buildMotionEvent(
        action: Int,
        actionIndex: Int = 0,
        downTime: Long,
        eventTime: Long = SystemClock.uptimeMillis(),
        pointers: List<RawPointerInput>,
        historicalBatches: List<Pair<Long, List<RawPointerInput>>> = emptyList(),
        mouseSecondaryPressed: Boolean = false,
        mouseTertiaryPressed: Boolean = false,
        defaultToolOverride: Int? = null
    ): MotionEvent {
        val hw = pollSnapshot()
        val hwFresh = hw.active
        val (hwTiltRad, hwOrientRad) = hw.computeTiltAndOrientationRad()

        val buttonState = buildButtonState(
            hwFresh = hwFresh,
            hw = hw,
            mouseSecondaryPressed = mouseSecondaryPressed,
            mouseTertiaryPressed = mouseTertiaryPressed
        )

        val mappedPointers = pointers.mapIndexed { _, raw ->
            val resolvedTool = resolveToolType(raw, hwFresh, hw, mouseSecondaryPressed, defaultToolOverride)
            val resolvedPressure = when {
                resolvedTool == MotionEvent.TOOL_TYPE_STYLUS || resolvedTool == MotionEvent.TOOL_TYPE_ERASER -> {
                    when {
                        hwFresh && hw.tipDown && hw.pressure > 0.001f -> hw.pressure
                        raw.pressure > 0.001f && raw.pressure != 1.0f && raw.pressure != 0.5f -> raw.pressure
                        hwFresh && hw.pressure > 0.001f -> hw.pressure
                        action == MotionEvent.ACTION_HOVER_MOVE ||
                            action == MotionEvent.ACTION_HOVER_ENTER ||
                            action == MotionEvent.ACTION_HOVER_EXIT -> 0f
                        else -> 0.55f
                    }
                }
                else -> raw.pressure.coerceIn(0.05f, 1.0f)
            }
            MotionEvent.PointerSample(
                id = raw.id,
                x = raw.x,
                y = raw.y,
                pressure = resolvedPressure,
                toolType = resolvedTool,
                tiltRad = if (hwFresh) hwTiltRad else 0f,
                orientationRad = if (hwFresh) hwOrientRad else 0f,
                tiltXDeg = if (hwFresh) hw.tiltXDeg else 0f,
                tiltYDeg = if (hwFresh) hw.tiltYDeg else 0f,
                distance = if (hwFresh && !hw.tipDown) 5f else 0f
            )
        }

        val mappedHistory = historicalBatches.map { (hTime, hRawList) ->
            MotionEvent.HistoricalBatch(
                eventTime = hTime,
                pointers = hRawList.mapIndexed { idx, raw ->
                    val base = mappedPointers.getOrNull(idx)
                    MotionEvent.PointerSample(
                        id = raw.id,
                        x = raw.x,
                        y = raw.y,
                        pressure = if (raw.pressure > 0.001f && raw.pressure != 1.0f) {
                            raw.pressure
                        } else {
                            base?.pressure ?: 0.55f
                        },
                        toolType = base?.toolType ?: MotionEvent.TOOL_TYPE_STYLUS,
                        tiltRad = base?.tiltRad ?: 0f,
                        orientationRad = base?.orientationRad ?: 0f,
                        tiltXDeg = base?.tiltXDeg ?: 0f,
                        tiltYDeg = base?.tiltYDeg ?: 0f
                    )
                }
            )
        }

        val combinedAction = if (action == MotionEvent.ACTION_POINTER_DOWN || action == MotionEvent.ACTION_POINTER_UP) {
            action or (actionIndex shl 8)
        } else {
            action
        }

        return MotionEvent(
            action = combinedAction,
            actionMasked = action,
            actionIndex = actionIndex,
            downTime = downTime,
            eventTime = eventTime,
            buttonState = buttonState,
            pointers = mappedPointers,
            history = mappedHistory
        )
    }

    private fun resolveToolType(
        raw: RawPointerInput,
        hwFresh: Boolean,
        hw: StylusSnapshot,
        mouseSecondaryPressed: Boolean,
        defaultToolOverride: Int?
    ): Int {
        if (defaultToolOverride != null) return defaultToolOverride
        if (hwFresh) {
            if (hw.toolType == MotionEvent.TOOL_TYPE_ERASER || hw.barrelSecondary) {
                return MotionEvent.TOOL_TYPE_ERASER
            }
            if (hw.toolType == MotionEvent.TOOL_TYPE_FINGER) {
                return MotionEvent.TOOL_TYPE_FINGER
            }
            if (hw.inProximity || hw.tipDown || hw.toolType == MotionEvent.TOOL_TYPE_STYLUS) {
                return MotionEvent.TOOL_TYPE_STYLUS
            }
        }
        return when (raw.composePointerType) {
            PointerKind.ERASER -> MotionEvent.TOOL_TYPE_ERASER
            PointerKind.STYLUS -> MotionEvent.TOOL_TYPE_STYLUS
            PointerKind.TOUCH -> MotionEvent.TOOL_TYPE_FINGER
            PointerKind.MOUSE -> {
                if (mouseSecondaryPressed) {
                    MotionEvent.TOOL_TYPE_ERASER
                } else {
                    MotionEvent.TOOL_TYPE_STYLUS
                }
            }
        }
    }

    private fun buildButtonState(
        hwFresh: Boolean,
        hw: StylusSnapshot,
        mouseSecondaryPressed: Boolean,
        mouseTertiaryPressed: Boolean
    ): Int {
        var mask = if (hwFresh) hw.buttonMask else 0
        if (mouseSecondaryPressed) {
            mask = mask or MotionEvent.BUTTON_SECONDARY or MotionEvent.BUTTON_STYLUS_PRIMARY
        }
        if (mouseTertiaryPressed) {
            mask = mask or MotionEvent.BUTTON_TERTIARY or MotionEvent.BUTTON_STYLUS_SECONDARY
        }
        return mask
    }

    enum class PointerKind {
        STYLUS,
        ERASER,
        TOUCH,
        MOUSE
    }

    data class RawPointerInput(
        val id: Int,
        val x: Float,
        val y: Float,
        val pressure: Float = 0.55f,
        val composePointerType: PointerKind = PointerKind.STYLUS
    )

    external fun nativeInit(): Boolean

    external fun nativeShutdown()

    external fun nativePollStylusState(outState8: FloatArray, outHistoryFlat6: FloatArray?): Int

    external fun nativeGetDetectedDevices(): Array<String>?

    external fun nativeInjectTestEvent(
        toolType: Int,
        x: Float,
        y: Float,
        pressure: Float,
        tiltX: Float,
        tiltY: Float,
        buttonMask: Int,
        timestampMs: Double
    )
}
