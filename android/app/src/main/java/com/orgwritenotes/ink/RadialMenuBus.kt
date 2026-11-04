package com.orgwritenotes.ink

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/** A request to open the radial pen menu at a window position, raised by [source]. */
class RadialMenuRequest(val source: WriteCanvasBlockView, val xWindow: Float, val yWindow: Float)

/**
 * Carries radial-menu requests from a canvas (stylus barrel button held while hovering) to the
 * Compose host that owns that canvas' tool state; the host whose canvas matches shows the menu.
 */
object RadialMenuBus {
    var request by mutableStateOf<RadialMenuRequest?>(null)
        private set

    fun open(source: WriteCanvasBlockView, xWindow: Float, yWindow: Float) {
        request = RadialMenuRequest(source, xWindow, yWindow)
    }

    fun dismiss() {
        request = null
    }
}
