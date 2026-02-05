package com.nshell.nsplayer.ui.main

import android.content.ContentResolver
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import com.nshell.nsplayer.data.repository.MediaStoreVideoRepository
import com.nshell.nsplayer.data.repository.VideoRepository
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class VideoBrowserViewModel : ViewModel() {
    private val items = MutableLiveData<List<DisplayItem>>(emptyList())
    private val loading = MutableLiveData(false)
    private val state = MutableLiveData(VideoBrowserState())
    private val repository: VideoRepository = MediaStoreVideoRepository()
    private val executor: ExecutorService = Executors.newSingleThreadExecutor()

    fun getItems(): LiveData<List<DisplayItem>> = items

    fun getLoading(): LiveData<Boolean> = loading

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
        resolver: ContentResolver
    ) {
        loading.value = true
        executor.execute {
            val result = repository.load(mode, sortMode, sortOrder, resolver)
            items.postValue(result)
            loading.postValue(false)
        }
    }

    fun loadFolderVideos(
        bucketId: String,
        sortMode: VideoSortMode,
        sortOrder: VideoSortOrder,
        resolver: ContentResolver
    ) {
        loading.value = true
        executor.execute {
            val result = repository.loadVideosInFolder(bucketId, sortMode, sortOrder, resolver)
            items.postValue(result)
            loading.postValue(false)
        }
    }

    fun loadHierarchy(
        path: String,
        sortMode: VideoSortMode,
        sortOrder: VideoSortOrder,
        resolver: ContentResolver
    ) {
        loading.value = true
        executor.execute {
            val result = repository.loadHierarchy(path, sortMode, sortOrder, resolver)
            items.postValue(result)
            loading.postValue(false)
        }
    }

    override fun onCleared() {
        super.onCleared()
        executor.shutdown()
    }
}
