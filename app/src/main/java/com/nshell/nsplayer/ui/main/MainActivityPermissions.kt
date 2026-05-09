package com.nshell.nsplayer.ui.main

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.view.View
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.nshell.nsplayer.R
import com.nshell.nsplayer.data.settings.SettingsState

internal fun MainActivity.loadIfPermitted(
    useCache: Boolean = false,
    showRefreshing: Boolean = false
) {
    val current = viewModel.getState().value ?: browserState
    if (hasVideoPermission()) {
        when {
            current.currentMode == VideoMode.HIERARCHY -> viewModel.loadHierarchy(
                current.hierarchyPath,
                current.sortMode,
                current.sortOrder,
                contentResolver,
                current.nomediaEnabled,
                current.searchFoldersUseAll,
                current.searchFolders,
                useCache,
                showRefreshing
            )
            current.inFolderVideos && current.selectedBucketId != null ->
                viewModel.loadFolderVideos(
                    current.selectedBucketId!!,
                    current.sortMode,
                    current.sortOrder,
                    contentResolver,
                    current.nomediaEnabled,
                    current.searchFoldersUseAll,
                    current.searchFolders,
                    useCache,
                    showRefreshing
                )
            else -> viewModel.load(
                current.currentMode,
                current.sortMode,
                current.sortOrder,
                contentResolver,
                current.nomediaEnabled,
                current.searchFoldersUseAll,
                current.searchFolders,
                useCache,
                showRefreshing
            )
        }
    } else {
        statusText.text = getString(R.string.permission_needed)
        statusText.visibility = View.VISIBLE
        if (showRefreshing) {
            viewModel.setRefreshing(false)
        }
        requestMediaPermissions()
    }
}

internal fun MainActivity.hasVideoPermission(): Boolean {
    val permission = if (Build.VERSION.SDK_INT >= 33) {
        Manifest.permission.READ_MEDIA_VIDEO
    } else {
        Manifest.permission.READ_EXTERNAL_STORAGE
    }
    return ContextCompat.checkSelfPermission(
        this,
        permission
    ) == PackageManager.PERMISSION_GRANTED
}

internal fun MainActivity.requestMediaPermissions() {
    val permissions = if (Build.VERSION.SDK_INT >= 33) {
        val requested = listOf(Manifest.permission.READ_MEDIA_VIDEO)
        requested.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }.toTypedArray()
    } else {
        if (
            ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.READ_EXTERNAL_STORAGE
            ) == PackageManager.PERMISSION_GRANTED
        ) {
            emptyArray()
        } else {
            arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)
        }
    }
    if (permissions.isNotEmpty()) {
        ActivityCompat.requestPermissions(this, permissions, MainActivity.REQUEST_PERMISSION)
    }
}

internal fun MainActivity.renderItems(items: List<DisplayItem>?) {
    if (items == null) {
        if (!isSearchMode || !isShowingSearchResults) {
            emptyText.visibility = View.GONE
        }
        return
    }
    latestBrowseItems = items
    if (isSearchMode && isShowingSearchResults) {
        return
    }
    adapter.submit(items) {
        restoreTransientUiStateIfNeeded()
    }
    emptyText.text = getString(R.string.empty_state)
    val isEmpty = items.isEmpty()
    emptyText.visibility = if (isEmpty) View.VISIBLE else View.GONE
}

internal fun MainActivity.restoreTransientUiStateIfNeeded() {
    pendingRestoredSelectionKeys?.let { keys ->
        if (keys.isEmpty()) {
            pendingRestoredSelectionKeys = null
        } else if (adapter.currentList.isNotEmpty()) {
            adapter.restoreSelection(keys)
            pendingRestoredSelectionKeys = null
        }
    }
    pendingListLayoutState?.let { state ->
        if (adapter.currentList.isNotEmpty()) {
            list.layoutManager?.onRestoreInstanceState(state)
            pendingListLayoutState = null
        }
    }
}

internal fun MainActivity.renderLoading(loading: Boolean?) {
    val show = loading != null && loading
    if (show) {
        statusText.text = getString(R.string.status_loading)
        statusText.visibility = View.GONE
        return
    }
    val current = statusText.text
    if (current != null && current.toString() == getString(R.string.status_loading)) {
        statusText.text = ""
        statusText.visibility = View.GONE
    }
}

internal fun MainActivity.applySettings(settings: SettingsState) {
    val current = viewModel.getState().value ?: browserState
    val nomediaChanged = current.nomediaEnabled != settings.nomediaEnabled
    val searchChanged =
        current.searchFoldersUseAll != settings.searchFoldersUseAll ||
            current.searchFolders != settings.searchFolders
    val shouldApplyInitialMode = !initialSettingsApplied && !restoredFromSavedState
    adapter.setVisibleItems(settings.visibleItems)
    viewModel.updateState {
        it.copy(
            currentMode = if (shouldApplyInitialMode) settings.mode else it.currentMode,
            videoDisplayMode = settings.displayMode,
            tileSpanCount = settings.tileSpanCount,
            sortMode = settings.sortMode,
            sortOrder = settings.sortOrder,
            nomediaEnabled = settings.nomediaEnabled,
            searchFoldersUseAll = settings.searchFoldersUseAll,
            searchFolders = settings.searchFolders,
            inFolderVideos = if (shouldApplyInitialMode) false else it.inFolderVideos,
            selectedBucketId = if (shouldApplyInitialMode) null else it.selectedBucketId,
            selectedBucketName = if (shouldApplyInitialMode) null else it.selectedBucketName,
            hierarchyPath = if (shouldApplyInitialMode) "" else it.hierarchyPath
        )
    }
    if (!initialSettingsApplied) {
        initialSettingsApplied = true
        loadWithSettings()
    } else if (nomediaChanged || searchChanged) {
        loadIfPermitted(useCache = true)
    }
}

internal fun MainActivity.loadWithSettings() {
    if (hasVideoPermission()) {
        loadIfPermitted(useCache = true, showRefreshing = true)
    } else {
        statusText.text = getString(R.string.permission_needed)
        statusText.visibility = View.VISIBLE
        requestMediaPermissions()
    }
}
