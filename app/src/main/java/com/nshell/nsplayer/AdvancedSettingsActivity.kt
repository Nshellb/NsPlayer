package com.nshell.nsplayer

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.CheckBox
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat

class AdvancedSettingsActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_advanced_settings)
        supportActionBar?.hide()

        val backButton = findViewById<View>(R.id.advancedBackButton)
        backButton.setOnClickListener { finish() }

        val searchFoldersRow = findViewById<View>(R.id.advancedSearchFoldersRow)
        searchFoldersRow.setOnClickListener {
            startActivity(Intent(this, SearchFoldersActivity::class.java))
        }

        val toastMessage = getString(R.string.action_not_ready_long)
        val noMediaCheckBox = findViewById<CheckBox>(R.id.advancedNoMediaCheckBox)
        noMediaCheckBox.setOnCheckedChangeListener { _, _ ->
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
        visibleItemIds.forEach { id ->
            findViewById<CheckBox>(id).setOnCheckedChangeListener { _, _ ->
                Toast.makeText(this, toastMessage, Toast.LENGTH_SHORT).show()
            }
        }

        val topBar = findViewById<View>(R.id.topBar)
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
