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

    abstract fun build(): View
    open fun onShow() {}
    open fun onHide() {}
    open fun onBack(): Boolean = false
    open fun onDestroy() {}

    internal fun dispatchShow() {
        visibleScope = MainScope()
        onShow()
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
