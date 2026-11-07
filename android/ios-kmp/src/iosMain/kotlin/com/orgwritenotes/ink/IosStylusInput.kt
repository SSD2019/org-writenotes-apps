package com.orgwritenotes.ink

import kotlin.math.PI
import kotlin.math.max
import kotlin.math.min

/**
 * Shared Kotlin Multiplatform Apple Pencil physics & gesture translator for iPadOS (`iosMain`).
 * Converts UIKit `UITouch` force, altitudeAngle, azimuthUnitVector, and Apple Pencil Pro `rollAngle`
 * into the normalized pressure, tiltX, tiltY, and chiselAngle values used by `WriteCanvasView`.
 */
object IosStylusInput {

    fun computeTiltDegrees(altitudeRad: Double, azimuthDx: Double, azimuthDy: Double): Pair<Float, Float> {
        val clampedAlt = max(0.0, min(PI / 2.0, altitudeRad))
        val zenithDeg = ((PI / 2.0 - clampedAlt) * 180.0 / PI).toFloat()
        val tx = max(-90f, min(90f, zenithDeg * azimuthDx.toFloat()))
        val ty = max(-90f, min(90f, zenithDeg * azimuthDy.toFloat()))
        return Pair(tx, ty)
    }

    fun normalizePressure(force: Double, maxForce: Double): Float {
        if (maxForce <= 0.01 || force <= 0.0) {
            return 0.55f // Apple Pencil USB-C fallback
        }
        val comfortableMax = max(0.5, maxForce * 0.65)
        val norm = (force / comfortableMax).toFloat()
        return max(0.02f, min(1.0f, norm))
    }

    fun normalizeRollAngleDegrees(rollRadians: Double): Float {
        val deg = ((rollRadians * 180.0 / PI) % 180.0).toFloat()
        return if (deg < 0f) deg + 180f else deg
    }
}

