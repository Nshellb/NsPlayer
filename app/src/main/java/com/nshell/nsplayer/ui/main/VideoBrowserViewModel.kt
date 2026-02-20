package com.nshell.nsplayer.ui.main

import android.content.ContentResolver
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import com.nshell.nsplayer.NsPlayerApp
import com.nshell.nsplayer.data.cache.VideoListCache
import com.nshell.nsplayer.data.repository.MediaStoreVideoRepository
import com.nshell.nsplayer.data.repository.VideoRepository
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class VideoBrowserViewModel : ViewModel() {
    private val items = MutableLiveData<List<DisplayItem>>(emptyList())
    private val loading = MutableLiveData(false)
    private val refreshing = MutableLiveData(false)
    private val state = MutableLiveData(VideoBrowserState())
    private val repository: VideoRepository = MediaStoreVideoRepository()
    private val executor: ExecutorService = Executors.newSingleThreadExecutor()
    private val cache: VideoListCache? = NsPlayerApp.appContext()?.let { VideoListCache(it) }

    fun getItems(): LiveData<List<DisplayItem>> = items

    fun getLoading(): LiveData<Boolean> = loading

    fun getRefreshing(): LiveData<Boolean> = refreshing

    fun getState(): LiveData<VideoBrowserState> = state

    fun setState(newState: VideoBrowserState) {
        state.value = newState
    }

    fun updateState(update: (VideoBrowserState) -> VideoBrowserState) {
        val current = state.value ?: VideoBrowserState()
        state.value = update(current)
    }

    fun load(
        mode: VideoMode,
        sortMode: VideoSortMode,
        sortOrder: VideoSortOrder,
        resolver: ContentResolver,
        useCache: Boolean = false,
        showRefreshing: Boolean = false
    ) {
        val key = VideoListCache.Key(
            queryType = VideoListCache.QueryType.MODE,
            mode = mode,
            sortMode = sortMode,
            sortOrder = sortOrder,
            bucketId = null,
            hierarchyPath = null
        )
        loadInternal(key, useCache, showRefreshing) {
            repository.load(mode, sortMode, sortOrder, resolver)
        }
    }

    fun loadFolderVideos(
        bucketId: String,
        sortMode: VideoSortMode,
        sortOrder: VideoSortOrder,
        resolver: ContentResolver,
        useCache: Boolean = false,
        showRefreshing: Boolean = false
    ) {
        val key = VideoListCache.Key(
            queryType = VideoListCache.QueryType.FOLDER,
            mode = VideoMode.FOLDERS,
            sortMode = sortMode,
            sortOrder = sortOrder,
            bucketId = bucketId,
            hierarchyPath = null
        )
        loadInternal(key, useCache, showRefreshing) {
            repository.loadVideosInFolder(bucketId, sortMode, sortOrder, resolver)
        }
    }

    fun loadHierarchy(
        path: String,
        sortMode: VideoSortMode,
        sortOrder: VideoSortOrder,
        resolver: ContentResolver,
        useCache: Boolean = false,
        showRefreshing: Boolean = false
    ) {
        val key = VideoListCache.Key(
            queryType = VideoListCache.QueryType.HIERARCHY,
            mode = VideoMode.HIERARCHY,
            sortMode = sortMode,
            sortOrder = sortOrder,
            bucketId = null,
            hierarchyPath = path
        )
        loadInternal(key, useCache, showRefreshing) {
            repository.loadHierarchy(path, sortMode, sortOrder, resolver)
        }
    }

    fun setRefreshing(value: Boolean) {
        refreshing.value = value
    }

    private fun loadInternal(
        key: VideoListCache.Key,
        useCache: Boolean,
        showRefreshing: Boolean,
        loader: () -> List<DisplayItem>
    ) {
        if (showRefreshing) {
            refreshing.value = true
        } else if (!useCache) {
            loading.value = true
        }
        executor.execute {
            if (useCache) {
                val cached = cache?.read(key)
                if (!cached.isNullOrEmpty()) {
                    items.postValue(cached)
                } else if (!showRefreshing) {
                    loading.postValue(true)
                }
            }
            val result = loader()
            cache?.write(key, result)
            items.postValue(result)
            loading.postValue(false)
            refreshing.postValue(false)
        }
    }

    override fun onCleared() {
        super.onCleared()
        executor.shutdown()
    }
}
