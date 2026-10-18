package com.nshell.nsplayer.data.repository

import android.content.ContentResolver

interface VideoRepository {
    fun load(query: VideoQuery, resolver: ContentResolver): List<VideoListItem>
}
