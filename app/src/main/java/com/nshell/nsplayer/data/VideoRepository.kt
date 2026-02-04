package com.nshell.nsplayer.data

import android.content.ContentResolver
import com.nshell.nsplayer.ui.DisplayItem
import com.nshell.nsplayer.ui.VideoMode
import com.nshell.nsplayer.ui.VideoSortMode
import com.nshell.nsplayer.ui.VideoSortOrder

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
