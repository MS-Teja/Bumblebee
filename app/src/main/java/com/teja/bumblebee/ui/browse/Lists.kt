package com.teja.bumblebee.ui.browse

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.dynamicanimation.animation.DynamicAnimation
import androidx.dynamicanimation.animation.SpringAnimation
import androidx.dynamicanimation.animation.SpringForce
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.teja.bumblebee.R
import com.teja.bumblebee.art.ArtLoader
import com.teja.bumblebee.data.Track
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
import com.teja.bumblebee.ui.design.outlineCircle
import com.teja.bumblebee.ui.design.ovalClip
import com.teja.bumblebee.ui.design.pressable
import com.teja.bumblebee.ui.design.rounded
import com.teja.bumblebee.ui.design.roundCorners
import com.teja.bumblebee.ui.design.text
import com.teja.bumblebee.ui.design.u
import com.teja.bumblebee.util.Fmt
import kotlin.math.abs

// ====================================================================== swipe-to-play-next row

/** A row whose content can be dragged right to reveal "Play next"; releasing past the line commits. */
@SuppressLint("ClickableViewAccessibility")
class SwipeRow(ctx: Context) : FrameLayout(ctx) {
    val reveal = LinearLayout(ctx).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(24.u, 0, 0, 0)
        alpha = 0f
    }
    private val revealText = ctx.text("PLAY NEXT", 16f, Fonts.extraBold, C.BEE).apply { letterSpacing = 0.06f }
    private val revealIcon = ctx.icon(R.drawable.ic_play_next, 24, C.BEE)
    val content = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
    var onCommit: (() -> Unit)? = null
    var accent = C.BEE
        set(v) {
            field = v
            reveal.background = rounded(C.alpha(v, 0.2f), 20)
            revealText.setTextColor(v)
            revealIcon.setColorFilter(v)
        }

    private val slop = ViewConfiguration.get(ctx).scaledTouchSlop
    private var downX = 0f
    private var downY = 0f
    private var dragging = false
    private val threshold = 110f.u
    private val spring = SpringAnimation(content, DynamicAnimation.TRANSLATION_X, 0f).apply { spring = SpringForce(0f).setStiffness(600f).setDampingRatio(0.7f) }

    init {
        reveal.addView(revealIcon, linear(24.u, 24.u, r = 10.u))
        reveal.addView(revealText)
        addView(reveal, frame(MATCH, MATCH))
        addView(content, frame(MATCH, MATCH))
        accent = C.BEE
    }

    override fun onInterceptTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> { downX = e.x; downY = e.y; dragging = false }
            MotionEvent.ACTION_MOVE -> {
                val dx = e.x - downX
                if (dx > slop && dx > abs(e.y - downY) * 1.6f) {
                    dragging = true
                    parent.requestDisallowInterceptTouchEvent(true)
                    spring.cancel()
                    downX = e.x
                    return true
                }
            }
        }
        return false
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        if (!dragging) return super.onTouchEvent(e)
        when (e.actionMasked) {
            MotionEvent.ACTION_MOVE -> {
                val x = (e.x - downX).coerceIn(0f, 190f.u)
                content.translationX = x
                reveal.alpha = (x / 90f.u).coerceIn(0f, 1f)
                revealText.text = if (x > threshold) "RELEASE: PLAY NEXT" else "PLAY NEXT"
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                val commit = content.translationX > threshold && e.actionMasked == MotionEvent.ACTION_UP
                dragging = false
                spring.animateToFinalPosition(0f)
                reveal.animate().alpha(0f).setDuration(240).start()
                if (commit) {
                    revealText.text = "QUEUED"
                    onCommit?.invoke()
                }
            }
        }
        return true
    }
}

// ====================================================================== track rows

data class TrackItem(val track: Track, val number: Int, val showArt: Boolean = false)

/** Track list with the playing row highlighted (accent title + equaliser). */
class TrackAdapter(
    private val onPlay: (Int) -> Unit,
    private val onLong: (Track) -> Unit,
    private val onPlayNext: (Track) -> Unit,
) : RecyclerView.Adapter<TrackAdapter.Holder>() {

    var items: List<TrackItem> = emptyList()
        private set
    private var playingPath: String? = null
    private var isPlaying = false
    private var highlightPath: String? = null
    var accent = C.BEE
        set(v) { if (field != v) { field = v; notifyItemRangeChanged(0, itemCount, PAYLOAD_STATE) } }

    init { setHasStableIds(true) }

    fun submit(list: List<TrackItem>) {
        val old = items
        items = list
        DiffUtil.calculateDiff(object : DiffUtil.Callback() {
            override fun getOldListSize() = old.size
            override fun getNewListSize() = list.size
            override fun areItemsTheSame(o: Int, n: Int) = old[o].track.path == list[n].track.path
            override fun areContentsTheSame(o: Int, n: Int) = old[o] == list[n]
        }).dispatchUpdatesTo(this)
    }

    fun setPlaying(path: String?, playing: Boolean) {
        if (path == playingPath && playing == isPlaying) return
        val before = items.indexOfFirst { it.track.path == playingPath }
        playingPath = path
        isPlaying = playing
        val after = items.indexOfFirst { it.track.path == path }
        if (before >= 0) notifyItemChanged(before, PAYLOAD_STATE)
        if (after >= 0) notifyItemChanged(after, PAYLOAD_STATE)
    }

    fun highlight(path: String?) { highlightPath = path }

    override fun getItemCount() = items.size
    override fun getItemId(position: Int) = items[position].track.path.hashCode().toLong()

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) = Holder(parent.context)

    override fun onBindViewHolder(h: Holder, position: Int) = h.bind(items[position], full = true)
    override fun onBindViewHolder(h: Holder, position: Int, payloads: MutableList<Any>) =
        h.bind(items[position], full = payloads.isEmpty())

    inner class Holder(ctx: Context) : RecyclerView.ViewHolder(SwipeRow(ctx)) {
        private val row = itemView as SwipeRow
        private val number = ctx.text("", 17f, Fonts.bold, C.alpha(C.TEXT, 0.42f)).apply { gravity = Gravity.END; fontFeatureSettings = "tnum" }
        private val eq = EqBars(ctx)
        private val art = ImageView(ctx).apply { scaleType = ImageView.ScaleType.CENTER_CROP; roundCorners(12) }
        private val title = ctx.text("", 20f, Fonts.bold)
        private val artist = ctx.text("", 16f, Fonts.semiBold, C.alpha(C.TEXT, 0.55f))
        private val time = ctx.text("", 17f, Fonts.semiBold, C.alpha(C.TEXT, 0.5f)).apply { fontFeatureSettings = "tnum" }
        private val bg = rounded(0, 20)

        init {
            row.layoutParams = RecyclerView.LayoutParams(MATCH, 76.u)
            val c = row.content
            c.background = bg
            c.setPadding(14.u, 0, 18.u, 0)
            val lead = FrameLayout(ctx)
            lead.addView(number, frame(MATCH, WRAP, Gravity.CENTER_VERTICAL))
            lead.addView(eq, frame(18.u, 18.u, Gravity.CENTER_VERTICAL or Gravity.END))
            c.addView(lead, linear(34.u, MATCH, r = 16.u))
            c.addView(art, linear(52.u, 52.u, r = 16.u))
            val texts = LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                addView(title, linear(MATCH, WRAP))
                addView(artist, linear(MATCH, WRAP, t = 3.u))
            }
            c.addView(texts, linear(0, WRAP, 1f, r = 12.u))
            c.addView(time, linear(WRAP, WRAP))
            c.pressable(0.985f, onLongClick = { items.getOrNull(bindingAdapterPosition)?.let { onLong(it.track) } }) {
                if (bindingAdapterPosition != RecyclerView.NO_POSITION) onPlay(bindingAdapterPosition)
            }
            row.onCommit = { items.getOrNull(bindingAdapterPosition)?.let { onPlayNext(it.track) } }
        }

        fun bind(item: TrackItem, full: Boolean) {
            val t = item.track
            val playing = t.path == playingPath
            if (full) {
                number.text = item.number.toString()
                title.text = t.title
                artist.text = listOfNotNull(t.artist ?: t.albumArtist, t.album.takeIf { item.showArt }).joinToString(" · ").ifBlank { "Unknown artist" }
                time.text = if (t.durationMs > 0) Fmt.time(t.durationMs) else ""
                art.visibility = if (item.showArt) View.VISIBLE else View.GONE
                if (item.showArt) ArtLoader.bind(art, t, ArtLoader.Size.SMALL)
            }
            row.accent = accent
            title.setTextColor(if (playing) accent else C.TEXT)
            number.visibility = if (playing) View.INVISIBLE else View.VISIBLE
            eq.visibility = if (playing) View.VISIBLE else View.GONE
            eq.color = accent
            eq.playing = playing && isPlaying
            bg.setColor(if (playing) C.alpha(accent, 0.10f) else 0)
            if (t.path == highlightPath && full) {
                highlightPath = null
                bg.setColor(C.alpha(accent, 0.28f))
                row.content.postDelayed({ bg.setColor(if (t.path == playingPath) C.alpha(accent, 0.10f) else 0) }, 1200)
            }
        }
    }

    companion object { const val PAYLOAD_STATE = "state" }
}

// ====================================================================== folder cards

data class FolderCardItem(val path: String, val name: String, val subtitle: String, val isPlaylist: Boolean = false)

/** Folder cards: big (mosaic tile + name) when a folder has only subfolders, compact chips above tracks. */
class FolderCardAdapter(
    private val big: Boolean,
    private val onOpen: (FolderCardItem) -> Unit,
    private val onPlay: (FolderCardItem) -> Unit,
    private val onLong: (FolderCardItem) -> Unit,
) : RecyclerView.Adapter<FolderCardAdapter.Holder>() {
    var items: List<FolderCardItem> = emptyList()
        set(v) { field = v; notifyDataSetChanged() }

    override fun getItemCount() = items.size
    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) = Holder(parent.context)
    override fun onBindViewHolder(h: Holder, position: Int) = h.bind(items[position])

    inner class Holder(ctx: Context) : RecyclerView.ViewHolder(LinearLayout(ctx)) {
        private val root = itemView as LinearLayout
        private val art = ImageView(ctx).apply { scaleType = ImageView.ScaleType.CENTER_CROP; roundCorners(if (big) 22 else 14) }
        private val name = ctx.text("", if (big) 19f else 18f, Fonts.extraBold)
        private val sub = ctx.text("", 15f, Fonts.semiBold, C.alpha(C.TEXT, 0.55f))
        private val playBtn = FrameLayout(ctx).apply {
            background = com.teja.bumblebee.ui.design.circle(0xB807090C.toInt())
            addView(ctx.icon(R.drawable.ic_play, 18, C.TEXT), frame(18.u, 18.u, Gravity.CENTER))
        }

        init {
            if (big) {
                root.orientation = LinearLayout.VERTICAL
                val box = FrameLayout(ctx)
                box.addView(art, frame(MATCH, MATCH))
                box.addView(playBtn, frame(48.u, 48.u, Gravity.BOTTOM or Gravity.END, r = 10.u, b = 10.u))
                root.addView(box, linear(MATCH, 0, 1f))
                root.addView(name, linear(MATCH, WRAP, t = 10.u))
                root.addView(sub, linear(MATCH, WRAP, t = 2.u))
                root.layoutParams = RecyclerView.LayoutParams(MATCH, 264.u).apply { bottomMargin = 18.u; marginEnd = 16.u }
            } else {
                root.orientation = LinearLayout.HORIZONTAL
                root.gravity = Gravity.CENTER_VERTICAL
                root.background = rounded(0x0FFFFFFF, 22)
                root.setPadding(12.u, 0, 10.u, 0)
                root.addView(art, linear(60.u, 60.u, r = 12.u))
                val texts = LinearLayout(ctx).apply {
                    orientation = LinearLayout.VERTICAL
                    addView(name, linear(MATCH, WRAP)); addView(sub, linear(MATCH, WRAP, t = 2.u))
                }
                root.addView(texts, linear(0, WRAP, 1f))
                root.addView(playBtn, linear(44.u, 44.u))
                root.layoutParams = RecyclerView.LayoutParams(MATCH, 84.u).apply { bottomMargin = 12.u; marginEnd = 12.u }
            }
            root.pressable(0.97f, onLongClick = { items.getOrNull(bindingAdapterPosition)?.let(onLong) }) {
                items.getOrNull(bindingAdapterPosition)?.let(onOpen)
            }
            playBtn.pressable(0.9f) { items.getOrNull(bindingAdapterPosition)?.let(onPlay) }
        }

        fun bind(item: FolderCardItem) {
            name.text = item.name
            sub.text = item.subtitle
            if (item.isPlaylist) {
                art.setImageDrawable(com.teja.bumblebee.art.CassetteCover(item.name, item.path))
            } else {
                ArtLoader.bindFolder(art, item.path, item.name, if (big) ArtLoader.Size.LARGE else ArtLoader.Size.SMALL)
            }
        }
    }
}

// ====================================================================== hero pane

/** Left pane of folder / album / artist pages: big art, name, stats, Play + Shuffle. */
class HeroPane(ctx: Context, round: Boolean = false) : LinearLayout(ctx) {
    val art = ImageView(ctx).apply { scaleType = ImageView.ScaleType.CENTER_CROP; if (round) ovalClip() else roundCorners(30) }
    val title: TextView = ctx.text("", 36f, Fonts.extraBold, lines = 2).apply { letterSpacing = -0.02f }
    val subtitle: TextView = ctx.text("", 18f, Fonts.semiBold, C.alpha(C.TEXT, 0.62f), lines = 2)
    val play = PillButton(ctx, "Play", R.drawable.ic_play, 72, true, C.BEE)
    val shuffle = FrameLayout(ctx).apply {
        background = outlineCircle(0x47FFFFFF, 2)
        addView(ctx.icon(R.drawable.ic_shuffle, 28, C.TEXT), frame(28.u, 28.u, Gravity.CENTER))
        contentDescription = "Shuffle"
    }

    init {
        orientation = VERTICAL
        setPadding(40.u, 34.u, 20.u, 0)
        addView(art, linear(300.u, 300.u))
        addView(title, linear(MATCH, WRAP, t = 22.u))
        addView(subtitle, linear(MATCH, WRAP, t = 6.u))
        val buttons = LinearLayout(ctx).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(play, linear(WRAP, 72.u, r = 14.u))
            addView(shuffle, linear(72.u, 72.u))
        }
        addView(buttons, linear(MATCH, WRAP, t = 22.u))
    }

    fun setAccent(c: Int) = play.setAccent(c)
}

// ====================================================================== A–Z rail

/** Fast scroller for long lists: drag along the letters, a big bubble shows where you are. */
@SuppressLint("ClickableViewAccessibility")
class AlphabetRail(ctx: Context, private val list: RecyclerView, private val keyOf: (Int) -> String?) : View(ctx) {
    private val letters = ('A'..'Z').map { it.toString() } + "#"
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { typeface = Fonts.extraBold; textAlign = Paint.Align.CENTER; textSize = 13f.u }
    private var active: String? = null
    var bubble: TextView? = null
    var accent = C.BEE

    override fun onDraw(canvas: Canvas) {
        val step = height / letters.size.toFloat()
        letters.forEachIndexed { i, l ->
            paint.color = if (l == active) accent else C.alpha(C.TEXT, 0.45f)
            canvas.drawText(l, width / 2f, step * i + step * 0.7f, paint)
        }
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE -> {
                parent.requestDisallowInterceptTouchEvent(true)
                val i = ((e.y / height) * letters.size).toInt().coerceIn(0, letters.size - 1)
                val l = letters[i]
                if (l != active) {
                    active = l
                    jump(l)
                    invalidate()
                }
                bubble?.let { b ->
                    b.text = l
                    b.translationY = (e.y - b.height / 2f).coerceIn(0f, (height - b.height).toFloat())
                    if (b.alpha < 1f) b.animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(120).start()
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                active = null
                invalidate()
                bubble?.animate()?.alpha(0f)?.scaleX(0.8f)?.scaleY(0.8f)?.setDuration(Motion.ms(200))?.start()
            }
        }
        return true
    }

    private fun jump(letter: String) {
        val count = list.adapter?.itemCount ?: return
        for (i in 0 until count) {
            val k = keyOf(i) ?: continue
            val first = k.firstOrNull()?.uppercaseChar() ?: continue
            val match = if (letter == "#") !first.isLetter() else first.toString() == letter || first > letter[0]
            if (match) {
                (list.layoutManager as? LinearLayoutManager)?.scrollToPositionWithOffset(i, 0)
                return
            }
        }
    }
}

/** Shared empty state ("Nothing here." / "Bee is speechless."). */
fun emptyState(ctx: Context, title: String, line: String, action: String?, onAction: (() -> Unit)?): View =
    LinearLayout(ctx).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER
        addView(ctx.icon(R.drawable.ic_music, 64, C.alpha(C.TEXT, 0.25f)), linear(64.u, 64.u, b = 18.u))
        addView(ctx.text(title, 28f, Fonts.extraBold).apply { gravity = Gravity.CENTER }, linear(WRAP, WRAP))
        addView(ctx.text(line, 18f, Fonts.semiBold, C.alpha(C.TEXT, 0.55f)).apply { gravity = Gravity.CENTER }, linear(WRAP, WRAP, t = 8.u))
        if (action != null && onAction != null) {
            addView(PillButton(ctx, action, null, 64, false, C.BEE).apply { pressable { onAction() } }, linear(WRAP, 64.u, t = 22.u))
        }
    }

fun sectionLabel(ctx: Context, text: String) = ctx.label(text, C.TEXT3, 14f)
