package com.teja.bumblebee.ui.browse

import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.recyclerview.widget.ConcatAdapter
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.teja.bumblebee.R
import com.teja.bumblebee.art.ArtLoader
import com.teja.bumblebee.data.Library
import com.teja.bumblebee.data.PlayContext
import com.teja.bumblebee.data.Prefs
import com.teja.bumblebee.eggs.EasterEggs
import com.teja.bumblebee.playback.PlayerHub
import com.teja.bumblebee.storage.FolderListing
import com.teja.bumblebee.storage.FolderRepo
import com.teja.bumblebee.storage.Volumes
import com.teja.bumblebee.ui.MainActivity
import com.teja.bumblebee.ui.design.C
import com.teja.bumblebee.ui.design.Fonts
import com.teja.bumblebee.ui.design.MATCH
import com.teja.bumblebee.ui.design.WRAP
import com.teja.bumblebee.ui.design.circle
import com.teja.bumblebee.ui.design.frame
import com.teja.bumblebee.ui.design.icon
import com.teja.bumblebee.ui.design.linear
import com.teja.bumblebee.ui.design.pressable
import com.teja.bumblebee.ui.design.text
import com.teja.bumblebee.ui.design.u
import com.teja.bumblebee.ui.shell.Screen
import com.teja.bumblebee.ui.shell.Tab
import com.teja.bumblebee.ui.shell.enter
import com.teja.bumblebee.util.Fmt
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch
import java.io.File

class FolderScreen(host: MainActivity, private val dir: File, private var highlight: String? = null) : Screen(host) {

    override val tab = Tab.HOME

    private lateinit var hero: HeroPane
    private lateinit var list: RecyclerView
    private lateinit var crumbs: LinearLayout
    private lateinit var emptyHost: FrameLayout
    private lateinit var listHost: FrameLayout
    private lateinit var tracks: TrackAdapter
    private var folders: FolderCardAdapter? = null
    private var listing: FolderListing? = null
    private var rail: AlphabetRail? = null

    override fun build(): View {
        val root = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL }
        hero = HeroPane(ctx)
        hero.title.text = displayName()
        ArtLoader.bindFolder(hero.art, dir.path, dir.name, ArtLoader.Size.LARGE)
        hero.play.pressable { host.playFolder(dir, shuffle = false) }
        hero.shuffle.pressable { host.playFolder(dir, shuffle = true) }
        root.addView(hero, linear(380.u, MATCH))

        val col = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL; setPadding(8.u, 26.u, 0, 0) }
        crumbs = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        val crumbScroll = HorizontalScrollView(ctx).apply { isHorizontalScrollBarEnabled = false; addView(crumbs) }
        col.addView(crumbScroll, linear(MATCH, 56.u, r = 30.u))
        listHost = FrameLayout(ctx)
        list = RecyclerView(ctx).apply {
            clipToPadding = false
            setPadding(0, 14.u, 30.u, 124.u)
            itemAnimator = null
            setHasFixedSize(true)
        }
        listHost.addView(list, frame(MATCH, MATCH))
        emptyHost = FrameLayout(ctx)
        listHost.addView(emptyHost, frame(MATCH, MATCH))
        col.addView(listHost, linear(MATCH, 0, 1f))
        root.addView(col, linear(0, MATCH, 1f))

        tracks = TrackAdapter(
            onPlay = { i -> playFrom(i) },
            onLong = { t -> host.showTrackActions(t, dir.path) },
            onPlayNext = { t -> PlayerHub.playNext(t); host.toast("Plays next: ${t.title}") },
        )
        buildCrumbs()
        load()
        return root
    }

    private fun displayName(): String {
        val v = Volumes.forPath(dir.path)
        return if (v != null && v.path == dir.path) v.label else dir.name
    }

    override fun onShow() {
        launchVisible {
            PlayerHub.state.collect { s ->
                tracks.setPlaying(s.current?.path, s.playing)
            }
        }
        launchVisible { Library.version.drop(1).debounce(700).collect { load() } }
        launchVisible {
            host.accentFlow.collect { a ->
                tracks.accent = a.accent
                hero.setAccent(a.accent)
                rail?.accent = a.accent
            }
        }
    }

    private fun buildCrumbs() {
        crumbs.removeAllViews()
        val back = FrameLayout(ctx).apply {
            background = circle(0x14FFFFFF)
            addView(ctx.icon(R.drawable.ic_chevron_left, 26, C.TEXT), frame(26.u, 26.u, Gravity.CENTER))
            contentDescription = "Up one folder"
            pressable(0.9f) { goUp() }
        }
        crumbs.addView(back, linear(56.u, 56.u, r = 8.u))
        val volume = Volumes.forPath(dir.path)
        val parts = ArrayList<Pair<String, String>>()
        var f: File? = dir
        while (f != null) {
            val isRoot = volume != null && f.path == volume.path
            parts.add(0, (if (isRoot) volume!!.label else f.name) to f.path)
            if (isRoot || f.parentFile == null || volume == null) break
            f = f.parentFile
        }
        parts.forEachIndexed { i, (name, path) ->
            val last = i == parts.lastIndex
            val tv: TextView = ctx.text(name, 18f, if (last) Fonts.extraBold else Fonts.bold, if (last) C.TEXT else C.alpha(C.TEXT, 0.6f)).apply {
                setPadding(10.u, 14.u, 10.u, 14.u)
                if (!last) pressable(0.95f) { host.openFolder(path) }
            }
            crumbs.addView(tv)
            if (!last) crumbs.addView(ctx.text("›", 18f, Fonts.bold, C.alpha(C.TEXT, 0.3f)))
        }
    }

    private fun goUp() {
        if (!host.pop()) host.onBackPressedDispatcher.onBackPressed()
    }

    private fun load() {
        scope.launch {
            val l = FolderRepo.list(dir)
            val first = listing == null
            listing = l
            render(l, first)
        }
    }

    private fun render(l: FolderListing, first: Boolean) {
        val totalSongs = l.tracks.size + l.folders.sumOf { it.songs.coerceAtLeast(0) }
        val dur = l.tracks.sumOf { it.durationMs } + l.folders.sumOf { it.durationMs }
        hero.subtitle.text = buildList {
            if (totalSongs > 0) add(Fmt.count(totalSongs, "song"))
            if (dur > 0) add(Fmt.duration(dur))
            if (l.folders.isNotEmpty()) add(Fmt.count(l.folders.size, "folder"))
        }.joinToString(" · ")

        val big = l.tracks.isEmpty()
        val cards = l.folders.map { FolderCardItem(it.dir.path, it.name, if (it.songs >= 0) Fmt.count(it.songs, "song") else "Folder") } +
            l.playlists.map { FolderCardItem(it.path, it.nameWithoutExtension, "Playlist", isPlaylist = true) }
        val fa = folders?.takeIf { cards.isNotEmpty() } ?: if (cards.isNotEmpty()) FolderCardAdapter(
            big,
            onOpen = { c -> if (c.isPlaylist) playPlaylist(c) else host.openFolder(c.path) },
            onPlay = { c -> if (c.isPlaylist) playPlaylist(c) else host.playFolder(File(c.path), shuffle = false) },
            onLong = { c -> if (!c.isPlaylist) host.showFolderActions(c.path, c.name) },
        ) else null
        fa?.items = cards
        tracks.submit(l.tracks.mapIndexed { i, t -> TrackItem(t, i + 1) })
        highlight?.let { tracks.highlight(it) }

        if (first || list.adapter == null || folders !== fa) {
            folders = fa
            val adapters = listOfNotNull(fa, tracks)
            val concat = ConcatAdapter(ConcatAdapter.Config.Builder().setIsolateViewTypes(true).build(), adapters)
            val span = if (big) 3 else 3
            val glm = GridLayoutManager(ctx, span)
            glm.spanSizeLookup = object : GridLayoutManager.SpanSizeLookup() {
                override fun getSpanSize(position: Int) = if (position < (folders?.itemCount ?: 0)) 1 else span
            }
            list.layoutManager = glm
            list.adapter = concat
            if (first) list.enter(dy = 18f.u, duration = 320)
        }

        emptyHost.removeAllViews()
        if (l.tracks.isEmpty() && cards.isEmpty()) {
            emptyHost.addView(emptyState(ctx, "Nothing here.", EasterEggs.emptyFolderLine(), "Go up") { goUp() }, frame(MATCH, MATCH))
        }

        rail?.let { listHost.removeView(it) }
        rail = null
        if (l.tracks.size > 40) {
            val offset = folders?.itemCount ?: 0
            val r = AlphabetRail(ctx, list) { i -> if (i >= offset) tracks.items.getOrNull(i - offset)?.track?.title else null }
            r.accent = host.accent.accent
            val bubble = ctx.text("", 40f, Fonts.extraBold, C.INK).apply {
                gravity = Gravity.CENTER
                background = circle(host.accent.accent)
                alpha = 0f
            }
            r.bubble = bubble
            listHost.addView(bubble, frame(84.u, 84.u, Gravity.END, r = 70.u))
            listHost.addView(r, frame(40.u, MATCH, Gravity.END, b = 120.u, t = 10.u))
            rail = r
        }

        highlight?.let { h ->
            val idx = l.tracks.indexOfFirst { it.path == h }
            if (idx >= 0) list.post { (list.layoutManager as GridLayoutManager).scrollToPositionWithOffset((folders?.itemCount ?: 0) + idx, 120.u) }
            highlight = null
        }
    }

    private fun playFrom(i: Int) {
        val l = listing ?: return
        val t = l.tracks.getOrNull(i) ?: return
        scope.launch {
            val ctxLabel = host.folderLabel(dir)
            if (Prefs.tapIncludesSubfolders && l.folders.isNotEmpty()) {
                val all = FolderRepo.tracksFor(dir, recursive = true)
                PlayerHub.play(all, all.indexOfFirst { it.path == t.path }.coerceAtLeast(0), PlayContext(ctxLabel, dir.path))
            } else {
                PlayerHub.play(l.tracks, i, PlayContext(ctxLabel, dir.path))
            }
        }
    }

    private fun playPlaylist(c: FolderCardItem) {
        scope.launch {
            val list = FolderRepo.playlist(File(c.path))
            if (list.isEmpty()) host.toast("That playlist is empty") else PlayerHub.play(list, 0, PlayContext(c.name, dir.path))
        }
    }
}
