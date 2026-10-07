package com.teja.bumblebee.util

import android.content.Context
import android.content.res.Configuration
import android.graphics.Point
import android.view.Surface
import android.view.WindowManager

/** What kind of screen we're on. Car head units are landscape-first; phones are portrait-first and small. */
object Device {
    @Volatile private var cached: Boolean? = null

    /**
     * True on phones (and other small, portrait-first devices). Head units report landscape as their
     * natural orientation, and tablets have a smallest width of 600dp or more.
     */
    fun isHandheld(context: Context): Boolean {
        cached?.let { return it }
        val cfg = context.resources.configuration
        val result = if ((cfg.uiMode and Configuration.UI_MODE_TYPE_MASK) == Configuration.UI_MODE_TYPE_CAR || cfg.smallestScreenWidthDp >= 600) {
            false
        } else {
            @Suppress("DEPRECATION")
            val display = context.getSystemService(WindowManager::class.java).defaultDisplay
            val real = Point().also { @Suppress("DEPRECATION") display.getRealSize(it) }
            val upright = display.rotation == Surface.ROTATION_0 || display.rotation == Surface.ROTATION_180
            // Natural orientation: how the screen is when rotation is 0.
            if (upright) real.y > real.x else real.x > real.y
        }
        cached = result
        return result
    }
}
