package com.teja.bumblebee.ui.settings

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.dynamicanimation.animation.DynamicAnimation
import androidx.dynamicanimation.animation.SpringAnimation
import androidx.dynamicanimation.animation.SpringForce
import com.teja.bumblebee.BuildConfig
import com.teja.bumblebee.R
import com.teja.bumblebee.data.Library
import com.teja.bumblebee.data.Prefs
import com.teja.bumblebee.eggs.EasterEggs
import com.teja.bumblebee.index.Indexer
import com.teja.bumblebee.ui.MainActivity
import com.teja.bumblebee.ui.Immersive
import com.teja.bumblebee.ui.design.C
import com.teja.bumblebee.ui.design.Fonts
import com.teja.bumblebee.ui.design.MATCH
import com.teja.bumblebee.ui.design.PillButton
import com.teja.bumblebee.ui.design.WRAP
import com.teja.bumblebee.ui.design.frame
import com.teja.bumblebee.ui.design.label
import com.teja.bumblebee.ui.design.linear
import com.teja.bumblebee.ui.design.pressable
import com.teja.bumblebee.ui.design.rounded
import com.teja.bumblebee.ui.design.text
import com.teja.bumblebee.ui.design.u
import com.teja.bumblebee.ui.shell.Screen
import com.teja.bumblebee.ui.shell.Tab

class SettingsScreen(host: MainActivity) : Screen(host) {
    override val tab = Tab.SETTINGS

    private lateinit var body: LinearLayout
    private var logoTaps = 0

    override fun build(): View {
        body = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL; setPadding(40.u, 30.u, 36.u, 140.u) }
        render()
        return ScrollView(ctx).apply { isVerticalScrollBarEnabled = false; addView(body) }
    }

    private fun render() {
        body.removeAllViews()
        body.addView(ctx.text("Settings", 32f, Fonts.extraBold).apply { letterSpacing = -0.02f }, linear(MATCH, WRAP))
        val accent = host.accent.accent

        group("Layout") {
            choice("Driver side", "Rail and controls sit closest to you", listOf("Right" to true, "Left" to false), Prefs.driverRight) {
                Prefs.driverRight = it; host.relayoutForDriverSide()
            }
            toggle("Full screen", "Hide the head unit's bars (swipe from the edge to show them)", Prefs.immersive) {
                Prefs.immersive = it; Immersive.apply(host, it)
                // The usable area changes size, so rebuild the layout at the new scale.
                host.window.decorView.postDelayed({ host.recreate() }, 250)
            }
            choice("Glance mode", "Big, calm driving view after no touches", listOf("Off" to 0, "10 s" to 10, "20 s" to 20, "30 s" to 30), Prefs.glanceDelaySec) { Prefs.glanceDelaySec = it }
            toggle("Reduce motion", "Swap animations for quick fades", Prefs.reduceMotion) { Prefs.reduceMotion = it }
        }
        group("Playback") {
            choice("On startup", null, listOf("Resume playing" to "play", "Resume paused" to "paused", "Don't resume" to "off"), Prefs.onStartup) { Prefs.onStartup = it }
            choice("When a USB drive is inserted", null, listOf("Ask" to "ask", "Play it" to "play", "Do nothing" to "none"), Prefs.onUsb) { Prefs.onUsb = it }
            choice("Tapping a song in a folder plays", null, listOf("This folder" to false, "Folder + subfolders" to true), Prefs.tapIncludesSubfolders) { Prefs.tapIncludesSubfolders = it }
        }
        group("Library") {
            choice("Hide short tracks", "Skips voice notes and ringtones", listOf("Off" to 0, "15 s" to 15, "30 s" to 30), Prefs.minDurationSec) {
                Prefs.minDurationSec = it; Library.changed()
            }
            toggle("Respect .nomedia", "Skip folders that ask to be hidden", Prefs.respectNomedia) { Prefs.respectNomedia = it; Indexer.scanAll(force = true) }
            toggle("Prefer folder cover image", "Use cover.jpg / folder.jpg over embedded art", Prefs.preferFolderArt) { Prefs.preferFolderArt = it }
            Prefs.excluded.sorted().forEach { path ->
                action("Hidden: ${path.substringAfterLast('/')}", path, "Unhide") {
                    Prefs.excluded = Prefs.excluded - path; Library.changed(); render()
                }
            }
            action("Rescan library", "Re-reads every drive for new or changed songs", "Rescan") {
                Indexer.scanAll(force = true); host.toast("Rewinding the tapes…")
            }
        }
        group("Fun") {
            toggle("Easter eggs", "Bee talks through songs, now and then", Prefs.easterEggs) { Prefs.easterEggs = it }
        }

        // About: tap the logo seven times.
        body.addView(ctx.label("About"), linear(MATCH, WRAP, t = 30.u))
        val logo = BeeLogo(ctx).apply {
            pressable(0.9f) {
                logoTaps++
                if (logoTaps >= 7) {
                    logoTaps = 0
                    Prefs.camaro = !Prefs.camaro
                    host.applyAccent(host.accent, animate = true)
                    host.toast(if (Prefs.camaro) "Camaro mode unlocked" else "Camaro mode off", C.BEE)
                } else if (logoTaps >= 4) {
                    host.toast("${7 - logoTaps} more…")
                }
            }
        }
        val about = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = rounded(0x0FFFFFFF, 24)
            setPadding(22.u, 18.u, 22.u, 18.u)
            addView(logo, linear(88.u, 88.u, r = 22.u))
            addView(LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                addView(ctx.text("Bumblebee", 26f, Fonts.extraBold), linear(MATCH, WRAP))
                addView(ctx.text("Version ${BuildConfig.VERSION_NAME}" + if (Prefs.camaro) " · Camaro mode" else "", 16f, Fonts.semiBold, C.TEXT2), linear(MATCH, WRAP, t = 4.u))
                addView(ctx.text(EasterEggs.listeningStats(), 16f, Fonts.semiBold, C.alpha(accent, 0.9f)), linear(MATCH, WRAP, t = 6.u))
            }, linear(0, WRAP, 1f))
            addView(PillButton(ctx, "Under the hood", R.drawable.ic_settings, 64, false, accent).apply {
                pressable { host.push(DiagnosticsScreen(host)) }
            }, linear(WRAP, 64.u))
        }
        body.addView(about, linear(MATCH, WRAP, t = 12.u))
    }

    // ---------------------------------------------------------------- row builders

    private fun group(title: String, rows: LinearLayout.() -> Unit) {
        body.addView(ctx.label(title), linear(MATCH, WRAP, t = 30.u))
        val card = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            background = rounded(0x0FFFFFFF, 24)
            setPadding(22.u, 6.u, 22.u, 6.u)
        }
        card.rows()
        body.addView(card, linear(MATCH, WRAP, t = 12.u))
    }

    private fun titles(title: String, sub: String?) = LinearLayout(ctx).apply {
        orientation = LinearLayout.VERTICAL
        addView(ctx.text(title, 20f, Fonts.bold), linear(MATCH, WRAP))
        sub?.let { addView(ctx.text(it, 15f, Fonts.semiBold, C.alpha(C.TEXT, 0.5f), lines = 2), linear(MATCH, WRAP, t = 3.u)) }
    }

    private fun LinearLayout.toggle(title: String, sub: String?, value: Boolean, onChange: (Boolean) -> Unit) {
        val sw = BeeSwitch(ctx, value, host.accent.accent)
        val row = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, 14.u, 0, 14.u)
            addView(titles(title, sub), linear(0, WRAP, 1f, r = 20.u))
            addView(sw, linear(84.u, 48.u))
            pressable(0.99f) { sw.set(!sw.on); onChange(sw.on) }
        }
        addView(row, linear(MATCH, WRAP))
    }

    private fun <T> LinearLayout.choice(title: String, sub: String?, options: List<Pair<String, T>>, value: T, onChange: (T) -> Unit) {
        val accent = host.accent.accent
        val chips = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL }
        val views = ArrayList<TextView>()
        fun paint(sel: T) = views.forEachIndexed { i, tv ->
            val on = options[i].second == sel
            tv.background = rounded(if (on) accent else 0x14FFFFFF, 26)
            tv.setTextColor(if (on) (if (C.isLight(accent)) C.INK else C.TEXT) else C.TEXT)
        }
        options.forEach { (label, v) ->
            val tv = ctx.text(label, 17f, Fonts.extraBold).apply {
                gravity = Gravity.CENTER
                setPadding(22.u, 0, 22.u, 0)
                pressable(0.95f) { paint(v); onChange(v) }
            }
            views += tv
            chips.addView(tv, linear(WRAP, 56.u, l = 8.u))
        }
        paint(value)
        // Narrow screens: options wrap under the title instead of squeezing it.
        val narrow = com.teja.bumblebee.ui.design.D.contentW < 1000
        val row = LinearLayout(ctx).apply {
            orientation = if (narrow) LinearLayout.VERTICAL else LinearLayout.HORIZONTAL
            gravity = if (narrow) Gravity.START else Gravity.CENTER_VERTICAL
            setPadding(0, 12.u, 0, 12.u)
            addView(titles(title, sub), if (narrow) linear(MATCH, WRAP, b = 10.u) else linear(0, WRAP, 1f, r = 16.u))
            if (narrow) {
                (chips.getChildAt(0)?.layoutParams as? LinearLayout.LayoutParams)?.leftMargin = 0
                addView(android.widget.HorizontalScrollView(ctx).apply { isHorizontalScrollBarEnabled = false; addView(chips) }, linear(MATCH, WRAP))
            } else {
                addView(chips, linear(WRAP, WRAP))
            }
        }
        addView(row, linear(MATCH, WRAP))
    }

    private fun LinearLayout.action(title: String, sub: String?, button: String, onClick: () -> Unit) {
        val row = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, 12.u, 0, 12.u)
            addView(titles(title, sub), linear(0, WRAP, 1f, r = 16.u))
            addView(PillButton(ctx, button, null, 56, false, C.BEE).apply { pressable { onClick() } }, linear(WRAP, 56.u))
        }
        addView(row, linear(MATCH, WRAP))
    }
}

/** Big pill switch with a springy thumb. */
class BeeSwitch(ctx: Context, initial: Boolean, private val accent: Int) : FrameLayout(ctx) {
    var on = initial
        private set
    private val track = View(ctx)
    private val thumb = View(ctx).apply { background = com.teja.bumblebee.ui.design.circle(C.TEXT) }
    private val spring = SpringAnimation(thumb, DynamicAnimation.TRANSLATION_X).apply { spring = SpringForce().setStiffness(700f).setDampingRatio(0.6f) }

    init {
        addView(track, frame(MATCH, MATCH))
        addView(thumb, frame(38.u, 38.u, Gravity.CENTER_VERTICAL, l = 5.u))
        apply(false)
    }

    fun set(v: Boolean) { on = v; apply(true) }

    private fun apply(animate: Boolean) {
        track.background = rounded(if (on) accent else 0x29FFFFFF, 24)
        val x = if (on) 36f.u else 0f
        if (animate) spring.animateToFinalPosition(x) else thumb.translationX = x
        thumb.background = com.teja.bumblebee.ui.design.circle(if (on && C.isLight(accent)) C.INK else C.TEXT)
    }
}

/** The app mark: yellow badge, twin black racing stripes, a play triangle. */
class BeeLogo(ctx: Context) : View(ctx) {
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        p.color = if (com.teja.bumblebee.data.Prefs.camaro) 0xFFFFD000.toInt() else C.BEE
        canvas.drawRoundRect(0f, 0f, w, h, w * 0.26f, w * 0.26f, p)
        p.color = 0xFF14161A.toInt()
        canvas.drawRect(w * 0.37f, 0f, w * 0.44f, h, p)
        canvas.drawRect(w * 0.56f, 0f, w * 0.63f, h, p)
        p.color = C.BEE
        canvas.drawCircle(w / 2, h / 2, w * 0.24f, p)
        p.color = 0xFF14161A.toInt()
        val path = android.graphics.Path().apply {
            moveTo(w * 0.44f, h * 0.38f); lineTo(w * 0.62f, h * 0.5f); lineTo(w * 0.44f, h * 0.62f); close()
        }
        canvas.drawPath(path, p)
    }
}
