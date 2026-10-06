package com.teja.bumblebee.ui.design

import android.content.Context
import android.graphics.Typeface
import androidx.core.content.res.ResourcesCompat
import com.teja.bumblebee.R

/** Manrope weights + a marker face for cassette labels. Loaded in code: XML font families need API 26 without AppCompat. */
object Fonts {
    private val cache = arrayOfNulls<Typeface>(5)
    private lateinit var app: Context

    fun init(context: Context) { app = context.applicationContext }

    val medium: Typeface get() = load(0, R.font.manrope_500)
    val semiBold: Typeface get() = load(1, R.font.manrope_600)
    val bold: Typeface get() = load(2, R.font.manrope_700)
    val extraBold: Typeface get() = load(3, R.font.manrope_800)
    val marker: Typeface get() = load(4, R.font.permanent_marker)

    // Kept for the Diagnostics screen's older call style.
    fun medium(ctx: Context) = medium
    fun semiBold(ctx: Context) = semiBold
    fun bold(ctx: Context) = bold
    fun extraBold(ctx: Context) = extraBold

    private fun load(slot: Int, res: Int): Typeface =
        cache[slot] ?: (ResourcesCompat.getFont(app, res) ?: Typeface.DEFAULT).also { cache[slot] = it }
}
