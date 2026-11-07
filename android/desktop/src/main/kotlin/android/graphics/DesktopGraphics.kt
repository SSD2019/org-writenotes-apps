@file:Suppress("ktlint")

package android.graphics

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.asComposeImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import org.jetbrains.skia.ColorAlphaType
import org.jetbrains.skia.ColorType
import org.jetbrains.skia.Font
import org.jetbrains.skia.ImageInfo
import java.awt.image.BufferedImage
import java.io.InputStream
import java.io.OutputStream
import javax.imageio.ImageIO
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

object Color {
    const val BLACK: Int = 0xFF000000.toInt()
    const val DARK_GRAY: Int = 0xFF444444.toInt()
    const val GRAY: Int = 0xFF888888.toInt()
    const val LIGHT_GRAY: Int = 0xFFCCCCCC.toInt()
    const val WHITE: Int = 0xFFFFFFFF.toInt()
    const val RED: Int = 0xFFFF0000.toInt()
    const val GREEN: Int = 0xFF00FF00.toInt()
    const val BLUE: Int = 0xFF0000FF.toInt()
    const val YELLOW: Int = 0xFFFFFF00.toInt()
    const val CYAN: Int = 0xFF00FFFF.toInt()
    const val MAGENTA: Int = 0xFFFF00FF.toInt()
    const val TRANSPARENT: Int = 0x00000000

    @JvmStatic
    fun argb(alpha: Int, red: Int, green: Int, blue: Int): Int {
        return ((alpha and 0xFF) shl 24) or
            ((red and 0xFF) shl 16) or
            ((green and 0xFF) shl 8) or
            (blue and 0xFF)
    }

    @JvmStatic
    fun rgb(red: Int, green: Int, blue: Int): Int = argb(255, red, green, blue)

    @JvmStatic
    fun alpha(color: Int): Int = (color ushr 24) and 0xFF

    @JvmStatic
    fun red(color: Int): Int = (color ushr 16) and 0xFF

    @JvmStatic
    fun green(color: Int): Int = (color ushr 8) and 0xFF

    @JvmStatic
    fun blue(color: Int): Int = color and 0xFF

    @JvmStatic
    fun parseColor(colorString: String): Int {
        val s = colorString.trim()
        if (s.startsWith("#")) {
            val hex = s.substring(1)
            return when (hex.length) {
                6 -> (0xFF000000L or hex.toLong(16)).toInt()
                8 -> hex.toLong(16).toInt()
                3 -> {
                    val r = hex[0].digitToInt(16) * 17
                    val g = hex[1].digitToInt(16) * 17
                    val b = hex[2].digitToInt(16) * 17
                    rgb(r, g, b)
                }
                else -> BLACK
            }
        }
        return BLACK
    }
}

class Rect(
    @JvmField var left: Int = 0,
    @JvmField var top: Int = 0,
    @JvmField var right: Int = 0,
    @JvmField var bottom: Int = 0
) {
    constructor(src: Rect) : this(src.left, src.top, src.right, src.bottom)

    companion object {
        @JvmStatic
        fun intersects(a: Rect, b: Rect): Boolean {
            return a.left < b.right && b.left < a.right && a.top < b.bottom && b.top < a.bottom
        }
    }

    val isEmpty: Boolean
        get() = left >= right || top >= bottom

    fun width(): Int = right - left
    fun height(): Int = bottom - top
    fun centerX(): Int = (left + right) shr 1
    fun centerY(): Int = (top + bottom) shr 1

    fun setEmpty() {
        left = 0
        top = 0
        right = 0
        bottom = 0
    }

    fun set(l: Int, t: Int, r: Int, b: Int) {
        left = l
        top = t
        right = r
        bottom = b
    }

    fun set(src: Rect) {
        left = src.left
        top = src.top
        right = src.right
        bottom = src.bottom
    }

    fun offset(dx: Int, dy: Int) {
        left += dx
        top += dy
        right += dx
        bottom += dy
    }

    fun inset(dx: Int, dy: Int) {
        left += dx
        top += dy
        right -= dx
        bottom -= dy
    }

    fun intersect(l: Int, t: Int, r: Int, b: Int): Boolean {
        if (left < r && l < right && top < b && t < bottom) {
            if (left < l) left = l
            if (top < t) top = t
            if (right > r) right = r
            if (bottom > b) bottom = b
            return true
        }
        return false
    }

    fun intersect(r: Rect): Boolean = intersect(r.left, r.top, r.right, r.bottom)

    fun union(l: Int, t: Int, r: Int, b: Int) {
        if (l < r && t < b) {
            if (left < right && top < bottom) {
                if (left > l) left = l
                if (top > t) top = t
                if (right < r) right = r
                if (bottom < b) bottom = b
            } else {
                left = l
                top = t
                right = r
                bottom = b
            }
        }
    }

    fun union(r: Rect) = union(r.left, r.top, r.right, r.bottom)
}

class RectF(
    @JvmField var left: Float = 0f,
    @JvmField var top: Float = 0f,
    @JvmField var right: Float = 0f,
    @JvmField var bottom: Float = 0f
) {
    constructor(src: RectF) : this(src.left, src.top, src.right, src.bottom)
    constructor(src: Rect) : this(src.left.toFloat(), src.top.toFloat(), src.right.toFloat(), src.bottom.toFloat())

    companion object {
        @JvmStatic
        fun intersects(a: RectF, b: RectF): Boolean {
            return a.left < b.right && b.left < a.right && a.top < b.bottom && b.top < a.bottom
        }
    }

    val isEmpty: Boolean
        get() = left >= right || top >= bottom

    fun width(): Float = right - left
    fun height(): Float = bottom - top
    fun centerX(): Float = (left + right) * 0.5f
    fun centerY(): Float = (top + bottom) * 0.5f

    fun setEmpty() {
        left = 0f
        top = 0f
        right = 0f
        bottom = 0f
    }

    fun set(l: Float, t: Float, r: Float, b: Float) {
        left = l
        top = t
        right = r
        bottom = b
    }

    fun set(src: RectF) {
        left = src.left
        top = src.top
        right = src.right
        bottom = src.bottom
    }

    fun set(src: Rect) {
        left = src.left.toFloat()
        top = src.top.toFloat()
        right = src.right.toFloat()
        bottom = src.bottom.toFloat()
    }

    fun offset(dx: Float, dy: Float) {
        left += dx
        top += dy
        right += dx
        bottom += dy
    }

    fun inset(dx: Float, dy: Float) {
        left += dx
        top += dy
        right -= dx
        bottom -= dy
    }

    fun contains(x: Float, y: Float): Boolean {
        return left < right && top < bottom && x >= left && x < right && y >= top && y < bottom
    }

    fun contains(r: RectF): Boolean {
        return left < right && top < bottom &&
            left <= r.left && top <= r.top && right >= r.right && bottom >= r.bottom
    }

    fun intersect(l: Float, t: Float, r: Float, b: Float): Boolean {
        if (left < r && l < right && top < b && t < bottom) {
            if (left < l) left = l
            if (top < t) top = t
            if (right > r) right = r
            if (bottom > b) bottom = b
            return true
        }
        return false
    }

    fun intersect(r: RectF): Boolean = intersect(r.left, r.top, r.right, r.bottom)

    fun union(l: Float, t: Float, r: Float, b: Float) {
        if (l < r && t < b) {
            if (left < right && top < bottom) {
                if (left > l) left = l
                if (top > t) top = t
                if (right < r) right = r
                if (bottom < b) bottom = b
            } else {
                left = l
                top = t
                right = r
                bottom = b
            }
        }
    }

    fun union(r: RectF) = union(r.left, r.top, r.right, r.bottom)
}

open class PathEffect()

class DashPathEffect(val intervals: FloatArray, val phase: Float) : PathEffect()

class Paint(val flags: Int = 0) {
    constructor(src: Paint) : this(src.flags) {
        color = src.color
        style = src.style
        strokeWidth = src.strokeWidth
        strokeCap = src.strokeCap
        strokeJoin = src.strokeJoin
        textSize = src.textSize
        textAlign = src.textAlign
        isAntiAlias = src.isAntiAlias
        isFakeBoldText = src.isFakeBoldText
        isFilterBitmap = src.isFilterBitmap
        pathEffect = src.pathEffect
    }

    companion object {
        const val ANTI_ALIAS_FLAG = 1
        const val FILTER_BITMAP_FLAG = 2
    }

    enum class Style {
        FILL,
        STROKE,
        FILL_AND_STROKE
    }

    enum class Cap {
        BUTT,
        ROUND,
        SQUARE
    }

    enum class Join {
        MITER,
        ROUND,
        BEVEL
    }

    enum class Align {
        LEFT,
        CENTER,
        RIGHT
    }

    var color: Int = Color.BLACK
    var style: Style = Style.FILL
    var strokeWidth: Float = 1f
    var strokeCap: Cap = Cap.BUTT
    var strokeJoin: Join = Join.MITER
    var textSize: Float = 14f
    var textAlign: Align = Align.LEFT
    var isAntiAlias: Boolean = (flags and ANTI_ALIAS_FLAG) != 0
    var isFakeBoldText: Boolean = false
    var isFilterBitmap: Boolean = (flags and FILTER_BITMAP_FLAG) != 0
    var pathEffect: PathEffect? = null

    var alpha: Int
        get() = Color.alpha(color)
        set(value) {
            color = (color and 0x00FFFFFF) or ((value.coerceIn(0, 255)) shl 24)
        }
}

class Path() {
    internal val composePath = androidx.compose.ui.graphics.Path()

    constructor(src: Path) : this() {
        composePath.addPath(src.composePath)
    }

    val isEmpty: Boolean
        get() = composePath.isEmpty

    fun reset() {
        composePath.reset()
    }

    fun moveTo(x: Float, y: Float) {
        composePath.moveTo(x, y)
    }

    fun lineTo(x: Float, y: Float) {
        composePath.lineTo(x, y)
    }

    fun quadTo(x1: Float, y1: Float, x2: Float, y2: Float) {
        composePath.quadraticBezierTo(x1, y1, x2, y2)
    }

    fun close() {
        composePath.close()
    }
}

class Bitmap(
    @JvmField val width: Int,
    @JvmField val height: Int,
    val config: Config = Config.ARGB_8888
) {
    enum class Config {
        ARGB_8888,
        RGB_565
    }

    enum class CompressFormat {
        PNG,
        JPEG,
        WEBP
    }

    /**
     * Raw RGBA_8888 pixel buffer written directly by `liborgwritenotes_jni.so`
     * (`lockDesktopBitmapAndRender` in `write_bridge_jni.cpp`).
     */
    @JvmField
    val rgbaPixels: ByteArray = ByteArray((width.coerceAtLeast(1)) * (height.coerceAtLeast(1)) * 4)

    @JvmField
    @Volatile
    var generation: Int = 0

    @Volatile
    var isRecycled: Boolean = false
        private set

    private var cachedImageBitmap: ImageBitmap? = null
    private var cachedGen: Int = -1

    companion object {
        @JvmStatic
        fun createBitmap(width: Int, height: Int, config: Config): Bitmap {
            return Bitmap(width.coerceAtLeast(1), height.coerceAtLeast(1), config)
        }

        @JvmStatic
        fun createScaledBitmap(src: Bitmap, dstWidth: Int, dstHeight: Int, filter: Boolean): Bitmap {
            val w = dstWidth.coerceAtLeast(1)
            val h = dstHeight.coerceAtLeast(1)
            if (w == src.width && h == src.height) {
                return src.copy(src.config, true)
            }
            val dst = Bitmap(w, h, src.config)
            val sw = src.width.coerceAtLeast(1)
            val sh = src.height.coerceAtLeast(1)
            for (y in 0 until h) {
                val sy = (y * sh / h).coerceIn(0, sh - 1)
                for (x in 0 until w) {
                    val sx = (x * sw / w).coerceIn(0, sw - 1)
                    val sIdx = (sy * sw + sx) * 4
                    val dIdx = (y * w + x) * 4
                    dst.rgbaPixels[dIdx] = src.rgbaPixels[sIdx]
                    dst.rgbaPixels[dIdx + 1] = src.rgbaPixels[sIdx + 1]
                    dst.rgbaPixels[dIdx + 2] = src.rgbaPixels[sIdx + 2]
                    dst.rgbaPixels[dIdx + 3] = src.rgbaPixels[sIdx + 3]
                }
            }
            dst.generation = 1
            return dst
        }
    }

    fun copy(config: Config, isMutable: Boolean): Bitmap {
        val dup = Bitmap(width, height, config)
        System.arraycopy(rgbaPixels, 0, dup.rgbaPixels, 0, rgbaPixels.size)
        dup.generation = generation + 1
        return dup
    }

    fun eraseColor(color: Int) {
        val r = Color.red(color).toByte()
        val g = Color.green(color).toByte()
        val b = Color.blue(color).toByte()
        val a = Color.alpha(color).toByte()
        var i = 0
        val n = rgbaPixels.size
        while (i + 3 < n) {
            rgbaPixels[i] = r
            rgbaPixels[i + 1] = g
            rgbaPixels[i + 2] = b
            rgbaPixels[i + 3] = a
            i += 4
        }
        generation++
    }

    fun getPixel(x: Int, y: Int): Int {
        if (x !in 0 until width || y !in 0 until height) return 0
        val idx = (y * width + x) * 4
        val r = rgbaPixels[idx].toInt() and 0xFF
        val g = rgbaPixels[idx + 1].toInt() and 0xFF
        val b = rgbaPixels[idx + 2].toInt() and 0xFF
        val a = rgbaPixels[idx + 3].toInt() and 0xFF
        return Color.argb(a, r, g, b)
    }

    fun setPixel(x: Int, y: Int, color: Int) {
        if (x !in 0 until width || y !in 0 until height) return
        val idx = (y * width + x) * 4
        rgbaPixels[idx] = Color.red(color).toByte()
        rgbaPixels[idx + 1] = Color.green(color).toByte()
        rgbaPixels[idx + 2] = Color.blue(color).toByte()
        rgbaPixels[idx + 3] = Color.alpha(color).toByte()
        generation++
    }

    fun recycle() {
        isRecycled = true
        cachedImageBitmap = null
    }

    @Synchronized
    fun toComposeImageBitmap(): ImageBitmap {
        val curGen = generation
        val existing = cachedImageBitmap
        if (existing != null && cachedGen == curGen) {
            return existing
        }
        val w = this.width.coerceAtLeast(1)
        val h = this.height.coerceAtLeast(1)
        val skiaInfo = ImageInfo(
            w,
            h,
            ColorType.RGBA_8888,
            ColorAlphaType.UNPREMUL
        )
        val skia = org.jetbrains.skia.Bitmap()
        skia.installPixels(skiaInfo, rgbaPixels, w * 4)
        skia.notifyPixelsChanged()
        val img = skia.asComposeImageBitmap()
        cachedImageBitmap = img
        cachedGen = curGen
        return img
    }

    fun compress(format: CompressFormat, quality: Int, stream: OutputStream): Boolean {
        return try {
            val bimg = BufferedImage(width.coerceAtLeast(1), height.coerceAtLeast(1), BufferedImage.TYPE_INT_ARGB)
            var idx = 0
            for (y in 0 until height) {
                for (x in 0 until width) {
                    val r = rgbaPixels[idx].toInt() and 0xFF
                    val g = rgbaPixels[idx + 1].toInt() and 0xFF
                    val b = rgbaPixels[idx + 2].toInt() and 0xFF
                    val a = rgbaPixels[idx + 3].toInt() and 0xFF
                    val argb = (a shl 24) or (r shl 16) or (g shl 8) or b
                    bimg.setRGB(x, y, argb)
                    idx += 4
                }
            }
            val fmtName = when (format) {
                CompressFormat.PNG -> "PNG"
                CompressFormat.JPEG -> "JPEG"
                CompressFormat.WEBP -> "PNG"
            }
            ImageIO.write(bimg, fmtName, stream)
        } catch (_: Exception) {
            false
        }
    }
}

object BitmapFactory {
    class Options {
        @JvmField var inJustDecodeBounds: Boolean = false
        @JvmField var inSampleSize: Int = 1
        @JvmField var outWidth: Int = 0
        @JvmField var outHeight: Int = 0
    }

    @JvmStatic
    fun decodeByteArray(data: ByteArray, offset: Int, length: Int, opts: Options? = null): Bitmap? {
        return try {
            val bais = java.io.ByteArrayInputStream(data, offset, length)
            decodeStream(bais, null, opts)
        } catch (_: Exception) {
            null
        }
    }

    @JvmStatic
    fun decodeStream(inputStream: InputStream, outPadding: Rect? = null, opts: Options? = null): Bitmap? {
        return try {
            val bimg = ImageIO.read(inputStream) ?: return null
            val sample = (opts?.inSampleSize ?: 1).coerceAtLeast(1)
            val srcW = bimg.width
            val srcH = bimg.height
            opts?.outWidth = srcW / sample
            opts?.outHeight = srcH / sample
            if (opts?.inJustDecodeBounds == true) {
                return null
            }
            val dstW = (srcW / sample).coerceAtLeast(1)
            val dstH = (srcH / sample).coerceAtLeast(1)
            val bmp = Bitmap.createBitmap(dstW, dstH, Bitmap.Config.ARGB_8888)
            var idx = 0
            for (y in 0 until dstH) {
                val sy = (y * sample).coerceIn(0, srcH - 1)
                for (x in 0 until dstW) {
                    val sx = (x * sample).coerceIn(0, srcW - 1)
                    val argb = bimg.getRGB(sx, sy)
                    bmp.rgbaPixels[idx] = ((argb ushr 16) and 0xFF).toByte()
                    bmp.rgbaPixels[idx + 1] = ((argb ushr 8) and 0xFF).toByte()
                    bmp.rgbaPixels[idx + 2] = (argb and 0xFF).toByte()
                    bmp.rgbaPixels[idx + 3] = ((argb ushr 24) and 0xFF).toByte()
                    idx += 4
                }
            }
            bmp.generation = 1
            bmp
        } catch (_: Exception) {
            null
        }
    }
}

class Canvas {
    private var targetBitmap: Bitmap? = null
    internal var drawScope: DrawScope? = null
    internal var composeCanvas: androidx.compose.ui.graphics.Canvas? = null

    constructor(bitmap: Bitmap) {
        this.targetBitmap = bitmap
    }

    internal constructor(scope: DrawScope, composeCanvas: androidx.compose.ui.graphics.Canvas) {
        this.drawScope = scope
        this.composeCanvas = composeCanvas
    }

    fun save(): Int {
        composeCanvas?.save()
        return 1
    }

    fun restore() {
        composeCanvas?.restore()
    }

    fun translate(dx: Float, dy: Float) {
        composeCanvas?.translate(dx, dy)
    }

    fun scale(sx: Float, sy: Float) {
        composeCanvas?.scale(sx, sy)
    }

    fun scale(sx: Float, sy: Float, px: Float, py: Float) {
        composeCanvas?.let { c ->
            c.translate(px, py)
            c.scale(sx, sy)
            c.translate(-px, -py)
        }
    }

    fun rotate(degrees: Float, px: Float, py: Float) {
        composeCanvas?.let { c ->
            c.translate(px, py)
            c.rotate(degrees)
            c.translate(-px, -py)
        }
    }

    fun clipRect(left: Float, top: Float, right: Float, bottom: Float): Boolean {
        composeCanvas?.clipRect(left, top, right, bottom)
        return true
    }

    fun clipRect(rect: RectF): Boolean = clipRect(rect.left, rect.top, rect.right, rect.bottom)

    fun clipRect(rect: Rect): Boolean =
        clipRect(rect.left.toFloat(), rect.top.toFloat(), rect.right.toFloat(), rect.bottom.toFloat())

    fun drawColor(color: Int) {
        targetBitmap?.eraseColor(color)
        composeCanvas?.let { c ->
            val p = toComposePaint(Paint().apply {
                this.color = color
                this.style = Paint.Style.FILL
            })
            val w = drawScope?.size?.width ?: 4096f
            val h = drawScope?.size?.height ?: 4096f
            c.drawRect(0f, 0f, w, h, p)
        }
    }

    fun drawRect(left: Float, top: Float, right: Float, bottom: Float, paint: Paint) {
        composeCanvas?.drawRect(left, top, right, bottom, toComposePaint(paint))
    }

    fun drawRect(rect: RectF, paint: Paint) {
        drawRect(rect.left, rect.top, rect.right, rect.bottom, paint)
    }

    fun drawRoundRect(left: Float, top: Float, right: Float, bottom: Float, rx: Float, ry: Float, paint: Paint) {
        composeCanvas?.drawRoundRect(left, top, right, bottom, rx, ry, toComposePaint(paint))
    }

    fun drawRoundRect(rect: RectF, rx: Float, ry: Float, paint: Paint) {
        drawRoundRect(rect.left, rect.top, rect.right, rect.bottom, rx, ry, paint)
    }

    fun drawCircle(cx: Float, cy: Float, radius: Float, paint: Paint) {
        composeCanvas?.drawCircle(Offset(cx, cy), radius, toComposePaint(paint))
    }

    fun drawLine(startX: Float, startY: Float, stopX: Float, stopY: Float, paint: Paint) {
        composeCanvas?.drawLine(Offset(startX, startY), Offset(stopX, stopY), toComposePaint(paint))
    }

    fun drawPath(path: Path, paint: Paint) {
        composeCanvas?.drawPath(path.composePath, toComposePaint(paint))
    }

    fun drawArc(
        oval: RectF,
        startAngle: Float,
        sweepAngle: Float,
        useCenter: Boolean,
        paint: Paint
    ) {
        composeCanvas?.drawArc(
            oval.left,
            oval.top,
            oval.right,
            oval.bottom,
            startAngle,
            sweepAngle,
            useCenter,
            toComposePaint(paint)
        )
    }

    fun drawText(text: String, x: Float, y: Float, paint: Paint) {
        val c = composeCanvas ?: return
        try {
            val skiaCanvas = c.nativeCanvas
            val skiaPaint = org.jetbrains.skia.Paint().apply {
                color = paint.color
                isAntiAlias = true
            }
            val font = Font(null, paint.textSize)
            val measuredWidth = font.measureTextWidth(text)
            val drawX = when (paint.textAlign) {
                Paint.Align.LEFT -> x
                Paint.Align.CENTER -> x - measuredWidth / 2f
                Paint.Align.RIGHT -> x - measuredWidth
            }
            skiaCanvas.drawString(text, drawX, y, font, skiaPaint)
        } catch (_: Exception) {
        }
    }

    fun drawBitmap(bitmap: Bitmap, left: Float, top: Float, paint: Paint?) {
        if (bitmap.isRecycled) return
        targetBitmap?.let {
            copyRegionToTargetBitmap(
                bitmap,
                Rect(0, 0, bitmap.width, bitmap.height),
                RectF(left, top, left + bitmap.width, top + bitmap.height)
            )
            return
        }
        val c = composeCanvas ?: return
        val p = paint?.let { toComposePaint(it) } ?: androidx.compose.ui.graphics.Paint()
        c.drawImage(bitmap.toComposeImageBitmap(), Offset(left, top), p)
    }

    fun drawBitmap(bitmap: Bitmap, src: Rect?, dst: RectF, paint: Paint?) {
        if (bitmap.isRecycled) return
        val sRect = src ?: Rect(0, 0, bitmap.width, bitmap.height)
        targetBitmap?.let {
            copyRegionToTargetBitmap(bitmap, sRect, dst)
            return
        }
        val c = composeCanvas ?: return
        val p = paint?.let { toComposePaint(it) } ?: androidx.compose.ui.graphics.Paint()
        c.drawImageRect(
            image = bitmap.toComposeImageBitmap(),
            srcOffset = IntOffset(sRect.left, sRect.top),
            srcSize = IntSize(sRect.width().coerceAtLeast(1), sRect.height().coerceAtLeast(1)),
            dstOffset = IntOffset(dst.left.roundToInt(), dst.top.roundToInt()),
            dstSize = IntSize(dst.width().roundToInt().coerceAtLeast(1), dst.height().roundToInt().coerceAtLeast(1)),
            paint = p
        )
    }

    fun drawBitmap(bitmap: Bitmap, src: Rect?, dst: Rect, paint: Paint?) {
        drawBitmap(
            bitmap,
            src,
            RectF(dst.left.toFloat(), dst.top.toFloat(), dst.right.toFloat(), dst.bottom.toFloat()),
            paint
        )
    }

    private fun copyRegionToTargetBitmap(src: Bitmap, sRect: Rect, dst: RectF) {
        val target = targetBitmap ?: return
        val dw = dst.width().roundToInt().coerceAtLeast(1)
        val dh = dst.height().roundToInt().coerceAtLeast(1)
        val sw = sRect.width().coerceAtLeast(1)
        val sh = sRect.height().coerceAtLeast(1)
        val dx0 = dst.left.roundToInt()
        val dy0 = dst.top.roundToInt()
        for (y in 0 until dh) {
            val ty = dy0 + y
            if (ty !in 0 until target.height) continue
            val sy = (sRect.top + (y * sh) / dh).coerceIn(0, src.height - 1)
            for (x in 0 until dw) {
                val tx = dx0 + x
                if (tx !in 0 until target.width) continue
                val sx = (sRect.left + (x * sw) / dw).coerceIn(0, src.width - 1)
                val sIdx = (sy * src.width + sx) * 4
                val dIdx = (ty * target.width + tx) * 4
                target.rgbaPixels[dIdx] = src.rgbaPixels[sIdx]
                target.rgbaPixels[dIdx + 1] = src.rgbaPixels[sIdx + 1]
                target.rgbaPixels[dIdx + 2] = src.rgbaPixels[sIdx + 2]
                target.rgbaPixels[dIdx + 3] = src.rgbaPixels[sIdx + 3]
            }
        }
        target.generation++
    }

    private fun toComposePaint(paint: Paint): androidx.compose.ui.graphics.Paint {
        return androidx.compose.ui.graphics.Paint().apply {
            color = androidx.compose.ui.graphics.Color(paint.color)
            isAntiAlias = paint.isAntiAlias
            style = when (paint.style) {
                Paint.Style.FILL -> androidx.compose.ui.graphics.PaintingStyle.Fill
                Paint.Style.STROKE -> androidx.compose.ui.graphics.PaintingStyle.Stroke
                Paint.Style.FILL_AND_STROKE -> androidx.compose.ui.graphics.PaintingStyle.Fill
            }
            strokeWidth = paint.strokeWidth
            strokeCap = when (paint.strokeCap) {
                Paint.Cap.BUTT -> StrokeCap.Butt
                Paint.Cap.ROUND -> StrokeCap.Round
                Paint.Cap.SQUARE -> StrokeCap.Square
            }
            strokeJoin = when (paint.strokeJoin) {
                Paint.Join.MITER -> StrokeJoin.Miter
                Paint.Join.ROUND -> StrokeJoin.Round
                Paint.Join.BEVEL -> StrokeJoin.Bevel
            }
            val pe = paint.pathEffect
            if (pe is DashPathEffect && pe.intervals.size >= 2) {
                pathEffect = androidx.compose.ui.graphics.PathEffect.dashPathEffect(pe.intervals, pe.phase)
            }
        }
    }
}
