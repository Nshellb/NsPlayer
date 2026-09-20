package com.nshell.nsplayer.ui.main

import com.nshell.nsplayer.ui.player.PlayerActivity

internal fun MainActivity.onItemSelected(item: DisplayItem) {
    if (item !in adapter.currentList) {
        return
    }
    when (item.type) {
        DisplayItem.Type.FOLDER -> {
            viewModel.updateState {
                it.copy(
                    selectedBucketId = item.bucketId,
                    selectedBucketName = item.title,
                    inFolderVideos = true
                )
            }
            loadIfPermitted(useCache = true)
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
            loadIfPermitted(useCache = true)
        }
        DisplayItem.Type.VIDEO -> {
            val uri = item.contentUri
            if (uri.isNullOrEmpty()) {
                return
            }
            val intent = PlayerActivity.createLaunchIntent(this)
            intent.putExtra(PlayerActivity.EXTRA_URI, uri)
            intent.putExtra(PlayerActivity.EXTRA_TITLE, item.title)
            launchPlayer(intent)
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
    if (exitSearchMode()) {
        browserBackGuard.onNavigationHandled()
        return true
    }
    if (selectionController.isSelectionMode()) {
        selectionController.clearSelection()
        browserBackGuard.onNavigationHandled()
        return true
    }
    if (viewModel.navigateUp()) {
        browserBackGuard.onNavigationHandled()
        loadIfPermitted(useCache = true)
        return true
    }
    return false
}
