package com.nshell.nsplayer.ui.widget.recent

import android.appwidget.AppWidgetManager
import android.content.Intent
import android.widget.RemoteViewsService

class RecentPlaybackWidgetService : RemoteViewsService() {
    override fun onGetViewFactory(intent: Intent): RemoteViewsFactory {
        val appWidgetId = intent.getIntExtra(
            AppWidgetManager.EXTRA_APPWIDGET_ID,
            AppWidgetManager.INVALID_APPWIDGET_ID
        )
        return RecentPlaybackWidgetViewsFactory(applicationContext, appWidgetId)
    }
}
