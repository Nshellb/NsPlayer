package com.nshell.nsplayer.ui.main

import android.content.Context
import androidx.core.content.ContextCompat
import androidx.lifecycle.AbstractSavedStateViewModelFactory
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.savedstate.SavedStateRegistryOwner
import com.nshell.nsplayer.data.repository.MediaStoreVideoRepository
import java.util.concurrent.Executors

internal class VideoSearchViewModelFactory(owner: SavedStateRegistryOwner, context: Context) :
    AbstractSavedStateViewModelFactory(owner, null) {
    private val applicationContext = context.applicationContext

    override fun <T : ViewModel> create(key: String, modelClass: Class<T>, handle: SavedStateHandle): T {
        require(modelClass == VideoSearchViewModel::class.java)
        val repository = MediaStoreVideoRepository()
        val resolver = applicationContext.contentResolver
        val loader = VideoSearchLoader { query, options, limit ->
            repository.searchVideos(
                query, options.sortMode, options.sortOrder, resolver,
                options.nomediaEnabled, options.searchFoldersUseAll, options.searchFolders, limit
            )
        }
        @Suppress("UNCHECKED_CAST")
        return VideoSearchViewModel(
            handle, loader, Executors.newSingleThreadScheduledExecutor(),
            ContextCompat.getMainExecutor(applicationContext)
        ) as T
    }
}
