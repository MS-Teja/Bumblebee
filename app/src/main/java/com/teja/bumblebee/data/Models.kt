package com.teja.bumblebee.data

import java.io.File

data class Track(
    val path: String,
    val title: String,
    val artist: String? = null,
    val album: String? = null,
    val albumArtist: String? = null,
    val trackNo: Int = 0,
    val discNo: Int = 0,
    val durationMs: Long = 0,
) {
    val parent: String get() = path.substringBeforeLast('/')
    val fileName: String get() = path.substringAfterLast('/')
    val artistLabel: String get() = artist ?: albumArtist ?: "Unknown artist"

    /** Tracks of one album share artwork, so the art cache is keyed per album (or per folder for loose files). */
    val artKey: String
        get() = if (album != null) "a:" + album.lowercase() + "|" + (albumArtist ?: artist ?: "").lowercase() else "d:$parent"

    val exists: Boolean get() = File(path).exists()
}

data class Album(val name: String, val artist: String?, val count: Int, val sample: Track)
data class Artist(val name: String, val count: Int, val albums: Int, val sample: Track)

data class FolderInfo(
    val path: String,
    val name: String,
    val total: Int,
    val durationMs: Long,
    val plays: Int = 0,
    val lastPlayed: Long = 0,
)

/** What is playing and where it came from ("Playing from" chip). */
data class PlayContext(val label: String, val path: String?) {
    companion object {
        val NONE = PlayContext("", null)
    }
}
