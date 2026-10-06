package com.teja.bumblebee.playback

import android.content.ComponentName
import android.content.Context
import androidx.core.content.ContextCompat
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.google.common.util.concurrent.ListenableFuture
import com.teja.bumblebee.data.PlayContext
import com.teja.bumblebee.data.Track
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** The UI's view of playback. Shows the persisted snapshot instantly, then follows the live controller. */
object PlayerHub {

    data class State(
        val current: Track? = null,
        val prev: Track? = null,
        val next: Track? = null,
        val index: Int = 0,
        val count: Int = 0,
        val playing: Boolean = false,
        val shuffle: Boolean = false,
        val repeat: Int = Player.REPEAT_MODE_ALL,
        val context: PlayContext = PlayContext.NONE,
        val durationMs: Long = 0,
        val connected: Boolean = false,
    ) {
        val hasPrev get() = prev != null
        val hasNext get() = next != null
    }

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state
    var controller: MediaController? = null
        private set
    private var future: ListenableFuture<MediaController>? = null
    private var snapshotPosition = 0L
    private val pending = ArrayList<(MediaController) -> Unit>()

    fun connect(context: Context) {
        if (future != null) return
        QueueStore.snapshot()?.let { s ->
            snapshotPosition = s.positionMs
            _state.value = State(current = s.track, context = s.context, durationMs = s.track.durationMs, count = 1)
        }
        val app = context.applicationContext
        val f = MediaController.Builder(app, SessionToken(app, ComponentName(app, PlaybackService::class.java))).buildAsync()
        future = f
        f.addListener({
            val c = runCatching { f.get() }.getOrNull() ?: return@addListener
            controller = c
            c.addListener(object : Player.Listener {
                override fun onEvents(player: Player, events: Player.Events) = publish()
            })
            publish()
            pending.forEach { it(c) }
            pending.clear()
        }, ContextCompat.getMainExecutor(app))
    }

    private fun run(block: (MediaController) -> Unit) {
        val c = controller
        if (c != null) block(c) else pending += block
    }

    private fun publish() {
        val c = controller ?: return
        val item = c.currentMediaItem
        val tl = c.currentTimeline
        fun at(index: Int): Track? = if (index == C.INDEX_UNSET || index >= c.mediaItemCount) null else MediaItems.track(c.getMediaItemAt(index))
        val dur = c.duration.takeIf { it != C.TIME_UNSET && it > 0 } ?: item?.let { MediaItems.track(it).durationMs } ?: 0
        _state.value = State(
            current = item?.let(MediaItems::track),
            prev = if (tl.isEmpty) null else at(c.previousMediaItemIndex),
            next = if (tl.isEmpty) null else at(c.nextMediaItemIndex),
            index = c.currentMediaItemIndex,
            count = c.mediaItemCount,
            playing = c.playWhenReady,
            shuffle = c.shuffleModeEnabled,
            repeat = c.repeatMode,
            context = MediaItems.context(item),
            durationMs = dur,
            connected = true,
        )
    }

    val positionMs: Long get() = controller?.currentPosition ?: snapshotPosition
    val bufferedFraction: Float
        get() = controller?.let { if (it.duration > 0) it.bufferedPosition.toFloat() / it.duration else 0f } ?: 0f

    // ------------------------------------------------------------------ commands

    fun play(tracks: List<Track>, start: Int, ctx: PlayContext, shuffle: Boolean = false) {
        if (tracks.isEmpty()) return
        val list = if (shuffle) tracks.shuffled() else tracks
        val first = if (shuffle) 0 else start.coerceIn(0, list.size - 1)
        val items = list.take(5000).map { MediaItems.of(it, ctx) }
        run { c ->
            c.shuffleModeEnabled = false
            c.setMediaItems(items, first, 0)
            c.prepare()
            c.play()
        }
    }

    fun playNext(track: Track, ctx: PlayContext = PlayContext("Queue", null)) = run { c ->
        if (c.mediaItemCount == 0) {
            c.setMediaItem(MediaItems.of(track, ctx)); c.prepare(); c.play()
        } else {
            c.addMediaItem(c.currentMediaItemIndex + 1, MediaItems.of(track, ctx))
        }
    }

    fun addToQueue(track: Track, ctx: PlayContext = PlayContext("Queue", null)) = run { c ->
        if (c.mediaItemCount == 0) {
            c.setMediaItem(MediaItems.of(track, ctx)); c.prepare(); c.play()
        } else {
            c.addMediaItem(MediaItems.of(track, ctx))
        }
    }

    fun toggle() = run { c ->
        if (c.playbackState == Player.STATE_IDLE) c.prepare()
        if (c.playWhenReady) c.pause() else c.play()
    }

    fun play() = run { c -> if (c.playbackState == Player.STATE_IDLE) c.prepare(); c.play() }
    fun next() = run { it.seekToNext() }
    fun prev() = run { it.seekToPrevious() }
    /** Always the previous song (a swipe right should never just restart the current one). */
    fun prevItem() = run { if (it.hasPreviousMediaItem()) it.seekToPreviousMediaItem() else it.seekTo(0) }
    fun seekTo(ms: Long) = run { it.seekTo(ms) }
    fun seekBy(ms: Long) = run { it.seekTo((it.currentPosition + ms).coerceIn(0, it.duration.coerceAtLeast(0))) }
    fun toggleShuffle() = run { it.shuffleModeEnabled = !it.shuffleModeEnabled }
    fun cycleRepeat() = run {
        it.repeatMode = when (it.repeatMode) {
            Player.REPEAT_MODE_OFF -> Player.REPEAT_MODE_ALL
            Player.REPEAT_MODE_ALL -> Player.REPEAT_MODE_ONE
            else -> Player.REPEAT_MODE_OFF
        }
    }

    // ------------------------------------------------------------------ queue sheet

    data class QueueEntry(val index: Int, val track: Track)

    /** Upcoming items in play order (respects shuffle), not including the current one. */
    fun upcoming(limit: Int = 500): List<QueueEntry> {
        val c = controller ?: return emptyList()
        val tl = c.currentTimeline
        if (tl.isEmpty) return emptyList()
        val out = ArrayList<QueueEntry>()
        // With repeat-all the queue wraps around, so "up next" continues from the start.
        val mode = if (c.repeatMode == Player.REPEAT_MODE_ALL) Player.REPEAT_MODE_ALL else Player.REPEAT_MODE_OFF
        val cur = c.currentMediaItemIndex
        var i = tl.getNextWindowIndex(cur, mode, c.shuffleModeEnabled)
        while (i != C.INDEX_UNSET && i != cur && out.size < limit) {
            out += QueueEntry(i, MediaItems.track(c.getMediaItemAt(i)))
            i = tl.getNextWindowIndex(i, mode, c.shuffleModeEnabled)
        }
        return out
    }

    fun itemAt(index: Int): MediaItem? = controller?.let { if (index in 0 until it.mediaItemCount) it.getMediaItemAt(index) else null }
    fun move(from: Int, to: Int) = run { it.moveMediaItem(from, to) }
    fun remove(index: Int) = run { it.removeMediaItem(index) }
    fun insert(index: Int, item: MediaItem) = run { it.addMediaItem(index.coerceAtMost(it.mediaItemCount), item) }
    fun jumpTo(index: Int) = run { it.seekTo(index, 0); it.play() }
    fun clearUpcoming() = run { c ->
        val keep = c.currentMediaItemIndex
        if (c.mediaItemCount > keep + 1) c.removeMediaItems(keep + 1, c.mediaItemCount)
        if (keep > 0) c.removeMediaItems(0, keep)
    }
}
