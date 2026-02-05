package com.nshell.nsplayer.data.repository

import android.content.ContentResolver
import android.content.ContentUris
import android.database.Cursor
import android.net.Uri
import android.provider.MediaStore
import com.nshell.nsplayer.ui.main.DisplayItem
import com.nshell.nsplayer.ui.main.VideoMode
import com.nshell.nsplayer.ui.main.VideoSortMode
import com.nshell.nsplayer.ui.main.VideoSortOrder
import java.util.Locale

class MediaStoreVideoRepository : VideoRepository {
    override fun load(
        mode: VideoMode,
        sortMode: VideoSortMode,
        sortOrder: VideoSortOrder,
        resolver: ContentResolver
    ): List<DisplayItem> {
        val entries = queryVideos(resolver)
        sortEntries(entries, sortMode, sortOrder)
        return when (mode) {
            VideoMode.VIDEOS -> buildVideoItems(entries)
            VideoMode.HIERARCHY -> buildHierarchyLevelItems(entries, "", sortMode, sortOrder)
            VideoMode.FOLDERS -> buildFolderItems(entries)
        }
    }

    override fun loadVideosInFolder(
        bucketId: String,
        sortMode: VideoSortMode,
        sortOrder: VideoSortOrder,
        resolver: ContentResolver
    ): List<DisplayItem> {
        val entries = queryVideosInternal(bucketId, resolver)
        sortEntries(entries, sortMode, sortOrder)
        return buildVideoItems(entries)
    }

    override fun loadHierarchy(
        path: String,
        sortMode: VideoSortMode,
        sortOrder: VideoSortOrder,
        resolver: ContentResolver
    ): List<DisplayItem> {
        val entries = queryVideos(resolver)
        return buildHierarchyLevelItems(entries, path, sortMode, sortOrder)
    }

    private fun queryVideos(resolver: ContentResolver): MutableList<VideoEntry> =
        queryVideosInternal(null, resolver)

    private fun queryVideosInternal(bucketId: String?, resolver: ContentResolver): MutableList<VideoEntry> {
        val entries = mutableListOf<VideoEntry>()
        val projection = arrayOf(
            MediaStore.Video.Media._ID,
            MediaStore.Video.Media.DISPLAY_NAME,
            MediaStore.Video.Media.BUCKET_ID,
            MediaStore.Video.Media.BUCKET_DISPLAY_NAME,
            MediaStore.Video.Media.DURATION,
            MediaStore.Video.Media.WIDTH,
            MediaStore.Video.Media.HEIGHT,
            MediaStore.Video.Media.RELATIVE_PATH,
            MediaStore.Video.Media.DATE_ADDED,
            MediaStore.Video.Media.DATE_MODIFIED
        )
        val sortOrder = MediaStore.Video.Media.DATE_ADDED + " DESC"
        val selection = if (bucketId != null) "${MediaStore.Video.Media.BUCKET_ID}=?" else null
        val selectionArgs = if (bucketId != null) arrayOf(bucketId) else null
        resolver.query(VIDEOS_URI, projection, selection, selectionArgs, sortOrder)?.use { cursor ->
            val idCol = cursor.getColumnIndexOrThrow(MediaStore.Video.Media._ID)
            val nameCol = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.DISPLAY_NAME)
            val bucketIdCol = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.BUCKET_ID)
            val bucketNameCol = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.BUCKET_DISPLAY_NAME)
            val durationCol = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.DURATION)
            val widthCol = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.WIDTH)
            val heightCol = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.HEIGHT)
            val pathCol = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.RELATIVE_PATH)
            val modifiedCol = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.DATE_MODIFIED)

            while (cursor.moveToNext()) {
                entries.add(
                    VideoEntry(
                        id = cursor.getLong(idCol),
                        displayName = cursor.getString(nameCol),
                        bucketId = cursor.getString(bucketIdCol),
                        bucketName = cursor.getString(bucketNameCol),
                        duration = cursor.getLong(durationCol),
                        width = cursor.getInt(widthCol),
                        height = cursor.getInt(heightCol),
                        relativePath = cursor.getString(pathCol),
                        modifiedSeconds = cursor.getLong(modifiedCol)
                    )
                )
            }
        }
        return entries
    }

    private fun buildVideoItems(entries: List<VideoEntry>): List<DisplayItem> {
        return entries.map { entry ->
            DisplayItem(
                DisplayItem.Type.VIDEO,
                safe(entry.displayName, "Unknown"),
                null,
                0,
                entry.duration,
                entry.width,
                entry.height,
                ContentUris.withAppendedId(VIDEOS_URI, entry.id).toString()
            )
        }
    }

    private fun buildFolderItems(entries: List<VideoEntry>): List<DisplayItem> {
        val folders = mutableMapOf<String, FolderAggregate>()
        for (entry in entries) {
            val key = entry.bucketId ?: safe(entry.bucketName, "Unknown")
            val name = safe(entry.bucketName, "Unknown")
            val aggregate = folders.getOrPut(key) { FolderAggregate(key, name) }
            aggregate.count++
        }
        val sorted = folders.values.sortedBy { it.name.lowercase(Locale.US) }
        return sorted.map { aggregate ->
            DisplayItem(
                DisplayItem.Type.FOLDER,
                aggregate.name,
                formatCountSubtitle(aggregate.count, 0),
                0,
                aggregate.bucketId
            )
        }
    }

    private fun buildHierarchyLevelItems(
        entries: List<VideoEntry>,
        currentPath: String,
        sortMode: VideoSortMode,
        sortOrder: VideoSortOrder
    ): List<DisplayItem> {
        val normalizedPath = normalizePath(currentPath)
        val folders = mutableMapOf<String, FolderAggregate>()
        val videos = mutableListOf<VideoEntry>()

        for (entry in entries) {
            var path = normalizePath(entry.relativePath)
            if (path.isEmpty()) {
                path = normalizePath(safe(entry.bucketName, "Unknown"))
            }
            if (!path.startsWith(normalizedPath)) {
                continue
            }
            val remainder = path.substring(normalizedPath.length)
            if (remainder.isEmpty()) {
                videos.add(entry)
                continue
            }
            val slash = remainder.indexOf('/')
            val childName = if (slash >= 0) remainder.substring(0, slash) else remainder
            if (childName.isEmpty()) {
                continue
            }
            val childPath = normalizedPath + childName + "/"
            val aggregate = folders.getOrPut(childPath) { FolderAggregate(childPath, childName) }
            aggregate.count++
            var childRemainder = remainder.substring(childName.length)
            if (childRemainder.startsWith("/")) {
                childRemainder = childRemainder.substring(1)
            }
            if (childRemainder.isEmpty()) {
                aggregate.directVideoCount++
            } else {
                val nextSlash = childRemainder.indexOf('/')
                val nextFolder = if (nextSlash >= 0) childRemainder.substring(0, nextSlash) else childRemainder
                if (nextFolder.isNotEmpty()) {
                    aggregate.directSubfolders.add(nextFolder)
                }
            }
        }

        val sortedFolders = folders.values.sortedBy { it.name.lowercase(Locale.US) }
        val items = mutableListOf<DisplayItem>()
        for (aggregate in sortedFolders) {
            items.add(
                DisplayItem(
                    DisplayItem.Type.HIERARCHY,
                    aggregate.name,
                    formatCountSubtitle(aggregate.directVideoCount, aggregate.directSubfolders.size),
                    0,
                    aggregate.bucketId
                )
            )
        }
        sortEntries(videos, sortMode, sortOrder)
        for (entry in videos) {
            items.add(buildVideoItem(entry))
        }
        return items
    }

    private fun safe(value: String?, fallback: String): String {
        return if (value.isNullOrEmpty()) fallback else value
    }

    private data class FolderAggregate(
        val bucketId: String,
        val name: String,
        var count: Int = 0,
        var directVideoCount: Int = 0,
        val directSubfolders: MutableSet<String> = mutableSetOf()
    )

    private data class VideoEntry(
        val id: Long,
        val displayName: String?,
        val bucketId: String?,
        val bucketName: String?,
        val duration: Long,
        val width: Int,
        val height: Int,
        val relativePath: String?,
        val modifiedSeconds: Long
    )

    private fun buildVideoItem(entry: VideoEntry): DisplayItem {
        return DisplayItem(
            DisplayItem.Type.VIDEO,
            safe(entry.displayName, "Unknown"),
            null,
            0,
            entry.duration,
            entry.width,
            entry.height,
            ContentUris.withAppendedId(VIDEOS_URI, entry.id).toString()
        )
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

    private fun sortEntries(
        entries: MutableList<VideoEntry>,
        sortMode: VideoSortMode,
        sortOrder: VideoSortOrder
    ) {
        if (entries.size < 2) {
            return
        }
        val comparator = when (sortMode) {
            VideoSortMode.TITLE -> compareBy<VideoEntry> { safe(it.displayName, "").lowercase(Locale.US) }
            VideoSortMode.LENGTH -> compareBy { it.duration }
            VideoSortMode.MODIFIED -> compareBy { it.modifiedSeconds }
        }
        if (sortOrder == VideoSortOrder.ASC) {
            entries.sortWith(comparator)
        } else {
            entries.sortWith(comparator.reversed())
        }
    }

    private fun formatCountSubtitle(videoCount: Int, folderCount: Int): String {
        return String.format(Locale.US, "Video %d, Folder %d", videoCount, folderCount)
    }

    companion object {
        private val VIDEOS_URI: Uri = MediaStore.Video.Media.EXTERNAL_CONTENT_URI
    }
}
