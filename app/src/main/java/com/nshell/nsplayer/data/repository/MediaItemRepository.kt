package com.nshell.nsplayer.data.repository

import android.app.RecoverableSecurityException
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.CancellationSignal
import android.provider.MediaStore
import androidx.annotation.WorkerThread
import androidx.documentfile.provider.DocumentFile

internal interface MediaItemDataSource {
    @WorkerThread
    fun loadMetadata(item: MediaItem, signal: CancellationSignal): MediaItemMetadata?

    @WorkerThread
    fun rename(
        item: MediaItem,
        newName: String,
        allowPermissionRequest: Boolean,
        signal: CancellationSignal,
        selectedTree: Uri?,
        folderPath: String?
    ): RenameOutcome
}

/** All provider access, including document traversal and permission inspection, runs on a worker. */
internal class MediaItemRepository(context: Context) : MediaItemDataSource {
    private val context = context.applicationContext
    private val resolver = this.context.contentResolver
    private val preferences = this.context.getSharedPreferences("nsplayer_prefs", Context.MODE_PRIVATE)

    @WorkerThread
    override fun loadMetadata(item: MediaItem, signal: CancellationSignal): MediaItemMetadata? {
        signal.throwIfCanceled()
        return if (item.type == MediaItem.Type.VIDEO) queryVideoMetadata(item.contentUri, signal)
            else queryFolderInfo(item, signal)
    }

    @WorkerThread
    override fun rename(
        item: MediaItem,
        newName: String,
        allowPermissionRequest: Boolean,
        signal: CancellationSignal,
        selectedTree: Uri?,
        folderPath: String?
    ): RenameOutcome {
        signal.throwIfCanceled()
        if (item.type == MediaItem.Type.VIDEO) {
            val uri = item.contentUri?.let(Uri::parse) ?: return RenameOutcome.Failure()
            val values = ContentValues().apply { put(MediaStore.MediaColumns.DISPLAY_NAME, newName) }
            return try {
                signal.throwIfCanceled()
                if (resolver.update(uri, values, null, null) > 0) RenameOutcome.Success
                    else RenameOutcome.Failure()
            } catch (e: RecoverableSecurityException) {
                if (allowPermissionRequest) RenameOutcome.PermissionRequired(e.userAction.actionIntent.intentSender)
                    else RenameOutcome.Failure()
            }
        }

        val relativePath = folderPath ?: when (item.type) {
            MediaItem.Type.FOLDER -> queryFolderInfo(item, signal)?.relativePath
            MediaItem.Type.HIERARCHY -> stripVolumePrefix(item.bucketId)
            else -> null
        }?.trim()
        if (relativePath.isNullOrEmpty()) return RenameOutcome.Failure()
        if (selectedTree != null) {
            preferences.edit().putString(KEY_FOLDER_RENAME_TREE_URI, selectedTree.toString()).apply()
            return renameFolder(selectedTree, relativePath, newName, signal)
        }
        val savedTree = preferences.getString(KEY_FOLDER_RENAME_TREE_URI, null)?.let(Uri::parse)
        if (savedTree != null && resolver.persistedUriPermissions.any {
                it.isWritePermission && it.uri == savedTree
            }
        ) {
            val result = try {
                renameFolder(savedTree, relativePath, newName, signal)
            } catch (_: Exception) {
                signal.throwIfCanceled()
                RenameOutcome.Failure()
            }
            if (result == RenameOutcome.Success) return result
        }
        signal.throwIfCanceled()
        return RenameOutcome.FolderRequired(relativePath)
    }

    private fun renameFolder(
        uri: Uri,
        relativePath: String,
        newName: String,
        signal: CancellationSignal
    ): RenameOutcome {
        signal.throwIfCanceled()
        try {
            resolver.takePersistableUriPermission(
                uri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            )
        } catch (_: SecurityException) {
            // The picker can grant temporary access even when a persistent grant is unavailable.
        }
        val root = DocumentFile.fromTreeUri(context, uri)
        if (root == null || !root.isDirectory) return RenameOutcome.Failure(RenameOutcome.Reason.INVALID_ROOT)
        val target = findFolderInTree(root, relativePath, signal)
            ?: return RenameOutcome.Failure(RenameOutcome.Reason.NOT_FOUND)
        signal.throwIfCanceled()
        return if (target.renameTo(newName)) RenameOutcome.Success else RenameOutcome.Failure()
    }

    private fun queryVideoMetadata(contentUri: String?, signal: CancellationSignal): MediaItemMetadata? {
        val uri = contentUri?.let { Uri.parse(it) } ?: return null
        val projection = arrayOf(
            MediaStore.Video.Media.DISPLAY_NAME,
            MediaStore.Video.Media.RELATIVE_PATH,
            MediaStore.Video.Media.SIZE,
            MediaStore.Video.Media.DATE_MODIFIED,
            MediaStore.Video.Media.VOLUME_NAME
        )
        resolver.query(uri, projection, null, null, null, signal)?.use { cursor ->
            if (!cursor.moveToFirst()) {
                return null
            }
            val nameCol = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.DISPLAY_NAME)
            val pathCol = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.RELATIVE_PATH)
            val sizeCol = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.SIZE)
            val modifiedCol = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.DATE_MODIFIED)
            val volumeCol = cursor.getColumnIndex(MediaStore.Video.Media.VOLUME_NAME)
            return MediaItemMetadata(
                displayName = cursor.getString(nameCol) ?: "",
                relativePath = cursor.getString(pathCol) ?: "",
                sizeBytes = cursor.getLong(sizeCol),
                modifiedSeconds = cursor.getLong(modifiedCol),
                volumeName = if (volumeCol >= 0) cursor.getString(volumeCol) else null
            )
        }
        return null
    }

    private fun queryFolderInfo(item: MediaItem, signal: CancellationSignal): MediaItemMetadata? {
        val projection = arrayOf(
            MediaStore.Video.Media.RELATIVE_PATH,
            MediaStore.Video.Media.SIZE,
            MediaStore.Video.Media.DATE_MODIFIED
        )
        var selection: String? = null
        var selectionArgs: Array<String>? = null
        var volumeName: String? = null
        var contentUri: Uri = MediaStore.Video.Media.EXTERNAL_CONTENT_URI
        when (item.type) {
            MediaItem.Type.FOLDER -> {
                val bucketId = item.bucketId ?: return null
                selection = "${MediaStore.Video.Media.BUCKET_ID}=?"
                selectionArgs = arrayOf(bucketId)
            }
            MediaItem.Type.HIERARCHY -> {
                val rawPath = item.bucketId ?: ""
                val parsed = parseVolumePath(rawPath)
                volumeName = parsed?.volumeName
                if (!volumeName.isNullOrEmpty()) {
                    contentUri = MediaStore.Video.Media.getContentUri(volumeName!!)
                }
                val relPath = parsed?.relativePath ?: stripVolumePrefix(rawPath)
                if (!relPath.isNullOrEmpty()) {
                    val normalized = if (relPath.endsWith("/")) relPath else "$relPath/"
                    selection = "${MediaStore.Video.Media.RELATIVE_PATH} LIKE ?"
                    selectionArgs = arrayOf("$normalized%")
                }
            }
            else -> return null
        }
        var relativePath: String? = null
        var sizeTotal = 0L
        var latestModified = 0L
        val cursor = resolver.query(
            contentUri,
            projection,
            selection,
            selectionArgs,
            null,
            signal
        ) ?: return null
        cursor.use {
            val pathCol = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.RELATIVE_PATH)
            val sizeCol = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.SIZE)
            val modifiedCol = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.DATE_MODIFIED)
            while (cursor.moveToNext()) {
                signal.throwIfCanceled()
                if (relativePath == null) {
                    relativePath = cursor.getString(pathCol)
                }
                sizeTotal += cursor.getLong(sizeCol)
                val modified = cursor.getLong(modifiedCol)
                if (modified > latestModified) {
                    latestModified = modified
                }
            }
        }
        if (relativePath == null && item.type == MediaItem.Type.HIERARCHY) {
            relativePath = item.bucketId
        }
        return MediaItemMetadata(item.title, relativePath, sizeTotal, latestModified, volumeName)
    }

    private fun findFolderInTree(root: DocumentFile, relativePath: String, signal: CancellationSignal): DocumentFile? {
        val segments = relativePath.split('/').filter { it.isNotBlank() }
        if (segments.isEmpty()) {
            return null
        }
        fun traverseFrom(startIndex: Int): DocumentFile? {
            var current: DocumentFile? = root
            for (i in startIndex until segments.size) {
                signal.throwIfCanceled()
                val next = current?.findFile(segments[i]) ?: return null
                if (!next.isDirectory) {
                    return null
                }
                current = next
            }
            return current
        }
        traverseFrom(0)?.let { return it }
        for (start in 1 until segments.size) {
            val found = traverseFrom(start)
            if (found != null) {
                return found
            }
        }
        return null
    }

    private fun stripVolumePrefix(path: String?): String? {
        if (path.isNullOrEmpty()) {
            return null
        }
        if (!path.startsWith(VOLUME_PREFIX)) {
            return path
        }
        val rest = path.removePrefix(VOLUME_PREFIX)
        val slash = rest.indexOf('/')
        if (slash < 0) {
            return ""
        }
        return rest.substring(slash + 1)
    }

    private fun parseVolumePath(path: String?): VolumePath? {
        if (path.isNullOrEmpty() || !path.startsWith(VOLUME_PREFIX)) {
            return null
        }
        val rest = path.removePrefix(VOLUME_PREFIX)
        val slash = rest.indexOf('/')
        if (slash < 0) {
            return VolumePath(rest, "")
        }
        val volume = rest.substring(0, slash)
        val rel = rest.substring(slash + 1)
        return VolumePath(volume, rel)
    }

    private data class VolumePath(val volumeName: String, val relativePath: String)

    private companion object {
        const val VOLUME_PREFIX = "volume:"
        const val KEY_FOLDER_RENAME_TREE_URI = "folder_rename_tree_uri"
    }
}
