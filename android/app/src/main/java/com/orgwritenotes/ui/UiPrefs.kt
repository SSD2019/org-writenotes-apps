package com.orgwritenotes.ui

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/** Where the sketch toolbar sits; FLOATING is a draggable, collapsible palette. */
enum class ToolbarDock { TOP, BOTTOM, FLOATING }

/**
 * Persisted UI preferences, exposed as Compose state so changes re-render immediately.
 * B8: [fontMultiplier] is applied on top of the system font scale (see `MainActivity`).
 */
object UiPrefs {
    private const val PREFS = "org_writenotes_prefs"
    private const val KEY = "ui_font_multiplier"
    private const val KEY_FOCUS = "ui_focus_mode"
    private const val KEY_LEFT_HANDED = "ui_left_handed"
    private const val KEY_DOCK = "ui_toolbar_dock"
    private const val KEY_FLOAT_X = "ui_toolbar_float_x"
    private const val KEY_FLOAT_Y = "ui_toolbar_float_y"
    private const val KEY_FLOAT_COLLAPSED = "ui_toolbar_float_collapsed"
    const val MIN = 0.85f
    const val MAX = 1.6f

    /** Compose state: reading it in composition re-renders when the user changes the size. */
    var fontMultiplier by mutableFloatStateOf(1f)
        private set

    /** Focus mode: sidebar collapses to an icon rail and finger swipes walk the heading history. */
    var focusMode by mutableStateOf(false)
        private set

    /** Left-handed mode: sidebar, rail and floating toolbar move to the right-hand side. */
    var leftHanded by mutableStateOf(false)
        private set

    var toolbarDock by mutableStateOf(ToolbarDock.TOP)
        private set

    /** Floating toolbar position in dp from the top-left (mirrored when left-handed), and collapsed state. */
    var floatingToolbarX by mutableFloatStateOf(16f)
        private set
    var floatingToolbarY by mutableFloatStateOf(96f)
        private set
    var floatingToolbarCollapsed by mutableStateOf(false)
        private set

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun load(context: Context) {
        val p = prefs(context)
        fontMultiplier = p.getFloat(KEY, 1f).coerceIn(MIN, MAX)
        focusMode = p.getBoolean(KEY_FOCUS, false)
        leftHanded = p.getBoolean(KEY_LEFT_HANDED, false)
        toolbarDock = runCatching { ToolbarDock.valueOf(p.getString(KEY_DOCK, null) ?: "TOP") }.getOrDefault(ToolbarDock.TOP)
        floatingToolbarX = p.getFloat(KEY_FLOAT_X, 16f)
        floatingToolbarY = p.getFloat(KEY_FLOAT_Y, 96f)
        floatingToolbarCollapsed = p.getBoolean(KEY_FLOAT_COLLAPSED, false)
    }

    fun set(context: Context, value: Float) {
        fontMultiplier = value.coerceIn(MIN, MAX)
        prefs(context).edit().putFloat(KEY, fontMultiplier).apply()
    }

    fun setFocusMode(context: Context, value: Boolean) {
        focusMode = value
        prefs(context).edit().putBoolean(KEY_FOCUS, value).apply()
    }

    fun setLeftHanded(context: Context, value: Boolean) {
        leftHanded = value
        prefs(context).edit().putBoolean(KEY_LEFT_HANDED, value).apply()
    }

    fun setToolbarDock(context: Context, value: ToolbarDock) {
        toolbarDock = value
        prefs(context).edit().putString(KEY_DOCK, value.name).apply()
    }

    fun setFloatingToolbar(context: Context, xDp: Float, yDp: Float, collapsed: Boolean) {
        floatingToolbarX = xDp
        floatingToolbarY = yDp
        floatingToolbarCollapsed = collapsed
        prefs(context).edit()
            .putFloat(KEY_FLOAT_X, xDp)
            .putFloat(KEY_FLOAT_Y, yDp)
            .putBoolean(KEY_FLOAT_COLLAPSED, collapsed)
            .apply()
    }
}
