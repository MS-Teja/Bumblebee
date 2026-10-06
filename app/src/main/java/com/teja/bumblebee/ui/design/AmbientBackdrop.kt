package com.teja.bumblebee.ui.design

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffColorFilter
import android.graphics.RadialGradient
import android.graphics.Shader
import android.view.View
import com.teja.bumblebee.art.Grain

/**
 * One full-screen layer behind every screen: album-tinted soft light, a vignette and film grain.
 * Blobs are alpha-only radial gradients tinted with a colour filter, so recolouring costs nothing
 * (no blur, no re-allocation of gradients), which keeps live drag-tinting smooth on a Mali-T820.
 */
class AmbientBackdrop(ctx: Context) : View(ctx) {

    private var shown = Accent.NEUTRAL
    private var from = Accent.NEUTRAL
    private var to = Accent.NEUTRAL
    private var preview: Accent? = null
    private var previewT = 0f
    private var intensity = 1f
    private var colorAnim: ValueAnimator? = null
    private var intensityAnim: ValueAnimator? = null

    private val base = Paint()
    private val blobA = Paint(Paint.ANTI_ALIAS_FLAG)
    private val blobB = Paint(Paint.ANTI_ALIAS_FLAG)
    private val vignette = Paint()
    private val grain = Paint().apply { shader = Grain.shader; alpha = 13 }
    private var sized = 0

    /** Colours currently on screen (after any drag preview). */
    val current: Accent get() = preview?.let { shown.blend(it, previewT) } ?: shown

    fun setAccent(a: Accent, animate: Boolean = true) {
        val start = current
        preview = null
        previewT = 0f
        colorAnim?.cancel()
        if (!animate) {
            shown = a; from = a; to = a; invalidate(); return
        }
        from = start
        to = a
        colorAnim = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = Motion.ms(420)
            interpolator = Motion.standard
            addUpdateListener { shown = from.blend(to, it.animatedFraction); invalidate() }
            start()
        }
    }

    /** Live tint toward the song being swiped in; [t] is 0..1 drag progress. */
    fun preview(target: Accent?, t: Float) {
        preview = target
        previewT = t.coerceIn(0f, 1f)
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
        sized = w
        val white = intArrayOf(0xFFFFFFFF.toInt(), 0x66FFFFFF, 0x00FFFFFF)
        val stops = floatArrayOf(0f, 0.45f, 1f)
        blobA.shader = RadialGradient(240f.u, 280f.u, 620f.u, white, stops, Shader.TileMode.CLAMP)
        blobB.shader = RadialGradient(1130f.u, 650f.u, 560f.u, white, stops, Shader.TileMode.CLAMP)
        val v = RadialGradient(
            0f, 0f, 1f,
            intArrayOf(0x0007090C, 0x7307090C, 0xE007090C.toInt()),
            floatArrayOf(0f, 0.55f, 1f),
            Shader.TileMode.CLAMP,
        )
        v.setLocalMatrix(Matrix().apply { setScale(w * 0.78f, h * 0.95f); postTranslate(w * 0.3f, h * 0.5f) })
        vignette.shader = v
    }

    override fun onDraw(canvas: Canvas) {
        val a = current
        val neutral = Accent.NEUTRAL
        base.color = C.blend(neutral.deep, a.deep, intensity)
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), base)
        blobA.colorFilter = PorterDuffColorFilter(a.glowA, PorterDuff.Mode.SRC_IN)
        blobA.alpha = (255 * (0.16f + 0.22f * intensity)).toInt()
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), blobA)
        blobB.colorFilter = PorterDuffColorFilter(a.glowB, PorterDuff.Mode.SRC_IN)
        blobB.alpha = (255 * (0.20f + 0.35f * intensity)).toInt()
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), blobB)
        vignette.alpha = (255 * (0.55f + 0.45f * intensity)).toInt()
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), vignette)
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), grain)
    }
}
