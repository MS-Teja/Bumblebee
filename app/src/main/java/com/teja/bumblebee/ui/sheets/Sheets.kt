package com.teja.bumblebee.ui.sheets

import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.dynamicanimation.animation.DynamicAnimation
import androidx.lifecycle.lifecycleScope
import androidx.dynamicanimation.animation.SpringAnimation
import androidx.dynamicanimation.animation.SpringForce
import androidx.media3.common.MediaItem
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.teja.bumblebee.R
import com.teja.bumblebee.art.ArtLoader
import com.teja.bumblebee.data.Library
import com.teja.bumblebee.data.Prefs
import com.teja.bumblebee.data.Track
import com.teja.bumblebee.playback.PlayerHub
import com.teja.bumblebee.storage.Volume
import com.teja.bumblebee.ui.MainActivity
import com.teja.bumblebee.ui.design.C
import com.teja.bumblebee.ui.design.EqBars
import com.teja.bumblebee.ui.design.Fonts
import com.teja.bumblebee.ui.design.MATCH
import com.teja.bumblebee.ui.design.Motion
import com.teja.bumblebee.ui.design.PillButton
import com.teja.bumblebee.ui.design.WRAP
import com.teja.bumblebee.ui.design.frame
import com.teja.bumblebee.ui.design.icon
import com.teja.bumblebee.ui.design.label
import com.teja.bumblebee.ui.design.linear
import com.teja.bumblebee.ui.design.pressable
import com.teja.bumblebee.ui.design.rounded
import com.teja.bumblebee.ui.design.roundCorners
import com.teja.bumblebee.ui.design.text
import com.teja.bumblebee.ui.design.u
import com.teja.bumblebee.ui.settings.BeeLogo
import com.teja.bumblebee.util.Fmt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

// ====================================================================== queue

@SuppressLint("ClickableViewAccessibility")
class QueueSheet(private val host: MainActivity, private val layer: FrameLayout, private val onClosed: () -> Unit) {
    private val ctx: Context = host
    private val scrim = View(ctx).apply { setBackgroundColor(0xB8090810.toInt()); alpha = 0f }
    private val panel = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL; setPadding(30.u, 0, 30.u, 0) }
    private val count = ctx.text("", 18f, Fonts.semiBold, C.alpha(C.TEXT, 0.55f))
    private val currentTitle = ctx.text("", 21f, Fonts.extraBold)
    private val currentArt = ImageView(ctx).apply { scaleType = ImageView.ScaleType.CENTER_CROP; roundCorners(14) }
    private val currentEq = EqBars(ctx)
    private val list = RecyclerView(ctx)
    private val adapter = QueueAdapter()
    private val touchHelper by lazy { makeTouchHelper() }
    private var undo: View? = null
    private var dragging = false
    private var closing = false
    private val job = host.lifecycleScope.launch {
        PlayerHub.state.drop(1).collect { if (!dragging && !closing) refresh() }
    }

    init {
        val accent = host.accent.accent
        panel.background = object : android.graphics.drawable.Drawable() {
            private val p = Paint(Paint.ANTI_ALIAS_FLAG)
            override fun draw(canvas: Canvas) {
                val b = bounds
                p.shader = LinearGradient(0f, 0f, 0f, b.height().toFloat(), intArrayOf(C.blend(0xFF161A20.toInt(), host.accent.deep, 0.7f), 0xFA0E1014.toInt()), floatArrayOf(0f, 0.45f), Shader.TileMode.CLAMP)
                val r = 34f.u
                canvas.drawRoundRect(b.left.toFloat(), b.top.toFloat(), b.right.toFloat(), b.bottom + r, r, r, p)
            }
            override fun setAlpha(alpha: Int) {}
            override fun setColorFilter(colorFilter: android.graphics.ColorFilter?) {}
            @Deprecated("Deprecated in Java")
            override fun getOpacity() = android.graphics.PixelFormat.TRANSLUCENT
        }
        val handle = FrameLayout(ctx).apply {
            addView(View(ctx).apply { background = rounded(0x4DFFFFFF, 3) }, frame(64.u, 6.u, Gravity.CENTER))
        }
        panel.addView(handle, linear(MATCH, 34.u))
        val clear = PillButton(ctx, "Clear upcoming", null, 56, false, accent).apply {
            pressable { PlayerHub.clearUpcoming(); host.toast("Queue cleared") }
        }
        panel.addView(LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(ctx.text("Up next", 30f, Fonts.extraBold), linear(WRAP, WRAP, r = 14.u))
            addView(count, linear(0, WRAP, 1f))
            addView(clear, linear(WRAP, 56.u))
        }, linear(MATCH, 64.u))
        currentEq.color = accent
        currentEq.playing = PlayerHub.state.value.playing
        panel.addView(LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(16.u, 0, 22.u, 0)
            background = rounded(C.alpha(accent, 0.14f), 22)
            addView(currentArt, linear(60.u, 60.u, r = 16.u))
            addView(LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                addView(ctx.text("NOW PLAYING", 13f, Fonts.extraBold, accent).apply { letterSpacing = 0.14f }, linear(MATCH, WRAP))
                addView(currentTitle, linear(MATCH, WRAP, t = 2.u))
            }, linear(0, WRAP, 1f))
            addView(currentEq, linear(20.u, 20.u))
        }, linear(MATCH, 84.u, t = 6.u))
        list.layoutManager = LinearLayoutManager(ctx)
        list.adapter = adapter
        list.clipToPadding = false
        list.setPadding(0, 10.u, 0, 40.u)
        list.itemAnimator?.changeDuration = 0
        panel.addView(list, linear(MATCH, 0, 1f))
        touchHelper.attachToRecyclerView(list)

        scrim.setOnClickListener { dismiss() }
        layer.addView(scrim, frame(MATCH, MATCH))
        val side = if (Prefs.driverRight) Gravity.START else Gravity.END
        layer.addView(panel, frame(1068.u, 664.u, Gravity.BOTTOM or side, l = if (Prefs.driverRight) 40.u else 172.u, r = if (Prefs.driverRight) 172.u else 40.u))
        panel.translationY = 700f.u
        scrim.animate().alpha(1f).setDuration(Motion.ms(220)).start()
        SpringAnimation(panel, DynamicAnimation.TRANSLATION_Y, 0f).apply {
            spring = SpringForce(0f).setStiffness(380f).setDampingRatio(0.84f)
            if (Motion.reduced) panel.translationY = 0f else start()
        }
        var downY = 0f
        handle.setOnTouchListener { _, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> downY = e.rawY
                MotionEvent.ACTION_MOVE -> panel.translationY = (e.rawY - downY).coerceAtLeast(0f)
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL ->
                    if (panel.translationY > 120f.u) dismiss() else panel.animate().translationY(0f).setDuration(220).setInterpolator(Motion.emphasized).start()
            }
            true
        }
        refresh()
    }

    private fun refresh() {
        val s = PlayerHub.state.value
        val cur = s.current
        currentTitle.text = cur?.let { "${it.title}  ·  ${it.artistLabel}" } ?: ""
        ArtLoader.bind(currentArt, cur, ArtLoader.Size.SMALL)
        currentEq.playing = s.playing
        val up = PlayerHub.upcoming()
        adapter.items = up.toMutableList()
        adapter.notifyDataSetChanged()
        count.text = "${Fmt.count(up.size, "song")}" + if (s.context.label.isNotBlank()) " · ${s.context.label}" else ""
    }

    fun dismiss() {
        if (closing) return
        closing = true
        job.cancel()
        scrim.animate().alpha(0f).setDuration(Motion.ms(200)).start()
        panel.animate().translationY(panel.height + 40f.u).setDuration(Motion.ms(240)).setInterpolator(Motion.exit).withEndAction {
            layer.removeView(scrim)
            layer.removeView(panel)
            onClosed()
        }.start()
    }

    private fun makeTouchHelper() = ItemTouchHelper(object : ItemTouchHelper.SimpleCallback(ItemTouchHelper.UP or ItemTouchHelper.DOWN, ItemTouchHelper.LEFT) {
        private var from = -1
        private var to = -1

        override fun isLongPressDragEnabled() = false

        override fun getMovementFlags(rv: RecyclerView, vh: RecyclerView.ViewHolder): Int =
            makeMovementFlags(if (PlayerHub.state.value.shuffle) 0 else ItemTouchHelper.UP or ItemTouchHelper.DOWN, ItemTouchHelper.LEFT)

        override fun onMove(rv: RecyclerView, vh: RecyclerView.ViewHolder, target: RecyclerView.ViewHolder): Boolean {
            val a = vh.bindingAdapterPosition
            val b = target.bindingAdapterPosition
            if (from == -1) from = a
            to = b
            adapter.items.add(b, adapter.items.removeAt(a))
            adapter.notifyItemMoved(a, b)
            return true
        }

        override fun onSelectedChanged(vh: RecyclerView.ViewHolder?, actionState: Int) {
            super.onSelectedChanged(vh, actionState)
            if (actionState == ItemTouchHelper.ACTION_STATE_DRAG) {
                dragging = true
                vh?.itemView?.animate()?.scaleX(1.03f)?.scaleY(1.03f)?.setDuration(120)?.start()
                vh?.itemView?.background = rounded(0xFF2A2236.toInt(), 20)
            }
        }

        override fun clearView(rv: RecyclerView, vh: RecyclerView.ViewHolder) {
            super.clearView(rv, vh)
            vh.itemView.animate().scaleX(1f).scaleY(1f).setDuration(160).start()
            vh.itemView.background = null
            if (from != -1 && to != -1 && from != to) {
                val base = PlayerHub.state.value.index + 1
                PlayerHub.move(base + from, base + to)
            }
            from = -1; to = -1
            dragging = false
        }

        override fun onSwiped(vh: RecyclerView.ViewHolder, direction: Int) {
            val pos = vh.bindingAdapterPosition
            val entry = adapter.items.getOrNull(pos) ?: return
            val item = PlayerHub.itemAt(entry.index) ?: return
            adapter.items.removeAt(pos)
            adapter.notifyItemRemoved(pos)
            PlayerHub.remove(entry.index)
            showUndo(entry.track, entry.index, item)
        }

        override fun onChildDraw(c: Canvas, rv: RecyclerView, vh: RecyclerView.ViewHolder, dX: Float, dY: Float, state: Int, active: Boolean) {
            if (state == ItemTouchHelper.ACTION_STATE_SWIPE) {
                val p = Paint().apply { color = C.alpha(C.DANGER, (-dX / vh.itemView.width).coerceIn(0f, 0.3f)) }
                val v = vh.itemView
                c.drawRoundRect(v.left.toFloat(), v.top + 4f.u, v.right.toFloat(), v.bottom - 4f.u, 20f.u, 20f.u, p)
                val t = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = C.DANGER; typeface = Fonts.extraBold; textSize = 16f.u; textAlign = Paint.Align.RIGHT; letterSpacing = 0.08f; alpha = ((-dX / 160f.u).coerceIn(0f, 1f) * 255).toInt() }
                c.drawText("REMOVE", v.right - 26f.u, v.top + v.height / 2f + 6f.u, t)
            }
            super.onChildDraw(c, rv, vh, dX, dY, state, active)
        }
    })

    private fun showUndo(track: Track, index: Int, item: MediaItem) {
        undo?.let { layer.removeView(it) }
        val bar = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = rounded(C.TEXT, 32)
            setPadding(26.u, 0, 8.u, 0)
            addView(ctx.text("Removed “${track.title}”", 18f, Fonts.bold, C.INK), linear(WRAP, WRAP, r = 18.u))
            addView(ctx.text("Undo", 18f, Fonts.extraBold, C.BEE).apply {
                gravity = Gravity.CENTER
                background = rounded(C.INK, 26)
                setPadding(24.u, 0, 24.u, 0)
                pressable { PlayerHub.insert(index, item); layer.removeView(this@apply.parent as View); undo = null; Handler(Looper.getMainLooper()).postDelayed({ refresh() }, 150) }
            }, linear(WRAP, 52.u))
        }
        undo = bar
        layer.addView(bar, frame(WRAP, 68.u, Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL, b = 30.u))
        bar.alpha = 0f
        bar.translationY = 20f.u
        bar.animate().alpha(1f).translationY(0f).setDuration(220).start()
        bar.postDelayed({ if (undo === bar) { bar.animate().alpha(0f).setDuration(200).withEndAction { layer.removeView(bar) }.start(); undo = null } }, 4_000)
    }

    private inner class QueueAdapter : RecyclerView.Adapter<QueueAdapter.H>() {
        var items: MutableList<PlayerHub.QueueEntry> = mutableListOf()
        override fun getItemCount() = items.size
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) = H()
        override fun onBindViewHolder(h: H, position: Int) = h.bind(items[position])

        inner class H : RecyclerView.ViewHolder(LinearLayout(ctx)) {
            private val art = ImageView(ctx).apply { scaleType = ImageView.ScaleType.CENTER_CROP; roundCorners(12) }
            private val title = ctx.text("", 19f, Fonts.bold)
            private val sub = ctx.text("", 15f, Fonts.semiBold, C.alpha(C.TEXT, 0.55f))
            private val time = ctx.text("", 16f, Fonts.semiBold, C.alpha(C.TEXT, 0.45f)).apply { fontFeatureSettings = "tnum" }
            private val drag = FrameLayout(ctx).apply { addView(ctx.icon(R.drawable.ic_drag, 28, C.TEXT2), frame(28.u, 28.u, Gravity.CENTER)); contentDescription = "Drag to reorder" }

            init {
                (itemView as LinearLayout).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    setPadding(16.u, 0, 6.u, 0)
                    addView(art, linear(52.u, 52.u, r = 16.u))
                    addView(LinearLayout(ctx).apply {
                        orientation = LinearLayout.VERTICAL
                        addView(title, linear(MATCH, WRAP)); addView(sub, linear(MATCH, WRAP, t = 2.u))
                    }, linear(0, WRAP, 1f, r = 12.u))
                    addView(time, linear(WRAP, WRAP, r = 6.u))
                    addView(drag, linear(64.u, 64.u))
                    layoutParams = RecyclerView.LayoutParams(MATCH, 76.u)
                    pressable(0.985f) { items.getOrNull(bindingAdapterPosition)?.let { PlayerHub.jumpTo(it.index) } }
                }
                drag.setOnTouchListener { _, e ->
                    if (e.actionMasked == MotionEvent.ACTION_DOWN) {
                        if (PlayerHub.state.value.shuffle) host.toast("Turn off shuffle to reorder") else touchHelper.startDrag(this)
                    }
                    false
                }
            }

            fun bind(e: PlayerHub.QueueEntry) {
                title.text = e.track.title
                sub.text = e.track.artistLabel
                time.text = if (e.track.durationMs > 0) Fmt.time(e.track.durationMs) else ""
                ArtLoader.bind(art, e.track, ArtLoader.Size.SMALL)
            }
        }
    }
}

// ====================================================================== USB card

/** "Tuning in…": a radio-dial needle sweeps across, then the drive's card settles in from the driver side. */
class UsbCard(
    host: MainActivity,
    private val layer: FrameLayout,
    volume: Volume,
    resume: Track?,
    onPlay: () -> Unit,
    onOpen: () -> Unit,
) {
    private val ctx: Context = host
    private val card = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
    private var gone = false

    init {
        val accent = C.BEE
        card.background = rounded(0xFF161D21.toInt(), 30)
        card.setPadding(24.u, 22.u, 24.u, 0)
        val dial = TuningDial(ctx)
        card.addView(dial, linear(MATCH, 34.u))
        val art = ImageView(ctx).apply { scaleType = ImageView.ScaleType.CENTER_CROP; roundCorners(18) }
        ArtLoader.bindFolder(art, volume.path, "Mixtape Vol. ${volume.mixtape}", ArtLoader.Size.SMALL)
        val sub = ctx.text("Mixtape Vol. ${volume.mixtape} · scanning…", 17f, Fonts.semiBold, C.TEXT2)
        card.addView(LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(art, linear(96.u, 96.u, r = 18.u))
            addView(LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                addView(ctx.text("${volume.label} connected", 26f, Fonts.extraBold), linear(MATCH, WRAP))
                addView(sub, linear(MATCH, WRAP, t = 4.u))
            }, linear(0, WRAP, 1f))
        }, linear(MATCH, WRAP, t = 18.u))
        val primary = PillButton(ctx, if (resume != null) "Resume ${resume.title}" else "Play", R.drawable.ic_play, 72, true, accent).apply {
            pressable { dismiss(); onPlay() }
        }
        val open = PillButton(ctx, "Open", null, 72, false, accent).apply { pressable { dismiss(); onOpen() } }
        card.addView(LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(primary, linear(0, 72.u, 1f, r = 12.u))
            addView(open, linear(120.u, 72.u))
        }, linear(MATCH, WRAP, t = 20.u))
        val timer = View(ctx).apply { setBackgroundColor(C.alpha(accent, 0.8f)); pivotX = 0f }
        card.addView(FrameLayout(ctx).apply { setBackgroundColor(0x14FFFFFF); addView(timer, frame(MATCH, MATCH)) }, linear(MATCH, 4.u, t = 20.u))
        card.roundCorners(30)

        val right = Prefs.driverRight
        layer.addView(card, frame(470.u, WRAP, Gravity.TOP or (if (right) Gravity.END else Gravity.START), t = 240.u, r = if (right) 156.u else 0, l = if (right) 0 else 156.u))
        card.translationX = (if (right) 1 else -1) * 620f.u
        SpringAnimation(card, DynamicAnimation.TRANSLATION_X, 0f).apply {
            spring = SpringForce(0f).setStiffness(300f).setDampingRatio(0.78f)
            if (Motion.reduced) card.translationX = 0f else start()
        }
        dial.tune(volume.label)
        timer.scaleX = 1f
        timer.animate().scaleX(0f).setDuration(8_000).setInterpolator(null).withEndAction { dismiss() }.start()
        host.lifecycleScope.launch {
            kotlinx.coroutines.delay(1_500)
            repeat(10) {
                val (songs, _) = withContext(Dispatchers.IO) { Library.volumeStats(volume.id) }
                if (songs > 0) { sub.text = "${Fmt.count(songs, "song")} · Mixtape Vol. ${volume.mixtape}"; return@launch }
                kotlinx.coroutines.delay(1_000)
            }
        }
    }

    fun dismiss() {
        if (gone) return
        gone = true
        card.animate().alpha(0f).translationX(card.translationX + (if (Prefs.driverRight) 80f else -80f).u).setDuration(Motion.ms(220)).withEndAction {
            layer.removeView(card)
        }.start()
    }

    /** Frequency scale with a sweeping needle; settles and shows the station (drive) name. */
    private class TuningDial(ctx: Context) : View(ctx) {
        private var needle = 0.05f
        private var labelText = "TUNING IN…"
        private val p = Paint(Paint.ANTI_ALIAS_FLAG)

        fun tune(station: String) {
            if (Motion.reduced) { needle = 0.63f; labelText = station.uppercase(); invalidate(); return }
            ValueAnimator.ofFloat(0.05f, 0.92f, 0.63f).apply {
                duration = 1300
                interpolator = Motion.standard
                addUpdateListener { needle = it.animatedValue as Float; invalidate() }
                addListener(object : android.animation.AnimatorListenerAdapter() {
                    override fun onAnimationEnd(animation: android.animation.Animator) { labelText = station.uppercase(); invalidate() }
                })
                start()
            }
        }

        override fun onDraw(canvas: Canvas) {
            val w = width.toFloat()
            val h = height.toFloat()
            p.color = 0x14FFC72C
            canvas.drawRoundRect(0f, 0f, w, h, 10f.u, 10f.u, p)
            val ticks = 17
            for (i in 0 until ticks) {
                val x = 10f.u + (w - 20f.u) * i / (ticks - 1)
                val major = i % 4 == 0
                p.color = if (major) 0x99FFC72C.toInt() else 0x59FFC72C
                canvas.drawRect(x, h - 6f.u - (if (major) 14f else 7f).u, x + 2f.u, h - 6f.u, p)
            }
            p.color = C.BEE
            p.setShadowLayer(8f.u, 0f, 0f, C.BEE)
            val nx = w * needle
            canvas.drawRoundRect(nx - 1.5f.u, 4f.u, nx + 1.5f.u, h - 4f.u, 2f.u, 2f.u, p)
            p.clearShadowLayer()
            p.typeface = Fonts.extraBold
            p.textSize = 12f.u
            p.letterSpacing = 0.16f
            canvas.drawText(labelText, 12f.u, 18f.u, p)
        }

        init { setLayerType(LAYER_TYPE_SOFTWARE, null) }
    }
}

// ====================================================================== onboarding

/** One calm screen: why we need access, one big button. Dismisses itself once access is granted. */
class Onboarding(private val host: MainActivity, private val layer: FrameLayout, private val onGranted: () -> Unit) {
    private val ctx: Context = host
    private val root = FrameLayout(ctx)
    private val handler = Handler(Looper.getMainLooper())

    init {
        root.background = android.graphics.drawable.GradientDrawable(
            android.graphics.drawable.GradientDrawable.Orientation.TL_BR,
            intArrayOf(0xFF1A1405.toInt(), 0xFF07090C.toInt(), 0xFF0B1418.toInt()),
        )
        root.isClickable = true
        val col = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_VERTICAL }
        col.addView(BeeLogo(ctx), linear(120.u, 120.u))
        col.addView(ctx.text("Your music,\non the big screen", 52f, Fonts.extraBold, lines = 2).apply { letterSpacing = -0.02f; setLineSpacing(0f, 1.05f) }, linear(MATCH, WRAP, t = 34.u))
        col.addView(ctx.text("Bumblebee plays the songs on this unit and on any pendrive you plug in.\nIt needs permission to read your files. Nothing ever leaves the car.", 20f, Fonts.semiBold, C.TEXT2, lines = 3).apply { setLineSpacing(0f, 1.25f) }, linear(MATCH, WRAP, t = 18.u))
        col.addView(PillButton(ctx, "Allow access", R.drawable.ic_check, 80, true, C.BEE).apply { pressable { host.requestStorage() } }, linear(WRAP, 80.u, t = 36.u))
        col.addView(ctx.label("Rewinding the tapes happens next", C.TEXT3, 14f), linear(WRAP, WRAP, t = 18.u))
        root.addView(col, frame(760.u, MATCH, Gravity.START, l = 120.u))
        layer.addView(root, frame(MATCH, MATCH))
        poll()
    }

    private fun poll() {
        if (host.hasStorageAccess()) {
            root.animate().alpha(0f).setDuration(Motion.ms(360)).withEndAction { layer.removeView(root) }.start()
            onGranted()
            return
        }
        handler.postDelayed({ poll() }, 800)
    }
}
