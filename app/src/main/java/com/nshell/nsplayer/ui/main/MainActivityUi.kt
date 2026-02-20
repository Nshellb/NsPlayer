package com.nshell.nsplayer.ui.main

import android.view.View
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import com.nshell.nsplayer.R

internal fun MainActivity.updateHeaderState() {
    if (browserState.inFolderVideos) {
        folderHeader.visibility = View.GONE
        headerBackButton.visibility = View.VISIBLE
        val title = browserState.selectedBucketName ?: "Unknown"
        titleText.text = title
        applyVideoDisplayMode()
    } else if (browserState.currentMode == VideoMode.HIERARCHY && !isHierarchyRoot()) {
        folderHeader.visibility = View.GONE
        headerBackButton.visibility = View.VISIBLE
        titleText.text = getHierarchyTitle()
        applyVideoDisplayMode()
    } else {
        folderHeader.visibility = View.GONE
        headerBackButton.visibility = View.GONE
        titleText.text = getString(R.string.app_name)
        if (
            browserState.currentMode == VideoMode.FOLDERS ||
            browserState.currentMode == VideoMode.VIDEOS ||
            browserState.currentMode == VideoMode.HIERARCHY
        ) {
            applyVideoDisplayMode()
        } else {
            list.layoutManager = LinearLayoutManager(this)
            adapter.setVideoDisplayMode(VideoDisplayMode.LIST)
        }
    }
}

internal fun MainActivity.applyVideoDisplayMode() {
    adapter.setVideoDisplayMode(browserState.videoDisplayMode)
    list.layoutManager = if (browserState.videoDisplayMode == VideoDisplayMode.TILE) {
        GridLayoutManager(this, browserState.tileSpanCount)
    } else {
        LinearLayoutManager(this)
    }
    adapter.notifyDataSetChanged()
}

internal fun MainActivity.isHierarchyRoot(): Boolean = browserState.hierarchyPath.isEmpty()

internal fun MainActivity.getHierarchyTitle(): String {
    if (browserState.hierarchyPath.isEmpty()) {
        return "Root"
    }
    if (browserState.hierarchyPath.startsWith(MainActivity.VOLUME_PREFIX)) {
        val rest = stripVolumePrefix(browserState.hierarchyPath) ?: ""
        val volume = browserState.hierarchyPath.removePrefix(MainActivity.VOLUME_PREFIX).substringBefore("/")
        if (rest.isEmpty()) {
            return buildVolumeLabel(volume)
        }
        val trimmed = if (rest.endsWith("/")) rest.substring(0, rest.length - 1) else rest
        val lastSlash = trimmed.lastIndexOf('/')
        return if (lastSlash >= 0 && lastSlash < trimmed.length - 1) {
            trimmed.substring(lastSlash + 1)
        } else {
            trimmed
        }
    }
    var trimmed = browserState.hierarchyPath
    if (trimmed.endsWith("/")) {
        trimmed = trimmed.substring(0, trimmed.length - 1)
    }
    val lastSlash = trimmed.lastIndexOf('/')
    return if (lastSlash >= 0 && lastSlash < trimmed.length - 1) {
        trimmed.substring(lastSlash + 1)
    } else {
        if (trimmed.isEmpty()) "Root" else trimmed
    }
}

internal fun MainActivity.getParentPath(path: String): String {
    if (path.isEmpty()) {
        return ""
    }
    var trimmed = path
    if (trimmed.endsWith("/")) {
        trimmed = trimmed.substring(0, trimmed.length - 1)
    }
    val lastSlash = trimmed.lastIndexOf('/')
    if (lastSlash < 0) {
        return ""
    }
    return trimmed.substring(0, lastSlash + 1)
}

internal fun MainActivity.updateModeSelectionUI(
    folders: TextView,
    hierarchy: TextView,
    videos: TextView,
    selectedMode: VideoMode,
    selectedColor: Int,
    defaultColor: Int
) {
    folders.setTextColor(if (selectedMode == VideoMode.FOLDERS) selectedColor else defaultColor)
    hierarchy.setTextColor(if (selectedMode == VideoMode.HIERARCHY) selectedColor else defaultColor)
    videos.setTextColor(if (selectedMode == VideoMode.VIDEOS) selectedColor else defaultColor)
}

internal fun MainActivity.updateDisplaySelectionUI(
    list: TextView,
    tile: TextView,
    tileMultipliers: View,
    selectedDisplay: VideoDisplayMode,
    selectedColor: Int,
    defaultColor: Int
) {
    val isTile = selectedDisplay == VideoDisplayMode.TILE
    list.setTextColor(if (selectedDisplay == VideoDisplayMode.LIST) selectedColor else defaultColor)
    tile.setTextColor(if (isTile) selectedColor else defaultColor)
    tileMultipliers.visibility = if (isTile) View.VISIBLE else View.GONE
}

internal fun MainActivity.updateTileSpanSelectionUI(
    x2: TextView,
    x3: TextView,
    x4: TextView,
    x2Icon: ImageView,
    x3Icon: ImageView,
    x4Icon: ImageView,
    selectedSpan: Int,
    selectedColor: Int,
    defaultColor: Int
) {
    val x2Selected = selectedSpan == 2
    val x3Selected = selectedSpan == 3
    val x4Selected = selectedSpan == 4
    x2.setTextColor(if (x2Selected) selectedColor else defaultColor)
    x3.setTextColor(if (x3Selected) selectedColor else defaultColor)
    x4.setTextColor(if (x4Selected) selectedColor else defaultColor)
    x2Icon.setColorFilter(if (x2Selected) selectedColor else defaultColor)
    x3Icon.setColorFilter(if (x3Selected) selectedColor else defaultColor)
    x4Icon.setColorFilter(if (x4Selected) selectedColor else defaultColor)
}

internal fun MainActivity.updateSortSelectionUI(
    title: TextView,
    modified: TextView,
    duration: TextView,
    asc: TextView,
    desc: TextView,
    selectedSort: VideoSortMode,
    selectedOrder: VideoSortOrder,
    selectedColor: Int,
    defaultColor: Int
) {
    val titleSelected = selectedSort == VideoSortMode.TITLE
    val modifiedSelected = selectedSort == VideoSortMode.MODIFIED
    val durationSelected = selectedSort == VideoSortMode.LENGTH
    title.setTextColor(if (titleSelected) selectedColor else defaultColor)
    modified.setTextColor(if (modifiedSelected) selectedColor else defaultColor)
    duration.setTextColor(if (durationSelected) selectedColor else defaultColor)
    if (titleSelected) {
        asc.text = getString(R.string.sort_title_asc)
        desc.text = getString(R.string.sort_title_desc)
    } else if (modifiedSelected) {
        asc.text = getString(R.string.sort_modified_asc)
        desc.text = getString(R.string.sort_modified_desc)
    } else {
        asc.text = getString(R.string.sort_length_asc)
        desc.text = getString(R.string.sort_length_desc)
    }
    asc.setTextColor(if (selectedOrder == VideoSortOrder.ASC) selectedColor else defaultColor)
    desc.setTextColor(if (selectedOrder == VideoSortOrder.DESC) selectedColor else defaultColor)
}

internal fun MainActivity.dpToPx(dp: Int): Int {
    val density = resources.displayMetrics.density
    return Math.round(dp * density)
}
