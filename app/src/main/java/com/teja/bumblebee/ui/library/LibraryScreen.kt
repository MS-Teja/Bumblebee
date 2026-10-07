package com.teja.bumblebee.ui.library

import android.content.Context
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.dynamicanimation.animation.DynamicAnimation
import androidx.dynamicanimation.animation.SpringAnimation
import androidx.dynamicanimation.animation.SpringForce
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.teja.bumblebee.art.ArtLoader
import com.teja.bumblebee.data.Album
import com.teja.bumblebee.data.Artist
import com.teja.bumblebee.data.Library
import com.teja.bumblebee.data.PlayContext
import com.teja.bumblebee.data.Track
import com.teja.bumblebee.playback.PlayerHub
import com.teja.bumblebee.ui.MainActivity
import com.teja.bumblebee.ui.browse.AlphabetRail
import com.teja.bumblebee.ui.browse.HeroPane
import com.teja.bumblebee.ui.browse.TrackAdapter
import com.teja.bumblebee.ui.browse.TrackItem
import com.teja.bumblebee.ui.browse.emptyState
import com.teja.bumblebee.ui.design.C
import com.teja.bumblebee.ui.design.D
import android.widget.HorizontalScrollView
import com.teja.bumblebee.ui.design.Fonts
import com.teja.bumblebee.ui.design.MATCH
import com.teja.bumblebee.ui.design.WRAP
import com.teja.bumblebee.ui.design.circle
import com.teja.bumblebee.ui.design.frame
import com.teja.bumblebee.ui.design.linear
import com.teja.bumblebee.ui.design.ovalClip
import com.teja.bumblebee.ui.design.pressable
import com.teja.bumblebee.ui.design.rounded
import com.teja.bumblebee.ui.design.roundCorners
import com.teja.bumblebee.ui.design.text
import com.teja.bumblebee.ui.design.u
import com.teja.bumblebee.ui.shell.Screen
import com.teja.bumblebee.ui.shell.Tab
import com.teja.bumblebee.ui.shell.enter
import com.teja.bumblebee.util.Fmt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.drop
import com.teja.bumblebee.ui.design.Motion
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class LibraryScreen(host: MainActivity) : Screen(host) {
    override val tab = Tab.LIBRARY

    private enum class Page(val title: String) { ALBUMS("Albums"), ARTISTS("Artists"), SONGS("Songs"), RECENT("Recently added") }

    private lateinit var list: RecyclerView
    private lateinit var listHost: FrameLayout
    private lateinit var pill: View
    private val tabs = ArrayList<TextView>()
    private var page = Page.ALBUMS
    private var rail: AlphabetRail? = null
    private val pillX by lazy { SpringAnimation(pill, DynamicAnimation.TRANSLATION_X).apply { spring = SpringForce().setStiffness(560f).setDampingRatio(0.8f) } }

    /** Library version the current page was loaded at; refreshes are silent and keep the scroll spot. */
    private var loadedVersion = -1L
    private var loadJob: kotlinx.coroutines.Job? = null
    private val tabW get() = if (D.narrow) 150 else 190

    override fun build(): View {
        val root = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL; setPadding((if (D.narrow) 24 else 40).u, 28.u, 0, 0) }
        root.addView(ctx.text("Library", 32f, Fonts.extraBold).apply { letterSpacing = -0.02f }, linear(MATCH, WRAP))

        val seg = FrameLayout(ctx).apply { background = rounded(0x0FFFFFFF, 30) }
        pill = View(ctx).apply { background = rounded(C.alpha(C.BEE, 0.18f), 26) }
        seg.addView(pill, frame(tabW.u, 52.u, Gravity.CENTER_VERTICAL, l = 4.u))
        val row = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        Page.entries.forEach { p ->
            val tv = ctx.text(p.title, if (D.narrow) 16f else 18f, Fonts.extraBold, C.TEXT2).apply {
                gravity = Gravity.CENTER
                pressable(0.95f) { select(p) }
            }
            tabs += tv
            row.addView(tv, linear(tabW.u, 52.u))
        }
        seg.addView(row, frame(WRAP, MATCH, l = 4.u))
        root.addView(HorizontalScrollView(ctx).apply {
            isHorizontalScrollBarEnabled = false
            clipToPadding = false
            setPadding(0, 0, 24.u, 0)
            addView(seg, android.widget.FrameLayout.LayoutParams(4 * tabW.u + 8.u, 60.u))
        }, linear(MATCH, 60.u, t = 18.u))

        listHost = FrameLayout(ctx)
        list = RecyclerView(ctx).apply {
            clipToPadding = false
            setPadding(0, 18.u, (if (D.narrow) 24 else 36).u, D.bottomChrome(mini = true) + 26.u)
            itemAnimator = null
        }
        listHost.addView(list, frame(MATCH, MATCH))
        root.addView(listHost, linear(MATCH, 0, 1f))
        paintTabs(host.accent.accent)
        pill.translationX = (Page.entries.indexOf(page) * tabW).u.toFloat()
        return root
    }

    override suspend fun prepare() {
        if (loadedVersion < 0) data = fetch(page)
    }

    override fun onShow() {
        // First visit: fill straight away (the tab transition is the only motion). Later visits keep
        // what's there and only refresh, silently, if the library changed meanwhile.
        val ready = data
        when {
            list.adapter == null && ready != null -> show(ready, Mode.FIRST)
            list.adapter == null -> load(Mode.FIRST)
            Library.version.value != loadedVersion -> load(Mode.SILENT)
        }
        launchVisible { Library.version.drop(1).debounce(800).collect { load(Mode.SILENT) } }
        launchVisible { PlayerHub.state.collect { s -> (list.adapter as? TrackAdapter)?.setPlaying(s.current?.path, s.playing) } }
        launchVisible {
            host.accentFlow.collect { a ->
                (list.adapter as? TrackAdapter)?.accent = a.accent
                paintTabs(a.accent)
            }
        }
    }

    private fun paintTabs(accent: Int) {
        (pill.background as android.graphics.drawable.GradientDrawable).setColor(C.alpha(accent, 0.18f))
        tabs.forEachIndexed { i, tv -> tv.setTextColor(if (i == Page.entries.indexOf(page)) accent else C.TEXT2) }
    }

    private fun select(p: Page) {
        if (p == page && list.adapter != null) { list.smoothScrollToPosition(0); return }
        page = p
        pillX.animateToFinalPosition((Page.entries.indexOf(p) * tabW).u.toFloat())
        paintTabs(host.accent.accent)
        load(Mode.SWITCH)
    }

    private enum class Mode { FIRST, SWITCH, SILENT }

    /** Data for one page, read off the main thread. */
    private class PageData(val page: Page, val version: Long, val albums: List<Album> = emptyList(), val artists: List<Artist> = emptyList(), val songs: List<Track> = emptyList())

    private var data: PageData? = null

    private suspend fun fetch(p: Page): PageData = withContext(Dispatchers.IO) {
        val v = Library.version.value
        when (p) {
            Page.ALBUMS -> PageData(p, v, albums = Library.albums())
            Page.ARTISTS -> PageData(p, v, artists = Library.artists())
            Page.SONGS -> PageData(p, v, songs = Library.allTracks())
            Page.RECENT -> PageData(p, v, songs = Library.recentlyAdded(200))
        }
    }

    private fun load(mode: Mode) {
        loadJob?.cancel()
        val p = page
        loadJob = scope.launch {
            if (mode == Mode.SWITCH && !Motion.reduced) {
                // Segment change: the old page dips out while the new one loads, then fades up.
                list.animate().alpha(0f).translationY(0f).setDuration(90).setStartDelay(0).start()
            }
            val d = fetch(p)
            if (p != page) return@launch
            show(d, mode)
        }
    }

    private fun show(d: PageData, mode: Mode) {
        data = d
        loadedVersion = d.version
        // A silent refresh keeps the reader exactly where they were.
        val keep = if (mode == Mode.SILENT) list.layoutManager?.onSaveInstanceState() else null
        rail?.let { listHost.removeView(it); (it.bubble)?.let { b -> listHost.removeView(b) } }
        rail = null
        emptyView?.let { listHost.removeView(it) }
        emptyView = null
        when (d.page) {
            Page.ALBUMS -> {
                list.layoutManager = GridLayoutManager(ctx, ((D.contentW - 76) / 220).coerceIn(2, 8))
                list.adapter = AlbumAdapter(d.albums) { a, art -> host.openDetail(DetailScreen.album(host, a.name, a.sample.albumArtist, art.drawable), art) }
                if (d.albums.isEmpty()) showEmpty()
            }
            Page.ARTISTS -> {
                list.layoutManager = LinearLayoutManager(ctx)
                list.adapter = ArtistAdapter(d.artists) { a, art -> host.openDetail(DetailScreen.artist(host, a.name, art.drawable), art) }
                if (d.artists.size > 40) addRail { i -> d.artists.getOrNull(i)?.name }
                if (d.artists.isEmpty()) showEmpty()
            }
            Page.SONGS, Page.RECENT -> {
                val songs = d.songs
                val ctxLabel = if (d.page == Page.SONGS) "All songs" else "Recently added"
                val ad = TrackAdapter(
                    onPlay = { i -> PlayerHub.play(songs, i, PlayContext(ctxLabel, null)) },
                    onLong = { t -> host.showTrackActions(t, t.parent) },
                    onPlayNext = { t -> PlayerHub.playNext(t); host.toast("Plays next: ${t.title}") },
                )
                ad.accent = host.accent.accent
                ad.submit(songs.mapIndexed { i, t -> TrackItem(t, i + 1, showArt = true) })
                list.layoutManager = LinearLayoutManager(ctx)
                list.adapter = ad
                PlayerHub.state.value.let { ad.setPlaying(it.current?.path, it.playing) }
                if (d.page == Page.SONGS && songs.size > 40) addRail { i -> songs.getOrNull(i)?.title }
                if (songs.isEmpty()) showEmpty()
            }
        }
        if (keep != null) list.layoutManager?.onRestoreInstanceState(keep)
        when (mode) {
            Mode.SWITCH -> {
                list.scrollToPosition(0)
                list.alpha = 0f
                list.translationY = 14f.u
                list.animate().alpha(1f).translationY(0f).setDuration(Motion.ms(220)).setStartDelay(0).setInterpolator(Motion.emphasized).start()
            }
            Mode.FIRST -> reveal(list)
            Mode.SILENT -> Unit
        }
    }

    private var emptyView: View? = null

    private fun showEmpty() {
        val v = emptyState(ctx, "Nothing yet", "Songs appear here once Bee has read their tags.", null, null)
        listHost.addView(v, frame(MATCH, MATCH))
        emptyView = v
    }

    private fun addRail(key: (Int) -> String?) {
        val r = AlphabetRail(ctx, list, key)
        r.accent = host.accent.accent
        val bubble = ctx.text("", 40f, Fonts.extraBold, C.INK).apply { gravity = Gravity.CENTER; background = circle(host.accent.accent); alpha = 0f }
        r.bubble = bubble
        listHost.addView(bubble, frame(84.u, 84.u, Gravity.END, r = 70.u))
        listHost.addView(r, frame(40.u, MATCH, Gravity.END, t = 20.u, b = 30.u))
        rail = r
    }

    // ---------------------------------------------------------------- adapters

    private class AlbumAdapter(private val albums: List<Album>, private val onOpen: (Album, ImageView) -> Unit) : RecyclerView.Adapter<AlbumAdapter.H>() {
        override fun getItemCount() = albums.size
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) = H(parent.context)
        override fun onBindViewHolder(h: H, position: Int) = h.bind(albums[position])
        inner class H(ctx: Context) : RecyclerView.ViewHolder(LinearLayout(ctx)) {
            private val art = SquareImage(ctx).apply { scaleType = ImageView.ScaleType.CENTER_CROP; roundCorners(20) }
            private val title = ctx.text("", 18f, Fonts.extraBold)
            private val sub = ctx.text("", 15f, Fonts.semiBold, C.alpha(C.TEXT, 0.55f))
            init {
                (itemView as LinearLayout).apply {
                    orientation = LinearLayout.VERTICAL
                    addView(art, linear(MATCH, WRAP))
                    addView(title, linear(MATCH, WRAP, t = 10.u))
                    addView(sub, linear(MATCH, WRAP, t = 2.u))
                    layoutParams = RecyclerView.LayoutParams(MATCH, WRAP).apply { setMargins(0, 0, 18.u, 22.u) }
                    pressable(0.96f) { albums.getOrNull(bindingAdapterPosition)?.let { onOpen(it, art) } }
                }
            }
            fun bind(a: Album) {
                title.text = a.name
                sub.text = a.artist ?: Fmt.count(a.count, "song")
                ArtLoader.bind(art, a.sample, ArtLoader.Size.SMALL)
            }
        }
    }

    private class ArtistAdapter(private val artists: List<Artist>, private val onOpen: (Artist, ImageView) -> Unit) : RecyclerView.Adapter<ArtistAdapter.H>() {
        override fun getItemCount() = artists.size
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) = H(parent.context)
        override fun onBindViewHolder(h: H, position: Int) = h.bind(artists[position])
        inner class H(ctx: Context) : RecyclerView.ViewHolder(LinearLayout(ctx)) {
            private val art = ImageView(ctx).apply { scaleType = ImageView.ScaleType.CENTER_CROP; ovalClip() }
            private val name = ctx.text("", 21f, Fonts.extraBold)
            private val sub = ctx.text("", 16f, Fonts.semiBold, C.alpha(C.TEXT, 0.55f))
            init {
                (itemView as LinearLayout).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    setPadding(10.u, 0, 10.u, 0)
                    addView(art, linear(60.u, 60.u, r = 18.u))
                    addView(LinearLayout(ctx).apply {
                        orientation = LinearLayout.VERTICAL
                        addView(name, linear(MATCH, WRAP)); addView(sub, linear(MATCH, WRAP, t = 3.u))
                    }, linear(0, WRAP, 1f))
                    layoutParams = RecyclerView.LayoutParams(MATCH, 84.u)
                    pressable(0.98f) { artists.getOrNull(bindingAdapterPosition)?.let { onOpen(it, art) } }
                }
            }
            fun bind(a: Artist) {
                name.text = a.name
                sub.text = "${Fmt.count(a.count, "song")} · ${Fmt.count(a.albums, "album")}"
                ArtLoader.bind(art, a.sample, ArtLoader.Size.SMALL)
            }
        }
    }
}

/** ImageView that stays square in grids. */
class SquareImage(ctx: Context) : ImageView(ctx) {
    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) = super.onMeasure(widthMeasureSpec, widthMeasureSpec)
}

/** Album or artist page: same two-pane layout as folders, for consistency. */
class DetailScreen private constructor(
    host: MainActivity,
    private val titleText: String,
    private val round: Boolean,
    private val loader: suspend () -> List<Track>,
    private val showArtInRows: Boolean,
    private val initialArt: android.graphics.drawable.Drawable?,
) : Screen(host) {
    override val tab = Tab.LIBRARY
    override val heroArt: ImageView? get() = if (::hero.isInitialized) hero.art else null

    private lateinit var hero: HeroPane
    private lateinit var list: RecyclerView
    private lateinit var tracksAdapter: TrackAdapter
    private var tracks: List<Track> = emptyList()

    override fun build(): View {
        hero = HeroPane(ctx, round)
        hero.title.text = titleText
        initialArt?.let { hero.art.setImageDrawable(it.constantState?.newDrawable() ?: it) }
        hero.play.pressable { if (tracks.isNotEmpty()) PlayerHub.play(tracks, 0, PlayContext(titleText, null)) }
        hero.shuffle.pressable { if (tracks.isNotEmpty()) PlayerHub.play(tracks, 0, PlayContext(titleText, null), shuffle = true) }
        val back = ctx.text("‹  Library", 18f, Fonts.bold, C.alpha(C.TEXT, 0.6f)).apply {
            setPadding(10.u, 16.u, 10.u, 16.u)
            pressable(0.95f) { host.pop() }
        }
        list = RecyclerView(ctx).apply {
            layoutManager = LinearLayoutManager(ctx)
            clipToPadding = false
            setPadding(0, 6.u, (if (D.narrow) 16 else 30).u, D.bottomChrome(mini = true) + 20.u)
            itemAnimator = null
        }
        tracksAdapter = TrackAdapter(
            onPlay = { i -> PlayerHub.play(tracks, i, PlayContext(titleText, null)) },
            onLong = { t -> host.showTrackActions(t, t.parent) },
            onPlayNext = { t -> PlayerHub.playNext(t); host.toast("Plays next: ${t.title}") },
        )
        list.adapter = tracksAdapter
        val col = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(if (hero.compact) 24.u else 8.u, if (hero.compact) 0 else 22.u, 0, 0)
            addView(back, linear(WRAP, 56.u))
            addView(list, linear(MATCH, 0, 1f))
        }
        val root = com.teja.bumblebee.ui.browse.heroLayout(ctx, hero, col)
        if (loaded) fill() else scope.launch {
            tracks = withContext(Dispatchers.IO) { loader() }
            loaded = true
            fill()
            reveal(list)
        }
        return root
    }

    private var loaded = false

    override suspend fun prepare() {
        tracks = withContext(Dispatchers.IO) { loader() }
        loaded = true
    }

    private fun fill() {
        tracksAdapter.submit(tracks.mapIndexed { i, t -> TrackItem(t, if (showArtInRows) i + 1 else t.trackNo.takeIf { it > 0 } ?: (i + 1), showArt = showArtInRows) })
        val dur = tracks.sumOf { it.durationMs }
        hero.subtitle.text = listOfNotNull(
            if (!round) tracks.firstOrNull()?.let { it.albumArtist ?: it.artist } else null,
            Fmt.count(tracks.size, "song"),
            if (dur > 0) Fmt.duration(dur) else null,
        ).joinToString(" · ")
        tracks.firstOrNull()?.let { ArtLoader.bind(hero.art, it, ArtLoader.Size.LARGE, fade = initialArt == null) }
    }

    override fun onShow() {
        launchVisible { host.accentFlow.collect { a -> tracksAdapter.accent = a.accent; hero.setAccent(a.accent) } }
        launchVisible { PlayerHub.state.collect { s -> tracksAdapter.setPlaying(s.current?.path, s.playing) } }
    }

    companion object {
        fun album(host: MainActivity, album: String, albumArtist: String?, art: android.graphics.drawable.Drawable? = null) =
            DetailScreen(host, album, round = false, loader = { Library.albumTracks(album, albumArtist) }, showArtInRows = false, initialArt = art)

        fun artist(host: MainActivity, artist: String, art: android.graphics.drawable.Drawable? = null) =
            DetailScreen(host, artist, round = true, loader = { Library.artistTracks(artist) }, showArtInRows = true, initialArt = art)
    }
}
