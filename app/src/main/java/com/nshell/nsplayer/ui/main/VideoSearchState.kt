package com.nshell.nsplayer.ui.main

import com.nshell.nsplayer.data.repository.VideoQueryOptions

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

fun interface VideoSearchLoader {
    fun search(query: String, options: VideoQueryOptions, limit: Int?): List<DisplayItem>
}
