package com.nshell.nsplayer.data.repository

enum class VideoBrowseMode { FOLDERS, HIERARCHY, VIDEOS }

enum class VideoSortField { TITLE, MODIFIED, LENGTH }

enum class VideoSortDirection { ASCENDING, DESCENDING }

data class VideoQueryOptions(
    val sortField: VideoSortField,
    val sortDirection: VideoSortDirection,
    val respectNoMedia: Boolean,
    /** Null means all folders; an empty set means no folders. */
    val folderIds: Set<String>?
) {
    init {
        require(folderIds == null || folderIds.none { it.isBlank() }) {
            "Folder IDs must not be blank"
        }
    }
}

sealed interface VideoQuerySource {
    data class Browse(val mode: VideoBrowseMode) : VideoQuerySource
    data class Folder(val bucketId: String) : VideoQuerySource
    data class Hierarchy(val path: String) : VideoQuerySource
    data class HierarchyVideos(val path: String) : VideoQuerySource
    data class Search(val text: String, val limit: Int? = null) : VideoQuerySource
}

data class VideoQuery(
    val source: VideoQuerySource,
    val options: VideoQueryOptions
)
