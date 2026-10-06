package com.teja.bumblebee.playback

import android.net.Uri
import android.os.Bundle
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import com.teja.bumblebee.data.PlayContext
import com.teja.bumblebee.data.Track
import com.teja.bumblebee.util.TitleCleaner
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import java.io.File

/** Track <-> MediaItem. The media id is the file path; the "playing from" context rides in the extras. */
object MediaItems {
    fun of(t: Track, ctx: PlayContext): MediaItem = MediaItem.Builder()
        .setMediaId(t.path)
        .setUri(Uri.fromFile(File(t.path)))
        .setMediaMetadata(
            MediaMetadata.Builder()
                .setTitle(t.title)
                .setArtist(t.artist)
                .setAlbumTitle(t.album)
                .setAlbumArtist(t.albumArtist)
                .setTrackNumber(t.trackNo)
                .setDiscNumber(t.discNo)
                .setIsPlayable(true)
                .setIsBrowsable(false)
                .setExtras(
                    Bundle().apply {
                        putLong("dur", t.durationMs)
                        putString("ctx", ctx.label)
                        putString("ctx_path", ctx.path)
                    },
                )
                .build(),
        )
        .build()

    fun track(item: MediaItem): Track {
        val m = item.mediaMetadata
        val path = item.mediaId
        return Track(
            path = path,
            title = m.title?.toString() ?: TitleCleaner.fromFileName(path.substringAfterLast('/')),
            artist = m.artist?.toString(),
            album = m.albumTitle?.toString(),
            albumArtist = m.albumArtist?.toString(),
            trackNo = m.trackNumber ?: 0,
            discNo = m.discNumber ?: 0,
            durationMs = m.extras?.getLong("dur") ?: 0,
        )
    }

    fun context(item: MediaItem?): PlayContext {
        val e = item?.mediaMetadata?.extras ?: return PlayContext.NONE
        return PlayContext(e.getString("ctx") ?: "", e.getString("ctx_path"))
    }

    /** Items sent from a controller lose their URI; rebuild it from the path. */
    fun resolve(item: MediaItem): MediaItem =
        if (item.localConfiguration != null) item else item.buildUpon().setUri(Uri.fromFile(File(item.mediaId))).build()
}

/** In-process events from the playback service to the UI. */
object BeeEvents {
    val toasts = MutableSharedFlow<String>(extraBufferCapacity = 8)
    val cheatCode = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    /** Label of the drive we're waiting for ("SanDisk"), or null. */
    val waiting = MutableStateFlow<String?>(null)
    fun toast(text: String) { toasts.tryEmit(text) }
}
