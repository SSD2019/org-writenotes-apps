package com.orgwritenotes.sync

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import java.io.File
import org.json.JSONArray

/**
 * D3: SQLite-backed node cache + offline mutation queue.
 *
 * Replaces the earlier ad-hoc JSON files. Uses the framework SQLite API directly (no Room/KSP
 * dependency, so offline and CI builds stay simple). Legacy JSON files are imported once.
 */
class OfflineStore(private val context: Context, name: String = "org_writenotes.db") :
    SQLiteOpenHelper(context, name, null, DB_VERSION) {

    companion object {
        private const val DB_VERSION = 1
        const val KEY_ACTIVE_SNAPSHOT = "active_snapshot"
        const val KEY_SUMMARIES = "node_summaries"
        fun snapshotKey(nodeId: String) = "snapshot:$nodeId"
    }

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE kv (key TEXT PRIMARY KEY, value TEXT NOT NULL)")
        db.execSQL(
            "CREATE TABLE queue (id INTEGER PRIMARY KEY AUTOINCREMENT, method TEXT NOT NULL, params TEXT NOT NULL)"
        )
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        // No schema changes yet.
    }

    @Synchronized
    fun putKv(key: String, value: String) {
        val cv = ContentValues().apply {
            put("key", key)
            put("value", value)
        }
        writableDatabase.insertWithOnConflict("kv", null, cv, SQLiteDatabase.CONFLICT_REPLACE)
    }

    @Synchronized
    fun getKv(key: String): String? =
        readableDatabase.query("kv", arrayOf("value"), "key = ?", arrayOf(key), null, null, null).use {
            if (it.moveToFirst()) it.getString(0) else null
        }

    /** Replaces the whole queue atomically, preserving order. */
    @Synchronized
    fun replaceQueue(items: List<Pair<String, String>>) {
        val db = writableDatabase
        db.beginTransaction()
        try {
            db.delete("queue", null, null)
            for ((method, params) in items) {
                db.insert(
                    "queue", null,
                    ContentValues().apply {
                        put("method", method)
                        put("params", params)
                    }
                )
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    @Synchronized
    fun loadQueue(): List<Pair<String, String>> =
        readableDatabase.query("queue", arrayOf("method", "params"), null, null, null, null, "id ASC").use {
            val out = mutableListOf<Pair<String, String>>()
            while (it.moveToNext()) out.add(it.getString(0) to it.getString(1))
            out
        }

    /** One-time import of the pre-SQLite JSON cache directory; imported files are deleted. */
    @Synchronized
    fun migrateLegacyJson(dir: File) {
        if (getKv("migrated_v1") != null) return
        runCatching {
            File(dir, "active_snapshot.json").takeIf { it.exists() }?.let {
                putKv(KEY_ACTIVE_SNAPSHOT, it.readText()); it.delete()
            }
            File(dir, "node_summaries.json").takeIf { it.exists() }?.let {
                putKv(KEY_SUMMARIES, it.readText()); it.delete()
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
}

