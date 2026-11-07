@file:Suppress("ktlint")

package com.orgwritenotes.ink

import android.graphics.Rect
import android.view.View

/**
 * Desktop Linux implementation of `OnyxEInkBridge`.
 * Allows high-contrast E-Ink / monochrome mode to be toggled via `--eink` or
 * `ORG_WRITENOTES_EINK=1` for Linux E-Ink tablets (e.g. PineNote).
 */
object OnyxEInkBridge {
    @Volatile
    var forceEInkMode: Boolean = System.getenv("ORG_WRITENOTES_EINK") == "1"

    val isEInkDevice: Boolean
        get() = forceEInkMode

    fun enterFastPanMode(view: View) {}

    fun exitFastPanModeAndRefresh(view: View) {
        view.invalidate()
    }

    fun requestCleanRefresh(view: View) {
        view.invalidate()
    }

    interface RawStrokeListener {
        fun onRawStrokeBegin(screenX: Float, screenY: Float, pressure: Float, tiltX: Float, tiltY: Float, timestampMs: Long)
        fun onRawStrokeMove(screenX: Float, screenY: Float, pressure: Float, tiltX: Float, tiltY: Float, timestampMs: Long)
        fun onRawStrokeEnd()
        fun onRawEraseBegin(screenX: Float, screenY: Float, timestampMs: Long)
        fun onRawEraseMove(screenX: Float, screenY: Float, timestampMs: Long)
        fun onRawEraseEnd()
    }

    class OnyxRawTouchSession private constructor() : AutoCloseable {
        var isRawDrawingActive: Boolean = false
            private set

        fun updateLimitRect(view: View, excludeRects: List<Rect> = emptyList()) {}
        fun applyLimitRect(rect: Rect, excludeRects: List<Rect>) {}
        fun configureStroke(widthPx: Float, colorArgb: Int) {}
        fun setRawDrawingEnabled(enabled: Boolean, renderToEpd: Boolean = true) {
            isRawDrawingActive = false
        }
        fun release() {
            isRawDrawingActive = false
        }
        override fun close() = release()

        companion object {
            fun createIfSupported(view: View, listener: RawStrokeListener): OnyxRawTouchSession? = null
        }
    }
}

