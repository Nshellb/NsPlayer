package com.nshell.nsplayer.ui.settings

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import com.nshell.nsplayer.data.settings.SettingsRepository
import com.nshell.nsplayer.data.settings.SettingsState
import com.nshell.nsplayer.data.settings.VisibleItem
import com.nshell.nsplayer.ui.main.VideoDisplayMode
import com.nshell.nsplayer.ui.main.VideoMode
import com.nshell.nsplayer.ui.main.VideoSortMode
import com.nshell.nsplayer.ui.main.VideoSortOrder

class SettingsViewModel(application: Application) : AndroidViewModel(application) {
    private val repository = SettingsRepository(application)
    private val settings = MutableLiveData(repository.load())

    fun getSettings(): LiveData<SettingsState> = settings

    fun refresh() {
        settings.value = repository.load()
    }

    fun updateMode(mode: VideoMode) {
        repository.updateMode(mode)
        refresh()
    }

    fun updateDisplayMode(displayMode: VideoDisplayMode) {
        repository.updateDisplayMode(displayMode)
        refresh()
    }

    fun updateTileSpanCount(tileSpanCount: Int) {
        repository.updateTileSpanCount(tileSpanCount)
        refresh()
    }

    fun updateSortMode(sortMode: VideoSortMode) {
        repository.updateSortMode(sortMode)
        refresh()
    }

    fun updateSortOrder(sortOrder: VideoSortOrder) {
        repository.updateSortOrder(sortOrder)
        refresh()
    }

    fun updateLanguageTag(languageTag: String?) {
        repository.updateLanguageTag(languageTag)
        refresh()
    }

    fun updateNomediaEnabled(enabled: Boolean) {
        repository.updateNomediaEnabled(enabled)
        refresh()
    }

    fun updateVisibleItems(items: Set<VisibleItem>) {
        repository.updateVisibleItems(items)
        refresh()
    }
}
