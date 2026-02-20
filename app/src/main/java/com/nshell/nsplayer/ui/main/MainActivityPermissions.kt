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
                useCache,
                showRefreshing
            )
            current.inFolderVideos && current.selectedBucketId != null ->
                viewModel.loadFolderVideos(
                    current.selectedBucketId!!,
                    current.sortMode,
                    current.sortOrder,
                    contentResolver,
                    useCache,
                    showRefreshing
                )
            else -> viewModel.load(
                current.currentMode,
                current.sortMode,
                current.sortOrder,
                contentResolver,
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
        val requested = mutableListOf(
            Manifest.permission.READ_MEDIA_VIDEO,
            Manifest.permission.READ_MEDIA_AUDIO,
            Manifest.permission.READ_MEDIA_IMAGES
        )
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
        emptyText.visibility = View.GONE
        return
    }
    adapter.submit(items)
    val isEmpty = items.isEmpty()
    emptyText.visibility = if (isEmpty) View.VISIBLE else View.GONE
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
    adapter.setVisibleItems(settings.visibleItems)
    viewModel.updateState {
        it.copy(
            currentMode = settings.mode,
            videoDisplayMode = settings.displayMode,
            tileSpanCount = settings.tileSpanCount,
            sortMode = settings.sortMode,
            sortOrder = settings.sortOrder
        )
    }
    if (!initialSettingsApplied) {
        initialSettingsApplied = true
        loadWithSettings()
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
