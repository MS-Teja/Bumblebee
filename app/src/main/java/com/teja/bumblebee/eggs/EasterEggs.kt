package com.teja.bumblebee.eggs

import com.teja.bumblebee.data.Prefs
import com.teja.bumblebee.data.Track
import java.util.Calendar

/**
 * Bumblebee can't talk, so he speaks through songs. Small, rare, never in glance mode, never blocking,
 * each at most once a day, and all off with the "Easter eggs" setting.
 */
object EasterEggs {

    private val cameos = mapOf(
        "hooked on a feeling" to "Ooga-chaka",
        "don't stop me now" to "Wasn't planning to",
        "dont stop me now" to "Wasn't planning to",
        "life is a highway" to "Literally my job",
        "naatu naatu" to "Oscar-winning feet detected",
        "africa" to "Blessing the rains",
        "mr. blue sky" to "Sun is shinin'",
        "mr blue sky" to "Sun is shinin'",
        "bohemian rhapsody" to "Headbang responsibly",
        "highway to hell" to "Drive safe, though",
        "drive" to "Already on it",
        "take on me" to "Take on me… (take on me)",
        "eye of the tiger" to "Rising up to the challenge",
        "danger zone" to "Highway to the…",
        "born to be wild" to "Born to be yellow",
        "radio ga ga" to "Bee approves this channel",
        "video killed the radio star" to "Not on my watch",
        "samajavaragamana" to "Bee is humming along",
        "butta bomma" to "Bee is doing the step",
        "jai ho" to "Jai ho!",
        "kesariya" to "Bee has the volume up",
        "ramuloo ramulaa" to "Can't sit still",
    )

    fun cameo(t: Track): String? {
        if (!Prefs.easterEggs) return null
        val key = t.title.lowercase().replace("’", "'").replace(Regex("\\s*[\\[(].*$"), "").trim()
        val line = cameos[key] ?: return null
        return if (Prefs.oncePerDay("cameo:$key")) line else null
    }

    fun milestone(plays: Int): String? {
        if (!Prefs.easterEggs) return null
        return when (plays) {
            100 -> "ON REPEAT · 100 PLAYS"
            250 -> "ON REPEAT · 250 PLAYS"
            500 -> "FAVOURITE · 500 PLAYS"
            else -> null
        }
    }

    /** Home header line. */
    fun greeting(): String {
        val h = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
        if (Prefs.easterEggs) {
            if (h in 0..3) return "Late-night drive"
            if (h in 4..5) return "Early start"
        }
        return when (h) {
            in 5..11 -> "Good morning"
            in 12..16 -> "Good afternoon"
            else -> "Good evening"
        }
    }

    fun emptyFolderLine(): String = if (Prefs.easterEggs) "Bee is speechless." else "No music in this folder."

    fun listeningStats(): String {
        val hours = Prefs.listenedMs / 3_600_000L
        return when {
            !Prefs.easterEggs -> "$hours hours of music played"
            hours < 1 -> "Just getting started. Bee is warming up."
            else -> "$hours hours on the road. Bee approves."
        }
    }
}
