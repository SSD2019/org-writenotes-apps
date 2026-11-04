package com.orgwritenotes.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.SnapshotStateMap
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.orgwritenotes.ink.OnyxEInkBridge
import kotlin.math.roundToInt

private val FLOAT_MAX_WIDTH = 760.dp
private val COLLAPSED_SIZE = 48.dp

/**
 * Hosts the sketch toolbar as a floating palette over the canvas: drag it by its grip, collapse it
 * to a pen-colored button, tap that to expand. Position and collapsed state persist in [UiPrefs];
 * in left-handed mode the position is measured from the right edge so the palette mirrors.
 * Its window rect is reported into [overlayRects] so the Onyx pen layer never inks under it.
 */
@Composable
internal fun FloatingToolbarFrame(
    overlayRects: SnapshotStateMap<String, android.graphics.Rect>,
    selectedColor: Int,
    toolbar: @Composable () -> Unit
) {
    val context = LocalContext.current
    val density = LocalDensity.current
    val isEInk = OnyxEInkBridge.isEInkDevice
    val leftHanded = UiPrefs.leftHanded
    val collapsed = UiPrefs.floatingToolbarCollapsed

    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val maxW = maxWidth
        val maxH = maxHeight
        val frameW = if (collapsed) COLLAPSED_SIZE else minOf(FLOAT_MAX_WIDTH, maxW - 16.dp)
        var xDp by remember { mutableFloatStateOf(UiPrefs.floatingToolbarX) }
        var yDp by remember { mutableFloatStateOf(UiPrefs.floatingToolbarY) }
        val maxX = (maxW - frameW).value.coerceAtLeast(0f)
        val maxY = (maxH - COLLAPSED_SIZE).value.coerceAtLeast(0f)
        val clampedX = xDp.coerceIn(0f, maxX)
        val clampedY = yDp.coerceIn(0f, maxY)
        val bounds by rememberUpdatedState(maxX to maxY)

        val dragModifier = Modifier.pointerInput(leftHanded, collapsed) {
            detectDragGestures(
                onDragEnd = { UiPrefs.setFloatingToolbar(context, xDp, yDp, UiPrefs.floatingToolbarCollapsed) }
            ) { change, drag ->
                change.consume()
                val dx = with(density) { drag.x.toDp().value }
                val dy = with(density) { drag.y.toDp().value }
                xDp = (xDp + if (leftHanded) -dx else dx).coerceIn(0f, bounds.first)
                yDp = (yDp + dy).coerceIn(0f, bounds.second)
            }
        }

        val placement = Modifier
            .align(if (leftHanded) Alignment.TopEnd else Alignment.TopStart)
            .offset {
                val px = with(density) { clampedX.dp.roundToPx() }
                IntOffset(if (leftHanded) -px else px, with(density) { clampedY.dp.roundToPx() })
            }
            .trackWindowRect(overlayRects, "floatingToolbar")

        if (collapsed) {
            Box(
                contentAlignment = Alignment.Center,
                modifier = placement
                    .size(COLLAPSED_SIZE)
                    .clip(CircleShape)
                    .background(if (isEInk) Color.White else Color(0xEE222222))
                    .border(if (isEInk) 2.dp else 1.dp, if (isEInk) Color.Black else Color(0xFF38BDF8), CircleShape)
                    .then(dragModifier)
                    .clickable { UiPrefs.setFloatingToolbar(context, clampedX, clampedY, collapsed = false) }
                    .semantics { contentDescription = "Expand toolbar" }
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("✎", color = if (isEInk) Color.Black else Color.White, fontSize = 16.sp)
                    Box(
                        modifier = Modifier
                            .size(width = 18.dp, height = 4.dp)
                            .clip(RoundedCornerShape(2.dp))
                            .background(Color(selectedColor))
                    )
                }
            }
        } else {
            Column(
                modifier = placement
                    .widthIn(max = frameW)
                    .clip(RoundedCornerShape(10.dp))
                    .border(if (isEInk) 2.dp else 1.dp, if (isEInk) Color.Black else Color(0xFF475569), RoundedCornerShape(10.dp))
                    .background(if (isEInk) Color.White else Color(0xFF222222))
            ) {
                // Grip row: drag anywhere on it; "–" collapses the palette.
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(if (isEInk) Color(0xFFF1F5F9) else Color(0xFF2E2E2E))
                        .then(dragModifier)
                        .padding(horizontal = 10.dp, vertical = 2.dp)
                ) {
                    Text(
                        text = "⠿ drag",
                        color = if (isEInk) Color.Black else Color(0xFF94A3B8),
                        fontSize = 10.sp,
                        modifier = Modifier.semantics { contentDescription = "Drag toolbar" }
                    )
                    Spacer(modifier = Modifier.weight(1f))
                    Text(
                        text = "–",
                        color = if (isEInk) Color.Black else Color(0xFFE2E8F0),
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier
                            .clip(RoundedCornerShape(4.dp))
                            .clickable { UiPrefs.setFloatingToolbar(context, clampedX, clampedY, collapsed = true) }
                            .padding(horizontal = 10.dp)
                            .semantics { contentDescription = "Collapse toolbar" }
                    )
                }
                Box(modifier = Modifier.width(frameW)) { toolbar() }
            }
        }
    }
}
