package android.os

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import javax.swing.SwingUtilities

object Build {
    @JvmField val MANUFACTURER: String = System.getProperty("org.writenotes.manufacturer", "Linux")
    @JvmField val BRAND: String = System.getProperty("org.writenotes.brand", "Linux")
    @JvmField val MODEL: String = System.getProperty("os.name", "Linux Desktop")
    @JvmField val PRODUCT: String = System.getProperty("os.arch", "x86_64")

    object VERSION {
        @JvmField val SDK_INT: Int = 34
    }

    object VERSION_CODES {
        const val O = 26
        const val Q = 29
        const val R = 30
        const val S = 31
        const val TIRAMISU = 33
        const val UPSIDE_DOWN_CAKE = 34
    }
}

object SystemClock {
    private val startNanos = System.nanoTime()

    @JvmStatic
    fun uptimeMillis(): Long = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startNanos) + 1000L

    @JvmStatic
    fun elapsedRealtime(): Long = uptimeMillis()
}

class Looper private constructor() {
    companion object {
        private val mainLooper = Looper()

        @JvmStatic
        fun getMainLooper(): Looper = mainLooper
    }
}

class Handler(val looper: Looper = Looper.getMainLooper()) {
    companion object {
        private val scheduler = Executors.newSingleThreadScheduledExecutor { r ->
            Thread(r, "OrgWriteNotes-HandlerTimer").apply { isDaemon = true }
        }
    }

    private val scheduledTasks = ConcurrentHashMap<Runnable, MutableList<ScheduledFuture<*>>>()

    fun post(r: Runnable): Boolean {
        if (SwingUtilities.isEventDispatchThread()) {
            SwingUtilities.invokeLater(r)
        } else {
            SwingUtilities.invokeLater(r)
        }
        return true
    }

    fun postDelayed(r: Runnable, delayMillis: Long): Boolean {
        val safeDelay = delayMillis.coerceAtLeast(0L)
        val future = scheduler.schedule({
            SwingUtilities.invokeLater {
                scheduledTasks[r]?.removeIf { it.isDone || it.isCancelled }
                r.run()
            }
        }, safeDelay, TimeUnit.MILLISECONDS)
        scheduledTasks.computeIfAbsent(r) { mutableListOf() }.add(future)
        return true
    }

    fun removeCallbacks(r: Runnable) {
        scheduledTasks.remove(r)?.forEach { it.cancel(false) }
    }
}

object Environment {
    const val DIRECTORY_PICTURES = "Pictures"
    const val DIRECTORY_DOCUMENTS = "Documents"
}

