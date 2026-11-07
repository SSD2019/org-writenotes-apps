@file:Suppress("ktlint")

package android.content

import android.content.pm.PackageManager
import org.json.JSONArray
import org.json.JSONObject
import java.awt.Toolkit
import java.awt.datatransfer.DataFlavor
import java.awt.datatransfer.StringSelection
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.ConcurrentHashMap
import javax.imageio.ImageIO

open class Context {
    companion object {
        const val MODE_PRIVATE = 0
        const val CLIPBOARD_SERVICE = "clipboard"
        val instance = Context()
    }

    private val userHome = System.getProperty("user.home", ".")
    private val dataRoot: File = File(
        System.getenv("XDG_DATA_HOME")?.takeIf { it.isNotBlank() } ?: "$userHome/.local/share",
        "org-writenotes"
    ).apply { mkdirs() }

    private val cacheRoot: File = File(
        System.getenv("XDG_CACHE_HOME")?.takeIf { it.isNotBlank() } ?: "$userHome/.cache",
        "org-writenotes"
    ).apply { mkdirs() }

    private val configRoot: File = File(
        System.getenv("XDG_CONFIG_HOME")?.takeIf { it.isNotBlank() } ?: "$userHome/.config",
        "org-writenotes"
    ).apply { mkdirs() }

    private val prefsMap = ConcurrentHashMap<String, SharedPreferences>()

    open val filesDir: File
        get() = dataRoot

    open val cacheDir: File
        get() = cacheRoot

    open val applicationContext: Context
        get() = this

    open val contentResolver: ContentResolver = ContentResolver(this)

    open fun checkSelfPermission(permission: String): Int = PackageManager.PERMISSION_GRANTED

    open fun getSharedPreferences(name: String, mode: Int): SharedPreferences {
        return prefsMap.computeIfAbsent(name) {
            DesktopSharedPreferences(File(configRoot, "$name.json"))
        }
    }

    open fun getExternalFilesDir(type: String?): File? {
        val dir = if (type.isNullOrEmpty()) dataRoot else File(dataRoot, type)
        dir.mkdirs()
        return dir
    }

    open fun getSystemService(name: String): Any? {
        return when (name) {
            CLIPBOARD_SERVICE -> ClipboardManager(this)
            else -> null
        }
    }
}

interface SharedPreferences {
    fun getString(key: String, defValue: String?): String?
    fun getInt(key: String, defValue: Int): Int
    fun getLong(key: String, defValue: Long): Long
    fun getFloat(key: String, defValue: Float): Float
    fun getBoolean(key: String, defValue: Boolean): Boolean
    fun getStringSet(key: String, defValues: Set<String>?): Set<String>?
    fun contains(key: String): Boolean
    fun edit(): Editor

    interface Editor {
        fun putString(key: String, value: String?): Editor
        fun putInt(key: String, value: Int): Editor
        fun putLong(key: String, value: Long): Editor
        fun putFloat(key: String, value: Float): Editor
        fun putBoolean(key: String, value: Boolean): Editor
        fun putStringSet(key: String, values: Set<String>?): Editor
        fun remove(key: String): Editor
        fun clear(): Editor
        fun apply()
        fun commit(): Boolean
    }
}

class DesktopSharedPreferences(private val backingFile: File) : SharedPreferences {
    private val map = ConcurrentHashMap<String, Any>()

    init {
        loadFromDisk()
    }

    @Synchronized
    private fun loadFromDisk() {
        if (!backingFile.exists()) return
        try {
            val json = JSONObject(backingFile.readText(Charsets.UTF_8))
            for (key in json.keys()) {
                val v = json.get(key)
                if (v is JSONArray) {
                    val set = mutableSetOf<String>()
                    for (i in 0 until v.length()) {
                        set.add(v.optString(i))
                    }
                    map[key] = set
                } else if (v != JSONObject.NULL) {
                    map[key] = v
                }
            }
        } catch (_: Exception) {
        }
    }

    @Synchronized
    internal fun saveToDisk() {
        try {
            backingFile.parentFile?.mkdirs()
            val json = JSONObject()
            for ((k, v) in map) {
                when (v) {
                    is Set<*> -> {
                        val arr = JSONArray()
                        v.forEach { arr.put(it?.toString() ?: "") }
                        json.put(k, arr)
                    }
                    else -> json.put(k, v)
                }
            }
            backingFile.writeText(json.toString(2), Charsets.UTF_8)
        } catch (_: Exception) {
        }
    }

    override fun getString(key: String, defValue: String?): String? =
        (map[key] as? String) ?: defValue

    override fun getInt(key: String, defValue: Int): Int =
        (map[key] as? Number)?.toInt() ?: defValue

    override fun getLong(key: String, defValue: Long): Long =
        (map[key] as? Number)?.toLong() ?: defValue

    override fun getFloat(key: String, defValue: Float): Float =
        (map[key] as? Number)?.toFloat() ?: defValue

    override fun getBoolean(key: String, defValue: Boolean): Boolean =
        (map[key] as? Boolean) ?: defValue

    @Suppress("UNCHECKED_CAST")
    override fun getStringSet(key: String, defValues: Set<String>?): Set<String>? =
        (map[key] as? Set<String>)?.toSet() ?: defValues

    override fun contains(key: String): Boolean = map.containsKey(key)

    override fun edit(): SharedPreferences.Editor = EditorImpl()

    private inner class EditorImpl : SharedPreferences.Editor {
        private val pending = mutableMapOf<String, Any?>()
        private var clearRequested = false

        override fun putString(key: String, value: String?): SharedPreferences.Editor {
            pending[key] = value
            return this
        }

        override fun putInt(key: String, value: Int): SharedPreferences.Editor {
            pending[key] = value
            return this
        }

        override fun putLong(key: String, value: Long): SharedPreferences.Editor {
            pending[key] = value
            return this
        }

        override fun putFloat(key: String, value: Float): SharedPreferences.Editor {
            pending[key] = value
            return this
        }

        override fun putBoolean(key: String, value: Boolean): SharedPreferences.Editor {
            pending[key] = value
            return this
        }

        override fun putStringSet(key: String, values: Set<String>?): SharedPreferences.Editor {
            pending[key] = values?.toSet()
            return this
        }

        override fun remove(key: String): SharedPreferences.Editor {
            pending[key] = null
            return this
        }

        override fun clear(): SharedPreferences.Editor {
            clearRequested = true
            return this
        }

        override fun apply() {
            commit()
        }

        override fun commit(): Boolean {
            synchronized(this@DesktopSharedPreferences) {
                if (clearRequested) {
                    map.clear()
                }
                for ((k, v) in pending) {
                    if (v == null) {
                        map.remove(k)
                    } else {
                        map[k] = v
                    }
                }
                saveToDisk()
            }
            return true
        }
    }
}

class ContentValues {
    val values = mutableMapOf<String, Any?>()

    fun put(key: String, value: String?) {
        values[key] = value
    }

    fun put(key: String, value: Int?) {
        values[key] = value
    }
}

class ContentResolver(private val context: Context) {
    fun insert(uri: android.net.Uri, values: ContentValues): android.net.Uri? {
        val displayName = (values.values["name"] as? String)
            ?: "org_writenotes_${System.currentTimeMillis()}.png"
        val relPath = (values.values["relative_path"] as? String) ?: "Pictures/OrgWriteNotes"
        val userHome = System.getProperty("user.home", ".")
        val targetDir = File(userHome, relPath).apply { mkdirs() }
        val file = File(targetDir, displayName)
        return android.net.Uri.fromFile(file)
    }

    fun openOutputStream(uri: android.net.Uri): OutputStream? {
        val f = uri.toFileOrNull() ?: return null
        f.parentFile?.mkdirs()
        return FileOutputStream(f)
    }

    fun openInputStream(uri: android.net.Uri): InputStream? {
        if (uri.raw.startsWith("clipboard://image")) {
            val bytes = ClipboardManager.readClipboardImagePngBytes() ?: return null
            return ByteArrayInputStream(bytes)
        }
        val f = uri.toFileOrNull() ?: return null
        if (!f.exists()) return null
        return FileInputStream(f)
    }

    fun update(uri: android.net.Uri, values: ContentValues, where: String?, selectionArgs: Array<String>?): Int {
        return 1
    }
}

class ClipboardManager(private val context: Context) {
    companion object {
        fun readClipboardImagePngBytes(): ByteArray? {
            return try {
                val cb = Toolkit.getDefaultToolkit().systemClipboard
                if (cb.isDataFlavorAvailable(DataFlavor.imageFlavor)) {
                    val img = cb.getData(DataFlavor.imageFlavor) as? java.awt.Image ?: return null
                    val bimg = if (img is BufferedImage) {
                        img
                    } else {
                        val w = img.getWidth(null).coerceAtLeast(1)
                        val h = img.getHeight(null).coerceAtLeast(1)
                        BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB).also { bi ->
                            val g = bi.createGraphics()
                            g.drawImage(img, 0, 0, null)
                            g.dispose()
                        }
                    }
                    val baos = ByteArrayOutputStream()
                    ImageIO.write(bimg, "PNG", baos)
                    baos.toByteArray()
                } else {
                    null
                }
            } catch (_: Throwable) {
                null
            }
        }
    }

    val primaryClip: ClipData?
        get() {
            return try {
                val cb = Toolkit.getDefaultToolkit().systemClipboard
                if (cb.isDataFlavorAvailable(DataFlavor.imageFlavor)) {
                    ClipData(listOf(ClipData.Item(uri = android.net.Uri.parse("clipboard://image"), text = null)))
                } else if (cb.isDataFlavorAvailable(DataFlavor.stringFlavor)) {
                    val str = cb.getData(DataFlavor.stringFlavor) as? String
                    ClipData(listOf(ClipData.Item(uri = null, text = str)))
                } else {
                    null
                }
            } catch (_: Throwable) {
                null
            }
        }

    fun setPrimaryClip(clip: ClipData) {
        if (clip.itemCount > 0) {
            val text = clip.getItemAt(0).text?.toString()
            if (text != null) {
                try {
                    val sel = StringSelection(text)
                    Toolkit.getDefaultToolkit().systemClipboard.setContents(sel, sel)
                } catch (_: Throwable) {
                }
            }
        }
    }
}

class ClipData(private val items: List<Item>) {
    companion object {
        @JvmStatic
        fun newPlainText(label: CharSequence?, text: CharSequence?): ClipData {
            return ClipData(listOf(Item(uri = null, text = text)))
        }
    }

    val itemCount: Int
        get() = items.size

    fun getItemAt(index: Int): Item = items[index]

    class Item(val uri: android.net.Uri?, val text: CharSequence?) {
        fun coerceToText(context: Context): CharSequence? = text
    }
}
