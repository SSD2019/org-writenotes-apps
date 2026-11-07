@file:Suppress("ktlint")

package androidx.activity.result.contract

import android.net.Uri
import java.awt.FileDialog
import java.awt.Frame
import java.io.File
import javax.swing.SwingUtilities

abstract class ActivityResultContract<I, O> {
    abstract fun executeDesktop(input: I, callback: (O) -> Unit)
}

object ActivityResultContracts {
    class RequestPermission : ActivityResultContract<String, Boolean>() {
        override fun executeDesktop(input: String, callback: (Boolean) -> Unit) {
            // Desktop Linux does not require Android runtime permission prompts
            callback(true)
        }
    }

    class GetContent : ActivityResultContract<String, Uri?>() {
        override fun executeDesktop(input: String, callback: (Uri?) -> Unit) {
            SwingUtilities.invokeLater {
                try {
                    val dialog = FileDialog(null as Frame?, "Select File", FileDialog.LOAD)
                    if (input.startsWith("image/")) {
                        dialog.setFilenameFilter { _, name ->
                            val lower = name.lowercase()
                            lower.endsWith(".png") ||
                                lower.endsWith(".jpg") ||
                                lower.endsWith(".jpeg") ||
                                lower.endsWith(".webp") ||
                                lower.endsWith(".bmp") ||
                                lower.endsWith(".gif")
                        }
                    }
                    dialog.isVisible = true
                    val dir = dialog.directory
                    val file = dialog.file
                    if (dir != null && file != null) {
                        callback(Uri.fromFile(File(dir, file)))
                    } else {
                        callback(null)
                    }
                } catch (_: Throwable) {
                    callback(null)
                }
            }
        }
    }
}

