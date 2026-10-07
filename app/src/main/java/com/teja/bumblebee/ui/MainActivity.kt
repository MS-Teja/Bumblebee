package com.teja.bumblebee.ui

import android.animation.ValueAnimator
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.lifecycle.lifecycleScope
import com.teja.bumblebee.R
import com.teja.bumblebee.art.ArtLoader
import com.teja.bumblebee.data.Library
import com.teja.bumblebee.data.PlayContext
import com.teja.bumblebee.data.Prefs
import com.teja.bumblebee.data.Track
import com.teja.bumblebee.eggs.EasterEggs
import com.teja.bumblebee.index.Indexer
import com.teja.bumblebee.playback.BeeEvents
import com.teja.bumblebee.playback.PlayerHub
import com.teja.bumblebee.storage.FolderRepo
import com.teja.bumblebee.storage.VolumeEvent
import com.teja.bumblebee.storage.Volumes
import com.teja.bumblebee.ui.browse.FolderScreen
import com.teja.bumblebee.ui.design.Accent
import com.teja.bumblebee.ui.design.AmbientBackdrop
import com.teja.bumblebee.ui.design.C
import com.teja.bumblebee.ui.design.D
import com.teja.bumblebee.ui.design.Fonts
import com.teja.bumblebee.ui.design.MATCH
import com.teja.bumblebee.ui.design.Motion
import com.teja.bumblebee.ui.design.frame
import com.teja.bumblebee.ui.design.text
import com.teja.bumblebee.ui.design.u
import com.teja.bumblebee.ui.home.HomeScreen
import com.teja.bumblebee.ui.library.DetailScreen
import com.teja.bumblebee.ui.library.LibraryScreen
import com.teja.bumblebee.ui.nowplaying.NowPlayingScreen
import com.teja.bumblebee.ui.search.SearchScreen
import com.teja.bumblebee.ui.settings.SettingsScreen
import com.teja.bumblebee.ui.shell.ActionSheet
import com.teja.bumblebee.ui.shell.MiniPlayer
import com.teja.bumblebee.ui.shell.Rail
import com.teja.bumblebee.ui.shell.Screen
import com.teja.bumblebee.ui.shell.SheetAction
import com.teja.bumblebee.ui.shell.Tab
import com.teja.bumblebee.ui.shell.Toaster
import com.teja.bumblebee.ui.sheets.Onboarding
import com.teja.bumblebee.ui.sheets.QueueSheet
import com.teja.bumblebee.ui.sheets.UsbCard
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File

class MainActivity : ComponentActivity() {

    lateinit var backdrop: AmbientBackdrop
        private set
    private lateinit var shell: FrameLayout
    private lateinit var content: FrameLayout
    private lateinit var screenHost: FrameLayout
    lateinit var overlay: FrameLayout
        private set
    private lateinit var rail: Rail
    private lateinit var mini: MiniPlayer
    private lateinit var toaster: Toaster
    private lateinit var glanceClock: View
    private lateinit var glanceLine: View
    private lateinit var glanceLineTrack: View

    val nowPlaying by lazy { NowPlayingScreen(this) }
    private val stacks = HashMap<Tab, ArrayDeque<Screen>>()
    private var currentTab: Tab = Tab.NOW
    private var started = false
    var sheetOpen = false
        private set
    private var queueSheet: QueueSheet? = null
    private var lastTrackPath: String? = null
    private var lastIndex = -1
    var accent: Accent = Accent.BEE
        private set
    private val _accentFlow = kotlinx.coroutines.flow.MutableStateFlow(Accent.BEE)
    /** Current album colours; screens collect this to tint rows, heroes and pills. */
    val accentFlow: kotlinx.coroutines.flow.StateFlow<Accent> = _accentFlow
    private var glance = false
    /** The browse tab the player collapses back to. */
    private var lastBrowseTab: Tab = Tab.HOME
    private var flight: Flight? = null
    /** A page is loading before it opens; further taps wait. */
    private var navigating = false

    private val current: Screen get() = stacks.getValue(currentTab).last()

    // ------------------------------------------------------------------ lifecycle

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        D.init(this as android.app.Activity, Prefs.immersive)
        buildShell()
        Immersive.apply(this, Prefs.immersive)
        PlayerHub.connect(this)
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() = goBack()
        })

        val hasTrack = QueueStore_hasSnapshot()
        // The car opens straight into the player; phones open to Home with the song in its card.
        val first = if (hasTrack && !D.handheld) Tab.NOW else Tab.HOME
        showTab(first, animate = false)
        observe()
        if (hasStorageAccess()) startLibrary() else showOnboarding()
        handleOpenIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleOpenIntent(intent)
    }

    override fun onResume() {
        super.onResume()
        if (!hasStorageAccess()) return
        if (!started) startLibrary() else Indexer.scanAll()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) Immersive.apply(this, Prefs.immersive)
    }

    override fun onDestroy() {
        stacks.values.flatten().forEach { it.dispatchDestroy() }
        super.onDestroy()
    }

    private fun QueueStore_hasSnapshot() = Prefs.raw().getString("snap_path", null) != null

    // ------------------------------------------------------------------ layout

    private fun buildShell() {
        val root = FrameLayout(this).apply { setBackgroundColor(C.BG) }
        backdrop = AmbientBackdrop(this)
        root.addView(backdrop, frame(MATCH, MATCH))

        // Nothing clips: landscape glance text grows into the rail's slot, and the portrait bottom
        // bar is an opaque overlay that lists scroll under.
        shell = FrameLayout(this).apply { clipChildren = false; clipToPadding = false }
        content = FrameLayout(this).apply { clipChildren = false }
        screenHost = FrameLayout(this).apply { clipChildren = false }
        content.addView(screenHost, frame(MATCH, MATCH))
        mini = MiniPlayer(this, compact = D.narrow) { showTab(Tab.NOW, fromMini = true) }.apply {
            onPrev = { PlayerHub.prev() }
            onNext = { PlayerHub.next() }
            onToggle = { PlayerHub.toggle() }
            alpha = 0f
            visibility = View.INVISIBLE
        }
        content.addView(mini)
        rail = Rail(
            this, horizontal = D.portrait,
            // Phones already show the time and have a home gesture.
            showClock = Prefs.immersive || !D.handheld, showExit = !D.handheld,
            onSelect = { showTab(it) }, onExit = { exitToLauncher() },
        )
        shell.addView(content)
        shell.addView(rail)
        arrangeShell()
        mini.translationY = miniHiddenY()
        root.addView(shell, frame(MATCH, MATCH))

        overlay = FrameLayout(this)
        root.addView(overlay, frame(MATCH, MATCH))
        toaster = Toaster(overlay)

        glanceClock = GlanceClock(this)
        glanceClock.alpha = 0f
        root.addView(glanceClock, frame(180.u, 40.u, Gravity.TOP or (if (Prefs.driverRight) Gravity.END else Gravity.START), l = 34.u + D.insetLeft, r = 34.u + D.insetRight, t = 24.u + D.insetTop))
        glanceLineTrack = View(this).apply { setBackgroundColor(0x1AFFFFFF); alpha = 0f }
        root.addView(glanceLineTrack, frame(MATCH, 5.u, Gravity.BOTTOM, b = D.insetBottom))
        glanceLine = View(this).apply { setBackgroundColor(C.BEE); pivotX = 0f; alpha = 0f; scaleX = 0f }
        root.addView(glanceLine, frame(MATCH, 5.u, Gravity.BOTTOM, b = D.insetBottom))
        setContentView(root)
    }

    /** Places content, rail / bottom bar and mini-player for the orientation and driver side. */
    private fun arrangeShell() {
        shell.setPadding(D.insetLeft, D.insetTop, D.insetRight, if (D.portrait) 0 else D.insetBottom)
        when {
            // Portrait: the bar overlays the bottom (and runs under the gesture/nav bar).
            D.portrait -> {
                content.layoutParams = frame(MATCH, MATCH)
                rail.layoutParams = frame(MATCH, D.BAR.u + D.insetBottom, Gravity.BOTTOM)
                rail.setPadding(0, 0, 0, D.insetBottom)
            }
            Prefs.driverRight -> {
                content.layoutParams = frame(MATCH, MATCH, r = D.RAIL.u)
                rail.layoutParams = frame(D.RAIL.u, MATCH, Gravity.END)
            }
            else -> {
                content.layoutParams = frame(MATCH, MATCH, l = D.RAIL.u)
                rail.layoutParams = frame(D.RAIL.u, MATCH, Gravity.START)
            }
        }
        mini.layoutParams = frame(MATCH, D.MINI.u, Gravity.BOTTOM, b = if (D.portrait) D.BAR.u + D.insetBottom else 0)
    }

    private fun miniHiddenY() = (D.MINI.u + (if (D.portrait) D.BAR.u + D.insetBottom else 0)).toFloat()

    /** Rebuilds after the driver side changes. */
    fun relayoutForDriverSide() {
        arrangeShell()
        (glanceClock.layoutParams as FrameLayout.LayoutParams).gravity = Gravity.TOP or (if (Prefs.driverRight) Gravity.END else Gravity.START)
        glanceClock.requestLayout()
        nowPlaying.relayout()
    }

    // ------------------------------------------------------------------ navigation

    private fun stackFor(tab: Tab): ArrayDeque<Screen> = stacks.getOrPut(tab) {
        ArrayDeque<Screen>().apply {
            add(
                when (tab) {
                    Tab.NOW -> nowPlaying
                    Tab.HOME -> HomeScreen(this@MainActivity)
                    Tab.LIBRARY -> LibraryScreen(this@MainActivity)
                    Tab.SEARCH -> SearchScreen(this@MainActivity)
                    Tab.SETTINGS -> SettingsScreen(this@MainActivity)
                },
            )
        }
    }

    /**
     * Switches tabs. Plain tab switches use one calm fade-through; only expanding the player (from the
     * mini-player or Home's card) and collapsing it again fly the cover between the two.
     */
    fun showTab(tab: Tab, animate: Boolean = true, fromMini: Boolean = false, collapse: Boolean = false) {
        if (navigating) return
        if (glance) nowPlaying.exitGlance()
        val previous = stacks[currentTab]?.lastOrNull()
        if (tab == currentTab && previous != null && stacks.getValue(tab).size > 1 && tab != Tab.NOW) {
            // Tapping the active tab again pops to its root.
            val stack = stacks.getValue(tab)
            val top = stack.removeLast()
            while (stack.size > 1) stack.removeLast().also { it.dispatchDestroy(); screenHost.removeView(it.view) }
            transition(top, stack.last(), Kind.POP) {
                top.dispatchDestroy()
                screenHost.removeView(top.view)
            }
            return
        }
        if (tab == currentTab && previous != null) return
        val next = stackFor(tab).last()
        rail.select(tab, animate)
        val go = {
            currentTab = tab
            if (tab != Tab.NOW) lastBrowseTab = tab
            val kind = when {
                !animate || previous == null -> Kind.NONE
                tab == Tab.NOW && fromMini -> Kind.FLY_UP
                previous === nowPlaying && collapse -> Kind.FLY_DOWN
                else -> Kind.FADE
            }
            transition(previous, next, kind)
        }
        // A tab's first visit waits a beat for its data, so it fades in complete.
        if (animate && previous != null) whenReady(next, go) else { next.prepared = true; go() }
    }

    /** Runs [block] once [screen] has its first content (or after a short cap, whichever is first). */
    private fun whenReady(screen: Screen, block: () -> Unit) {
        if (screen.prepared) { block(); return }
        if (navigating) return
        navigating = true
        lifecycleScope.launch {
            withTimeoutOrNull(220) { screen.prepare() }
            screen.prepared = true
            navigating = false
            if (!isFinishing && !isDestroyed) block()
        }
    }

    /** Back from the player: return to wherever you were browsing. */
    fun collapsePlayer() {
        if (currentTab == Tab.NOW) showTab(lastBrowseTab, collapse = true)
    }

    /**
     * Opens a page. Its content is loaded first (briefly, with the pressed card still springing),
     * so the page slides in complete instead of sliding in empty and then filling up.
     */
    fun push(screen: Screen, shared: ImageView? = null, beforeShow: (() -> Unit)? = null) {
        whenReady(screen) {
            beforeShow?.invoke()
            val stack = stackFor(screen.tab)
            val from = current
            if (currentTab != screen.tab) {
                currentTab = screen.tab
                if (screen.tab != Tab.NOW) lastBrowseTab = screen.tab
                rail.select(screen.tab, true)
            }
            stack.addLast(screen)
            transition(from, screen, Kind.PUSH)
            if (shared != null) screen.view.post { screen.heroArt?.let { flyArt(shared, it) } }
        }
    }

    fun pop(): Boolean {
        val stack = stacks.getValue(currentTab)
        if (stack.size <= 1) return false
        val top = stack.removeLast()
        transition(top, stack.last(), Kind.POP) {
            top.dispatchDestroy()
            screenHost.removeView(top.view)
        }
        return true
    }

    /**
     * Opens a folder. Navigating deeper from the current folder keeps the trail (so Back goes up one
     * level); jumping from elsewhere starts a fresh trail under Home.
     */
    fun openFolder(path: String, highlight: String? = null, shared: ImageView? = null) {
        val screen = FolderScreen(this, File(path), highlight, shared?.drawable?.constantState?.newDrawable())
        push(screen, shared) {
            val stack = stackFor(Tab.HOME)
            val top = if (currentTab == Tab.HOME) stack.last() else null
            val deeper = top is FolderScreen && path.startsWith(top.path + "/")
            if (!deeper) {
                // Drop old folder screens (but never the one on screen; the transition removes it).
                while (stack.size > 1) stack.removeLast().also { if (it !== current) { it.dispatchDestroy(); screenHost.removeView(it.view) } }
            }
        }
    }

    fun openDetail(screen: DetailScreen, shared: ImageView? = null) = push(screen, shared)

    /** Opens Now Playing with the cover flying from [from] (e.g. the Home card). */
    fun openNowPlayingFrom(from: ImageView) {
        if (currentTab == Tab.NOW) return
        val previous = current
        currentTab = Tab.NOW
        transition(previous, stackFor(Tab.NOW).last(), Kind.FLY_UP, flyFrom = from)
        rail.select(Tab.NOW, true)
    }

    private enum class Kind { NONE, FADE, PUSH, POP, FLY_UP, FLY_DOWN }

    private fun transition(from: Screen?, to: Screen, kind: Kind, flyFrom: View? = null, after: (() -> Unit)? = null) {
        flight?.finish()
        val toView = to.view
        if (toView.parent == null) screenHost.addView(toView, frame(MATCH, MATCH))
        toView.visibility = View.VISIBLE
        toView.animate().cancel()
        from?.view?.animate()?.cancel()
        from?.dispatchHide()
        to.dispatchShow()
        val animate = kind != Kind.NONE
        backdrop.setIntensity(to.backdropIntensity, animate)
        updateMini(to, animate)
        updateBar(to, animate)

        val out = from?.view
        fun finishOut() {
            if (out != null && out !== toView) {
                out.visibility = View.GONE
                out.alpha = 1f; out.translationX = 0f; out.translationY = 0f; out.scaleX = 1f; out.scaleY = 1f
            }
            after?.invoke()
        }
        toView.translationX = 0f; toView.translationY = 0f; toView.scaleX = 1f; toView.scaleY = 1f
        if (kind == Kind.NONE || out == null || Motion.reduced) {
            toView.alpha = 0f
            toView.animate().alpha(1f).setStartDelay(0).setDuration(if (kind == Kind.NONE) 0 else 150).start()
            finishOut()
            return
        }
        // The player slides further on phones, where it behaves like a sheet over the app.
        val lift = (if (D.portrait) 140f else 40f).u
        when (kind) {
            Kind.PUSH, Kind.POP -> {
                // Shared axis: the page moves the way you navigate, the old one makes room.
                val d = if (kind == Kind.PUSH) 1 else -1
                toView.alpha = 0f
                toView.translationX = d * 56f.u
                toView.animate().alpha(1f).translationX(0f).setDuration(300).setStartDelay(30).setInterpolator(Motion.emphasized).withLayer().start()
                out.animate().alpha(0f).translationX(-d * 40f.u).setDuration(180).setStartDelay(0).setInterpolator(Motion.exit).withLayer().withEndAction { finishOut() }.start()
            }
            Kind.FADE -> {
                // Fade through: the old tab dips out quickly, the new one settles in.
                toView.alpha = 0f
                toView.scaleX = 1.015f; toView.scaleY = 1.015f
                toView.animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(240).setStartDelay(60).setInterpolator(Motion.emphasized).withLayer().start()
                out.animate().alpha(0f).setDuration(100).setStartDelay(0).setInterpolator(Motion.exit).withLayer().withEndAction { finishOut() }.start()
            }
            Kind.FLY_UP -> {
                toView.alpha = 0f
                toView.translationY = lift
                toView.animate().alpha(1f).translationY(0f).setDuration(380).setStartDelay(0).setInterpolator(Motion.emphasized).withLayer().start()
                out.animate().alpha(0f).setDuration(160).setStartDelay(0).withLayer().withEndAction { finishOut() }.start()
                val src = flyFrom ?: mini.cover
                toView.post { flyArt(src, nowPlaying.coverView()) }
            }
            Kind.FLY_DOWN -> {
                toView.alpha = 0f
                toView.animate().alpha(1f).setDuration(260).setStartDelay(60).setInterpolator(Motion.emphasized).withLayer().start()
                // Continues from wherever a pull-down left the player.
                val endY = maxOf(lift, out.translationY + 80f.u)
                out.animate().alpha(0f).translationY(endY).setDuration(300).setStartDelay(0).setInterpolator(Motion.exit).withLayer().withEndAction { finishOut() }.start()
                val target = to.playerArt ?: mini.cover.takeIf { to.showsMiniPlayer && PlayerHub.state.value.current != null }
                if (target != null) toView.post { flyArt(nowPlaying.coverView(), target) }
            }
            Kind.NONE -> Unit
        }
    }

    private fun updateMini(to: Screen, animate: Boolean) {
        val show = to.showsMiniPlayer && PlayerHub.state.value.current != null
        if (show == (mini.visibility == View.VISIBLE && mini.translationY == 0f)) return
        // Slide + fade, then go invisible: in portrait the bottom bar sits right under the mini-player.
        if (show) mini.visibility = View.VISIBLE
        val a = mini.animate().translationY(if (show) 0f else miniHiddenY()).alpha(if (show) 1f else 0f)
            .setDuration(if (animate) Motion.ms(300) else 0).setStartDelay(0).setInterpolator(Motion.emphasized)
        if (!show) a.withEndAction { if (mini.translationY != 0f) mini.visibility = View.INVISIBLE }
        a.start()
    }

    /** Portrait: the player is full screen, so the bottom bar steps aside while it's open. */
    private fun updateBar(to: Screen, animate: Boolean) {
        if (!D.portrait) return
        val hide = to === nowPlaying
        val y = if (hide) (D.BAR.u + D.insetBottom).toFloat() else 0f
        if (rail.translationY == y) return
        rail.animate().translationY(y).setDuration(if (animate) Motion.ms(if (hide) 260 else 320) else 0).setStartDelay(0)
            .setInterpolator(if (hide) Motion.exit else Motion.emphasized).start()
    }

    /** A cover in flight between two places; finishing it restores both ends. */
    private inner class Flight(val anim: ValueAnimator) {
        fun finish() = anim.cancel()
    }

    /**
     * Shared-element flight: a copy of the artwork travels from [from] to [to] while both originals
     * hide, morphing size and corner radius, then the destination takes over in place. It lands on the
     * destination's resting place (ignoring its page's enter offset), so it always lands exactly.
     */
    private fun flyArt(from: View, to: View) {
        flight?.finish()
        if (Motion.reduced || from.width == 0 || to.width == 0 || !from.isAttachedToWindow || !to.isAttachedToWindow) return
        // The bigger end has the sharper picture; it's used for the whole flight.
        val big = if (to.width >= from.width) to else from
        val d = snapshot(big as? ImageView ?: return) ?: return
        // The source is measured where it is now (it may already be leaving); the destination where
        // it will come to rest (its page may still be sliding in).
        val a = IntArray(2).also { from.getLocationInWindow(it) }
        val b = restLocation(to)
        val r0 = cornerOf(from)
        val r1 = cornerOf(to)
        val w = maxOf(from.width, to.width)
        val h = maxOf(from.height, to.height)
        var radius = r0
        var sx = from.width.toFloat() / w
        val fly = ImageView(this).apply {
            setImageDrawable(d)
            scaleType = ImageView.ScaleType.CENTER_CROP
            pivotX = 0f
            pivotY = 0f
            outlineProvider = object : android.view.ViewOutlineProvider() {
                override fun getOutline(view: View, outline: android.graphics.Outline) =
                    outline.setRoundRect(0, 0, view.width, view.height, radius / sx)
            }
            clipToOutline = true
        }
        overlay.addView(fly, frame(w, h))
        from.visibility = View.INVISIBLE
        to.visibility = View.INVISIBLE
        fun lerp(p: Float, q: Float, t: Float) = p + (q - p) * t
        val anim = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 380
            interpolator = Motion.emphasized
            addUpdateListener {
                val t = it.animatedValue as Float
                sx = lerp(from.width.toFloat(), to.width.toFloat(), t) / w
                fly.scaleX = sx
                fly.scaleY = lerp(from.height.toFloat(), to.height.toFloat(), t) / h
                fly.translationX = lerp(a[0].toFloat(), b[0].toFloat(), t)
                fly.translationY = lerp(a[1].toFloat(), b[1].toFloat(), t)
                radius = lerp(r0, r1, t)
                fly.invalidateOutline()
            }
            addListener(object : android.animation.AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: android.animation.Animator) {
                    from.visibility = View.VISIBLE
                    to.visibility = View.VISIBLE
                    overlay.removeView(fly)
                    if (flight?.anim === animation) flight = null
                }
            })
        }
        flight = Flight(anim)
        anim.start()
    }

    /** A copy of what [v] shows: its bitmap when it has one, else the view drawn into a bitmap. */
    private fun snapshot(v: ImageView): android.graphics.drawable.Drawable? {
        val d = v.drawable ?: return null
        if (d is android.graphics.drawable.BitmapDrawable) return d.constantState?.newDrawable(resources)
        // Generated covers and mid-crossfade art: draw the view once (corners come from the flight's clip).
        if (v.width <= 0 || v.height <= 0) return null
        val bmp = android.graphics.Bitmap.createBitmap(v.width, v.height, android.graphics.Bitmap.Config.ARGB_8888)
        v.draw(android.graphics.Canvas(bmp))
        return android.graphics.drawable.BitmapDrawable(resources, bmp)
    }

    /** Window position of [v] with every translation on it and its parents undone (its resting place). */
    private fun restLocation(v: View): IntArray {
        val loc = IntArray(2).also { v.getLocationInWindow(it) }
        var p: View? = v
        while (p != null) {
            loc[0] -= p.translationX.toInt()
            loc[1] -= p.translationY.toInt()
            p = p.parent as? View
        }
        return loc
    }

    private fun cornerOf(v: View): Float {
        val o = android.graphics.Outline()
        v.outlineProvider?.getOutline(v, o)
        return o.radius.coerceAtLeast(0f)
    }

    private fun goBack() {
        when {
            queueSheet != null -> queueSheet?.dismiss()
            glance -> nowPlaying.exitGlance()
            current.onBack() -> Unit
            pop() -> Unit
            currentTab == Tab.NOW -> collapsePlayer()
            currentTab != Tab.HOME -> showTab(Tab.HOME)
            else -> moveTaskToBack(true)
        }
    }

    private fun exitToLauncher() {
        val home = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { startActivity(home) }.onFailure { moveTaskToBack(true) }
    }

    // ------------------------------------------------------------------ state

    private fun observe() {
        lifecycleScope.launch {
            PlayerHub.state.collect { s ->
                val t = s.current
                val dir = when {
                    t == null || lastTrackPath == null || t.path == lastTrackPath -> 0
                    s.index > lastIndex -> 1
                    else -> -1
                }
                mini.bind(t, s.playing, animate = true, direction = dir)
                if (t?.path != lastTrackPath) {
                    lastTrackPath = t?.path
                    if (t != null) {
                        refreshAccent(t)
                        // Warm the big cover so opening the player never waits on a decode.
                        launch { ArtLoader.art(t, ArtLoader.Size.LARGE) }
                    }
                }
                lastIndex = s.index
                updateMini(current, animate = true)
            }
        }
        lifecycleScope.launch {
            while (true) {
                val s = PlayerHub.state.value
                if (s.durationMs > 0) {
                    val f = PlayerHub.positionMs.toFloat() / s.durationMs
                    mini.setProgress(f)
                    if (glance) glanceLine.scaleX = f.coerceIn(0f, 1f)
                }
                kotlinx.coroutines.delay(if (glance || currentTab == Tab.NOW) 500 else 1000)
            }
        }
        lifecycleScope.launch { BeeEvents.toasts.collect { toast(it) } }
        lifecycleScope.launch {
            BeeEvents.cheatCode.collect {
                toast("Roll out!", C.BEE)
                shuffleEverything("Roll out! — everything")
            }
        }
        lifecycleScope.launch { Prefs.changes.collect { applyAccent(accent, animate = true) } }
        lifecycleScope.launch {
            Volumes.events.collect { e ->
                when (e) {
                    is VolumeEvent.Mounted -> if (e.volume.removable) onUsbMounted(e.volume)
                    is VolumeEvent.Removed -> if (e.volume.removable) toast("${e.volume.label} removed")
                }
            }
        }
    }

    private fun refreshAccent(t: Track) {
        ArtLoader.cachedAccent(t)?.let { applyAccent(it, animate = !nowPlaying.carouselMoving) }
        lifecycleScope.launch {
            val a = ArtLoader.accent(t)
            if (PlayerHub.state.value.current?.path == t.path) applyAccent(a, animate = !nowPlaying.carouselMoving)
        }
    }

    /** Applies album colours everywhere (Camaro mode pins Bee yellow). */
    fun applyAccent(a: Accent, animate: Boolean) {
        val effective = if (Prefs.camaro) Accent.BEE.copy(deep = a.deep, glowA = a.glowA, glowB = a.glowB) else a
        accent = effective
        _accentFlow.value = effective
        backdrop.setAccent(effective, animate)
        rail.setAccent(effective.accent)
        mini.setAccent(effective.accent, Prefs.camaro)
        glanceLine.setBackgroundColor(effective.accent)
        nowPlaying.onAccent(effective)
    }

    // ------------------------------------------------------------------ glance chrome

    fun setGlance(on: Boolean) {
        glance = on
        val d = Motion.ms(if (on) 400 else 250)
        rail.animate().alpha(if (on) 0f else 1f).setDuration(d).start()
        rail.isEnabled = !on
        setTreeEnabled(rail, !on)
        glanceClock.animate().alpha(if (on) 1f else 0f).setDuration(d).start()
        glanceLine.animate().alpha(if (on) 1f else 0f).setDuration(d).start()
        glanceLineTrack.animate().alpha(if (on) 1f else 0f).setDuration(d).start()
    }

    private fun setTreeEnabled(v: View, enabled: Boolean) {
        v.isEnabled = enabled
        if (v is android.view.ViewGroup) for (i in 0 until v.childCount) setTreeEnabled(v.getChildAt(i), enabled)
        if (!enabled) v.isClickable.let { } // clicks are ignored while disabled
    }

    // ------------------------------------------------------------------ overlays

    fun toast(text: String, color: Int = C.TEXT) = toaster.show(text, color)

    fun showQueue() {
        if (queueSheet != null || PlayerHub.state.value.current == null) return
        if (glance) nowPlaying.exitGlance()
        sheetOpen = true
        queueSheet = QueueSheet(this, overlay) {
            queueSheet = null
            sheetOpen = false
        }
    }

    fun showTrackActions(track: Track, folderPath: String?) {
        sheetOpen = true
        val actions = buildList {
            add(SheetAction(R.drawable.ic_play_next, "Play next") { PlayerHub.playNext(track); toast("Plays next: ${track.title}") })
            add(SheetAction(R.drawable.ic_add, "Add to queue") { PlayerHub.addToQueue(track); toast("Added to queue") })
            if (folderPath != null) add(SheetAction(R.drawable.ic_shuffle, "Shuffle this folder") { playFolder(File(folderPath), shuffle = true) })
            track.album?.let { album -> add(SheetAction(R.drawable.ic_album, "Go to album") { openDetail(DetailScreen.album(this@MainActivity, album, track.albumArtist)) }) }
            track.artist?.let { artist -> add(SheetAction(R.drawable.ic_artist, "Go to artist") { openDetail(DetailScreen.artist(this@MainActivity, artist)) }) }
            add(SheetAction(R.drawable.ic_folder, "Show in folder") { openFolder(track.parent, track.path) })
        }
        ActionSheet(overlay, track.title, track.artistLabel, actions) { sheetOpen = false }
    }

    fun showFolderActions(path: String, name: String) {
        sheetOpen = true
        ActionSheet(
            overlay, name, null,
            listOf(
                SheetAction(R.drawable.ic_play, "Play") { playFolder(File(path), shuffle = false) },
                SheetAction(R.drawable.ic_shuffle, "Shuffle") { playFolder(File(path), shuffle = true) },
                SheetAction(R.drawable.ic_eye_off, "Hide this folder") {
                    Prefs.excluded = Prefs.excluded + path
                    toast("Hidden. Unhide it in Settings")
                    Library.changed()
                },
            ),
        ) { sheetOpen = false }
    }

    fun shuffleEverything(label: String = "Everything, shuffled") {
        lifecycleScope.launch {
            val all = withContext(Dispatchers.IO) { Library.allTracks() }
            if (all.isEmpty()) toast("No songs found yet") else PlayerHub.play(all, 0, PlayContext(label, null), shuffle = true)
        }
    }

    fun playFolder(dir: File, shuffle: Boolean, recursive: Boolean = true) {
        lifecycleScope.launch {
            val tracks = FolderRepo.tracksFor(dir, recursive)
            if (tracks.isEmpty()) { toast("Nothing to play here"); return@launch }
            PlayerHub.play(tracks, 0, PlayContext(folderLabel(dir), dir.path), shuffle)
            val v = Volumes.forPath(dir.path)
            val name = if (v != null && v.path == dir.path) v.label else FolderRepo.contextualName(dir.path)
            toast(if (shuffle) "Shuffling $name" else "Playing $name")
        }
    }

    fun folderLabel(dir: File): String {
        val v = Volumes.forPath(dir.path)
        return if (v != null && v.path == dir.path) v.label else (if (v != null && v.removable) "USB · " else "") + dir.name
    }

    private fun onUsbMounted(v: com.teja.bumblebee.storage.Volume) {
        Indexer.scan(v)
        val waitingOn = BeeEvents.waiting.value
        val resume = if (waitingOn != null && PlayerHub.state.value.current?.path?.startsWith(v.path) == true) PlayerHub.state.value.current else null
        when {
            Prefs.onUsb == "play" && resume == null -> playFolder(v.root, shuffle = false)
            Prefs.onUsb == "none" && resume == null -> Unit
            else -> UsbCard(this, overlay, v, resume,
                onPlay = { if (resume != null) PlayerHub.play() else playFolder(v.root, shuffle = false) },
                onOpen = { openFolder(v.path) })
        }
    }

    // ------------------------------------------------------------------ permissions & library

    /** Full file access, or the standard "music and audio" permission (enough for songs anywhere). */
    fun hasStorageAccess(): Boolean = when {
        Build.VERSION.SDK_INT >= 30 && Environment.isExternalStorageManager() -> true
        else -> checkSelfPermission(mediaPermission) == PackageManager.PERMISSION_GRANTED
    }

    private val mediaPermission: String
        get() = if (Build.VERSION.SDK_INT >= 33) android.Manifest.permission.READ_MEDIA_AUDIO else android.Manifest.permission.READ_EXTERNAL_STORAGE

    private fun showOnboarding() {
        Onboarding(this, overlay, onGranted = { startLibrary() })
    }

    /**
     * Asks with the system's own dialog first. If that was refused for good, opens this app's
     * settings page so it can be switched on there.
     */
    fun requestStorage() {
        val asked = Prefs.raw().getBoolean("asked_media", false)
        if (asked && !shouldShowRequestPermissionRationale(mediaPermission)) {
            runCatching { startActivity(Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName"))) }
                .onFailure { requestAllFiles() }
            return
        }
        Prefs.raw().edit().putBoolean("asked_media", true).apply()
        requestPermissions(arrayOf(mediaPermission), 7)
    }

    /** Android 11+: all-files access, which also shows folder.jpg covers and .m3u playlists. */
    fun requestAllFiles() {
        if (Build.VERSION.SDK_INT < 30) return
        val specific = Intent(android.provider.Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, Uri.parse("package:$packageName"))
        runCatching { startActivity(specific) }
            .recoverCatching { startActivity(Intent(android.provider.Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)) }
            .onFailure { toast("Grant via adb: appops set $packageName MANAGE_EXTERNAL_STORAGE allow") }
    }

    @Deprecated("Deprecated in Java")
    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (hasStorageAccess()) startLibrary()
    }

    private fun startLibrary() {
        if (started) return
        started = true
        Volumes.start(this)
        Volumes.refresh()
        Indexer.scanAll()
        Library.changed()
        // Freshly granted storage access can take a moment to apply to a running process.
        lifecycleScope.launch {
            repeat(4) {
                kotlinx.coroutines.delay(2_500)
                if (Volumes.all.value.any { it.id !in Prefs.scannedVolumes }) { Volumes.refresh(); Indexer.scanAll() }
            }
        }
    }

    /** "Open with" from a file manager: play that file's folder starting from it. */
    private fun handleOpenIntent(intent: Intent?) {
        // Debug-only: preview the USB card on emulators that can't mount a virtual drive.
        if (com.teja.bumblebee.BuildConfig.DEBUG && intent?.getBooleanExtra("demo_usb", false) == true) {
            Volumes.all.value.firstOrNull()?.let { v ->
                UsbCard(this, overlay, v.copy(label = "SanDisk"), PlayerHub.state.value.current, onPlay = { PlayerHub.play() }, onOpen = { openFolder(v.path) })
            }
            return
        }
        val uri = intent?.data ?: return
        if (intent.action != Intent.ACTION_VIEW) return
        val path = when (uri.scheme) {
            "file" -> uri.path
            else -> runCatching {
                contentResolver.query(uri, arrayOf("_data"), null, null, null)?.use { if (it.moveToFirst()) it.getString(0) else null }
            }.getOrNull()
        } ?: run { toast("Can't open that file"); return }
        val file = File(path)
        lifecycleScope.launch {
            val listing = FolderRepo.list(file.parentFile ?: return@launch)
            val start = listing.tracks.indexOfFirst { it.path == file.path }.coerceAtLeast(0)
            PlayerHub.play(listing.tracks.ifEmpty { listOf(Track(file.path, file.nameWithoutExtension)) }, start, PlayContext(folderLabel(listing.dir), listing.dir.path))
            showTab(Tab.NOW)
        }
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.action == KeyEvent.ACTION_DOWN) nowPlaying.onUserActivity()
        return super.dispatchKeyEvent(event)
    }

    fun easterEggGreeting(): String = EasterEggs.greeting()

    private class GlanceClock(ctx: android.content.Context) : FrameLayout(ctx) {
        private val label = ctx.text("", 20f, Fonts.semiBold, C.alpha(C.TEXT, 0.55f)).apply {
            gravity = Gravity.END or Gravity.CENTER_VERTICAL
            fontFeatureSettings = "tnum"
        }
        private val tick = object : Runnable {
            override fun run() {
                label.text = android.text.format.DateFormat.format(if (android.text.format.DateFormat.is24HourFormat(context)) "H:mm" else "h:mm", java.util.Calendar.getInstance())
                postDelayed(this, 15_000)
            }
        }
        init {
            addView(label, frame(MATCH, MATCH))
            post(tick)
        }
    }
}
