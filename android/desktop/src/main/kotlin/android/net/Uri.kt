@file:Suppress("ktlint")

package android.net

import java.io.File
import java.net.URI

class Uri(val raw: String) {
    companion object {
        @JvmStatic
        fun parse(uriString: String): Uri = Uri(uriString)

        @JvmStatic
        fun fromFile(file: File): Uri = Uri(file.toURI().toString())
    }

    fun toFileOrNull(): File? {
        return try {
            when {
                raw.startsWith("file:") -> File(URI(raw))
                else -> File(raw)
            }
        } catch (_: Exception) {
            File(raw)
        }
    }

    override fun toString(): String = raw
}

