package com.nshell.nsplayer.ui.widget.recent

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.widget.RemoteViews
import com.nshell.nsplayer.R
import com.nshell.nsplayer.ui.player.PlayerActivity

class RecentPlaybackWidgetProvider : AppWidgetProvider() {

    override fun onUpdate(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetIds: IntArray
    ) {
        appWidgetIds.forEach { appWidgetId ->
            updateWidget(context, appWidgetManager, appWidgetId)
        }
    }

    override fun onAppWidgetOptionsChanged(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetId: Int,
        newOptions: android.os.Bundle
    ) {
        updateWidget(context, appWidgetManager, appWidgetId)
    }

    companion object {
        fun refreshAll(context: Context) {
            val appContext = context.applicationContext
            val appWidgetManager = AppWidgetManager.getInstance(appContext)
            val component = ComponentName(appContext, RecentPlaybackWidgetProvider::class.java)
            val appWidgetIds = appWidgetManager.getAppWidgetIds(component)
            if (appWidgetIds.isEmpty()) {
                return
            }
            appWidgetIds.forEach { appWidgetId ->
                updateWidget(appContext, appWidgetManager, appWidgetId)
            }
        }

        internal fun resolveMaxRows(context: Context, appWidgetId: Int): Int {
            if (appWidgetId == AppWidgetManager.INVALID_APPWIDGET_ID) {
                return MAX_ROWS
            }
            val options = AppWidgetManager.getInstance(context).getAppWidgetOptions(appWidgetId)
            val maxHeight = options.getInt(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT, 0)
            val minHeight = options.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT, 0)
            val heightDp = if (maxHeight > 0) maxHeight else minHeight
            val rows = when {
                heightDp <= 0 -> MAX_ROWS
                heightDp < HEIGHT_TWO_ROWS_DP -> 1
                heightDp < HEIGHT_THREE_ROWS_DP -> 2
                else -> 3
            }
            return rows.coerceIn(1, MAX_ROWS)
        }

        private fun updateWidget(
            context: Context,
            appWidgetManager: AppWidgetManager,
            appWidgetId: Int
        ) {
            val views = RemoteViews(context.packageName, R.layout.widget_recent_playback)
            views.setTextViewText(R.id.widgetRecentHeader, context.getString(R.string.widget_recent_playback_title))

            val serviceIntent = Intent(context, RecentPlaybackWidgetService::class.java).apply {
                putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId)
                data = Uri.parse(toUri(Intent.URI_INTENT_SCHEME))
            }
            views.setRemoteAdapter(R.id.widgetRecentList, serviceIntent)
            views.setEmptyView(R.id.widgetRecentList, R.id.widgetRecentEmpty)

            val templateIntent = Intent(context, PlayerActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            }
            val clickTemplate = PendingIntent.getActivity(
                context,
                appWidgetId,
                templateIntent,
                pendingIntentFlags()
            )
            views.setPendingIntentTemplate(R.id.widgetRecentList, clickTemplate)

            appWidgetManager.updateAppWidget(appWidgetId, views)
            appWidgetManager.notifyAppWidgetViewDataChanged(
                intArrayOf(appWidgetId),
                R.id.widgetRecentList
            )
        }

        private fun pendingIntentFlags(): Int {
            var flags = PendingIntent.FLAG_UPDATE_CURRENT
            flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                flags or PendingIntent.FLAG_MUTABLE
            } else {
                flags
            }
            return flags
        }

        private const val MAX_ROWS = 3
        private const val HEIGHT_TWO_ROWS_DP = 110
        private const val HEIGHT_THREE_ROWS_DP = 170
    }
}
