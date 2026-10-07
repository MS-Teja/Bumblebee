package com.teja.bumblebee.ui.shell

import android.content.Context
import android.view.View
import com.teja.bumblebee.ui.MainActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * A screen is a plain View tree plus lifecycle hooks; the shell owns transitions between them.
 * (No fragments: full control over motion and less overhead on a slow device.)
 */
abstract class Screen(val host: MainActivity) {
    val ctx: Context get() = host
    val view: View by lazy { build() }
    /** Lives while the screen is visible; cancelled on hide. */
    var visibleScope: CoroutineScope = MainScope().also { it.cancel() }
        private set
    /** Lives as long as the screen exists. */
    val scope: CoroutineScope = MainScope()

    open val showsMiniPlayer: Boolean = true
    open val backdropIntensity: Float = 0.42f
    open val tab: Tab = Tab.HOME
    /** The big artwork a shared-element flight lands on when this screen is opened from a card. */
    open val heroArt: android.widget.ImageView? get() = null
    /** Where the playing song's cover sits on this screen (Home's card); null means the mini-player. */
    open val playerArt: android.widget.ImageView? get() = null

    /** When the screen last became visible (uptime ms). */
    private var shownAt = 0L

    /**
     * Loads what the first frame needs. The shell waits for it briefly before navigating, so the page
     * arrives already filled and the transition is the only motion (no "loads twice" effect).
     */
    open suspend fun prepare() {}
    internal var prepared = false

    abstract fun build(): View
    open fun onShow() {}
    open fun onHide() {}
    open fun onBack(): Boolean = false
    open fun onDestroy() {}

    internal fun dispatchShow() {
        visibleScope = MainScope()
        shownAt = android.os.SystemClock.uptimeMillis()
        onShow()
    }

    /**
     * Content that arrives after the page is already on screen fades in once; content that is ready
     * while the page is still transitioning in just appears (the transition already animates it).
     */
    protected fun reveal(v: View) {
        if (com.teja.bumblebee.ui.design.Motion.reduced || android.os.SystemClock.uptimeMillis() - shownAt < 160) return
        v.alpha = 0f
        v.animate().alpha(1f).setDuration(180).setStartDelay(0).start()
    }

    internal fun dispatchHide() {
        visibleScope.cancel()
        onHide()
    }

    internal fun dispatchDestroy() {
        scope.cancel()
        onDestroy()
    }

    protected fun launchVisible(block: suspend CoroutineScope.() -> Unit): Job = visibleScope.launch(block = block)
}

enum class Tab { NOW, HOME, LIBRARY, SEARCH, SETTINGS }
