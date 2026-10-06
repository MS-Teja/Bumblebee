package com.teja.bumblebee.playback

import android.app.PendingIntent
import android.content.Intent
import android.os.Handler
import android.os.Looper
import androidx.annotation.OptIn
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.ForwardingPlayer
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.Timeline
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import com.teja.bumblebee.data.Library
import com.teja.bumblebee.data.Prefs
import com.teja.bumblebee.storage.VolumeEvent
import com.teja.bumblebee.storage.Volumes
import com.teja.bumblebee.ui.MainActivity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.io.File

@OptIn(UnstableApi::class)
class PlaybackService : MediaSessionService() {

    companion object {
        @Volatile
        var instance: PlaybackService? = null
            private set
    }

    lateinit var exo: ExoPlayer
        private set
    private lateinit var player: BeePlayer
    private var session: MediaSession? = null
    private val handler = Handler(Looper.getMainLooper())
    private val scope = MainScope()
    private var consecutiveErrors = 0
    private var lastTick = 0L

    override fun onCreate() {
        super.onCreate()
        instance = this
        exo = ExoPlayer.Builder(this)
            .setAudioAttributes(
                AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MUSIC).build(),
                /* handleAudioFocus = */ true,
            )
            .setHandleAudioBecomingNoisy(true)
            .setWakeMode(C.WAKE_MODE_LOCAL)
            .setLoadControl(DefaultLoadControl.Builder().setBufferDurationsMs(5_000, 15_000, 500, 1_500).build())
            .setSeekBackIncrementMs(10_000)
            .setSeekForwardIncrementMs(10_000)
            .build()
        // In a car the music should keep going: repeat the queue unless the user turns it off.
        exo.repeatMode = Player.REPEAT_MODE_ALL
        player = BeePlayer(exo)
        session = MediaSession.Builder(this, player)
            .setCallback(SessionCallback())
            .setSessionActivity(
                PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT),
            )
            .build()
        exo.addListener(listener)
        restore()
        handler.post(tick)
        Volumes.start(this)
        scope.launch {
            Volumes.events.collect { e ->
                when (e) {
                    is VolumeEvent.Mounted -> onDriveBack()
                    is VolumeEvent.Removed -> onDriveGone(e.volume.path)
                }
            }
        }
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = session

    override fun onTaskRemoved(rootIntent: Intent?) {
        save(queue = true)
        // In a car the music keeps playing when the UI is swiped away.
        if (!exo.playWhenReady || exo.mediaItemCount == 0) stopSelf()
    }

    override fun onDestroy() {
        save(queue = true)
        handler.removeCallbacksAndMessages(null)
        scope.cancel()
        session?.run { player.release(); release() }
        session = null
        instance = null
        super.onDestroy()
    }

    // ------------------------------------------------------------------ restore / save

    private fun restore() {
        val r = QueueStore.load() ?: return
        exo.setMediaItems(r.items, r.index, r.positionMs)
        exo.shuffleModeEnabled = r.shuffle
        exo.repeatMode = r.repeat
        val path = r.items[r.index].mediaId
        if (File(path).exists()) {
            exo.prepare()
            if (Prefs.onStartup == "play") exo.playWhenReady = true
        } else {
            BeeEvents.waiting.value = Volumes.labelForMissing(path)
        }
    }

    private var queueDirty = false

    private fun save(queue: Boolean = false) {
        if (exo.mediaItemCount == 0) return
        if (queue || queueDirty) {
            QueueStore.saveQueue(exo)
            queueDirty = false
        }
        QueueStore.savePosition(exo)
    }

    private val tick = object : Runnable {
        override fun run() {
            if (exo.isPlaying) {
                val now = System.currentTimeMillis()
                if (lastTick > 0) Prefs.listenedMs = Prefs.listenedMs + (now - lastTick).coerceAtMost(15_000)
                lastTick = now
                save()
            } else {
                lastTick = 0
            }
            handler.postDelayed(this, 10_000)
        }
    }

    // ------------------------------------------------------------------ USB drive comes and goes

    private fun onDriveBack() {
        if (BeeEvents.waiting.value == null) return
        val path = exo.currentMediaItem?.mediaId ?: return
        if (!File(path).exists()) return
        BeeEvents.waiting.value = null
        exo.prepare()
        if (Prefs.onUsb == "play" || Prefs.onStartup == "play") exo.play()
    }

    private fun onDriveGone(root: String) {
        val path = exo.currentMediaItem?.mediaId ?: return
        if (!path.startsWith("$root/")) return
        save()
        exo.pause()
        BeeEvents.waiting.value = Volumes.labelForMissing(path)
    }

    // ------------------------------------------------------------------ listener

    private val listener = object : Player.Listener {
        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            mediaItem ?: return
            if (reason != Player.MEDIA_ITEM_TRANSITION_REASON_PLAYLIST_CHANGED || exo.isPlaying) {
                val path = mediaItem.mediaId
                scope.launch(Dispatchers.IO) { runCatching { Library.recordPlay(path) } }
            }
            save()
        }

        override fun onTimelineChanged(timeline: Timeline, reason: Int) {
            if (reason == Player.TIMELINE_CHANGE_REASON_PLAYLIST_CHANGED) {
                queueDirty = true
                handler.removeCallbacks(saveSoon)
                handler.postDelayed(saveSoon, 800)
            }
        }

        override fun onIsPlayingChanged(isPlaying: Boolean) {
            if (isPlaying) {
                consecutiveErrors = 0
                BeeEvents.waiting.value = null
            } else {
                save()
            }
        }

        override fun onPlayerError(error: PlaybackException) {
            val item = exo.currentMediaItem ?: return
            val path = item.mediaId
            if (!File(path).exists()) {
                // The drive was pulled: wait for it instead of skipping through the whole queue.
                exo.pause()
                BeeEvents.waiting.value = Volumes.labelForMissing(path)
                return
            }
            consecutiveErrors++
            val title = item.mediaMetadata.title ?: path.substringAfterLast('/')
            if (consecutiveErrors >= 5 || !exo.hasNextMediaItem()) {
                BeeEvents.toast("Stopped: several files in a row couldn't play")
                return
            }
            BeeEvents.toast("Skipped “$title” — can't play this file")
            exo.seekToNextMediaItem()
            exo.prepare()
            exo.play()
        }
    }

    private val saveSoon = Runnable { save(queue = true) }

    // ------------------------------------------------------------------ session callback

    private inner class SessionCallback : MediaSession.Callback {
        override fun onAddMediaItems(
            mediaSession: MediaSession,
            controller: MediaSession.ControllerInfo,
            mediaItems: MutableList<MediaItem>,
        ): ListenableFuture<MutableList<MediaItem>> =
            Futures.immediateFuture(mediaItems.map(MediaItems::resolve).toMutableList())

        override fun onPlaybackResumption(
            mediaSession: MediaSession,
            controller: MediaSession.ControllerInfo,
        ): ListenableFuture<MediaSession.MediaItemsWithStartPosition> {
            val r = QueueStore.load() ?: return Futures.immediateFailedFuture(IllegalStateException("No queue"))
            return Futures.immediateFuture(MediaSession.MediaItemsWithStartPosition(r.items, r.index, r.positionMs))
        }
    }
}

/**
 * Watches skip commands from every source (screen, steering wheel, legacy broadcasts) for the
 * "Roll out!" cheat code: ⏮⏮⏭⏭⏮⏭ within four seconds.
 */
@OptIn(UnstableApi::class)
class BeePlayer(player: Player) : ForwardingPlayer(player) {
    private val presses = ArrayDeque<Pair<Char, Long>>()

    private fun record(c: Char) {
        val now = System.currentTimeMillis()
        presses.addLast(c to now)
        while (presses.size > 6) presses.removeFirst()
        if (presses.size == 6 && now - presses.first().second < 4_000 && presses.joinToString("") { it.first.toString() } == "PPNNPN") {
            presses.clear()
            if (Prefs.easterEggs) BeeEvents.cheatCode.tryEmit(Unit)
        }
    }

    override fun seekToNext() { record('N'); super.seekToNext() }
    override fun seekToNextMediaItem() { record('N'); super.seekToNextMediaItem() }
    override fun seekToPrevious() { record('P'); super.seekToPrevious() }
    override fun seekToPreviousMediaItem() { record('P'); super.seekToPreviousMediaItem() }
}
