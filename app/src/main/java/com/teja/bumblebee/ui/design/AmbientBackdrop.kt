package com.teja.bumblebee.ui.design

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.Rect
import android.graphics.Shader
import android.util.LruCache
import android.view.View
import com.teja.bumblebee.art.Grain

/**
 * One full-screen layer behind every screen: album-tinted soft light, a vignette and film grain.
 *
 * Performance: each colour scheme is painted once into a small bitmap (the content is soft
 * gradients, so a 320×180 render scaled up looks identical). Drag-tinting and colour changes are
 * then just a crossfade between two textures, so a frame costs two cheap texture draws plus grain
 * instead of five full-screen gradient passes — important on a Mali-T820.
 */
class AmbientBackdrop(ctx: Context) : View(ctx) {

    private var fromAccent = Accent.NEUTRAL
    private var fromBmp: Bitmap? = null
    private var toAccent: Accent? = null
    private var toBmp: Bitmap? = null
    private var t = 0f
    private var animating = false
    private var intensity = 1f
    private var colorAnim: ValueAnimator? = null
    private var intensityAnim: ValueAnimator? = null

    private val bmpPaint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val overlay = Paint()
    private val grain = Paint().apply { shader = Grain.shader; alpha = 13 }
    private val dst = Rect()
    private val cache = LruCache<Accent, Bitmap>(6)

    fun setAccent(a: Accent, animate: Boolean = true) {
        colorAnim?.cancel()
        if (!animate || width == 0) {
            fromAccent = a; fromBmp = null; toAccent = null; toBmp = null; t = 0f; animating = false
            invalidate()
            return
        }
        // Freeze whatever is on screen (possibly mid-blend) as the starting point.
        val start = toAccent?.let { fromAccent.blend(it, t) } ?: fromAccent
        fromAccent = start
        fromBmp = if (toAccent != null && t > 0f && t < 1f) render(start, cacheIt = false) else null
        toAccent = a
        toBmp = null
        t = 0f
        animating = true
        colorAnim = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = Motion.ms(420)
            interpolator = Motion.standard
            addUpdateListener { t = it.animatedValue as Float; invalidate() }
            addListener(object : android.animation.AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: android.animation.Animator) {
                    fromAccent = a; fromBmp = null; toAccent = null; toBmp = null; t = 0f; animating = false
                    invalidate()
                }
            })
            start()
        }
    }

    /** Live tint toward the song being swiped in; [progress] is 0..1. */
    fun preview(target: Accent?, progress: Float) {
        if (animating) return
        if (target == null || progress <= 0f) {
            if (toAccent != null) { toAccent = null; toBmp = null; t = 0f; invalidate() }
            return
        }
        if (toAccent != target) { toAccent = target; toBmp = null }
        t = progress.coerceIn(0f, 1f)
        invalidate()
    }

    fun setIntensity(v: Float, animate: Boolean = true) {
        intensityAnim?.cancel()
        if (!animate) { intensity = v; invalidate(); return }
        intensityAnim = ValueAnimator.ofFloat(intensity, v).apply {
            duration = Motion.ms(380)
            interpolator = Motion.standard
            addUpdateListener { intensity = it.animatedValue as Float; invalidate() }
            start()
        }
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        cache.evictAll()
        fromBmp = null
        toBmp = null
    }

    override fun onDraw(canvas: Canvas) {
        if (width == 0) return
        dst.set(0, 0, width, height)
        val base = fromBmp ?: render(fromAccent).also { fromBmp = it }
        bmpPaint.alpha = 255
        canvas.drawBitmap(base, null, dst, bmpPaint)
        val target = toAccent
        if (target != null && t > 0f) {
            val top = toBmp ?: render(target).also { toBmp = it }
            bmpPaint.alpha = (255 * t).toInt()
            canvas.drawBitmap(top, null, dst, bmpPaint)
        }
        if (intensity < 0.999f) {
            // Browse screens: calm the colours down toward a neutral dark.
            overlay.color = C.alpha(0xFF0B0E12.toInt(), (1f - intensity) * 0.72f)
            canvas.drawRect(dst, overlay)
        }
        canvas.drawRect(dst, grain)
    }

    /** Paints one colour scheme at 1/4 resolution. */
    private fun render(a: Accent, cacheIt: Boolean = true): Bitmap {
        if (cacheIt) cache.get(a)?.let { return it }
        val sw = 320
        val sh = (320f * height / width).toInt().coerceAtLeast(90)
        val bmp = Bitmap.createBitmap(sw, sh, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val k = sw / 1280f
        val hk = sh / 720f
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        c.drawColor(a.deep)
        fun blob(cx: Float, cy: Float, r: Float, color: Int, alpha: Float) {
            p.shader = RadialGradient(
                cx * k, cy * hk, r * k,
                intArrayOf(C.alpha(color, alpha), C.alpha(color, alpha * 0.4f), C.alpha(color, 0f)),
                floatArrayOf(0f, 0.45f, 1f), Shader.TileMode.CLAMP,
            )
            c.drawRect(0f, 0f, sw.toFloat(), sh.toFloat(), p)
        }
        blob(240f, 280f, 620f, a.glowA, 0.38f)
        blob(1130f, 650f, 560f, a.glowB, 0.55f)
        val v = RadialGradient(0f, 0f, 1f, intArrayOf(0x0007090C, 0x7307090C, 0xE007090C.toInt()), floatArrayOf(0f, 0.55f, 1f), Shader.TileMode.CLAMP)
        v.setLocalMatrix(Matrix().apply { setScale(sw * 0.78f, sh * 0.95f); postTranslate(sw * 0.3f, sh * 0.5f) })
        p.shader = v
        c.drawRect(0f, 0f, sw.toFloat(), sh.toFloat(), p)
        if (cacheIt) cache.put(a, bmp)
        return bmp
    }
}
