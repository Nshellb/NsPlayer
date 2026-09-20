package com.nshell.nsplayer.ui.base

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import com.nshell.nsplayer.data.settings.SettingsRepository

open class BaseActivity : AppCompatActivity() {
    private var lastLanguageTag: String? = null
    private val languageSettings by lazy { SettingsRepository(this) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        lastLanguageTag = languageSettings.loadLanguageTag()
    }

    override fun onResume() {
        super.onResume()
        val currentTag = languageSettings.loadLanguageTag()
        if (currentTag != lastLanguageTag && !isFinishing && !isDestroyed) {
            lastLanguageTag = currentTag
            recreate()
        }
    }
}
