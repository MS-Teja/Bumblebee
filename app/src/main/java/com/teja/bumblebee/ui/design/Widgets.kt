package com.teja.bumblebee.ui.design

import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Outline
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.annotation.DrawableRes
import kotlin.math.abs

// ---------------------------------------------------------------- builders

fun Context.text(
    value: CharSequence = "",
    size: Float,
    face: Typeface = Fonts.semiBold,
    color: Int = C.TEXT,
    lines: Int = 1,
): TextView = TextView(this).apply {
    text = value
    typeface = face
    setTextSize(TypedValue.COMPLEX_UNIT_PX, size.u)
    setTextColor(color)
    includeFontPadding = false
    maxLines = lines
    ellipsize = TextUtils.TruncateAt.END
    if (lines == 1) isSingleLine = true
}

fun Context.label(value: String, color: Int = C.TEXT3, size: Float = 14f): TextView =
    text(value.uppercase(), size, Fonts.extraBold, color).apply { letterSpacing = 0.14f }

fun Context.icon(@DrawableRes res: Int, size: Int, tint: Int = C.TEXT): ImageView = ImageView(this).apply {
    setImageResource(res)
    setColorFilter(tint)
    scaleType = ImageView.ScaleType.FIT_CENTER
    layoutParams = ViewGroup.LayoutParams(size.u, size.u)
}

fun rounded(color: Int, radius: Int): GradientDrawable = GradientDrawable().apply {
    setColor(color)
    cornerRadius = radius.u.toFloat()
}

fun circle(color: Int): GradientDrawable = GradientDrawable().apply {
    shape = GradientDrawable.OVAL
    setColor(color)
}

fun outlineCircle(stroke: Int, width: Int): GradientDrawable = GradientDrawable().apply {
    shape = GradientDrawable.OVAL
    setColor(0)
    setStroke(width.u, stroke)
}

/** Clips a view to rounded corners on the RenderThread (cheap, unlike canvas clipping). */
fun View.roundCorners(radius: Int) {
    val r = radius.u.toFloat()
    outlineProvider = object : ViewOutlineProvider() {
        override fun getOutline(view: View, outline: Outline) = outline.setRoundRect(0, 0, view.width, view.height, r)
    }
    clipToOutline = true
}

fun View.ovalClip() {
    outlineProvider = object : ViewOutlineProvider() {
        override fun getOutline(view: View, outline: Outline) = outline.setOval(0, 0, view.width, view.height)
    }
    clipToOutline = true
}

fun lp(w: Int, h: Int) = ViewGroup.LayoutParams(w, h)
fun frame(w: Int, h: Int, gravity: Int = Gravity.NO_GRAVITY, l: Int = 0, t: Int = 0, r: Int = 0, b: Int = 0) =
    FrameLayout.LayoutParams(w, h, gravity).apply { setMargins(l, t, r, b) }
fun linear(w: Int, h: Int, weight: Float = 0f, l: Int = 0, t: Int = 0, r: Int = 0, b: Int = 0) =
    LinearLayout.LayoutParams(w, h, weight).apply { setMargins(l, t, r, b) }

const val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
const val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT

fun Context.row(gravity: Int = Gravity.CENTER_VERTICAL, vararg children: View) = LinearLayout(this).apply {
    orientation = LinearLayout.HORIZONTAL
    this.gravity = gravity
    children.forEach { addView(it) }
}

fun Context.column(vararg children: View) = LinearLayout(this).apply {
    orientation = LinearLayout.VERTICAL
    children.forEach { addView(it) }
}

fun View.gone(hide: Boolean = true) { visibility = if (hide) View.GONE else View.VISIBLE }

/** Springy press feedback + click, with taps ignored if the finger drifted (bumpy roads). */
@SuppressLint("ClickableViewAccessibility")
fun View.pressable(scale: Float = 0.92f, onLongClick: (() -> Unit)? = null, onClick: () -> Unit) {
    isClickable = true
    setOnClickListener { onClick() }
    if (onLongClick != null) setOnLongClickListener { onLongClick(); true }
    setOnTouchListener { v, e ->
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> Motion.pressScale(v, true, scale)
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> Motion.pressScale(v, false)
        }
        false
    }
}

// ---------------------------------------------------------------- buttons

/** Round icon button. */
class CircleButton(ctx: Context, @DrawableRes iconRes: Int, diameter: Int, iconSize: Int, bg: Int, fg: Int) : FrameLayout(ctx) {
    val image: ImageView = ctx.icon(iconRes, iconSize, fg)
    private val bgDrawable = circle(bg)

    init {
        background = bgDrawable
        addView(image, frame(iconSize.u, iconSize.u, Gravity.CENTER))
        layoutParams = lp(diameter.u, diameter.u)
    }

    fun setColors(bg: Int, fg: Int) {
        bgDrawable.setColor(bg)
        image.setColorFilter(fg)
    }
}

/** Pill with optional icon and text. */
class PillButton(ctx: Context, title: String, @DrawableRes iconRes: Int?, height: Int, private var filled: Boolean, accent: Int) : LinearLayout(ctx) {
    private val bg = GradientDrawable().apply { cornerRadius = (height / 2).u.toFloat() }
    val title: TextView = ctx.text(title, height * 0.29f, Fonts.extraBold)
    private val iconView: ImageView? = iconRes?.let { ctx.icon(it, (height * 0.36f).toInt()) }

    init {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER
        background = bg
        setPadding((height * 0.36f).toInt().u, 0, (height * 0.42f).toInt().u, 0)
        iconView?.let { addView(it, linear((height * 0.36f).toInt().u, (height * 0.36f).toInt().u, r = 10.u)) }
        addView(this.title)
        minimumHeight = height.u
        setAccent(accent)
    }

    fun setAccent(accent: Int) {
        if (filled) {
            bg.setColor(accent); bg.setStroke(0, 0)
            title.setTextColor(C.INK); iconView?.setColorFilter(C.INK)
        } else {
            bg.setColor(0); bg.setStroke(2.u, 0x47FFFFFF)
            title.setTextColor(C.TEXT); iconView?.setColorFilter(C.TEXT)
        }
    }
}

/** Play/pause that morphs between the two shapes instead of swapping icons. */
class PlayPauseView(ctx: Context, private val glyphSize: Int) : View(ctx) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val path = Path()
    private var t = 0f // 0 = pause bars, 1 = play triangle
    private var anim: ValueAnimator? = null
    private val bg = Paint(Paint.ANTI_ALIAS_FLAG)
    var drawBackground = true

    // Corresponding points: two pause bars morph into the two halves of the play triangle.
    private val pauseL = floatArrayOf(5.5f, 4f, 10.1f, 4f, 10.1f, 20f, 5.5f, 20f)
    private val pauseR = floatArrayOf(13.9f, 4f, 18.5f, 4f, 18.5f, 20f, 13.9f, 20f)
    private val playL = floatArrayOf(7f, 4f, 13.6f, 8.1f, 13.6f, 15.9f, 7f, 20f)
    private val playR = floatArrayOf(13.6f, 8.1f, 20f, 12f, 20f, 12f, 13.6f, 15.9f)

    var colorBg: Int = C.BEE
        set(v) { field = v; invalidate() }
    var colorGlyph: Int = C.INK
        set(v) { field = v; invalidate() }

    fun setPlaying(playing: Boolean, animate: Boolean) {
        val target = if (playing) 0f else 1f
        if (target == t && anim == null) return
        anim?.cancel()
        if (!animate) { t = target; anim = null; invalidate(); return }
        anim = ValueAnimator.ofFloat(t, target).apply {
            duration = Motion.ms(240)
            interpolator = Motion.emphasized
            addUpdateListener { t = it.animatedValue as Float; invalidate() }
            start()
        }
    }

    override fun onDraw(canvas: Canvas) {
        val cx = width / 2f
        val cy = height / 2f
        if (drawBackground) {
            bg.color = colorBg
            canvas.drawCircle(cx, cy, minOf(cx, cy), bg)
        }
        val s = glyphSize.u / 24f
        val ox = cx - 12f * s + (t * 0.6f * s)
        val oy = cy - 12f * s
        paint.color = colorGlyph
        path.reset()
        shape(pauseL, playL, ox, oy, s)
        shape(pauseR, playR, ox, oy, s)
        canvas.drawPath(path, paint)
    }

    private fun shape(a: FloatArray, b: FloatArray, ox: Float, oy: Float, s: Float) {
        for (i in 0 until 4) {
            val x = ox + (a[i * 2] + (b[i * 2] - a[i * 2]) * t) * s
            val y = oy + (a[i * 2 + 1] + (b[i * 2 + 1] - a[i * 2 + 1]) * t) * s
            if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        path.close()
    }
}

/** Shuffle/repeat style toggle: accent-tinted pill background fades in when on. */
class ToggleIcon(ctx: Context, @DrawableRes iconRes: Int, diameter: Int, iconSize: Int) : FrameLayout(ctx) {
    private val bg = circle(0)
    private val image = ctx.icon(iconRes, iconSize, C.TEXT2)
    private val badge = ctx.text("1", 12f, Fonts.extraBold).apply { gone() }
    private var active = false

    init {
        background = bg
        addView(image, frame(iconSize.u, iconSize.u, Gravity.CENTER))
        addView(badge, frame(WRAP, WRAP, Gravity.TOP or Gravity.END, r = 12.u, t = 9.u))
        layoutParams = lp(diameter.u, diameter.u)
    }

    fun set(on: Boolean, accent: Int, one: Boolean = false) {
        active = on
        bg.setColor(if (on) C.alpha(accent, 0.18f) else 0)
        image.setColorFilter(if (on) accent else C.TEXT2)
        badge.setTextColor(accent)
        badge.gone(!one)
    }
}

/** Three animated bars for the playing row; animates only while visible and playing. */
class EqBars(ctx: Context) : View(ctx) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private var phase = 0f
    private val anim = ValueAnimator.ofFloat(0f, 1f).apply {
        duration = 900
        repeatCount = ValueAnimator.INFINITE
        addUpdateListener { phase = it.animatedValue as Float; invalidate() }
    }
    var color: Int = C.BEE
        set(v) { field = v; paint.color = v; invalidate() }
    var playing = false
        set(v) { field = v; sync() }

    private fun sync() {
        if (playing && isAttachedToWindow && isShown) { if (!anim.isStarted) anim.start() } else if (anim.isStarted) anim.cancel()
        invalidate()
    }

    override fun onAttachedToWindow() { super.onAttachedToWindow(); sync() }
    override fun onDetachedFromWindow() { anim.cancel(); super.onDetachedFromWindow() }
    override fun onVisibilityChanged(changedView: View, visibility: Int) { super.onVisibilityChanged(changedView, visibility); if (isAttachedToWindow) sync() }

    override fun onDraw(canvas: Canvas) {
        paint.color = color
        val bw = width / 5f
        for (i in 0 until 3) {
            val p = (phase + i * 0.33f) % 1f
            val k = if (playing) 0.35f + 0.65f * (1f - abs(p * 2f - 1f)) else 0.35f
            val h = height * k
            val x = i * bw * 2f
            canvas.drawRoundRect(x, height - h, x + bw, height.toFloat(), bw / 2, bw / 2, paint)
        }
    }
}
