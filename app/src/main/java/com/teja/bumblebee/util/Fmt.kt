package com.teja.bumblebee.util

import java.util.Locale

object Fmt {
    fun time(ms: Long): String {
        val total = (ms.coerceAtLeast(0) / 1000).toInt()
        val h = total / 3600
        val m = (total % 3600) / 60
        val s = total % 60
        return if (h > 0) String.format(Locale.US, "%d:%02d:%02d", h, m, s) else String.format(Locale.US, "%d:%02d", m, s)
    }

    /** "2 h 31 m" / "47 m". */
    fun duration(ms: Long): String {
        val minutes = (ms / 60000).toInt()
        return if (minutes >= 60) "${minutes / 60} h ${minutes % 60} m" else "$minutes m"
    }

    fun count(n: Int, one: String, many: String = one + "s"): String =
        "${String.format(Locale.US, "%,d", n)} ${if (n == 1) one else many}"

    fun initials(text: String): String {
        val words = text.split(Regex("[\\s\\-_/]+")).filter { it.firstOrNull()?.isLetterOrDigit() == true }
        return words.take(2).joinToString("") { it.first().uppercase() }.ifBlank { "♪" }
    }
}
