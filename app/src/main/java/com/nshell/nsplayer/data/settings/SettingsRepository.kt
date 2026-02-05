package com.nshell.nsplayer.data.settings

import android.content.Context
import android.content.SharedPreferences
import com.nshell.nsplayer.ui.main.VideoDisplayMode
import com.nshell.nsplayer.ui.main.VideoMode
import com.nshell.nsplayer.ui.main.VideoSortMode
import com.nshell.nsplayer.ui.main.VideoSortOrder

class SettingsRepository(context: Context) {
    private val preferences: SharedPreferences =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun load(): SettingsState {
        val modeValue = preferences.getString(KEY_MODE, VideoMode.FOLDERS.name)
        val displayValue = preferences.getString(KEY_DISPLAY, VideoDisplayMode.LIST.name)
        val tileSpanValue = preferences.getInt(KEY_TILE_SPAN, 2)
        val sortValue = preferences.getString(KEY_SORT, VideoSortMode.MODIFIED.name)
        val sortOrderValue = preferences.getString(KEY_SORT_ORDER, VideoSortOrder.DESC.name)
        val languageTag = preferences.getString(KEY_LANGUAGE, null)
        val nomediaEnabled = preferences.getBoolean(KEY_NOMEDIA, false)
        val visibleItems = preferences.getStringSet(KEY_VISIBLE_ITEMS, null)
            ?.mapNotNull { runCatching { VisibleItem.valueOf(it) }.getOrNull() }
            ?.toSet()
            ?: SettingsState().visibleItems

        val mode = runCatching {
            VideoMode.valueOf(modeValue ?: VideoMode.FOLDERS.name)
        }.getOrElse { VideoMode.FOLDERS }
        val displayMode = runCatching {
            VideoDisplayMode.valueOf(displayValue ?: VideoDisplayMode.LIST.name)
        }.getOrElse { VideoDisplayMode.LIST }
        val tileSpan = when (tileSpanValue) {
            2, 3, 4 -> tileSpanValue
            else -> 2
        }
        val sortMode = runCatching {
            VideoSortMode.valueOf(sortValue ?: VideoSortMode.MODIFIED.name)
        }.getOrElse { VideoSortMode.MODIFIED }
        val sortOrder = runCatching {
            VideoSortOrder.valueOf(sortOrderValue ?: VideoSortOrder.DESC.name)
        }.getOrElse { VideoSortOrder.DESC }

        return SettingsState(
            mode = mode,
            displayMode = displayMode,
            tileSpanCount = tileSpan,
            sortMode = sortMode,
            sortOrder = sortOrder,
            languageTag = languageTag,
            nomediaEnabled = nomediaEnabled,
            visibleItems = visibleItems
        )
    }

    fun updateMode(mode: VideoMode) {
        preferences.edit().putString(KEY_MODE, mode.name).apply()
    }

    fun updateDisplayMode(displayMode: VideoDisplayMode) {
        preferences.edit().putString(KEY_DISPLAY, displayMode.name).apply()
    }

    fun updateTileSpanCount(tileSpanCount: Int) {
        preferences.edit().putInt(KEY_TILE_SPAN, tileSpanCount).apply()
    }

    fun updateSortMode(sortMode: VideoSortMode) {
        preferences.edit().putString(KEY_SORT, sortMode.name).apply()
    }

    fun updateSortOrder(sortOrder: VideoSortOrder) {
        preferences.edit().putString(KEY_SORT_ORDER, sortOrder.name).apply()
    }

    fun updateLanguageTag(languageTag: String?) {
        preferences.edit().putString(KEY_LANGUAGE, languageTag).apply()
    }

    fun updateNomediaEnabled(enabled: Boolean) {
        preferences.edit().putBoolean(KEY_NOMEDIA, enabled).apply()
    }

    fun updateVisibleItems(items: Set<VisibleItem>) {
        val values = items.map { it.name }.toSet()
        preferences.edit().putStringSet(KEY_VISIBLE_ITEMS, values).apply()
    }

    companion object {
        private const val PREFS = "nsplayer_prefs"
        private const val KEY_MODE = "video_mode"
        private const val KEY_DISPLAY = "video_display"
        private const val KEY_TILE_SPAN = "video_tile_span"
        private const val KEY_SORT = "video_sort"
        private const val KEY_SORT_ORDER = "video_sort_order"
        private const val KEY_LANGUAGE = "language_tag"
        private const val KEY_NOMEDIA = "nomedia_enabled"
        private const val KEY_VISIBLE_ITEMS = "visible_items"
    }
}
