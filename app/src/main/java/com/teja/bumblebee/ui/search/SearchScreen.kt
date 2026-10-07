package com.teja.bumblebee.ui.search

import android.content.Context
import android.text.Editable
import android.text.TextWatcher
import android.util.TypedValue
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import com.teja.bumblebee.R
import com.teja.bumblebee.art.ArtLoader
import com.teja.bumblebee.data.FolderInfo
import com.teja.bumblebee.data.Library
import com.teja.bumblebee.data.PlayContext
import com.teja.bumblebee.data.Prefs
import com.teja.bumblebee.data.Track
import com.teja.bumblebee.playback.PlayerHub
import com.teja.bumblebee.ui.MainActivity
import com.teja.bumblebee.ui.design.C
import com.teja.bumblebee.ui.design.D
import com.teja.bumblebee.ui.design.Fonts
import com.teja.bumblebee.ui.design.MATCH
import com.teja.bumblebee.ui.design.PillButton
import com.teja.bumblebee.ui.design.WRAP
import com.teja.bumblebee.ui.design.frame
import com.teja.bumblebee.ui.design.icon
import com.teja.bumblebee.ui.design.label
import com.teja.bumblebee.ui.design.linear
import com.teja.bumblebee.ui.design.ovalClip
import com.teja.bumblebee.ui.design.pressable
import com.teja.bumblebee.ui.design.rounded
import com.teja.bumblebee.ui.design.roundCorners
import com.teja.bumblebee.ui.design.text
import com.teja.bumblebee.ui.design.u
import com.teja.bumblebee.ui.library.DetailScreen
import com.teja.bumblebee.ui.shell.Screen
import com.teja.bumblebee.ui.shell.Tab
import com.teja.bumblebee.util.Fmt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class SearchScreen(host: MainActivity) : Screen(host) {
    override val tab = Tab.SEARCH

    private val narrow = D.narrow
    private lateinit var field: EditText
    private lateinit var results: LinearLayout
    private var job: Job? = null

    override fun build(): View {
        val root = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL; setPadding((if (narrow) 20 else 40).u, 30.u, (if (narrow) 20 else 36).u, 0) }
        field = EditText(ctx).apply {
            hint = "Songs, artists, albums, folders"
            setHintTextColor(C.alpha(C.TEXT, 0.4f))
            setTextColor(C.TEXT)
            typeface = Fonts.bold
            setTextSize(TypedValue.COMPLEX_UNIT_PX, 24f.u)
            background = null
            isSingleLine = true
            imeOptions = EditorInfo.IME_ACTION_SEARCH or EditorInfo.IME_FLAG_NO_EXTRACT_UI or EditorInfo.IME_FLAG_NO_FULLSCREEN
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
                override fun afterTextChanged(s: Editable?) = query(s?.toString().orEmpty())
            })
            setOnEditorActionListener { _, id, e ->
                if (id == EditorInfo.IME_ACTION_SEARCH || e?.keyCode == KeyEvent.KEYCODE_ENTER) { hideKeyboard(); remember(text.toString()); true } else false
            }
        }
        val clear = ctx.icon(R.drawable.ic_close, 26, C.TEXT2).apply { pressable(0.9f) { field.setText(""); showKeyboard() } }
        val bar = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = rounded(0x14FFFFFF, 32)
            setPadding(24.u, 0, 20.u, 0)
            addView(ctx.icon(R.drawable.ic_search, 28, C.TEXT2), linear(28.u, 28.u, r = 16.u))
            addView(field, linear(0, MATCH, 1f))
            addView(clear, linear(26.u, 26.u))
        }
        root.addView(bar, linear(MATCH, 64.u))
        results = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL; setPadding(0, 10.u, 0, D.bottomChrome(mini = true) + 36.u) }
        val scroll = ScrollView(ctx).apply { isVerticalScrollBarEnabled = false; clipToPadding = false; addView(results) }
        root.addView(scroll, linear(MATCH, 0, 1f, t = 8.u))
        // Phones: keep results scrollable above the on-screen keyboard.
        androidx.core.view.ViewCompat.setOnApplyWindowInsetsListener(scroll) { v, insets ->
            val ime = insets.getInsets(androidx.core.view.WindowInsetsCompat.Type.ime()).bottom
            v.setPadding(0, 0, 0, (ime - D.bottomChrome(mini = true) - D.insetBottom).coerceAtLeast(0))
            insets
        }
        showRecent()
        return root
    }

    override fun onShow() {
        field.post { field.requestFocus(); showKeyboard() }
    }

    override fun onHide() = hideKeyboard()

    private fun showKeyboard() {
        ctx.getSystemService(InputMethodManager::class.java)?.showSoftInput(field, InputMethodManager.SHOW_IMPLICIT)
    }

    private fun hideKeyboard() {
        ctx.getSystemService(InputMethodManager::class.java)?.hideSoftInputFromWindow(field.windowToken, 0)
    }

    private fun recent(): List<String> = Prefs.raw().getString("recent_searches", "")!!.split('\n').filter { it.isNotBlank() }

    private fun remember(q: String) {
        val t = q.trim()
        if (t.length < 2) return
        val list = (listOf(t) + recent().filter { !it.equals(t, true) }).take(8)
        Prefs.raw().edit().putString("recent_searches", list.joinToString("\n")).apply()
    }

    private fun showRecent() {
        results.removeAllViews()
        val r = recent()
        if (r.isEmpty()) {
            val hint = if (D.handheld) "Type to search every song, album, artist and folder." else "Type to search everything on your drives. (Best done while parked.)"
            results.addView(ctx.text(hint, 18f, Fonts.semiBold, C.alpha(C.TEXT, 0.45f), lines = 3), linear(MATCH, WRAP, t = 24.u))
            return
        }
        results.addView(ctx.label("Recent searches"), linear(MATCH, WRAP, t = 18.u))
        val chips = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL }
        r.take(5).forEach { q ->
            chips.addView(PillButton(ctx, q, R.drawable.ic_search, 56, false, C.BEE).apply { pressable(0.95f) { field.setText(q); field.setSelection(q.length) } }, linear(WRAP, 56.u, r = 12.u))
        }
        results.addView(android.widget.HorizontalScrollView(ctx).apply { isHorizontalScrollBarEnabled = false; addView(chips) }, linear(MATCH, WRAP, t = 12.u))
    }

    private fun query(q: String) {
        job?.cancel()
        if (q.isBlank()) { showRecent(); return }
        job = scope.launch {
            delay(150)
            data class R(val tracks: List<Track>, val folders: List<FolderInfo>)
            val r = withContext(Dispatchers.IO) { R(Library.search(q, 60), Library.searchFolders(q, 8)) }
            render(q, r.tracks, r.folders)
        }
    }

    private fun render(q: String, tracks: List<Track>, folders: List<FolderInfo>) {
        results.removeAllViews()
        if (tracks.isEmpty() && folders.isEmpty()) {
            results.addView(ctx.text("Nothing matches “$q”.", 22f, Fonts.extraBold), linear(MATCH, WRAP, t = 30.u))
            results.addView(ctx.text("Bee checked every tape. Try fewer letters.", 17f, Fonts.semiBold, C.alpha(C.TEXT, 0.5f)), linear(MATCH, WRAP, t = 6.u))
            return
        }
        val accent = host.accent.accent
        tracks.firstOrNull()?.let { top ->
            results.addView(ctx.label("Top result"), linear(MATCH, WRAP, t = 18.u))
            val art = ImageView(ctx).apply { scaleType = ImageView.ScaleType.CENTER_CROP; roundCorners(18) }
            ArtLoader.bind(art, top, ArtLoader.Size.SMALL)
            val card = LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                background = rounded(C.alpha(accent, 0.14f), 26)
                setPadding(16.u, 0, 18.u, 0)
                val side = if (narrow) 84 else 100
                addView(art, linear(side.u, side.u, r = (if (narrow) 16 else 22).u))
                addView(LinearLayout(ctx).apply {
                    orientation = LinearLayout.VERTICAL
                    addView(ctx.text(top.title, if (narrow) 24f else 28f, Fonts.extraBold), linear(MATCH, WRAP))
                    addView(ctx.text(listOfNotNull(top.artist, top.album).joinToString(" · ").ifBlank { top.parent.substringAfterLast('/') }, if (narrow) 16f else 18f, Fonts.semiBold, C.TEXT2), linear(MATCH, WRAP, t = 4.u))
                }, linear(0, WRAP, 1f, r = 10.u))
                val playTop = { remember(q); hideKeyboard(); PlayerHub.play(tracks, 0, PlayContext("Search: $q", null)) }
                if (narrow) {
                    addView(com.teja.bumblebee.ui.design.CircleButton(ctx, R.drawable.ic_play, 64, 28, accent, if (C.isLight(accent)) C.INK else C.TEXT).apply {
                        contentDescription = "Play"
                        pressable { playTop() }
                    }, linear(64.u, 64.u))
                } else {
                    addView(PillButton(ctx, "Play", R.drawable.ic_play, 64, true, accent).apply { pressable { playTop() } }, linear(WRAP, 64.u))
                }
            }
            results.addView(card, linear(MATCH, (if (narrow) 116 else 132).u, t = 12.u))
        }
        if (folders.isNotEmpty()) {
            results.addView(ctx.label("Folders"), linear(MATCH, WRAP, t = 26.u))
            folders.forEach { f -> results.addView(row(ctx, f.name, Fmt.count(f.total, "song"), null, R.drawable.ic_folder) { remember(q); host.openFolder(f.path) }, linear(MATCH, 76.u)) }
        }
        val albums = tracks.filter { it.album != null }.distinctBy { it.album!!.lowercase() }.take(5)
        if (albums.isNotEmpty()) {
            results.addView(ctx.label("Albums"), linear(MATCH, WRAP, t = 26.u))
            albums.forEach { t -> results.addView(row(ctx, t.album!!, t.albumArtist ?: t.artist ?: "Album", t, null) { remember(q); host.openDetail(DetailScreen.album(host, t.album, t.albumArtist)) }, linear(MATCH, 76.u)) }
        }
        val artists = tracks.mapNotNull { it.artist }.distinctBy { it.lowercase() }.filter { it.contains(q.trim(), ignoreCase = true) }.take(5)
        if (artists.isNotEmpty()) {
            results.addView(ctx.label("Artists"), linear(MATCH, WRAP, t = 26.u))
            artists.forEach { a -> results.addView(row(ctx, a, "Artist", tracks.first { it.artist == a }, null, round = true) { remember(q); host.openDetail(DetailScreen.artist(host, a)) }, linear(MATCH, 76.u)) }
        }
        results.addView(ctx.label("Songs"), linear(MATCH, WRAP, t = 26.u))
        tracks.forEachIndexed { i, t ->
            results.addView(row(ctx, t.title, t.artistLabel, t, null) { remember(q); hideKeyboard(); PlayerHub.play(tracks, i, PlayContext("Search: $q", null)) }, linear(MATCH, 76.u))
        }
    }

    private fun row(ctx: Context, title: String, sub: String, art: Track?, iconRes: Int?, round: Boolean = false, onClick: () -> Unit): View {
        val lead: View = if (art != null) {
            ImageView(ctx).apply { scaleType = ImageView.ScaleType.CENTER_CROP; if (round) ovalClip() else roundCorners(12); ArtLoader.bind(this, art, ArtLoader.Size.SMALL) }
        } else {
            FrameLayout(ctx).apply {
                background = rounded(0x14FFFFFF, 12)
                addView(ctx.icon(iconRes ?: R.drawable.ic_music, 26, C.TEXT2), frame(26.u, 26.u, Gravity.CENTER))
            }
        }
        return LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(12.u, 0, 12.u, 0)
            addView(lead, linear(52.u, 52.u, r = 16.u))
            addView(LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                addView(ctx.text(title, 20f, Fonts.bold), linear(MATCH, WRAP))
                addView(ctx.text(sub, 16f, Fonts.semiBold, C.alpha(C.TEXT, 0.55f)), linear(MATCH, WRAP, t = 3.u))
            }, linear(0, WRAP, 1f))
            pressable(0.985f) { onClick() }
        }
    }
}
