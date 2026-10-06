package com.teja.bumblebee.ui

import android.app.Activity
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat

/** Hides the head unit's system bars; an edge swipe reveals them briefly. */
object Immersive {
    fun apply(activity: Activity, on: Boolean = true) {
        val window = activity.window
        WindowCompat.setDecorFitsSystemWindows(window, !on)
        WindowInsetsControllerCompat(window, window.decorView).apply {
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            if (on) hide(WindowInsetsCompat.Type.systemBars()) else show(WindowInsetsCompat.Type.systemBars())
        }
    }
}
