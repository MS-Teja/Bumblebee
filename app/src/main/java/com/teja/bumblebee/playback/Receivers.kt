package com.teja.bumblebee.playback

import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.teja.bumblebee.data.Prefs

/** Runs [block] against a short-lived controller (starting the service if needed), then releases it. */
internal fun withController(context: Context, done: () -> Unit, block: (MediaController) -> Unit) {
    val app = context.applicationContext
    val future = MediaController.Builder(app, SessionToken(app, ComponentName(app, PlaybackService::class.java))).buildAsync()
    future.addListener({
        runCatching {
            val c = future.get()
            block(c)
            c.release()
        }
        done()
    }, ContextCompat.getMainExecutor(app))
}

/** Resumes the last song when the head unit boots (if "On startup" says so). */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        Prefs.init(context)
        if (Prefs.onStartup == "off") return
        val pending = goAsync()
        // Creating the service restores the queue and, for "play", starts playback itself.
        withController(context, { pending.finish() }) { }
    }
}

/**
 * Many Chinese head units send steering-wheel keys as the old stock-music broadcasts
 * (com.android.music.musicservicecommand) instead of media button events.
 */
class LegacyCommandReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val cmd = intent.getStringExtra("command") ?: when (intent.action) {
            "com.android.music.togglepause" -> "togglepause"
            "com.android.music.pause" -> "pause"
            "com.android.music.next" -> "next"
            "com.android.music.previous" -> "previous"
            else -> return
        }
        val service = PlaybackService.instance
        if (service != null) {
            apply(service.exo, cmd)
            return
        }
        val pending = goAsync()
        withController(context, { pending.finish() }) { c -> apply(c, cmd) }
    }

    private fun apply(p: androidx.media3.common.Player, cmd: String) {
        when (cmd) {
            "togglepause", "playpause" -> if (p.playWhenReady) p.pause() else { p.prepare(); p.play() }
            "pause", "stop" -> p.pause()
            "play" -> { p.prepare(); p.play() }
            "next" -> p.seekToNext()
            "previous", "prev" -> p.seekToPrevious()
        }
    }
}
