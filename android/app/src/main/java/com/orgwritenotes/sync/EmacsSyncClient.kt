package com.orgwritenotes.sync

import android.content.Context
import com.orgwritenotes.model.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.*
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

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
    val paramsJson: String
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
    private val offlineMutationQueue = ConcurrentLinkedQueue<QueuedMutation>()

    private val httpClient = OkHttpClient.Builder()
        .pingInterval(15, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .build()

    private var webSocket: WebSocket? = null
    private val rpcIdGenerator = AtomicInteger(1)
    private val pendingCallbacks = ConcurrentHashMap<Int, (JSONObject) -> Unit>()
    private val commitDebounceJobs = ConcurrentHashMap<String, Job>()

    companion object {
        const val INK_COMMIT_DEBOUNCE_MS = 3000L
    }

    private val _connectionState = MutableStateFlow(ConnectionState.DISCONNECTED)
    val connectionState: StateFlow<ConnectionState> = _connectionState

    private val _nodeSummaries = MutableStateFlow<List<OrgNodeSummary>>(emptyList())
    val nodeSummaries: StateFlow<List<OrgNodeSummary>> = _nodeSummaries

    private val _activeSnapshot = MutableStateFlow<OrgNodeSnapshot?>(null)
    val activeSnapshot: StateFlow<OrgNodeSnapshot?> = _activeSnapshot

    private val _pendingOfflineCount = MutableStateFlow(0)
    val pendingOfflineCount: StateFlow<Int> = _pendingOfflineCount

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
        webSocket?.cancel()
        _connectionState.value = ConnectionState.CONNECTING

        val request = Request.Builder().url(url).build()
        webSocket = httpClient.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                _connectionState.value =
                    if (isAdb) ConnectionState.CONNECTED_ADB else ConnectionState.CONNECTED_NETWORK

                // Replay any queued offline mutations first
                flushOfflineMutationQueue()

                val params = JSONObject().apply {
                    put("clientName", "Galaxy Tab S7")
                    put("version", "0.2.0")
                    if (token != null) put("token", token)
                }
                sendRpc("session.hello", params, queueIfOffline = false) { result ->
                    val activeId = result.optString("activeNodeId", "")
                    refreshNodeList()
                    if (activeId.isNotEmpty() && activeId != "null") {
                        fetchNode(activeId)
                    }
                }
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                handleIncomingMessage(text)
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                _connectionState.value = if (_activeSnapshot.value != null) {
                    ConnectionState.OFFLINE_CACHED
                } else {
                    ConnectionState.ERROR
                }
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                _connectionState.value = if (_activeSnapshot.value != null) {
                    ConnectionState.OFFLINE_CACHED
                } else {
                    ConnectionState.DISCONNECTED
                }
            }
        })
    }

    fun refreshNodeList(query: String = "") {
        val params = JSONObject().apply {
            put("filter", "pushed_history")
            put("query", query)
        }
        sendRpc("node.list", params, queueIfOffline = false) { result ->
            val arr = result.optJSONArray("nodes") ?: return@sendRpc
            val remoteList = mutableListOf<OrgNodeSummary>()
            for (i in 0 until arr.length()) {
                val obj = arr.getJSONObject(i)
                val nid = obj.optString("nodeId", "")
                if (nid.isNotEmpty()) {
                    remoteList.add(
                        OrgNodeSummary(
                            nodeId = nid,
                            filePath = obj.optString("filePath", ""),
                            level = obj.optInt("level", 1),
                            todoKeyword = obj.optString("todoKeyword").takeIf { it.isNotEmpty() && it != "null" },
                            priority = obj.optString("priority").takeIf { it.isNotEmpty() && it != "null" },
                            title = obj.optString("title", "Untitled"),
                            tags = obj.optJSONArray("tags")?.toStringList() ?: emptyList()
                        )
                    )
                }
            }
            if (remoteList.isNotEmpty()) {
                val merged = (remoteList + _nodeSummaries.value)
                    .distinctBy { it.nodeId }
                    .take(50)
                _nodeSummaries.value = merged
                saveCachedSummariesToDisk(merged)
            }
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
        if (!isConnected()) {
            loadCachedSnapshotForNode(nodeId)?.let {
                _activeSnapshot.value = it
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
        val priorities = listOf("A", "B", "C")
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
        tags: List<String>? = null
    ) {
        val params = JSONObject().apply {
            put("nodeId", nodeId)
            if (todoKeyword != null) put("todoKeyword", todoKeyword)
            if (priority != null) put("priority", priority)
            if (title != null) put("title", title)
            if (tags != null) put("tags", JSONArray(tags))
        }
        sendRpc("node.updateHeader", params, queueIfOffline = true) { result ->
            updateActiveSnapshot(result)
        }
    }

    fun toggleCheckbox(nodeId: String, line: Int, checked: Boolean) {
        val params = JSONObject().apply {
            put("nodeId", nodeId)
            put("line", line)
            put("state", if (checked) "checked" else "unchecked")
        }
        sendRpc("node.toggleCheckbox", params, queueIfOffline = true) { result ->
            updateActiveSnapshot(result)
        }
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

    fun insertInkBlock(
        nodeId: String,
        afterLine: Int? = null,
        width: Int = 1200,
        height: Int = 600
    ) {
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
        deleteFileOnDisk: Boolean = true
    ) {
        // Optimistically remove ink block from active snapshot immediately
        val current = _activeSnapshot.value
        if (current != null && current.nodeId == nodeId) {
            val updatedBlocks = current.blocks.filterNot {
                it is OrgBlock.InkCanvasBlock && it.fileName == fileName
            }
            _activeSnapshot.value = current.copy(blocks = updatedBlocks)
        }

        // Cancel pending debounce commits for this file
        commitDebounceJobs[fileName]?.cancel()
        commitDebounceJobs.remove(fileName)

        val params = JSONObject().apply {
            put("nodeId", nodeId)
            put("fileName", fileName)
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
     * schedules a debounced `.svgz` full binary commit (3.0s idle) to update
     * the common directory file and refresh Emacs inline display.
     */
    fun streamStrokeAndScheduleCommit(
        nodeId: String,
        fileName: String,
        pathXml: String,
        canvasWidth: Int,
        canvasHeight: Int,
        exportSvgzBase64Provider: () -> String
    ) {
        val svgzBase64 = exportSvgzBase64Provider()
        if (svgzBase64.isNotEmpty()) {
            val current = _activeSnapshot.value
            if (current != null && current.nodeId == nodeId) {
                val updatedBlocks = current.blocks.map { blk ->
                    if (blk is OrgBlock.InkCanvasBlock && blk.fileName == fileName) {
                        blk.copy(
                            width = canvasWidth,
                            height = canvasHeight,
                            svgzBase64 = svgzBase64
                        )
                    } else {
                        blk
                    }
                }
                _activeSnapshot.value = current.copy(blocks = updatedBlocks)
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

        commitDebounceJobs[fileName]?.cancel()
        commitDebounceJobs[fileName] = scope.launch(Dispatchers.IO) {
            delay(INK_COMMIT_DEBOUNCE_MS)
            if (svgzBase64.isNotEmpty()) {
                val commitParams = JSONObject().apply {
                    put("nodeId", nodeId)
                    put("fileName", fileName)
                    put("width", canvasWidth)
                    put("height", canvasHeight)
                    put("svgzBase64", svgzBase64)
                }
                sendRpc("ink.commit", commitParams, queueIfOffline = true)
            }
        }
    }

    private fun isConnected(): Boolean {
        val st = _connectionState.value
        return st == ConnectionState.CONNECTED_ADB || st == ConnectionState.CONNECTED_NETWORK
    }

    private fun sendRpc(
        method: String,
        params: JSONObject,
        queueIfOffline: Boolean = false,
        onResult: ((JSONObject) -> Unit)? = null
    ) {
        val ws = webSocket
        if (ws == null || !isConnected()) {
            if (queueIfOffline) {
                offlineMutationQueue.add(QueuedMutation(method, params.toString()))
                _pendingOfflineCount.value = offlineMutationQueue.size
                persistOfflineQueueToDisk()
            }
            return
        }

        val id = rpcIdGenerator.getAndIncrement()
        if (onResult != null) {
            pendingCallbacks[id] = onResult
        }
        val envelope = JSONObject().apply {
            put("jsonrpc", "2.0")
            put("id", id)
            put("method", method)
            put("params", params)
        }
        val sent = ws.send(envelope.toString())
        if (!sent && queueIfOffline) {
            offlineMutationQueue.add(QueuedMutation(method, params.toString()))
            _pendingOfflineCount.value = offlineMutationQueue.size
            persistOfflineQueueToDisk()
        }
    }

    private fun flushOfflineMutationQueue() {
        while (true) {
            val item = offlineMutationQueue.poll() ?: break
            runCatching {
                val params = JSONObject(item.paramsJson)
                sendRpc(item.method, params, queueIfOffline = false)
            }
        }
        _pendingOfflineCount.value = 0
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
        val snap = parseSnapshot(obj)
        _activeSnapshot.value = snap
        saveCachedSnapshotToDisk(snap.nodeId, obj)
        if (snap.nodeId.isNotEmpty()) {
            val summary = summaryFromSnapshot(snap)
            val updatedHistory = (listOf(summary) + _nodeSummaries.value.filterNot { it.nodeId == snap.nodeId })
                .take(50)
            _nodeSummaries.value = updatedHistory
            saveCachedSummariesToDisk(updatedHistory)
        }
    }

    private fun saveCachedSnapshotToDisk(nodeId: String, obj: JSONObject) {
        val dir = cacheDir ?: return
        runCatching {
            File(dir, "active_snapshot.json").writeText(obj.toString())
            if (nodeId.isNotEmpty()) {
                File(dir, "node_${nodeId.hashCode().toUInt()}.json").writeText(obj.toString())
            }
        }
    }

    private fun loadCachedSnapshotForNode(nodeId: String): OrgNodeSnapshot? {
        val dir = cacheDir ?: return null
        return runCatching {
            val f = File(dir, "node_${nodeId.hashCode().toUInt()}.json")
            if (f.exists()) parseSnapshot(JSONObject(f.readText())) else null
        }.getOrNull()
    }

    private fun saveCachedSummariesToDisk(summaries: List<OrgNodeSummary>) {
        val dir = cacheDir ?: return
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
                })
            }
            File(dir, "node_summaries.json").writeText(arr.toString())
        }
    }

    private fun persistOfflineQueueToDisk() {
        val dir = cacheDir ?: return
        runCatching {
            val arr = JSONArray()
            for (q in offlineMutationQueue) {
                arr.put(JSONObject().apply {
                    put("method", q.method)
                    put("params", q.paramsJson)
                })
            }
            File(dir, "offline_queue.json").writeText(arr.toString())
        }
    }

    private fun loadCachedStateFromDisk() {
        val dir = cacheDir ?: return
        runCatching {
            val snapFile = File(dir, "active_snapshot.json")
            if (snapFile.exists()) {
                _activeSnapshot.value = parseSnapshot(JSONObject(snapFile.readText()))
            }
            val sumFile = File(dir, "node_summaries.json")
            if (sumFile.exists()) {
                val arr = JSONArray(sumFile.readText())
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
                                tags = obj.optJSONArray("tags")?.toStringList() ?: emptyList()
                            )
                        )
                    }
                }
                _nodeSummaries.value = list.distinctBy { it.nodeId }.take(50)
            }
            val qFile = File(dir, "offline_queue.json")
            if (qFile.exists()) {
                val arr = JSONArray(qFile.readText())
                for (i in 0 until arr.length()) {
                    val item = arr.getJSONObject(i)
                    offlineMutationQueue.add(
                        QueuedMutation(item.getString("method"), item.getString("params"))
                    )
                }
                _pendingOfflineCount.value = offlineMutationQueue.size
            }
        }
    }

    private fun handleIncomingMessage(text: String) {
        val msg = JSONObject(text)
        if (msg.has("id") && msg.has("result")) {
            val id = msg.getInt("id")
            val result = msg.optJSONObject("result") ?: JSONObject()
            pendingCallbacks.remove(id)?.invoke(result)
        } else if (msg.has("method")) {
            when (msg.getString("method")) {
                "emacs.focusNode", "emacs.nodeModified" -> {
                    val params = msg.optJSONObject("params") ?: return
                    updateActiveSnapshot(params)
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
            properties = propsMap
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
            }
        }

        return OrgNodeSnapshot(
            nodeId = obj.optString("nodeId", ""),
            etag = obj.optString("etag", ""),
            filePath = obj.optString("filePath", ""),
            header = header,
            blocks = blocks
        )
    }

    private fun JSONArray.toStringList(): List<String> =
        (0 until length()).map { optString(it) }
}
