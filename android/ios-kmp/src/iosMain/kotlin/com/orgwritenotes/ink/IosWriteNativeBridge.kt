@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.orgwritenotes.ink

import com.orgwritenotes.ink.cinterop.*
import kotlinx.cinterop.*

/**
 * Kotlin/Native (iPadOS `iosArm64` / `iosSimulatorArm64`) implementation of `WriteNativeBridge`
 * backed by the C++ `wn_session_*` C ABI (`write_bridge_c_api.h`).
 */
object IosWriteNativeBridge {

    fun createSession(width: Int, height: Int, xRuling: Int = 0, yRuling: Int = 40): Long =
        wn_session_create(width, height, xRuling, yRuling)

    fun retainSession(handle: Long) {
        if (handle != 0L) wn_session_retain(handle)
    }

    fun destroySession(handle: Long) {
        if (handle != 0L) wn_session_destroy(handle)
    }

    fun loadSvgz(handle: Long, svgzBytes: ByteArray): Boolean {
        if (handle == 0L || svgzBytes.isEmpty()) return false
        return svgzBytes.usePinned { pinned ->
            wn_session_load_svgz(handle, pinned.addressOf(0).reinterpret(), svgzBytes.size)
        }
    }

    fun exportSvgz(handle: Long): ByteArray {
        if (handle == 0L) return ByteArray(0)
        val size = wn_session_export_svgz(handle, null, 0)
        if (size <= 0) return ByteArray(0)
        val out = ByteArray(size)
        out.usePinned { pinned ->
            wn_session_export_svgz(handle, pinned.addressOf(0).reinterpret(), size)
        }
        return out
    }

    fun renderRgba(handle: Long, rgbaOut: ByteArray, width: Int, height: Int, originX: Float = 0f, originY: Float = 0f): Boolean {
        if (handle == 0L || rgbaOut.size < width * height * 4) return false
        return rgbaOut.usePinned { pinned ->
            wn_session_render_rgba(handle, pinned.addressOf(0).reinterpret(), width, height, originX, originY)
        }
    }

    fun setPen(
        handle: Long,
        colorArgb: Int,
        strokeWidth: Float,
        pressureSensitive: Boolean = true,
        tipType: Int = 1,
        snapToGrid: Boolean = false,
        lineDrawing: Boolean = false,
        chiselAngle: Float = 45f
    ) {
        if (handle != 0L) {
            wn_session_set_pen(handle, colorArgb, strokeWidth, pressureSensitive, tipType, snapToGrid, lineDrawing, chiselAngle)
        }
    }

    fun beginStroke(handle: Long, x: Float, y: Float, pressure: Float, tiltX: Float, tiltY: Float, timestampMs: Double) {
        if (handle != 0L) wn_session_begin_stroke(handle, x, y, pressure, tiltX, tiltY, timestampMs)
    }

    fun moveStroke(handle: Long, x: Float, y: Float, pressure: Float, tiltX: Float, tiltY: Float, timestampMs: Double) {
        if (handle != 0L) wn_session_move_stroke(handle, x, y, pressure, tiltX, tiltY, timestampMs)
    }

    fun moveStrokeBatch(handle: Long, pointsData6: FloatArray, pointCount: Int) {
        if (handle == 0L || pointCount <= 0 || pointsData6.size < pointCount * 6) return
        pointsData6.usePinned { pinned ->
            wn_session_move_stroke_batch(handle, pinned.addressOf(0), pointCount)
        }
    }

    fun endStroke(handle: Long, outDimsAndDirty6: IntArray) {
        if (handle == 0L || outDimsAndDirty6.size < 6) return
        outDimsAndDirty6.usePinned { pinned ->
            wn_session_end_stroke(handle, pinned.addressOf(0))
        }
    }

    fun undo(handle: Long, outNewDims2: IntArray): Boolean {
        if (handle == 0L || outNewDims2.size < 2) return false
        return outNewDims2.usePinned { pinned ->
            wn_session_undo(handle, pinned.addressOf(0))
        }
    }

    fun redo(handle: Long, outNewDims2: IntArray): Boolean {
        if (handle == 0L || outNewDims2.size < 2) return false
        return outNewDims2.usePinned { pinned ->
            wn_session_redo(handle, pinned.addressOf(0))
        }
    }
}

