package com.orgwritenotes.ink

import android.content.Context
import kotlin.math.pow

/**
 * B6: user-tunable input shaping applied before points reach the native pen engine.
 * Values are process-wide and persisted in SharedPreferences.
 */
object StrokeTuning {
    private const val PREFS = "org_writenotes_prefs"

    /** 0.5 (soft: light pressure gives thicker ink) … 2.0 (firm). 1.0 = linear. */
    @Volatile var pressureGamma: Float = 1.0f

    /** 0 = raw input, up to 0.85 = heavy exponential smoothing. */
    @Volatile var smoothing: Float = 0.0f

    /** When false, stylus tilt is ignored (nib shape stays constant). */
    @Volatile var tiltEnabled: Boolean = true

    fun curve(pressure: Float): Float =
        pressure.coerceIn(0.0f, 1.0f).toDouble().pow(pressureGamma.toDouble()).toFloat().coerceIn(0.05f, 1.0f)

    fun tilt(tilt: Float): Float = if (tiltEnabled) tilt else 0f

    fun load(context: Context) {
        val p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        pressureGamma = p.getFloat("tune_pressure_gamma", 1.0f).coerceIn(0.5f, 2.0f)
        smoothing = p.getFloat("tune_smoothing", 0.0f).coerceIn(0.0f, 0.85f)
        tiltEnabled = p.getBoolean("tune_tilt", true)
    }

    fun save(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putFloat("tune_pressure_gamma", pressureGamma)
            .putFloat("tune_smoothing", smoothing)
            .putBoolean("tune_tilt", tiltEnabled)
            .apply()
    }

    /** Exponential moving-average smoother for one stroke. */
    class Smoother {
        private var sx = 0f
        private var sy = 0f

        fun reset(x: Float, y: Float) {
            sx = x
            sy = y
        }

        fun apply(x: Float, y: Float): Pair<Float, Float> {
            val a = smoothing
            if (a <= 0f) {
                sx = x
                sy = y
            } else {
                sx = sx * a + x * (1f - a)
                sy = sy * a + y * (1f - a)
            }
            return Pair(sx, sy)
        }
    }
}
