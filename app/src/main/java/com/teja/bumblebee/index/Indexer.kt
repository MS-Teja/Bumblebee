package com.teja.bumblebee.index

import android.content.Context
import android.media.MediaMetadataRetriever
import android.os.Build
import android.os.Process
import android.provider.MediaStore
import com.teja.bumblebee.data.Library
import com.teja.bumblebee.data.Prefs
import com.teja.bumblebee.storage.AudioFormats
import com.teja.bumblebee.storage.Volume
import com.teja.bumblebee.storage.Volumes
import com.teja.bumblebee.util.TitleCleaner
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
import java.util.concurrent.Executors

data class IndexState(val running: Boolean = false, val phase: String = "", val done: Int = 0, val total: Int = 0)

/**
 * Builds the library index in three passes on one background-priority thread:
 * walk (seconds, makes files browsable/searchable), tags (MediaStore bulk, then per-file), folder stats.
 */
object Indexer {
    private val dispatcher = Executors.newSingleThreadExecutor { r ->
        Thread({ Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND); r.run() }, "bee-indexer")
    }.asCoroutineDispatcher()
    private val scope = CoroutineScope(SupervisorJob() + dispatcher)
    private val lock = Mutex()
    private val _state = MutableStateFlow(IndexState())
    val state: StateFlow<IndexState> = _state
    private lateinit var app: Context

    fun init(context: Context) { app = context.applicationContext }

    fun scanAll(force: Boolean = false) {
        scope.launch {
            lock.withLock {
                val stale = System.currentTimeMillis() - Prefs.lastScanAt > 3_600_000L
                // Unscanned, or "scanned" but empty (e.g. access wasn't live yet): walk it again.
                val todo = Volumes.all.value.filter { force || stale || it.id !in Prefs.scannedVolumes || Library.volumeStats(it.id).first == 0 }
                todo.forEach { walk(it) }
                if (todo.isNotEmpty()) Prefs.lastScanAt = System.currentTimeMillis()
                tagPass()
            }
        }
    }

    fun scan(volume: Volume) {
        scope.launch {
            lock.withLock {
                walk(volume)
                tagPass()
            }
        }
    }

    private fun walk(volume: Volume) {
        // Unreadable (permission not live yet, drive half-mounted): don't record it as scanned.
        if (volume.root.listFiles() == null) {
            _state.value = IndexState()
            return
        }
        _state.value = IndexState(true, "Rewinding the tapes…")
        val found = ArrayList<Triple<String, Long, Long>>()
        val stack = ArrayDeque<File>().apply { add(volume.root) }
        while (stack.isNotEmpty()) {
            val dir = stack.removeLast()
            val kids = dir.listFiles() ?: continue
            val atRoot = dir.path == volume.path
            for (f in kids) {
                if (f.isDirectory) {
                    if (!AudioFormats.ignoredDir(f, atRoot)) stack.add(f)
                } else if (AudioFormats.isAudio(f.name)) {
                    found += Triple(f.path, f.length(), f.lastModified())
                }
            }
            if (found.size % 200 == 0) _state.value = IndexState(true, "Rewinding the tapes…", found.size, 0)
        }
        val existing = Library.stamps(volume.id)
        val fresh = found.filter { (path, size, mtime) -> existing[path]?.let { it.size != size || it.mtime != mtime } ?: true }
        val gone = existing.keys - found.mapTo(HashSet()) { it.first }
        Library.insertFiles(volume.id, fresh, TitleCleaner::fromFileName)
        Library.deletePaths(gone)
        Library.rebuildFolders(volume.id, volume.path)
        Prefs.scannedVolumes = Prefs.scannedVolumes + volume.id
        Library.changed()
    }

    private fun tagPass() {
        var pending = Library.untagged(Int.MAX_VALUE).size
        if (pending == 0) {
            _state.value = IndexState()
            return
        }
        val total = pending
        _state.value = IndexState(true, "Reading tags", 0, total)
        fromMediaStore()
        var done = 0
        while (true) {
            val batch = Library.untagged(40)
            if (batch.isEmpty()) break
            val updates = batch.associateWith { readTags(it) }
            Library.applyTags(updates)
            done += batch.size
            pending = total - done
            _state.value = IndexState(true, "Reading tags", done.coerceAtMost(total), total)
            if (done % 200 == 0) Library.changed()
        }
        Volumes.all.value.forEach { Library.rebuildFolders(it.id, it.path) }
        Library.changed()
        _state.value = IndexState()
    }

    /** The system scanner usually already has tags; reading them in bulk is far faster than per-file extraction. */
    private fun fromMediaStore() {
        val wanted = Library.untagged(Int.MAX_VALUE).toHashSet()
        if (wanted.isEmpty()) return
        val uris = if (Build.VERSION.SDK_INT >= 29) {
            MediaStore.getExternalVolumeNames(app).map { MediaStore.Audio.Media.getContentUri(it) }
        } else {
            listOf(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI)
        }
        @Suppress("DEPRECATION")
        val projection = arrayOf(
            MediaStore.Audio.Media.DATA, MediaStore.Audio.Media.TITLE, MediaStore.Audio.Media.ARTIST,
            MediaStore.Audio.Media.ALBUM, MediaStore.Audio.Media.DURATION, MediaStore.Audio.Media.TRACK,
        )
        val updates = HashMap<String, Library.Tags?>()
        uris.forEach { uri ->
            runCatching {
                app.contentResolver.query(uri, projection, null, null, null)?.use { c ->
                    while (c.moveToNext()) {
                        val path = c.getString(0) ?: continue
                        if (path !in wanted) continue
                        val rawTitle = c.getString(1)
                        val fileStem = path.substringAfterLast('/').substringBeforeLast('.')
                        // MediaStore falls back to the file name when there is no tag; clean that like a file name.
                        val title = if (rawTitle == null || rawTitle == fileStem) TitleCleaner.fromFileName(path.substringAfterLast('/')) else TitleCleaner.clean(rawTitle)
                        val track = c.getInt(5)
                        updates[path] = Library.Tags(
                            title = title,
                            artist = TitleCleaner.clean(c.getString(2)),
                            album = TitleCleaner.clean(c.getString(3)),
                            albumArtist = null,
                            trackNo = track % 1000,
                            discNo = track / 1000,
                            durationMs = c.getLong(4),
                        )
                    }
                }
            }
        }
        // Album artist isn't in older MediaStore; leave those for the per-file pass only if missing an album.
        Library.applyTags(updates)
        if (updates.isNotEmpty()) Library.changed()
    }

    private fun readTags(path: String): Library.Tags? {
        val r = MediaMetadataRetriever()
        return try {
            r.setDataSource(path)
            fun key(k: Int) = r.extractMetadata(k)
            fun number(v: String?) = v?.substringBefore('/')?.trim()?.toIntOrNull() ?: 0
            Library.Tags(
                title = TitleCleaner.clean(key(MediaMetadataRetriever.METADATA_KEY_TITLE)) ?: TitleCleaner.fromFileName(path.substringAfterLast('/')),
                artist = TitleCleaner.clean(key(MediaMetadataRetriever.METADATA_KEY_ARTIST)),
                album = TitleCleaner.clean(key(MediaMetadataRetriever.METADATA_KEY_ALBUM)),
                albumArtist = TitleCleaner.clean(key(MediaMetadataRetriever.METADATA_KEY_ALBUMARTIST)),
                trackNo = number(key(MediaMetadataRetriever.METADATA_KEY_CD_TRACK_NUMBER)),
                discNo = number(key(MediaMetadataRetriever.METADATA_KEY_DISC_NUMBER)),
                durationMs = key(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0,
            )
        } catch (_: Exception) {
            null
        } finally {
            runCatching { r.release() }
        }
    }
}
