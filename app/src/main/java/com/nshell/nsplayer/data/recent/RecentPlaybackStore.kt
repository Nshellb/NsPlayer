package com.nshell.nsplayer.data.recent

import android.content.Context
import android.net.Uri
import android.os.Process
import com.nshell.nsplayer.ui.widget.recent.RecentPlaybackWidgetProvider
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.Executors

class RecentPlaybackStore(context: Context) {
    private val appContext = context.applicationContext
    private val preferences =
        appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun recordPlayback(
        uri: Uri,
        title: String,
        positionMs: Long,
        durationMs: Long
    ) {
        val uriText = uri.toString()
        if (uriText.isBlank()) {
            return
        }
        val normalizedTitle = title.ifBlank {
            uri.lastPathSegment?.substringAfterLast('/') ?: uriText
        }
        val item = Item(
            uri = uriText,
            title = normalizedTitle,
            positionMs = positionMs.coerceAtLeast(0L),
            durationMs = durationMs.coerceAtLeast(0L),
            playedAtMs = System.currentTimeMillis()
        )
        // Share one queue across Activity instances so an older snapshot cannot
        // overwrite a newer one. Only the application context is retained.
        writer.execute {
            savePlayback(item)
        }
    }

    private fun savePlayback(item: Item) {
        val updated = loadRecentInternal().toMutableList()
        updated.removeAll { it.uri == item.uri }
        updated.add(0, item)
        if (updated.size > MAX_ITEMS) {
            updated.subList(MAX_ITEMS, updated.size).clear()
        }
        saveRecentInternal(updated)
        RecentPlaybackWidgetProvider.refreshAll(appContext)
    }

    fun loadRecent(maxItems: Int = MAX_ITEMS): List<Item> {
        if (maxItems <= 0) {
            return emptyList()
        }
        return loadRecentInternal().take(maxItems)
    }

    private fun loadRecentInternal(): List<Item> {
        val json = preferences.getString(KEY_RECENT_ITEMS, null) ?: return emptyList()
        return runCatching {
            val array = JSONArray(json)
            val parsed = mutableListOf<Item>()
            for (i in 0 until array.length()) {
                val obj = array.optJSONObject(i) ?: continue
                val uri = obj.optString(JSON_URI, "")
                if (uri.isBlank()) {
                    continue
                }
                val title = obj.optString(JSON_TITLE, "")
                parsed.add(
                    Item(
                        uri = uri,
                        title = title,
                        positionMs = obj.optLong(JSON_POSITION_MS, 0L).coerceAtLeast(0L),
                        durationMs = obj.optLong(JSON_DURATION_MS, 0L).coerceAtLeast(0L),
                        playedAtMs = obj.optLong(JSON_PLAYED_AT_MS, 0L).coerceAtLeast(0L)
                    )
                )
            }
            parsed.sortedByDescending { it.playedAtMs }
        }.getOrElse { emptyList() }
    }

    private fun saveRecentInternal(items: List<Item>) {
        // Already on the writer thread: finish persistence here instead of
        // adding an apply() flush that the Activity lifecycle may wait for.
        if (items.isEmpty()) {
            preferences.edit().remove(KEY_RECENT_ITEMS).commit()
            return
        }
        val array = JSONArray()
        items.forEach { item ->
            array.put(
                JSONObject()
                    .put(JSON_URI, item.uri)
                    .put(JSON_TITLE, item.title)
                    .put(JSON_POSITION_MS, item.positionMs)
                    .put(JSON_DURATION_MS, item.durationMs)
                    .put(JSON_PLAYED_AT_MS, item.playedAtMs)
            )
        }
        preferences.edit().putString(KEY_RECENT_ITEMS, array.toString()).commit()
    }

    data class Item(
        val uri: String,
        val title: String,
        val positionMs: Long,
        val durationMs: Long,
        val playedAtMs: Long
    )

    companion object {
        private val writer = Executors.newSingleThreadExecutor { task ->
            Thread({
                Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND)
                task.run()
            }, "recent-playback-writer")
        }

        private const val PREFS = "nsplayer_prefs"
        private const val KEY_RECENT_ITEMS = "recent_playback_items"
        private const val MAX_ITEMS = 50
        private const val JSON_URI = "uri"
        private const val JSON_TITLE = "title"
        private const val JSON_POSITION_MS = "positionMs"
        private const val JSON_DURATION_MS = "durationMs"
        private const val JSON_PLAYED_AT_MS = "playedAtMs"
    }
}
