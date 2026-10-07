package com.teja.bumblebee

import com.teja.bumblebee.storage.FolderRepo
import org.junit.Assert.assertEquals
import org.junit.Test

class FolderNamesTest {
    @Test
    fun genericFolderNamesGetTheirParent() {
        assertEquals("Abbey Road · CD1", FolderRepo.contextualName("/storage/USB/Albums/Abbey Road/CD1"))
        assertEquals("Thriller · Disc 2", FolderRepo.contextualName("/storage/USB/Thriller/Disc 2"))
        assertEquals("Telugu · Songs", FolderRepo.contextualName("/storage/USB/Telugu/Songs"))
        assertEquals("Road Trip", FolderRepo.contextualName("/storage/USB/Road Trip"))
        assertEquals("CDs of 1990", FolderRepo.contextualName("/storage/USB/CDs of 1990"))
    }
}
