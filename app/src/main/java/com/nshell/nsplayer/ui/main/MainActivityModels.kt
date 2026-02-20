package com.nshell.nsplayer.ui.main

internal data class VolumePath(
    val volumeName: String,
    val relativePath: String
)

internal data class ItemProperties(
    val title: String,
    val typeLabel: String,
    val fullName: String,
    val location: String,
    val size: String,
    val modified: String,
    val subtitle: String
)

internal data class VideoMeta(
    val displayName: String,
    val relativePath: String,
    val sizeBytes: Long,
    val modifiedSeconds: Long,
    val volumeName: String?
)

internal data class FolderMeta(
    val relativePath: String?,
    val sizeBytes: Long,
    val modifiedSeconds: Long,
    val volumeName: String?
)

internal data class RenameRequest(
    val item: DisplayItem,
    val newName: String
)

internal data class FolderRenameRequest(
    val item: DisplayItem,
    val newName: String,
    val relativePath: String
)

internal enum class FolderRenameResult {
    SUCCESS,
    NOT_FOUND,
    INVALID_ROOT,
    FAILED
}
