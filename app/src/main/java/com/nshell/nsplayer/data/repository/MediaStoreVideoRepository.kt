package com.nshell.nsplayer.data.repository

import android.content.ContentResolver
import android.content.ContentUris
import android.database.Cursor
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.util.Log
import com.nshell.nsplayer.NsPlayerApp
import com.nshell.nsplayer.R
import com.nshell.nsplayer.ui.main.DisplayItem
import com.nshell.nsplayer.ui.main.VideoMode
import com.nshell.nsplayer.ui.main.VideoSortMode
import com.nshell.nsplayer.ui.main.VideoSortOrder
import java.util.Locale

class MediaStoreVideoRepository : VideoRepository {
    private val logTag = "NsPlayerStorage"
    override fun load(
        mode: VideoMode,
        sortMode: VideoSortMode,
        sortOrder: VideoSortOrder,
        resolver: ContentResolver,
        nomediaEnabled: Boolean,
        searchFoldersUseAll: Boolean,
        searchFolders: Set<String>
    ): List<DisplayItem> {
        val entries = queryVideos(resolver)
        val searchFiltered = applySearchFolderFilter(entries, searchFoldersUseAll, searchFolders)
        val noMediaIndex = buildNoMediaIndexForEntries(searchFiltered, resolver, nomediaEnabled)
        val filtered = applyNoMediaFilter(searchFiltered, noMediaIndex)
        sortEntries(filtered, sortMode, sortOrder)
        val enriched = attachSubtitleInfo(filtered, resolver)
        return when (mode) {
            VideoMode.VIDEOS -> buildVideoItems(enriched)
            VideoMode.HIERARCHY ->
                buildHierarchyLevelItems(enriched, "", sortMode, sortOrder, noMediaIndex)
            VideoMode.FOLDERS -> buildFolderItems(enriched)
        }
    }

    override fun loadVideosInFolder(
        bucketId: String,
        sortMode: VideoSortMode,
        sortOrder: VideoSortOrder,
        resolver: ContentResolver,
        nomediaEnabled: Boolean,
        searchFoldersUseAll: Boolean,
        searchFolders: Set<String>
    ): List<DisplayItem> {
        val entries = queryVideosInternal(bucketId, resolver)
        val searchFiltered = applySearchFolderFilter(entries, searchFoldersUseAll, searchFolders)
        val noMediaIndex = buildNoMediaIndexForEntries(searchFiltered, resolver, nomediaEnabled)
        val filtered = applyNoMediaFilter(searchFiltered, noMediaIndex)
        sortEntries(filtered, sortMode, sortOrder)
        val enriched = attachSubtitleInfo(filtered, resolver)
        return buildVideoItems(enriched)
    }

    override fun loadHierarchy(
        path: String,
        sortMode: VideoSortMode,
        sortOrder: VideoSortOrder,
        resolver: ContentResolver,
        nomediaEnabled: Boolean,
        searchFoldersUseAll: Boolean,
        searchFolders: Set<String>
    ): List<DisplayItem> {
        val entries = if (path.isEmpty()) {
            queryVideos(resolver)
        } else {
            queryVideosForHierarchy(path, resolver)
        }
        val searchFiltered = applySearchFolderFilter(entries, searchFoldersUseAll, searchFolders)
        val noMediaIndex = buildNoMediaIndexForEntries(searchFiltered, resolver, nomediaEnabled)
        val filtered = applyNoMediaFilter(searchFiltered, noMediaIndex)
        val enriched = attachSubtitleInfo(filtered, resolver)
        return buildHierarchyLevelItems(enriched, path, sortMode, sortOrder, noMediaIndex)
    }

    fun loadVideosUnderHierarchy(
        path: String,
        sortMode: VideoSortMode,
        sortOrder: VideoSortOrder,
        resolver: ContentResolver,
        nomediaEnabled: Boolean,
        searchFoldersUseAll: Boolean,
        searchFolders: Set<String>
    ): List<DisplayItem> {
        val entries = if (path.isEmpty()) {
            queryVideos(resolver)
        } else {
            queryVideosForHierarchy(path, resolver)
        }
        val searchFiltered = applySearchFolderFilter(entries, searchFoldersUseAll, searchFolders)
        val noMediaIndex = buildNoMediaIndexForEntries(searchFiltered, resolver, nomediaEnabled)
        val filtered = applyNoMediaFilter(searchFiltered, noMediaIndex)
        sortEntries(filtered, sortMode, sortOrder)
        val enriched = attachSubtitleInfo(filtered, resolver)
        return buildVideoItems(enriched)
    }

    private fun queryVideos(resolver: ContentResolver): MutableList<VideoEntry> =
        queryVideosInternal(null, resolver)

    private fun queryVideosInternal(
        bucketId: String?,
        resolver: ContentResolver,
        contentUri: Uri? = null,
        selectionOverride: String? = null,
        selectionArgsOverride: Array<String>? = null
    ): MutableList<VideoEntry> {
        val entries = mutableListOf<VideoEntry>()
        val projection = mutableListOf(
            MediaStore.Video.Media._ID,
            MediaStore.Video.Media.DISPLAY_NAME,
            MediaStore.Video.Media.BUCKET_ID,
            MediaStore.Video.Media.BUCKET_DISPLAY_NAME,
            MediaStore.Video.Media.DURATION,
            MediaStore.Video.Media.WIDTH,
            MediaStore.Video.Media.HEIGHT,
            MediaStore.Video.Media.RELATIVE_PATH,
            MediaStore.Video.Media.DATE_MODIFIED,
            MediaStore.Video.Media.SIZE,
            MediaStore.Video.Media.VOLUME_NAME
        )
        val includeFrameRate = Build.VERSION.SDK_INT >= Build.VERSION_CODES.R
        if (includeFrameRate) {
            projection.add(MediaStore.Video.Media.CAPTURE_FRAMERATE)
        }
        val sortOrder = MediaStore.Video.Media.DATE_ADDED + " DESC"
        val selection = when {
            selectionOverride != null -> selectionOverride
            bucketId != null -> "${MediaStore.Video.Media.BUCKET_ID}=?"
            else -> null
        }
        val selectionArgs = when {
            selectionOverride != null -> selectionArgsOverride
            bucketId != null -> arrayOf(bucketId)
            else -> null
        }
        val targetUri = contentUri ?: VIDEOS_URI
        resolver.query(targetUri, projection.toTypedArray(), selection, selectionArgs, sortOrder)?.use { cursor ->
            val idCol = cursor.getColumnIndexOrThrow(MediaStore.Video.Media._ID)
            val nameCol = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.DISPLAY_NAME)
            val bucketIdCol = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.BUCKET_ID)
            val bucketNameCol = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.BUCKET_DISPLAY_NAME)
            val durationCol = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.DURATION)
            val widthCol = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.WIDTH)
            val heightCol = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.HEIGHT)
            val pathCol = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.RELATIVE_PATH)
            val modifiedCol = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.DATE_MODIFIED)
            val sizeCol = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.SIZE)
            val frameRateCol = if (includeFrameRate) {
                cursor.getColumnIndex(MediaStore.Video.Media.CAPTURE_FRAMERATE)
            } else {
                -1
            }
            val volumeCol = cursor.getColumnIndex(MediaStore.Video.Media.VOLUME_NAME)

            while (cursor.moveToNext()) {
                val rawVolume = if (volumeCol >= 0) cursor.getString(volumeCol) else null
                val relPath = cursor.getString(pathCol)
                val frameRate = if (frameRateCol >= 0) cursor.getFloat(frameRateCol) else 0f
                entries.add(
                    VideoEntry(
                        id = cursor.getLong(idCol),
                        displayName = cursor.getString(nameCol),
                        bucketId = cursor.getString(bucketIdCol),
                        bucketName = cursor.getString(bucketNameCol),
                        duration = cursor.getLong(durationCol),
                        width = cursor.getInt(widthCol),
                        height = cursor.getInt(heightCol),
                        relativePath = relPath,
                        sizeBytes = cursor.getLong(sizeCol),
                        modifiedSeconds = cursor.getLong(modifiedCol),
                        frameRate = frameRate,
                        volumeName = rawVolume
                    )
                )
                if (entries.size <= 10) {
                    Log.d(
                        logTag,
                        "MediaStore entry volume=$rawVolume, relPath=$relPath, name=${cursor.getString(nameCol)}"
                    )
                }
            }
        }
        Log.d(logTag, "MediaStore query done. entries=${entries.size}")
        return entries
    }

    private fun queryVideosForHierarchy(path: String, resolver: ContentResolver): MutableList<VideoEntry> {
        val volumePath = parseVolumePath(path)
        val volumeName = volumePath?.volumeName
        val relative = volumePath?.relativePath ?: path
        val normalized = normalizePath(relative)
        val uri = if (!volumeName.isNullOrEmpty()) {
            MediaStore.Video.Media.getContentUri(volumeName)
        } else {
            VIDEOS_URI
        }
        val selection = if (normalized.isNotEmpty()) {
            "${MediaStore.Video.Media.RELATIVE_PATH} LIKE ?"
        } else {
            null
        }
        val selectionArgs = if (normalized.isNotEmpty()) {
            arrayOf("$normalized%")
        } else {
            null
        }
        return queryVideosInternal(
            bucketId = null,
            resolver = resolver,
            contentUri = uri,
            selectionOverride = selection,
            selectionArgsOverride = selectionArgs
        )
    }

    private fun buildVideoItems(entries: List<VideoEntry>): List<DisplayItem> {
        return entries.map { entry -> buildVideoItem(entry) }
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
        sortOrder: VideoSortOrder,
        noMediaIndex: Map<String, Set<String>>
    ): List<DisplayItem> {
        if (currentPath.isEmpty()) {
            return buildVolumeRootItems(entries)
        }
        val volumePath = parseVolumePath(currentPath)
        val normalizedPath = if (volumePath == null) normalizePath(currentPath) else normalizePath(volumePath.relativePath)
        val folders = mutableMapOf<String, FolderAggregate>()
        val videos = mutableListOf<VideoEntry>()
        val basePrefix = if (volumePath == null) "" else "$VOLUME_PREFIX${volumePath.volumeName}/"
        val activeVolume = resolveVolumeName(volumePath?.volumeName)

        for (entry in entries) {
            val entryVolume = resolveVolumeName(entry.volumeName)
            if (volumePath != null && entryVolume != volumePath.volumeName) {
                continue
            }
            var path = entryPath(entry)
            if (isBlockedByNoMedia(entryVolume, path, noMediaIndex)) {
                continue
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
            val childPath = basePrefix + normalizedPath + childName + "/"
            if (isBlockedByNoMedia(activeVolume, normalizedPath + childName + "/", noMediaIndex)) {
                continue
            }
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
            if (isBlockedBucket(aggregate.bucketId, noMediaIndex)) {
                continue
            }
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
        val sizeBytes: Long,
        val modifiedSeconds: Long,
        val frameRate: Float,
        val volumeName: String?,
        val hasSubtitle: Boolean = false
    )

    private fun buildVideoItem(entry: VideoEntry): DisplayItem {
        return DisplayItem(
            DisplayItem.Type.VIDEO,
            safe(entry.displayName, "Unknown"),
            null,
            0,
            durationMs = entry.duration,
            width = entry.width,
            height = entry.height,
            contentUri = ContentUris.withAppendedId(VIDEOS_URI, entry.id).toString(),
            sizeBytes = entry.sizeBytes,
            modifiedSeconds = entry.modifiedSeconds,
            frameRate = entry.frameRate,
            hasSubtitle = entry.hasSubtitle
        )
    }

    private fun attachSubtitleInfo(
        entries: MutableList<VideoEntry>,
        resolver: ContentResolver
    ): MutableList<VideoEntry> {
        if (entries.isEmpty()) {
            return entries
        }
        val subtitleIndex = buildSubtitleIndex(entries, resolver)
        if (subtitleIndex.isEmpty()) {
            return entries
        }
        return entries.map { entry ->
            val name = entry.displayName ?: ""
            val base = name.substringBeforeLast('.', name).lowercase(Locale.US)
            if (base.isEmpty() || entry.relativePath.isNullOrEmpty()) {
                entry
            } else {
                val key = SubtitleKey(resolveVolumeName(entry.volumeName), entry.relativePath)
                val subtitleBases = subtitleIndex[key]
                val hasSubtitle = subtitleBases?.any { matchesSubtitleBase(base, it) } == true
                if (hasSubtitle == entry.hasSubtitle) {
                    entry
                } else {
                    entry.copy(hasSubtitle = hasSubtitle)
                }
            }
        }.toMutableList()
    }

    private fun applyNoMediaFilter(
        entries: MutableList<VideoEntry>,
        noMediaIndex: Map<String, Set<String>>
    ): MutableList<VideoEntry> {
        if (noMediaIndex.isEmpty()) {
            return entries
        }
        return entries.filterNot { entry ->
            val relative = normalizePath(entry.relativePath)
            if (relative.isEmpty()) {
                return@filterNot false
            }
            val volume = resolveVolumeName(entry.volumeName)
            val blocked = noMediaIndex[volume] ?: return@filterNot false
            blocked.any { relative.startsWith(it) }
        }.toMutableList()
    }

    private fun applySearchFolderFilter(
        entries: MutableList<VideoEntry>,
        useAll: Boolean,
        searchFolders: Set<String>
    ): MutableList<VideoEntry> {
        if (useAll) {
            return entries
        }
        if (searchFolders.isEmpty() || entries.isEmpty()) {
            return mutableListOf()
        }
        return entries.filter { entry ->
            val bucket = entry.bucketId ?: return@filter false
            searchFolders.contains(bucket)
        }.toMutableList()
    }

    private fun buildNoMediaIndexForEntries(
        entries: List<VideoEntry>,
        resolver: ContentResolver,
        enabled: Boolean
    ): Map<String, Set<String>> {
        if (!enabled || entries.isEmpty()) {
            return emptyMap()
        }
        return buildNoMediaIndex(entries, resolver)
    }

    private fun buildNoMediaIndex(
        entries: List<VideoEntry>,
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
        noMediaIndex: Map<String, Set<String>>
    ): Boolean {
        if (relativePath.isEmpty()) {
            return false
        }
        val blocked = noMediaIndex[volumeName] ?: return false
        return blocked.any { relativePath.startsWith(it) }
    }

    private fun isBlockedBucket(
        bucketId: String?,
        noMediaIndex: Map<String, Set<String>>
    ): Boolean {
        if (bucketId.isNullOrEmpty()) {
            return false
        }
        val volumePath = parseVolumePath(bucketId)
        val volume = resolveVolumeName(volumePath?.volumeName)
        val relative = volumePath?.relativePath ?: bucketId
        val normalized = normalizePath(relative)
        if (normalized.isEmpty()) {
            return false
        }
        val blocked = noMediaIndex[volume] ?: return false
        return blocked.any { normalized.startsWith(it) }
    }

    private fun buildSubtitleIndex(
        entries: List<VideoEntry>,
        resolver: ContentResolver
    ): Map<SubtitleKey, Set<String>> {
        val allowedExt = setOf("srt", "vtt", "ass", "ssa", "sub")
        val pathsByVolume = mutableMapOf<String, MutableSet<String>>()
        entries.forEach { entry ->
            val path = entry.relativePath
            if (path.isNullOrEmpty()) {
                return@forEach
            }
            val volume = resolveVolumeName(entry.volumeName)
            pathsByVolume.getOrPut(volume) { mutableSetOf() }.add(path)
        }
        if (pathsByVolume.isEmpty()) {
            return emptyMap()
        }
        val result = mutableMapOf<SubtitleKey, MutableSet<String>>()
        pathsByVolume.forEach { (volume, paths) ->
            val filesUri = MediaStore.Files.getContentUri(volume)
            val projection = arrayOf(
                MediaStore.Files.FileColumns.DISPLAY_NAME,
                MediaStore.Files.FileColumns.RELATIVE_PATH
            )
            paths.forEach { relativePath ->
                resolver.query(
                    filesUri,
                    projection,
                    "${MediaStore.Files.FileColumns.RELATIVE_PATH}=?",
                    arrayOf(relativePath),
                    null
                )?.use { cursor ->
                    val nameCol = cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns.DISPLAY_NAME)
                    val pathCol = cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns.RELATIVE_PATH)
                    while (cursor.moveToNext()) {
                        val name = cursor.getString(nameCol) ?: continue
                        val path = cursor.getString(pathCol) ?: relativePath
                        val ext = name.substringAfterLast('.', "").lowercase(Locale.US)
                        if (!allowedExt.contains(ext)) {
                            continue
                        }
                        val base = name.substringBeforeLast('.', name).lowercase(Locale.US)
                        if (base.isEmpty()) {
                            continue
                        }
                        val key = SubtitleKey(volume, path)
                        result.getOrPut(key) { mutableSetOf() }.add(base)
                    }
                }
            }
        }
        return result
    }

    private fun matchesSubtitleBase(videoBase: String, subtitleBase: String): Boolean {
        return subtitleBase == videoBase || subtitleBase.startsWith("$videoBase.")
    }

    private data class SubtitleKey(
        val volumeName: String,
        val relativePath: String
    )

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

    private fun entryPath(entry: VideoEntry): String {
        val relative = normalizePath(entry.relativePath)
        if (relative.isNotEmpty()) {
            return relative
        }
        val bucket = normalizePath(safe(entry.bucketName, "Unknown"))
        return bucket
    }

    private fun buildVolumeRootItems(entries: List<VideoEntry>): List<DisplayItem> {
        val volumes = mutableMapOf<String, FolderAggregate>()
        for (entry in entries) {
            val volumeName = resolveVolumeName(entry.volumeName)
            val label = buildVolumeLabel(volumeName)
            val key = "$VOLUME_PREFIX$volumeName/"
            val aggregate = volumes.getOrPut(key) { FolderAggregate(key, label) }
            val path = entryPath(entry)
            val remainder = path
            if (remainder.isEmpty()) {
                aggregate.directVideoCount++
                continue
            }
            val slash = remainder.indexOf('/')
            val childName = if (slash >= 0) remainder.substring(0, slash) else remainder
            if (childName.isNotEmpty()) {
                aggregate.directSubfolders.add(childName)
            }
        }
        val sorted = volumes.values.sortedBy { it.name.lowercase(Locale.US) }
        return sorted.map { aggregate ->
            DisplayItem(
                DisplayItem.Type.HIERARCHY,
                aggregate.name,
                formatCountSubtitle(aggregate.directVideoCount, aggregate.directSubfolders.size),
                0,
                aggregate.bucketId
            )
        }
    }

    private data class VolumePath(
        val volumeName: String,
        val relativePath: String
    )

    private fun parseVolumePath(path: String): VolumePath? {
        if (!path.startsWith(VOLUME_PREFIX)) {
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

    private fun resolveVolumeName(name: String?): String {
        return if (name.isNullOrEmpty()) {
            MediaStore.VOLUME_EXTERNAL_PRIMARY
        } else {
            name
        }
    }

    private fun buildVolumeLabel(volumeName: String): String {
        Log.d(logTag, "Build volume label for volumeName=$volumeName")
        val context = NsPlayerApp.appContext()
        return if (volumeName == MediaStore.VOLUME_EXTERNAL_PRIMARY) {
            context?.getString(R.string.storage_internal) ?: "내장 스토리지"
        } else {
            context?.getString(R.string.storage_external_format, volumeName)
                ?: "외장 스토리지 ($volumeName)"
        }
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
        private const val VOLUME_PREFIX = "volume:"
    }
}






