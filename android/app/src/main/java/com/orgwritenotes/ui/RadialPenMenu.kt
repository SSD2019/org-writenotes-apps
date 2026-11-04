package com.orgwritenotes.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties
import com.orgwritenotes.ink.OnyxEInkBridge
import com.orgwritenotes.ink.RadialMenuBus
import com.orgwritenotes.ink.StylusToolMode
import com.orgwritenotes.ink.WriteCanvasBlockView
import com.orgwritenotes.ink.WritePenTip
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

private val INNER_RADIUS = 72.dp
private val OUTER_RADIUS = 140.dp
private val ITEM_SIZE = 54.dp
private val MENU_SIZE = (OUTER_RADIUS + ITEM_SIZE) * 2

/** Centers the popup on a window point, kept inside the window. */
private class CenteredAt(private val x: Int, private val y: Int) : PopupPositionProvider {
    override fun calculatePosition(
        anchorBounds: IntRect,
        windowSize: IntSize,
        layoutDirection: LayoutDirection,
        popupContentSize: IntSize
    ): IntOffset = IntOffset(
        (x - popupContentSize.width / 2).coerceIn(0, (windowSize.width - popupContentSize.width).coerceAtLeast(0)),
        (y - popupContentSize.height / 2).coerceIn(0, (windowSize.height - popupContentSize.height).coerceAtLeast(0))
    )
}

private class RadialItem(
    val label: String,
    val description: String,
    val dotColor: Color? = null,
    val active: Boolean = false,
    val keepOpen: Boolean = false,
    val onClick: () -> Unit
)

/**
 * Radial pen menu at the pen tip, opened by holding the stylus barrel button while hovering.
 * Inner ring: Pen, Eraser, Lasso, Undo, Redo. Outer ring: up to six favorite pens.
 * Each canvas host places one; it shows only for requests whose canvas [accepts] returns true,
 * and routes tool changes through the host's own [onSelectToolMode] so its toolbar stays in sync.
 */
@Composable
internal fun RadialPenMenuHost(
    accepts: (WriteCanvasBlockView) -> Boolean,
    activeMode: StylusToolMode,
    selectedColor: Int,
    selectedWidth: Float,
    selectedPenTip: WritePenTip,
    lastEraserMode: StylusToolMode,
    lastSelectMode: StylusToolMode,
    onSelectToolMode: (StylusToolMode, Int, Float, WritePenTip, StylusToolMode?, StylusToolMode?, StylusToolMode?) -> Unit
) {
    val request = RadialMenuBus.request ?: return
    if (!accepts(request.source)) return
    val source = request.source
    val context = LocalContext.current
    val isEInk = OnyxEInkBridge.isEInkDevice

    DisposableEffect(source) {
        source.radialMenuOpen = true
        onDispose { source.radialMenuOpen = false }
    }

    val customPens = remember { WritePenPresetStorage.loadCustomPens(context) }
    val favorites = (WRITE_SAVED_PENS + customPens).take(6)

    fun select(mode: StylusToolMode, color: Int = selectedColor, width: Float = selectedWidth, tip: WritePenTip = selectedPenTip) {
        onSelectToolMode(mode, color, width, tip, null, null, null)
    }

    val isEraser = activeMode == StylusToolMode.ERASER || activeMode == StylusToolMode.ERASER_RULED ||
        activeMode == StylusToolMode.ERASER_FREE
    val isSelect = activeMode == StylusToolMode.SELECT_LASSO || activeMode == StylusToolMode.SELECT_RECT ||
        activeMode == StylusToolMode.SELECT_RULED
    val inner = listOf(
        RadialItem("Pen", "Pen", dotColor = Color(selectedColor), active = activeMode == StylusToolMode.PEN) { select(StylusToolMode.PEN) },
        RadialItem("Erase", "Eraser", active = isEraser) { select(lastEraserMode) },
        RadialItem("Lasso", "Lasso select", active = isSelect) {
            select(if (lastSelectMode == StylusToolMode.SELECT_RECT || lastSelectMode == StylusToolMode.SELECT_RULED) lastSelectMode else StylusToolMode.SELECT_LASSO)
        },
        RadialItem("Undo", "Undo", keepOpen = true) { source.performUndo() },
        RadialItem("Redo", "Redo", keepOpen = true) { source.performRedo() }
    )
    val outer = favorites.map { p ->
        val isActive = if (p.isHighlighter) {
            activeMode == StylusToolMode.HIGHLIGHTER
        } else {
            activeMode == StylusToolMode.PEN && selectedColor == p.colorArgb && selectedWidth == p.widthPx && selectedPenTip == p.tip
        }
        RadialItem(
            label = p.name.split(' ').take(2).joinToString("\n"),
            description = "Favorite pen ${p.name}",
            dotColor = Color(p.colorArgb),
            active = isActive
        ) {
            select(if (p.isHighlighter) StylusToolMode.HIGHLIGHTER else StylusToolMode.PEN, p.colorArgb, p.widthPx, p.tip)
        }
    }

    Popup(
        popupPositionProvider = remember(request) { CenteredAt(request.xWindow.roundToInt(), request.yWindow.roundToInt()) },
        onDismissRequest = { RadialMenuBus.dismiss() },
        properties = PopupProperties(focusable = true, dismissOnClickOutside = true, dismissOnBackPress = true)
    ) {
        Box(modifier = Modifier.size(MENU_SIZE), contentAlignment = Alignment.Center) {
            // Faint disc behind the rings so items read against busy ink (plain white on E-Ink).
            Box(
                modifier = Modifier
                    .size((OUTER_RADIUS + ITEM_SIZE / 2) * 2)
                    .clip(CircleShape)
                    .background(if (isEInk) Color.White.copy(alpha = 0.9f) else Color(0xCC0F172A))
                    .then(if (isEInk) Modifier.border(1.dp, Color.Black, CircleShape) else Modifier)
            )
            RadialRing(inner, INNER_RADIUS, isEInk, startDeg = -90.0)
            RadialRing(outer, OUTER_RADIUS, isEInk, startDeg = -90.0 + 30.0)
            RadialButton(
                item = RadialItem("✕", "Close menu") { },
                isEInk = isEInk,
                size = 40.dp,
                modifier = Modifier
            )
        }
    }
}

@Composable
private fun RadialRing(items: List<RadialItem>, radius: Dp, isEInk: Boolean, startDeg: Double) {
    if (items.isEmpty()) return
    val step = 360.0 / items.size
    items.forEachIndexed { i, item ->
        val a = (startDeg + i * step) * PI / 180.0
        RadialButton(
            item = item,
            isEInk = isEInk,
            size = ITEM_SIZE,
            modifier = Modifier.offset(x = radius * cos(a).toFloat(), y = radius * sin(a).toFloat())
        )
    }
}

@Composable
private fun RadialButton(item: RadialItem, isEInk: Boolean, size: Dp, modifier: Modifier) {
    val bg = when {
        item.active -> if (isEInk) Color.Black else Color(0xFF0284C7)
        else -> if (isEInk) Color.White else Color(0xFF1E293B)
    }
    val fg = if (item.active) Color.White else if (isEInk) Color.Black else Color(0xFFF1F5F9)
    Box(
        contentAlignment = Alignment.Center,
        modifier = modifier
            .size(size)
            .clip(CircleShape)
            .background(bg)
            .border(if (isEInk) 1.5.dp else 1.dp, if (isEInk) Color.Black else Color(0xFF475569), CircleShape)
            .clickable {
                item.onClick()
                if (!item.keepOpen) RadialMenuBus.dismiss()
            }
            .semantics { contentDescription = item.description }
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            item.dotColor?.let {
                Box(
                    modifier = Modifier
                        .size(10.dp)
                        .clip(CircleShape)
                        .background(it)
                        .border(1.dp, if (isEInk || item.active) Color.White else Color.Black, CircleShape)
                )
            }
            Text(
                text = item.label,
                color = fg,
                fontSize = 10.sp,
                lineHeight = 11.sp,
                fontWeight = if (item.active) FontWeight.Bold else FontWeight.SemiBold,
                textAlign = TextAlign.Center
            )
        }
    }
}
