@file:Suppress("ktlint")

package android.widget

import android.content.Context
import android.os.Handler
import android.os.Looper
import androidx.compose.runtime.mutableStateOf

class Toast private constructor(
    private val text: CharSequence,
    private val duration: Int
) {
    companion object {
        const val LENGTH_SHORT = 0
        const val LENGTH_LONG = 1

        val currentToastMessage = mutableStateOf<String?>(null)
        private val handler = Handler(Looper.getMainLooper())
        private var clearRunnable: Runnable? = null

        @JvmStatic
        fun makeText(context: Context, text: CharSequence, duration: Int): Toast {
            return Toast(text, duration)
        }
    }

    fun show() {
        val msg = text.toString()
        println("[OrgWriteNotes] $msg")
        currentToastMessage.value = msg
        clearRunnable?.let { handler.removeCallbacks(it) }
        val r = Runnable {
            if (currentToastMessage.value == msg) {
                currentToastMessage.value = null
            }
        }
        clearRunnable = r
        handler.postDelayed(r, if (duration == LENGTH_LONG) 3500L else 2000L)
    }
}

