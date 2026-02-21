package com.nshell.nsplayer.ui.settings

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import com.nshell.nsplayer.data.settings.SettingsRepository
import com.nshell.nsplayer.data.settings.SettingsState
import com.nshell.nsplayer.data.settings.ThemeMode
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
        val current = settings.value
        if (current != null) {
            if (current.languageTag == languageTag) {
                return
            }
            settings.value = current.copy(languageTag = languageTag)
        } else {
            refresh()
        }
    }

    fun updateThemeMode(themeMode: ThemeMode) {
        repository.updateThemeMode(themeMode)
        val current = settings.value
        if (current != null) {
            if (current.themeMode == themeMode) {
                return
            }
            settings.value = current.copy(themeMode = themeMode)
        } else {
            refresh()
        }
    }

    fun updateNomediaEnabled(enabled: Boolean) {
        repository.updateNomediaEnabled(enabled)
        val current = settings.value
        if (current != null) {
            if (current.nomediaEnabled == enabled) {
                return
            }
            settings.value = current.copy(nomediaEnabled = enabled)
        } else {
            refresh()
        }
    }

    fun updateSearchFolders(folders: Set<String>, useAll: Boolean) {
        repository.updateSearchFolders(folders, useAll)
        val current = settings.value
        if (current != null) {
            if (
                current.searchFoldersUseAll == useAll &&
                current.searchFolders == folders
            ) {
                return
            }
            settings.value = current.copy(
                searchFoldersUseAll = useAll,
                searchFolders = folders
            )
        } else {
            refresh()
        }
    }

    fun updateVisibleItems(items: Set<VisibleItem>) {
        repository.updateVisibleItems(items)
        val current = settings.value
        if (current != null) {
            settings.value = current.copy(visibleItems = items)
        } else {
            refresh()
        }
    }

    fun updateVisibleItem(item: VisibleItem, enabled: Boolean) {
        repository.updateVisibleItem(item, enabled)
        val current = settings.value
        if (current != null) {
            val next = current.visibleItems.toMutableSet()
            if (enabled) {
                next.add(item)
            } else {
                next.remove(item)
            }
            if (next != current.visibleItems) {
                settings.value = current.copy(visibleItems = next)
            }
        } else {
            refresh()
        }
    }
}
