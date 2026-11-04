package com.orgwritenotes.ui

import android.view.KeyEvent
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import com.orgwritenotes.ink.WriteCanvasBlockView
import com.orgwritenotes.sync.EmacsSyncClient
import kotlin.math.abs

/** Sidebar tabs, in display order. */
object SidebarTab {
    const val HISTORY = 0
    const val OUTLINE = 1
    const val AGENDA = 2
    const val SEARCH = 3
    const val GALLERY = 4
}

/**
 * Screen-level UI state for [OrgWriteNotesTabletScreen], hoisted out of the composable so the
 * sidebar, rail, command palette and keyboard shortcuts share one source of truth, and so state
 * changes only recompose the parts that read them.
 *
 * Data (nodes, snapshots, connection) stays in [EmacsSyncClient]'s StateFlows. A plain state
 * holder is used rather than an androidx ViewModel: MainActivity handles all config changes itself,
 * so nothing is recreated on rotation, and a ViewModel would outlive the activity-scoped sync client.
 */
@Stable
class TabletUiState(private val syncClient: EmacsSyncClient) {
    var activeTab by mutableIntStateOf(SidebarTab.HISTORY)
        private set
    var searchQuery by mutableStateOf("")
    var showNewNodeDialog by mutableStateOf(false)
    var showPairingDialog by mutableStateOf(false)
    var showCommandPalette by mutableStateOf(false)
    var showConflictDialog by mutableStateOf(false)
    var isDrawerOpen by mutableStateOf(false)
    var isPinnedLandscape by mutableStateOf(true)
    var isPinnedPortrait by mutableStateOf(false)
    var zoomedInkBlockFileName by mutableStateOf<String?>(null)
    var zoomedSourceView by mutableStateOf<WriteCanvasBlockView?>(null)

    fun selectTab(tab: Int) {
        activeTab = tab
        when (tab) {
            SidebarTab.OUTLINE -> syncClient.fetchBufferOutline()
            SidebarTab.AGENDA -> syncClient.fetchAgendaNodes()
            SidebarTab.SEARCH -> if (searchQuery.trim().length >= 2) syncClient.searchNodes(searchQuery.trim())
            SidebarTab.GALLERY -> syncClient.fetchGallery()
        }
    }

    /** Switches to [tab] and opens the overlay drawer (used by the focus-mode rail and shortcuts). */
    fun openTabInDrawer(tab: Int) {
        selectTab(tab)
        isDrawerOpen = true
    }

    // --- Heading history: back / forward through the nodes shown in the editor -------------------

    private val visited = mutableStateListOf<String>()
    private var cursor by mutableIntStateOf(-1)
    private var pendingNav: String? = null

    val canGoBack: Boolean get() = cursor > 0
    val canGoForward: Boolean get() = cursor in 0 until visited.lastIndex

    /** Records [nodeId] as shown; a node reached by [goBack]/[goForward] doesn't truncate the stack. */
    fun onNodeShown(nodeId: String) {
        if (nodeId == pendingNav) {
            pendingNav = null
            return
        }
        pendingNav = null
        if (visited.getOrNull(cursor) == nodeId) return
        while (visited.lastIndex > cursor) visited.removeAt(visited.lastIndex)
        visited.add(nodeId)
        if (visited.size > MAX_HISTORY) visited.removeAt(0)
        cursor = visited.lastIndex
    }

    fun goBack() {
        if (!canGoBack) return
        cursor -= 1
        navigateToCursor()
    }

    fun goForward() {
        if (!canGoForward) return
        cursor += 1
        navigateToCursor()
    }

    private fun navigateToCursor() {
        val target = visited[cursor]
        pendingNav = target
        syncClient.fetchNode(target)
    }

    // --- Hardware keyboard shortcuts --------------------------------------------------------------

    private var chordStage = 0
    private var chordAt = 0L

    /**
     * Handles a hardware key event; returns true when consumed. Mirrors the Emacs `C-c w` prefix
     * (C-c w f flag flashcard, C-c w q/t pairing, C-c w w menu = command palette) plus direct keys:
     * Ctrl+K palette, Ctrl+\ focus mode, Alt+←/→ heading history, Ctrl+1..5 sidebar tabs,
     * Ctrl+F search, Ctrl+N new heading, Ctrl+T cycle TODO, Ctrl+Shift+C clock in/out.
     */
    fun handleKey(event: KeyEvent, actions: ShortcutActions): Boolean {
        if (event.action != KeyEvent.ACTION_DOWN) return false
        val now = event.eventTime
        if (chordStage > 0 && now - chordAt > CHORD_TIMEOUT_MS) chordStage = 0
        val code = event.keyCode
        if (isModifierKey(code)) return false

        when (chordStage) {
            1 -> {
                chordStage = 0
                if (code == KeyEvent.KEYCODE_W && !event.isCtrlPressed && !event.isAltPressed) {
                    chordStage = 2
                    chordAt = now
                    return true
                }
            }
            2 -> {
                chordStage = 0
                when (code) {
                    KeyEvent.KEYCODE_F -> actions.flagFlashcard()
                    KeyEvent.KEYCODE_Q, KeyEvent.KEYCODE_T -> showPairingDialog = true
                    KeyEvent.KEYCODE_W, KeyEvent.KEYCODE_M -> showCommandPalette = true
                    KeyEvent.KEYCODE_P -> actions.refresh()
                    else -> return false
                }
                return true
            }
        }

        if (event.isCtrlPressed && !event.isShiftPressed && code == KeyEvent.KEYCODE_C) {
            // Don't consume: Ctrl+C still copies in a focused text field.
            chordStage = 1
            chordAt = now
            return false
        }

        if (event.isAltPressed && !event.isCtrlPressed) {
            when (code) {
                KeyEvent.KEYCODE_DPAD_LEFT -> { goBack(); return true }
                KeyEvent.KEYCODE_DPAD_RIGHT -> { goForward(); return true }
            }
        }
        if (!event.isCtrlPressed) {
            if (code == KeyEvent.KEYCODE_ESCAPE && showCommandPalette) {
                showCommandPalette = false
                return true
            }
            return false
        }
        when (code) {
            KeyEvent.KEYCODE_K -> showCommandPalette = !showCommandPalette
            KeyEvent.KEYCODE_BACKSLASH -> actions.toggleFocusMode()
            KeyEvent.KEYCODE_1 -> openTabInDrawer(SidebarTab.HISTORY)
            KeyEvent.KEYCODE_2 -> openTabInDrawer(SidebarTab.OUTLINE)
            KeyEvent.KEYCODE_3 -> openTabInDrawer(SidebarTab.AGENDA)
            KeyEvent.KEYCODE_4, KeyEvent.KEYCODE_F -> openTabInDrawer(SidebarTab.SEARCH)
            KeyEvent.KEYCODE_5 -> openTabInDrawer(SidebarTab.GALLERY)
            KeyEvent.KEYCODE_N -> showNewNodeDialog = true
            KeyEvent.KEYCODE_T -> actions.cycleTodo()
            KeyEvent.KEYCODE_C -> if (event.isShiftPressed) actions.toggleClock() else return false
            else -> return false
        }
        return true
    }

    private fun isModifierKey(code: Int): Boolean = code == KeyEvent.KEYCODE_CTRL_LEFT ||
        code == KeyEvent.KEYCODE_CTRL_RIGHT || code == KeyEvent.KEYCODE_SHIFT_LEFT ||
        code == KeyEvent.KEYCODE_SHIFT_RIGHT || code == KeyEvent.KEYCODE_ALT_LEFT ||
        code == KeyEvent.KEYCODE_ALT_RIGHT || code == KeyEvent.KEYCODE_META_LEFT ||
        code == KeyEvent.KEYCODE_META_RIGHT

    companion object {
        private const val MAX_HISTORY = 50
        private const val CHORD_TIMEOUT_MS = 1500L
    }
}

/** Node-level actions the shortcuts and command palette can trigger. */
class ShortcutActions(
    val toggleFocusMode: () -> Unit,
    val cycleTodo: () -> Unit,
    val toggleClock: () -> Unit,
    val flagFlashcard: () -> Unit,
    val refresh: () -> Unit
)

/** Activity-level hook: MainActivity forwards hardware key events here before normal dispatch. */
object TabletKeyDispatcher {
    var handler: ((KeyEvent) -> Boolean)? = null
}

/**
 * Detects a quick horizontal one-finger swipe without consuming it, so scrolling and the ink
 * canvases underneath keep working. Stylus and multi-finger gestures are ignored.
 * A swipe to the right calls [onSwipeRight] (history back), to the left [onSwipeLeft] (forward).
 */
internal fun Modifier.horizontalSwipeNav(
    enabled: Boolean,
    onSwipeRight: () -> Unit,
    onSwipeLeft: () -> Unit
): Modifier = if (!enabled) this else composed {
    val right by rememberUpdatedState(onSwipeRight)
    val left by rememberUpdatedState(onSwipeLeft)
    pointerInput(Unit) {
        val minDistance = 140.dp.toPx()
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
            if (down.type != PointerType.Touch) return@awaitEachGesture
            var multiTouch = false
            var last = down.position
            var lastTime = down.uptimeMillis
            while (true) {
                val event = awaitPointerEvent(PointerEventPass.Initial)
                if (event.changes.size > 1) multiTouch = true
                val change = event.changes.firstOrNull { it.id == down.id } ?: break
                last = change.position
                lastTime = change.uptimeMillis
                if (!change.pressed) break
            }
            val dx = last.x - down.position.x
            val dy = last.y - down.position.y
            if (!multiTouch && lastTime - down.uptimeMillis < 700 && abs(dx) > minDistance && abs(dx) > 2.5f * abs(dy)) {
                if (dx > 0) right() else left()
            }
        }
    }
}
