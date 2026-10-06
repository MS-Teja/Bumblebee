package com.teja.bumblebee.playback

import android.content.Context
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import com.teja.bumblebee.data.Library
import com.teja.bumblebee.data.PlayContext
import com.teja.bumblebee.data.Prefs
import com.teja.bumblebee.data.Track
import com.teja.bumblebee.util.TitleCleaner
import java.io.File

/**
 * Persists the queue (a small text file, rewritten atomically only when it changes) and the position
 * (SharedPreferences, committed every few seconds) so a power cut never loses your place.
 */
object QueueStore {
    private lateinit var file: File

    fun init(context: Context) { file = File(context.filesDir, "queue.tsv") }

    data class Restored(val items: List<MediaItem>, val index: Int, val positionMs: Long, val shuffle: Boolean, val repeat: Int)

    data class Snapshot(val track: Track, val context: PlayContext, val positionMs: Long)

    fun saveQueue(player: Player) {
        val sb = StringBuilder()
        for (i in 0 until player.mediaItemCount) {
            val item = player.getMediaItemAt(i)
            val ctx = MediaItems.context(item)
            sb.append(item.mediaId).append('\t').append(ctx.label.replace('\t', ' ')).append('\t').append(ctx.path ?: "").append('\n')
        }
        runCatching {
            val tmp = File(file.path + ".tmp")
            tmp.writeText(sb.toString())
            tmp.renameTo(file)
        }
    }

    fun savePosition(player: Player) {
        val item = player.currentMediaItem ?: return
        val t = MediaItems.track(item)
        val ctx = MediaItems.context(item)
        Prefs.raw().edit()
            .putInt("q_index", player.currentMediaItemIndex)
            .putLong("q_pos", player.currentPosition.coerceAtLeast(0))
            .putBoolean("q_shuffle", player.shuffleModeEnabled)
            .putInt("q_repeat", player.repeatMode)
            .putString("snap_path", t.path)
            .putString("snap_title", t.title)
            .putString("snap_artist", t.artist)
            .putString("snap_album", t.album)
            .putString("snap_album_artist", t.albumArtist)
            .putLong("snap_dur", if (player.duration > 0) player.duration else t.durationMs)
            .putString("snap_ctx", ctx.label)
            .putString("snap_ctx_path", ctx.path)
            .commit()
    }

    /** What the UI shows before the player connects. */
    fun snapshot(): Snapshot? {
        val sp = Prefs.raw()
        val path = sp.getString("snap_path", null) ?: return null
        val t = Track(
            path = path,
            title = sp.getString("snap_title", null) ?: TitleCleaner.fromFileName(path.substringAfterLast('/')),
            artist = sp.getString("snap_artist", null),
            album = sp.getString("snap_album", null),
            albumArtist = sp.getString("snap_album_artist", null),
            durationMs = sp.getLong("snap_dur", 0),
        )
        return Snapshot(t, PlayContext(sp.getString("snap_ctx", "") ?: "", sp.getString("snap_ctx_path", null)), sp.getLong("q_pos", 0))
    }

    fun load(): Restored? {
        val lines = runCatching { file.readLines() }.getOrNull()?.filter { it.isNotBlank() } ?: return null
        if (lines.isEmpty()) return null
        val rows = lines.map { it.split('\t') }
        val tags = Library.tracksByPath(rows.map { it[0] })
        val items = rows.map { r ->
            val path = r[0]
            val track = tags[path] ?: Track(path, TitleCleaner.fromFileName(path.substringAfterLast('/')))
            MediaItems.of(track, PlayContext(r.getOrNull(1) ?: "", r.getOrNull(2)?.ifBlank { null }))
        }
        val sp = Prefs.raw()
        val index = sp.getInt("q_index", 0).coerceIn(0, items.size - 1)
        return Restored(items, index, sp.getLong("q_pos", 0), sp.getBoolean("q_shuffle", false), sp.getInt("q_repeat", Player.REPEAT_MODE_ALL))
    }
}
