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
) {
    fun hasSameNavigation(other: VideoBrowserState): Boolean =
        currentMode == other.currentMode &&
            inFolderVideos == other.inFolderVideos &&
            selectedBucketId == other.selectedBucketId &&
            hierarchyPath == other.hierarchyPath

    fun parentNavigationState(): VideoBrowserState? = when {
        currentMode == VideoMode.HIERARCHY && hierarchyPath.isNotEmpty() -> {
            val path = hierarchyPath.trimEnd('/')
            val parentEnd = path.lastIndexOf('/')
            copy(hierarchyPath = if (parentEnd < 0) "" else path.substring(0, parentEnd + 1))
        }
        currentMode == VideoMode.FOLDERS && inFolderVideos -> copy(
            inFolderVideos = false,
            selectedBucketId = null,
            selectedBucketName = null
        )
        else -> null
    }

    fun normalizedNavigationState(): VideoBrowserState {
        val validFolder = currentMode == VideoMode.FOLDERS &&
            inFolderVideos &&
            !selectedBucketId.isNullOrEmpty()
        val normalizedPath = if (currentMode == VideoMode.HIERARCHY) {
            hierarchyPath.trimEnd('/').let { path ->
                if (path.isEmpty()) "" else "$path/"
            }
        } else {
            ""
        }
        return copy(
            inFolderVideos = validFolder,
            selectedBucketId = if (validFolder) selectedBucketId else null,
            selectedBucketName = if (validFolder) selectedBucketName else null,
            hierarchyPath = normalizedPath
        )
    }
}
