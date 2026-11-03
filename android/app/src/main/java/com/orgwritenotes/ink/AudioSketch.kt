package com.orgwritenotes.ink

import android.content.Context
import android.media.MediaPlayer
import android.media.MediaRecorder
import android.os.Build
import android.os.SystemClock
import java.io.File
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONArray
import org.json.JSONObject

/** One recorded take: the audio file plus the offsets (ms from start) at which strokes were committed. */
data class AudioSession(
    val sketch: String,
    val audioPath: String,
    val startedAtEpochMs: Long,
    val durationMs: Long,
    val strokeOffsetsMs: List<Long>
) {
    /** Offset of the first stroke strictly after [positionMs], or null. */
    fun nextStroke(positionMs: Long): Long? = strokeOffsetsMs.firstOrNull { it > positionMs + 250 }

    /** Offset of the last stroke strictly before [positionMs] (with a little slack), or 0. */
    fun previousStroke(positionMs: Long): Long = strokeOffsetsMs.lastOrNull { it < positionMs - 1000 } ?: 0L

    fun toJson(): JSONObject = JSONObject()
        .put("sketch", sketch).put("audioPath", audioPath)
        .put("startedAt", startedAtEpochMs).put("durationMs", durationMs)
        .put("strokes", JSONArray(strokeOffsetsMs))

    companion object {
        fun fromJson(o: JSONObject): AudioSession {
            val arr = o.optJSONArray("strokes") ?: JSONArray()
            return AudioSession(
                sketch = o.optString("sketch"),
                audioPath = o.optString("audioPath"),
                startedAtEpochMs = o.optLong("startedAt"),
                durationMs = o.optLong("durationMs"),
                strokeOffsetsMs = (0 until arr.length()).map { arr.getLong(it) }
            )
        }
    }
}

/**
 * E1: records microphone audio while you write and logs when each stroke is committed, so playback
 * can jump between strokes. Audio stays on the tablet (`files/audio/`); only sketches sync to Emacs.
 */
object AudioSketchRecorder {
    private var recorder: MediaRecorder? = null
    private var player: MediaPlayer? = null
    private var currentFile: File? = null
    private var currentSketch: String? = null
    private var startElapsed = 0L
    private var startEpoch = 0L
    private val strokeOffsets = mutableListOf<Long>()

    private val _recording = MutableStateFlow(false)
    val recording: StateFlow<Boolean> = _recording

    private fun dir(context: Context) = File(context.filesDir, "audio").apply { mkdirs() }

    private fun indexFile(context: Context, sketch: String) =
        File(dir(context), sketch.substringBeforeLast('.') + ".sessions.json")

    fun start(context: Context, sketch: String): Boolean {
        if (recorder != null) return false
        val file = File(dir(context), sketch.substringBeforeLast('.') + "-${System.currentTimeMillis()}.m4a")
        return runCatching {
            @Suppress("DEPRECATION")
            val r = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) MediaRecorder(context) else MediaRecorder()
            r.setAudioSource(MediaRecorder.AudioSource.MIC)
            r.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            r.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            r.setOutputFile(file.absolutePath)
            r.prepare()
            r.start()
            recorder = r
            currentFile = file
            currentSketch = sketch
            strokeOffsets.clear()
            startElapsed = SystemClock.elapsedRealtime()
            startEpoch = System.currentTimeMillis()
            _recording.value = true
            true
        }.getOrDefault(false)
    }

    /** Called whenever a stroke is committed on [sketch]; ignored unless recording that sketch. */
    @Synchronized
    fun noteStroke(sketch: String) {
        if (_recording.value && sketch == currentSketch) {
            strokeOffsets.add(SystemClock.elapsedRealtime() - startElapsed)
        }
    }

    fun stop(context: Context): AudioSession? {
        val r = recorder ?: return null
        val file = currentFile ?: return null
        val sketch = currentSketch ?: return null
        runCatching { r.stop() }
        r.release()
        recorder = null
        _recording.value = false
        val session = AudioSession(
            sketch, file.absolutePath, startEpoch,
            SystemClock.elapsedRealtime() - startElapsed, synchronized(this) { strokeOffsets.toList() }
        )
        val idx = indexFile(context, sketch)
        val arr = runCatching { JSONArray(idx.readText()) }.getOrDefault(JSONArray())
        arr.put(session.toJson())
        idx.writeText(arr.toString())
        return session
    }

    fun sessions(context: Context, sketch: String): List<AudioSession> {
        val idx = indexFile(context, sketch)
        if (!idx.exists()) return emptyList()
        return runCatching {
            val arr = JSONArray(idx.readText())
            (0 until arr.length()).map { AudioSession.fromJson(arr.getJSONObject(it)) }
                .filter { File(it.audioPath).exists() }
        }.getOrDefault(emptyList())
    }

    fun play(session: AudioSession, fromMs: Long = 0L, onDone: () -> Unit = {}) {
        stopPlayback()
        player = MediaPlayer().apply {
            setDataSource(session.audioPath)
            prepare()
            seekTo(fromMs.toInt())
            setOnCompletionListener { stopPlayback(); onDone() }
            start()
        }
    }

    fun positionMs(): Long = player?.currentPosition?.toLong() ?: 0L
    fun isPlaying(): Boolean = player?.isPlaying == true
    fun seekTo(ms: Long) { player?.seekTo(ms.toInt()) }

    fun stopPlayback() {
        player?.let { runCatching { it.stop() }; it.release() }
        player = null
    }
}

