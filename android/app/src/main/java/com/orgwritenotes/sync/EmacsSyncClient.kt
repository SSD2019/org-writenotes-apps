package com.orgwritenotes.sync

import android.content.Context
import android.os.Build
import android.util.Base64
import com.orgwritenotes.ink.HandwritingOcrEngine
import com.orgwritenotes.model.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.*
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.TimeUnit
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.zip.GZIPOutputStream

enum class ConnectionState {
    DISCONNECTED,
    CONNECTING,
    CONNECTED_ADB,
    CONNECTED_NETWORK,
    OFFLINE_CACHED,
    ERROR
}

private data class QueuedMutation(
    val method: String,
    val paramsJson: String,
    val nodeId: String = "",
    val fileName: String = "",
    val key: String = ""
)

/** Builds a queue entry with its dedupe keys extracted once, so the large payload is never re-parsed. */
private fun queuedMutation(method: String, params: JSONObject, json: String = params.toString()) =
    QueuedMutation(
        method = method,
        paramsJson = json,
        nodeId = params.optString("nodeId", ""),
        fileName = params.optString("fileName", ""),
        key = params.optString("key", "")
    )

private data class PendingInkCommit(
    val nodeId: String,
    val fileName: String,
    val canvasWidth: Int,
    val canvasHeight: Int,
    val exportSvgzBase64Provider: (Boolean) -> String
)

/**
 * Bidirectional JSON-RPC 2.0 WebSocket client for syncing Org-mode AST blocks
 * and Stylus Labs Write `.svgz` ink canvases with GNU Emacs (`org-writenotes.el`).
 *
 * Phase 3 Enhancements:
 * - **Offline Cache & Replay Queue**: Caches node summaries and the active `OrgNodeSnapshot`
 *   to local storage (`filesDir/org_cache/`) and queues any offline checklist/header/paragraph
 *   edits or `.svgz` commits, replaying them automatically upon reconnecting to Emacs.
 * - **Rich Org Mutations**: Supports cycling `TODO` states, priorities, tags, inline paragraph
 *   text updates (`node.updateParagraph`), and `:PROPERTIES:` drawer edits (`node.setProperty`).
 */
class EmacsSyncClient(
    private val scope: CoroutineScope,
    context: Context? = null
) {
    private val cacheDir: File? = context?.filesDir?.let { File(it, "org_writenotes_cache").apply { mkdirs() } }
    /** D3: SQLite cache (snapshots, summaries, offline queue). Null only in context-less unit tests. */
    private val store: OfflineStore? = context?.let { OfflineStore(it) }
    private val offlineMutationQueue = ConcurrentLinkedQueue<QueuedMutation>()

    private val httpClient = OkHttpClient.Builder()
        .pingInterval(15, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .build()

    private var webSocket: WebSocket? = null
    private val rpcIdGenerator = AtomicInteger(1)
    private val pendingCallbacks = ConcurrentHashMap<Int, (JSONObject) -> Unit>()
    private val pendingErrorCallbacks = ConcurrentHashMap<Int, (String) -> Unit>()
    private val pendingMethods = ConcurrentHashMap<Int, String>()
    /** Bumped per connection attempt so callbacks from a cancelled socket cannot touch newer state. */
    private val connectGeneration = AtomicInteger(0)
    private val commitDebounceJobs = ConcurrentHashMap<String, Job>()
    private val pendingInkCommits = ConcurrentHashMap<String, PendingInkCommit>()

    companion object {
        const val INK_COMMIT_DEBOUNCE_MS = 3000L
        const val PROTOCOL_VERSION = 2
        private const val RECOVERY_DEBOUNCE_MS = 1000L
        const val LARGE_SKETCH_BASE64_CHARS = 1_500_000
        private val RECONNECT_BACKOFF_MS = longArrayOf(1000, 2000, 5000, 10000)
    }

    // --- Auto-reconnect with backoff (D5: paused while app is backgrounded) ---
    private var lastConnectArgs: Triple<String, Boolean, String?>? = null
    private var reconnectJob: Job? = null
    @Volatile private var reconnectAttempt = 0
    @Volatile var reconnectPaused: Boolean = false
        set(value) {
            field = value
            if (value) reconnectJob?.cancel() else if (!isConnected()) scheduleReconnect(immediate = true)
        }

    /** D5: set by the activity's network callback; only gates non-ADB (network) connections. */
    @Volatile var networkAvailable: Boolean = true
        set(value) {
            field = value
            if (value && !reconnectPaused && !isConnected()) scheduleReconnect(immediate = true)
            else if (!value && lastConnectArgs?.second == false) reconnectJob?.cancel()
        }

    private fun scheduleReconnect(immediate: Boolean = false) {
        val args = lastConnectArgs ?: return
        if (reconnectPaused || isConnected()) return
        if (!args.second && !networkAvailable) return
        reconnectJob?.cancel()
        val wait = if (immediate) 0L
            else RECONNECT_BACKOFF_MS[reconnectAttempt.coerceAtMost(RECONNECT_BACKOFF_MS.lastIndex)]
        reconnectAttempt++
        reconnectJob = scope.launch {
            delay(wait)
            if (!reconnectPaused && !isConnected()) connect(args.first, args.second, args.third)
        }
    }

    private val _connectionState = MutableStateFlow(ConnectionState.DISCONNECTED)
    val connectionState: StateFlow<ConnectionState> = _connectionState

    // B9: Actionable connection error tracking
    private val _lastErrorMessage = MutableStateFlow<String?>(null)
    val lastErrorMessage: StateFlow<String?> = _lastErrorMessage

    private val _nodeSummaries = MutableStateFlow<List<OrgNodeSummary>>(emptyList())
    val nodeSummaries: StateFlow<List<OrgNodeSummary>> = _nodeSummaries

    private val _bufferOutline = MutableStateFlow<List<OrgNodeSummary>>(emptyList())
    val bufferOutline: StateFlow<List<OrgNodeSummary>> = _bufferOutline

    private val _agendaNodes = MutableStateFlow<List<OrgNodeSummary>>(emptyList())
    val agendaNodes: StateFlow<List<OrgNodeSummary>> = _agendaNodes

    private val _searchNodes = MutableStateFlow<List<OrgNodeSummary>>(emptyList())
    val searchNodes: StateFlow<List<OrgNodeSummary>> = _searchNodes
    private val _paletteSearchNodes = MutableStateFlow<List<OrgNodeSummary>>(emptyList())
    /** Command-palette search results, kept apart from the sidebar Search tab's list. */
    val paletteSearchNodes: StateFlow<List<OrgNodeSummary>> = _paletteSearchNodes

    private val _galleryItems = MutableStateFlow<List<OrgGalleryItem>>(emptyList())
    val galleryItems: StateFlow<List<OrgGalleryItem>> = _galleryItems

    private val _pendingSharedImageBytes = MutableStateFlow<ByteArray?>(null)
    val pendingSharedImageBytes: StateFlow<ByteArray?> = _pendingSharedImageBytes

    fun setPendingSharedImageBytes(bytes: ByteArray?) {
        _pendingSharedImageBytes.value = bytes
    }

    fun clearPendingSharedImageBytes() {
        _pendingSharedImageBytes.value = null
    }

    /** Final teardown when the owning activity is finishing: stop reconnects and drop the socket. */
    fun shutdown() {
        reconnectPaused = true
        reconnectJob?.cancel()
        // Release the retained native sessions of commits that will never run.
        for (fileName in pendingInkCommits.keys.toList()) {
            pendingInkCommits.remove(fileName)?.let { runCatching { it.exportSvgzBase64Provider(false) } }
        }
        connectGeneration.incrementAndGet()
        webSocket?.cancel()
        webSocket = null
    }

    fun onAppResumed() {
        reconnectPaused = false
        if (!isConnected() && _connectionState.value != ConnectionState.CONNECTING) {
            if (lastConnectArgs != null) {
                scheduleReconnect(immediate = true)
            }
        }
    }

    private val _emacsTheme = MutableStateFlow(OrgEmacsTheme())
    val emacsTheme: StateFlow<OrgEmacsTheme> = _emacsTheme
    private var userDarkThemeOverride: Boolean? = null

    fun toggleCanvasDarkMode() {
        val current = _emacsTheme.value
        val nextDark = !current.isDark
        userDarkThemeOverride = nextDark
        val nextBg = if (nextDark) {
            val parsedBg = OrgEmacsTheme.parseColorOrNull(current.bgColor)
            if (parsedBg != null && OrgEmacsTheme.isColorDark(parsedBg)) current.bgColor else "#181825"
        } else {
            val parsedBg = OrgEmacsTheme.parseColorOrNull(current.bgColor)
            if (parsedBg != null && !OrgEmacsTheme.isColorDark(parsedBg)) current.bgColor else "#FFFDF8"
        }
        val nextFg = if (nextDark) {
            val parsedFg = OrgEmacsTheme.parseColorOrNull(current.fgColor)
            if (parsedFg != null && !OrgEmacsTheme.isColorDark(parsedFg)) current.fgColor else "#CDD6F4"
        } else {
            val parsedFg = OrgEmacsTheme.parseColorOrNull(current.fgColor)
            if (parsedFg != null && OrgEmacsTheme.isColorDark(parsedFg)) current.fgColor else "#1E293B"
        }
        _emacsTheme.value = current.copy(
            isDark = nextDark,
            bgColor = nextBg,
            fgColor = nextFg
        )
    }

    private fun applyRemoteEmacsTheme(themeObj: JSONObject, isExplicitThemeChange: Boolean) {
        if (isExplicitThemeChange) {
            userDarkThemeOverride = null
        }
        val remoteDark = themeObj.optBoolean("isDark", true)
        val effectiveDark = userDarkThemeOverride ?: remoteDark
        val rawBg = themeObj.optString("bgColor", if (effectiveDark) "#1E293B" else "#FFFDF8")
        val rawFg = themeObj.optString("fgColor", if (effectiveDark) "#F8FAFC" else "#1E293B")
        val bg = if (effectiveDark) {
            val parsed = OrgEmacsTheme.parseColorOrNull(rawBg)
            if (parsed != null && OrgEmacsTheme.isColorDark(parsed)) rawBg else "#181825"
        } else {
            val parsed = OrgEmacsTheme.parseColorOrNull(rawBg)
            if (parsed != null && !OrgEmacsTheme.isColorDark(parsed)) rawBg else "#FFFDF8"
        }
        val fg = if (effectiveDark) {
            val parsed = OrgEmacsTheme.parseColorOrNull(rawFg)
            if (parsed != null && !OrgEmacsTheme.isColorDark(parsed)) rawFg else "#CDD6F4"
        } else {
            val parsed = OrgEmacsTheme.parseColorOrNull(rawFg)
            if (parsed != null && OrgEmacsTheme.isColorDark(parsed)) rawFg else "#1E293B"
        }
        _emacsTheme.value = OrgEmacsTheme(
            isDark = effectiveDark,
            bgColor = bg,
            fgColor = fg,
            accentColor = themeObj.optString("accentColor", "#38BDF8")
        )
    }

    private val _activeSnapshot = MutableStateFlow<OrgNodeSnapshot?>(null)
    val activeSnapshot: StateFlow<OrgNodeSnapshot?> = _activeSnapshot

    private val _zoomedInkBlockTarget = MutableStateFlow<String?>(null)
    val zoomedInkBlockTarget: StateFlow<String?> = _zoomedInkBlockTarget

    private val _pendingOfflineCount = MutableStateFlow(0)
    val pendingOfflineCount: StateFlow<Int> = _pendingOfflineCount

    fun clearZoomedInkBlockTarget() {
        _zoomedInkBlockTarget.value = null
    }

    init {
        loadCachedStateFromDisk()
    }

    /**
     * One-tap zero-config connection over `adb reverse tcp:9876 tcp:9876`.
     */
    fun connectAdbUsb(port: Int = 9876) {
        connect(url = "ws://127.0.0.1:$port", isAdb = true)
    }

    /**
     * Connect over LAN / Tailscale / QR code URL (`{"v":1,"url":"ws://...","token":"..."}` or raw `ws://`).
     */
    fun connectNetwork(wsUrlOrQrPayload: String, token: String? = null) {
        val trimmed = wsUrlOrQrPayload.trim()
        if (trimmed.startsWith("{")) {
            runCatching {
                val qr = JSONObject(trimmed)
                val extractedUrl = qr.getString("url")
                val extractedToken = qr.optString("token").takeIf { it.isNotEmpty() }
                connect(url = extractedUrl, isAdb = false, token = extractedToken ?: token)
                return
            }
        }
        connect(url = trimmed, isAdb = false, token = token)
    }

    private fun connect(url: String, isAdb: Boolean, token: String? = null) {
        val request = try {
            Request.Builder().url(url).build()
        } catch (e: IllegalArgumentException) {
            _lastErrorMessage.value = "Invalid server URL: $url"
            _connectionState.value = ConnectionState.ERROR
            return
        }
        lastConnectArgs = Triple(url, isAdb, token)
        reconnectJob?.cancel()
        val gen = connectGeneration.incrementAndGet()
        webSocket?.cancel()
        _connectionState.value = ConnectionState.CONNECTING

        webSocket = httpClient.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                if (gen != connectGeneration.get()) return
                AppLogger.i("SyncClient", "Connected to Emacs (isAdb=$isAdb)")
                _lastErrorMessage.value = null
                _connectionState.value =
                    if (isAdb) ConnectionState.CONNECTED_ADB else ConnectionState.CONNECTED_NETWORK

                val params = JSONObject().apply {
                    put("clientName", Build.MODEL?.takeIf { it.isNotBlank() } ?: "Android tablet")
                    put("version", "0.2.0")
                    put("protocolVersion", PROTOCOL_VERSION)
                    if (token != null) put("token", token)
                }
                sendRpc("session.hello", params, queueIfOffline = false) { result ->
                    reconnectAttempt = 0
                    // Replay queued offline mutations only once the session is established.
                    flushOfflineMutationQueue()
                    _serverCapabilities.value =
                        result.optJSONArray("capabilities")?.toStringList()?.toSet().orEmpty()
                    val activeId = result.optString("activeNodeId", "")
                    val themeObj = result.optJSONObject("theme")
                    if (themeObj != null) {
                        applyRemoteEmacsTheme(themeObj, isExplicitThemeChange = false)
                    }
                    refreshNodeList()
                    fetchBufferOutline()
                    if (activeId.isNotEmpty() && activeId != "null") {
                        fetchNode(activeId)
                    }
                }
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                if (gen != connectGeneration.get()) return
                runCatching { handleIncomingMessage(text) }
                    .onFailure { AppLogger.e("SyncClient", "Failed to handle message", it) }
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                if (gen != connectGeneration.get()) return
                pendingCallbacks.clear()
                pendingErrorCallbacks.clear()
                pendingMethods.clear()
                val errorDetail = when {
                    t is java.net.ConnectException -> "Connection refused. Is Emacs running (M-x org-writenotes-start-server)?"
                    t is java.net.SocketTimeoutException -> "Connection timed out. Check network or Tailscale status."
                    response?.code == 401 || response?.code == 403 -> "Authentication rejected. Check session token."
                    else -> t.localizedMessage ?: "Failed to connect to Emacs."
                }
                AppLogger.e("SyncClient", "Connection failure: $errorDetail", t)
                _lastErrorMessage.value = errorDetail
                _connectionState.value = if (_activeSnapshot.value != null) {
                    ConnectionState.OFFLINE_CACHED
                } else {
                    ConnectionState.ERROR
                }
                scheduleReconnect()
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                if (gen != connectGeneration.get()) return
                pendingCallbacks.clear()
                pendingErrorCallbacks.clear()
                pendingMethods.clear()
                _connectionState.value = if (_activeSnapshot.value != null) {
                    ConnectionState.OFFLINE_CACHED
                } else {
                    ConnectionState.DISCONNECTED
                }
                scheduleReconnect()
            }
        })
    }

    private fun parseSummaryList(arr: JSONArray?): List<OrgNodeSummary> {
        if (arr == null) return emptyList()
        val list = mutableListOf<OrgNodeSummary>()
        for (i in 0 until arr.length()) {
            val obj = arr.getJSONObject(i)
            val nid = obj.optString("nodeId", "")
            if (nid.isNotEmpty()) {
                list.add(
                    OrgNodeSummary(
                        nodeId = nid,
                        filePath = obj.optString("filePath", ""),
                        level = obj.optInt("level", 1),
                        todoKeyword = obj.optString("todoKeyword").takeIf { it.isNotEmpty() && it != "null" },
                        priority = obj.optString("priority").takeIf { it.isNotEmpty() && it != "null" },
                        title = obj.optString("title", "Untitled"),
                        tags = obj.optJSONArray("tags")?.toStringList() ?: emptyList(),
                        scheduled = obj.optString("scheduled").takeIf { it.isNotEmpty() && it != "null" },
                        deadline = obj.optString("deadline").takeIf { it.isNotEmpty() && it != "null" },
                        sketchCount = obj.optInt("sketchCount", 0),
                        agendaLabel = obj.optString("agendaLabel").takeIf { it.isNotEmpty() && it != "null" }
                    )
                )
            }
        }
        // LazyColumn keys rows by nodeId; a duplicate key would crash the sidebar.
        return list.distinctBy { it.nodeId }
    }

    fun refreshNodeList(query: String = "") {
        val params = JSONObject().apply {
            put("filter", "pushed_history")
            put("query", query)
        }
        sendRpc("node.list", params, queueIfOffline = false) { result ->
            val remoteList = parseSummaryList(result.optJSONArray("nodes"))
            if (remoteList.isNotEmpty()) {
                val merged = (remoteList + _nodeSummaries.value)
                    .distinctBy { it.nodeId }
                    .take(50)
                _nodeSummaries.value = merged
                saveCachedSummariesToDisk(merged)
            }
        }
    }

    fun fetchBufferOutline() {
        val params = JSONObject().apply {
            put("filter", "buffer_outline")
        }
        sendRpc("node.list", params, queueIfOffline = false) { result ->
            _bufferOutline.value = parseSummaryList(result.optJSONArray("nodes"))
        }
    }

    fun fetchAgendaNodes(query: String = "") {
        val params = JSONObject().apply {
            put("filter", "agenda")
            put("query", query)
        }
        sendRpc("node.list", params, queueIfOffline = false) { result ->
            _agendaNodes.value = parseSummaryList(result.optJSONArray("nodes"))
        }
    }

    private val searchSeq = AtomicInteger(0)

    private val paletteSearchSeq = AtomicInteger(0)

    fun searchNodesForPalette(query: String) {
        val params = JSONObject().put("filter", "search").put("query", query)
        val seq = paletteSearchSeq.incrementAndGet()
        sendRpc("node.list", params, queueIfOffline = false) { result ->
            if (seq == paletteSearchSeq.get()) _paletteSearchNodes.value = parseSummaryList(result.optJSONArray("nodes"))
        }
    }

    fun searchNodes(query: String) {
        val params = JSONObject().apply {
            put("filter", "search")
            put("query", query)
        }
        // Responses can arrive out of order while typing; only the latest query's results win.
        val seq = searchSeq.incrementAndGet()
        sendRpc("node.list", params, queueIfOffline = false) { result ->
            if (seq == searchSeq.get()) {
                _searchNodes.value = parseSummaryList(result.optJSONArray("nodes"))
            }
        }
    }

    fun fetchGallery() {
        sendRpc("sketch.listGallery", JSONObject(), queueIfOffline = false) { result ->
            val arr = result.optJSONArray("sketches") ?: return@sendRpc
            val items = mutableListOf<OrgGalleryItem>()
            for (i in 0 until arr.length()) {
                val obj = arr.getJSONObject(i)
                val fname = obj.optString("fileName", "")
                if (fname.isNotEmpty()) {
                    items.add(
                        OrgGalleryItem(
                            fileName = fname,
                            nodeId = obj.optString("nodeId").takeIf { it.isNotEmpty() && it != "null" },
                            nodeTitle = obj.optString("nodeTitle", "Unlinked Sketch"),
                            modifiedTime = obj.optString("modifiedTime", ""),
                            width = obj.optInt("width", 1200),
                            height = obj.optInt("height", 600),
                            svgzBase64 = obj.optString("svgzBase64").takeIf { it.isNotEmpty() && it != "null" },
                            ocrText = obj.optString("ocrText", "")
                        )
                    )
                }
            }
            _galleryItems.value = items
        }
    }

    fun createNode(
        title: String,
        todoKeyword: String? = "TODO",
        parentId: String? = null,
        mode: String = "child",
        createSketch: Boolean = false
    ) {
        val params = JSONObject().apply {
            put("title", title)
            if (todoKeyword != null) put("todoKeyword", todoKeyword)
            if (parentId != null) put("parentId", parentId)
            put("mode", mode)
            put("createSketch", createSketch)
        }
        sendRpc("node.create", params, queueIfOffline = true) { result ->
            updateActiveSnapshot(result)
            fetchBufferOutline()
        }
    }

    fun clearHistory() {
        val currentActive = _activeSnapshot.value
        val kept = if (currentActive != null) {
            listOf(summaryFromSnapshot(currentActive))
        } else {
            emptyList()
        }
        _nodeSummaries.value = kept
        saveCachedSummariesToDisk(kept)
    }

    fun fetchNode(nodeId: String) {
        flushPendingInkCommits()
        if (!isConnected()) {
            scope.launch(Dispatchers.IO) {
                loadCachedSnapshotForNode(nodeId)?.let {
                    _activeSnapshot.value = it
                }
            }
            return
        }
        val params = JSONObject().apply { put("nodeId", nodeId) }
        sendRpc("node.get", params, queueIfOffline = false) { result ->
            updateActiveSnapshot(result)
        }
    }

    fun cycleTodoState(snapshot: OrgNodeSnapshot) {
        val all = snapshot.header.todoKeywordsAll.ifEmpty { listOf("TODO", "NEXT", "DONE") }
        val current = snapshot.header.todoKeyword
        val nextIdx = if (current == null) 0 else (all.indexOf(current) + 1) % all.size
        val nextTodo = all[nextIdx]
        updateHeader(snapshot.nodeId, todoKeyword = nextTodo)
    }

    fun cyclePriority(snapshot: OrgNodeSnapshot) {
        val current = snapshot.header.priority
        val nextPrio = when (current) {
            null -> "A"
            "A" -> "B"
            "B" -> "C"
            else -> "A"
        }
        updateHeader(snapshot.nodeId, priority = nextPrio)
    }

    fun updateHeader(
        nodeId: String,
        todoKeyword: String? = null,
        priority: String? = null,
        title: String? = null,
        tags: List<String>? = null,
        scheduled: String? = null,
        deadline: String? = null
    ) {
        val params = JSONObject().apply {
            put("nodeId", nodeId)
            if (todoKeyword != null) put("todoKeyword", todoKeyword)
            if (priority != null) put("priority", priority)
            if (title != null) put("title", title)
            if (tags != null) put("tags", JSONArray(tags))
            if (scheduled != null) put("scheduled", scheduled)
            if (deadline != null) put("deadline", deadline)
        }
        sendRpc("node.updateHeader", params, queueIfOffline = true) { result ->
            updateActiveSnapshot(result)
        }
    }

    fun toggleCheckbox(nodeId: String, line: Int, checked: Boolean) {
        setCheckboxState(nodeId, line, if (checked) ChecklistState.CHECKED else ChecklistState.UNCHECKED)
    }

    fun setCheckboxState(nodeId: String, line: Int, state: ChecklistState) {
        val params = JSONObject().apply {
            put("nodeId", nodeId)
            put("line", line)
            put("state", state.toWire())
        }
        sendRpc("node.toggleCheckbox", params, queueIfOffline = true) { result ->
            updateActiveSnapshot(result)
        }
    }

    fun updateChecklistItem(
        nodeId: String,
        line: Int,
        text: String? = null,
        state: ChecklistState? = null,
        action: String? = null
    ) {
        val params = JSONObject().apply {
            put("nodeId", nodeId)
            put("line", line)
            if (text != null) put("text", text)
            if (state != null) put("state", state.toWire())
            if (action != null) put("action", action)
        }
        sendRpc("node.updateChecklistItem", params, queueIfOffline = true) { result ->
            updateActiveSnapshot(result)
        }
    }

    fun deleteChecklistItem(nodeId: String, line: Int) {
        val params = JSONObject().apply {
            put("nodeId", nodeId)
            put("line", line)
        }
        sendRpc("node.deleteChecklistItem", params, queueIfOffline = true) { result ->
            updateActiveSnapshot(result)
        }
    }

    fun executeCodeBlock(nodeId: String, startLine: Int) {
        val params = JSONObject().apply {
            put("nodeId", nodeId)
            put("startLine", startLine)
        }
        sendRpc("node.executeCodeBlock", params, queueIfOffline = false) { result ->
            updateActiveSnapshot(result)
        }
    }

    fun updateTableCell(nodeId: String, startLine: Int, row: Int, col: Int, value: String) {
        val params = JSONObject().apply {
            put("nodeId", nodeId)
            put("startLine", startLine)
            put("row", row)
            put("col", col)
            put("value", value)
        }
        sendRpc("node.updateTableCell", params, queueIfOffline = true) { result ->
            updateActiveSnapshot(result)
        }
    }

    fun clockIn(nodeId: String) {
        val params = JSONObject().apply { put("nodeId", nodeId) }
        sendRpc("node.clockIn", params, queueIfOffline = false) { result ->
            updateActiveSnapshot(result)
        }
    }

    fun clockOut(nodeId: String) {
        val params = JSONObject().apply { put("nodeId", nodeId) }
        sendRpc("node.clockOut", params, queueIfOffline = false) { result ->
            updateActiveSnapshot(result)
        }
    }

    fun insertOcrText(
        nodeId: String,
        text: String,
        mode: String = "paragraph",
        fileName: String? = null
    ) {
        val params = JSONObject().apply {
            put("nodeId", nodeId)
            put("text", text)
            put("mode", mode)
            if (!fileName.isNullOrEmpty()) put("fileName", fileName)
        }
        sendRpc("node.insertOcrText", params, queueIfOffline = true) { result ->
            updateActiveSnapshot(result)
        }
    }

    fun sendLaserPoint(nodeId: String, fileName: String, x: Float, y: Float) {
        val params = JSONObject().apply {
            put("nodeId", nodeId)
            put("fileName", fileName)
            put("x", x.toDouble())
            put("y", y.toDouble())
        }
        sendRpc("ink.laser", params, queueIfOffline = false)
    }

    fun addChecklistItem(nodeId: String, text: String, afterLine: Int? = null) {
        val params = JSONObject().apply {
            put("nodeId", nodeId)
            put("text", text)
            if (afterLine != null) put("afterLine", afterLine)
        }
        sendRpc("node.addChecklistItem", params, queueIfOffline = true) { result ->
            updateActiveSnapshot(result)
        }
    }

    fun updateParagraph(
        nodeId: String,
        startLine: Int,
        endLine: Int,
        oldText: String,
        newText: String
    ) {
        val params = JSONObject().apply {
            put("nodeId", nodeId)
            put("startLine", startLine)
            put("endLine", endLine)
            put("oldText", oldText)
            put("text", newText)
        }
        sendRpc("node.updateParagraph", params, queueIfOffline = true) { result ->
            updateActiveSnapshot(result)
        }
    }

    fun setProperty(nodeId: String, key: String, value: String) {
        val params = JSONObject().apply {
            put("nodeId", nodeId)
            put("key", key)
            put("value", value)
        }
        sendRpc("node.setProperty", params, queueIfOffline = true) { result ->
            updateActiveSnapshot(result)
        }
    }

    /** E4: Flag node for spaced repetition (org-drill / Anki). */
    fun flagAsFlashcard(nodeId: String, onComplete: (() -> Unit)? = null) {
        val params = JSONObject().apply { put("nodeId", nodeId) }
        sendRpc("node.flagFlashcard", params, queueIfOffline = true) {
            onComplete?.invoke()
        }
    }

    private fun buildBlankSvgzBase64(width: Int, height: Int, xRuling: Int = 40, yRuling: Int = 40): String {
        val xml = """<?xml version="1.0" encoding="UTF-8" standalone="no"?>
<svg xmlns="http://www.w3.org/2000/svg" width="$width" height="$height" viewBox="0 0 $width $height">
<svg class="write-doc" width="$width" height="$height" viewBox="0 0 $width $height">
<g class="write-page" width="$width" height="$height" xruling="$xRuling" yruling="$yRuling" marginLeft="0" color="#FFFFFFFF">
<rect class="write-background" x="0" y="0" width="$width" height="$height" fill="#FFFFFF"/>
</g>
<g class="write-content">
</g>
</svg>
</svg>"""
        return try {
            val bos = ByteArrayOutputStream()
            GZIPOutputStream(bos).use { it.write(xml.toByteArray(Charsets.UTF_8)) }
            Base64.encodeToString(bos.toByteArray(), Base64.NO_WRAP)
        } catch (_: Exception) {
            ""
        }
    }

    fun insertInkBlock(
        nodeId: String,
        afterLine: Int? = null,
        width: Int = 1200,
        height: Int = 600
    ) {
        if (!isConnected()) {
            // Tier 1.3 Offline Sketch Creation: generate local .svgz and optimistic InkCanvasBlock
            val current = _activeSnapshot.value
            if (current != null && current.nodeId == nodeId) {
                val slug = current.header.title.lowercase(Locale.US)
                    .replace(Regex("[^a-z0-9]+"), "-")
                    .trim('-')
                    .ifEmpty { "sketch" }
                val ts = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
                val localFileName = "$slug-$ts.svgz"
                val blankB64 = buildBlankSvgzBase64(width, height)
                val newBlock = OrgBlock.InkCanvasBlock(
                    blockId = "ink-offline-$ts",
                    startLine = afterLine ?: (current.blocks.lastOrNull()?.endLine ?: 1),
                    endLine = afterLine ?: (current.blocks.lastOrNull()?.endLine ?: 1),
                    fileName = localFileName,
                    linkText = "[[file:images/$localFileName]]",
                    width = width,
                    height = height,
                    xRuling = 40,
                    yRuling = 40,
                    svgzBase64 = blankB64
                )
                _activeSnapshot.update { cur ->
                    if (cur != null && cur.nodeId == nodeId) cur.copy(blocks = cur.blocks + newBlock) else cur
                }
            }
        }
        val params = JSONObject().apply {
            put("nodeId", nodeId)
            if (afterLine != null) put("afterLine", afterLine)
            put("width", width)
            put("height", height)
        }
        sendRpc("node.insertInkBlock", params, queueIfOffline = true) { result ->
            updateActiveSnapshot(result)
        }
    }

    fun deleteInkBlock(
        nodeId: String,
        fileName: String,
        linkText: String = "",
        deleteFileOnDisk: Boolean = true
    ) {
        // Optimistically remove ink block from active snapshot immediately
        _activeSnapshot.update { cur ->
            if (cur != null && cur.nodeId == nodeId) {
                cur.copy(blocks = cur.blocks.filterNot {
                    it is OrgBlock.InkCanvasBlock && it.fileName == fileName
                })
            } else {
                cur
            }
        }

        // Cancel pending debounce commits for this file and release retained native session
        commitDebounceJobs.remove(fileName)?.cancel()
        pendingInkCommits.remove(fileName)?.let { abandoned ->
            runCatching { abandoned.exportSvgzBase64Provider(false) }
        }

        // Cancel and purge any pending offline ink commits for this file
        offlineMutationQueue.removeAll { it.method == "ink.commit" && it.fileName == fileName }
        _pendingOfflineCount.value = offlineMutationQueue.size
        persistOfflineQueueToDisk()

        val params = JSONObject().apply {
            put("nodeId", nodeId)
            put("fileName", fileName)
            if (linkText.isNotEmpty()) put("linkText", linkText)
            put("deleteFile", deleteFileOnDisk)
        }
        sendRpc("node.deleteInkBlock", params, queueIfOffline = true) { result ->
            updateActiveSnapshot(result)
        }
    }

    fun insertInkBlockAtParagraphSplit(
        nodeId: String,
        startLine: Int,
        endLine: Int,
        textBefore: String,
        textAfter: String,
        width: Int = 1200,
        height: Int = 600
    ) {
        val params = JSONObject().apply {
            put("nodeId", nodeId)
            put("splitStartLine", startLine)
            put("splitEndLine", endLine)
            put("textBefore", textBefore)
            put("textAfter", textAfter)
            put("width", width)
            put("height", height)
        }
        sendRpc("node.insertInkBlock", params, queueIfOffline = true) { result ->
            updateActiveSnapshot(result)
        }
    }

    /**
     * Streams a single stroke `<path .../>` immediately on stylus pen-up, then
     * schedules a debounced `.svgz` full binary commit (3.0s idle) on a background
     * IO thread so `.svgz` gzip compression + Base64 encoding never block the UI thread.
     */
    fun streamStrokeAndScheduleCommit(
        nodeId: String,
        fileName: String,
        pathXml: String,
        canvasWidth: Int,
        canvasHeight: Int,
        exportSvgzBase64Provider: (Boolean) -> String
    ) {
        // Only update snapshot dimensions synchronously if the canvas actually grew
        _activeSnapshot.update { cur ->
            val targetBlock = if (cur != null && cur.nodeId == nodeId) {
                cur.blocks.firstOrNull {
                    it is OrgBlock.InkCanvasBlock && it.fileName == fileName
                } as? OrgBlock.InkCanvasBlock
            } else {
                null
            }
            if (cur != null && targetBlock != null &&
                (targetBlock.width != canvasWidth || targetBlock.height != canvasHeight)
            ) {
                cur.copy(blocks = cur.blocks.map { blk ->
                    if (blk is OrgBlock.InkCanvasBlock && blk.fileName == fileName) {
                        blk.copy(width = canvasWidth, height = canvasHeight)
                    } else {
                        blk
                    }
                })
            } else {
                cur
            }
        }

        val deltaParams = JSONObject().apply {
            put("nodeId", nodeId)
            put("fileName", fileName)
            put("op", "stroke_add")
            put("pathXml", pathXml)
            put("canvasWidth", canvasWidth)
            put("canvasHeight", canvasHeight)
        }
        sendRpc("ink.delta", deltaParams, queueIfOffline = false)

        val pending = PendingInkCommit(
            nodeId = nodeId,
            fileName = fileName,
            canvasWidth = canvasWidth,
            canvasHeight = canvasHeight,
            exportSvgzBase64Provider = exportSvgzBase64Provider
        )
        pendingInkCommits.put(fileName, pending)?.let { superseded ->
            if (superseded !== pending) {
                runCatching { superseded.exportSvgzBase64Provider(false) }
            }
        }

        commitDebounceJobs.remove(fileName)?.cancel()
        commitDebounceJobs[fileName] = scope.launch(Dispatchers.IO) {
            delay(INK_COMMIT_DEBOUNCE_MS)
            val toCommit = pendingInkCommits.remove(fileName) ?: return@launch
            executeInkCommit(toCommit)
        }
    }

    /** Immediately commit every sketch with a pending debounced save (call from onPause/onTrimMemory). */
    fun flushAllPendingCommits() {
        flushPendingInkCommits()
    }

    /**
     * Asynchronously exports and commits any pending debounced `.svgz` ink changes
     * on `Dispatchers.IO` (e.g. when closing the zoomed editor overlay or switching nodes)
     * so `.svgz` gzip compression and Base64 encoding never block the UI thread.
     */
    fun flushPendingInkCommits() {
        val keys = pendingInkCommits.keys.toList()
        for (fileName in keys) {
            commitDebounceJobs.remove(fileName)?.cancel()
            val pending = pendingInkCommits.remove(fileName) ?: continue
            scope.launch(Dispatchers.IO) {
                executeInkCommit(pending)
            }
        }
    }

    private fun executeInkCommit(pending: PendingInkCommit) {
        val svgzBase64 = runCatching { pending.exportSvgzBase64Provider(true) }.getOrDefault("")
        if (svgzBase64.isNotEmpty()) {
            updateLocalBlockSvgz(
                pending.nodeId,
                pending.fileName,
                pending.canvasWidth,
                pending.canvasHeight,
                svgzBase64
            )
            commitSvgz(pending.nodeId, pending.fileName, pending.canvasWidth, pending.canvasHeight, svgzBase64)
        }
    }

    /** OCR-indexes and sends a full `.svgz` commit; heavy (decompress + recognise), so call off the UI thread. */
    private fun commitSvgz(nodeId: String, fileName: String, canvasWidth: Int, canvasHeight: Int, svgzBase64: String) {
        val ocrText = HandwritingOcrEngine.extractTextFromSvgzBase64(svgzBase64)
        val commitParams = JSONObject().apply {
            put("nodeId", nodeId)
            put("fileName", fileName)
            put("width", canvasWidth)
            put("height", canvasHeight)
            put("svgzBase64", svgzBase64)
            if (ocrText.isNotBlank()) put("ocrText", ocrText)
        }
        sendInkCommit(commitParams)
    }

    private fun updateLocalBlockSvgz(
        nodeId: String,
        fileName: String,
        canvasWidth: Int,
        canvasHeight: Int,
        svgzBase64: String
    ) {
        _activeSnapshot.update { cur ->
            if (cur != null && cur.nodeId == nodeId) {
                cur.copy(blocks = cur.blocks.map { blk ->
                    if (blk is OrgBlock.InkCanvasBlock && blk.fileName == fileName) {
                        blk.copy(
                            width = canvasWidth,
                            height = canvasHeight,
                            svgzBase64 = svgzBase64
                        )
                    } else {
                        blk
                    }
                })
            } else {
                cur
            }
        }
    }

    fun flushPendingInkCommit(
        nodeId: String,
        fileName: String,
        canvasWidth: Int,
        canvasHeight: Int,
        svgzBase64: String
    ) {
        commitDebounceJobs.remove(fileName)?.cancel()
        pendingInkCommits.remove(fileName)?.let { abandoned ->
            runCatching { abandoned.exportSvgzBase64Provider(false) }
        }
        if (svgzBase64.isEmpty()) return
        updateLocalBlockSvgz(nodeId, fileName, canvasWidth, canvasHeight, svgzBase64)
        // Callers are on the UI thread: keep OCR, recovery-file I/O and JSON building off it.
        scope.launch(Dispatchers.IO) { commitSvgz(nodeId, fileName, canvasWidth, canvasHeight, svgzBase64) }
    }

    // --- A3 / A7: etag tracking & conflict surfacing -------------------------
    private val knownEtags = ConcurrentHashMap<String, String>()

    private val _conflictedFiles = MutableStateFlow<Set<String>>(emptySet())
    /** Files whose last commit conflicted; Emacs saved our copy as `*.conflict-*.svgz`. */
    val conflictedFiles: StateFlow<Set<String>> = _conflictedFiles

    private val _serverCapabilities = MutableStateFlow<Set<String>>(emptySet())
    val serverCapabilities: StateFlow<Set<String>> = _serverCapabilities

    // --- E5: multi-client presence (broadcast by Emacs as `presence.update`) ---
    data class PresenceClient(val id: String, val name: String, val nodeId: String?)

    private val _presence = MutableStateFlow<List<PresenceClient>>(emptyList())
    /** Every client currently connected to the Emacs server, including this one. */
    val presence: StateFlow<List<PresenceClient>> = _presence

    // --- D4: large-sketch warning (based on committed size; strokes dominate the payload) ---
    private val _largeSketches = MutableStateFlow<Set<String>>(emptySet())
    /** Sketches whose committed `.svgz` exceeds [LARGE_SKETCH_BASE64_CHARS]; the UI warns about these. */
    val largeSketches: StateFlow<Set<String>> = _largeSketches

    private fun trackSketchSize(file: String, base64Length: Int) {
        _largeSketches.update { if (base64Length > LARGE_SKETCH_BASE64_CHARS) it + file else it - file }
    }

    /** Node each conflicted sketch belongs to, so the resolution RPC can locate its file. */
    private val conflictNodeIds = ConcurrentHashMap<String, String>()

    private fun sendInkCommit(params: JSONObject) {
        val file = params.optString("fileName")
        params.optString("nodeId").takeIf { it.isNotEmpty() }?.let { conflictNodeIds[file] = it }
        trackSketchSize(file, params.optString("svgzBase64").length)
        knownEtags[file]?.let { if (!params.has("baseEtag")) params.put("baseEtag", it) }
        writeRecovery(params)
        sendRpc("ink.commit", params, queueIfOffline = true) { result -> onInkCommitResult(file, result) }
    }

    private fun onInkCommitResult(file: String, result: JSONObject) {
        clearRecovery(file)
        val etag = result.optString("etag", "")
        if (result.optBoolean("conflict", false)) {
            _conflictedFiles.update { it + file }
        } else {
            _conflictedFiles.update { it - file }
        }
        if (etag.isNotEmpty()) knownEtags[file] = etag
    }

    // --- D2: crash-recovery snapshots (one file per sketch, deleted once Emacs acks the commit) ---
    // Getter (not an initialiser): read from `init`, which runs before later property initialisers.
    private val recoveryDir: File? get() = cacheDir?.let { File(it, "recovery").apply { mkdirs() } }
    private val recoveryJobs = ConcurrentHashMap<String, Job>()

    private fun recoveryFile(fileName: String): File? =
        recoveryDir?.let {
            val digest = MessageDigest.getInstance("SHA-256").digest(fileName.toByteArray(Charsets.UTF_8))
            File(it, "${digest.take(12).joinToString("") { b -> "%02x".format(b) }}.json")
        }

    private fun writeRecovery(params: JSONObject) {
        val f = recoveryFile(params.optString("fileName")) ?: return
        runCatching {
            val tmp = File(f.parentFile, f.name + ".tmp")
            tmp.writeText(params.toString())
            tmp.renameTo(f)
        }
    }

    private fun clearRecovery(fileName: String) {
        recoveryJobs.remove(fileName)?.cancel()
        recoveryFile(fileName)?.delete()
    }

    /** Re-queues snapshots left behind by a crash; they replay on the next connection. */
    private fun restoreRecoverySnapshots() {
        val dir = recoveryDir ?: return
        val files = dir.listFiles { f -> f.name.endsWith(".json") } ?: return
        for (f in files) {
            runCatching {
                val params = JSONObject(f.readText())
                offlineMutationQueue.add(queuedMutation("ink.commit", params))
                AppLogger.i("SyncClient", "Recovered unsaved sketch ${params.optString("fileName")} after restart")
            }
            f.delete()
        }
        if (files.isNotEmpty()) {
            _pendingOfflineCount.value = offlineMutationQueue.size
            persistOfflineQueueToDisk()
        }
    }

    fun clearConflict(fileName: String) {
        _conflictedFiles.update { it - fileName }
    }

    /** Both versions of a conflicted sketch: what Emacs has on disk and the tablet's conflict copy. */
    data class SketchConflict(
        val fileName: String,
        val nodeId: String,
        val conflictFile: String,
        val conflictCount: Int,
        val emacsSvgzBase64: String,
        val tabletSvgzBase64: String
    )

    private val _sketchReloads = MutableSharedFlow<Pair<String, String>>(extraBufferCapacity = 4)
    /** (fileName, svgzBase64) to load into open canvases after Emacs replaced the sketch (conflict resolution). */
    val sketchReloads: SharedFlow<Pair<String, String>> = _sketchReloads

    fun conflictNodeId(fileName: String): String? =
        conflictNodeIds[fileName] ?: _activeSnapshot.value?.takeIf { snap ->
            snap.blocks.any { it is OrgBlock.InkCanvasBlock && it.fileName == fileName }
        }?.nodeId

    fun fetchConflictInfo(fileName: String, onResult: (SketchConflict?, String?) -> Unit) {
        val nodeId = conflictNodeId(fileName)
        val params = JSONObject().put("fileName", fileName).apply { nodeId?.let { put("nodeId", it) } }
        val sent = sendRpc("ink.conflictInfo", params, onError = { msg -> onResult(null, msg) }) { r ->
            onResult(
                SketchConflict(
                    fileName = fileName,
                    nodeId = nodeId.orEmpty(),
                    conflictFile = r.optString("conflictFile"),
                    conflictCount = r.optInt("conflictCount", 1),
                    emacsSvgzBase64 = r.optString("emacsSvgzBase64"),
                    tabletSvgzBase64 = r.optString("tabletSvgzBase64")
                ),
                null
            )
        }
        if (!sent) onResult(null, "Not connected to Emacs")
    }

    /**
     * Resolves a sketch conflict on the Emacs side. [choice] is "emacs", "tablet" or "merged"
     * (then [mergedSvgzBase64] is written). Pending local commits of the stale content are dropped
     * first so they can't overwrite the resolution; the node is re-fetched so canvases reload.
     */
    fun resolveConflict(
        conflict: SketchConflict,
        choice: String,
        mergedSvgzBase64: String? = null,
        onDone: (String?) -> Unit
    ) {
        val file = conflict.fileName
        commitDebounceJobs.remove(file)?.cancel()
        pendingInkCommits.remove(file)?.let { abandoned -> runCatching { abandoned.exportSvgzBase64Provider(false) } }
        offlineMutationQueue.removeAll { it.method == "ink.commit" && it.fileName == file }
        _pendingOfflineCount.value = offlineMutationQueue.size
        persistOfflineQueueToDisk()
        clearRecovery(file)

        val params = JSONObject()
            .put("fileName", file)
            .put("conflictFile", conflict.conflictFile)
            .put("choice", choice)
        if (conflict.nodeId.isNotEmpty()) params.put("nodeId", conflict.nodeId)
        if (mergedSvgzBase64 != null) params.put("svgzBase64", mergedSvgzBase64)
        val sent = sendRpc("ink.resolveConflict", params, onError = { msg -> onDone(msg) }) { r ->
            r.optString("etag").takeIf { it.isNotEmpty() }?.let { knownEtags[file] = it }
            r.optString("svgzBase64").takeIf { it.isNotEmpty() }?.let { _sketchReloads.tryEmit(file to it) }
            if (r.optInt("remainingConflicts", 0) == 0) {
                _conflictedFiles.update { it - file }
                conflictNodeIds.remove(file)
            }
            if (conflict.nodeId.isNotEmpty()) fetchNode(conflict.nodeId)
            onDone(null)
        }
        if (!sent) onDone("Not connected to Emacs")
    }

    /** A2: list historical snapshots of a sketch. */
    fun fetchSketchHistory(nodeId: String, fileName: String, onResult: (List<String>) -> Unit) {
        val p = JSONObject().put("nodeId", nodeId).put("fileName", fileName)
        sendRpc("ink.history", p) { r -> onResult(r.optJSONArray("snapshots")?.toStringList().orEmpty()) }
    }

    /** A2: restore a snapshot; returns new base64 svgz to reload in the canvas. */
    fun revertSketch(nodeId: String, fileName: String, snapshot: String, onResult: (String) -> Unit) {
        val p = JSONObject().put("nodeId", nodeId).put("fileName", fileName).put("snapshot", snapshot)
        sendRpc("ink.revert", p) { r ->
            r.optString("etag").takeIf { it.isNotEmpty() }?.let { knownEtags[fileName] = it }
            onResult(r.optString("svgzBase64"))
        }
    }

    private fun isConnected(): Boolean {
        val st = _connectionState.value
        return st == ConnectionState.CONNECTED_ADB || st == ConnectionState.CONNECTED_NETWORK
    }

    private fun enqueueOfflineMutation(method: String, params: JSONObject) {
        val entry = queuedMutation(method, params)
        when (method) {
            "ink.commit" -> {
                if (entry.fileName.isNotEmpty()) {
                    offlineMutationQueue.removeAll {
                        it.method == "ink.commit" && it.nodeId == entry.nodeId && it.fileName == entry.fileName
                    }
                }
            }
            "node.updateHeader" -> {
                if (entry.nodeId.isNotEmpty()) {
                    var mergedParams: JSONObject? = null
                    offlineMutationQueue.removeAll { item ->
                        if (item.method != "node.updateHeader" || item.nodeId != entry.nodeId) {
                            return@removeAll false
                        }
                        val p = runCatching { JSONObject(item.paramsJson) }.getOrNull() ?: return@removeAll false
                        val keys = params.keys()
                        while (keys.hasNext()) {
                            val k = keys.next()
                            p.put(k, params.opt(k))
                        }
                        mergedParams = p
                        true
                    }
                    mergedParams?.let { merged ->
                        offlineMutationQueue.add(queuedMutation(method, merged))
                        _pendingOfflineCount.value = offlineMutationQueue.size
                        persistOfflineQueueToDisk()
                        return
                    }
                }
            }
            "node.setProperty" -> {
                if (entry.nodeId.isNotEmpty() && entry.key.isNotEmpty()) {
                    offlineMutationQueue.removeAll {
                        it.method == "node.setProperty" && it.nodeId == entry.nodeId && it.key == entry.key
                    }
                }
            }
        }
        offlineMutationQueue.add(entry)
        _pendingOfflineCount.value = offlineMutationQueue.size
        persistOfflineQueueToDisk()
    }

    /** Returns true if the request was written to the socket. */
    private fun sendRpc(
        method: String,
        params: JSONObject,
        queueIfOffline: Boolean = false,
        onError: ((String) -> Unit)? = null,
        onResult: ((JSONObject) -> Unit)? = null
    ): Boolean {
        val ws = webSocket
        if (ws == null || !isConnected()) {
            if (queueIfOffline) {
                enqueueOfflineMutation(method, params)
            }
            return false
        }

        val id = rpcIdGenerator.getAndIncrement()
        pendingMethods[id] = method
        if (onResult != null) {
            pendingCallbacks[id] = onResult
        }
        if (onError != null) {
            pendingErrorCallbacks[id] = onError
        }
        val envelope = JSONObject().apply {
            put("jsonrpc", "2.0")
            put("id", id)
            put("method", method)
            put("params", params)
        }
        val sent = ws.send(envelope.toString())
        if (!sent) {
            pendingCallbacks.remove(id)
            pendingErrorCallbacks.remove(id)
            pendingMethods.remove(id)
            if (queueIfOffline) enqueueOfflineMutation(method, params)
        }
        return sent
    }

    /** Replays queued edits in order; stops (keeping the rest queued) as soon as a send fails. */
    private fun flushOfflineMutationQueue() {
        while (true) {
            val item = offlineMutationQueue.peek() ?: break
            val params = runCatching { JSONObject(item.paramsJson) }.getOrNull()
            val sent = when {
                params == null -> true // corrupt entry: drop it rather than block the queue
                item.method == "ink.commit" ->
                    sendRpc(item.method, params, queueIfOffline = false) { onInkCommitResult(item.fileName, it) }
                else -> sendRpc(item.method, params, queueIfOffline = false)
            }
            if (!sent) break
            offlineMutationQueue.remove(item)
        }
        _pendingOfflineCount.value = offlineMutationQueue.size
        persistOfflineQueueToDisk()
    }

    private fun summaryFromSnapshot(snap: OrgNodeSnapshot): OrgNodeSummary =
        OrgNodeSummary(
            nodeId = snap.nodeId,
            filePath = snap.filePath,
            level = snap.header.level,
            todoKeyword = snap.header.todoKeyword,
            priority = snap.header.priority,
            title = snap.header.title.ifEmpty { "Untitled" },
            tags = snap.header.tags
        )

    private fun updateActiveSnapshot(obj: JSONObject) {
        val snap = preservePendingInk(parseSnapshot(obj))
        _activeSnapshot.value = snap
        saveCachedSnapshotToDisk(snap.nodeId, obj)
        if (snap.nodeId.isNotEmpty()) {
            val summary = summaryFromSnapshot(snap)
            val updatedHistory = (listOf(summary) + _nodeSummaries.value.filterNot { it.nodeId == snap.nodeId })
                .take(50)
            if (updatedHistory != _nodeSummaries.value) {
                _nodeSummaries.value = updatedHistory
                saveCachedSummariesToDisk(updatedHistory)
            }
        }
    }

    /**
     * Sketches with uncommitted local strokes keep their local bytes: an echoed snapshot from Emacs
     * carries the older on-disk copy and would otherwise make the canvas reload over fresh strokes.
     */
    private fun preservePendingInk(snap: OrgNodeSnapshot): OrgNodeSnapshot {
        if (pendingInkCommits.isEmpty()) return snap
        val cur = _activeSnapshot.value?.takeIf { it.nodeId == snap.nodeId } ?: return snap
        val local = cur.blocks.filterIsInstance<OrgBlock.InkCanvasBlock>()
            .filter { pendingInkCommits.containsKey(it.fileName) }
            .associateBy { it.fileName }
        if (local.isEmpty()) return snap
        return snap.copy(blocks = snap.blocks.map { blk ->
            val mine = (blk as? OrgBlock.InkCanvasBlock)?.let { local[it.fileName] }
            if (mine != null) blk.copy(svgzBase64 = mine.svgzBase64) else blk
        })
    }

    private val latestSnapshotToSave = java.util.concurrent.atomic.AtomicReference<Pair<String, JSONObject>?>(null)

    /** Coalesces bursts (echoed edits) so only the newest snapshot is serialised and written. */
    private fun saveCachedSnapshotToDisk(nodeId: String, obj: JSONObject) {
        val db = store ?: return
        if (latestSnapshotToSave.getAndSet(nodeId to obj) != null) return // a writer is already queued
        scope.launch(Dispatchers.IO) {
            val (id, latest) = latestSnapshotToSave.getAndSet(null) ?: return@launch
            runCatching {
                val text = latest.toString()
                if (id.isNotEmpty()) {
                    db.putKv(OfflineStore.snapshotKey(id), text)
                    db.putKv(OfflineStore.KEY_ACTIVE_NODE, id)
                } else {
                    db.putKv(OfflineStore.KEY_ACTIVE_SNAPSHOT, text)
                }
            }
        }
    }

    private fun loadCachedSnapshotForNode(nodeId: String): OrgNodeSnapshot? {
        val db = store ?: return null
        return runCatching {
            db.getKv(OfflineStore.snapshotKey(nodeId))?.let { parseSnapshot(JSONObject(it)) }
        }.getOrNull()
    }

    private fun saveCachedSummariesToDisk(summaries: List<OrgNodeSummary>) {
        val db = store ?: return
        scope.launch(Dispatchers.IO) {
            runCatching {
                val arr = JSONArray()
                for (s in summaries) {
                    arr.put(JSONObject().apply {
                        put("nodeId", s.nodeId)
                        put("filePath", s.filePath)
                        put("level", s.level)
                        put("todoKeyword", s.todoKeyword ?: JSONObject.NULL)
                        put("priority", s.priority ?: JSONObject.NULL)
                        put("title", s.title)
                        put("tags", JSONArray(s.tags))
                        put("scheduled", s.scheduled ?: JSONObject.NULL)
                        put("deadline", s.deadline ?: JSONObject.NULL)
                        put("sketchCount", s.sketchCount)
                    })
                }
                db.putKv(OfflineStore.KEY_SUMMARIES, arr.toString())
            }
        }
    }

    private val queueSeq = AtomicLong(0)
    private val queuePersistLock = Any()
    private var queueWrittenSeq = 0L

    /** Writes are serialized and stale snapshots skipped, so an older queue can never overwrite a newer one. */
    private fun persistOfflineQueueToDisk() {
        val db = store ?: return
        val seq = queueSeq.incrementAndGet()
        val snapshotQueue = offlineMutationQueue.map { it.method to it.paramsJson }
        scope.launch(Dispatchers.IO) {
            synchronized(queuePersistLock) {
                if (seq > queueWrittenSeq) {
                    queueWrittenSeq = seq
                    runCatching { db.replaceQueue(snapshotQueue) }
                }
            }
        }
    }

    private fun loadCachedStateFromDisk() {
        val db = store ?: return
        scope.launch(Dispatchers.IO) {
            cacheDir?.let { db.migrateLegacyJson(it) }
            runCatching {
                val activeJson = db.getKv(OfflineStore.KEY_ACTIVE_NODE)?.let { db.getKv(OfflineStore.snapshotKey(it)) }
                    ?: db.getKv(OfflineStore.KEY_ACTIVE_SNAPSHOT)
                activeJson?.let { _activeSnapshot.value = parseSnapshot(JSONObject(it)) }
                db.getKv(OfflineStore.KEY_SUMMARIES)?.let { text ->
                    val arr = JSONArray(text)
                    val list = mutableListOf<OrgNodeSummary>()
                    for (i in 0 until arr.length()) {
                        val obj = arr.getJSONObject(i)
                        val nid = obj.optString("nodeId", "")
                        if (nid.isNotEmpty()) {
                            list.add(
                                OrgNodeSummary(
                                    nodeId = nid,
                                    filePath = obj.optString("filePath", ""),
                                    level = obj.optInt("level", 1),
                                    todoKeyword = obj.optString("todoKeyword").takeIf { it.isNotEmpty() && it != "null" },
                                    priority = obj.optString("priority").takeIf { it.isNotEmpty() && it != "null" },
                                    title = obj.optString("title", "Untitled"),
                                    tags = obj.optJSONArray("tags")?.toStringList() ?: emptyList(),
                                    scheduled = obj.optString("scheduled").takeIf { it.isNotEmpty() && it != "null" },
                                    deadline = obj.optString("deadline").takeIf { it.isNotEmpty() && it != "null" },
                                    sketchCount = obj.optInt("sketchCount", 0)
                                )
                            )
                        }
                    }
                    _nodeSummaries.value = list.distinctBy { it.nodeId }.take(50)
                }
                db.loadQueue().forEach { (method, params) ->
                    offlineMutationQueue.add(
                        runCatching { queuedMutation(method, JSONObject(params), params) }
                            .getOrElse { QueuedMutation(method, params) }
                    )
                }
                _pendingOfflineCount.value = offlineMutationQueue.size
            }
            restoreRecoverySnapshots()
        }
    }

    private fun handleIncomingMessage(text: String) {
        val msg = JSONObject(text)
        if (msg.has("id") && msg.has("result")) {
            val id = msg.getInt("id")
            val result = msg.optJSONObject("result") ?: JSONObject()
            pendingMethods.remove(id)
            pendingErrorCallbacks.remove(id)
            pendingCallbacks.remove(id)?.invoke(result)
        } else if (msg.has("id") && msg.has("error")) {
            val id = msg.getInt("id")
            val errObj = msg.optJSONObject("error")
            val errMsg = errObj?.optString("message") ?: "RPC Error"
            AppLogger.e("EmacsSyncClient", "RPC id $id returned error: $errMsg")
            pendingCallbacks.remove(id)
            pendingErrorCallbacks.remove(id)?.invoke(errMsg)
            val failedMethod = pendingMethods.remove(id)
            // Re-fetch the node after a failed mutation to resync the UI; never after a failed read
            // (that would loop on a missing node) or a streaming ink call.
            val isMutation = failedMethod != null && failedMethod.startsWith("node.") &&
                failedMethod != "node.get" && failedMethod != "node.list"
            if (isMutation) {
                _activeSnapshot.value?.nodeId?.let { nid ->
                    if (nid.isNotEmpty()) {
                        fetchNode(nid)
                    }
                }
            }
        } else if (msg.has("method")) {
            when (msg.getString("method")) {
                "emacs.focusNode", "emacs.nodeModified" -> {
                    val params = msg.optJSONObject("params") ?: return
                    updateActiveSnapshot(params)
                }
                "emacs.zoomInkBlock" -> {
                    val params = msg.optJSONObject("params") ?: return
                    params.optJSONObject("snapshot")?.let { snapObj ->
                        updateActiveSnapshot(snapObj)
                    }
                    val targetFile = params.optString("fileName", "")
                    if (targetFile.isNotEmpty()) {
                        _zoomedInkBlockTarget.value = targetFile
                    }
                }
                "presence.update" -> {
                    val params = msg.optJSONObject("params") ?: return
                    val arr = params.optJSONArray("clients") ?: JSONArray()
                    _presence.value = (0 until arr.length()).map {
                        val o = arr.getJSONObject(it)
                        PresenceClient(
                            id = o.optString("id"),
                            name = o.optString("name", "client"),
                            nodeId = o.optString("node").takeIf { n -> n.isNotEmpty() && n != "null" }
                        )
                    }
                }
                "emacs.themeChanged" -> {
                    val params = msg.optJSONObject("params") ?: return
                    applyRemoteEmacsTheme(params, isExplicitThemeChange = true)
                }
            }
        }
    }

    private fun parseSnapshot(obj: JSONObject): OrgNodeSnapshot {
        val hdrObj = obj.getJSONObject("header")
        val propsMap = mutableMapOf<String, String>()
        hdrObj.optJSONObject("properties")?.let { pObj ->
            val keys = pObj.keys()
            while (keys.hasNext()) {
                val k = keys.next()
                propsMap[k] = pObj.optString(k, "")
            }
        }

        val header = OrgNodeHeader(
            level = hdrObj.optInt("level", 1),
            todoKeyword = hdrObj.optString("todoKeyword").takeIf { it.isNotEmpty() && it != "null" },
            todoKeywordsAll = hdrObj.optJSONArray("todoKeywordsAll")?.toStringList() ?: listOf("TODO", "DONE"),
            priority = hdrObj.optString("priority").takeIf { it.isNotEmpty() && it != "null" },
            title = hdrObj.optString("title", ""),
            tags = hdrObj.optJSONArray("tags")?.toStringList() ?: emptyList(),
            scheduled = hdrObj.optString("scheduled").takeIf { it.isNotEmpty() && it != "null" },
            deadline = hdrObj.optString("deadline").takeIf { it.isNotEmpty() && it != "null" },
            checkboxProgress = hdrObj.optString("checkboxProgress").takeIf { it.isNotEmpty() && it != "null" },
            properties = propsMap,
            breadcrumbs = hdrObj.optJSONArray("breadcrumbs")?.toStringList() ?: emptyList(),
            parentNodeId = hdrObj.optString("parentNodeId").takeIf { it.isNotEmpty() && it != "null" },
            clockedIn = hdrObj.optBoolean("clockedIn", false),
            clockedMinutes = hdrObj.optInt("clockedMinutes", 0)
        )

        val blocksArr = obj.optJSONArray("blocks") ?: JSONArray()
        val blocks = mutableListOf<OrgBlock>()
        for (i in 0 until blocksArr.length()) {
            val b = blocksArr.getJSONObject(i)
            val blockId = b.optString("blockId", "blk-$i")
            val startLine = b.optInt("startLine", 0)
            val endLine = b.optInt("endLine", startLine)
            when (b.optString("type")) {
                "checklist" -> {
                    val itemsArr = b.optJSONArray("items") ?: JSONArray()
                    val items = mutableListOf<ChecklistItem>()
                    for (j in 0 until itemsArr.length()) {
                        val itObj = itemsArr.getJSONObject(j)
                        items.add(
                            ChecklistItem(
                                index = itObj.optInt("index", j),
                                line = itObj.optInt("line", startLine + j),
                                state = ChecklistState.fromWire(itObj.optString("state")),
                                text = itObj.optString("text", "")
                            )
                        )
                    }
                    val resolvedEndLine = b.optInt("endLine", items.lastOrNull()?.line ?: startLine)
                    blocks.add(OrgBlock.ChecklistBlock(blockId, startLine, resolvedEndLine, items))
                }
                "ink_canvas" -> {
                    blocks.add(
                        OrgBlock.InkCanvasBlock(
                            blockId = blockId,
                            startLine = startLine,
                            endLine = endLine,
                            fileName = b.optString("fileName", "sketch.svgz"),
                            linkText = b.optString("linkText", ""),
                            width = b.optInt("width", 1200),
                            height = b.optInt("height", 600),
                            xRuling = b.optInt("xRuling", 40),
                            yRuling = b.optInt("yRuling", 40),
                            svgzBase64 = b.optString("svgzBase64").takeIf { it.isNotEmpty() && it != "null" }
                        )
                    )
                }
                "paragraph" -> {
                    val txt = b.optString("text", "")
                    val resolvedEndLine = b.optInt(
                        "endLine",
                        startLine + (txt.lines().size - 1).coerceAtLeast(0)
                    )
                    blocks.add(
                        OrgBlock.ParagraphBlock(
                            blockId = blockId,
                            startLine = startLine,
                            endLine = resolvedEndLine,
                            text = txt
                        )
                    )
                }
                "code_block" -> {
                    blocks.add(
                        OrgBlock.CodeBlock(
                            blockId = blockId,
                            startLine = startLine,
                            endLine = endLine,
                            language = b.optString("language", "text"),
                            code = b.optString("code", ""),
                            results = b.optString("results").takeIf { it.isNotEmpty() && it != "null" }
                        )
                    )
                }
                "table" -> {
                    val rowsArr = b.optJSONArray("rows") ?: JSONArray()
                    val rows = mutableListOf<List<String>>()
                    for (r in 0 until rowsArr.length()) {
                        val rowArr = rowsArr.optJSONArray(r) ?: continue
                        rows.add(rowArr.toStringList())
                    }
                    blocks.add(
                        OrgBlock.TableBlock(
                            blockId = blockId,
                            startLine = startLine,
                            endLine = endLine,
                            rows = rows
                        )
                    )
                }
            }
        }

        val subArr = obj.optJSONArray("subheadings") ?: JSONArray()
        val subheadings = mutableListOf<OrgSubheadingItem>()
        for (i in 0 until subArr.length()) {
            val sObj = subArr.getJSONObject(i)
            val nid = sObj.optString("nodeId", "")
            if (nid.isNotEmpty()) {
                subheadings.add(
                    OrgSubheadingItem(
                        nodeId = nid,
                        level = sObj.optInt("level", 2),
                        todoKeyword = sObj.optString("todoKeyword").takeIf { it.isNotEmpty() && it != "null" },
                        priority = sObj.optString("priority").takeIf { it.isNotEmpty() && it != "null" },
                        title = sObj.optString("title", "Untitled"),
                        tags = sObj.optJSONArray("tags")?.toStringList() ?: emptyList(),
                        sketchCount = sObj.optInt("sketchCount", 0)
                    )
                )
            }
        }

        return OrgNodeSnapshot(
            nodeId = obj.optString("nodeId", ""),
            etag = obj.optString("etag", ""),
            filePath = obj.optString("filePath", ""),
            header = header,
            blocks = blocks,
            subheadings = subheadings
        )
    }

    private fun JSONArray.toStringList(): List<String> =
        (0 until length()).map { optString(it) }
}
