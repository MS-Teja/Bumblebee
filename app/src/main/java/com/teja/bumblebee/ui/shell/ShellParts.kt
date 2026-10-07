package com.teja.bumblebee.ui.shell

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import android.os.Handler
import android.os.Looper
import android.text.format.DateFormat
import android.view.Gravity
import android.view.MotionEvent
import android.view.VelocityTracker
import android.view.View
import android.view.ViewConfiguration
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.annotation.DrawableRes
import androidx.dynamicanimation.animation.DynamicAnimation
import androidx.dynamicanimation.animation.SpringAnimation
import androidx.dynamicanimation.animation.SpringForce
import com.teja.bumblebee.R
import com.teja.bumblebee.art.ArtLoader
import com.teja.bumblebee.data.Track
import com.teja.bumblebee.ui.design.C
import com.teja.bumblebee.ui.design.CircleButton
import com.teja.bumblebee.ui.design.Fonts
import com.teja.bumblebee.ui.design.MATCH
import com.teja.bumblebee.ui.design.Motion
import com.teja.bumblebee.ui.design.PlayPauseView
import com.teja.bumblebee.ui.design.WRAP
import com.teja.bumblebee.ui.design.frame
import com.teja.bumblebee.ui.design.icon
import com.teja.bumblebee.ui.design.linear
import com.teja.bumblebee.ui.design.pressable
import com.teja.bumblebee.ui.design.rounded
import com.teja.bumblebee.ui.design.roundCorners
import com.teja.bumblebee.ui.design.text
import com.teja.bumblebee.ui.design.u
import java.util.Calendar
import kotlin.math.abs

// ====================================================================== rail

/**
 * Navigation: a driver-side rail in landscape, a bottom bar in portrait. Small clock, four
 * destinations with a spring-sliding pill, then settings and exit.
 */
class Rail(ctx: Context, private val horizontal: Boolean, onSelect: (Tab) -> Unit, onExit: () -> Unit) : FrameLayout(ctx) {

    private data class Item(val tab: Tab, val root: LinearLayout, val icon: ImageView, val label: TextView?)

    private val pill = View(ctx)
    private val pillBg = rounded(C.alpha(C.BEE, 0.16f), 22)
    private val items = ArrayList<Item>()
    private var selected: Tab? = null
    private var accent = C.BEE
    private val clock = ctx.text("", 18f, Fonts.semiBold, C.alpha(C.TEXT, 0.6f)).apply { gravity = Gravity.CENTER; fontFeatureSettings = "tnum" }
    private val handler = Handler(Looper.getMainLooper())
    private val pillMove = SpringAnimation(pill, if (horizontal) DynamicAnimation.TRANSLATION_X else DynamicAnimation.TRANSLATION_Y)
        .apply { spring = SpringForce().setStiffness(520f).setDampingRatio(0.78f) }
    private val strip = LinearLayout(ctx)

    init {
        // The bottom bar sits over scrolling content, so it must be solid; the side rail can stay airy.
        setBackgroundColor(if (horizontal) 0xFA0B0E12.toInt() else 0x09FFFFFF)
        val hair = View(ctx).apply { setBackgroundColor(C.HAIR) }
        addView(hair, if (horizontal) frame(MATCH, 1.u, Gravity.TOP) else frame(1.u, MATCH, Gravity.START))
        pill.background = pillBg
        addView(pill, if (horizontal) frame(150.u, 88.u, Gravity.CENTER_VERTICAL) else frame(104.u, 88.u, l = 14.u))
        strip.orientation = if (horizontal) LinearLayout.HORIZONTAL else LinearLayout.VERTICAL
        strip.gravity = Gravity.CENTER
        if (horizontal) strip.setPadding(24.u, 0, 16.u, 0) else strip.setPadding(14.u, 22.u, 14.u, 18.u)
        strip.addView(clock, if (horizontal) linear(80.u, WRAP) else linear(MATCH, WRAP, b = 22.u))
        if (horizontal) strip.addView(View(ctx), linear(0, MATCH, 1f))
        fun add(tab: Tab, @DrawableRes res: Int, title: String?) {
            val icon = ctx.icon(res, if (title != null) 30 else 28, C.alpha(C.TEXT, 0.78f))
            val label = title?.let { ctx.text(it, 14f, Fonts.bold, C.alpha(C.TEXT, 0.78f)).apply { gravity = Gravity.CENTER } }
            val root = LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                addView(icon, linear(icon.layoutParams.width, icon.layoutParams.height))
                label?.let { addView(it, linear(WRAP, WRAP, t = 6.u)) }
                pressable(0.9f) { onSelect(tab) }
                contentDescription = title ?: tab.name.lowercase()
            }
            items += Item(tab, root, icon, label)
            val main = title != null
            strip.addView(root, when {
                horizontal -> linear(if (main) 150.u else 76.u, 88.u)
                else -> linear(MATCH, if (main) 88.u else 64.u, b = 4.u)
            })
        }
        add(Tab.NOW, R.drawable.ic_music, "Playing")
        add(Tab.HOME, R.drawable.ic_home, "Home")
        add(Tab.LIBRARY, R.drawable.ic_library, "Library")
        add(Tab.SEARCH, R.drawable.ic_search, "Search")
        strip.addView(View(ctx), if (horizontal) linear(0, MATCH, 1f) else linear(MATCH, 0, 1f))
        add(Tab.SETTINGS, R.drawable.ic_settings, null)
        val exit = ctx.icon(R.drawable.ic_exit, 28, C.alpha(C.TEXT, 0.6f))
        val exitBox = FrameLayout(ctx).apply {
            addView(exit, frame(28.u, 28.u, Gravity.CENTER))
            contentDescription = "Exit to launcher"
            pressable(0.9f) { onExit() }
        }
        strip.addView(exitBox, if (horizontal) linear(76.u, 88.u) else linear(MATCH, 64.u))
        addView(strip, frame(MATCH, MATCH))
        tickClock()
    }

    private fun tickClock() {
        val now = Calendar.getInstance()
        clock.text = DateFormat.format(if (DateFormat.is24HourFormat(context)) "H:mm" else "h:mm", now)
        val ms = (60 - now.get(Calendar.SECOND)) * 1000L + 50
        handler.postDelayed({ tickClock() }, ms)
    }

    fun select(tab: Tab, animate: Boolean) {
        val item = items.firstOrNull { it.tab == tab } ?: return
        selected = tab
        val place = {
            val lp = pill.layoutParams
            if (horizontal) lp.width = item.root.width else lp.height = item.root.height
            pill.layoutParams = lp
            val target = (if (horizontal) item.root.left + strip.left else item.root.top + strip.top).toFloat()
            if (animate && pill.alpha > 0f) pillMove.animateToFinalPosition(target)
            else if (horizontal) pill.translationX = target else pill.translationY = target
            pill.alpha = 1f
        }
        if (item.root.width == 0) post { place() } else place()
        tint()
    }

    fun setAccent(color: Int) {
        accent = color
        pillBg.setColor(C.alpha(color, 0.16f))
        tint()
    }

    private fun tint() {
        items.forEach {
            val c = if (it.tab == selected) accent else C.alpha(C.TEXT, 0.78f)
            it.icon.setColorFilter(c)
            it.label?.setTextColor(c)
        }
    }
}

// ====================================================================== mini-player

/** Persistent player on browse screens. Swipe the text to skip; tap or swipe up to open Now Playing. */
@SuppressLint("ClickableViewAccessibility")
class MiniPlayer(ctx: Context, private val onOpen: () -> Unit) : FrameLayout(ctx) {
    val cover = ImageView(ctx).apply { scaleType = ImageView.ScaleType.CENTER_CROP; roundCorners(16) }
    private val title = ctx.text("", 21f, Fonts.extraBold)
    private val artist = ctx.text("", 16f, Fonts.semiBold, C.TEXT2)
    private val textBox = LinearLayout(ctx).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER_VERTICAL
        addView(title, linear(MATCH, WRAP))
        addView(artist, linear(MATCH, WRAP, t = 3.u))
    }
    private val playPause = PlayPauseView(ctx, 34).apply { colorBg = C.TEXT; colorGlyph = C.INK }
    private val progress = View(ctx)
    private val stripe = StripeView(ctx)
    private val bgPaint = Paint()
    private var tint = C.BEE
    private var shown: Track? = null
    private var fraction = 0f

    var onPrev: (() -> Unit)? = null
    var onNext: (() -> Unit)? = null
    var onToggle: (() -> Unit)? = null

    init {
        setWillNotDraw(false)
        val row = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(22.u, 0, 26.u, 0)
        }
        row.addView(cover, linear(76.u, 76.u, r = 18.u))
        row.addView(textBox, linear(0, MATCH, 1f, r = 12.u))
        val prev = CircleButton(ctx, R.drawable.ic_prev, 72, 32, 0, C.TEXT).apply { pressable { onPrev?.invoke() } ; contentDescription = "Previous" }
        val next = CircleButton(ctx, R.drawable.ic_next, 72, 32, 0, C.TEXT).apply { pressable { onNext?.invoke() }; contentDescription = "Next" }
        playPause.pressable { onToggle?.invoke() }
        playPause.contentDescription = "Play or pause"
        row.addView(prev, linear(72.u, 72.u))
        row.addView(playPause, linear(80.u, 80.u, l = 6.u, r = 6.u))
        row.addView(next, linear(72.u, 72.u))
        addView(row, frame(MATCH, MATCH))
        progress.setBackgroundColor(C.BEE)
        progress.pivotX = 0f
        addView(progress, frame(MATCH, 3.u, Gravity.TOP))
        addView(stripe, frame(MATCH, 3.u, Gravity.TOP))
        stripe.visibility = View.GONE
        setOnClickListener { onOpen() }
        cover.setOnClickListener { onOpen() }
        installSwipe()
    }

    override fun onDraw(canvas: Canvas) {
        // Opaque base so lists scrolling underneath never show through, then the album tint on top.
        bgPaint.shader = null
        bgPaint.color = 0xFF0D1014.toInt()
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), bgPaint)
        bgPaint.shader = LinearGradient(0f, 0f, width.toFloat(), 0f, intArrayOf(C.alpha(tint, 0.26f), C.alpha(tint, 0.08f), 0x00000000), floatArrayOf(0f, 0.55f, 1f), Shader.TileMode.CLAMP)
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), bgPaint)
        canvas.drawRect(0f, 0f, width.toFloat(), 1f.u, Paint().apply { color = C.HAIR })
    }

    fun bind(t: Track?, playing: Boolean, animate: Boolean, direction: Int) {
        playPause.setPlaying(playing, animate)
        if (t?.path == shown?.path) return
        val old = shown
        shown = t
        ArtLoader.bind(cover, t, ArtLoader.Size.SMALL)
        if (old != null && direction != 0 && animate && !Motion.reduced) {
            // Slide the text the way the song moved.
            textBox.animate().translationX(-direction * 60f.u).alpha(0f).setDuration(110).withEndAction {
                title.text = t?.title ?: ""
                artist.text = t?.artistLabel ?: ""
                textBox.translationX = direction * 60f.u
                textBox.animate().translationX(0f).alpha(1f).setDuration(220).setInterpolator(Motion.emphasized).start()
            }.start()
        } else {
            textBox.animate().cancel()
            textBox.translationX = 0f
            textBox.alpha = 1f
            title.text = t?.title ?: ""
            artist.text = t?.artistLabel ?: ""
        }
    }

    fun setProgress(f: Float) {
        fraction = f
        progress.scaleX = f.coerceIn(0f, 1f)
        stripe.fraction = f
    }

    fun setAccent(color: Int, camaro: Boolean) {
        tint = color
        progress.setBackgroundColor(color)
        stripe.visibility = if (camaro) View.VISIBLE else View.GONE
        progress.visibility = if (camaro) View.GONE else View.VISIBLE
        invalidate()
    }

    private fun installSwipe() {
        val slop = ViewConfiguration.get(context).scaledTouchSlop
        var downX = 0f
        var downY = 0f
        var mode = 0
        var vt: VelocityTracker? = null
        textBox.setOnTouchListener { _, e ->
            if (e.actionMasked == MotionEvent.ACTION_DOWN) { vt?.recycle(); vt = VelocityTracker.obtain() }
            vt?.addMovement(e)
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> { downX = e.rawX; downY = e.rawY; mode = 0; parent.requestDisallowInterceptTouchEvent(true) }
                MotionEvent.ACTION_MOVE -> {
                    val dx = e.rawX - downX
                    val dy = e.rawY - downY
                    if (mode == 0) mode = when {
                        abs(dx) > slop && abs(dx) > abs(dy) -> 1
                        dy < -slop && abs(dy) > abs(dx) -> 2
                        else -> 0
                    }
                    if (mode == 1) {
                        textBox.translationX = dx
                        textBox.alpha = 1f - (abs(dx) / (textBox.width * 0.6f)).coerceIn(0f, 0.8f)
                    }
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    vt?.computeCurrentVelocity(1000)
                    val vx = vt?.xVelocity ?: 0f
                    val dx = e.rawX - downX
                    when {
                        mode == 1 && (abs(dx) > textBox.width * 0.25f || abs(vx) > 900f.u) && e.actionMasked == MotionEvent.ACTION_UP -> {
                            if (dx < 0) onNext?.invoke() else onPrev?.invoke()
                        }
                        mode == 1 -> textBox.animate().translationX(0f).alpha(1f).setDuration(260).setInterpolator(Motion.emphasized).start()
                        mode == 2 && e.actionMasked == MotionEvent.ACTION_UP -> onOpen()
                        mode == 0 && e.actionMasked == MotionEvent.ACTION_UP -> onOpen()
                    }
                    vt?.recycle(); vt = null
                }
            }
            true
        }
    }

    /** Camaro mode: the progress line becomes twin black racing stripes on yellow. */
    private class StripeView(ctx: Context) : View(ctx) {
        var fraction = 0f
            set(v) { field = v; invalidate() }
        private val p = Paint()
        override fun onDraw(canvas: Canvas) {
            val w = width * fraction
            p.color = C.BEE
            canvas.drawRect(0f, 0f, w, height.toFloat(), p)
            p.color = 0xFF14161A.toInt()
            var x = 0f
            while (x < w) {
                canvas.drawRect(x + 10f.u, 0f, (x + 14f.u).coerceAtMost(w), height.toFloat(), p)
                x += 24f.u
            }
        }
    }
}

// ====================================================================== toast

/** Pill toasts that rise from the bottom, queue up and never block anything. */
class Toaster(private val layer: FrameLayout) {
    private val queue = ArrayDeque<Pair<String, Int>>()
    private var showing = false

    fun show(text: String, accent: Int = C.TEXT) {
        queue.addLast(text to accent)
        if (!showing) next()
    }

    private fun next() {
        val (text, accent) = queue.removeFirstOrNull() ?: run { showing = false; return }
        showing = true
        val ctx = layer.context
        val pill = ctx.text(text, 19f, Fonts.extraBold, C.INK).apply {
            background = rounded(accent, 30)
            setPadding(28.u, 16.u, 28.u, 16.u)
            alpha = 0f
            translationY = 24f.u
            elevation = 0f
        }
        layer.addView(pill, frame(WRAP, WRAP, Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL, b = 132.u))
        pill.animate().alpha(1f).translationY(0f).setDuration(Motion.ms(260)).setInterpolator(Motion.emphasized).withEndAction {
            pill.animate().alpha(0f).translationY(-10f.u).setStartDelay(1900).setDuration(Motion.ms(220)).withEndAction {
                layer.removeView(pill)
                next()
            }.start()
        }.start()
    }
}

// ====================================================================== action sheet

data class SheetAction(@DrawableRes val icon: Int, val title: String, val run: () -> Unit)

/** Bottom sheet with big rows; tap outside or drag down to dismiss. */
@SuppressLint("ClickableViewAccessibility")
class ActionSheet(private val layer: FrameLayout, title: String, subtitle: String?, actions: List<SheetAction>, onClosed: () -> Unit) {
    private val ctx = layer.context
    private val scrim = View(ctx).apply { setBackgroundColor(0xB3000000.toInt()); alpha = 0f }
    private val panel = LinearLayout(ctx).apply {
        orientation = LinearLayout.VERTICAL
        background = rounded(0xFF161A20.toInt(), 30)
        setPadding(30.u, 14.u, 30.u, 24.u)
    }
    private val closed = onClosed
    private var done = false

    init {
        val handle = View(ctx).apply { background = rounded(0x4DFFFFFF, 3) }
        panel.addView(handle, linear(64.u, 6.u).apply { gravity = Gravity.CENTER_HORIZONTAL; bottomMargin = 16.u })
        panel.addView(ctx.text(title, 26f, Fonts.extraBold), linear(MATCH, WRAP))
        subtitle?.let { panel.addView(ctx.text(it, 17f, Fonts.semiBold, C.TEXT2), linear(MATCH, WRAP, t = 4.u, b = 10.u)) }
        actions.forEach { a ->
            val row = LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(16.u, 0, 16.u, 0)
                background = rounded(0, 20)
                addView(ctx.icon(a.icon, 28, C.TEXT), linear(28.u, 28.u, r = 20.u))
                addView(ctx.text(a.title, 21f, Fonts.bold), linear(0, WRAP, 1f))
                pressable(0.97f) { dismiss(); a.run() }
            }
            panel.addView(row, linear(MATCH, 72.u))
        }
        scrim.setOnClickListener { dismiss() }
        layer.addView(scrim, frame(MATCH, MATCH))
        layer.addView(panel, frame(620.u, WRAP, Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL))
        panel.translationY = 600f.u
        scrim.animate().alpha(1f).setDuration(Motion.ms(200)).start()
        SpringAnimation(panel, DynamicAnimation.TRANSLATION_Y, 0f).apply {
            spring.setStiffness(420f).setDampingRatio(0.82f)
            if (Motion.reduced) panel.translationY = 0f else start()
        }
        var downY = 0f
        panel.setOnTouchListener { _, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> downY = e.rawY
                MotionEvent.ACTION_MOVE -> panel.translationY = (e.rawY - downY).coerceAtLeast(0f)
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL ->
                    if (panel.translationY > 100f.u) dismiss() else panel.animate().translationY(0f).setDuration(200).start()
            }
            true
        }
    }

    fun dismiss() {
        if (done) return
        done = true
        scrim.animate().alpha(0f).setDuration(Motion.ms(180)).start()
        panel.animate().translationY(panel.height.toFloat() + 40f.u).setDuration(Motion.ms(220)).setInterpolator(Motion.exit).withEndAction {
            layer.removeView(scrim)
            layer.removeView(panel)
            closed()
        }.start()
    }
}

/** A thin animator helper for "fade + slide" entrances. */
fun View.enter(dx: Float = 0f, dy: Float = 0f, delay: Long = 0, duration: Long = 260) {
    alpha = 0f
    translationX = dx
    translationY = dy
    animate().alpha(1f).translationX(0f).translationY(0f).setStartDelay(delay).setDuration(Motion.ms(duration)).setInterpolator(Motion.emphasized).start()
}
