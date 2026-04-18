package com.nshell.nsplayer.ui.widget.recent

import android.content.Context
import android.content.Intent
import android.widget.RemoteViews
import android.widget.RemoteViewsService
import com.nshell.nsplayer.R
import com.nshell.nsplayer.data.recent.RecentPlaybackStore
import com.nshell.nsplayer.ui.player.PlayerActivity
import java.util.Locale

class RecentPlaybackWidgetViewsFactory(
    private val context: Context,
    private val appWidgetId: Int
) : RemoteViewsService.RemoteViewsFactory {

    private val recentPlaybackStore = RecentPlaybackStore(context)
    private var items: List<RecentPlaybackStore.Item> = emptyList()

    override fun onCreate() = Unit

    override fun onDataSetChanged() {
        val maxRows = RecentPlaybackWidgetProvider.resolveMaxRows(context, appWidgetId)
        items = recentPlaybackStore.loadRecent(maxRows)
    }

    override fun onDestroy() {
        items = emptyList()
    }

    override fun getCount(): Int = items.size

    override fun getViewAt(position: Int): RemoteViews? {
        if (position !in items.indices) {
            return null
        }
        val item = items[position]
        val views = RemoteViews(context.packageName, R.layout.widget_recent_playback_item)
        views.setTextViewText(R.id.widgetRecentItemTitle, item.title)
        views.setTextViewText(R.id.widgetRecentItemSubtitle, buildSubtitle(item))

        val fillInIntent = Intent().apply {
            putExtra(PlayerActivity.EXTRA_URI, item.uri)
            putExtra(PlayerActivity.EXTRA_TITLE, item.title)
        }
        views.setOnClickFillInIntent(R.id.widgetRecentItemRoot, fillInIntent)
        return views
    }

    override fun getLoadingView(): RemoteViews? = null

    override fun getViewTypeCount(): Int = 1

    override fun getItemId(position: Int): Long {
        return items.getOrNull(position)?.uri?.hashCode()?.toLong() ?: position.toLong()
    }

    override fun hasStableIds(): Boolean = true

    private fun buildSubtitle(item: RecentPlaybackStore.Item): String {
        val position = item.positionMs.coerceAtLeast(0L)
        val duration = item.durationMs.coerceAtLeast(0L)
        if (position <= 0L && duration <= 0L) {
            return context.getString(R.string.widget_recent_subtitle_recent)
        }
        return if (duration > 0L) {
            context.getString(
                R.string.widget_recent_subtitle_resume_with_duration,
                formatTime(position),
                formatTime(duration)
            )
        } else {
            context.getString(
                R.string.widget_recent_subtitle_resume,
                formatTime(position)
            )
        }
    }

    private fun formatTime(ms: Long): String {
        val totalSeconds = (ms / 1000L).coerceAtLeast(0L)
        val seconds = totalSeconds % 60
        val minutes = (totalSeconds / 60) % 60
        val hours = totalSeconds / 3600
        return if (hours > 0) {
            String.format(Locale.US, "%d:%02d:%02d", hours, minutes, seconds)
        } else {
            String.format(Locale.US, "%02d:%02d", minutes, seconds)
        }
    }
}
