package com.teja.bumblebee.ui.nowplaying

import android.content.Context
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffColorFilter
import android.graphics.RadialGradient
import android.graphics.Shader
import android.graphics.drawable.ShapeDrawable
import android.graphics.drawable.shapes.OvalShape
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import androidx.dynamicanimation.animation.DynamicAnimation
import androidx.dynamicanimation.animation.FloatPropertyCompat
import androidx.dynamicanimation.animation.SpringAnimation
import androidx.dynamicanimation.animation.SpringForce
import com.teja.bumblebee.art.ArtLoader
import com.teja.bumblebee.data.Track
import com.teja.bumblebee.ui.design.Motion
import com.teja.bumblebee.ui.design.frame
import com.teja.bumblebee.ui.design.roundCorners
import com.teja.bumblebee.ui.design.u
import kotlin.math.abs
import kotlin.math.min
import kotlin.math.sign

/**
 * The song deck. Only the current cover is visible at rest. A swipe drags it away 1:1 (tilting and
 * fading like a card being dealt off the top) while the next/previous cover rises from behind it.
 * The release velocity carries into a spring. Every skip — swipe, button, steering wheel — runs
 * through the same animation, and a new swipe can grab the deck mid-flight.
 */
class CoverCarousel(ctx: Context, sizeDp: Int, leftDp: Int, topDp: Int) : FrameLayout(ctx) {

    interface Listener {
        /** Signed drag progress: negative while the next song comes in, positive for the previous one. */
        fun onProgress(progress: Float)
        /** The user committed a skip (+1 next, -1 previous); the player should change now. */
        fun onUserSkip(dir: Int)
        /** The strip came to rest; [dir] is the direction it moved (0 = snapped back). */
        fun onSettled(dir: Int)
    }

    var listener: Listener? = null
    val coverSize = sizeDp.u
    private val gap = 24.u
    private val step = (coverSize + gap).toFloat()
    private val coverLeft = leftDp.u
    private val coverTop = topDp.u

    private val slots = Array(3) { ImageView(ctx) }
    private val tracks = arrayOfNulls<Track>(3)
    private var order = intArrayOf(0, 1, 2) // order[pos] = slot index; pos 0 = prev, 1 = current, 2 = next
    private var offset = 0f
    private var committing = 0
    private var pendingBind: Array<Track?>? = null
    private var dragStart = 0f
    private var dragging = false
    private var desaturated = false

    private val glow = View(ctx)
    private val glowDrawable = ShapeDrawable(OvalShape())

    private val offsetProp = object : FloatPropertyCompat<CoverCarousel>("offset") {
        override fun getValue(o: CoverCarousel) = o.offset
        override fun setValue(o: CoverCarousel, v: Float) { o.offset = v; o.layoutStrip() }
    }
    private val spring = SpringAnimation(this, offsetProp).apply {
        spring = SpringForce(0f)
        addEndListener { _, canceled, _, _ -> if (!canceled) onSpringEnd() }
    }

    val hasPrev get() = tracks[order[0]] != null
    val hasNext get() = tracks[order[2]] != null
    val currentTrack get() = tracks[order[1]]
    /** The songs actually on the strip (may lag the player while an animation finishes). */
    val prevTrack get() = tracks[order[0]]
    val nextTrack get() = tracks[order[2]]
    val isMoving get() = dragging || spring.isRunning

    // Draw order: glow, neighbours, then the current cover on top (no relayout needed).
    override fun getChildDrawingOrder(childCount: Int, drawingPosition: Int): Int {
        if (childCount != 4) return drawingPosition
        return when (drawingPosition) {
            0 -> 0
            1 -> 1 + order[0]
            2 -> 1 + order[2]
            else -> 1 + order[1]
        }
    }

    init {
        isChildrenDrawingOrderEnabled = true
        // The outgoing card may travel past the cover area while it fades.
        clipChildren = false
        val glowSize = (sizeDp * 0.9f).toInt().u
        glowDrawable.paint.shader = RadialGradient(
            glowSize / 2f, glowSize / 2f, glowSize / 2f,
            intArrayOf(0xFFFFFFFF.toInt(), 0x55FFFFFF, 0x00FFFFFF), floatArrayOf(0f, 0.5f, 1f), Shader.TileMode.CLAMP,
        )
        glow.background = glowDrawable
        glow.alpha = 0.5f
        addView(glow, frame(glowSize, glowSize, l = coverLeft + (coverSize - glowSize) / 2, t = coverTop + (coverSize - glowSize) / 2 + 30.u))
        slots.forEach { iv ->
            iv.scaleType = ImageView.ScaleType.CENTER_CROP
            iv.roundCorners(30)
            iv.cameraDistance = 9000f * resources.displayMetrics.density
            addView(iv, frame(coverSize, coverSize, l = coverLeft, t = coverTop))
        }
        layoutStrip()
    }

    fun setGlow(color: Int) {
        glowDrawable.paint.colorFilter = PorterDuffColorFilter(color, PorterDuff.Mode.SRC_IN)
        glow.invalidate()
    }

    /** The on-screen rectangle of the current cover (for the flying-cover transition). */
    fun currentCoverView(): View = slots[order[1]]

    // ------------------------------------------------------------------ binding

    /** Shows these three songs; animates when the change is a step to a neighbour. */
    fun bind(prev: Track?, cur: Track?, next: Track?) {
        if (committing != 0 || dragging) {
            pendingBind = arrayOf(prev, cur, next)
            return
        }
        val shown = tracks[order[1]]
        when {
            cur?.path == shown?.path -> {
                setSlot(order[0], prev)
                setSlot(order[2], next)
                if (cur != null && tracks[order[1]] !== cur) tracks[order[1]] = cur
            }
            cur != null && shown != null && cur.path == tracks[order[2]]?.path -> {
                pendingBind = arrayOf(prev, cur, next)
                animateCommit(+1, 0f)
            }
            cur != null && shown != null && cur.path == tracks[order[0]]?.path -> {
                pendingBind = arrayOf(prev, cur, next)
                animateCommit(-1, 0f)
            }
            else -> {
                setSlot(order[0], prev)
                setSlot(order[1], cur)
                setSlot(order[2], next)
                val v = slots[order[1]]
                if (shown != null) {
                    v.alpha = 0f
                    v.scaleX = 0.94f; v.scaleY = 0.94f
                    v.animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(Motion.ms(320)).setInterpolator(Motion.emphasized)
                        .withEndAction { layoutStrip() }.start()
                }
            }
        }
        applyDesaturation()
        layoutStrip()
    }

    /** Shows these three songs at once, cancelling any deck motion (used when the page reappears). */
    fun snap(prev: Track?, cur: Track?, next: Track?) {
        if (dragging) return
        spring.cancel()
        committing = 0
        pendingBind = null
        offset = 0f
        slots.forEach { it.animate().cancel() }
        val wanted = arrayOf(prev, cur, next)
        for (pos in 0..2) setSlot(order[pos], wanted[pos])
        layers(false)
        applyDesaturation()
        layoutStrip()
    }

    private fun setSlot(slot: Int, t: Track?) {
        if (tracks[slot]?.path == t?.path && t != null) { tracks[slot] = t; return }
        tracks[slot] = t
        ArtLoader.bind(slots[slot], t, ArtLoader.Size.LARGE)
    }

    fun setWaiting(waiting: Boolean) {
        desaturated = waiting
        applyDesaturation()
    }

    private fun applyDesaturation() {
        slots.forEachIndexed { i, iv ->
            iv.colorFilter = if (desaturated && i == order[1]) {
                ColorMatrixColorFilter(ColorMatrix().apply { setSaturation(0f); postConcat(ColorMatrix().apply { setScale(0.55f, 0.55f, 0.55f, 1f) }) })
            } else null
        }
    }

    // ------------------------------------------------------------------ drag

    fun beginDrag() {
        if (spring.isRunning) {
            spring.cancel()
            if (committing != 0) finishCommitNow()
        }
        dragging = true
        dragStart = offset
        layers(true)
    }

    fun dragTo(dx: Float) {
        var o = dragStart + dx
        if (o < 0 && !hasNext) o = rubber(o)
        if (o > 0 && !hasPrev) o = rubber(o)
        offset = o.coerceIn(-step * 1.4f, step * 1.4f)
        layoutStrip()
    }

    fun release(velocity: Float) {
        dragging = false
        val dir = if (offset < 0) 1 else -1
        val allowed = if (dir > 0) hasNext else hasPrev
        val far = abs(offset) > step * 0.3f
        val flung = abs(velocity) > 1000f.u && sign(velocity) == sign(offset) && abs(offset) > 40f.u
        if (allowed && (far || flung)) {
            listener?.onUserSkip(dir)
            animateCommit(dir, velocity)
        } else {
            committing = 0
            spring.spring.setStiffness(520f).setDampingRatio(0.72f).finalPosition = 0f
            spring.setStartVelocity(velocity).start()
        }
    }

    /** One gentle nudge to teach the swipe on first use. */
    fun nudge() {
        if (isMoving || !hasNext) return
        layers(true)
        offset = 0f
        spring.spring.setStiffness(140f).setDampingRatio(0.45f).finalPosition = 0f
        spring.setStartVelocity(-900f.u).start()
    }

    private fun rubber(o: Float): Float {
        val limit = step * 0.22f
        val x = abs(o)
        return sign(o) * limit * (1f - 1f / (x / limit + 1f))
    }

    // ------------------------------------------------------------------ commit / settle

    private fun animateCommit(dir: Int, velocity: Float) {
        committing = dir
        layers(true)
        if (Motion.reduced) {
            offset = -dir * step
            onSpringEnd()
            return
        }
        spring.spring.setStiffness(340f).setDampingRatio(0.86f).finalPosition = -dir * step
        spring.setStartVelocity(velocity).start()
    }

    private fun onSpringEnd() {
        if (committing != 0) {
            val dir = committing
            rotate(dir)
            committing = 0
            listener?.onSettled(dir)
        } else {
            listener?.onSettled(0)
        }
        layers(false)
        pendingBind?.let { pendingBind = null; bind(it[0], it[1], it[2]) }
    }

    /** Ends an in-flight commit instantly while keeping covers exactly where they are on screen. */
    private fun finishCommitNow() {
        val dir = committing
        rotate(dir)
        offset = 0f
        committing = 0
        listener?.onSettled(dir)
        pendingBind?.let { pb ->
            pendingBind = null
            // Neighbours only: the current cover is already right.
            if (pb[1]?.path == tracks[order[1]]?.path) { setSlot(order[0], pb[0]); setSlot(order[2], pb[2]) } else pendingBind = pb
        }
    }

    private fun rotate(dir: Int) {
        order = if (dir > 0) intArrayOf(order[1], order[2], order[0]) else intArrayOf(order[2], order[0], order[1])
        // The slot that wrapped around shows a stale cover until the next bind; hide it.
        val wrapped = if (dir > 0) order[2] else order[0]
        tracks[wrapped] = null
        slots[wrapped].setImageDrawable(null)
        slots[wrapped].setTag(com.teja.bumblebee.R.id.art_key, null)
        offset += dir * step
        applyDesaturation()
        layoutStrip()
    }

    private fun layers(on: Boolean) {
        val type = if (on) View.LAYER_TYPE_HARDWARE else View.LAYER_TYPE_NONE
        slots.forEach { if (it.layerType != type) it.setLayerType(type, null) }
    }

    // ------------------------------------------------------------------ layout

    private fun layoutStrip() {
        val p = (offset / step).coerceIn(-1.2f, 1.2f)
        val a = abs(p).coerceAtMost(1f)
        val incomingPos = if (p < 0) 2 else if (p > 0) 0 else -1
        for (pos in 0..2) {
            val v = slots[order[pos]]
            when (pos) {
                1 -> {
                    // The top card follows the finger, tilts and fades as it is dealt away.
                    v.translationX = offset
                    v.translationY = 0f
                    v.rotation = p * 5f
                    v.rotationY = -p * 10f
                    val s = 1f - 0.06f * a
                    v.scaleX = s
                    v.scaleY = s
                    // Fade faster when it travels over the text column.
                    v.alpha = (1f - a * (if (p > 0) 1.5f else 1.1f)).coerceIn(0f, 1f)
                }
                incomingPos -> {
                    // The next card rises from behind: small, dim and slightly offset, growing into place.
                    val e = a * a * (3f - 2f * a)
                    val s = 0.86f + 0.14f * e
                    v.scaleX = s
                    v.scaleY = s
                    v.rotation = 0f
                    v.rotationY = 0f
                    v.translationX = (if (p < 0) 1f else -1f) * (1f - e) * 46f.u
                    v.translationY = (1f - e) * 14f.u
                    v.alpha = if (tracks[order[pos]] == null) 0f else e.coerceIn(0f, 1f)
                }
                else -> {
                    v.alpha = 0f
                    v.translationX = 0f
                    v.rotation = 0f
                    v.rotationY = 0f
                }
            }
            v.isClickable = false
        }
        invalidate()
        glow.alpha = 0.5f * (1f - a * 0.5f)
        listener?.onProgress(p.coerceIn(-1f, 1f))
    }
}
