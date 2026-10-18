package com.nshell.nsplayer.ui.main

data class VideoSearchState(
    val active: Boolean = false,
    val input: String = "",
    val committedQuery: String? = null,
    val results: List<DisplayItem>? = null,
    val previews: List<DisplayItem> = emptyList(),
    val loading: Boolean = false,
    val error: Exception? = null
) {
    val showingResults: Boolean get() = active && committedQuery != null
}

data class VideoSearchOptions(
    val sortMode: VideoSortMode,
    val sortOrder: VideoSortOrder,
    val nomediaEnabled: Boolean,
    val searchFoldersUseAll: Boolean,
    val searchFolders: Set<String>
)

fun interface VideoSearchLoader {
    fun search(query: String, options: VideoSearchOptions, limit: Int?): List<DisplayItem>
}
