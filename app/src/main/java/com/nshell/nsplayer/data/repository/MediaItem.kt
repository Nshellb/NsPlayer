package com.nshell.nsplayer.data.repository

import android.content.IntentSender

internal data class MediaItem(
    val type: Type,
    val title: String,
    val contentUri: String?,
    val bucketId: String?
) {
    enum class Type { VIDEO, FOLDER, HIERARCHY }
}

internal data class MediaItemMetadata(
    val displayName: String,
    val relativePath: String?,
    val sizeBytes: Long,
    val modifiedSeconds: Long,
    val volumeName: String?
)

internal sealed class RenameOutcome {
    object Success : RenameOutcome()
    data class Failure(val reason: Reason = Reason.FAILED) : RenameOutcome()
    data class PermissionRequired(val intentSender: IntentSender) : RenameOutcome()
    data class FolderRequired(val relativePath: String) : RenameOutcome()

    enum class Reason { FAILED, INVALID_ROOT, NOT_FOUND, PERMISSION_DENIED, PICK_CANCELLED }
}
