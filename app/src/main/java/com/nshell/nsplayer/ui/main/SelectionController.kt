package com.nshell.nsplayer.ui.main

import android.view.View
import android.widget.Button

class SelectionController(
    private val selectionBar: View,
    private val selectionAllButton: Button,
    private val adapter: VideoListAdapter
) {
    fun bind() {
        selectionAllButton.setOnClickListener {
            if (adapter.isAllSelected()) {
                adapter.clearSelection()
            } else {
                adapter.selectAll()
            }
        }
    }

    fun onSelectionChanged(selectionMode: Boolean) {
        selectionBar.visibility = if (selectionMode) View.VISIBLE else View.GONE
    }

    fun isSelectionMode(): Boolean = adapter.isSelectionMode()

    fun clearSelection() {
        adapter.clearSelection()
    }
}
