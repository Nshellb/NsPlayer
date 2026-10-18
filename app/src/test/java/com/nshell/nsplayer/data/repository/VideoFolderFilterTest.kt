package com.nshell.nsplayer.data.repository

import org.junit.Assert.assertEquals
import org.junit.Test

class VideoFolderFilterTest {
    private data class Entry(val folderId: String?)

    @Test fun nullSelectionKeepsAllFolders() {
        val entries = mutableListOf(Entry("movies"), Entry(null))

        val result = VideoFolderFilter.apply(entries, null, Entry::folderId)

        assertEquals(entries, result)
    }

    @Test fun emptySelectionExcludesAllFolders() {
        val entries = mutableListOf(Entry("movies"), Entry("camera"))

        val result = VideoFolderFilter.apply(entries, emptySet(), Entry::folderId)

        assertEquals(emptyList<Entry>(), result)
    }

    @Test fun selectionKeepsOnlyMatchingFolderIds() {
        val entries = mutableListOf(Entry("movies"), Entry("camera"), Entry(null))

        val result = VideoFolderFilter.apply(entries, setOf("camera"), Entry::folderId)

        assertEquals(listOf(Entry("camera")), result)
    }
}
