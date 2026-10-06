package com.teja.bumblebee.storage

import com.teja.bumblebee.data.Library
import com.teja.bumblebee.data.Prefs
import com.teja.bumblebee.data.Track
import com.teja.bumblebee.util.NaturalOrder
import com.teja.bumblebee.util.TitleCleaner
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

object AudioFormats {
    val extensions = setOf("mp3", "m4a", "aac", "flac", "ogg", "oga", "opus", "wav", "mka", "wma", "amr", "3gp", "mp4a")
    val playlistExtensions = setOf("m3u", "m3u8")
    fun isAudio(name: String) = name.substringAfterLast('.', "").lowercase() in extensions
    fun isPlaylist(name: String) = name.substringAfterLast('.', "").lowercase() in playlistExtensions

    private val ignoredNames = setOf("android", "lost.dir", "ringtones", "notifications", "alarms", "podcasts_cache", "data", "obb", "dcim", "pictures", "movies")

    fun ignoredDir(dir: File, atVolumeRoot: Boolean): Boolean {
        val n = dir.name.lowercase()
        if (n.startsWith(".")) return true
        if (n in setOf("lost.dir", "ringtones", "notifications", "alarms")) return true
        if (atVolumeRoot && n in ignoredNames) return true
        if (dir.path in Prefs.excluded) return true
        if (Prefs.respectNomedia && File(dir, ".nomedia").exists()) return true
        return false
    }
}

data class FolderEntry(
    /** The folder to open (end of a collapsed chain). */
    val dir: File,
    /** "Music / Songs / All" when a chain of single-child folders was collapsed. */
    val name: String,
    val songs: Int,
    val durationMs: Long,
)

data class FolderListing(
    val dir: File,
    val folders: List<FolderEntry>,
    val tracks: List<Track>,
    val playlists: List<File>,
)

/** Lists folders live from the file system (no index needed), enriched with whatever the index knows. */
object FolderRepo {

    suspend fun list(dir: File): FolderListing = withContext(Dispatchers.IO) {
        val volume = Volumes.forPath(dir.path)
        val atRoot = volume != null && volume.path == dir.path
        val indexed = volume != null && volume.id in Prefs.scannedVolumes
        val children = dir.listFiles().orEmpty()
        val known = Library.childFolders(dir.path)
        val minMs = Prefs.minDurationSec * 1000L

        val folders = children.asSequence()
            .filter { it.isDirectory && !AudioFormats.ignoredDir(it, atRoot) }
            .mapNotNull { sub ->
                val info = known[sub.path]
                if (indexed && (info == null || info.total == 0)) return@mapNotNull null
                collapse(sub, info?.total ?: -1, info?.durationMs ?: 0, indexed)
            }
            .sortedWith(compareBy(NaturalOrder) { it.name })
            .toList()

        val tags = Library.tracksIn(dir.path)
        val tracks = children.asSequence()
            .filter { it.isFile && AudioFormats.isAudio(it.name) }
            .map { f -> tags[f.path] ?: Track(f.path, TitleCleaner.fromFileName(f.name)) }
            .filter { it.durationMs == 0L || it.durationMs >= minMs }
            .toList()

        val playlists = children.filter { it.isFile && AudioFormats.isPlaylist(it.name) }.sortedWith(compareBy(NaturalOrder) { it.name })
        FolderListing(dir, folders, Library.sortFolder(tracks), playlists)
    }

    /** Follows chains of folders that hold nothing but a single subfolder. */
    private fun collapse(start: File, total: Int, duration: Long, indexed: Boolean): FolderEntry {
        var dir = start
        var name = start.name
        if (indexed) {
            for (step in 0 until 6) {
                if (Library.folderDirect(dir.path) > 0) break
                val kids = Library.childFolders(dir.path).values.filter { it.total > 0 }
                if (kids.size != 1) break
                dir = File(kids.first().path)
                name += " / " + dir.name
            }
        }
        return FolderEntry(dir, name, total, duration)
    }

    /** Tracks for "play this folder": just this folder, or everything below it. */
    suspend fun tracksFor(dir: File, recursive: Boolean): List<Track> = withContext(Dispatchers.IO) {
        if (recursive) {
            val indexed = Library.tracksUnder(dir.path)
            if (indexed.isNotEmpty()) return@withContext indexed
            walk(dir)
        } else {
            list(dir).tracks
        }
    }

    private fun walk(dir: File): List<Track> {
        val out = ArrayList<Track>()
        val stack = ArrayDeque<File>().apply { add(dir) }
        while (stack.isNotEmpty() && out.size < 5000) {
            val d = stack.removeLast()
            val kids = d.listFiles() ?: continue
            kids.filter { it.isDirectory && !AudioFormats.ignoredDir(it, false) }.forEach(stack::add)
            kids.filter { it.isFile && AudioFormats.isAudio(it.name) }.forEach { out += Track(it.path, TitleCleaner.fromFileName(it.name)) }
        }
        return Library.sortForPlayback(out)
    }

    /** Reads an .m3u/.m3u8 playlist, resolving relative paths against its folder. */
    suspend fun playlist(file: File): List<Track> = withContext(Dispatchers.IO) {
        val base = file.parentFile ?: return@withContext emptyList()
        val paths = runCatching { file.readLines() }.getOrDefault(emptyList())
            .map { it.trim().replace('\\', '/') }
            .filter { it.isNotEmpty() && !it.startsWith("#") }
            .map { if (it.startsWith("/")) File(it) else File(base, it) }
            .filter { it.exists() }
            .map { it.path }
        val tags = Library.tracksByPath(paths)
        paths.map { tags[it] ?: Track(it, TitleCleaner.fromFileName(it.substringAfterLast('/'))) }
    }
}
