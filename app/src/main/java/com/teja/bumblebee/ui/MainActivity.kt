package com.teja.bumblebee.ui

import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.drawable.Drawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
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
import com.teja.bumblebee.ui.design.linear
import com.teja.bumblebee.ui.design.roundCorners
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
import java.io.File

class MainActivity : ComponentActivity() {

    lateinit var backdrop: AmbientBackdrop
        private set
    private lateinit var shell: LinearLayout
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
        val first = if (hasTrack) Tab.NOW else Tab.HOME
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

        // Landscape glance text grows into the rail's slot, so the shell must not clip it there;
        // in portrait nothing overflows and clipping keeps scrolled content off the bottom bar.
        val overflow = !D.portrait
        shell = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; clipChildren = !overflow }
        content = FrameLayout(this).apply { clipChildren = !overflow }
        screenHost = FrameLayout(this).apply { clipChildren = !overflow }
        content.addView(screenHost, frame(MATCH, MATCH))
        mini = MiniPlayer(this) { showTab(Tab.NOW, fromMini = true) }.apply {
            onPrev = { PlayerHub.prev() }
            onNext = { PlayerHub.next() }
            onToggle = { PlayerHub.toggle() }
            translationY = 104f.u
            alpha = 0f
            visibility = View.INVISIBLE
        }
        content.addView(mini, frame(MATCH, 104.u, Gravity.BOTTOM))
        rail = Rail(this, horizontal = D.portrait, onSelect = { showTab(it) }, onExit = { exitToLauncher() })
        arrangeShell()
        root.addView(shell, frame(MATCH, MATCH))

        overlay = FrameLayout(this)
        root.addView(overlay, frame(MATCH, MATCH))
        toaster = Toaster(overlay)

        glanceClock = GlanceClock(this)
        glanceClock.alpha = 0f
        root.addView(glanceClock, frame(180.u, 40.u, Gravity.TOP or (if (Prefs.driverRight) Gravity.END else Gravity.START), l = 34.u, r = 34.u, t = 24.u))
        glanceLineTrack = View(this).apply { setBackgroundColor(0x1AFFFFFF); alpha = 0f }
        root.addView(glanceLineTrack, frame(MATCH, 5.u, Gravity.BOTTOM))
        glanceLine = View(this).apply { setBackgroundColor(C.BEE); pivotX = 0f; alpha = 0f; scaleX = 0f }
        root.addView(glanceLine, frame(MATCH, 5.u, Gravity.BOTTOM))
        setContentView(root)
    }

    private fun arrangeShell() {
        shell.removeAllViews()
        when {
            // Portrait panels: navigation becomes a bottom bar under the content.
            D.portrait -> {
                shell.orientation = LinearLayout.VERTICAL
                shell.addView(content, linear(MATCH, 0, 1f))
                shell.addView(rail, linear(MATCH, D.BAR.u))
            }
            Prefs.driverRight -> {
                shell.orientation = LinearLayout.HORIZONTAL
                shell.addView(content, linear(0, MATCH, 1f))
                shell.addView(rail, linear(D.RAIL.u, MATCH))
            }
            else -> {
                shell.orientation = LinearLayout.HORIZONTAL
                shell.addView(rail, linear(D.RAIL.u, MATCH))
                shell.addView(content, linear(0, MATCH, 1f))
            }
        }
    }

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

    fun showTab(tab: Tab, animate: Boolean = true, fromMini: Boolean = false) {
        if (glance) nowPlaying.exitGlance()
        val previous = stacks[currentTab]?.lastOrNull()
        if (tab == currentTab && previous != null && stacks.getValue(tab).size > 1 && tab != Tab.NOW) {
            // Tapping the active tab again pops to its root.
            val stack = stacks.getValue(tab)
            val top = stack.removeLast()
            while (stack.size > 1) stack.removeLast().also { it.dispatchDestroy(); screenHost.removeView(it.view) }
            transition(top, stack.last(), Kind.POP)
            top.dispatchDestroy()
            screenHost.removeView(top.view)
            return
        }
        if (tab == currentTab && previous != null) return
        currentTab = tab
        val next = stackFor(tab).last()
        val kind = when {
            !animate || previous == null -> Kind.NONE
            fromMini || (tab == Tab.NOW && mini.translationY == 0f) -> Kind.FLY_UP
            previous === nowPlaying && next.showsMiniPlayer -> Kind.FLY_DOWN
            else -> Kind.FADE
        }
        transition(previous, next, kind)
        rail.select(tab, animate)
    }

    fun push(screen: Screen, shared: ImageView? = null) {
        val stack = stackFor(screen.tab)
        val from = current
        if (currentTab != screen.tab) {
            currentTab = screen.tab
            rail.select(screen.tab, true)
        }
        stack.addLast(screen)
        transition(from, screen, Kind.PUSH)
        if (shared != null) screen.view.post { screen.heroArt?.let { flyShared(shared, it) } }
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
        val stack = stackFor(Tab.HOME)
        val top = if (currentTab == Tab.HOME) stack.last() else null
        val deeper = top is FolderScreen && path.startsWith(top.path + "/")
        if (!deeper) {
            // Drop old folder screens (but never the one currently on screen; transition removes it).
            while (stack.size > 1) stack.removeLast().also { if (it !== current) { it.dispatchDestroy(); screenHost.removeView(it.view) } }
        }
        val screen = FolderScreen(this, File(path), highlight, shared?.drawable?.constantState?.newDrawable())
        push(screen, shared)
    }

    fun openDetail(screen: DetailScreen, shared: ImageView? = null) = push(screen, shared)

    /** Card artwork flies into the opened page's hero image. */
    private fun flyShared(from: ImageView, to: ImageView) {
        if (Motion.reduced || from.width == 0 || to.width == 0 || !from.isAttachedToWindow) return
        val d = from.drawable?.constantState?.newDrawable()?.mutate() ?: return
        val a = IntArray(2).also { from.getLocationInWindow(it) }
        val b = IntArray(2).also { to.getLocationInWindow(it) }
        val fly = ImageView(this).apply {
            setImageDrawable(d)
            scaleType = ImageView.ScaleType.CENTER_CROP
            roundCorners(30)
            pivotX = 0f
            pivotY = 0f
        }
        overlay.addView(fly, frame(to.width, to.height))
        val s = from.width.toFloat() / to.width
        fly.translationX = a[0].toFloat()
        fly.translationY = a[1].toFloat()
        fly.scaleX = s
        fly.scaleY = s
        to.alpha = 0f
        fly.animate().translationX(b[0].toFloat()).translationY(b[1].toFloat()).scaleX(1f).scaleY(1f)
            .setDuration(360).setInterpolator(Motion.emphasized).withLayer()
            .withEndAction {
                to.alpha = 1f
                fly.animate().alpha(0f).setDuration(120).withEndAction { overlay.removeView(fly) }.start()
            }.start()
    }

    /** Opens Now Playing with the cover flying from [from] (e.g. the Home card). */
    fun openNowPlayingFrom(from: ImageView) {
        if (currentTab == Tab.NOW) return
        val previous = current
        currentTab = Tab.NOW
        val next = stackFor(Tab.NOW).last()
        transition(previous, next, Kind.FADE)
        rail.select(Tab.NOW, true)
        next.view.post { flyCover(from, nowPlaying.coverView(), up = true) }
    }

    private enum class Kind { NONE, FADE, PUSH, POP, FLY_UP, FLY_DOWN }

    private fun transition(from: Screen?, to: Screen, kind: Kind, after: (() -> Unit)? = null) {
        val toView = to.view
        if (toView.parent == null) screenHost.addView(toView, frame(MATCH, MATCH))
        toView.visibility = View.VISIBLE
        toView.animate().cancel()
        from?.view?.animate()?.cancel()
        from?.dispatchHide()
        to.dispatchShow()
        backdrop.setIntensity(to.backdropIntensity, kind != Kind.NONE)
        updateMini(to, kind != Kind.NONE)

        val out = from?.view
        val reduce = Motion.reduced
        fun finishOut() {
            if (out != null && out !== toView) {
                out.visibility = View.GONE
                out.alpha = 1f; out.translationX = 0f; out.translationY = 0f; out.scaleX = 1f; out.scaleY = 1f
            }
            after?.invoke()
        }
        if (kind == Kind.NONE || out == null || reduce) {
            toView.alpha = 0f
            toView.translationX = 0f; toView.translationY = 0f; toView.scaleX = 1f; toView.scaleY = 1f
            toView.animate().alpha(1f).setDuration(if (kind == Kind.NONE) 0 else 150).start()
            finishOut()
            return
        }
        when (kind) {
            Kind.PUSH, Kind.POP -> {
                val d = if (kind == Kind.PUSH) 1 else -1
                toView.alpha = 0f
                toView.translationX = d * 48f.u
                toView.scaleX = 1f; toView.scaleY = 1f
                toView.animate().alpha(1f).translationX(0f).setDuration(260).setStartDelay(40).setInterpolator(Motion.emphasized).withLayer().start()
                out.animate().alpha(0f).translationX(-d * 48f.u).setDuration(200).setStartDelay(0).setInterpolator(Motion.exit).withLayer().withEndAction { finishOut() }.start()
            }
            Kind.FADE -> {
                toView.alpha = 0f
                toView.scaleX = 1.02f; toView.scaleY = 1.02f
                toView.translationX = 0f; toView.translationY = 0f
                toView.animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(220).setStartDelay(70).setInterpolator(Motion.emphasized).withLayer().start()
                out.animate().alpha(0f).scaleX(0.98f).scaleY(0.98f).setDuration(110).setStartDelay(0).setInterpolator(Motion.exit).withLayer().withEndAction { finishOut() }.start()
            }
            Kind.FLY_UP -> {
                toView.alpha = 0f
                toView.translationY = 40f.u
                toView.scaleX = 1f; toView.scaleY = 1f
                toView.animate().alpha(1f).translationY(0f).setDuration(320).setStartDelay(20).setInterpolator(Motion.emphasized).withLayer().start()
                out.animate().alpha(0f).setDuration(140).setStartDelay(0).withLayer().withEndAction { finishOut() }.start()
                toView.post { flyCover(mini.cover, nowPlaying.coverView(), up = true) }
            }
            Kind.FLY_DOWN -> {
                toView.alpha = 0f
                toView.animate().alpha(1f).setDuration(240).setStartDelay(90).setInterpolator(Motion.emphasized).withLayer().start()
                out.animate().alpha(0f).translationY(40f.u).setDuration(220).setStartDelay(0).setInterpolator(Motion.exit).withLayer().withEndAction { finishOut() }.start()
                flyCover(nowPlaying.coverView(), mini.cover, up = false)
            }
            Kind.NONE -> Unit
        }
    }

    private fun updateMini(to: Screen, animate: Boolean) {
        val show = to.showsMiniPlayer && PlayerHub.state.value.current != null
        if (show == (mini.visibility == View.VISIBLE && mini.translationY == 0f)) return
        // Slide + fade, then go invisible: in portrait the bottom bar sits right under the mini-player.
        if (show) mini.visibility = View.VISIBLE
        val a = mini.animate().translationY(if (show) 0f else 104f.u).alpha(if (show) 1f else 0f)
            .setDuration(if (animate) Motion.ms(300) else 0).setInterpolator(Motion.emphasized)
        if (!show) a.withEndAction { if (mini.translationY != 0f) mini.visibility = View.INVISIBLE }
        a.start()
    }

    /** Shared-element style cover flight between the mini-player and Now Playing. */
    private fun flyCover(from: View, to: View, up: Boolean) {
        if (Motion.reduced || from.width == 0 || to.width == 0) return
        val drawable: Drawable = (if (up) (from as ImageView).drawable else (to as ImageView).drawable)?.constantState?.newDrawable()?.mutate()
            ?: (from as? ImageView)?.drawable ?: return
        val a = IntArray(2).also { from.getLocationInWindow(it) }
        val b = IntArray(2).also { to.getLocationInWindow(it) }
        val big = if (up) to else from
        val small = if (up) from else to
        val fly = ImageView(this).apply {
            setImageDrawable(drawable)
            scaleType = ImageView.ScaleType.CENTER_CROP
            roundCorners(30)
            pivotX = 0f
            pivotY = 0f
        }
        val bigLoc = if (up) b else a
        val smallLoc = if (up) a else b
        overlay.addView(fly, frame(big.width, big.height))
        val s = small.width.toFloat() / big.width
        val startX = (if (up) smallLoc else bigLoc)[0].toFloat()
        val startY = (if (up) smallLoc else bigLoc)[1].toFloat()
        val endX = (if (up) bigLoc else smallLoc)[0].toFloat()
        val endY = (if (up) bigLoc else smallLoc)[1].toFloat()
        fly.translationX = startX
        fly.translationY = startY
        fly.scaleX = if (up) s else 1f
        fly.scaleY = if (up) s else 1f
        to.alpha = 0f
        from.alpha = 0f
        fly.animate().translationX(endX).translationY(endY).scaleX(if (up) 1f else s).scaleY(if (up) 1f else s)
            .setDuration(340).setInterpolator(Motion.emphasized).withLayer()
            .withEndAction {
                to.alpha = 1f
                from.alpha = 1f
                overlay.removeView(fly)
            }.start()
    }

    private fun goBack() {
        when {
            queueSheet != null -> queueSheet?.dismiss()
            glance -> nowPlaying.exitGlance()
            current.onBack() -> Unit
            pop() -> Unit
            currentTab != Tab.HOME && currentTab != Tab.NOW -> showTab(Tab.HOME)
            currentTab == Tab.HOME && PlayerHub.state.value.current != null -> showTab(Tab.NOW)
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
                    if (t != null) refreshAccent(t)
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
            toast(if (shuffle) "Shuffling ${dir.name}" else "Playing ${dir.name}")
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

    fun hasStorageAccess(): Boolean = when {
        Build.VERSION.SDK_INT >= 30 -> Environment.isExternalStorageManager()
        else -> checkSelfPermission(android.Manifest.permission.READ_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED
    }

    private fun showOnboarding() {
        Onboarding(this, overlay, onGranted = { startLibrary() })
    }

    fun requestStorage() {
        if (Build.VERSION.SDK_INT >= 30) {
            val specific = Intent(android.provider.Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, Uri.parse("package:$packageName"))
            runCatching { startActivity(specific) }
                .recoverCatching { startActivity(Intent(android.provider.Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)) }
                .onFailure { toast("Grant via adb: appops set $packageName MANAGE_EXTERNAL_STORAGE allow") }
        } else {
            requestPermissions(arrayOf(android.Manifest.permission.READ_EXTERNAL_STORAGE, android.Manifest.permission.WRITE_EXTERNAL_STORAGE), 7)
        }
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
