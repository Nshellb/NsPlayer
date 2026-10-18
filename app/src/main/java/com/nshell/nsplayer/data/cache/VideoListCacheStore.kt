package com.nshell.nsplayer.data.cache

import com.nshell.nsplayer.data.repository.VideoListItem
import com.nshell.nsplayer.data.repository.VideoQuery

interface VideoListCacheStore {
    fun read(query: VideoQuery): List<VideoListItem>?
    fun write(query: VideoQuery, items: List<VideoListItem>)
}
