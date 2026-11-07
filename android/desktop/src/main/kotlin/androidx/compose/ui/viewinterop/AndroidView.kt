@file:Suppress("ktlint")

package androidx.compose.ui.viewinterop

import android.content.Context
import android.os.SystemClock
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isAltPressed
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.ui.input.pointer.isSecondaryPressed
import androidx.compose.ui.input.pointer.isTertiaryPressed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalContext
import com.orgwritenotes.ink.LinuxStylusBridge
import com.orgwritenotes.ink.WriteCanvasBlockView
import kotlin.math.roundToInt

/**
 * Desktop Compose implementation of `AndroidView` that hosts custom Android `View`s
 * (specifically `WriteCanvasBlockView`) inside Skiko/Compose Desktop with:
 * - Zero-copy Skia drawing via `android.graphics.Canvas`
 * - Hardware Wacom EMR / USI / AES stylus input (`LinuxStylusBridge` + Compose `pointerInput`)
 * - Stylus hover radial menu support (`ACTION_HOVER_ENTER`, `ACTION_HOVER_MOVE`, `ACTION_HOVER_EXIT`)
 * - Multi-touch pinch-zoom & two-finger pan (`ACTION_POINTER_DOWN`, `ACTION_POINTER_UP`)
 * - Desktop trackpad / mouse wheel pan and `Ctrl + Wheel` zoom
 * - Keyboard shortcuts (`Ctrl+Z`, `Ctrl+Y`, `Ctrl+C`, `Ctrl+V`, `1..5`, `E`, `L`, `Space`, `Del`, `Esc`)
 */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun <T : View> AndroidView(
    factory: (Context) -> T,
    modifier: Modifier = Modifier,
    update: (T) -> Unit = {},
    onRelease: (T) -> Unit = {}
) {
    val context = LocalContext.current
    val view = remember { factory(context) }
    val focusRequester = remember { FocusRequester() }
    var downTimeMs by remember { mutableStateOf(0L) }
    var isHovering by remember { mutableStateOf(false) }
    var spacePanHeld by remember { mutableStateOf(false) }
    var ctrlHeldState by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        LinuxStylusBridge.ensureInitialized()
    }

    DisposableEffect(view) {
        view.dispatchDesktopAttachedToWindow()
        onDispose {
            onRelease(view)
            view.dispatchDesktopDetachedFromWindow()
        }
    }

    // Re-run update callback whenever Compose recomposes
    update(view)

    Box(
        modifier = modifier
            .focusRequester(focusRequester)
            .focusable()
            .onGloballyPositioned { coords ->
                val pos = coords.positionInWindow()
                view.screenLocationX = pos.x.roundToInt()
                view.screenLocationY = pos.y.roundToInt()
                view.dispatchDesktopSizeChanged(coords.size.width, coords.size.height)
            }
            .onPreviewKeyEvent { composeKey ->
                val isDown = composeKey.type == KeyEventType.KeyDown
                ctrlHeldState = composeKey.isCtrlPressed || composeKey.isMetaPressed
                if (composeKey.key == Key.Spacebar) {
                    spacePanHeld = isDown
                }
                if (isDown) {
                    val mappedCode = mapComposeKeyToAndroidKeyCode(composeKey.key)
                    if (mappedCode != null) {
                        val androidKeyEvent = KeyEvent(
                            action = KeyEvent.ACTION_DOWN,
                            keyCode = mappedCode,
                            isCtrlPressed = composeKey.isCtrlPressed || composeKey.isMetaPressed,
                            isShiftPressed = composeKey.isShiftPressed,
                            isAltPressed = composeKey.isAltPressed,
                            isMetaPressed = composeKey.isMetaPressed
                        )
                        view.onKeyDown(mappedCode, androidKeyEvent)
                    } else {
                        false
                    }
                } else {
                    false
                }
            }
            .pointerInput(view) {
                awaitPointerEventScope {
                    var spacePanActive = false

                    while (true) {
                        val event = awaitPointerEvent()
                        val now = SystemClock.uptimeMillis()
                        val changes = event.changes
                        if (changes.isEmpty()) continue

                        // Handle Desktop Mouse Wheel / Trackpad two-finger scroll & Ctrl+Wheel zoom
                        if (event.type == PointerEventType.Scroll) {
                            val first = changes.first()
                            val scrollDelta = first.scrollDelta
                            if (view is WriteCanvasBlockView) {
                                if (ctrlHeldState) {
                                    val factor = if (scrollDelta.y < 0f) 1.12f else 0.89f
                                    view.zoomBy(factor)
                                } else {
                                    val dir = if (scrollDelta.y > 0f) 1 else -1
                                    view.pageScrollVertical(dir)
                                }
                            }
                            changes.forEach { it.consume() }
                            continue
                        }

                        val pressedChanges = changes.filter { it.pressed }
                        val anyPressed = pressedChanges.isNotEmpty()
                        val wasAnyPressed = changes.any { it.previousPressed }

                        // Space + drag pans the canvas on Desktop via FINGER tool in zoomed mode
                        if (spacePanHeld && (anyPressed || wasAnyPressed)) {
                            val active = if (anyPressed) pressedChanges else changes
                            val p = active.first().position
                            val rawFinger = listOf(
                                LinuxStylusBridge.RawPointerInput(
                                    id = 0,
                                    x = p.x,
                                    y = p.y,
                                    pressure = 0.5f,
                                    composePointerType = LinuxStylusBridge.PointerKind.TOUCH
                                )
                            )
                            val act = when {
                                !spacePanActive && anyPressed -> {
                                    spacePanActive = true
                                    downTimeMs = now
                                    MotionEvent.ACTION_DOWN
                                }
                                spacePanActive && anyPressed -> MotionEvent.ACTION_MOVE
                                else -> {
                                    spacePanActive = false
                                    MotionEvent.ACTION_UP
                                }
                            }
                            val ev = LinuxStylusBridge.buildMotionEvent(
                                action = act,
                                downTime = downTimeMs,
                                eventTime = now,
                                pointers = rawFinger,
                                defaultToolOverride = MotionEvent.TOOL_TYPE_FINGER
                            )
                            view.onTouchEvent(ev)
                            changes.forEach { it.consume() }
                            continue
                        } else {
                            spacePanActive = false
                        }

                        val secondaryPressed = event.buttons.isSecondaryPressed
                        val tertiaryPressed = event.buttons.isTertiaryPressed

                        // Convert active pointer changes to RawPointerInput
                        val activeChanges = if (anyPressed) pressedChanges else changes
                        val rawPointers = activeChanges.map { ch ->
                            LinuxStylusBridge.RawPointerInput(
                                id = ch.id.value.toInt(),
                                x = ch.position.x,
                                y = ch.position.y,
                                pressure = ch.pressure,
                                composePointerType = mapPointerType(ch.type)
                            )
                        }

                        // Collect coalesced historical samples for ultra-smooth 240Hz+ stylus strokes
                        val firstChange = activeChanges.first()
                        val historicalBatches = if (firstChange.historical.isNotEmpty()) {
                            firstChange.historical.map { hist ->
                                hist.uptimeMillis to listOf(
                                    LinuxStylusBridge.RawPointerInput(
                                        id = firstChange.id.value.toInt(),
                                        x = hist.position.x,
                                        y = hist.position.y,
                                        pressure = firstChange.pressure,
                                        composePointerType = mapPointerType(firstChange.type)
                                    )
                                )
                            }
                        } else {
                            emptyList()
                        }

                        when {
                            !wasAnyPressed && anyPressed -> {
                                if (isHovering) {
                                    isHovering = false
                                    val hoverExit = LinuxStylusBridge.buildMotionEvent(
                                        action = MotionEvent.ACTION_HOVER_EXIT,
                                        downTime = now,
                                        eventTime = now,
                                        pointers = rawPointers
                                    )
                                    view.onHoverEvent(hoverExit)
                                }
                                downTimeMs = now
                                focusRequester.requestFocus()
                                val downEv = LinuxStylusBridge.buildMotionEvent(
                                    action = MotionEvent.ACTION_DOWN,
                                    downTime = downTimeMs,
                                    eventTime = now,
                                    pointers = rawPointers,
                                    mouseSecondaryPressed = secondaryPressed,
                                    mouseTertiaryPressed = tertiaryPressed
                                )
                                view.onTouchEvent(downEv)
                                changes.forEach { it.consume() }
                            }

                            wasAnyPressed && anyPressed -> {
                                val newlyPressedIdx = activeChanges.indexOfFirst { it.pressed && !it.previousPressed }
                                if (newlyPressedIdx > 0) {
                                    val ptrDownEv = LinuxStylusBridge.buildMotionEvent(
                                        action = MotionEvent.ACTION_POINTER_DOWN,
                                        actionIndex = newlyPressedIdx,
                                        downTime = downTimeMs,
                                        eventTime = now,
                                        pointers = rawPointers,
                                        mouseSecondaryPressed = secondaryPressed,
                                        mouseTertiaryPressed = tertiaryPressed
                                    )
                                    view.onTouchEvent(ptrDownEv)
                                }

                                val moveEv = LinuxStylusBridge.buildMotionEvent(
                                    action = MotionEvent.ACTION_MOVE,
                                    downTime = downTimeMs,
                                    eventTime = now,
                                    pointers = rawPointers,
                                    historicalBatches = historicalBatches,
                                    mouseSecondaryPressed = secondaryPressed,
                                    mouseTertiaryPressed = tertiaryPressed
                                )
                                view.onTouchEvent(moveEv)
                                changes.forEach { it.consume() }
                            }

                            wasAnyPressed && !anyPressed -> {
                                val upEv = LinuxStylusBridge.buildMotionEvent(
                                    action = MotionEvent.ACTION_UP,
                                    downTime = downTimeMs,
                                    eventTime = now,
                                    pointers = rawPointers,
                                    mouseSecondaryPressed = secondaryPressed,
                                    mouseTertiaryPressed = tertiaryPressed
                                )
                                view.onTouchEvent(upEv)
                                changes.forEach { it.consume() }
                            }

                            else -> {
                                when (event.type) {
                                    PointerEventType.Enter -> {
                                        isHovering = true
                                        val enterEv = LinuxStylusBridge.buildMotionEvent(
                                            action = MotionEvent.ACTION_HOVER_ENTER,
                                            downTime = now,
                                            eventTime = now,
                                            pointers = rawPointers,
                                            mouseSecondaryPressed = secondaryPressed,
                                            mouseTertiaryPressed = tertiaryPressed
                                        )
                                        view.onHoverEvent(enterEv)
                                    }
                                    PointerEventType.Move -> {
                                        if (!isHovering) {
                                            isHovering = true
                                            val enterEv = LinuxStylusBridge.buildMotionEvent(
                                                action = MotionEvent.ACTION_HOVER_ENTER,
                                                downTime = now,
                                                eventTime = now,
                                                pointers = rawPointers,
                                                mouseSecondaryPressed = secondaryPressed,
                                                mouseTertiaryPressed = tertiaryPressed
                                            )
                                            view.onHoverEvent(enterEv)
                                        }
                                        val hoverEv = LinuxStylusBridge.buildMotionEvent(
                                            action = MotionEvent.ACTION_HOVER_MOVE,
                                            downTime = now,
                                            eventTime = now,
                                            pointers = rawPointers,
                                            mouseSecondaryPressed = secondaryPressed,
                                            mouseTertiaryPressed = tertiaryPressed
                                        )
                                        view.onHoverEvent(hoverEv)
                                    }
                                    PointerEventType.Exit -> {
                                        if (isHovering) {
                                            isHovering = false
                                            val exitEv = LinuxStylusBridge.buildMotionEvent(
                                                action = MotionEvent.ACTION_HOVER_EXIT,
                                                downTime = now,
                                                eventTime = now,
                                                pointers = rawPointers,
                                                mouseSecondaryPressed = secondaryPressed,
                                                mouseTertiaryPressed = tertiaryPressed
                                            )
                                            view.onHoverEvent(exitEv)
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            @Suppress("UNUSED_VARIABLE")
            val tick = view.invalidationCounter.intValue
            view.dispatchDesktopSizeChanged(size.width.roundToInt(), size.height.roundToInt())
            drawIntoCanvas { composeCanvas ->
                val androidCanvas = android.graphics.Canvas(this, composeCanvas)
                view.dispatchDesktopDraw(androidCanvas)
            }
        }
    }
}

fun mapPointerType(type: PointerType): LinuxStylusBridge.PointerKind {
    return when (type) {
        PointerType.Stylus -> LinuxStylusBridge.PointerKind.STYLUS
        PointerType.Eraser -> LinuxStylusBridge.PointerKind.ERASER
        PointerType.Touch -> LinuxStylusBridge.PointerKind.TOUCH
        else -> LinuxStylusBridge.PointerKind.MOUSE
    }
}

fun mapComposeKeyToAndroidKeyCode(key: Key): Int? {
    return when (key) {
        Key.Zero, Key.NumPad0 -> KeyEvent.KEYCODE_0
        Key.One, Key.NumPad1 -> KeyEvent.KEYCODE_1
        Key.Two, Key.NumPad2 -> KeyEvent.KEYCODE_2
        Key.Three, Key.NumPad3 -> KeyEvent.KEYCODE_3
        Key.Four, Key.NumPad4 -> KeyEvent.KEYCODE_4
        Key.Five, Key.NumPad5 -> KeyEvent.KEYCODE_5
        Key.DirectionLeft -> KeyEvent.KEYCODE_DPAD_LEFT
        Key.DirectionRight -> KeyEvent.KEYCODE_DPAD_RIGHT
        Key.A -> KeyEvent.KEYCODE_A
        Key.C -> KeyEvent.KEYCODE_C
        Key.E -> KeyEvent.KEYCODE_E
        Key.F -> KeyEvent.KEYCODE_F
        Key.K -> KeyEvent.KEYCODE_K
        Key.L -> KeyEvent.KEYCODE_L
        Key.M -> KeyEvent.KEYCODE_M
        Key.N -> KeyEvent.KEYCODE_N
        Key.P -> KeyEvent.KEYCODE_P
        Key.Q -> KeyEvent.KEYCODE_Q
        Key.T -> KeyEvent.KEYCODE_T
        Key.V -> KeyEvent.KEYCODE_V
        Key.W -> KeyEvent.KEYCODE_W
        Key.X -> KeyEvent.KEYCODE_X
        Key.Y -> KeyEvent.KEYCODE_Y
        Key.Z -> KeyEvent.KEYCODE_Z
        Key.Spacebar -> KeyEvent.KEYCODE_SPACE
        Key.Backspace -> KeyEvent.KEYCODE_DEL
        Key.Delete -> KeyEvent.KEYCODE_FORWARD_DEL
        Key.Escape -> KeyEvent.KEYCODE_ESCAPE
        Key.Minus, Key.NumPadSubtract -> KeyEvent.KEYCODE_MINUS
        Key.Equals, Key.NumPadAdd -> KeyEvent.KEYCODE_EQUALS
        Key.Backslash -> KeyEvent.KEYCODE_BACKSLASH
        else -> null
    }
}
