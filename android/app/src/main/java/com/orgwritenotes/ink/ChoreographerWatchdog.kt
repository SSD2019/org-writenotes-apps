package com.orgwritenotes.ink

import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.Choreographer
import android.view.View

/**
 * Detects a wedged main-thread Choreographer: the main looper still runs, but a frame callback
 * is never delivered (a lost vsync). While wedged, batched stylus MOVE events are never consumed
 * and Android reports "not responding". On Boox E-Ink devices this has been observed with the
 * app fully idle. When detected, clears Choreographer's scheduled flag and re-posts a frame
 * callback so the next vsync is requested again.
 */
class ChoreographerWatchdog {
    private val main = Handler(Looper.getMainLooper())
    private var thread: HandlerThread? = null
    private var worker: Handler? = null
    private var choreographer: Choreographer? = null

    @Volatile private var running = false
    @Volatile private var lastMainMs = 0L
    @Volatile private var lastAckMs = 0L
    private var lastRecoveryMs = 0L

    private var rootView: View? = null
    private var viewRoot: Any? = null
    private var batchedFlag: java.lang.reflect.Field? = null
    private var doConsume: java.lang.reflect.Method? = null
    @Volatile private var batchStuckSinceMs = 0L

    /** Call on the main thread. [decor] is the activity's decor view, used to find its ViewRootImpl. */
    fun start(decor: View) {
        if (running) return
        running = true
        rootView = decor
        choreographer = Choreographer.getInstance()
        val now = SystemClock.uptimeMillis()
        lastMainMs = now
        lastAckMs = now
        val t = HandlerThread("choreo-watchdog").also { it.start() }
        thread = t
        worker = Handler(t.looper).also {
            it.post(tick)
            it.post(fastTick)
        }
    }

    fun stop() {
        running = false
        worker?.removeCallbacksAndMessages(null)
        thread?.quitSafely()
        thread = null
        worker = null
    }

    private var getRootMethod: java.lang.reflect.Method? = null
    private var pendingCountField: java.lang.reflect.Field? = null
    private var processScheduledField: java.lang.reflect.Field? = null
    private var doProcess: java.lang.reflect.Method? = null
    @Volatile private var pendingSinceMs = 0L

    /** Resolves the current ViewRootImpl and its input-queue state via reflection (main thread only). */
    private fun resolveViewRoot() {
        try {
            val decor = rootView ?: return
            if (getRootMethod == null) {
                runCatching { org.lsposed.hiddenapibypass.HiddenApiBypass.addHiddenApiExemptions("Landroid/view/") }
                getRootMethod = View::class.java.getDeclaredMethod("getViewRootImpl").apply { isAccessible = true }
            }
            val root = getRootMethod?.invoke(decor) ?: return
            if (root === viewRoot) return
            val cls = root.javaClass
            batchedFlag = cls.getDeclaredField("mConsumeBatchedInputScheduled").apply { isAccessible = true }
            doConsume = cls.getDeclaredMethod("doConsumeBatchedInput", Long::class.javaPrimitiveType)
                .apply { isAccessible = true }
            pendingCountField = runCatching { cls.getDeclaredField("mPendingInputEventCount").apply { isAccessible = true } }.getOrNull()
            processScheduledField = runCatching { cls.getDeclaredField("mProcessInputEventsScheduled").apply { isAccessible = true } }.getOrNull()
            doProcess = runCatching { cls.getDeclaredMethod("doProcessInputEvents").apply { isAccessible = true } }.getOrNull()
            viewRoot = root
            Log.w(TAG, "tracking ViewRootImpl ${System.identityHashCode(root)}")
        } catch (t: Throwable) {
            Log.w(TAG, "ViewRootImpl reflection unavailable", t)
        }
    }

    /** Input events queued inside ViewRootImpl but never processed: process them by hand. */
    private fun checkPendingInput() {
        val root = viewRoot ?: return
        val count = try { pendingCountField?.getInt(root) ?: 0 } catch (_: Throwable) { 0 }
        val now = SystemClock.uptimeMillis()
        if (count <= 0) {
            pendingSinceMs = 0L
            return
        }
        if (pendingSinceMs == 0L) {
            pendingSinceMs = now
            return
        }
        if (now - pendingSinceMs >= BATCH_STUCK_MS) {
            val scheduled = try { processScheduledField?.getBoolean(root) } catch (_: Throwable) { null }
            pendingSinceMs = 0L
            main.post {
                try {
                    doProcess?.invoke(root)
                } catch (t: Throwable) {
                    Log.w(TAG, "manual process failed", t)
                }
            }
        }
    }

    /**
     * A batch of pen MOVE events is normally consumed within a frame. If ViewRootImpl keeps the
     * "batched input scheduled" flag set for many ticks, the consume callback was lost and queued
     * input would wait forever: consume it by hand.
     */
    private fun checkBatchedInput() {
        val root = viewRoot ?: return
        val flag = batchedFlag ?: return
        val stuck = try { flag.getBoolean(root) } catch (_: Throwable) { false }
        val now = SystemClock.uptimeMillis()
        if (!stuck) {
            batchStuckSinceMs = 0L
            return
        }
        if (batchStuckSinceMs == 0L) {
            batchStuckSinceMs = now
            return
        }
        if (now - batchStuckSinceMs >= BATCH_STUCK_MS) {
            batchStuckSinceMs = 0L
            main.post {
                try {
                    doConsume?.invoke(root, System.nanoTime())
                } catch (t: Throwable) {
                    Log.w(TAG, "manual consume failed", t)
                }
            }
        }
    }

    /** Polls often so queued pen events are released within ~100ms instead of waiting on the panel's frame pace. */
    private val fastTick = object : Runnable {
        override fun run() {
            if (!running) return
            checkBatchedInput()
            checkPendingInput()
            worker?.postDelayed(this, FAST_TICK_MS)
        }
    }

    private val tick = object : Runnable {
        override fun run() {
            if (!running) return
            val probeStart = SystemClock.uptimeMillis()
            main.post { resolveViewRoot() }
            main.post {
                lastMainMs = SystemClock.uptimeMillis()
                choreographer?.postFrameCallback { lastAckMs = SystemClock.uptimeMillis() }
            }
            worker?.postDelayed({ check(probeStart) }, CHECK_DELAY_MS)
            worker?.postDelayed(this, TICK_MS)
        }
    }

    private fun check(probeStart: Long) {
        if (!running) return
        val mainAlive = lastMainMs >= probeStart
        val frameAcked = lastAckMs >= probeStart
        if (mainAlive && !frameAcked) {
            val now = SystemClock.uptimeMillis()
            if (now - lastRecoveryMs < RECOVERY_COOLDOWN_MS) return
            lastRecoveryMs = now
            Log.w(TAG, "Choreographer wedged: main alive, no frame for ${now - probeStart}ms; resetting")
            main.post { recover() }
        }
    }

    private fun recover() {
        val c = choreographer ?: return
        try {
            runCatching { org.lsposed.hiddenapibypass.HiddenApiBypass.addHiddenApiExemptions("Landroid/view/Choreographer;") }
            val f = Choreographer::class.java.getDeclaredField("mFrameScheduled")
            f.isAccessible = true
            Log.w(TAG, "mFrameScheduled was ${f.getBoolean(c)}")
            f.setBoolean(c, false)
        } catch (t: Throwable) {
            Log.w(TAG, "could not reset mFrameScheduled", t)
        }
        val posted = SystemClock.uptimeMillis()
        c.postFrameCallback {
            Log.w(TAG, "frame delivered ${SystemClock.uptimeMillis() - posted}ms after recovery")
            lastAckMs = SystemClock.uptimeMillis()
        }
    }

    private companion object {
        const val TAG = "ChoreoWd"
        const val TICK_MS = 500L
        const val CHECK_DELAY_MS = 600L
        const val RECOVERY_COOLDOWN_MS = 1500L
        const val BATCH_STUCK_MS = 80L
        const val FAST_TICK_MS = 40L
    }
}
