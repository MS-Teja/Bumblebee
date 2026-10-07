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
        surface = SwipeSurface(ctx).apply { clipChildren = false }
        carousel = CoverCarousel(ctx).apply { pivotX = 300f.u; pivotY = 360f.u }
        surface.addView(carousel, frame(600.u, 720.u))

        info = FrameLayout(ctx).apply { clipChildren = false }
        surface.addView(info, frame(492.u, 720.u))

        labelTop = ctx.label("Playing from", C.TEXT3, 15f)
        info.addView(labelTop, frame(MATCH, WRAP, t = 72.u))
        chipText = ctx.text("", 22f, Fonts.bold, C.alpha(C.TEXT, 0.82f))
        chip = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(chipText, linear(WRAP, WRAP))
            addView(ctx.icon(R.drawable.ic_chevron_right, 22, C.alpha(C.TEXT, 0.82f)), linear(22.u, 22.u, l = 6.u))
            pressable(0.96f) { state.context.path?.let { host.openFolder(it, state.current?.path) } }
        }
        info.addView(chip, frame(WRAP, 48.u, t = 90.u))

        trackText = TrackText(ctx, 52f, 26f, showAlbum = true)
        info.addView(trackText, frame(MATCH, 214.u, t = 160.u))
        glanceText = TrackText(ctx, 66f, 30f, showAlbum = false).apply { alpha = 0f }
        info.addView(glanceText, frame(624.u, 260.u, t = 168.u))

        seek = DragSeekBar(ctx)
        info.addView(seek, frame(MATCH, 56.u, t = 380.u))
        elapsed = ctx.text("0:00", 17f, Fonts.semiBold, C.alpha(C.TEXT, 0.62f)).apply { fontFeatureSettings = "tnum" }
        remaining = ctx.text("", 17f, Fonts.semiBold, C.alpha(C.TEXT, 0.62f)).apply {
            fontFeatureSettings = "tnum"
            gravity = Gravity.END
            setPadding(16.u, 6.u, 0, 6.u)
            pressable(0.96f) { showRemaining = !showRemaining; tickProgress() }
        }
        info.addView(elapsed, frame(WRAP, WRAP, l = 11.u, t = 436.u))
        info.addView(remaining, frame(WRAP, WRAP, Gravity.END, r = 11.u, t = 430.u))
        bubble = ctx.text("", 20f, Fonts.extraBold, C.INK).apply {
            background = rounded(C.TEXT, 12)
            setPadding(14.u, 6.u, 14.u, 6.u)
            fontFeatureSettings = "tnum"
            alpha = 0f
        }
        info.addView(bubble, frame(WRAP, WRAP, t = 336.u))

        // Transport: fixed centres (prev/next ±128 from play) so glance mode only scales them in place.
        val rowTop = 470.u
        val cx = 246.u
        shuffle = ToggleIcon(ctx, R.drawable.ic_shuffle, 56, 26).apply { pressable { PlayerHub.toggleShuffle() }; contentDescription = "Shuffle" }
        repeat = ToggleIcon(ctx, R.drawable.ic_repeat, 56, 26).apply { pressable { PlayerHub.cycleRepeat() }; contentDescription = "Repeat" }
        prev = CircleButton(ctx, R.drawable.ic_prev, 96, 40, 0x14FFFFFF, C.TEXT).apply { contentDescription = "Previous" }
        next = CircleButton(ctx, R.drawable.ic_next, 96, 40, 0x14FFFFFF, C.TEXT).apply { contentDescription = "Next" }
        play = PlayPauseView(ctx, 54).apply { contentDescription = "Play or pause" }
        prev.pressable(0.9f, onLongClick = { PlayerHub.seekBy(-10_000) }) { onUserActivity(); PlayerHub.prev() }
        next.pressable(0.9f, onLongClick = { PlayerHub.seekBy(10_000) }) { onUserActivity(); PlayerHub.next() }
        play.pressable(0.92f) { onUserActivity(); PlayerHub.toggle() }
        info.addView(shuffle, frame(56.u, 56.u, l = 0, t = rowTop + 47.u))
        info.addView(prev, frame(96.u, 96.u, l = cx - 128.u - 48.u, t = rowTop + 27.u))
        info.addView(play, frame(136.u, 136.u, l = cx - 68.u, t = rowTop + 7.u))
        info.addView(next, frame(96.u, 96.u, l = cx + 128.u - 48.u, t = rowTop + 27.u))
        info.addView(repeat, frame(56.u, 56.u, Gravity.END, t = rowTop + 47.u))

        upNextText = ctx.text("", 18f, Fonts.bold)
        upNext = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(18.u, 0, 14.u, 0)
            background = rounded(0x0FFFFFFF, 26)
            addView(ctx.label("Up next", C.TEXT3, 14f), linear(WRAP, WRAP, r = 14.u))
            addView(upNextText, linear(0, WRAP, 1f))
            addView(ctx.icon(R.drawable.ic_chevron_right, 20, C.TEXT2), linear(20.u, 20.u))
            pressable(0.97f) { host.showQueue() }
        }
        info.addView(upNext, frame(MATCH, 52.u, t = 640.u))

        badge = ctx.text("", 15f, Fonts.extraBold, C.BEE).apply {
            setPadding(14.u, 6.u, 14.u, 6.u)
            alpha = 0f
        }
        info.addView(badge, frame(WRAP, WRAP, Gravity.END, t = 74.u))

        // Nothing queued yet: a calm invitation instead of dead controls.
        empty = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(48.u, 0, 40.u, 0)
            val cassette = android.widget.ImageView(ctx).apply {
                setImageDrawable(com.teja.bumblebee.art.CassetteCover("Mixtape Vol. 1", "bumblebee"))
                roundCorners(30)
            }
            addView(cassette, linear(420.u, 420.u, r = 56.u))
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
            }, linear(0, WRAP, 1f))
            visibility = View.GONE
        }
        surface.addView(empty, frame(MATCH, MATCH))

        wireGestures()
        relayout()
        return surface
    }

    /** Positions the cover and info column for the driver side. */
    fun relayout() {
        if (!::carousel.isInitialized) return
        val right = Prefs.driverRight
        (carousel.layoutParams as FrameLayout.LayoutParams).leftMargin = if (right) 0 else 548.u
        (info.layoutParams as FrameLayout.LayoutParams).leftMargin = if (right) 620.u else 36.u
        carousel.requestLayout()
        info.requestLayout()
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

    // ------------------------------------------------------------------ lifecycle

    override fun onShow() {
        launchVisible { PlayerHub.state.collect { render(it) } }
        launchVisible { BeeEvents.waiting.collect { waitingLabel = it; applyOverrides() } }
        launchVisible { Prefs.changes.collect { relayout() } }
        handler.post(progressTick)
        onUserActivity()
        if (autoGlanceArmed && state.playing) {
            autoGlanceArmed = false
            handler.postDelayed({ if (state.playing) enterGlance() }, 3_500)
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
        chipText.text = s.context.label.ifBlank { s.current?.parent?.substringAfterLast('/') ?: "" }
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
        listOf(labelTop, chip, seek, elapsed, remaining, shuffle, repeat, upNext, badge).forEach {
            it.animate().alpha(0f).setDuration(d).start()
            it.isEnabled = false
        }
        trackText.animate().alpha(0f).translationY(-8f.u).setDuration(d).start()
        glanceText.translationY = 10f.u
        glanceText.animate().alpha(1f).translationY(0f).setDuration(d).setStartDelay(80).setInterpolator(Motion.emphasized).start()
        listOf(prev, next).forEach { it.animate().scaleX(1.08f).scaleY(1.08f).setDuration(d).setInterpolator(Motion.emphasized).start() }
        play.animate().scaleX(1.1f).scaleY(1.1f).setDuration(d).setInterpolator(Motion.emphasized).start()
        carousel.animate().scaleX(1.06f).scaleY(1.06f).setDuration(d).setInterpolator(Motion.emphasized).start()
        host.setGlance(true)
    }

    fun exitGlance() {
        if (!glance) return
        glance = false
        val d = Motion.ms(250)
        listOf(labelTop, chip, seek, elapsed, remaining, shuffle, repeat, upNext).forEach {
            it.animate().alpha(1f).setDuration(d).start()
            it.isEnabled = true
        }
        trackText.animate().alpha(1f).translationY(0f).setDuration(d).setStartDelay(40).start()
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
                play.animate().scaleX(1.2f).scaleY(1.2f).setDuration(220).setInterpolator(Motion.emphasized).withEndAction {
                    play.animate().scaleX(1.1f).scaleY(1.1f).setDuration(380).start()
                }.start()
            }
            handler.postDelayed(this, 4_000)
        }
    }
}
