package com.nshell.nsplayer.ui.base

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import com.nshell.nsplayer.data.settings.SettingsRepository

open class BaseActivity : AppCompatActivity() {
    private var lastLanguageTag: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        lastLanguageTag = SettingsRepository(this).load().languageTag
    }

    override fun onResume() {
        super.onResume()
        val currentTag = SettingsRepository(this).load().languageTag
        if (currentTag != lastLanguageTag && !isFinishing && !isDestroyed) {
            lastLanguageTag = currentTag
            recreate()
        }
    }
}
