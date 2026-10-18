package com.nshell.nsplayer.ui.main

import android.content.Context
import android.provider.MediaStore
import com.nshell.nsplayer.R
import com.nshell.nsplayer.data.repository.VideoBrowseMode
import com.nshell.nsplayer.data.repository.VideoListItem
import com.nshell.nsplayer.data.repository.VideoQueryOptions
import com.nshell.nsplayer.data.repository.VideoSortDirection
import com.nshell.nsplayer.data.repository.VideoSortField
import java.util.Locale

internal class VideoDisplayItemMapper(context: Context) {
    private val context = context.applicationContext

    fun map(items: List<VideoListItem>): List<DisplayItem> = items.map(::map)

    fun map(item: VideoListItem): DisplayItem = DisplayItem(
        type = when (item.type) {
            VideoListItem.Type.VIDEO -> DisplayItem.Type.VIDEO
            VideoListItem.Type.FOLDER -> DisplayItem.Type.FOLDER
            VideoListItem.Type.HIERARCHY -> DisplayItem.Type.HIERARCHY
        },
        title = volumeLabel(item.volumeName) ?: item.name.orEmpty().ifEmpty { "Unknown" },
        subtitle = if (item.type == VideoListItem.Type.VIDEO) null else {
            String.format(Locale.US, "Video %d, Folder %d", item.videoCount, item.folderCount)
        },
        indentLevel = 0,
        bucketId = item.id,
        durationMs = item.durationMs,
        width = item.width,
        height = item.height,
        contentUri = item.contentUri,
        sizeBytes = item.sizeBytes,
        modifiedSeconds = item.modifiedSeconds,
        frameRate = item.frameRate,
        hasSubtitle = item.hasSubtitle
    )

    private fun volumeLabel(volumeName: String?): String? = when {
        volumeName == null -> null
        volumeName == MediaStore.VOLUME_EXTERNAL_PRIMARY -> context.getString(R.string.storage_internal)
        else -> context.getString(R.string.storage_external_format, volumeName)
    }
}

internal fun VideoBrowserState.toQueryOptions(): VideoQueryOptions = VideoQueryOptions(
    sortField = when (sortMode) {
        VideoSortMode.TITLE -> VideoSortField.TITLE
        VideoSortMode.MODIFIED -> VideoSortField.MODIFIED
        VideoSortMode.LENGTH -> VideoSortField.LENGTH
    },
    sortDirection = when (sortOrder) {
        VideoSortOrder.ASC -> VideoSortDirection.ASCENDING
        VideoSortOrder.DESC -> VideoSortDirection.DESCENDING
    },
    respectNoMedia = nomediaEnabled,
    folderIds = if (searchFoldersUseAll) null else searchFolders.toSet()
)

internal fun VideoMode.toBrowseMode(): VideoBrowseMode = when (this) {
    VideoMode.FOLDERS -> VideoBrowseMode.FOLDERS
    VideoMode.HIERARCHY -> VideoBrowseMode.HIERARCHY
    VideoMode.VIDEOS -> VideoBrowseMode.VIDEOS
}
