package com.nshell.nsplayer.ui.main

data class VideoBrowserLoadState(
    val items: List<DisplayItem>? = null,
    val phase: Phase = Phase.IDLE,
    val error: Exception? = null
) {
    enum class Phase { IDLE, LOADING, REFRESHING, READY, FAILED }

    val isLoading: Boolean get() = phase == Phase.LOADING
    val isRefreshing: Boolean get() = phase == Phase.REFRESHING
}
