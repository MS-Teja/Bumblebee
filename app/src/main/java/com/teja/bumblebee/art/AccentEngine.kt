package com.teja.bumblebee.art

import android.graphics.Bitmap
import android.graphics.Color
import androidx.core.graphics.ColorUtils
import androidx.palette.graphics.Palette
import com.teja.bumblebee.ui.design.Accent
import com.teja.bumblebee.ui.design.C

/** Derives the UI colours from cover art: a readable accent plus dark tints for the ambient backdrop. */
object AccentEngine {

    fun fromBitmap(bitmap: Bitmap): Accent {
        val small = if (bitmap.width > 96) Bitmap.createScaledBitmap(bitmap, 64, 64, true) else bitmap
        val p = Palette.from(small).maximumColorCount(16).generate()
        val vibrant = p.vibrantSwatch?.rgb ?: p.lightVibrantSwatch?.rgb ?: p.mutedSwatch?.rgb ?: p.getDominantColor(C.BEE)
        val dominant = p.getDominantColor(vibrant)
        val second = p.darkVibrantSwatch?.rgb ?: p.darkMutedSwatch?.rgb ?: dominant
        return build(vibrant, dominant, second)
    }

    fun fromColors(a: Int, b: Int): Accent = build(b, a, a)

    private fun build(vibrant: Int, dominant: Int, second: Int): Accent {
        val accent = readable(vibrant)
        val deep = tone(dominant, lightness = 0.11f, satScale = 0.85f)
        val glowA = tone(vibrant, lightness = 0.45f, satScale = 1f)
        val glowB = tone(second, lightness = 0.28f, satScale = 0.9f)
        return Accent(accent, deep, glowA, glowB)
    }

    /** Lightens/saturates until the accent has at least 4.5:1 contrast on the near-black background. */
    private fun readable(color: Int): Int {
        val hsl = FloatArray(3)
        ColorUtils.colorToHSL(color, hsl)
        if (hsl[1] < 0.25f) hsl[1] = 0.25f + hsl[1] * 0.5f
        hsl[1] = hsl[1].coerceAtMost(0.95f)
        if (hsl[2] < 0.55f) hsl[2] = 0.55f
        var c = ColorUtils.HSLToColor(hsl)
        var guard = 0
        while (ColorUtils.calculateContrast(c, C.BG) < 4.5 && guard++ < 20) {
            hsl[2] = (hsl[2] + 0.04f).coerceAtMost(0.92f)
            c = ColorUtils.HSLToColor(hsl)
        }
        // Keep it from going pastel-white: cap lightness for very bright hues.
        if (hsl[2] > 0.86f) { hsl[2] = 0.82f; c = ColorUtils.HSLToColor(hsl) }
        return c or 0xFF000000.toInt()
    }

    private fun tone(color: Int, lightness: Float, satScale: Float): Int {
        val hsl = FloatArray(3)
        ColorUtils.colorToHSL(color, hsl)
        hsl[1] = (hsl[1] * satScale).coerceIn(0f, 0.9f)
        hsl[2] = lightness
        return ColorUtils.HSLToColor(hsl) or Color.BLACK
    }
}
