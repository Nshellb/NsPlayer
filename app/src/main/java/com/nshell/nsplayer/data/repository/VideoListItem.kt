package com.nshell.nsplayer.data.repository

data class VideoListItem(
    val type: Type,
    val name: String?,
    val id: String? = null,
    val videoCount: Int = 0,
    val folderCount: Int = 0,
    val durationMs: Long = 0L,
    val width: Int = 0,
    val height: Int = 0,
    val contentUri: String? = null,
    val sizeBytes: Long = 0L,
    val modifiedSeconds: Long = 0L,
    val frameRate: Float = 0f,
    val hasSubtitle: Boolean = false,
    /** Present only for a storage-volume root row. */
    val volumeName: String? = null
) {
    enum class Type { VIDEO, FOLDER, HIERARCHY }
}
