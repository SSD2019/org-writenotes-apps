@file:Suppress("ktlint")

package com.orgwritenotes.ink

import android.content.Context
import android.os.SystemClock
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import javax.sound.sampled.AudioFileFormat
import javax.sound.sampled.AudioFormat
import javax.sound.sampled.AudioInputStream
import javax.sound.sampled.AudioSystem
import javax.sound.sampled.Clip
import javax.sound.sampled.DataLine
import javax.sound.sampled.LineEvent
import javax.sound.sampled.TargetDataLine

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
        .put("sketch", sketch)
        .put("audioPath", audioPath)
        .put("startedAt", startedAtEpochMs)
        .put("durationMs", durationMs)
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
 * Desktop Linux implementation of `AudioSketchRecorder` using Java Sound API (`javax.sound.sampled`).
 * Matches the exact singleton API of `android/app/src/main/java/com/orgwritenotes/ink/AudioSketch.kt`.
 */
object AudioSketchRecorder {
    private var targetLine: TargetDataLine? = null
    private var activeClip: Clip? = null
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

    @Synchronized
    fun start(context: Context, sketch: String): Boolean {
        if (targetLine != null) return false
        val file = File(dir(context), sketch.substringBeforeLast('.') + "-${System.currentTimeMillis()}.wav")
        return try {
            val format = AudioFormat(16000f, 16, 1, true, false)
            val info = DataLine.Info(TargetDataLine::class.java, format)
            if (!AudioSystem.isLineSupported(info)) return false
            val line = (AudioSystem.getLine(info) as TargetDataLine).apply {
                open(format)
                start()
            }
            targetLine = line
            currentFile = file
            currentSketch = sketch
            strokeOffsets.clear()
            startElapsed = SystemClock.elapsedRealtime()
            startEpoch = System.currentTimeMillis()
            _recording.value = true

            Thread({
                try {
                    AudioInputStream(line).use { ais ->
                        AudioSystem.write(ais, AudioFileFormat.Type.WAVE, file)
                    }
                } catch (_: Exception) {
                }
            }, "OrgWriteNotes-DesktopAudio").apply { isDaemon = true }.start()
            true
        } catch (_: Exception) {
            runCatching { targetLine?.close() }
            targetLine = null
            file.delete()
            false
        }
    }

    @Synchronized
    fun noteStroke(sketch: String) {
        if (_recording.value && sketch == currentSketch) {
            strokeOffsets.add(SystemClock.elapsedRealtime() - startElapsed)
        }
    }

    @Synchronized
    fun stop(context: Context): AudioSession? {
        val line = targetLine ?: return null
        val file = currentFile
        val sketch = currentSketch
        val stopped = runCatching {
            line.stop()
            line.close()
        }.isSuccess
        targetLine = null
        _recording.value = false
        if (!stopped || file == null || sketch == null) {
            file?.delete()
            return null
        }
        val session = AudioSession(
            sketch = sketch,
            audioPath = file.absolutePath,
            startedAtEpochMs = startEpoch,
            durationMs = (SystemClock.elapsedRealtime() - startElapsed).coerceAtLeast(100L),
            strokeOffsetsMs = strokeOffsets.toList()
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
        val f = File(session.audioPath)
        if (!f.exists()) return
        try {
            val ais = AudioSystem.getAudioInputStream(f)
            val clip = AudioSystem.getClip()
            clip.open(ais)
            if (fromMs > 0L) {
                clip.microsecondPosition = fromMs * 1000L
            }
            clip.addLineListener { ev ->
                if (ev.type == LineEvent.Type.STOP) {
                    stopPlayback()
                    onDone()
                }
            }
            clip.start()
            activeClip = clip
        } catch (_: Exception) {
            stopPlayback()
        }
    }

    fun positionMs(): Long = activeClip?.let { it.microsecondPosition / 1000L } ?: 0L

    fun isPlaying(): Boolean = activeClip?.isRunning == true

    fun seekTo(ms: Long) {
        activeClip?.microsecondPosition = ms.coerceAtLeast(0L) * 1000L
    }

    fun stopPlayback() {
        activeClip?.let {
            runCatching { it.stop() }
            runCatching { it.close() }
        }
        activeClip = null
    }
}

