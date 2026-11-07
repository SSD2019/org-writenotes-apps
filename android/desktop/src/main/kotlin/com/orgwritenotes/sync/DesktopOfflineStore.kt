@file:Suppress("ktlint")

package com.orgwritenotes.sync

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Desktop Linux implementation of `OfflineStore` backed by JSON files in
 * `~/.local/share/org-writenotes/offline_store/`.
 * Matches the exact API of `android/app/src/main/java/com/orgwritenotes/sync/OfflineStore.kt`.
 */
class OfflineStore(
    private val context: Context,
    name: String = "org_writenotes.db"
) : AutoCloseable {

    companion object {
        const val KEY_ACTIVE_SNAPSHOT = "active_snapshot"
        const val KEY_ACTIVE_NODE = "active_node_id"
        const val KEY_SUMMARIES = "node_summaries"
        fun snapshotKey(nodeId: String) = "snapshot:$nodeId"
    }

    private val storeDir = File(context.filesDir, "offline_store_${name.replace('.', '_')}").apply { mkdirs() }
    private val kvFile = File(storeDir, "kv.json")
    private val queueFile = File(storeDir, "queue.json")
    private val kvMap = mutableMapOf<String, String>()
    private val queueList = mutableListOf<Pair<String, String>>()

    init {
        loadFromDisk()
    }

    @Synchronized
    private fun loadFromDisk() {
        try {
            if (kvFile.exists()) {
                val obj = JSONObject(kvFile.readText(Charsets.UTF_8))
                for (k in obj.keys()) {
                    kvMap[k] = obj.optString(k, "")
                }
            }
            if (queueFile.exists()) {
                val arr = JSONArray(queueFile.readText(Charsets.UTF_8))
                queueList.clear()
                for (i in 0 until arr.length()) {
                    val item = arr.optJSONObject(i) ?: continue
                    queueList.add(item.optString("method", "") to item.optString("params", ""))
                }
            }
        } catch (_: Exception) {
        }
    }

    @Synchronized
    private fun saveKvToDisk() {
        try {
            val obj = JSONObject()
            for ((k, v) in kvMap) {
                obj.put(k, v)
            }
            kvFile.writeText(obj.toString(), Charsets.UTF_8)
        } catch (_: Exception) {
        }
    }

    @Synchronized
    private fun saveQueueToDisk() {
        try {
            val arr = JSONArray()
            for ((m, p) in queueList) {
                arr.put(
                    JSONObject().apply {
                        put("method", m)
                        put("params", p)
                    }
                )
            }
            queueFile.writeText(arr.toString(), Charsets.UTF_8)
        } catch (_: Exception) {
        }
    }

    @Synchronized
    fun putKv(key: String, value: String) {
        kvMap[key] = value
        saveKvToDisk()
    }

    @Synchronized
    fun getKv(key: String): String? = kvMap[key]

    @Synchronized
    fun replaceQueue(items: List<Pair<String, String>>) {
        queueList.clear()
        queueList.addAll(items)
        saveQueueToDisk()
    }

    @Synchronized
    fun loadQueue(): List<Pair<String, String>> = queueList.toList()

    @Synchronized
    fun migrateLegacyJson(dir: File) {
        if (getKv("migrated_v1") != null) return
        runCatching {
            File(dir, "active_snapshot.json").takeIf { it.exists() }?.let {
                putKv(KEY_ACTIVE_SNAPSHOT, it.readText())
                it.delete()
            }
            File(dir, "node_summaries.json").takeIf { it.exists() }?.let {
                putKv(KEY_SUMMARIES, it.readText())
                it.delete()
            }
            File(dir, "offline_queue.json").takeIf { it.exists() }?.let { f ->
                val arr = JSONArray(f.readText())
                val items = (0 until arr.length()).map {
                    val o = arr.getJSONObject(it)
                    o.getString("method") to o.getString("params")
                }
                replaceQueue(items)
                f.delete()
            }
        }
        putKv("migrated_v1", "1")
    }

    override fun close() {}
}

