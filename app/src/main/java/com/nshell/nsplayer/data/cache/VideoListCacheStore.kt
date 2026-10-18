package com.nshell.nsplayer.data.cache

import com.nshell.nsplayer.ui.main.DisplayItem

interface VideoListCacheStore {
    fun read(key: VideoListCache.Key): List<DisplayItem>?
    fun write(key: VideoListCache.Key, items: List<DisplayItem>)
}
