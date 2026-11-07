package android.util

object Base64 {
    const val DEFAULT = 0
    const val NO_WRAP = 2
    const val URL_SAFE = 8

    @JvmStatic
    fun decode(str: String, flags: Int): ByteArray =
        java.util.Base64.getMimeDecoder().decode(str.trim())

    @JvmStatic
    fun decode(input: ByteArray, flags: Int): ByteArray =
        java.util.Base64.getMimeDecoder().decode(input)

    @JvmStatic
    fun encodeToString(input: ByteArray, flags: Int): String =
        java.util.Base64.getEncoder().encodeToString(input)
}

object Log {
    const val VERBOSE = 2
    const val DEBUG = 3
    const val INFO = 4
    const val WARN = 5
    const val ERROR = 6

    @JvmStatic
    fun println(priority: Int, tag: String, msg: String): Int {
        val lvl = when (priority) {
            DEBUG -> "D"
            INFO -> "I"
            WARN -> "W"
            ERROR -> "E"
            else -> "V"
        }
        kotlin.io.println("[$lvl/$tag] $msg")
        return msg.length
    }

    @JvmStatic
    fun d(tag: String, msg: String): Int = println(DEBUG, tag, msg)

    @JvmStatic
    fun i(tag: String, msg: String): Int = println(INFO, tag, msg)

    @JvmStatic
    fun w(tag: String, msg: String, tr: Throwable? = null): Int =
        println(WARN, tag, if (tr != null) "$msg: ${tr.message}" else msg)

    @JvmStatic
    fun e(tag: String, msg: String, tr: Throwable? = null): Int =
        println(ERROR, tag, if (tr != null) "$msg: ${tr.message}" else msg)
}

