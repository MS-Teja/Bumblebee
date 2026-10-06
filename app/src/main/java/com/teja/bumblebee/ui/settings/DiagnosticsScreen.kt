package com.teja.bumblebee.ui.settings

import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Environment
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.teja.bumblebee.storage.Volumes
import com.teja.bumblebee.ui.MainActivity
import com.teja.bumblebee.ui.design.C
import com.teja.bumblebee.ui.design.Fonts
import com.teja.bumblebee.ui.design.MATCH
import com.teja.bumblebee.ui.design.PillButton
import com.teja.bumblebee.ui.design.WRAP
import com.teja.bumblebee.ui.design.label
import com.teja.bumblebee.ui.design.linear
import com.teja.bumblebee.ui.design.pressable
import com.teja.bumblebee.ui.design.rounded
import com.teja.bumblebee.ui.design.text
import com.teja.bumblebee.ui.design.u
import com.teja.bumblebee.ui.diagnostics.DeviceReport
import com.teja.bumblebee.ui.diagnostics.KeyLog
import com.teja.bumblebee.ui.diagnostics.MediaKeyProbe
import com.teja.bumblebee.ui.shell.Screen
import com.teja.bumblebee.ui.shell.Tab
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/** "Under the hood": what the head unit really is, and which path steering-wheel keys take. */
class DiagnosticsScreen(host: MainActivity) : Screen(host) {
    override val tab = Tab.SETTINGS
    override val showsMiniPlayer = false

    private lateinit var sectionsHost: LinearLayout
    private lateinit var logView: TextView
    private lateinit var keyButton: PillButton
    private val probe = MediaKeyProbe(host.applicationContext)
    private var sections: List<DeviceReport.Section> = emptyList()
    private val logListener = { renderLog() }

    override fun build(): View {
        val root = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL; setPadding(40.u, 26.u, 30.u, 20.u) }
        val left = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        left.addView(ctx.text("‹  Settings", 18f, Fonts.bold, C.alpha(C.TEXT, 0.6f)).apply { setPadding(0, 8.u, 0, 8.u); pressable(0.95f) { host.pop() } })
        left.addView(ctx.text("Under the hood", 34f, Fonts.extraBold), linear(MATCH, WRAP, t = 4.u))
        left.addView(ctx.text("What this head unit really is, beyond its Settings screen.", 17f, Fonts.medium, C.TEXT2), linear(MATCH, WRAP, t = 4.u, b = 8.u))
        sectionsHost = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        left.addView(ScrollView(ctx).apply { isVerticalScrollBarEnabled = false; addView(sectionsHost) }, linear(MATCH, 0, 1f))
        root.addView(left, linear(0, MATCH, 1.6f))

        val right = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL; setPadding(24.u, 0, 0, 0) }
        right.addView(PillButton(ctx, "Allow storage access", null, 64, false, C.BEE).apply { pressable { host.requestStorage() } }, linear(MATCH, 64.u, b = 10.u))
        keyButton = PillButton(ctx, "Start key test", null, 64, true, C.BEE).apply { pressable { toggleProbe() } }
        right.addView(keyButton, linear(MATCH, 64.u, b = 10.u))
        right.addView(PillButton(ctx, "Save report to USB", null, 64, false, C.BEE).apply { pressable { save() } }, linear(MATCH, 64.u, b = 10.u))
        right.addView(ctx.label("Live events"), linear(MATCH, WRAP, t = 10.u, b = 6.u))
        logView = ctx.text("", 14f, Fonts.medium, C.TEXT2, lines = 400).apply {
            setPadding(16.u, 12.u, 16.u, 12.u)
            background = rounded(C.SURF1, 20)
            setLineSpacing(0f, 1.15f)
        }
        right.addView(ScrollView(ctx).apply { addView(logView) }, linear(MATCH, 0, 1f))
        root.addView(right, linear(0, MATCH, 1f))
        return root
    }

    override fun onShow() {
        KeyLog.listen(logListener)
        renderLog()
        launchVisible {
            sections = withContext(Dispatchers.IO) { DeviceReport.collect(host.applicationContext) }
            renderSections()
        }
    }

    override fun onHide() { KeyLog.unlisten(logListener) }
    override fun onDestroy() { probe.stop() }

    private fun renderSections() {
        sectionsHost.removeAllViews()
        sections.forEach { s ->
            sectionsHost.addView(ctx.label(s.title), linear(MATCH, WRAP, t = 14.u, b = 6.u))
            val card = LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                background = rounded(C.SURF1, 24)
                setPadding(20.u, 8.u, 20.u, 8.u)
            }
            s.rows.forEach { r ->
                card.addView(LinearLayout(ctx).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    setPadding(0, 8.u, 0, 8.u)
                    addView(View(ctx).apply {
                        background = GradientDrawable().apply {
                            shape = GradientDrawable.OVAL
                            setColor(when (r.level) {
                                DeviceReport.Level.OK -> 0xFF5BD68A.toInt()
                                DeviceReport.Level.WARN -> 0xFFFFB547.toInt()
                                DeviceReport.Level.BAD -> 0xFFFF6B6B.toInt()
                                DeviceReport.Level.INFO -> Color.TRANSPARENT
                            })
                        }
                    }, linear(10.u, 10.u, r = 12.u))
                    addView(ctx.text(r.key, 16f, Fonts.semiBold, C.TEXT2), linear(230.u, WRAP))
                    addView(ctx.text(r.value, 17f, Fonts.semiBold, C.TEXT, lines = 20).apply { setTextIsSelectable(true) }, linear(0, WRAP, 1f))
                })
            }
            sectionsHost.addView(card)
        }
    }

    private fun renderLog() {
        val lines = KeyLog.snapshot()
        logView.text = if (lines.isEmpty()) {
            "Tap “Start key test”, then press every steering-wheel and panel button. Also try a navigation prompt and a phone call to see how audio focus behaves."
        } else lines.joinToString("\n")
    }

    private fun toggleProbe() {
        if (probe.isRunning) probe.stop() else probe.start()
        keyButton.title.text = if (probe.isRunning) "Stop key test" else "Start key test"
    }

    private fun save() {
        val report = DeviceReport.asText(sections, KeyLog.snapshot())
        scope.launch {
            val saved = withContext(Dispatchers.IO) {
                val targets = buildList {
                    Volumes.all.value.filter { it.removable }.forEach { add(it.root) }
                    @Suppress("DEPRECATION")
                    add(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS))
                    host.getExternalFilesDir(null)?.let(::add)
                }
                targets.mapNotNull { dir -> runCatching { File(dir, "Bumblebee-under-the-hood.txt").apply { writeText(report) }.path }.getOrNull() }
            }
            host.toast(if (saved.isEmpty()) "Couldn't save. Allow storage access first." else "Saved to ${saved.first()}")
            KeyLog.add("Report saved: ${saved.joinToString()}")
        }
    }
}
