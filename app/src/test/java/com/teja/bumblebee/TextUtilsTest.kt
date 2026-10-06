package com.teja.bumblebee

import com.teja.bumblebee.util.NaturalOrder
import com.teja.bumblebee.util.TitleCleaner
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TextUtilsTest {

    @Test
    fun cleansFileNames() {
        val cases = mapOf(
            "01 - Samajavaragamana [Masstamilan.in].mp3" to "Samajavaragamana",
            "Butta Bomma (www.SongsPk.com).mp3" to "Butta Bomma",
            "03_Naatu_Naatu.mp3" to "Naatu Naatu",
            "Ramuloo Ramulaa - 320kbps.mp3" to "Ramuloo Ramulaa",
            "Inkem Inkem - Naa Songs.mp3" to "Inkem Inkem",
            "07. Hooked on a Feeling.flac" to "Hooked on a Feeling",
            "99 Luftballons.mp3" to "99 Luftballons",
            "Track 05.mp3" to "Track 05",
            "1-02 Mr. Blue Sky.m4a" to "Mr. Blue Sky",
            "Kalaavathi - SenSongsMp3.Co.mp3" to "Kalaavathi",
        )
        cases.forEach { (input, expected) -> assertEquals(input, expected, TitleCleaner.fromFileName(input)) }
    }

    @Test
    fun cleansTags() {
        assertEquals("Sid Sriram", TitleCleaner.clean("Sid Sriram - [Masstamilan.in]"))
        assertEquals("Ala Vaikunthapurramuloo", TitleCleaner.clean("Ala Vaikunthapurramuloo (2020) - www.SongsPk.com"
            .replace(" (2020)", "")))
        assertNull(TitleCleaner.clean("<unknown>"))
        assertNull(TitleCleaner.clean("   "))
    }

    @Test
    fun naturalOrder() {
        val sorted = listOf("10 song", "2 song", "1 song", "Song b", "song A", "02 x", "2 x").sortedWith(NaturalOrder)
        assertEquals(listOf("1 song", "2 song", "02 x", "2 x", "10 song", "song A", "Song b").take(2), sorted.take(2))
        assertTrue(NaturalOrder.compare("track 9", "track 10") < 0)
        assertTrue(NaturalOrder.compare("a", "A") == 0)
    }
}
