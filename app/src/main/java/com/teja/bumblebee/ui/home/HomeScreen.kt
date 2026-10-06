package com.teja.bumblebee.ui.home

import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.teja.bumblebee.R
import com.teja.bumblebee.art.ArtLoader
import com.teja.bumblebee.data.FolderInfo
import com.teja.bumblebee.data.Library
import com.teja.bumblebee.eggs.EasterEggs
import com.teja.bumblebee.index.Indexer
import com.teja.bumblebee.playback.PlayerHub
import com.teja.bumblebee.storage.Volume
import com.teja.bumblebee.storage.Volumes
import com.teja.bumblebee.ui.MainActivity
import com.teja.bumblebee.ui.browse.FolderCardAdapter
import com.teja.bumblebee.ui.browse.FolderCardItem
import com.teja.bumblebee.ui.browse.emptyState
import com.teja.bumblebee.ui.browse.sectionLabel
import com.teja.bumblebee.ui.design.C
import com.teja.bumblebee.ui.design.CircleButton
import com.teja.bumblebee.ui.design.Fonts
import com.teja.bumblebee.ui.design.MATCH
import com.teja.bumblebee.ui.design.PlayPauseView
import com.teja.bumblebee.ui.design.WRAP
import com.teja.bumblebee.ui.design.frame
import com.teja.bumblebee.ui.design.linear
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
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/** Home: the "now playing" card doubles as the player here (no mini-player), then sources and folders. */
class HomeScreen(host: MainActivity) : Screen(host) {

    override val showsMiniPlayer = false
    override val tab = Tab.HOME

    private lateinit var greeting: TextView
    private lateinit var stats: TextView
    private lateinit var card: LinearLayout
    private lateinit var cardBg: GradientDrawable
    private lateinit var cardCover: ImageView
    private lateinit var cardLabel: TextView
    private lateinit var cardTitle: TextView
    private lateinit var cardSub: TextView
    private lateinit var cardProgress: View
    private lateinit var cardPlay: PlayPauseView
    private lateinit var sourcesRow: LinearLayout
    private lateinit var sections: LinearLayout
    private lateinit var body: LinearLayout
    private lateinit var emptyHost: FrameLayout
    private var built = false

    override fun build(): View {
        val scroll = ScrollView(ctx).apply { isVerticalScrollBarEnabled = false; clipToPadding = false }
        body = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL; setPadding(40.u, 30.u, 36.u, 40.u) }
        scroll.addView(body)

        greeting = ctx.text("", 32f, Fonts.extraBold).apply { letterSpacing = -0.02f }
        stats = ctx.text("", 16f, Fonts.semiBold, C.alpha(C.TEXT, 0.5f)).apply { gravity = Gravity.END }
        val header = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.BOTTOM
            addView(greeting, linear(0, WRAP, 1f))
            addView(stats, linear(WRAP, WRAP))
        }
        body.addView(header, linear(MATCH, 44.u))

        buildCard()
        body.addView(card, linear(MATCH, 132.u, t = 18.u))

        body.addView(sectionLabel(ctx, "Sources"), linear(MATCH, WRAP, t = 26.u))
        sourcesRow = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL }
        body.addView(sourcesRow, linear(MATCH, 112.u, t = 12.u))

        sections = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        body.addView(sections, linear(MATCH, WRAP))
        emptyHost = FrameLayout(ctx)
        body.addView(emptyHost, linear(MATCH, WRAP, t = 30.u))
        return scroll
    }

    private fun buildCard() {
        cardBg = GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT, intArrayOf(0x4DFFC72C, 0x14FFFFFF)).apply { cornerRadius = 28f.u }
        cardCover = ImageView(ctx).apply { scaleType = ImageView.ScaleType.CENTER_CROP; roundCorners(18) }
        cardLabel = ctx.text("", 14f, Fonts.extraBold, C.BEE).apply { letterSpacing = 0.14f }
        cardTitle = ctx.text("", 28f, Fonts.extraBold)
        cardSub = ctx.text("", 18f, Fonts.semiBold, C.alpha(C.TEXT, 0.66f))
        cardProgress = View(ctx).apply { background = rounded(C.BEE, 3); pivotX = 0f }
        val track = FrameLayout(ctx).apply {
            background = rounded(0x24FFFFFF, 3)
            addView(cardProgress, frame(MATCH, MATCH))
        }
        val texts = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            addView(cardLabel, linear(MATCH, WRAP))
            addView(cardTitle, linear(MATCH, WRAP, t = 6.u))
            addView(cardSub, linear(MATCH, WRAP, t = 4.u))
            addView(track, linear(MATCH, 5.u, t = 12.u))
        }
        val open = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(cardCover, linear(100.u, 100.u, r = 22.u))
            addView(texts, linear(0, WRAP, 1f, r = 18.u))
            pressable(0.98f) { host.showTab(Tab.NOW) }
        }
        val prev = CircleButton(ctx, R.drawable.ic_prev, 76, 30, 0x14FFFFFF, C.TEXT).apply { pressable { PlayerHub.prev() } }
        cardPlay = PlayPauseView(ctx, 40).apply { pressable { PlayerHub.toggle() } }
        val next = CircleButton(ctx, R.drawable.ic_next, 76, 30, 0x14FFFFFF, C.TEXT).apply { pressable { PlayerHub.next() } }
        card = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = cardBg
            setPadding(16.u, 0, 18.u, 0)
            addView(open, linear(0, MATCH, 1f))
            addView(prev, linear(76.u, 76.u))
            addView(cardPlay, linear(96.u, 96.u, l = 10.u, r = 10.u))
            addView(next, linear(76.u, 76.u))
        }
    }

    override fun onShow() {
        greeting.text = EasterEggs.greeting()
        tint(host.accent.accent)
        launchVisible {
            PlayerHub.state.collect { s ->
                val t = s.current
                card.visibility = if (t == null) View.GONE else View.VISIBLE
                if (t != null) {
                    ArtLoader.bind(cardCover, t, ArtLoader.Size.SMALL)
                    cardLabel.text = if (s.playing) "NOW PLAYING" else "CONTINUE"
                    cardTitle.text = t.title
                    cardSub.text = listOf(t.artistLabel, s.context.label).filter { it.isNotBlank() }.joinToString(" · ") +
                        if (s.count > 1) " · song ${s.index + 1} of ${s.count}" else ""
                }
                cardPlay.setPlaying(s.playing, animate = true)
            }
        }
        launchVisible { host.accentFlow.collect { tint(it.accent) } }
        launchVisible {
            while (true) {
                val s = PlayerHub.state.value
                if (s.durationMs > 0) cardProgress.scaleX = (PlayerHub.positionMs.toFloat() / s.durationMs).coerceIn(0f, 1f)
                kotlinx.coroutines.delay(1000)
            }
        }
        launchVisible {
            combine(Library.version, Volumes.all, Indexer.state) { _, v, i -> v to i }.debounce(250).collect { (vols, idx) ->
                refresh(vols, idx.running)
            }
        }
    }

    private fun tint(accent: Int) {
        cardBg.colors = intArrayOf(C.alpha(accent, 0.28f), 0x12FFFFFF)
        cardLabel.setTextColor(accent)
        cardProgress.background = rounded(accent, 3)
        cardPlay.colorBg = accent
        cardPlay.colorGlyph = if (C.isLight(accent)) C.INK else C.TEXT
    }

    private suspend fun refresh(vols: List<Volume>, indexing: Boolean) {
        data class Data(
            val total: Int,
            val sources: List<Triple<Volume, Int, Int>>,
            val recent: List<FolderInfo>,
            val most: List<FolderInfo>,
            val newest: List<FolderInfo>,
        )
        val d = withContext(Dispatchers.IO) {
            Data(
                Library.totalTracks(),
                vols.map { v -> Library.volumeStats(v.id).let { Triple(v, it.first, it.second) } },
                Library.recentFolders(12),
                Library.mostPlayedFolders(12),
                Library.newestFolders(12),
            )
        }
        stats.text = when {
            indexing -> "Rewinding the tapes…"
            d.total > 0 -> "${Fmt.count(d.total, "song")} on ${Fmt.count(vols.size, "source")}"
            else -> ""
        }
        renderSources(d.sources)
        sections.removeAllViews()
        fun section(title: String, folders: List<FolderInfo>) {
            if (folders.isEmpty()) return
            sections.addView(sectionLabel(ctx, title), linear(MATCH, WRAP, t = 28.u))
            val rv = RecyclerView(ctx).apply {
                layoutManager = LinearLayoutManager(ctx, LinearLayoutManager.HORIZONTAL, false)
                clipToPadding = false
                itemAnimator = null
                adapter = FolderCardAdapter(
                    big = true,
                    onOpen = { host.openFolder(it.path) },
                    onPlay = { host.playFolder(File(it.path), shuffle = false, recursive = false) },
                    onLong = { host.showFolderActions(it.path, it.name) },
                ).apply { items = folders.map { f -> FolderCardItem(f.path, f.name, Fmt.count(f.total, "song")) } }
            }
            // Fixed-width cards in a horizontal row.
            rv.addOnChildAttachStateChangeListener(object : RecyclerView.OnChildAttachStateChangeListener {
                override fun onChildViewAttachedToWindow(view: View) { view.layoutParams.width = 168.u }
                override fun onChildViewDetachedFromWindow(view: View) {}
            })
            sections.addView(rv, linear(MATCH, 270.u, t = 12.u))
        }
        section("Recent folders", d.recent)
        section("Most played", d.most.filter { m -> d.recent.take(4).none { it.path == m.path } })
        section("Recently added", d.newest)

        emptyHost.removeAllViews()
        if (d.total == 0 && !indexing) {
            emptyHost.addView(
                emptyState(ctx, "No music yet", "Plug in a pendrive with songs. Bee will find them.", if (vols.isNotEmpty()) "Browse ${vols.first().label}" else null) {
                    vols.firstOrNull()?.let { host.openFolder(it.path) }
                },
                frame(MATCH, 320.u),
            )
        }
        if (!built) { built = true; body.enter(dy = 16f.u, duration = 320) }
    }

    private fun renderSources(list: List<Triple<Volume, Int, Int>>) {
        sourcesRow.removeAllViews()
        list.forEachIndexed { i, (v, songs, folders) ->
            val art = ImageView(ctx).apply { scaleType = ImageView.ScaleType.CENTER_CROP; roundCorners(16) }
            ArtLoader.bindFolder(art, v.path, "Mixtape Vol. ${v.mixtape}", ArtLoader.Size.SMALL)
            val name = ctx.text(v.label, 22f, Fonts.extraBold)
            val vol = ctx.text("MIXTAPE VOL. ${v.mixtape}", 13f, Fonts.extraBold, C.BEE).apply {
                letterSpacing = 0.08f
                background = rounded(0x29FFC72C, 99)
                setPadding(9.u, 3.u, 9.u, 3.u)
            }
            val sub = ctx.text(if (songs > 0) "${Fmt.count(songs, "song")} · ${Fmt.count(folders, "folder")}" else "Not scanned yet", 17f, Fonts.semiBold, C.alpha(C.TEXT, 0.62f))
            val space = v.space()
            val bar = FrameLayout(ctx).apply { background = rounded(0x1FFFFFFF, 3) }
            val fill = View(ctx).apply { background = rounded(0x8CFFFFFF.toInt(), 3); pivotX = 0f; scaleX = space?.let { it.first.toFloat() / it.second } ?: 0f }
            bar.addView(fill, frame(MATCH, MATCH))
            val spaceText = ctx.text(space?.let { "%.1f of %.0f GB".format(it.first / 1e9, it.second / 1e9) } ?: "", 14f, Fonts.semiBold, C.alpha(C.TEXT, 0.5f))
            val texts = LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                addView(LinearLayout(ctx).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    addView(name, linear(WRAP, WRAP, r = 10.u))
                    addView(vol, linear(WRAP, WRAP))
                }, linear(MATCH, WRAP))
                addView(sub, linear(MATCH, WRAP, t = 4.u))
                addView(LinearLayout(ctx).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    addView(bar, linear(0, 5.u, 1f, r = 10.u))
                    addView(spaceText, linear(WRAP, WRAP))
                }, linear(MATCH, WRAP, t = 10.u))
            }
            val cardView = LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                background = rounded(0x0FFFFFFF, 24)
                setPadding(18.u, 0, 18.u, 0)
                addView(art, linear(80.u, 80.u, r = 18.u))
                addView(texts, linear(0, WRAP, 1f))
                pressable(0.98f, onLongClick = { host.showFolderActions(v.path, v.label) }) { host.openFolder(v.path) }
            }
            sourcesRow.addView(cardView, linear(0, MATCH, 1f, r = if (i < list.lastIndex) 16.u else 0))
        }
        if (list.size == 1) sourcesRow.addView(View(ctx), linear(0, MATCH, 1f, l = 16.u))
    }
}
