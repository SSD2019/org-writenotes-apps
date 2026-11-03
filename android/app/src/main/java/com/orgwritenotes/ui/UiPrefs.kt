package com.orgwritenotes.ui

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.setValue

/**
 * B8: in-app text-size multiplier applied on top of the system font scale
 * (see `MainActivity`, which provides it through `LocalDensity`).
 */
object UiPrefs {
    private const val PREFS = "org_writenotes_prefs"
    private const val KEY = "ui_font_multiplier"
    const val MIN = 0.85f
    const val MAX = 1.6f

    /** Compose state: reading it in composition re-renders when the user changes the size. */
    var fontMultiplier by mutableFloatStateOf(1f)
        private set

    fun load(context: Context) {
        fontMultiplier = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getFloat(KEY, 1f).coerceIn(MIN, MAX)
    }

    fun set(context: Context, value: Float) {
        fontMultiplier = value.coerceIn(MIN, MAX)
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putFloat(KEY, fontMultiplier).apply()
    }
}

