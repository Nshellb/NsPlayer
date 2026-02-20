package com.nshell.nsplayer.data.settings

import com.nshell.nsplayer.ui.main.VideoDisplayMode
import com.nshell.nsplayer.ui.main.VideoMode
import com.nshell.nsplayer.ui.main.VideoSortMode
import com.nshell.nsplayer.ui.main.VideoSortOrder

data class SettingsState(
    val mode: VideoMode = VideoMode.FOLDERS,
    val displayMode: VideoDisplayMode = VideoDisplayMode.LIST,
    val tileSpanCount: Int = 2,
    val sortMode: VideoSortMode = VideoSortMode.MODIFIED,
    val sortOrder: VideoSortOrder = VideoSortOrder.DESC,
    val languageTag: String? = null,
    val themeMode: ThemeMode = ThemeMode.SYSTEM,
    val nomediaEnabled: Boolean = false,
    val visibleItems: Set<VisibleItem> = setOf(
        VisibleItem.THUMBNAIL,
        VisibleItem.DURATION,
        VisibleItem.RESOLUTION
    )
)
