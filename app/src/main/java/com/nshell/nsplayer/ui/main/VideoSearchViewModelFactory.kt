package com.nshell.nsplayer.ui.main

import android.content.Context
import androidx.core.content.ContextCompat
import androidx.lifecycle.AbstractSavedStateViewModelFactory
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.savedstate.SavedStateRegistryOwner
import com.nshell.nsplayer.data.repository.MediaStoreVideoRepository
import com.nshell.nsplayer.data.repository.VideoQuery
import com.nshell.nsplayer.data.repository.VideoQuerySource
import java.util.concurrent.Executors

internal class VideoSearchViewModelFactory(owner: SavedStateRegistryOwner, context: Context) :
    AbstractSavedStateViewModelFactory(owner, null) {
    private val applicationContext = context.applicationContext

    override fun <T : ViewModel> create(key: String, modelClass: Class<T>, handle: SavedStateHandle): T {
        require(modelClass == VideoSearchViewModel::class.java)
        val repository = MediaStoreVideoRepository()
        val resolver = applicationContext.contentResolver
        val mapper = VideoDisplayItemMapper(applicationContext)
        val loader = VideoSearchLoader { query, options, limit ->
            mapper.map(repository.load(VideoQuery(VideoQuerySource.Search(query, limit), options), resolver))
        }
        @Suppress("UNCHECKED_CAST")
        return VideoSearchViewModel(
            handle, loader, Executors.newSingleThreadScheduledExecutor(),
            ContextCompat.getMainExecutor(applicationContext)
        ) as T
    }
}
