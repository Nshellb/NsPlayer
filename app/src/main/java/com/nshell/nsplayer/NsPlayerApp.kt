package com.nshell.nsplayer

import android.app.Application
import androidx.appcompat.app.AppCompatDelegate
import com.nshell.nsplayer.data.settings.SettingsRepository
import com.nshell.nsplayer.data.settings.ThemeMode

class NsPlayerApp : Application() {
    override fun onCreate() {
        super.onCreate()
        val themeMode = SettingsRepository(this).load().themeMode
        applyTheme(themeMode)
    }

    companion object {
        fun applyTheme(themeMode: ThemeMode) {
            val nightMode = when (themeMode) {
                ThemeMode.SYSTEM -> AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
                ThemeMode.LIGHT -> AppCompatDelegate.MODE_NIGHT_NO
                ThemeMode.DARK -> AppCompatDelegate.MODE_NIGHT_YES
            }
            AppCompatDelegate.setDefaultNightMode(nightMode)
        }
    }
}
