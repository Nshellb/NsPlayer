package com.nshell.nsplayer.data.cache

import android.content.Context
import android.util.Log
import com.nshell.nsplayer.data.repository.VideoListItem
import com.nshell.nsplayer.data.repository.VideoQuery
import com.nshell.nsplayer.data.repository.VideoQuerySource
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest
import java.util.Locale

class VideoListCache(context: Context) : VideoListCacheStore {
    private val dir = File(context.filesDir, "video_list_cache")
    private val logTag = "VideoListCache"

    override fun read(query: VideoQuery): List<VideoListItem>? {
        val file = fileFor(query)
        if (!file.exists()) {
            return null
        }
        return try {
            val root = JSONObject(file.readText())
            if (root.optInt("version", 0) != CACHE_VERSION) {
                return null
            }
            val array = root.optJSONArray("items") ?: return null
            val items = mutableListOf<VideoListItem>()
            for (index in 0 until array.length()) {
                val obj = array.optJSONObject(index) ?: continue
                val typeName = obj.optString("type", "")
                val type = VideoListItem.Type.values().firstOrNull { it.name == typeName } ?: continue
                val name = if (obj.isNull("name")) null else obj.optString("name", "")
                val id = if (obj.isNull("id")) null else obj.optString("id", "")
                val videoCount = obj.optInt("videoCount", 0)
                val folderCount = obj.optInt("folderCount", 0)
                val durationMs = obj.optLong("durationMs", 0L)
                val width = obj.optInt("width", 0)
                val height = obj.optInt("height", 0)
                val contentUri = if (obj.isNull("contentUri")) null else obj.optString("contentUri", "")
                val sizeBytes = obj.optLong("sizeBytes", 0L)
                val modifiedSeconds = obj.optLong("modifiedSeconds", 0L)
                val frameRate = obj.optDouble("frameRate", 0.0).toFloat()
                val hasSubtitle = obj.optBoolean("hasSubtitle", false)
                val volumeName = if (obj.isNull("volumeName")) null else obj.optString("volumeName", "")
                items.add(
                    VideoListItem(
                        type = type,
                        name = name,
                        id = id,
                        videoCount = videoCount,
                        folderCount = folderCount,
                        durationMs = durationMs,
                        width = width,
                        height = height,
                        contentUri = contentUri,
                        sizeBytes = sizeBytes,
                        modifiedSeconds = modifiedSeconds,
                        frameRate = frameRate,
                        hasSubtitle = hasSubtitle,
                        volumeName = volumeName
                    )
                )
            }
            items
        } catch (e: Exception) {
            Log.w(logTag, "Failed to read cache ${file.name}", e)
            null
        }
    }

    @Synchronized
    override fun write(query: VideoQuery, items: List<VideoListItem>) {
        if (!dir.exists() && !dir.mkdirs()) {
            return
        }
        val file = fileFor(query)
        try {
            val root = JSONObject()
            root.put("version", CACHE_VERSION)
            root.put("updatedAt", System.currentTimeMillis())
            val array = JSONArray()
            items.forEach { item ->
                val obj = JSONObject()
                obj.put("type", item.type.name)
                obj.put("name", item.name)
                obj.put("id", item.id)
                obj.put("videoCount", item.videoCount)
                obj.put("folderCount", item.folderCount)
                obj.put("durationMs", item.durationMs)
                obj.put("width", item.width)
                obj.put("height", item.height)
                obj.put("contentUri", item.contentUri)
                obj.put("sizeBytes", item.sizeBytes)
                obj.put("modifiedSeconds", item.modifiedSeconds)
                obj.put("frameRate", item.frameRate.toDouble())
                obj.put("hasSubtitle", item.hasSubtitle)
                obj.put("volumeName", item.volumeName)
                array.put(obj)
            }
            root.put("items", array)
            val payload = root.toString()
            val temp = File(dir, file.name + ".tmp")
            temp.writeText(payload)
            if (!temp.renameTo(file)) {
                temp.delete()
                file.writeText(payload)
            }
        } catch (e: Exception) {
            Log.w(logTag, "Failed to write cache ${file.name}", e)
        }
    }

    private fun fileFor(query: VideoQuery): File {
        val sourceKey = when (val source = query.source) {
            is VideoQuerySource.Browse -> "browse:${source.mode.name}"
            is VideoQuerySource.Folder -> "folder:${source.bucketId}"
            is VideoQuerySource.Hierarchy -> "hierarchy:${source.path}"
            is VideoQuerySource.HierarchyVideos -> "hierarchy_videos:${source.path}"
            is VideoQuerySource.Search -> "search:${source.text}:${source.limit.orEmpty()}"
        }
        val options = query.options
        val raw = listOf(
            sourceKey,
            options.sortField.name,
            options.sortDirection.name,
            if (options.respectNoMedia) "nomedia_on" else "nomedia_off",
            options.folderIds?.sorted()?.joinToString("|") ?: "all_folders"
        ).joinToString("|")
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(raw.toByteArray(Charsets.UTF_8))
        val hex = digest.joinToString("") { byte ->
            String.format(Locale.US, "%02x", byte)
        }
        return File(dir, "video_list_$hex.json")
    }

    companion object {
        private const val CACHE_VERSION = 2
    }
}

private fun Int?.orEmpty(): String = this?.toString().orEmpty()
