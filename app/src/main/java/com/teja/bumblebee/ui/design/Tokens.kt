package com.teja.bumblebee.ui.design

import android.content.Context
import android.graphics.Color
import androidx.core.graphics.ColorUtils
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Design units. The whole UI is designed on a 1280×720 canvas; one unit is one design pixel scaled to
 * the real screen, so the layout matches the prototype whatever density the head unit reports.
 */
object D {
    var scale = 1f
        private set
    /** Real window size in pixels. */
    var screenW = 1280
        private set
    var screenH = 720
        private set
    /** Portrait panels (e.g. 768×1024 "tablet-style" head units) get stacked layouts and a bottom bar. */
    var portrait = false
        private set
    /** The window in design units. The short side is the design canvas (720 in landscape, 720 wide
     *  in portrait); the long side is whatever the screen gives (1280 on 16:9, 1920 on ultrawide…). */
    var designW = 1280
        private set
    var designH = 720
        private set

    /** Width of the navigation rail (landscape) or height of the bottom bar (portrait), design px. */
    const val RAIL = 132
    const val BAR = 112

    /** Content area (excluding rail / bottom bar) in design px. */
    val contentW: Int get() = if (portrait) designW else designW - RAIL
    val contentH: Int get() = if (portrait) designH - BAR else designH

    /** First guess from the application context; [init] with the activity refines it. */
    fun init(context: Context, fullScreen: Boolean = true) {
        val m = android.util.DisplayMetrics()
        if (fullScreen) {
            @Suppress("DEPRECATION")
            context.getSystemService(android.view.WindowManager::class.java).defaultDisplay.getRealMetrics(m)
        } else {
            m.setTo(context.resources.displayMetrics)
        }
        apply(m.widthPixels, m.heightPixels)
    }

    /**
     * Sizes against the activity's real window: the full window when the bars are hidden, the area
     * inside them otherwise, and the split-screen pane when the head unit runs apps side by side.
     */
    fun init(activity: android.app.Activity, fullScreen: Boolean) {
        if (android.os.Build.VERSION.SDK_INT >= 30) {
            val wm = activity.windowManager.currentWindowMetrics
            val b = wm.bounds
            if (fullScreen) {
                apply(b.width(), b.height())
            } else {
                val ins = wm.windowInsets.getInsetsIgnoringVisibility(android.view.WindowInsets.Type.systemBars())
                apply(b.width() - ins.left - ins.right, b.height() - ins.top - ins.bottom)
            }
        } else if (fullScreen && !activity.isInMultiWindowMode) {
            init(activity as Context, true)
        } else {
            val m = activity.resources.displayMetrics
            apply(m.widthPixels, m.heightPixels)
        }
    }

    private fun apply(w: Int, h: Int) {
        screenW = w
        screenH = h
        portrait = h > w
        scale = if (portrait) min(w / 720f, h / 1280f) else min(w / 1280f, h / 720f)
        designW = (w / scale).roundToInt()
        designH = (h / scale).roundToInt()
    }
}

val Int.u: Int get() = (this * D.scale).roundToInt()
val Float.u: Float get() = this * D.scale
val Double.u: Float get() = (this * D.scale).toFloat()

object C {
    const val BG = 0xFF07090C.toInt()
    const val TEXT = 0xFFF7F8FA.toInt()
    const val TEXT2 = 0xA8FFFFFF.toInt()
    const val TEXT3 = 0x80FFFFFF.toInt()
    const val SURF1 = 0x0FFFFFFF
    const val SURF2 = 0x1AFFFFFF
    const val SURF3 = 0x24FFFFFF
    const val HAIR = 0x12FFFFFF
    const val BEE = 0xFFFFC72C.toInt()
    const val INK = 0xFF0B0C0F.toInt()
    const val DANGER = 0xFFFF8A8A.toInt()

    fun alpha(color: Int, a: Float) = ColorUtils.setAlphaComponent(color, (a * 255).roundToInt().coerceIn(0, 255))
    fun blend(from: Int, to: Int, t: Float) = ColorUtils.blendARGB(from, to, t.coerceIn(0f, 1f))
    fun isLight(color: Int) = ColorUtils.calculateLuminance(color) > 0.5
    fun parse(hex: String) = Color.parseColor(hex)
}

/** Album-derived colours: [accent] for controls, [deep] for the backdrop base, [glowA]/[glowB] for ambient blobs. */
data class Accent(val accent: Int, val deep: Int, val glowA: Int, val glowB: Int) {
    fun blend(to: Accent, t: Float) = Accent(
        C.blend(accent, to.accent, t), C.blend(deep, to.deep, t), C.blend(glowA, to.glowA, t), C.blend(glowB, to.glowB, t),
    )

    companion object {
        val BEE = Accent(C.BEE, 0xFF15120A.toInt(), 0xFF5C3D00.toInt(), 0xFFFFC72C.toInt())
        val NEUTRAL = Accent(C.BEE, 0xFF0E1116.toInt(), 0xFF1D2733.toInt(), 0xFF3A4452.toInt())
    }
}
