package com.teja.bumblebee.art

import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.drawable.Drawable
import com.teja.bumblebee.ui.design.Accent
import com.teja.bumblebee.ui.design.C
import com.teja.bumblebee.ui.design.Fonts
import com.teja.bumblebee.util.Fmt
import kotlin.math.abs
import kotlin.math.min

/** Curated two-stop gradients for generated covers, picked by hashing the album/folder name. */
object CoverPalette {
    private val pairs = listOf(
        "#3B2A7A" to "#E2557B", "#0E5E6F" to "#F2C14E", "#8A1C14" to "#F08A24", "#1D3557" to "#E76F51",
        "#264653" to "#2A9D8F", "#1B4FBF" to "#7FD3FF", "#4A1942" to "#C33C54", "#283618" to "#A3B18A",
        "#5C3D00" to "#FFC72C", "#3D2C8D" to "#916BBF", "#0B3954" to "#BFD7EA", "#6B2737" to "#E08E45",
    ).map { C.parse(it.first) to C.parse(it.second) }

    fun pick(seed: String): Pair<Int, Int> = pairs[abs(seed.lowercase().hashCode()) % pairs.size]

    /** Accent colours for a generated cover, so tinting works even without real art. */
    fun accentFor(seed: String): Accent {
        val (a, b) = pick(seed)
        return AccentEngine.fromColors(a, b)
    }
}

/** A designed placeholder: diagonal gradient, two thin rings, album caption and big initials. */
class GeneratedCover(private val title: String, caption: String?, seed: String) : Drawable() {
    private val colors = CoverPalette.pick(seed)
    private val caption = caption?.uppercase()
    private val initials = Fmt.initials(title)
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xE6FFFFFF.toInt() }
    private var shaderFor: Rect? = null

    override fun draw(canvas: Canvas) {
        val b = bounds
        val w = b.width().toFloat()
        val h = b.height().toFloat()
        if (shaderFor != b) {
            fill.shader = LinearGradient(b.left.toFloat(), b.top.toFloat(), b.right.toFloat(), b.bottom.toFloat(), colors.first, colors.second, Shader.TileMode.CLAMP)
            shaderFor = Rect(b)
        }
        canvas.drawRect(b, fill)
        ring.strokeWidth = w * 0.006f
        ring.color = 0x38FFFFFF
        canvas.drawCircle(b.right - w * 0.12f, b.top + h * 0.12f, w * 0.32f, ring)
        ring.color = 0x24FFFFFF
        canvas.drawCircle(b.right - w * 0.12f, b.top + h * 0.12f, w * 0.21f, ring)
        if (caption != null && w > 260) {
            text.typeface = Fonts.bold
            text.textSize = w * 0.034f
            text.letterSpacing = 0.14f
            text.alpha = 210
            val clipped = TextFit.ellipsize(caption, text, w * 0.62f)
            canvas.drawText(clipped, b.left + w * 0.072f, b.top + h * 0.1f, text)
        }
        text.typeface = Fonts.extraBold
        text.letterSpacing = -0.04f
        text.alpha = 230
        text.textSize = h * (if (initials.length > 1) 0.38f else 0.46f)
        canvas.drawText(initials, b.left + w * 0.055f, b.bottom - h * 0.06f, text)
    }

    override fun setAlpha(alpha: Int) { fill.alpha = alpha }
    override fun setColorFilter(colorFilter: ColorFilter?) { fill.colorFilter = colorFilter }
    @Deprecated("Deprecated in Java")
    override fun getOpacity() = PixelFormat.OPAQUE
}

/** Cassette-tape label cover for art-less folders (a nod to the 2018 Bumblebee film). */
class CassetteCover(private val label: String, seed: String) : Drawable() {
    private val stripe = CoverPalette.pick(seed).second
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private val r = RectF()

    override fun draw(canvas: Canvas) {
        val b = bounds
        val w = b.width().toFloat()
        val h = b.height().toFloat()
        p.style = Paint.Style.FILL
        p.shader = LinearGradient(b.left.toFloat(), b.top.toFloat(), b.right.toFloat(), b.bottom.toFloat(), 0xFF1A1D22.toInt(), 0xFF2B2F36.toInt(), Shader.TileMode.CLAMP)
        canvas.drawRect(b, p)
        p.shader = null

        val cw = w * 0.82f
        val ch = cw / 1.58f
        val cx = b.left + (w - cw) / 2
        val cy = b.top + (h - ch) / 2
        val rad = cw * 0.06f
        p.color = 0xFF24272D.toInt()
        r.set(cx, cy, cx + cw, cy + ch)
        canvas.drawRoundRect(r, rad, rad, p)
        p.style = Paint.Style.STROKE
        p.strokeWidth = cw * 0.01f
        p.color = 0xFF3A3F47.toInt()
        canvas.drawRoundRect(r, rad, rad, p)
        p.style = Paint.Style.FILL

        // Label
        val lx = cx + cw * 0.08f
        val ly = cy + ch * 0.09f
        val lw = cw * 0.84f
        val lh = ch * 0.52f
        p.color = 0xFFF4E9D0.toInt()
        r.set(lx, ly, lx + lw, ly + lh)
        canvas.drawRoundRect(r, rad * 0.6f, rad * 0.6f, p)
        p.color = stripe
        canvas.save()
        canvas.clipRect(lx, ly, lx + lw, ly + lh * 0.22f)
        canvas.drawRoundRect(r, rad * 0.6f, rad * 0.6f, p)
        canvas.restore()
        p.color = 0xFF1D1F24.toInt()
        p.typeface = Fonts.marker
        p.textSize = lh * 0.3f
        val text = TextFit.ellipsize(label, p, lw * 0.9f)
        canvas.drawText(text, lx + lw * 0.05f, ly + lh * 0.62f, p)

        // Window with two reels
        val wx = cx + cw * 0.26f
        val wy = cy + ch * 0.66f
        val ww = cw * 0.48f
        val wh = ch * 0.2f
        p.color = 0xFF15171B.toInt()
        r.set(wx, wy, wx + ww, wy + wh)
        canvas.drawRoundRect(r, wh / 2, wh / 2, p)
        p.style = Paint.Style.STROKE
        p.strokeWidth = cw * 0.012f
        p.color = 0xFFE9E2D0.toInt()
        canvas.drawCircle(wx + ww * 0.22f, wy + wh / 2, wh * 0.3f, p)
        canvas.drawCircle(wx + ww * 0.78f, wy + wh / 2, wh * 0.3f, p)
        p.style = Paint.Style.FILL
    }

    override fun setAlpha(alpha: Int) { p.alpha = alpha }
    override fun setColorFilter(colorFilter: ColorFilter?) { p.colorFilter = colorFilter }
    @Deprecated("Deprecated in Java")
    override fun getOpacity() = PixelFormat.OPAQUE
}

/** 2×2 grid of covers, composed into one bitmap so lists draw a single image. */
object Mosaic {
    fun compose(tiles: List<Any>, size: Int): Bitmap {
        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.RGB_565)
        val c = Canvas(bmp)
        c.drawColor(C.BG)
        val gap = (size * 0.012f).toInt().coerceAtLeast(2)
        val cell = (size - gap) / 2
        val list = when (tiles.size) {
            0 -> return bmp
            1 -> listOf(tiles[0])
            2 -> listOf(tiles[0], tiles[1], tiles[1], tiles[0])
            3 -> listOf(tiles[0], tiles[1], tiles[2], tiles[0])
            else -> tiles.take(4)
        }
        if (list.size == 1) {
            draw(c, list[0], Rect(0, 0, size, size))
            return bmp
        }
        list.forEachIndexed { i, tile ->
            val x = (i % 2) * (cell + gap)
            val y = (i / 2) * (cell + gap)
            draw(c, tile, Rect(x, y, x + cell, y + cell))
        }
        return bmp
    }

    private fun draw(c: Canvas, tile: Any, dst: Rect) {
        when (tile) {
            is Bitmap -> c.drawBitmap(tile, centerSquare(tile), dst, Paint(Paint.FILTER_BITMAP_FLAG))
            is Drawable -> { tile.bounds = dst; tile.draw(c) }
        }
    }

    private fun centerSquare(b: Bitmap): Rect {
        val s = min(b.width, b.height)
        return Rect((b.width - s) / 2, (b.height - s) / 2, (b.width - s) / 2 + s, (b.height - s) / 2 + s)
    }
}

/** Renders any drawable into a bitmap (for flying covers and palette extraction). */
object Rasterize {
    fun of(d: Drawable, size: Int): Bitmap {
        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        d.setBounds(0, 0, size, size)
        d.draw(Canvas(bmp))
        return bmp
    }
}

object TextFit {
    fun ellipsize(text: String, paint: Paint, max: Float): String {
        if (paint.measureText(text) <= max) return text
        var end = text.length
        while (end > 1 && paint.measureText(text, 0, end) + paint.measureText("…") > max) end--
        return text.substring(0, end).trimEnd() + "…"
    }
}

/** Grain texture: a 128px noise tile drawn once, repeated at 4–6% opacity for depth. */
object Grain {
    val shader: BitmapShader by lazy {
        val size = 128
        val px = IntArray(size * size)
        val rnd = java.util.Random(7)
        for (i in px.indices) {
            val v = rnd.nextInt(256)
            px[i] = (0xFF shl 24) or (v shl 16) or (v shl 8) or v
        }
        BitmapShader(Bitmap.createBitmap(px, size, size, Bitmap.Config.ARGB_8888), Shader.TileMode.REPEAT, Shader.TileMode.REPEAT)
            .apply { setLocalMatrix(Matrix()) }
    }
}
