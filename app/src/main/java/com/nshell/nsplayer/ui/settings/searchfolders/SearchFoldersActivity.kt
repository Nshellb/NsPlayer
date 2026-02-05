package com.nshell.nsplayer.ui.settings.searchfolders

import android.os.Bundle
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.nshell.nsplayer.R

class SearchFoldersActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_search_folders)
        supportActionBar?.hide()

        val backButton = findViewById<View>(R.id.searchFoldersBackButton)
        backButton.setOnClickListener { finish() }

        val topBar = findViewById<View>(R.id.searchFoldersTopBar)
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
