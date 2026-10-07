package com.teja.bumblebee.ui.nowplaying

import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.media3.common.Player
import com.teja.bumblebee.R
import com.teja.bumblebee.art.ArtLoader
import com.teja.bumblebee.data.Library
import com.teja.bumblebee.data.Prefs
import com.teja.bumblebee.data.Track
import com.teja.bumblebee.eggs.EasterEggs
import com.teja.bumblebee.playback.BeeEvents
import com.teja.bumblebee.playback.PlayerHub
import com.teja.bumblebee.ui.MainActivity
import com.teja.bumblebee.ui.design.Accent
import com.teja.bumblebee.ui.design.C
import com.teja.bumblebee.ui.design.D
import com.teja.bumblebee.ui.design.CircleButton
import com.teja.bumblebee.ui.design.Fonts
import com.teja.bumblebee.ui.design.MATCH
import com.teja.bumblebee.ui.design.Motion
import com.teja.bumblebee.ui.design.PlayPauseView
import com.teja.bumblebee.ui.design.ToggleIcon
import com.teja.bumblebee.ui.design.WRAP
import com.teja.bumblebee.ui.design.frame
import com.teja.bumblebee.ui.design.icon
import com.teja.bumblebee.ui.design.label
import com.teja.bumblebee.ui.design.linear
import com.teja.bumblebee.ui.design.pressable
import com.teja.bumblebee.ui.design.rounded
import com.teja.bumblebee.ui.design.text
import com.teja.bumblebee.ui.design.u
import com.teja.bumblebee.ui.shell.Screen
import com.teja.bumblebee.ui.shell.Tab
import com.teja.bumblebee.ui.shell.enter
import com.teja.bumblebee.ui.design.roundCorners
import com.teja.bumblebee.util.Fmt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.abs

/**
 * The hero screen. Layout (RHD, inside the 1148×720 content area): cover carousel on the passenger
 * side, title/controls next to the rail. Glance mode strips it to cover, title and three big buttons
 * that stay exactly where they were.
 */
class NowPlayingScreen(host: MainActivity) : Screen(host) {

    override val showsMiniPlayer = false
    override val backdropIntensity = 1f
    override val tab = Tab.NOW

    private lateinit var surface: SwipeSurface
    private lateinit var carousel: CoverCarousel
    private lateinit var info: FrameLayout
    private lateinit var labelTop: TextView
    private lateinit var chip: LinearLayout
    private lateinit var chipText: TextView
    private lateinit var trackText: TrackText
    private lateinit var glanceText: TrackText
    private lateinit var seek: DragSeekBar
    private lateinit var elapsed: TextView
    private lateinit var remaining: TextView
    private lateinit var bubble: TextView
    private lateinit var shuffle: ToggleIcon
    private lateinit var repeat: ToggleIcon
    private lateinit var prev: CircleButton
    private lateinit var next: CircleButton
    private lateinit var play: PlayPauseView
    private lateinit var upNext: LinearLayout
    private lateinit var upNextText: TextView
    private lateinit var badge: TextView
    private lateinit var empty: LinearLayout
    private lateinit var geo: Geo
    private var nextPanel: NextPanel? = null
    private var topBar: View? = null
    private lateinit var glanceNext: TextView

    private val handler = Handler(Looper.getMainLooper())
    private var state = PlayerHub.State()
    private var accent: Accent = Accent.BEE
    private var showRemaining = true
    private var glance = false
    private var waitingLabel: String? = null
    private var lastPath: String? = null
    private var cameoUntil = 0L
    private var autoGlanceArmed = true
    /** Colours of the song coming in, locked when a gesture starts (state moves on mid-animation). */
    private var lockedDir = 0
    private var lockedAccent: Accent? = null

    val carouselMoving: Boolean get() = ::carousel.isInitialized && carousel.isMoving

    // ------------------------------------------------------------------ build

    override fun build(): View {
        val g = Geo.compute()
        geo = g
        surface = SwipeSurface(ctx).apply { clipChildren = false }
        carousel = CoverCarousel(ctx, g.cover, g.coverInsetX, g.coverInsetY).apply {
            pivotX = (g.coverInsetX + g.cover / 2f).u
            pivotY = (g.coverInsetY + g.cover / 2f).u
        }
        surface.addView(carousel, frame(g.carouselW.u, g.carouselH.u))

        // The controls column: laid out top to bottom from the geometry so every band is used.
        info = FrameLayout(ctx).apply { clipChildren = false }
        surface.addView(info, frame(g.colW.u, g.colH.u))

        labelTop = ctx.label("Playing from", C.TEXT3, 15f)
        chipText = ctx.text("", if (g.topBar) 19f else 22f, Fonts.bold, C.alpha(C.TEXT, 0.82f))
        chip = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(chipText, linear(WRAP, WRAP))
            addView(ctx.icon(R.drawable.ic_chevron_right, 22, C.alpha(C.TEXT, 0.82f)), linear(22.u, 22.u, l = 6.u))
            pressable(0.96f) { state.context.path?.let { host.openFolder(it, state.current?.path) } }
        }
        if (g.topBar) {
            // Full-screen player: collapse on the left, where the music comes from in the middle,
            // the queue on the right.
            labelTop.gravity = Gravity.CENTER
            chipText.maxWidth = (D.contentW - 260).u
            val collapse = CircleButton(ctx, R.drawable.ic_chevron_down, 64, 30, 0x14FFFFFF, C.TEXT).apply {
                contentDescription = "Close player"
                pressable(0.9f) { host.collapsePlayer() }
            }
            val queue = CircleButton(ctx, R.drawable.ic_queue, 64, 28, 0x14FFFFFF, C.TEXT).apply {
                contentDescription = "Queue"
                pressable(0.9f) { host.showQueue() }
            }
            val middle = LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                addView(labelTop, linear(MATCH, WRAP))
                addView(chip, linear(WRAP, 40.u, t = 2.u))
            }
            topBar = FrameLayout(ctx).apply {
                addView(collapse, frame(64.u, 64.u, Gravity.CENTER_VERTICAL or Gravity.START, l = 20.u))
                addView(middle, frame(MATCH, WRAP, Gravity.CENTER_VERTICAL, l = 96.u, r = 96.u))
                addView(queue, frame(64.u, 64.u, Gravity.CENTER_VERTICAL or Gravity.END, r = 20.u))
            }
            surface.addView(topBar, frame(MATCH, Geo.TOP_BAR.u, t = 4.u))
        } else {
            info.addView(labelTop, frame(MATCH, WRAP, t = 2.u))
            info.addView(chip, frame(WRAP, 48.u, t = 20.u))
        }

        trackText = TrackText(ctx, g.titleSize, g.artistSize, showAlbum = g.showAlbum, fitWidth = g.colW, minTitle = minOf(32f, g.titleSize - 6f))
        info.addView(trackText, frame(MATCH, g.textH.u, t = g.textT.u))
        glanceText = TrackText(ctx, g.glanceTitle, 36f, showAlbum = false, fitWidth = g.glanceW, minTitle = 50f).apply { alpha = 0f }
        info.addView(glanceText, frame(g.glanceW.u, (g.textH + 90).u, t = (g.textT - 14).u))

        seek = DragSeekBar(ctx)
        info.addView(seek, frame(MATCH, 56.u, t = g.seekT.u))
        elapsed = ctx.text("0:00", 17f, Fonts.semiBold, C.alpha(C.TEXT, 0.62f)).apply { fontFeatureSettings = "tnum" }
        remaining = ctx.text("", 17f, Fonts.semiBold, C.alpha(C.TEXT, 0.62f)).apply {
            fontFeatureSettings = "tnum"
            gravity = Gravity.END
            setPadding(16.u, 6.u, 0, 6.u)
            pressable(0.96f) { showRemaining = !showRemaining; tickProgress() }
        }
        info.addView(elapsed, frame(WRAP, WRAP, l = 11.u, t = (g.seekT + 54).u))
        info.addView(remaining, frame(WRAP, WRAP, Gravity.END, r = 11.u, t = (g.seekT + 48).u))
        bubble = ctx.text("", 20f, Fonts.extraBold, C.INK).apply {
            background = rounded(C.TEXT, 12)
            setPadding(14.u, 6.u, 14.u, 6.u)
            fontFeatureSettings = "tnum"
            alpha = 0f
        }
        info.addView(bubble, frame(WRAP, WRAP, t = (g.seekT - 44).u))

        // Transport spans the full column; centres stay fixed so glance mode only scales them in place.
        prev = CircleButton(ctx, R.drawable.ic_prev, g.btn, g.btn * 44 / 104, 0x14FFFFFF, C.TEXT).apply { contentDescription = "Previous" }
        next = CircleButton(ctx, R.drawable.ic_next, g.btn, g.btn * 44 / 104, 0x14FFFFFF, C.TEXT).apply { contentDescription = "Next" }
        play = PlayPauseView(ctx, g.play * 58 / 144).apply { contentDescription = "Play or pause" }
        prev.pressable(0.9f, onLongClick = { PlayerHub.seekBy(-10_000) }) { onUserActivity(); PlayerHub.prev() }
        next.pressable(0.9f, onLongClick = { PlayerHub.seekBy(10_000) }) { onUserActivity(); PlayerHub.next() }
        play.pressable(0.92f) { onUserActivity(); PlayerHub.toggle() }
        val mid = g.colW / 2
        val spread = ((g.colW - g.btn) / 2).coerceAtMost(176)
        val dy = (g.play - g.btn) / 2
        info.addView(prev, frame(g.btn.u, g.btn.u, l = (mid - spread - g.btn / 2).u, t = (g.playT + dy).u))
        info.addView(play, frame(g.play.u, g.play.u, l = (mid - g.play / 2).u, t = g.playT.u))
        info.addView(next, frame(g.btn.u, g.btn.u, l = (mid + spread - g.btn / 2).u, t = (g.playT + dy).u))

        // Bottom row: shuffle, repeat and "up next" share one band.
        shuffle = ToggleIcon(ctx, R.drawable.ic_shuffle, 56, 26).apply { pressable { PlayerHub.toggleShuffle() }; contentDescription = "Shuffle" }
        repeat = ToggleIcon(ctx, R.drawable.ic_repeat, 56, 26).apply { pressable { PlayerHub.cycleRepeat() }; contentDescription = "Repeat" }
        info.addView(shuffle, frame(56.u, 56.u, t = g.bottomT.u))
        info.addView(repeat, frame(56.u, 56.u, l = 62.u, t = g.bottomT.u))
        upNextText = ctx.text("", 17f, Fonts.bold)
        upNext = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(18.u, 0, 12.u, 0)
            background = rounded(0x0FFFFFFF, 28)
            addView(ctx.label("Next", C.TEXT3, 13f), linear(WRAP, WRAP, r = 12.u))
            addView(upNextText, linear(0, WRAP, 1f))
            addView(ctx.icon(R.drawable.ic_chevron_right, 20, C.TEXT2), linear(20.u, 20.u))
            pressable(0.97f) { host.showQueue() }
        }
        info.addView(upNext, frame(MATCH, 56.u, l = 128.u, t = g.bottomT.u))

        // Glance mode's bottom line: what's coming next, readable at a glance.
        glanceNext = ctx.text("", 26f, Fonts.bold, C.alpha(C.TEXT, 0.62f)).apply { alpha = 0f }
        info.addView(glanceNext, frame(g.glanceW.u, WRAP, t = (g.bottomT + 8).u))

        badge = ctx.text("", 15f, Fonts.extraBold, C.BEE).apply {
            setPadding(14.u, 6.u, 14.u, 6.u)
            alpha = 0f
        }
        info.addView(badge, frame(WRAP, WRAP, Gravity.END, t = 4.u))

        if (g.panelW > 0) {
            nextPanel = NextPanel(ctx) { index -> onUserActivity(); PlayerHub.jumpTo(index) }
            surface.addView(nextPanel, frame(g.panelW.u, WRAP, l = g.panelX.u, t = g.colY.u))
        }

        // Nothing queued yet: a calm invitation instead of dead controls.
        empty = LinearLayout(ctx).apply {
            orientation = if (D.portrait) LinearLayout.VERTICAL else LinearLayout.HORIZONTAL
            gravity = if (D.portrait) Gravity.CENTER else Gravity.CENTER_VERTICAL
            setPadding(48.u, 0, 40.u, 0)
            val cassette = android.widget.ImageView(ctx).apply {
                setImageDrawable(com.teja.bumblebee.art.CassetteCover("Mixtape Vol. 1", "bumblebee"))
                roundCorners(30)
            }
            val side = minOf(420, (if (D.portrait) D.contentW - 96 else D.contentH - 160))
            addView(cassette, if (D.portrait) linear(side.u, side.u, b = 48.u) else linear(side.u, side.u, r = 56.u))
            addView(LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                addView(ctx.text("Nothing playing yet", 46f, Fonts.extraBold, lines = 2).apply { letterSpacing = -0.02f }, linear(MATCH, WRAP))
                addView(ctx.text("Pick a folder, or let Bee shuffle everything on your drives.", 20f, Fonts.semiBold, C.TEXT2, lines = 3), linear(MATCH, WRAP, t = 12.u))
                addView(LinearLayout(ctx).apply {
                    orientation = LinearLayout.HORIZONTAL
                    addView(com.teja.bumblebee.ui.design.PillButton(ctx, "Shuffle everything", R.drawable.ic_shuffle, 72, true, C.BEE).apply {
                        pressable { host.shuffleEverything() }
                    }, linear(WRAP, 72.u, r = 14.u))
                    addView(com.teja.bumblebee.ui.design.PillButton(ctx, "Browse", R.drawable.ic_folder, 72, false, C.BEE).apply {
                        pressable { host.showTab(Tab.HOME) }
                    }, linear(WRAP, 72.u))
                }, linear(WRAP, WRAP, t = 30.u))
            }, if (D.portrait) linear(MATCH, WRAP) else linear(0, WRAP, 1f))
            visibility = View.GONE
        }
        surface.addView(empty, frame(MATCH, MATCH))

        wireGestures()
        relayout()
        return surface
    }

    /** Positions the cover and controls column (and mirrors them for left-hand drive). */
    fun relayout() {
        if (!::carousel.isInitialized) return
        val g = Geo.compute()
        geo = g
        val c = carousel.layoutParams as FrameLayout.LayoutParams
        val i = info.layoutParams as FrameLayout.LayoutParams
        c.leftMargin = g.carouselX.u; c.topMargin = g.carouselY.u
        i.leftMargin = g.colX.u; i.topMargin = g.colY.u
        // Glance text grows into the space the rail frees, on whichever side the rail is.
        val extra = g.glanceW - g.colW
        (glanceText.layoutParams as FrameLayout.LayoutParams).leftMargin = if (g.glanceGrowsLeft) -extra.u else 0
        (glanceNext.layoutParams as FrameLayout.LayoutParams).leftMargin = if (g.glanceGrowsLeft) -extra.u else 0
        nextPanel?.let { (it.layoutParams as FrameLayout.LayoutParams).apply { leftMargin = g.panelX.u; topMargin = g.colY.u }; it.requestLayout() }
        carousel.requestLayout()
        info.requestLayout()
    }

    /**
     * Now Playing geometry in design px, computed from the window so any screen works: 16:9 gets the
     * reference layout; taller/narrower screens centre it; ultrawide adds an "up next" panel; short
     * landscape phones tighten the column; portrait is a full-screen player (cover on top, controls
     * below, a bar with collapse / source / queue above).
     */
    private data class Geo(
        val cover: Int, val coverInsetX: Int, val coverInsetY: Int,
        val carouselX: Int, val carouselY: Int, val carouselW: Int, val carouselH: Int,
        val colX: Int, val colY: Int, val colW: Int, val colH: Int,
        val textT: Int, val textH: Int, val seekT: Int, val playT: Int, val bottomT: Int,
        val titleSize: Float, val artistSize: Float, val showAlbum: Boolean,
        val play: Int, val btn: Int,
        val glanceW: Int, val glanceTitle: Float, val glanceGrowsLeft: Boolean,
        val glanceScale: Float, val topBar: Boolean, val panelX: Int = 0, val panelW: Int = 0,
    ) {
        companion object {
            /** Below this the column switches to smaller transport and type. */
            private const val MIN_COL_H = 560
            const val TOP_BAR = 96

            fun compute(): Geo {
                // Portrait: the player covers the bottom bar, so it gets the whole height.
                return if (D.portrait) portrait(D.contentW, D.designH) else landscape(D.contentW, D.contentH)
            }

            private fun landscape(cw: Int, ch: Int): Geo {
                val gap = 64
                var s = minOf(ch - 120, cw - 404 - 40 - gap - 32)
                s = s.coerceIn(minOf(320, ch - 40), 760)
                val colW = (cw - 40 - s - gap - 32).coerceIn(360, 560)
                var group = 40 + s + gap + colW + 32
                // Ultrawide: spend spare width on an "up next" panel instead of empty margins.
                val spare = cw - group
                val panelW = if (spare >= 420) minOf(520, spare - 56) else 0
                if (panelW > 0) group += 56 + panelW
                val ox = maxOf(0, (cw - group) / 2)
                val marginY = (ch - s) / 2
                var colTop = marginY
                var colBottom = minOf(ch - 20, marginY + s + 40)
                if (colBottom - colTop < MIN_COL_H) {
                    // Short screens (phones on their side): the column takes the full height.
                    colTop = minOf(colTop, 20)
                    colBottom = ch - 16
                }
                val right = Prefs.driverRight
                val pw = if (panelW > 0) 56 + panelW else 0
                val carouselX = if (right) ox else ox + 32 + colW + pw + gap - 40
                val colX = if (right) ox + 40 + s + gap else ox + 32
                val panelX = if (panelW > 0) colX + colW + 56 else 0
                val glanceExtra = if (ox >= D.RAIL / 2 || panelW > 0) 0 else D.RAIL - 8
                val g = column(
                    cover = s, insetX = 40, insetY = marginY,
                    carouselX = carouselX, carouselY = 0, carouselW = s + 80, carouselH = ch,
                    colX = colX, colY = colTop, colW = colW, colH = colBottom - colTop,
                    glanceW = colW + glanceExtra, growsLeft = !right && glanceExtra > 0,
                    glanceScale = ((ch - 60f) / s).coerceIn(1f, 1.12f), labelH = 90, topBar = false,
                )
                return if (panelW > 0) g.copy(panelX = panelX, panelW = panelW) else g
            }

            private fun portrait(cw: Int, ch: Int): Geo {
                val margin = if (cw < 600) 32 else 48
                val colW = minOf(cw - 2 * margin, 640)
                val maxS = minOf(cw - 2 * margin, 760)
                val coverY = TOP_BAR + 8
                // The cover takes what the controls leave; tight phones get the compact controls.
                fun coverFor(need: Int) = ch - coverY - 36 - need - 24
                var s = minOf(maxS, coverFor(480))
                if (s < 320) s = minOf(maxS, coverFor(400))
                s = s.coerceAtLeast(180)
                val colY = coverY + s + 36
                val carouselW = s + 80
                return column(
                    cover = s, insetX = 40, insetY = coverY,
                    carouselX = (cw - carouselW) / 2, carouselY = 0, carouselW = carouselW, carouselH = coverY + s + 48,
                    colX = (cw - colW) / 2, colY = colY, colW = colW, colH = ch - colY - 24,
                    glanceW = colW, growsLeft = false, glanceScale = 1.06f, labelH = 0, topBar = true,
                )
            }

            private fun column(
                cover: Int, insetX: Int, insetY: Int,
                carouselX: Int, carouselY: Int, carouselW: Int, carouselH: Int,
                colX: Int, colY: Int, colW: Int, colH: Int,
                glanceW: Int, growsLeft: Boolean, glanceScale: Float, labelH: Int, topBar: Boolean,
            ): Geo {
                // Bottom-up: shared row, transport, seek; the title block takes what's left at the top.
                val compact = colH < MIN_COL_H - (if (topBar) 80 else 0)
                // In the hand, controls are a thumb away: smaller buttons, more room for the title.
                val smallControls = compact || (topBar && D.handheld)
                val play = if (smallControls) 120 else 144
                val btn = if (smallControls) 88 else 104
                val bottomT = colH - 56
                val playT = bottomT - (if (compact) 14 else 20) - play
                val seekT = playT - (if (compact) 80 else 90)
                val textT = if (compact && labelH > 0) 72 else labelH
                val textH = (seekT - 10 - textT).coerceAtLeast(96)
                val title = when {
                    textH >= 220 -> 56f
                    textH >= 160 -> 48f
                    else -> 38f
                }
                return Geo(
                    cover, insetX, insetY, carouselX, carouselY, carouselW, carouselH,
                    colX, colY, colW, colH, textT, textH, seekT, playT, bottomT,
                    title, if (textH >= 160) 28f else 23f, textH >= 170, play, btn,
                    glanceW, if (glanceW >= 500) 88f else 72f, growsLeft, glanceScale, topBar,
                )
            }
        }
    }

    fun coverView(): View = carousel.currentCoverView()

    // ------------------------------------------------------------------ gestures

    private fun wireGestures() {
        carousel.listener = object : CoverCarousel.Listener {
            override fun onProgress(progress: Float) {
                trackText.progress(progress, carousel.prevTrack, carousel.nextTrack)
                glanceText.progress(progress, carousel.prevTrack, carousel.nextTrack)
                val dir = if (progress < 0) 1 else if (progress > 0) -1 else 0
                if (dir != 0 && dir != lockedDir) {
                    lockedDir = dir
                    lockedAccent = (if (dir > 0) carousel.nextTrack else carousel.prevTrack)?.let { ArtLoader.cachedAccent(it) }
                }
                val target = if (dir == 0) null else lockedAccent
                val t = abs(progress)
                host.backdrop.preview(target, t)
                val shown = if (target != null) accent.blend(target, t) else accent
                paint(shown)
            }

            override fun onUserSkip(dir: Int) {
                onUserActivity()
                if (dir > 0) PlayerHub.next() else PlayerHub.prevItem()
            }

            override fun onSettled(dir: Int) {
                trackText.settle(dir)
                glanceText.settle(dir)
                val locked = if (dir != 0 && dir == lockedDir) lockedAccent else null
                lockedDir = 0
                lockedAccent = null
                if (dir != 0) {
                    if (locked != null) host.applyAccent(locked, animate = false) else host.backdrop.preview(null, 0f)
                } else {
                    host.backdrop.preview(null, 0f)
                    paint(accent)
                }
                // State may have moved on during the animation.
                trackText.show(state.current)
                glanceText.show(state.current)
                applyOverrides()
            }
        }
        surface.onDragStart = { carousel.beginDrag(); onUserActivity() }
        surface.onDrag = { dx -> carousel.dragTo(dx) }
        surface.onDragEnd = { v -> carousel.release(v) }
        surface.onSwipeUp = { host.showQueue() }
        // Portrait (phones): the player is a sheet; pull it down to put it away.
        surface.onPullDown = { dy -> if (D.portrait && !glance) pull(dy) }
        surface.onPullEnd = { dy, vy ->
            when {
                glance -> exitGlance()
                !D.portrait -> Unit
                dy > 160f.u || vy > 1100f.u -> host.collapsePlayer()
                else -> surface.animate().translationY(0f).alpha(1f).setDuration(Motion.ms(240)).setInterpolator(Motion.emphasized).start()
            }
        }
        surface.onEmptyTap = { if (glance) exitGlance() }
        surface.onAnyTouch = { onUserActivity() }

        seek.onScrubState = { on ->
            bubble.animate().alpha(if (on) 1f else 0f).setDuration(120).start()
        }
        seek.onScrub = { f ->
            bubble.text = Fmt.time((f * state.durationMs).toLong())
            bubble.post { bubble.translationX = (seek.width * f - bubble.width / 2f).coerceIn(0f, (seek.width - bubble.width).toFloat()) }
            elapsed.text = Fmt.time((f * state.durationMs).toLong())
        }
        seek.onSeek = { f -> PlayerHub.seekTo((f * state.durationMs).toLong()); onUserActivity() }
    }

    /** Follows a downward drag with resistance, dimming as it goes. */
    private fun pull(dy: Float) {
        val y = if (dy <= 0f) 0f else dy * 0.62f
        surface.translationY = y
        surface.alpha = 1f - (y / (surface.height * 0.9f)).coerceIn(0f, 0.5f)
    }

    // ------------------------------------------------------------------ lifecycle

    /**
     * Shows the current song immediately, with no deck animation: the song may have changed while
     * this page was hidden, and the page's own entrance is the only motion it should make.
     */
    private fun syncNow() {
        val s = PlayerHub.state.value
        lockedDir = 0
        lockedAccent = null
        host.backdrop.preview(null, 0f)
        if (s.current != null) {
            carousel.snap(s.prev, s.current, s.next)
            trackText.settle(0)
            glanceText.settle(0)
            trackText.show(s.current)
            glanceText.show(s.current)
        }
        render(s)
    }

    override fun onShow() {
        surface.alpha = 1f
        syncNow()
        launchVisible { PlayerHub.state.collect { render(it) } }
        launchVisible { BeeEvents.waiting.collect { waitingLabel = it; applyOverrides() } }
        launchVisible { Prefs.changes.collect { relayout() } }
        handler.post(progressTick)
        onUserActivity()
        // Starting the car lands in the driving view: only on the player's very first appearance
        // (the player may still be connecting, so check for playback when the timer fires).
        if (autoGlanceArmed) {
            autoGlanceArmed = false
            if (Prefs.glanceDelaySec > 0) handler.postDelayed({ if (state.playing) enterGlance() }, 3_500)
        }
        if (!Prefs.swipeHintShown) {
            handler.postDelayed({
                if (!Prefs.swipeHintShown && carousel.hasNext) {
                    Prefs.swipeHintShown = true
                    carousel.nudge()
                    host.toast("Swipe anywhere to change songs")
                }
            }, 1_400)
        }
    }

    override fun onHide() {
        handler.removeCallbacksAndMessages(null)
        if (glance) exitGlance()
    }

    // ------------------------------------------------------------------ render

    private fun render(s: PlayerHub.State) {
        val old = state
        state = s
        val nothing = s.current == null
        if (nothing != (empty.visibility == View.VISIBLE)) {
            empty.visibility = if (nothing) View.VISIBLE else View.GONE
            carousel.visibility = if (nothing) View.INVISIBLE else View.VISIBLE
            info.visibility = if (nothing) View.INVISIBLE else View.VISIBLE
            if (nothing) empty.enter(dy = 16f.u)
        }
        if (nothing) return
        carousel.bind(s.prev, s.current, s.next)
        if (!carousel.isMoving) {
            trackText.show(s.current)
            glanceText.show(s.current)
        }
        play.setPlaying(s.playing, animate = old.connected)
        shuffle.set(s.shuffle, accent.accent)
        repeat.set(s.repeat != Player.REPEAT_MODE_OFF, accent.accent, one = s.repeat == Player.REPEAT_MODE_ONE)
        upNextText.text = s.next?.let { "${it.title}  ·  ${it.artistLabel}" } ?: "End of queue"
        glanceNext.text = s.next?.let { "Next · ${it.title}" } ?: ""
        nextPanel?.bind(PlayerHub.upcoming(8), ((geo.colH - 60) / 84).coerceIn(3, 8))
        // During a cameo the chip carries Bee's line; don't let state updates overwrite it.
        if (System.currentTimeMillis() > cameoUntil) chipText.text = s.context.label.ifBlank { s.current?.parent?.substringAfterLast('/') ?: "" }
        if (s.current?.path != lastPath) {
            lastPath = s.current?.path
            s.current?.let { onNewTrack(it) }
        }
        prefetchNeighbours(s)
        if (!s.playing && glance) { handler.removeCallbacks(pulse); handler.postDelayed(pulse, 1_000) }
        applyOverrides()
    }

    /** Warms the colour cache so a swipe can tint toward the neighbour from the first frame. */
    private fun prefetchNeighbours(s: PlayerHub.State) {
        visibleScope.launch {
            s.prev?.let { ArtLoader.accent(it) }
            s.next?.let { ArtLoader.accent(it) }
        }
    }

    private fun onNewTrack(t: Track) {
        // Bee talks through songs: a once-a-day cameo for a few hand-picked titles.
        val line = EasterEggs.cameo(t)
        if (line != null && !glance) {
            cameoUntil = System.currentTimeMillis() + 5_000
            labelTop.text = "BEE SAYS"
            chipText.text = line
            flicker(labelTop); flicker(chip)
            handler.postDelayed({ cameoUntil = 0; render(state) }, 5_000)
        }
        visibleScope.launch {
            val plays = withContext(Dispatchers.IO) { Library.plays(t.path) }
            val milestone = EasterEggs.milestone(plays)
            if (milestone != null) showBadge(milestone)
        }
    }

    private fun applyOverrides() {
        if (!::trackText.isInitialized) return
        val waiting = waitingLabel
        carousel.setWaiting(waiting != null)
        if (waiting != null) {
            labelTop.text = "WAITING FOR THE DRIVE"
            val t = state.current
            trackText.override("Insert $waiting to continue", t?.let { "${Fmt.time(PlayerHub.positionMs)} into ${it.title}" })
            glanceText.override("Insert $waiting", null)
            trackText.alpha = 0.85f
        } else {
            if (System.currentTimeMillis() > cameoUntil) labelTop.text = "PLAYING FROM"
            trackText.override(null, null)
            glanceText.override(null, null)
            trackText.alpha = if (glance) 0f else 1f
        }
    }

    private fun flicker(v: View) {
        if (Motion.reduced) return
        v.alpha = 0.2f
        v.animate().alpha(1f).setDuration(60).withEndAction {
            v.animate().alpha(0.4f).setDuration(50).withEndAction { v.animate().alpha(1f).setDuration(90).start() }.start()
        }.start()
    }

    private fun showBadge(text: String) {
        badge.text = text
        badge.background = rounded(C.alpha(accent.accent, 0.16f), 16)
        badge.setTextColor(accent.accent)
        badge.animate().alpha(1f).setDuration(240).start()
        handler.postDelayed({ badge.animate().alpha(0f).setDuration(400).start() }, 6_000)
    }

    fun onAccent(a: Accent) {
        accent = a
        if (!::carousel.isInitialized) return
        if (!carousel.isMoving) paint(a)
        shuffle.set(state.shuffle, a.accent)
        repeat.set(state.repeat != Player.REPEAT_MODE_OFF, a.accent, one = state.repeat == Player.REPEAT_MODE_ONE)
    }

    private fun paint(a: Accent) {
        play.colorBg = a.accent
        play.colorGlyph = if (C.isLight(a.accent)) C.INK else C.TEXT
        seek.accent = a.accent
        trackText.accent = a.accent
        glanceText.accent = a.accent
        carousel.setGlow(a.accent)
    }

    private val progressTick = object : Runnable {
        override fun run() {
            tickProgress()
            handler.postDelayed(this, 250)
        }
    }

    private fun tickProgress() {
        val dur = state.durationMs
        val pos = PlayerHub.positionMs
        if (dur > 0) seek.fraction = pos.toFloat() / dur
        if (bubble.alpha == 0f) elapsed.text = Fmt.time(pos)
        remaining.text = if (dur <= 0) "" else if (showRemaining) "−" + Fmt.time(dur - pos) else Fmt.time(dur)
    }

    // ------------------------------------------------------------------ glance

    private val glanceRunnable = Runnable { enterGlance() }

    fun onUserActivity() {
        if (!::surface.isInitialized) return
        scheduleGlance()
    }

    private fun scheduleGlance() {
        handler.removeCallbacks(glanceRunnable)
        val delaySec = Prefs.glanceDelaySec
        if (delaySec > 0 && !glance) handler.postDelayed(glanceRunnable, delaySec * 1000L)
    }

    private fun enterGlance() {
        if (glance || !state.playing || host.sheetOpen || waitingLabel != null) { scheduleGlance(); return }
        glance = true
        val d = Motion.ms(400)
        listOfNotNull(labelTop, chip, seek, elapsed, remaining, shuffle, repeat, upNext, badge, nextPanel, topBar).forEach {
            it.animate().alpha(0f).setDuration(d).start()
            it.isEnabled = false
        }
        glanceNext.animate().alpha(1f).setStartDelay(160).setDuration(d).start()
        trackText.animate().alpha(0f).translationY(-8f.u).setDuration(d).start()
        glanceText.translationY = 10f.u
        glanceText.animate().alpha(1f).translationY(0f).setDuration(d).setStartDelay(80).setInterpolator(Motion.emphasized).start()
        listOf(prev, next).forEach { it.animate().scaleX(1.12f).scaleY(1.12f).setDuration(d).setInterpolator(Motion.emphasized).start() }
        play.animate().scaleX(1.12f).scaleY(1.12f).setDuration(d).setInterpolator(Motion.emphasized).start()
        carousel.animate().scaleX(geo.glanceScale).scaleY(geo.glanceScale).setDuration(d).setInterpolator(Motion.emphasized).start()
        host.setGlance(true)
    }

    fun exitGlance() {
        if (!glance) return
        glance = false
        val d = Motion.ms(250)
        listOfNotNull(labelTop, chip, seek, elapsed, remaining, shuffle, repeat, upNext, nextPanel, topBar).forEach {
            it.animate().alpha(1f).setDuration(d).start()
            it.isEnabled = true
        }
        trackText.animate().alpha(1f).translationY(0f).setDuration(d).setStartDelay(40).start()
        glanceNext.animate().alpha(0f).setStartDelay(0).setDuration(d / 2).start()
        glanceText.animate().alpha(0f).setDuration(d * 2 / 3).setStartDelay(0).start()
        listOf(prev, next, play).forEach { it.animate().scaleX(1f).scaleY(1f).setDuration(d).setInterpolator(Motion.emphasized).start() }
        carousel.animate().scaleX(1f).scaleY(1f).setDuration(d).setInterpolator(Motion.emphasized).start()
        host.setGlance(false)
        scheduleGlance()
    }

    /** In glance mode a paused player pulses its play button every 4 s so the state reads at a glance. */
    private val pulse = object : Runnable {
        override fun run() {
            if (!glance || state.playing) return
            if (!Motion.reduced) {
                play.animate().scaleX(1.22f).scaleY(1.22f).setDuration(220).setInterpolator(Motion.emphasized).withEndAction {
                    play.animate().scaleX(1.12f).scaleY(1.12f).setDuration(380).start()
                }.start()
            }
            handler.postDelayed(this, 4_000)
        }
    }
}
