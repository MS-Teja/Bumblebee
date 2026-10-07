package com.teja.bumblebee.ui.design

import android.content.Context
import android.graphics.Color
import androidx.core.graphics.ColorUtils
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Design units. The UI is designed on a 1280×720 canvas (720×1280 in portrait); one unit is one
 * design pixel scaled to the real window, so the car layout matches the prototype whatever density
 * the head unit reports. Phones are held closer than a dashboard screen, so there a unit is pinned
 * to a physical size instead and the canvas simply becomes narrower (≈530 units on a typical phone).
 */
object D {
    var scale = 1f
        private set
    /** Usable window size in pixels (inside the system bars when they are shown). */
    var screenW = 1280
        private set
    var screenH = 720
        private set
    /** Portrait windows get stacked layouts and a bottom bar. */
    var portrait = false
        private set
    /** Phone-like device: physical sizing, system bars visible, a full-screen player in portrait. */
    var handheld = false
        private set
    /** The usable window in design units. */
    var designW = 1280
        private set
    var designH = 720
        private set

    /** System bar / cutout insets in real pixels; the shell pads by them (all 0 in full screen). */
    var insetLeft = 0
        private set
    var insetTop = 0
        private set
    var insetRight = 0
        private set
    var insetBottom = 0
        private set

    /** Width of the navigation rail (landscape), design px. */
    const val RAIL = 132
    /** Height of the bottom bar (portrait), design px. */
    val BAR: Int get() = if (handheld) 100 else 112
    /** Height of the mini-player, design px. */
    val MINI: Int get() = if (handheld) 96 else 104

    /** Content area (excluding rail / bottom bar) in design px. */
    val contentW: Int get() = if (portrait) designW else designW - RAIL
    val contentH: Int get() = if (portrait) designH - BAR else designH
    /** Narrow content (phones in portrait): screens drop secondary buttons and stack rows. */
    val narrow: Boolean get() = contentW < 700

    /** Real pixels a scrolling list must keep clear at the bottom (bottom bar, mini-player, nav bar). */
    fun bottomChrome(mini: Boolean): Int =
        (if (portrait) BAR.u + insetBottom else 0) + (if (mini) MINI.u else 0)

    /** First guess from the application context; [init] with the activity refines it. */
    fun init(context: Context, fullScreen: Boolean = true) {
        handheld = com.teja.bumblebee.util.Device.isHandheld(context)
        val m = android.util.DisplayMetrics()
        @Suppress("DEPRECATION")
        context.getSystemService(android.view.WindowManager::class.java).defaultDisplay.getRealMetrics(m)
        setInsets(0, 0, 0, 0)
        apply(m.widthPixels, m.heightPixels, m.density)
    }

    /**
     * Sizes against the activity's real window: the full window when the bars are hidden, the area
     * inside them otherwise, and the split-screen pane when apps run side by side.
     */
    fun init(activity: android.app.Activity, fullScreen: Boolean) {
        handheld = com.teja.bumblebee.util.Device.isHandheld(activity)
        val density = activity.resources.displayMetrics.density
        if (android.os.Build.VERSION.SDK_INT >= 30) {
            val wm = activity.windowManager.currentWindowMetrics
            val b = wm.bounds
            val ins = if (fullScreen) {
                wm.windowInsets.getInsets(android.view.WindowInsets.Type.displayCutout())
            } else {
                wm.windowInsets.getInsetsIgnoringVisibility(android.view.WindowInsets.Type.systemBars() or android.view.WindowInsets.Type.displayCutout())
            }
            setInsets(ins.left, ins.top, ins.right, ins.bottom)
            apply(b.width() - ins.left - ins.right, b.height() - ins.top - ins.bottom, density)
        } else if (activity.isInMultiWindowMode) {
            val m = activity.resources.displayMetrics
            setInsets(0, 0, 0, 0)
            apply(m.widthPixels, m.heightPixels, density)
        } else {
            @Suppress("DEPRECATION")
            val display = activity.windowManager.defaultDisplay
            val real = android.graphics.Point().also { @Suppress("DEPRECATION") display.getRealSize(it) }
            if (fullScreen) {
                setInsets(0, 0, 0, 0)
            } else {
                // Pre-11: the app area excludes the navigation bar; the status bar height is a resource.
                val app = android.graphics.Point().also { @Suppress("DEPRECATION") display.getSize(it) }
                val res = activity.resources
                val id = res.getIdentifier("status_bar_height", "dimen", "android")
                val status = if (id > 0) res.getDimensionPixelSize(id) else 0
                setInsets(0, status, (real.x - app.x).coerceAtLeast(0), (real.y - app.y).coerceAtLeast(0))
            }
            apply(real.x - insetLeft - insetRight, real.y - insetTop - insetBottom, density)
        }
    }

    private fun setInsets(l: Int, t: Int, r: Int, b: Int) {
        insetLeft = l; insetTop = t; insetRight = r; insetBottom = b
    }

    private fun apply(w: Int, h: Int, density: Float) {
        screenW = w
        screenH = h
        portrait = h > w
        val fit = if (portrait) min(w / 720f, h / 1280f) else min(w / 1280f, h / 720f)
        // Phones: one unit ≈ 0.78dp, so row text lands at 15–16sp and buttons stay thumb-sized.
        scale = if (handheld) max(fit, density * 0.78f) else fit
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
