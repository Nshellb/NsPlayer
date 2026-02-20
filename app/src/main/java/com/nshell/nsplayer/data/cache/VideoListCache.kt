package com.nshell.nsplayer.data.cache

import android.content.Context
import android.util.Log
import com.nshell.nsplayer.ui.main.DisplayItem
import com.nshell.nsplayer.ui.main.VideoMode
import com.nshell.nsplayer.ui.main.VideoSortMode
import com.nshell.nsplayer.ui.main.VideoSortOrder
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest
import java.util.Locale

class VideoListCache(context: Context) {
    data class Key(
        val queryType: QueryType,
        val mode: VideoMode,
        val sortMode: VideoSortMode,
        val sortOrder: VideoSortOrder,
        val bucketId: String?,
        val hierarchyPath: String?
    )

    enum class QueryType {
        MODE,
        FOLDER,
        HIERARCHY
    }

    private val dir = File(context.filesDir, "video_list_cache")
    private val logTag = "VideoListCache"

    fun read(key: Key): List<DisplayItem>? {
        val file = fileFor(key)
        if (!file.exists()) {
            return null
        }
        return try {
            val root = JSONObject(file.readText())
            if (root.optInt("version", 0) != CACHE_VERSION) {
                return null
            }
            val array = root.optJSONArray("items") ?: return null
            val items = mutableListOf<DisplayItem>()
            for (index in 0 until array.length()) {
                val obj = array.optJSONObject(index) ?: continue
                val typeName = obj.optString("type", "")
                val type = DisplayItem.Type.values().firstOrNull { it.name == typeName } ?: continue
                val title = obj.optString("title", "")
                val subtitle = if (obj.isNull("subtitle")) null else obj.optString("subtitle", "")
                val indentLevel = obj.optInt("indentLevel", 0)
                val bucketId = if (obj.isNull("bucketId")) null else obj.optString("bucketId", "")
                val durationMs = obj.optLong("durationMs", 0L)
                val width = obj.optInt("width", 0)
                val height = obj.optInt("height", 0)
                val contentUri = if (obj.isNull("contentUri")) null else obj.optString("contentUri", "")
                val sizeBytes = obj.optLong("sizeBytes", 0L)
                val modifiedSeconds = obj.optLong("modifiedSeconds", 0L)
                val frameRate = obj.optDouble("frameRate", 0.0).toFloat()
                val hasSubtitle = obj.optBoolean("hasSubtitle", false)
                items.add(
                    DisplayItem(
                        type = type,
                        title = title,
                        subtitle = subtitle,
                        indentLevel = indentLevel,
                        bucketId = bucketId,
                        durationMs = durationMs,
                        width = width,
                        height = height,
                        contentUri = contentUri,
                        sizeBytes = sizeBytes,
                        modifiedSeconds = modifiedSeconds,
                        frameRate = frameRate,
                        hasSubtitle = hasSubtitle
                    )
                )
            }
            items
        } catch (e: Exception) {
            Log.w(logTag, "Failed to read cache ${file.name}", e)
            null
        }
    }

    fun write(key: Key, items: List<DisplayItem>) {
        if (!dir.exists() && !dir.mkdirs()) {
            return
        }
        val file = fileFor(key)
        try {
            val root = JSONObject()
            root.put("version", CACHE_VERSION)
            root.put("updatedAt", System.currentTimeMillis())
            val array = JSONArray()
            items.forEach { item ->
                val obj = JSONObject()
                obj.put("type", item.type.name)
                obj.put("title", item.title)
                obj.put("subtitle", item.subtitle)
                obj.put("indentLevel", item.indentLevel)
                obj.put("bucketId", item.bucketId)
                obj.put("durationMs", item.durationMs)
                obj.put("width", item.width)
                obj.put("height", item.height)
                obj.put("contentUri", item.contentUri)
                obj.put("sizeBytes", item.sizeBytes)
                obj.put("modifiedSeconds", item.modifiedSeconds)
                obj.put("frameRate", item.frameRate.toDouble())
                obj.put("hasSubtitle", item.hasSubtitle)
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

    private fun fileFor(key: Key): File {
        val raw = listOf(
            key.queryType.name,
            key.mode.name,
            key.sortMode.name,
            key.sortOrder.name,
            key.bucketId.orEmpty(),
            key.hierarchyPath.orEmpty()
        ).joinToString("|")
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(raw.toByteArray(Charsets.UTF_8))
        val hex = digest.joinToString("") { byte ->
            String.format(Locale.US, "%02x", byte)
        }
        return File(dir, "video_list_$hex.json")
    }

    companion object {
        private const val CACHE_VERSION = 1
    }
}
