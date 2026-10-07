package com.teja.bumblebee.ui.browse

import android.graphics.drawable.Drawable
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
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
import com.teja.bumblebee.ui.design.D
import com.teja.bumblebee.ui.design.Fonts
import com.teja.bumblebee.ui.design.MATCH
import com.teja.bumblebee.ui.design.circle
import com.teja.bumblebee.ui.design.frame
import com.teja.bumblebee.ui.design.icon
import com.teja.bumblebee.ui.design.linear
import com.teja.bumblebee.ui.design.pressable
import com.teja.bumblebee.ui.design.text
import com.teja.bumblebee.ui.design.u
import com.teja.bumblebee.ui.shell.Screen
import com.teja.bumblebee.ui.shell.Tab
import com.teja.bumblebee.util.Fmt
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch
import java.io.File

/**
 * A folder page. Handles the awkward real-world shapes of pendrives:
 * - folder with both songs and subfolders: two labelled sections; "Play all" covers everything
 *   inside, "Play these N" just the songs at this level;
 * - only subfolders: a grid of big cards; only songs: a track list;
 * - a chain of single folders (Music › Songs › All): opens straight into the music;
 * - a folder that vanished (drive pulled): says so instead of looking empty.
 */
class FolderScreen(
    host: MainActivity,
    private var dir: File,
    private var highlight: String? = null,
    private val initialArt: Drawable? = null,
) : Screen(host) {

    override val tab = Tab.HOME
    val path: String get() = dir.path
    override val heroArt: ImageView? get() = if (::hero.isInitialized) hero.art else null

    private lateinit var hero: HeroPane
    private lateinit var list: RecyclerView
    private lateinit var crumbs: LinearLayout
    private lateinit var emptyHost: FrameLayout
    private lateinit var listHost: FrameLayout
    private lateinit var tracks: TrackAdapter
    private lateinit var folderHeader: HeaderAdapter
    private lateinit var songHeader: HeaderAdapter
    private var folders: FolderCardAdapter? = null
    private var bigCards: Boolean? = null
    private var listing: FolderListing? = null
    private var rail: AlphabetRail? = null
    private var firstFill = true

    override fun build(): View {
        hero = HeroPane(ctx)
        hero.title.text = displayName()
        ArtLoader.bindFolder(hero.art, dir.path, dir.name, ArtLoader.Size.LARGE, initialArt)
        hero.play.pressable { host.playFolder(dir, shuffle = false) }
        hero.shuffle.pressable { host.playFolder(dir, shuffle = true) }

        val col = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL; setPadding(if (hero.compact) 24.u else 8.u, if (hero.compact) 8.u else 26.u, 0, 0) }
        crumbs = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        val crumbScroll = HorizontalScrollView(ctx).apply { isHorizontalScrollBarEnabled = false; addView(crumbs) }
        col.addView(crumbScroll, linear(MATCH, 56.u, r = 30.u))
        listHost = FrameLayout(ctx)
        list = RecyclerView(ctx).apply {
            clipToPadding = false
            setPadding(0, 4.u, 30.u, 124.u)
            itemAnimator = null
        }
        listHost.addView(list, frame(MATCH, MATCH))
        emptyHost = FrameLayout(ctx)
        listHost.addView(emptyHost, frame(MATCH, MATCH))
        col.addView(listHost, linear(MATCH, 0, 1f))
        val root = heroLayout(ctx, hero, col)

        tracks = TrackAdapter(
            onPlay = { i -> playFrom(i) },
            onLong = { t -> host.showTrackActions(t, dir.path) },
            onPlayNext = { t -> PlayerHub.playNext(t); host.toast("Plays next: ${t.title}") },
        )
        folderHeader = HeaderAdapter("Folders").apply { visible = false }
        songHeader = HeaderAdapter("Songs", null) { playHere() }.apply { visible = false }
        buildCrumbs()
        load()
        return root
    }

    private fun displayName(): String {
        val v = Volumes.forPath(dir.path)
        return if (v != null && v.path == dir.path) v.label else FolderRepo.contextualName(dir.path)
    }

    override fun onShow() {
        launchVisible { PlayerHub.state.collect { s -> tracks.setPlaying(s.current?.path, s.playing) } }
        launchVisible { Library.version.drop(1).debounce(700).collect { load() } }
        // Drive pulled (or plugged back in) while this folder is open: re-check right away.
        launchVisible { Volumes.all.drop(1).collect { load() } }
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
        // Very deep paths: keep the first and last two segments.
        val shown = if (parts.size > 4) listOf(parts.first(), "…" to "", parts[parts.size - 2], parts.last()) else parts
        shown.forEachIndexed { i, (name, path) ->
            val last = i == shown.lastIndex
            val tv: TextView = ctx.text(name, 18f, if (last) Fonts.extraBold else Fonts.bold, if (last) C.TEXT else C.alpha(C.TEXT, 0.6f)).apply {
                setPadding(10.u, 14.u, 10.u, 14.u)
                if (!last && path.isNotEmpty()) pressable(0.95f) { host.openFolder(path) }
            }
            crumbs.addView(tv)
            if (!last) crumbs.addView(ctx.text("›", 18f, Fonts.bold, C.alpha(C.TEXT, 0.3f)))
        }
        crumbs.post { (crumbs.parent as? HorizontalScrollView)?.fullScroll(View.FOCUS_RIGHT) }
    }

    private fun goUp() {
        if (!host.pop()) host.onBackPressedDispatcher.onBackPressed()
    }

    private fun load() {
        scope.launch {
            var l = FolderRepo.list(dir)
            // A folder holding nothing but one subfolder: open straight into it.
            var hops = 0
            while (l.exists && l.tracks.isEmpty() && l.playlists.isEmpty() && l.folders.size == 1 && hops < 6) {
                dir = l.folders.first().dir
                l = FolderRepo.list(dir)
                hops++
            }
            if (hops > 0) {
                hero.title.text = displayName()
                ArtLoader.bindFolder(hero.art, dir.path, dir.name, ArtLoader.Size.LARGE, hero.art.drawable)
                buildCrumbs()
            }
            listing = l
            render(l)
        }
    }

    private fun render(l: FolderListing) {
        emptyHost.removeAllViews()
        if (!l.exists) {
            hero.subtitle.text = "Not available"
            list.adapter = null
            val v = Volumes.labelForMissing(dir.path)
            emptyHost.addView(emptyState(ctx, "Can't reach this folder", "It may be on $v, which isn't connected right now.", "Go back") { goUp() }, frame(MATCH, MATCH))
            return
        }

        val here = l.tracks.size
        val nested = l.nestedSongs
        val dur = l.tracks.sumOf { it.durationMs } + l.folders.sumOf { it.durationMs }
        hero.subtitle.text = when {
            l.folders.isEmpty() -> listOfNotNull(Fmt.count(here, "song"), if (dur > 0) Fmt.duration(dur) else null).joinToString(" · ")
            here == 0 -> listOfNotNull(
                if (nested > 0) Fmt.count(nested, "song") else null,
                "in " + Fmt.count(l.folders.size, "folder"),
                if (dur > 0) Fmt.duration(dur) else null,
            ).joinToString(" · ")
            else -> listOfNotNull(
                if (nested > 0) Fmt.count(here + nested, "song") else Fmt.count(here, "song") + " here",
                "${Fmt.count(l.folders.size, "folder")} inside",
                if (dur > 0) Fmt.duration(dur) else null,
            ).joinToString(" · ")
        }
        hero.play.title.text = if (l.folders.isNotEmpty()) "Play all" else "Play"

        val big = l.tracks.isEmpty()
        val cards = l.folders.map { e ->
            FolderCardItem(e.dir.path, e.name, if (e.songs >= 0) Fmt.count(e.songs, "song") else "Folder")
        } + l.playlists.map { FolderCardItem(it.path, it.nameWithoutExtension, "Playlist", isPlaylist = true) }

        if (folders == null || bigCards != big) {
            bigCards = big
            folders = FolderCardAdapter(
                big,
                onOpen = { c, art -> if (c.isPlaylist) playPlaylist(c) else host.openFolder(c.path, shared = art) },
                onPlay = { c -> if (c.isPlaylist) playPlaylist(c) else host.playFolder(File(c.path), shuffle = false) },
                onLong = { c -> if (!c.isPlaylist) host.showFolderActions(c.path, c.name) },
            )
            val concat = ConcatAdapter(
                ConcatAdapter.Config.Builder().setIsolateViewTypes(true).setStableIdMode(ConcatAdapter.Config.StableIdMode.ISOLATED_STABLE_IDS).build(),
                listOf(folderHeader, folders!!, songHeader, tracks),
            )
            // Folder cards per row from the list's width (≈250px each), so any screen fills evenly.
            val listW = D.contentW - (if (hero.compact) 24 else 380 + 8) - 30
            val span = (listW / (if (big) 230 else 250)).coerceIn(2, 6)
            val glm = GridLayoutManager(ctx, span)
            glm.spanSizeLookup = object : GridLayoutManager.SpanSizeLookup() {
                override fun getSpanSize(position: Int): Int {
                    val h = folderHeader.itemCount
                    val f = folders?.itemCount ?: 0
                    return if (position >= h && position < h + f) 1 else span
                }
            }
            list.layoutManager = glm
            list.adapter = concat
        }
        folders?.items = cards
        val mixed = cards.isNotEmpty() && l.tracks.isNotEmpty()
        folderHeader.visible = mixed
        folderHeader.set(Fmt.count(l.folders.size, "folder") + if (l.playlists.isNotEmpty()) " · ${Fmt.count(l.playlists.size, "playlist")}" else "", null)
        songHeader.visible = mixed
        songHeader.set("${Fmt.count(here, "song")} in this folder", "Play these $here")
        tracks.submit(l.tracks.mapIndexed { i, t -> TrackItem(t, i + 1) })
        highlight?.let { tracks.highlight(it) }
        if (firstFill) { firstFill = false; list.staggerIn() }

        if (l.tracks.isEmpty() && cards.isEmpty()) {
            emptyHost.addView(emptyState(ctx, "Nothing here.", EasterEggs.emptyFolderLine(), "Go up") { goUp() }, frame(MATCH, MATCH))
        }

        rail?.let { listHost.removeView(it) }
        rail = null
        if (l.tracks.size > 40) {
            val offset = folderHeader.itemCount + (folders?.itemCount ?: 0) + songHeader.itemCount
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
            val offset = folderHeader.itemCount + (folders?.itemCount ?: 0) + songHeader.itemCount
            if (idx >= 0) list.post { (list.layoutManager as GridLayoutManager).scrollToPositionWithOffset(offset + idx, 120.u) }
            highlight = null
        }
    }

    private fun playHere() {
        val l = listing ?: return
        if (l.tracks.isNotEmpty()) PlayerHub.play(l.tracks, 0, PlayContext(host.folderLabel(dir), dir.path))
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
