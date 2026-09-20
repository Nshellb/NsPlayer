package com.nshell.nsplayer.ui.main

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class VideoBrowserStateTest {
    @Test
    fun repeatedParentNavigationVisitsEveryDirectoryAndVolumeRoot() {
        val nested = hierarchy("volume:external_primary/Movies/Trips/")

        val movies = nested.parentNavigationState()!!
        val volume = movies.parentNavigationState()!!
        val root = volume.parentNavigationState()!!

        assertEquals("volume:external_primary/Movies/", movies.hierarchyPath)
        assertEquals("volume:external_primary/", volume.hierarchyPath)
        assertEquals("", root.hierarchyPath)
        assertNull(root.parentNavigationState())
    }

    @Test
    fun parentNavigationHandlesPathsWithAndWithoutTrailingSlashes() {
        val expected = hierarchy("volume:external_primary/Movies/")

        assertEquals(expected, hierarchy("volume:external_primary/Movies/Trips").parentNavigationState())
        assertEquals(expected, hierarchy("volume:external_primary/Movies/Trips/").parentNavigationState())
        assertEquals(expected, hierarchy("volume:external_primary/Movies/Trips///").parentNavigationState())
        assertEquals("", hierarchy("Movies/").parentNavigationState()?.hierarchyPath)
        assertEquals("", hierarchy("volume:external_primary").parentNavigationState()?.hierarchyPath)
    }

    @Test
    fun folderBackReturnsToFolderListAndPreservesDisplaySettings() {
        val folder = VideoBrowserState(
            inFolderVideos = true,
            selectedBucketId = "42",
            selectedBucketName = "Movies",
            videoDisplayMode = VideoDisplayMode.TILE,
            tileSpanCount = 4,
            sortMode = VideoSortMode.TITLE,
            sortOrder = VideoSortOrder.ASC
        )

        val parent = folder.parentNavigationState()!!

        assertEquals(
            folder.copy(inFolderVideos = false, selectedBucketId = null, selectedBucketName = null),
            parent
        )
        assertNull(parent.parentNavigationState())
    }

    @Test
    fun rootModesDoNotHaveAParent() {
        assertNull(VideoBrowserState().parentNavigationState())
        assertNull(hierarchy("").parentNavigationState())
        assertNull(VideoBrowserState(currentMode = VideoMode.VIDEOS).parentNavigationState())
    }

    @Test
    fun displaySettingsAndFolderTitleDoNotChangeNavigationIdentity() {
        val folder = VideoBrowserState(
            inFolderVideos = true,
            selectedBucketId = "42",
            selectedBucketName = "Movies"
        )
        val changedSettings = folder.copy(
            selectedBucketName = "Renamed movies",
            videoDisplayMode = VideoDisplayMode.TILE,
            tileSpanCount = 4,
            sortMode = VideoSortMode.TITLE,
            sortOrder = VideoSortOrder.ASC,
            nomediaEnabled = true,
            searchFoldersUseAll = false,
            searchFolders = setOf("Movies/")
        )

        assertTrue(folder.hasSameNavigation(changedSettings))
        assertTrue(changedSettings.hasSameNavigation(folder))
        assertFalse(folder.hasSameNavigation(folder.copy(selectedBucketId = "43")))
        assertFalse(folder.hasSameNavigation(folder.copy(inFolderVideos = false)))
        assertFalse(folder.hasSameNavigation(folder.copy(currentMode = VideoMode.VIDEOS)))
        assertFalse(hierarchy("Movies/").hasSameNavigation(hierarchy("Movies/Trips/")))
    }

    @Test
    fun normalizationClearsInvalidFolderSelection() {
        val missingId = VideoBrowserState(
            inFolderVideos = true,
            selectedBucketName = "Movies"
        )

        assertEquals(VideoBrowserState(), missingId.normalizedNavigationState())
        assertEquals(VideoBrowserState(), missingId.copy(selectedBucketId = "").normalizedNavigationState())
        assertEquals(
            VideoBrowserState(),
            missingId.copy(inFolderVideos = false, selectedBucketId = "42").normalizedNavigationState()
        )
    }

    @Test
    fun normalizationKeepsOnlyNavigationForTheActiveMode() {
        val mixed = hierarchy("volume:external_primary/Movies").copy(
            inFolderVideos = true,
            selectedBucketId = "42",
            selectedBucketName = "Movies"
        )

        assertEquals(hierarchy("volume:external_primary/Movies/"), mixed.normalizedNavigationState())
        assertEquals(
            VideoBrowserState(currentMode = VideoMode.VIDEOS),
            mixed.copy(currentMode = VideoMode.VIDEOS).normalizedNavigationState()
        )
        assertEquals(
            mixed.copy(currentMode = VideoMode.FOLDERS, hierarchyPath = ""),
            mixed.copy(currentMode = VideoMode.FOLDERS).normalizedNavigationState()
        )
    }

    @Test
    fun normalizationMakesHierarchyPathsCanonical() {
        val expected = hierarchy("volume:external_primary/Movies/")

        assertEquals(expected, hierarchy("volume:external_primary/Movies").normalizedNavigationState())
        assertEquals(expected, hierarchy("volume:external_primary/Movies///").normalizedNavigationState())
        assertEquals(hierarchy(""), hierarchy("").normalizedNavigationState())
        assertEquals(hierarchy(""), hierarchy("///").normalizedNavigationState())
        assertEquals(expected, expected.normalizedNavigationState().normalizedNavigationState())
    }

    private fun hierarchy(path: String) = VideoBrowserState(
        currentMode = VideoMode.HIERARCHY,
        hierarchyPath = path
    )
}
