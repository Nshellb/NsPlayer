package com.nshell.nsplayer.ui.main

import android.content.Context
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import androidx.lifecycle.AbstractSavedStateViewModelFactory
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.savedstate.SavedStateRegistryOwner
import com.nshell.nsplayer.data.cache.VideoListCache
import com.nshell.nsplayer.data.repository.MediaStoreVideoRepository
import java.util.concurrent.Executor
import java.util.concurrent.Executors

internal class VideoBrowserViewModelFactory(
    owner: SavedStateRegistryOwner,
    context: Context,
    defaultArgs: Bundle? = null
) : AbstractSavedStateViewModelFactory(owner, defaultArgs) {
    private val applicationContext = context.applicationContext

    override fun <T : ViewModel> create(
        key: String,
        modelClass: Class<T>,
        handle: SavedStateHandle
    ): T {
        require(modelClass == VideoBrowserViewModel::class.java)
        val handler = Handler(Looper.getMainLooper())
        val mapper = VideoDisplayItemMapper(applicationContext)
        @Suppress("UNCHECKED_CAST")
        return VideoBrowserViewModel(
            savedStateHandle = handle,
            repository = MediaStoreVideoRepository(),
            executor = Executors.newSingleThreadExecutor(),
            cacheExecutor = Executors.newSingleThreadExecutor(),
            prefetchExecutor = Executors.newSingleThreadExecutor(),
            cache = VideoListCache(applicationContext),
            itemMapper = { items -> mapper.map(items) },
            mainExecutor = Executor { handler.post(it) }
        ) as T
    }
}
