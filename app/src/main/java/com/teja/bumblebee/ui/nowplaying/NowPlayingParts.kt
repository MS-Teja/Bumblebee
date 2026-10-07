package com.teja.bumblebee.ui.nowplaying

import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.view.MotionEvent
import android.view.VelocityTracker
import android.view.View
import android.view.ViewConfiguration
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import com.teja.bumblebee.data.Track
import com.teja.bumblebee.ui.design.C
import com.teja.bumblebee.ui.design.Fonts
import com.teja.bumblebee.ui.design.MATCH
import com.teja.bumblebee.ui.design.Motion
import com.teja.bumblebee.ui.design.WRAP
import com.teja.bumblebee.ui.design.frame
import com.teja.bumblebee.ui.design.linear
import com.teja.bumblebee.ui.design.text
import com.teja.bumblebee.ui.design.u
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Title / artist / album that move with the cover: the current text slides away with the drag while
 * the incoming song's text slides in from the other side, so the whole screen feels like one object.
 */
class TrackText(private val ctx: Context, private val titleSize: Float, private val artistSize: Float, private val showAlbum: Boolean) : FrameLayout(ctx) {

    private inner class Slot {
        val title: TextView = ctx.text("", titleSize, Fonts.extraBold, C.TEXT, lines = 2).apply { letterSpacing = -0.02f; setLineSpacing(0f, 1.04f) }
        val artist: TextView = ctx.text("", artistSize, Fonts.bold, C.BEE)
        val album: TextView = ctx.text("", 19f, Fonts.semiBold, C.TEXT2)
        // Texts never overlap, so fading needs no offscreen buffer.
        val root = object : LinearLayout(ctx) { override fun hasOverlappingRendering() = false }.apply {
            orientation = LinearLayout.VERTICAL
            addView(title, linear(MATCH, WRAP))
            addView(artist, linear(MATCH, WRAP, t = 12.u))
            if (showAlbum) addView(album, linear(MATCH, WRAP, t = 6.u))
        }
        var track: Track? = null
        fun bind(t: Track?) {
            track = t
            title.text = t?.title ?: ""
            artist.text = t?.artistLabel ?: ""
            album.text = t?.album ?: ""
        }
    }

    private var cur = Slot()
    private var inc = Slot()
    private var incDir = 0
    private val shift = 210f.u
    var accent = C.BEE
        set(v) { field = v; cur.artist.setTextColor(v); inc.artist.setTextColor(v) }

    init {
        addView(cur.root, frame(MATCH, WRAP))
        addView(inc.root, frame(MATCH, WRAP))
        inc.root.alpha = 0f
    }

    val titleView: TextView get() = cur.title

    fun show(t: Track?) {
        if (t?.path == cur.track?.path && t?.title == cur.track?.title) return
        cur.bind(t)
        cur.root.translationX = 0f
        cur.root.alpha = 1f
        inc.root.alpha = 0f
    }

    /** Overrides the visible copy (waiting state, cameo lines) without touching the slots' tracks. */
    fun override(title: String?, artist: String?) {
        if (title != null) cur.title.text = title else cur.track?.let { cur.title.text = it.title }
        if (artist != null) cur.artist.text = artist else cur.track?.let { cur.artist.text = it.artistLabel }
    }

    fun progress(p: Float, prev: Track?, next: Track?) {
        val dir = if (p < 0) 1 else if (p > 0) -1 else 0
        if (dir != 0 && dir != incDir) {
            inc.bind(if (dir > 0) next else prev)
            inc.accentFix()
            incDir = dir
        }
        val a = abs(p)
        cur.root.translationX = p * shift
        cur.root.alpha = max(0f, 1f - a * 2.1f)
        inc.root.translationX = if (dir >= 0) (1f + p.coerceAtMost(0f)) * shift else -(1f - p) * shift
        inc.root.alpha = if (dir == 0) 0f else max(0f, (a - 0.36f) / 0.64f)
        cur.root.translationY = 0f
    }

    private fun Slot.accentFix() = artist.setTextColor(accent)

    /** After a committed skip, the incoming text becomes current. */
    fun settle(dir: Int) {
        if (dir != 0 && incDir == dir) {
            val t = cur; cur = inc; inc = t
        }
        incDir = 0
        cur.root.alpha = 1f
        cur.root.translationX = 0f
        inc.root.alpha = 0f
    }
}

/** Drag-only seek bar: a tap never jumps (bumpy roads), dragging grows the thumb and shows a time bubble. */
@SuppressLint("ClickableViewAccessibility")
class DragSeekBar(ctx: Context) : View(ctx) {
    var onScrub: ((Float) -> Unit)? = null
    var onSeek: ((Float) -> Unit)? = null
    var onScrubState: ((Boolean) -> Unit)? = null
    var accent = C.BEE
        set(v) { field = v; invalidate() }
    var fraction = 0f
        set(v) { if (!scrubbing) { field = v.coerceIn(0f, 1f); invalidate() } }

    private var scrubbing = false
    private var scrubFraction = 0f
    private var grow = 0f
    private var growAnim: ValueAnimator? = null
    private var downX = 0f
    private val slop = ViewConfiguration.get(ctx).scaledTouchSlop
    private val track = Paint(Paint.ANTI_ALIAS_FLAG)
    private val thumb = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = C.TEXT }

    val shownFraction get() = if (scrubbing) scrubFraction else fraction

    override fun onDraw(canvas: Canvas) {
        val h = (6f + 4f * grow).u
        val r = (11f + 5f * grow).u
        val cy = height / 2f
        val left = r
        val right = width - r
        val x = left + (right - left) * shownFraction
        track.color = 0x29FFFFFF
        canvas.drawRoundRect(left, cy - h / 2, right, cy + h / 2, h / 2, h / 2, track)
        track.color = accent
        canvas.drawRoundRect(left, cy - h / 2, x, cy + h / 2, h / 2, h / 2, track)
        canvas.drawCircle(x, cy, r, thumb)
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                parent.requestDisallowInterceptTouchEvent(true)
                downX = e.x
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                if (!scrubbing && abs(e.x - downX) > slop) {
                    scrubbing = true
                    animateGrow(1f)
                    onScrubState?.invoke(true)
                }
                if (scrubbing) {
                    val r = 11f.u
                    scrubFraction = ((e.x - r) / (width - 2 * r)).coerceIn(0f, 1f)
                    onScrub?.invoke(scrubFraction)
                    invalidate()
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (scrubbing) {
                    scrubbing = false
                    fraction = scrubFraction
                    if (e.actionMasked == MotionEvent.ACTION_UP) onSeek?.invoke(scrubFraction)
                    animateGrow(0f)
                    onScrubState?.invoke(false)
                }
                parent.requestDisallowInterceptTouchEvent(false)
            }
        }
        return true
    }

    private fun animateGrow(to: Float) {
        growAnim?.cancel()
        growAnim = ValueAnimator.ofFloat(grow, to).apply {
            duration = Motion.ms(160)
            addUpdateListener { grow = it.animatedValue as Float; invalidate() }
            start()
        }
    }
}

/**
 * Hosts the Now Playing content and claims horizontal drags anywhere (except where a child opts out,
 * like the seek bar) for the cover carousel, plus upward swipes for the queue.
 */
class SwipeSurface(ctx: Context) : FrameLayout(ctx) {
    var onDragStart: (() -> Unit)? = null
    var onDrag: ((Float) -> Unit)? = null
    var onDragEnd: ((Float) -> Unit)? = null
    var onSwipeUp: (() -> Unit)? = null
    var onEmptyTap: (() -> Unit)? = null
    var onAnyTouch: (() -> Unit)? = null

    private val slop = ViewConfiguration.get(ctx).scaledTouchSlop
    private var downX = 0f
    private var downY = 0f
    private var mode = 0 // 0 undecided, 1 horizontal, 2 vertical
    private var velocity: VelocityTracker? = null

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        if (ev.actionMasked == MotionEvent.ACTION_DOWN) onAnyTouch?.invoke()
        return super.dispatchTouchEvent(ev)
    }

    override fun onInterceptTouchEvent(e: MotionEvent): Boolean {
        track(e)
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> { downX = e.x; downY = e.y; mode = 0 }
            MotionEvent.ACTION_MOVE -> {
                val dx = e.x - downX
                val dy = e.y - downY
                if (abs(dx) > slop * 1.5f && abs(dx) > abs(dy) * 1.4f) {
                    mode = 1
                    onDragStart?.invoke()
                    downX = e.x
                    return true
                }
                if (dy < -slop * 2.5f && abs(dy) > abs(dx) * 1.6f) {
                    mode = 2
                    return true
                }
            }
        }
        return false
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        track(e)
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> { downX = e.x; downY = e.y; mode = 0; return true }
            MotionEvent.ACTION_MOVE -> {
                val dx = e.x - downX
                val dy = e.y - downY
                if (mode == 0) {
                    if (abs(dx) > slop * 1.5f && abs(dx) > abs(dy) * 1.4f) { mode = 1; onDragStart?.invoke(); downX = e.x }
                    else if (dy < -slop * 2.5f && abs(dy) > abs(dx) * 1.6f) mode = 2
                }
                if (mode == 1) onDrag?.invoke(e.x - downX)
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                val vt = velocity
                vt?.computeCurrentVelocity(1000)
                when (mode) {
                    1 -> onDragEnd?.invoke(if (e.actionMasked == MotionEvent.ACTION_UP) vt?.xVelocity ?: 0f else 0f)
                    2 -> if (e.actionMasked == MotionEvent.ACTION_UP && (downY - e.y > 80f.u || (vt?.yVelocity ?: 0f) < -900f.u)) onSwipeUp?.invoke()
                    0 -> if (e.actionMasked == MotionEvent.ACTION_UP && abs(e.x - downX) < slop && abs(e.y - downY) < slop) onEmptyTap?.invoke()
                }
                mode = 0
                velocity?.recycle()
                velocity = null
            }
        }
        return true
    }

    private fun track(e: MotionEvent) {
        if (e.actionMasked == MotionEvent.ACTION_DOWN) { velocity?.recycle(); velocity = VelocityTracker.obtain() }
        velocity?.addMovement(e)
    }
}

/** Clamp helper for colour/alpha math. */
internal fun Float.clamp01() = min(1f, max(0f, this))
