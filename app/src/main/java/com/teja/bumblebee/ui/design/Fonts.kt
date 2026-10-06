package com.teja.bumblebee.ui.design

import android.content.Context
import android.graphics.Typeface
import androidx.core.content.res.ResourcesCompat
import com.teja.bumblebee.R

/** Manrope weights, loaded once. Loaded in code because XML font families need API 26 without AppCompat. */
object Fonts {
    private var cache: Array<Typeface?> = arrayOfNulls(4)

    fun medium(ctx: Context) = load(ctx, 0, R.font.manrope_500)
    fun semiBold(ctx: Context) = load(ctx, 1, R.font.manrope_600)
    fun bold(ctx: Context) = load(ctx, 2, R.font.manrope_700)
    fun extraBold(ctx: Context) = load(ctx, 3, R.font.manrope_800)

    private fun load(ctx: Context, slot: Int, res: Int): Typeface =
        cache[slot] ?: (ResourcesCompat.getFont(ctx.applicationContext, res) ?: Typeface.DEFAULT).also { cache[slot] = it }
}
