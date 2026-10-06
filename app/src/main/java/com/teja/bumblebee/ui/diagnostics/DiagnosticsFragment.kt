package com.teja.bumblebee.ui.diagnostics

import android.content.ActivityNotFoundException
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.Settings
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.teja.bumblebee.R
import com.teja.bumblebee.ui.design.Fonts
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/** "Under the hood": shows what the head unit really is and which path steering-wheel keys take. */
class DiagnosticsFragment : Fragment() {

    private lateinit var sectionsHost: LinearLayout
    private lateinit var logView: TextView
    private lateinit var keyButton: TextView
    private var probe: MediaKeyProbe? = null
    private var sections: List<DeviceReport.Section> = emptyList()
    private val logListener = { renderLog() }

    private val accent get() = ContextCompat.getColor(requireContext(), R.color.accent_fallback)

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, state: Bundle?): View {
        val ctx = requireContext()
        probe = MediaKeyProbe(ctx.applicationContext)

        val root = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            setBackgroundColor(ContextCompat.getColor(ctx, R.color.bg))
            setPadding(dp(24), dp(20), dp(24), dp(20))
        }

        // Left: the report (passenger side, read when parked).
        val left = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        left.addView(text("Under the hood", 34f, Fonts.extraBold(ctx), R.color.text_primary))
        left.addView(
            text("What this head unit really is, beyond the Settings screen.", 17f, Fonts.medium(ctx), R.color.text_secondary)
                .apply { setPadding(0, dp(2), 0, dp(12)) },
        )
        sectionsHost = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        left.addView(ScrollView(ctx).apply { isVerticalScrollBarEnabled = false; addView(sectionsHost) }, LinearLayout.LayoutParams(-1, 0, 1f))
        root.addView(left, LinearLayout.LayoutParams(0, -1, 1.6f))

        // Right: actions and the live log (driver side).
        val right = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), 0, 0, 0)
        }
        right.addView(button("Allow storage access", filled = false) { requestStorage() })
        keyButton = button("Start key test", filled = true) { toggleProbe() }
        right.addView(keyButton)
        right.addView(button("Save report to USB", filled = false) { saveReport() })
        val row = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL }
        row.addView(button("Refresh", filled = false) { refresh() }, LinearLayout.LayoutParams(0, -2, 1f).apply { marginEnd = dp(8) })
        row.addView(button("Exit", filled = false) { requireActivity().finishAndRemoveTask() }, LinearLayout.LayoutParams(0, -2, 1f))
        right.addView(row)
        right.addView(label("LIVE EVENTS").apply { setPadding(dp(4), dp(16), 0, dp(6)) })
        logView = text("", 14f, Fonts.medium(ctx), R.color.text_secondary).apply {
            setPadding(dp(16), dp(12), dp(16), dp(12))
            background = rounded(ContextCompat.getColor(ctx, R.color.surface1), 20)
            setLineSpacing(0f, 1.15f)
        }
        right.addView(ScrollView(ctx).apply { addView(logView) }, LinearLayout.LayoutParams(-1, 0, 1f))
        root.addView(right, LinearLayout.LayoutParams(0, -1, 1f))

        return root
    }

    override fun onStart() {
        super.onStart()
        KeyLog.listen(logListener)
        renderLog()
        refresh()
    }

    override fun onStop() {
        KeyLog.unlisten(logListener)
        super.onStop()
    }

    override fun onDestroy() {
        probe?.stop()
        super.onDestroy()
    }

    private fun refresh() {
        viewLifecycleOwner.lifecycleScope.launch {
            sections = withContext(Dispatchers.IO) { DeviceReport.collect(requireContext().applicationContext) }
            renderSections()
        }
    }

    private fun renderSections() {
        val ctx = requireContext()
        sectionsHost.removeAllViews()
        sections.forEach { s ->
            sectionsHost.addView(label(s.title.uppercase()).apply { setPadding(dp(4), dp(14), 0, dp(6)) })
            val card = LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                background = rounded(ContextCompat.getColor(ctx, R.color.surface1), 24)
                setPadding(dp(20), dp(8), dp(20), dp(8))
            }
            s.rows.forEach { r -> card.addView(rowView(r)) }
            sectionsHost.addView(card)
        }
    }

    private fun rowView(r: DeviceReport.Row): View {
        val ctx = requireContext()
        return LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(8), 0, dp(8))
            val dot = View(ctx).apply {
                background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(
                        when (r.level) {
                            DeviceReport.Level.OK -> ContextCompat.getColor(ctx, R.color.ok)
                            DeviceReport.Level.WARN -> ContextCompat.getColor(ctx, R.color.warn)
                            DeviceReport.Level.BAD -> ContextCompat.getColor(ctx, R.color.bad)
                            DeviceReport.Level.INFO -> Color.TRANSPARENT
                        },
                    )
                }
            }
            addView(dot, LinearLayout.LayoutParams(dp(10), dp(10)).apply { marginEnd = dp(12) })
            addView(text(r.key, 16f, Fonts.semiBold(ctx), R.color.text_secondary), LinearLayout.LayoutParams(dp(230), -2))
            addView(
                text(r.value, 17f, Fonts.semiBold(ctx), R.color.text_primary).apply { setTextIsSelectable(true) },
                LinearLayout.LayoutParams(0, -2, 1f),
            )
        }
    }

    private fun renderLog() {
        val lines = KeyLog.snapshot()
        logView.text = if (lines.isEmpty()) {
            "Tap “Start key test”, then press every steering-wheel and panel button. " +
                "Also try a navigation prompt and a phone call to see how audio focus behaves."
        } else {
            lines.joinToString("\n")
        }
    }

    private fun toggleProbe() {
        val p = probe ?: return
        if (p.isRunning) p.stop() else p.start()
        keyButton.text = if (p.isRunning) "Stop key test" else "Start key test"
    }

    private fun requestStorage() {
        val ctx = requireContext()
        if (Build.VERSION.SDK_INT >= 30) {
            val specific = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, Uri.parse("package:${ctx.packageName}"))
            val general = Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)
            try {
                startActivity(specific)
            } catch (_: ActivityNotFoundException) {
                try {
                    startActivity(general)
                } catch (_: ActivityNotFoundException) {
                    KeyLog.add("No all-files-access screen on this unit. Use: adb shell appops set ${ctx.packageName} MANAGE_EXTERNAL_STORAGE allow")
                }
            }
        } else {
            requestPermissions(
                arrayOf(android.Manifest.permission.READ_EXTERNAL_STORAGE, android.Manifest.permission.WRITE_EXTERNAL_STORAGE),
                1,
            )
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        refresh()
    }

    private fun saveReport() {
        val ctx = requireContext().applicationContext
        val report = DeviceReport.asText(sections, KeyLog.snapshot())
        viewLifecycleOwner.lifecycleScope.launch {
            val saved = withContext(Dispatchers.IO) {
                val targets = buildList {
                    val sm = ctx.getSystemService(android.os.storage.StorageManager::class.java)
                    sm.storageVolumes.filter { it.isRemovable }.mapNotNull(DeviceReport::volumePath).forEach(::add)
                    @Suppress("DEPRECATION")
                    add(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS))
                    ctx.getExternalFilesDir(null)?.let(::add)
                }
                targets.mapNotNull { dir ->
                    runCatching { File(dir, "Bumblebee-under-the-hood.txt").apply { writeText(report) }.path }.getOrNull()
                }
            }
            val message = if (saved.isEmpty()) "Couldn't save the report. Allow storage access first." else "Saved:\n${saved.joinToString("\n")}"
            KeyLog.add(message.replace("\n", "  "))
            Toast.makeText(ctx, message, Toast.LENGTH_LONG).show()
        }
    }

    // --- tiny view helpers (the real design system arrives with the prototype) ---

    private fun text(value: String, sp: Float, face: android.graphics.Typeface, color: Int) = TextView(requireContext()).apply {
        text = value
        typeface = face
        setTextSize(TypedValue.COMPLEX_UNIT_SP, sp)
        setTextColor(ContextCompat.getColor(requireContext(), color))
        includeFontPadding = false
    }

    private fun label(value: String) = text(value, 14f, Fonts.semiBold(requireContext()), R.color.text_tertiary).apply {
        letterSpacing = 0.08f
    }

    private fun button(title: String, filled: Boolean, onClick: () -> Unit): TextView {
        val ctx = requireContext()
        return text(title, 18f, Fonts.bold(ctx), if (filled) R.color.bg else R.color.text_primary).apply {
            gravity = Gravity.CENTER
            minHeight = dp(64)
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            setPadding(dp(20), 0, dp(20), 0)
            background = rounded(if (filled) accent else ContextCompat.getColor(ctx, R.color.surface2), 32)
            layoutParams = LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(10) }
            isClickable = true
            setOnClickListener { onClick() }
            setOnTouchListener { v, e ->
                when (e.actionMasked) {
                    android.view.MotionEvent.ACTION_DOWN -> v.animate().scaleX(0.96f).scaleY(0.96f).setDuration(90).start()
                    android.view.MotionEvent.ACTION_UP, android.view.MotionEvent.ACTION_CANCEL ->
                        v.animate().scaleX(1f).scaleY(1f).setDuration(160).start()
                }
                false
            }
        }
    }

    private fun rounded(color: Int, radiusDp: Int) = GradientDrawable().apply {
        setColor(color)
        cornerRadius = dp(radiusDp).toFloat()
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
}
