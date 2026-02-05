package com.nshell.nsplayer.ui.settings.advanced

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.CheckBox
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.ViewModelProvider
import com.nshell.nsplayer.R
import com.nshell.nsplayer.data.settings.VisibleItem
import com.nshell.nsplayer.ui.settings.SettingsViewModel
import com.nshell.nsplayer.ui.settings.searchfolders.SearchFoldersActivity

class AdvancedSettingsActivity : AppCompatActivity() {
    private lateinit var settingsViewModel: SettingsViewModel
    private var updating = false

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

        val toastMessage = getString(R.string.action_not_ready_long)
        val noMediaRow = findViewById<View>(R.id.advancedNoMediaRow)
        val noMediaTitle = noMediaRow.findViewById<TextView>(R.id.settingsRowTitle)
        noMediaTitle.text = getString(R.string.advanced_settings_nomedia)
        val noMediaCheckBox = noMediaRow.findViewById<CheckBox>(R.id.settingsRowCheckBox)
        noMediaCheckBox.setOnCheckedChangeListener { _, isChecked ->
            if (updating) {
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
            findViewById<CheckBox>(id).setOnCheckedChangeListener { _, _ ->
                if (updating) {
                    return@setOnCheckedChangeListener
                }
                val current = settingsViewModel.getSettings().value?.visibleItems ?: emptySet()
                val item = visibleItemMap[id] ?: return@setOnCheckedChangeListener
                val next = current.toMutableSet()
                if (next.contains(item)) {
                    next.remove(item)
                } else {
                    next.add(item)
                }
                settingsViewModel.updateVisibleItems(next)
                Toast.makeText(this, toastMessage, Toast.LENGTH_SHORT).show()
            }
        }

        settingsViewModel.getSettings().observe(this) { settings ->
            updating = true
            noMediaCheckBox.isChecked = settings.nomediaEnabled
            visibleItemIds.forEach { id ->
                val item = visibleItemMap[id] ?: return@forEach
                findViewById<CheckBox>(id).isChecked = settings.visibleItems.contains(item)
            }
            updating = false
        }

        val topBar = findViewById<View>(R.id.commonTopBar)
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

    private fun dpToPx(dp: Int): Int {
        val density = resources.displayMetrics.density
        return Math.round(dp * density)
    }
}
