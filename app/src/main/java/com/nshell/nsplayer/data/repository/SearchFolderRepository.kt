package com.nshell.nsplayer.data.repository

import android.content.ContentResolver
import android.provider.MediaStore
import java.util.Locale

class SearchFolderRepository {
    data class FolderEntry(
        val bucketId: String,
        val name: String,
        val relativePath: String,
        val volumeName: String?,
        val count: Int
    )

    fun load(resolver: ContentResolver, nomediaEnabled: Boolean): List<FolderEntry> {
        val aggregates = mutableMapOf<String, FolderAggregate>()
        val projection = arrayOf(
            MediaStore.Video.Media.BUCKET_ID,
            MediaStore.Video.Media.BUCKET_DISPLAY_NAME,
            MediaStore.Video.Media.RELATIVE_PATH,
            MediaStore.Video.Media.VOLUME_NAME
        )
        resolver.query(
            MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
            projection,
            null,
            null,
            null
        )?.use { cursor ->
            val bucketIdCol = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.BUCKET_ID)
            val bucketNameCol = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.BUCKET_DISPLAY_NAME)
            val pathCol = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.RELATIVE_PATH)
            val volumeCol = cursor.getColumnIndex(MediaStore.Video.Media.VOLUME_NAME)
            while (cursor.moveToNext()) {
                val bucketId = cursor.getString(bucketIdCol) ?: continue
                val bucketName = cursor.getString(bucketNameCol) ?: "Unknown"
                val relPath = cursor.getString(pathCol) ?: ""
                val volume = if (volumeCol >= 0) cursor.getString(volumeCol) else null
                val aggregate = aggregates.getOrPut(bucketId) {
                    FolderAggregate(bucketId, bucketName, relPath, volume)
                }
                aggregate.count++
                if (aggregate.relativePath.isEmpty() && relPath.isNotEmpty()) {
                    aggregate.relativePath = relPath
                }
            }
        }

        val entries = aggregates.values
            .filter { it.count > 0 }
            .map { aggregate ->
                FolderEntry(
                    bucketId = aggregate.bucketId,
                    name = aggregate.name,
                    relativePath = aggregate.relativePath,
                    volumeName = aggregate.volumeName,
                    count = aggregate.count
                )
            }

        val filtered = if (nomediaEnabled) {
            val index = buildNoMediaIndex(entries, resolver)
            entries.filterNot { entry ->
                val volume = resolveVolumeName(entry.volumeName)
                val normalized = normalizePath(entry.relativePath)
                isBlockedByNoMedia(volume, normalized, index)
            }
        } else {
            entries
        }

        return filtered.sortedWith(compareBy<FolderEntry> {
            it.name.lowercase(Locale.US)
        }.thenBy { it.relativePath.lowercase(Locale.US) })
    }

    private data class FolderAggregate(
        val bucketId: String,
        val name: String,
        var relativePath: String,
        val volumeName: String?,
        var count: Int = 0
    )

    private fun buildNoMediaIndex(
        entries: List<FolderEntry>,
        resolver: ContentResolver
    ): Map<String, Set<String>> {
        val volumes = entries
            .map { resolveVolumeName(it.volumeName) }
            .distinct()
            .ifEmpty { listOf(MediaStore.VOLUME_EXTERNAL_PRIMARY) }
        val result = mutableMapOf<String, MutableSet<String>>()
        volumes.forEach { volume ->
            val filesUri = MediaStore.Files.getContentUri(volume)
            val projection = arrayOf(MediaStore.Files.FileColumns.RELATIVE_PATH)
            resolver.query(
                filesUri,
                projection,
                "${MediaStore.Files.FileColumns.DISPLAY_NAME}=?",
                arrayOf(".nomedia"),
                null
            )?.use { cursor ->
                val pathCol = cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns.RELATIVE_PATH)
                while (cursor.moveToNext()) {
                    val rel = normalizePath(cursor.getString(pathCol))
                    if (rel.isNotEmpty()) {
                        result.getOrPut(volume) { mutableSetOf() }.add(rel)
                    }
                }
            }
        }
        return result
    }

    private fun isBlockedByNoMedia(
        volumeName: String,
        relativePath: String,
        index: Map<String, Set<String>>
    ): Boolean {
        if (relativePath.isEmpty()) {
            return false
        }
        val blocked = index[volumeName] ?: return false
        return blocked.any { relativePath.startsWith(it) }
    }

    private fun resolveVolumeName(name: String?): String {
        return if (name.isNullOrEmpty()) {
            MediaStore.VOLUME_EXTERNAL_PRIMARY
        } else {
            name
        }
    }

    private fun normalizePath(path: String?): String {
        if (path.isNullOrEmpty()) {
            return ""
        }
        var normalized = path.replace('\\', '/')
        if (!normalized.endsWith("/")) {
            normalized += "/"
        }
        return normalized
    }
}
