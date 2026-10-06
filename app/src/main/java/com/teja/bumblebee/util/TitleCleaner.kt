package com.teja.bumblebee.util

/**
 * Turns messy file names and tags into display titles. Downloaded music is full of site
 * watermarks ("[Masstamilan.in]", "(www.SongsPk.com)", "- 320kbps") and track-number prefixes.
 */
object TitleCleaner {

    private val IGNORE = RegexOption.IGNORE_CASE
    private const val SPAM = "(?:www\\.|\\.com|\\.in\\b|\\.net|\\.cc|\\.org|\\.info|\\.pk|\\.co\\b|\\.me\\b|\\.io|kbps|songs?\\s?pk|masstamilan|starmusiq|isaimini|pagalworld|mr-?jatt|djmaza|naa\\s?songs|tamilwire|atozmp3)"

    private val extension = Regex("\\.[A-Za-z0-9]{2,4}$")
    private val bracketSpam = Regex("\\s*[\\[(\\{][^\\])}]*?$SPAM[^\\])}]*[\\])}]", IGNORE)
    private val trailingSpam = Regex("\\s*[-–|:~]+\\s*[^-–|:~]*?$SPAM[^-–|:~]*$", IGNORE)
    private val leadingSpam = Regex("^\\s*[^-–|:~]*?$SPAM[^-–|:~]*[-–|:~]+\\s*", IGNORE)
    private val bareSite = Regex("\\s*(?:www\\.)?[A-Za-z0-9-]+\\.(?:com|in|net|cc|org|info|pk)\\b", IGNORE)
    private val trackPrefix = Regex("^\\s*(?:track\\s*)?(?:\\d{1,2}\\s*-\\s*\\d{1,3}\\s+|\\d{1,3}\\s*[-._)]\\s*|0\\d\\s+)", IGNORE)
    private val trackWord = Regex("^\\s*track\\s*\\d{1,3}\\s*$", IGNORE)
    private val whitespace = Regex("\\s{2,}")
    private val edgeJunk = Regex("^[\\s\\-–_.|:~]+|[\\s\\-–_.|:~]+$")

    /** Title for a file with no usable tags. */
    fun fromFileName(fileName: String): String {
        var s = fileName.replace(extension, "").replace('_', ' ')
        s = stripSpam(s)
        if (!trackWord.matches(s)) {
            val withoutNumber = s.replace(trackPrefix, "")
            if (withoutNumber.isNotBlank()) s = withoutNumber
        }
        s = tidy(s)
        return s.ifBlank { fileName }
    }

    /** Cleans a tag value; returns null when nothing meaningful is left. */
    fun clean(tag: String?): String? {
        if (tag.isNullOrBlank()) return null
        val s = tidy(stripSpam(tag.replace('_', ' ')))
        if (s.isBlank()) return null
        val lower = s.lowercase()
        if (lower == "<unknown>" || lower == "unknown" || lower == "unknown artist" || lower == "unknown album") return null
        return s
    }

    private fun stripSpam(input: String): String {
        var s = input
        repeat(3) {
            s = s.replace(bracketSpam, "").replace(trailingSpam, "").replace(leadingSpam, "")
        }
        val withoutSites = s.replace(bareSite, "")
        if (withoutSites.isNotBlank()) s = withoutSites
        return s
    }

    private fun tidy(s: String) = s.replace(whitespace, " ").replace(edgeJunk, "").trim()
}
