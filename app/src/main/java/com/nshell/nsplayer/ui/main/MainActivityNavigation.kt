package com.nshell.nsplayer.ui.main

import android.content.Intent
import com.nshell.nsplayer.ui.player.PlayerActivity

internal fun MainActivity.onItemSelected(item: DisplayItem) {
    when (item.type) {
        DisplayItem.Type.FOLDER -> {
            viewModel.updateState {
                it.copy(
                    selectedBucketId = item.bucketId,
                    selectedBucketName = item.title,
                    inFolderVideos = true
                )
            }
            loadIfPermitted()
        }
        DisplayItem.Type.HIERARCHY -> {
            viewModel.updateState {
                it.copy(
                    hierarchyPath = item.bucketId ?: "",
                    inFolderVideos = false,
                    selectedBucketId = null,
                    selectedBucketName = null
                )
            }
            loadIfPermitted()
        }
        DisplayItem.Type.VIDEO -> {
            val uri = item.contentUri
            if (uri.isNullOrEmpty()) {
                return
            }
            val intent = Intent(this, PlayerActivity::class.java)
            intent.putExtra(PlayerActivity.EXTRA_URI, uri)
            intent.putExtra(PlayerActivity.EXTRA_TITLE, item.title)
            startActivity(intent)
        }
    }
}

internal fun MainActivity.setMode(mode: VideoMode) {
    viewModel.updateState {
        it.copy(
            currentMode = mode,
            inFolderVideos = false,
            selectedBucketId = null,
            selectedBucketName = null,
            hierarchyPath = if (mode == VideoMode.HIERARCHY) "" else it.hierarchyPath
        )
    }
    loadIfPermitted()
}

internal fun MainActivity.handleBackNavigation(): Boolean {
    if (selectionController.isSelectionMode()) {
        selectionController.clearSelection()
        return true
    }
    val current = viewModel.getState().value ?: browserState
    if (current.currentMode == VideoMode.HIERARCHY && current.hierarchyPath.isNotEmpty()) {
        val nextPath = getParentPath(current.hierarchyPath)
        viewModel.updateState { it.copy(hierarchyPath = nextPath) }
        loadIfPermitted()
        return true
    }
    if (current.inFolderVideos) {
        setMode(VideoMode.FOLDERS)
        return true
    }
    return false
}
