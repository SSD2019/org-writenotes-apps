package com.orgwritenotes.sync

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ConcurrentLinkedDeque

data class LogEntry(
    val timestamp: Long = System.currentTimeMillis(),
    val level: String,
    val tag: String,
    val message: String
) {
    fun format(): String {
        val sdf = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)
        return "${sdf.format(Date(timestamp))} [$level/$tag] $message"
    }
}

object AppLogger {
    private const val MAX_LOGS = 200
    private val buffer = ConcurrentLinkedDeque<LogEntry>()
    private val _logsFlow = MutableStateFlow<List<LogEntry>>(emptyList())
    val logsFlow: StateFlow<List<LogEntry>> = _logsFlow

    fun d(tag: String, message: String) = log("D", tag, message)
    fun i(tag: String, message: String) = log("I", tag, message)
    fun w(tag: String, message: String) = log("W", tag, message)
    fun e(tag: String, message: String, throwable: Throwable? = null) {
        val msg = if (throwable != null) "$message: ${throwable.stackTraceToString()}" else message
        log("E", tag, msg)
    }

    private fun log(level: String, tag: String, message: String) {
        android.util.Log.println(
            when (level) {
                "D" -> android.util.Log.DEBUG
                "I" -> android.util.Log.INFO
                "W" -> android.util.Log.WARN
                "E" -> android.util.Log.ERROR
                else -> android.util.Log.VERBOSE
            },
            tag,
            message
        )
        synchronized(buffer) {
            buffer.add(LogEntry(level = level, tag = tag, message = message))
            while (buffer.size > MAX_LOGS) {
                buffer.pollFirst()
            }
            // Publish under the lock so concurrent writers can't emit snapshots out of order.
            _logsFlow.value = buffer.toList()
        }
    }

    fun dumpDebugBundle(): String {
        val sb = StringBuilder()
        sb.append("=== Org-WriteNotes Debug Log Bundle ===\n")
        sb.append("Generated at: ${SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())}\n\n")
        for (item in buffer) {
            sb.append(item.format()).append("\n")
        }
        return sb.toString()
    }

    fun clear() {
        buffer.clear()
        _logsFlow.value = emptyList()
    }
}

