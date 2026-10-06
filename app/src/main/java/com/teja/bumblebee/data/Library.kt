package com.teja.bumblebee.data

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import com.teja.bumblebee.util.NaturalOrder
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** The library index: one SQLite file with tracks, folder stats and a full-text search table. */
object Library {

    private lateinit var helper: Helper
    private val db: SQLiteDatabase get() = helper.writableDatabase

    private val _version = MutableStateFlow(0L)
    /** Bumps whenever the index changes so screens can refresh. */
    val version: StateFlow<Long> = _version

    fun init(context: Context) {
        if (::helper.isInitialized) return
        helper = Helper(context.applicationContext)
    }

    fun changed() { _version.value = System.nanoTime() }

    private class Helper(ctx: Context) : SQLiteOpenHelper(ctx, "library.db", null, 1) {
        override fun onConfigure(db: SQLiteDatabase) {
            db.enableWriteAheadLogging()
        }

        override fun onCreate(db: SQLiteDatabase) {
            db.execSQL(
                """CREATE TABLE tracks(
                path TEXT PRIMARY KEY, volume TEXT, parent TEXT, file TEXT,
                title TEXT, artist TEXT, album TEXT, album_artist TEXT,
                track_no INTEGER DEFAULT 0, disc_no INTEGER DEFAULT 0, duration INTEGER DEFAULT 0,
                size INTEGER, mtime INTEGER, tags INTEGER DEFAULT 0, added INTEGER,
                plays INTEGER DEFAULT 0, last_played INTEGER DEFAULT 0)""",
            )
            db.execSQL("CREATE INDEX tracks_parent ON tracks(parent)")
            db.execSQL("CREATE INDEX tracks_volume ON tracks(volume)")
            db.execSQL("CREATE INDEX tracks_album ON tracks(album)")
            db.execSQL(
                """CREATE TABLE folders(
                path TEXT PRIMARY KEY, parent TEXT, volume TEXT, name TEXT,
                direct INTEGER, total INTEGER, children INTEGER, duration INTEGER,
                plays INTEGER DEFAULT 0, last_played INTEGER DEFAULT 0, added INTEGER DEFAULT 0)""",
            )
            db.execSQL("CREATE INDEX folders_parent ON folders(parent)")
            db.execSQL("CREATE VIRTUAL TABLE tracks_fts USING fts4(path, title, artist, album, file, notindexed=path)")
        }

        override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {}
    }

    // ---------------------------------------------------------------- reading tracks

    private const val TRACK_COLS = "path, title, artist, album, album_artist, track_no, disc_no, duration"

    private fun Cursor.toTrack() = Track(
        path = getString(0),
        title = getString(1),
        artist = getString(2),
        album = getString(3),
        albumArtist = getString(4),
        trackNo = getInt(5),
        discNo = getInt(6),
        durationMs = getLong(7),
    )

    private fun query(sql: String, vararg args: String): List<Track> =
        db.rawQuery(sql, args).use { c -> buildList { while (c.moveToNext()) add(c.toTrack()) } }

    private val minMs get() = Prefs.minDurationSec * 1000L
    private val durationFilter get() = "(duration = 0 OR duration >= $minMs)"

    fun tracksIn(parent: String): Map<String, Track> =
        query("SELECT $TRACK_COLS FROM tracks WHERE parent = ?", parent).associateBy { it.path }

    fun track(path: String): Track? = query("SELECT $TRACK_COLS FROM tracks WHERE path = ?", path).firstOrNull()

    fun tracksByPath(paths: List<String>): Map<String, Track> {
        val out = HashMap<String, Track>(paths.size)
        paths.chunked(400).forEach { chunk ->
            val marks = chunk.joinToString(",") { "?" }
            query("SELECT $TRACK_COLS FROM tracks WHERE path IN ($marks)", *chunk.toTypedArray()).forEach { out[it.path] = it }
        }
        return out
    }

    /** Every track under [dir], sorted folder by folder like a file browser would. */
    fun tracksUnder(dir: String): List<Track> =
        sortForPlayback(query("SELECT $TRACK_COLS FROM tracks WHERE (parent = ? OR parent LIKE ?) AND $durationFilter", dir, "$dir/%"))

    fun allTracks(): List<Track> =
        query("SELECT $TRACK_COLS FROM tracks WHERE $durationFilter").sortedWith(compareBy(NaturalOrder) { it.title })

    /** Tracks of an album; [albumArtist] null means "the album name without an album artist". */
    fun albumTracks(album: String, albumArtist: String?): List<Track> {
        val list = if (albumArtist != null) {
            query("SELECT $TRACK_COLS FROM tracks WHERE album = ? COLLATE NOCASE AND album_artist = ? COLLATE NOCASE AND $durationFilter", album, albumArtist)
        } else {
            query("SELECT $TRACK_COLS FROM tracks WHERE album = ? COLLATE NOCASE AND album_artist IS NULL AND $durationFilter", album)
        }
        return list.sortedWith(compareBy<Track> { it.discNo }.thenBy { it.trackNo }.thenBy(NaturalOrder) { it.fileName })
    }

    fun artistTracks(artist: String): List<Track> =
        query("SELECT $TRACK_COLS FROM tracks WHERE (artist = ? OR album_artist = ?) AND $durationFilter", artist, artist)
            .sortedWith(compareBy<Track, String>(NaturalOrder) { it.album ?: "" }.thenBy { it.discNo }.thenBy { it.trackNo }.thenBy(NaturalOrder) { it.title })

    /** Albums grouped by name + album artist (tracks without an album artist group by name alone). */
    fun albums(): List<Album> {
        val rows = query("SELECT $TRACK_COLS FROM tracks WHERE album IS NOT NULL AND $durationFilter")
        return rows.groupBy { it.album!!.lowercase() + "|" + (it.albumArtist?.lowercase() ?: "") }.values.map { group ->
            val first = group.minWithOrNull(compareBy<Track> { it.discNo }.thenBy { it.trackNo }) ?: group.first()
            val artists = group.mapNotNull { it.albumArtist ?: it.artist }.distinct()
            val artist = first.albumArtist ?: artists.singleOrNull() ?: if (artists.isEmpty()) null else "Various artists"
            Album(first.album!!, artist, group.size, first)
        }.sortedWith(compareBy(NaturalOrder) { it.name })
    }

    fun artists(): List<Artist> {
        val out = ArrayList<Artist>()
        db.rawQuery(
            """SELECT COALESCE(album_artist, artist) AS a, COUNT(*), COUNT(DISTINCT album), MIN(path) FROM tracks
               WHERE a IS NOT NULL AND $durationFilter GROUP BY a""",
            null,
        ).use { c ->
            val samples = ArrayList<Pair<Artist, String>>()
            while (c.moveToNext()) {
                val placeholder = Track(c.getString(3), c.getString(0))
                samples += Artist(c.getString(0), c.getInt(1), c.getInt(2), placeholder) to c.getString(3)
            }
            val bySample = tracksByPath(samples.map { it.second })
            samples.forEach { (a, p) -> out += a.copy(sample = bySample[p] ?: a.sample) }
        }
        return out.sortedWith(compareBy(NaturalOrder) { it.name })
    }

    fun recentlyAdded(limit: Int): List<Track> =
        query("SELECT $TRACK_COLS FROM tracks WHERE $durationFilter ORDER BY added DESC LIMIT $limit")

    fun mostPlayed(limit: Int): List<Track> =
        query("SELECT $TRACK_COLS FROM tracks WHERE plays > 0 ORDER BY plays DESC, last_played DESC LIMIT $limit")

    fun plays(path: String): Int =
        db.rawQuery("SELECT plays FROM tracks WHERE path = ?", arrayOf(path)).use { if (it.moveToFirst()) it.getInt(0) else 0 }

    /** Up to [n] tracks from distinct albums under [dir], most common albums first (for mosaics). */
    fun mosaicTracks(dir: String, n: Int): List<Track> =
        query(
            """SELECT $TRACK_COLS FROM tracks WHERE parent = ? OR parent LIKE ?
               GROUP BY COALESCE(album, parent) ORDER BY COUNT(*) DESC LIMIT $n""",
            dir, "$dir/%",
        )

    fun search(text: String, limit: Int = 60): List<Track> {
        val terms = text.trim().split(Regex("\\s+")).filter { it.isNotBlank() }
            .joinToString(" ") { it.replace(Regex("[\"*:^()-]"), "") + "*" }
        if (terms.isBlank()) return emptyList()
        val paths = db.rawQuery("SELECT path FROM tracks_fts WHERE tracks_fts MATCH ? LIMIT $limit", arrayOf(terms))
            .use { c -> buildList { while (c.moveToNext()) add(c.getString(0)) } }
        val byPath = tracksByPath(paths)
        return paths.mapNotNull { byPath[it] }
    }

    // ---------------------------------------------------------------- folders

    private fun Cursor.toFolder() = FolderInfo(getString(0), getString(1), getInt(2), getLong(3), getInt(4), getLong(5))
    private const val FOLDER_COLS = "path, name, total, duration, plays, last_played"

    fun folder(path: String): FolderInfo? =
        db.rawQuery("SELECT $FOLDER_COLS FROM folders WHERE path = ?", arrayOf(path)).use { if (it.moveToFirst()) it.toFolder() else null }

    fun childFolders(parent: String): Map<String, FolderInfo> =
        db.rawQuery("SELECT $FOLDER_COLS FROM folders WHERE parent = ?", arrayOf(parent))
            .use { c -> buildMap { while (c.moveToNext()) c.toFolder().let { put(it.path, it) } } }

    fun folderChildCount(path: String): Int =
        db.rawQuery("SELECT children FROM folders WHERE path = ?", arrayOf(path)).use { if (it.moveToFirst()) it.getInt(0) else -1 }

    fun folderDirect(path: String): Int =
        db.rawQuery("SELECT direct FROM folders WHERE path = ?", arrayOf(path)).use { if (it.moveToFirst()) it.getInt(0) else -1 }

    fun recentFolders(limit: Int): List<FolderInfo> =
        db.rawQuery("SELECT $FOLDER_COLS FROM folders WHERE last_played > 0 AND direct > 0 ORDER BY last_played DESC LIMIT $limit", null)
            .use { c -> buildList { while (c.moveToNext()) add(c.toFolder()) } }

    fun mostPlayedFolders(limit: Int): List<FolderInfo> =
        db.rawQuery("SELECT $FOLDER_COLS FROM folders WHERE plays > 0 AND direct > 0 ORDER BY plays DESC LIMIT $limit", null)
            .use { c -> buildList { while (c.moveToNext()) add(c.toFolder()) } }

    fun newestFolders(limit: Int): List<FolderInfo> =
        db.rawQuery("SELECT $FOLDER_COLS FROM folders WHERE direct > 0 ORDER BY added DESC LIMIT $limit", null)
            .use { c -> buildList { while (c.moveToNext()) add(c.toFolder()) } }

    fun searchFolders(text: String, limit: Int = 12): List<FolderInfo> =
        db.rawQuery("SELECT $FOLDER_COLS FROM folders WHERE name LIKE ? AND total > 0 ORDER BY total DESC LIMIT $limit", arrayOf("%${text.trim()}%"))
            .use { c -> buildList { while (c.moveToNext()) add(c.toFolder()) } }

    fun volumeStats(volume: String): Pair<Int, Int> =
        db.rawQuery(
            "SELECT (SELECT COUNT(*) FROM tracks WHERE volume = ?), (SELECT COUNT(*) FROM folders WHERE volume = ? AND direct > 0)",
            arrayOf(volume, volume),
        ).use { if (it.moveToFirst()) it.getInt(0) to it.getInt(1) else 0 to 0 }

    fun totalTracks(): Int = db.rawQuery("SELECT COUNT(*) FROM tracks", null).use { if (it.moveToFirst()) it.getInt(0) else 0 }

    // ---------------------------------------------------------------- writing (indexer)

    data class FileStamp(val size: Long, val mtime: Long)

    fun stamps(volume: String): Map<String, FileStamp> =
        db.rawQuery("SELECT path, size, mtime FROM tracks WHERE volume = ?", arrayOf(volume))
            .use { c -> buildMap { while (c.moveToNext()) put(c.getString(0), FileStamp(c.getLong(1), c.getLong(2))) } }

    fun insertFiles(volume: String, files: List<Triple<String, Long, Long>>, titleFor: (String) -> String) {
        if (files.isEmpty()) return
        val now = System.currentTimeMillis()
        db.beginTransaction()
        try {
            files.forEach { (path, size, mtime) ->
                val file = path.substringAfterLast('/')
                val title = titleFor(file)
                db.insertWithOnConflict(
                    "tracks", null,
                    ContentValues().apply {
                        put("path", path); put("volume", volume); put("parent", path.substringBeforeLast('/'))
                        put("file", file); put("title", title); put("size", size); put("mtime", mtime)
                        put("tags", 0); put("added", now)
                    },
                    SQLiteDatabase.CONFLICT_REPLACE,
                )
                db.delete("tracks_fts", "path = ?", arrayOf(path))
                db.insert("tracks_fts", null, ContentValues().apply { put("path", path); put("title", title); put("file", file) })
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    fun deletePaths(paths: Collection<String>) {
        if (paths.isEmpty()) return
        db.beginTransaction()
        try {
            paths.forEach {
                db.delete("tracks", "path = ?", arrayOf(it))
                db.delete("tracks_fts", "path = ?", arrayOf(it))
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    fun untagged(limit: Int): List<String> =
        db.rawQuery("SELECT path FROM tracks WHERE tags = 0 LIMIT $limit", null)
            .use { c -> buildList { while (c.moveToNext()) add(c.getString(0)) } }

    data class Tags(
        val title: String?, val artist: String?, val album: String?, val albumArtist: String?,
        val trackNo: Int, val discNo: Int, val durationMs: Long,
    )

    fun applyTags(updates: Map<String, Tags?>) {
        if (updates.isEmpty()) return
        db.beginTransaction()
        try {
            updates.forEach { (path, t) ->
                val cv = ContentValues().apply { put("tags", 1) }
                if (t != null) {
                    t.title?.let { cv.put("title", it) }
                    cv.put("artist", t.artist); cv.put("album", t.album); cv.put("album_artist", t.albumArtist)
                    cv.put("track_no", t.trackNo); cv.put("disc_no", t.discNo); cv.put("duration", t.durationMs)
                }
                db.update("tracks", cv, "path = ?", arrayOf(path))
                if (t != null) {
                    val fts = ContentValues().apply {
                        t.title?.let { put("title", it) }
                        put("artist", t.artist); put("album", t.album)
                    }
                    db.update("tracks_fts", fts, "path = ?", arrayOf(path))
                }
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    /** Recomputes folder rows for one volume from its tracks (direct + recursive counts and durations). */
    fun rebuildFolders(volume: String, root: String) {
        data class Acc(var direct: Int = 0, var total: Int = 0, var duration: Long = 0, var added: Long = 0, val kids: HashSet<String> = HashSet())
        val acc = HashMap<String, Acc>()
        db.rawQuery("SELECT parent, duration, added FROM tracks WHERE volume = ? AND $durationFilter", arrayOf(volume)).use { c ->
            while (c.moveToNext()) {
                val parent = c.getString(0)
                val dur = c.getLong(1)
                val added = c.getLong(2)
                acc.getOrPut(parent) { Acc() }.direct++
                var p = parent
                var child: String? = null
                while (true) {
                    val a = acc.getOrPut(p) { Acc() }
                    a.total++
                    a.duration += dur
                    if (added > a.added) a.added = added
                    if (child != null) a.kids += child
                    if (p == root || !p.startsWith(root) || p.length <= root.length) break
                    child = p
                    p = p.substringBeforeLast('/')
                }
            }
        }
        val old = db.rawQuery("SELECT path, plays, last_played FROM folders WHERE volume = ?", arrayOf(volume))
            .use { c -> buildMap { while (c.moveToNext()) put(c.getString(0), c.getInt(1) to c.getLong(2)) } }
        db.beginTransaction()
        try {
            db.delete("folders", "volume = ?", arrayOf(volume))
            acc.forEach { (path, a) ->
                val (plays, last) = old[path] ?: (0 to 0L)
                db.insert(
                    "folders", null,
                    ContentValues().apply {
                        put("path", path); put("parent", path.substringBeforeLast('/')); put("volume", volume)
                        put("name", path.substringAfterLast('/')); put("direct", a.direct); put("total", a.total)
                        put("children", a.kids.size); put("duration", a.duration); put("added", a.added)
                        put("plays", plays); put("last_played", last)
                    },
                )
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    fun recordPlay(path: String) {
        val now = System.currentTimeMillis()
        db.execSQL("UPDATE tracks SET plays = plays + 1, last_played = ? WHERE path = ?", arrayOf<Any>(now, path))
        db.execSQL("UPDATE folders SET plays = plays + 1, last_played = ? WHERE path = ?", arrayOf<Any>(now, path.substringBeforeLast('/')))
    }

    // ---------------------------------------------------------------- helpers

    /** Folder-by-folder order; inside a folder: disc/track numbers when every file has them, else natural file name. */
    fun sortForPlayback(tracks: List<Track>): List<Track> =
        tracks.groupBy { it.parent }.toSortedMap(NaturalOrder).values.flatMap { sortFolder(it) }

    fun sortFolder(tracks: List<Track>): List<Track> =
        if (tracks.isNotEmpty() && tracks.all { it.trackNo > 0 }) {
            tracks.sortedWith(compareBy<Track> { it.discNo }.thenBy { it.trackNo }.thenBy(NaturalOrder) { it.fileName })
        } else {
            tracks.sortedWith(compareBy(NaturalOrder) { it.fileName })
        }
}
