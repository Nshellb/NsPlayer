package com.nshell.nsplayer.ui.settings.advanced

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.AdapterView
import android.widget.CheckBox
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.ViewModelProvider
import com.nshell.nsplayer.R
import com.nshell.nsplayer.NsPlayerApp
import com.nshell.nsplayer.data.settings.ThemeMode
import com.nshell.nsplayer.data.settings.SettingsState
import com.nshell.nsplayer.ui.base.BaseActivity
import com.nshell.nsplayer.data.settings.VisibleItem
import com.nshell.nsplayer.ui.settings.SettingsViewModel
import com.nshell.nsplayer.ui.settings.searchfolders.SearchFoldersActivity

class AdvancedSettingsActivity : BaseActivity() {
    private lateinit var settingsViewModel: SettingsViewModel
    private var updating = false
    private val languageTags = listOf<String?>(null, "ko", "en")
    private var currentSettings: SettingsState? = null
    private var pendingLanguageRecreate = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_advanced_settings)
        supportActionBar?.hide()

        val backButton = findViewById<View>(R.id.commonBackButton)
        backButton.setOnClickListener { finish() }

        val titleText = findViewById<TextView>(R.id.commonTitleText)
        titleText.text = getString(R.string.advanced_settings_title)

        val searchFoldersRow = findViewById<View>(R.id.advancedSearchFoldersRow)
        val searchTitle = searchFoldersRow.findViewById<TextView>(R.id.settingsRowTitle)
        searchTitle.text = getString(R.string.advanced_settings_search_folders)
        searchFoldersRow.setOnClickListener {
            startActivity(Intent(this, SearchFoldersActivity::class.java))
        }

        settingsViewModel = ViewModelProvider(this)[SettingsViewModel::class.java]

        val languageSpinner = findViewById<Spinner>(R.id.advancedLanguageSpinner)
        languageSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(
                parent: AdapterView<*>?,
                view: View?,
                position: Int,
                id: Long
            ) {
                if (updating) {
                    return
                }
                if (currentSettings == null) {
                    return
                }
                val tag = languageTags.getOrNull(position) ?: languageTags.first()
                if (currentSettings?.languageTag == tag) {
                    return
                }
                settingsViewModel.updateLanguageTag(tag)
                NsPlayerApp.applyLanguage(tag, recreate = false)
                requestLanguageRecreate()
            }

            override fun onNothingSelected(parent: AdapterView<*>?) = Unit
        }

        val themeSpinner = findViewById<Spinner>(R.id.advancedThemeSpinner)
        themeSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(
                parent: AdapterView<*>?,
                view: View?,
                position: Int,
                id: Long
            ) {
                if (updating) {
                    return
                }
                if (currentSettings == null) {
                    return
                }
                val mode = when (position) {
                    1 -> ThemeMode.LIGHT
                    2 -> ThemeMode.DARK
                    else -> ThemeMode.SYSTEM
                }
                if (currentSettings?.themeMode == mode) {
                    return
                }
                settingsViewModel.updateThemeMode(mode)
                NsPlayerApp.applyTheme(mode)
            }

            override fun onNothingSelected(parent: AdapterView<*>?) = Unit
        }

        val toastMessage = getString(R.string.action_not_ready_long)
        val noMediaRow = findViewById<View>(R.id.advancedNoMediaRow)
        val noMediaTitle = noMediaRow.findViewById<TextView>(R.id.settingsRowTitle)
        noMediaTitle.text = getString(R.string.advanced_settings_nomedia)
        val noMediaCheckBox = noMediaRow.findViewById<CheckBox>(R.id.settingsRowCheckBox)
        noMediaCheckBox.setOnCheckedChangeListener { _, isChecked ->
            if (updating) {
                return@setOnCheckedChangeListener
            }
            if (currentSettings?.nomediaEnabled == isChecked) {
                return@setOnCheckedChangeListener
            }
            settingsViewModel.updateNomediaEnabled(isChecked)
            Toast.makeText(this, toastMessage, Toast.LENGTH_SHORT).show()
        }

        val visibleItemIds = listOf(
            R.id.visibleItemThumbnail,
            R.id.visibleItemDuration,
            R.id.visibleItemExtension,
            R.id.visibleItemResolution,
            R.id.visibleItemFrameRate,
            R.id.visibleItemSize,
            R.id.visibleItemModified
        )
        val visibleItemMap = mapOf(
            R.id.visibleItemThumbnail to VisibleItem.THUMBNAIL,
            R.id.visibleItemDuration to VisibleItem.DURATION,
            R.id.visibleItemExtension to VisibleItem.EXTENSION,
            R.id.visibleItemResolution to VisibleItem.RESOLUTION,
            R.id.visibleItemFrameRate to VisibleItem.FRAME_RATE,
            R.id.visibleItemSize to VisibleItem.SIZE,
            R.id.visibleItemModified to VisibleItem.MODIFIED
        )
        visibleItemIds.forEach { id ->
            findViewById<CheckBox>(id).setOnCheckedChangeListener { _, isChecked ->
                if (updating) {
                    return@setOnCheckedChangeListener
                }
                val item = visibleItemMap[id] ?: return@setOnCheckedChangeListener
                if (currentSettings?.visibleItems?.contains(item) == isChecked) {
                    return@setOnCheckedChangeListener
                }
                settingsViewModel.updateVisibleItem(item, isChecked)
            }
        }

        settingsViewModel.getSettings().observe(this) { settings ->
            updating = true
            currentSettings = settings
            val languagePosition = languageTags.indexOf(settings.languageTag).let { idx ->
                if (idx >= 0) idx else 0
            }
            if (languageSpinner.selectedItemPosition != languagePosition) {
                languageSpinner.setSelection(languagePosition, false)
            }
            val targetPosition = when (settings.themeMode) {
                ThemeMode.LIGHT -> 1
                ThemeMode.DARK -> 2
                ThemeMode.SYSTEM -> 0
            }
            if (themeSpinner.selectedItemPosition != targetPosition) {
                themeSpinner.setSelection(targetPosition, false)
            }
            if (noMediaCheckBox.isChecked != settings.nomediaEnabled) {
                noMediaCheckBox.isChecked = settings.nomediaEnabled
            }
            visibleItemIds.forEach { id ->
                val item = visibleItemMap[id] ?: return@forEach
                val checkBox = findViewById<CheckBox>(id)
                val shouldChecked = settings.visibleItems.contains(item)
                if (checkBox.isChecked != shouldChecked) {
                    checkBox.isChecked = shouldChecked
                }
            }
            updating = false
        }

        val topBar = findViewById<View>(R.id.commonTopBar)
            ?: findViewById(R.id.advancedTopBarInclude)
        if (topBar != null) {
            ViewCompat.setOnApplyWindowInsetsListener(topBar) { view, insets ->
                val topInset = insets.getInsets(WindowInsetsCompat.Type.systemBars()).top
                view.setPadding(
                    view.paddingLeft,
                    topInset + dpToPx(12),
                    view.paddingRight,
                    view.paddingBottom
                )
                insets
            }
        }
    }

    private fun dpToPx(dp: Int): Int {
        val density = resources.displayMetrics.density
        return Math.round(dp * density)
    }

    private fun requestLanguageRecreate() {
        if (pendingLanguageRecreate || isFinishing || isDestroyed) {
            return
        }
        pendingLanguageRecreate = true
        window.decorView.post {
            pendingLanguageRecreate = false
            if (!isFinishing && !isDestroyed) {
                recreate()
            }
        }
    }
}
