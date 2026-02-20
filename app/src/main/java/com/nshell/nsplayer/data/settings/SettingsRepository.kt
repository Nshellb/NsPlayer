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
        val themeValue = preferences.getString(KEY_THEME, ThemeMode.SYSTEM.name)
        val nomediaEnabled = preferences.getBoolean(KEY_NOMEDIA, false)
        val defaultVisibleItems = SettingsState().visibleItems
        val legacyVisibleItems = preferences.getStringSet(KEY_VISIBLE_ITEMS, null)
            ?.mapNotNull { runCatching { VisibleItem.valueOf(it) }.getOrNull() }
            ?.toSet()

        val visibleItems = if (hasVisibleItemKeys()) {
            VisibleItem.values().filter { item ->
                preferences.getBoolean(visibleItemKey(item), defaultVisibleItems.contains(item))
            }.toSet()
        } else {
            legacyVisibleItems ?: defaultVisibleItems
        }

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
        val themeMode = runCatching {
            ThemeMode.valueOf(themeValue ?: ThemeMode.SYSTEM.name)
        }.getOrElse { ThemeMode.SYSTEM }

        val state = SettingsState(
            mode = mode,
            displayMode = displayMode,
            tileSpanCount = tileSpan,
            sortMode = sortMode,
            sortOrder = sortOrder,
            languageTag = languageTag,
            themeMode = themeMode,
            nomediaEnabled = nomediaEnabled,
            visibleItems = visibleItems
        )
        if (!hasVisibleItemKeys()) {
            persistVisibleItems(visibleItems)
        }
        return state
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

    fun updateThemeMode(themeMode: ThemeMode) {
        preferences.edit().putString(KEY_THEME, themeMode.name).apply()
    }

    fun updateNomediaEnabled(enabled: Boolean) {
        preferences.edit().putBoolean(KEY_NOMEDIA, enabled).apply()
    }

    fun updateVisibleItems(items: Set<VisibleItem>) {
        persistVisibleItems(items)
    }

    fun updateVisibleItem(item: VisibleItem, enabled: Boolean) {
        preferences.edit().putBoolean(visibleItemKey(item), enabled).apply()
    }

    companion object {
        private const val PREFS = "nsplayer_prefs"
        private const val KEY_MODE = "video_mode"
        private const val KEY_DISPLAY = "video_display"
        private const val KEY_TILE_SPAN = "video_tile_span"
        private const val KEY_SORT = "video_sort"
        private const val KEY_SORT_ORDER = "video_sort_order"
        private const val KEY_LANGUAGE = "language_tag"
        private const val KEY_THEME = "theme_mode"
        private const val KEY_NOMEDIA = "nomedia_enabled"
        private const val KEY_VISIBLE_ITEMS = "visible_items"
        private const val KEY_VISIBLE_ITEM_THUMBNAIL = "visible_item_thumbnail"
        private const val KEY_VISIBLE_ITEM_DURATION = "visible_item_duration"
        private const val KEY_VISIBLE_ITEM_EXTENSION = "visible_item_extension"
        private const val KEY_VISIBLE_ITEM_RESOLUTION = "visible_item_resolution"
        private const val KEY_VISIBLE_ITEM_FRAME_RATE = "visible_item_frame_rate"
        private const val KEY_VISIBLE_ITEM_SIZE = "visible_item_size"
        private const val KEY_VISIBLE_ITEM_MODIFIED = "visible_item_modified"

        private fun visibleItemKey(item: VisibleItem): String = when (item) {
            VisibleItem.THUMBNAIL -> KEY_VISIBLE_ITEM_THUMBNAIL
            VisibleItem.DURATION -> KEY_VISIBLE_ITEM_DURATION
            VisibleItem.EXTENSION -> KEY_VISIBLE_ITEM_EXTENSION
            VisibleItem.RESOLUTION -> KEY_VISIBLE_ITEM_RESOLUTION
            VisibleItem.FRAME_RATE -> KEY_VISIBLE_ITEM_FRAME_RATE
            VisibleItem.SIZE -> KEY_VISIBLE_ITEM_SIZE
            VisibleItem.MODIFIED -> KEY_VISIBLE_ITEM_MODIFIED
        }
    }

    private fun hasVisibleItemKeys(): Boolean {
        val keys = preferences.all.keys
        return keys.contains(KEY_VISIBLE_ITEM_THUMBNAIL) ||
            keys.contains(KEY_VISIBLE_ITEM_DURATION) ||
            keys.contains(KEY_VISIBLE_ITEM_EXTENSION) ||
            keys.contains(KEY_VISIBLE_ITEM_RESOLUTION) ||
            keys.contains(KEY_VISIBLE_ITEM_FRAME_RATE) ||
            keys.contains(KEY_VISIBLE_ITEM_SIZE) ||
            keys.contains(KEY_VISIBLE_ITEM_MODIFIED)
    }

    private fun persistVisibleItems(items: Set<VisibleItem>) {
        val editor = preferences.edit()
        editor.putBoolean(KEY_VISIBLE_ITEM_THUMBNAIL, items.contains(VisibleItem.THUMBNAIL))
        editor.putBoolean(KEY_VISIBLE_ITEM_DURATION, items.contains(VisibleItem.DURATION))
        editor.putBoolean(KEY_VISIBLE_ITEM_EXTENSION, items.contains(VisibleItem.EXTENSION))
        editor.putBoolean(KEY_VISIBLE_ITEM_RESOLUTION, items.contains(VisibleItem.RESOLUTION))
        editor.putBoolean(KEY_VISIBLE_ITEM_FRAME_RATE, items.contains(VisibleItem.FRAME_RATE))
        editor.putBoolean(KEY_VISIBLE_ITEM_SIZE, items.contains(VisibleItem.SIZE))
        editor.putBoolean(KEY_VISIBLE_ITEM_MODIFIED, items.contains(VisibleItem.MODIFIED))
        editor.apply()
    }
}
