package com.nshell.nsplayer.ui

import android.content.ContentResolver
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import com.nshell.nsplayer.data.MediaStoreVideoRepository
import com.nshell.nsplayer.data.VideoRepository
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class VideoBrowserViewModel : ViewModel() {
    private val items = MutableLiveData<List<DisplayItem>>(emptyList())
    private val loading = MutableLiveData(false)
    private val repository: VideoRepository = MediaStoreVideoRepository()
    private val executor: ExecutorService = Executors.newSingleThreadExecutor()

    fun getItems(): LiveData<List<DisplayItem>> = items

    fun getLoading(): LiveData<Boolean> = loading

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
