package com.nshell.nsplayer

import android.app.Activity
import android.app.Application
import android.content.Context
import android.os.Bundle
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat
import com.nshell.nsplayer.data.settings.SettingsRepository
import com.nshell.nsplayer.data.settings.ThemeMode

class NsPlayerApp : Application(), Application.ActivityLifecycleCallbacks {
    private val activities = mutableSetOf<Activity>()

    override fun onCreate() {
        super.onCreate()
        instance = this
        registerActivityLifecycleCallbacks(this)
        val settings = SettingsRepository(this).load()
        val themeMode = settings.themeMode
        applyTheme(themeMode)
        applyLanguage(settings.languageTag, recreate = false)
    }

    override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {
        activities.add(activity)
    }

    override fun onActivityDestroyed(activity: Activity) {
        activities.remove(activity)
    }

    override fun onActivityStarted(activity: Activity) = Unit
    override fun onActivityResumed(activity: Activity) = Unit
    override fun onActivityPaused(activity: Activity) = Unit
    override fun onActivityStopped(activity: Activity) = Unit
    override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit

    private fun recreateAllActivities() {
        val snapshot = activities.toList()
        snapshot.forEach { activity ->
            if (!activity.isFinishing && !activity.isDestroyed) {
                activity.recreate()
            }
        }
    }

    companion object {
        private var instance: NsPlayerApp? = null

        fun appContext(): Context? = instance

        fun applyTheme(themeMode: ThemeMode) {
            val nightMode = when (themeMode) {
                ThemeMode.SYSTEM -> AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
                ThemeMode.LIGHT -> AppCompatDelegate.MODE_NIGHT_NO
                ThemeMode.DARK -> AppCompatDelegate.MODE_NIGHT_YES
            }
            AppCompatDelegate.setDefaultNightMode(nightMode)
        }

        fun applyLanguage(languageTag: String?, recreate: Boolean = true) {
            val locales = if (languageTag.isNullOrBlank()) {
                LocaleListCompat.getEmptyLocaleList()
            } else {
                LocaleListCompat.forLanguageTags(languageTag)
            }
            val current = AppCompatDelegate.getApplicationLocales()
            if (current.toLanguageTags() == locales.toLanguageTags()) {
                return
            }
            AppCompatDelegate.setApplicationLocales(locales)
            if (recreate) {
                instance?.recreateAllActivities()
            }
        }
    }
}
