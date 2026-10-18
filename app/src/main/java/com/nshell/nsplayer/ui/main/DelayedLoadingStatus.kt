package com.nshell.nsplayer.ui.main

import android.view.View
import android.widget.TextView
import com.nshell.nsplayer.R

internal class DelayedLoadingStatus(private val view: TextView) {
    enum class Source { BROWSE, SEARCH }

    private var source: Source? = null
    private var pendingShow: Runnable? = null

    fun show(source: Source) {
        if (this.source != source) {
            cancel()
            this.source = source
            pendingShow = Runnable {
                pendingShow = null
                view.setText(R.string.status_loading)
                view.visibility = View.VISIBLE
            }.also { view.postDelayed(it, SHOW_DELAY_MS) }
        }
        view.setText(R.string.status_loading)
        view.visibility = if (pendingShow == null) View.VISIBLE else View.GONE
    }

    fun cancel() {
        pendingShow?.let { view.removeCallbacks(it) }
        pendingShow = null
        source = null
    }

    private companion object {
        const val SHOW_DELAY_MS = 500L
    }
}
