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
    var screenW = 1280
        private set
    var screenH = 720
        private set

    fun init(context: Context) {
        val m = context.resources.displayMetrics
        screenW = max(m.widthPixels, m.heightPixels)
        screenH = min(m.widthPixels, m.heightPixels)
        scale = min(screenW / 1280f, screenH / 720f)
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
