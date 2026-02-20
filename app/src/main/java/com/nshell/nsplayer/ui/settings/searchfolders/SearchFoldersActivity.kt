package com.nshell.nsplayer.ui.settings.searchfolders

import android.os.Bundle
import android.view.View
import android.widget.TextView
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.nshell.nsplayer.R
import com.nshell.nsplayer.ui.base.BaseActivity

class SearchFoldersActivity : BaseActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_search_folders)
        supportActionBar?.hide()

        val backButton = findViewById<View>(R.id.commonBackButton)
        backButton.setOnClickListener { finish() }

        val titleText = findViewById<TextView>(R.id.commonTitleText)
        titleText.text = getString(R.string.search_folders_title)

        val topBar = findViewById<View>(R.id.commonTopBar)
            ?: findViewById(R.id.searchFoldersTopBarInclude)
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
}
