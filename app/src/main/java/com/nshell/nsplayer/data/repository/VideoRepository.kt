package com.nshell.nsplayer.data.repository

import android.content.ContentResolver
import com.nshell.nsplayer.ui.main.DisplayItem
import com.nshell.nsplayer.ui.main.VideoMode
import com.nshell.nsplayer.ui.main.VideoSortMode
import com.nshell.nsplayer.ui.main.VideoSortOrder

interface VideoRepository {
    fun load(
        mode: VideoMode,
        sortMode: VideoSortMode,
        sortOrder: VideoSortOrder,
        resolver: ContentResolver
    ): List<DisplayItem>

    fun loadVideosInFolder(
        bucketId: String,
        sortMode: VideoSortMode,
        sortOrder: VideoSortOrder,
        resolver: ContentResolver
    ): List<DisplayItem>

    fun loadHierarchy(
        path: String,
        sortMode: VideoSortMode,
        sortOrder: VideoSortOrder,
        resolver: ContentResolver
    ): List<DisplayItem>
}
