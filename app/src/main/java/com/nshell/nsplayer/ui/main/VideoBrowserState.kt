package com.nshell.nsplayer.ui.main

data class VideoBrowserState(
    val currentMode: VideoMode = VideoMode.FOLDERS,
    val videoDisplayMode: VideoDisplayMode = VideoDisplayMode.LIST,
    val tileSpanCount: Int = 2,
    val sortMode: VideoSortMode = VideoSortMode.MODIFIED,
    val sortOrder: VideoSortOrder = VideoSortOrder.DESC,
    val nomediaEnabled: Boolean = false,
    val searchFoldersUseAll: Boolean = true,
    val searchFolders: Set<String> = emptySet(),
    val inFolderVideos: Boolean = false,
    val selectedBucketId: String? = null,
    val selectedBucketName: String? = null,
    val hierarchyPath: String = ""
)
