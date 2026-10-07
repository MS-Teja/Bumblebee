package com.teja.bumblebee.ui

import android.app.Activity
import android.graphics.Color
import android.os.Build
import android.view.WindowManager
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat

/**
 * Always draws edge to edge (the album backdrop runs under the bars; the shell pads its content by
 * the insets). Full screen hides the bars too — the default on head units; an edge swipe shows them.
 */
object Immersive {
    fun apply(activity: Activity, on: Boolean = true) {
        val window = activity.window
        WindowCompat.setDecorFitsSystemWindows(window, false)
        @Suppress("DEPRECATION")
        window.clearFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN)
        @Suppress("DEPRECATION")
        run {
            window.statusBarColor = Color.TRANSPARENT
            window.navigationBarColor = Color.TRANSPARENT
        }
        if (Build.VERSION.SDK_INT >= 29) {
            window.isNavigationBarContrastEnforced = false
            window.isStatusBarContrastEnforced = false
        }
        if (Build.VERSION.SDK_INT >= 28) {
            window.attributes = window.attributes.apply {
                layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
        }
        WindowInsetsControllerCompat(window, window.decorView).apply {
            isAppearanceLightStatusBars = false
            isAppearanceLightNavigationBars = false
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            if (on) hide(WindowInsetsCompat.Type.systemBars()) else show(WindowInsetsCompat.Type.systemBars())
        }
    }
}
