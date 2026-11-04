package com.orgwritenotes.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.orgwritenotes.ink.OnyxEInkBridge
import com.orgwritenotes.model.OrgNodeSummary
import kotlinx.coroutines.delay

/** An action offered by the command palette; [hint] shows its keyboard shortcut. */
class PaletteAction(val title: String, val hint: String = "", val run: () -> Unit)

private sealed class PaletteEntry {
    abstract val label: String
    class Action(val action: PaletteAction) : PaletteEntry() {
        override val label get() = action.title
    }
    class Heading(val node: OrgNodeSummary) : PaletteEntry() {
        override val label get() = node.title
    }
}

/**
 * Fuzzy subsequence score of [query] in [text] (higher is better), or null when not every query
 * character occurs in order. Rewards consecutive runs and matches at word starts.
 */
internal fun fuzzyScore(query: String, text: String): Int? {
    if (query.isEmpty()) return 0
    val q = query.lowercase()
    val t = text.lowercase()
    var qi = 0
    var score = 0
    var run = 0
    for (ti in t.indices) {
        if (qi < q.length && t[ti] == q[qi]) {
            run += 1
            score += 1 + run * 2
            if (ti == 0 || !t[ti - 1].isLetterOrDigit()) score += 6
            qi += 1
        } else {
            run = 0
        }
    }
    if (qi < q.length) return null
    return score - t.length / 8
}

/**
 * Ctrl+K command palette: one fuzzy box over actions and Org headings. Headings come from the
 * outline, history, agenda and (for 2+ characters) the Emacs full-text search via [onRemoteSearch].
 */
@Composable
internal fun CommandPaletteDialog(
    actions: List<PaletteAction>,
    headings: List<OrgNodeSummary>,
    onRemoteSearch: (String) -> Unit,
    onPickHeading: (OrgNodeSummary) -> Unit,
    onDismiss: () -> Unit
) {
    val isEInk = OnyxEInkBridge.isEInkDevice
    var query by remember { mutableStateOf("") }
    var selected by remember { mutableIntStateOf(0) }
    val focusRequester = remember { FocusRequester() }
    val listState = rememberLazyListState()

    LaunchedEffect(Unit) { focusRequester.requestFocus() }
    LaunchedEffect(query) {
        if (query.trim().length >= 2) {
            delay(250L)
            onRemoteSearch(query.trim())
        }
    }

    val entries = remember(query, actions, headings) {
        val q = query.trim()
        val scored = actions.mapNotNull { a -> fuzzyScore(q, a.title)?.let { PaletteEntry.Action(a) to it + 12 } } +
            headings.distinctBy { it.nodeId }.mapNotNull { n ->
                val best = listOfNotNull(
                    fuzzyScore(q, n.title),
                    n.tags.mapNotNull { fuzzyScore(q, it) }.maxOrNull()?.minus(2)
                ).maxOrNull()
                best?.let { PaletteEntry.Heading(n) to it }
            }
        // Empty query: actions first, then headings in their natural order.
        (if (q.isEmpty()) scored else scored.sortedByDescending { it.second }).map { it.first }.take(60)
    }
    LaunchedEffect(entries) { selected = 0 }
    LaunchedEffect(selected) { if (entries.isNotEmpty()) listState.animateScrollToItem(selected.coerceIn(0, entries.lastIndex)) }

    fun run(entry: PaletteEntry) {
        onDismiss()
        when (entry) {
            is PaletteEntry.Action -> entry.action.run()
            is PaletteEntry.Heading -> onPickHeading(entry.node)
        }
    }

    val bg = if (isEInk) Color.White else Color(0xFF1E293B)
    val fg = if (isEInk) Color.Black else Color(0xFFF1F5F9)
    val muted = if (isEInk) Color(0xFF334155) else Color(0xFF94A3B8)
    val selBg = if (isEInk) Color.Black else Color(0xFF0284C7)

    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Column(
            modifier = Modifier
                .widthIn(max = 640.dp)
                .fillMaxWidth(0.9f)
                .clip(RoundedCornerShape(12.dp))
                .background(bg)
                .border(if (isEInk) 2.dp else 1.dp, if (isEInk) Color.Black else Color(0xFF334155), RoundedCornerShape(12.dp))
                .padding(12.dp)
        ) {
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                singleLine = true,
                placeholder = { Text("Type a command or heading…", color = muted, fontSize = 13.sp) },
                modifier = Modifier
                    .fillMaxWidth()
                    .focusRequester(focusRequester)
                    .onPreviewKeyEvent { e ->
                        if (e.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                        when (e.key) {
                            Key.DirectionDown -> { if (entries.isNotEmpty()) selected = (selected + 1) % entries.size; true }
                            Key.DirectionUp -> { if (entries.isNotEmpty()) selected = (selected - 1 + entries.size) % entries.size; true }
                            Key.Enter, Key.NumPadEnter -> { entries.getOrNull(selected)?.let { run(it) }; true }
                            Key.Escape -> { onDismiss(); true }
                            else -> false
                        }
                    }
            )
            Spacer(modifier = Modifier.padding(top = 8.dp))
            if (entries.isEmpty()) {
                Text("No matches", color = muted, fontSize = 12.sp, modifier = Modifier.padding(8.dp))
            }
            LazyColumn(state = listState, modifier = Modifier.heightIn(max = 420.dp)) {
                itemsIndexed(entries) { idx, entry ->
                    val isSel = idx == selected
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(6.dp))
                            .background(if (isSel) selBg else Color.Transparent)
                            .clickable { run(entry) }
                            .padding(horizontal = 10.dp, vertical = 8.dp)
                    ) {
                        val rowFg = if (isSel) Color.White else fg
                        val rowMuted = if (isSel) Color.White else muted
                        Text(
                            text = if (entry is PaletteEntry.Action) "›" else "*",
                            color = rowMuted,
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.width(18.dp)
                        )
                        Column(modifier = Modifier.weight(1f)) {
                            when (entry) {
                                is PaletteEntry.Action -> Text(entry.label, color = rowFg, fontSize = 14.sp)
                                is PaletteEntry.Heading -> {
                                    val n = entry.node
                                    Text(
                                        text = listOfNotNull(n.todoKeyword, n.title).joinToString(" "),
                                        color = rowFg,
                                        fontSize = 14.sp,
                                        maxLines = 1
                                    )
                                    Text(
                                        text = n.filePath.substringAfterLast('/') + (if (n.tags.isNotEmpty()) "  :" + n.tags.joinToString(":") + ":" else ""),
                                        color = rowMuted,
                                        fontSize = 10.sp,
                                        maxLines = 1
                                    )
                                }
                            }
                        }
                        if (entry is PaletteEntry.Action && entry.action.hint.isNotEmpty()) {
                            Text(entry.action.hint, color = rowMuted, fontSize = 11.sp, fontFamily = FontFamily.Monospace)
                        }
                    }
                }
            }
        }
    }
}
