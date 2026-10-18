package com.nshell.nsplayer.ui.player

import android.content.ContentResolver
import android.content.ContentUris
import android.content.Context
import android.net.Uri
import android.provider.MediaStore
import android.provider.OpenableColumns
import androidx.annotation.WorkerThread
import androidx.media3.common.MimeTypes
import java.io.File
import java.io.InterruptedIOException
import java.nio.charset.Charset
import java.util.Locale

/** Performs subtitle provider I/O and conversion. Call its methods from a worker thread. */
internal class SubtitleRepository(context: Context) {
    private val context = context.applicationContext
    private val resolver: ContentResolver = this.context.contentResolver

    @WorkerThread
    fun findCandidates(videoUri: Uri): List<SubtitleSource>? {
        val video = queryVideoInfo(videoUri) ?: return null
        if (video.relativePath.isEmpty() || video.displayName.isEmpty()) return emptyList()

        val filesUri = MediaStore.Files.getContentUri(
            video.volumeName ?: MediaStore.VOLUME_EXTERNAL_PRIMARY
        )
        val projection = arrayOf(
            MediaStore.Files.FileColumns._ID,
            MediaStore.Files.FileColumns.DISPLAY_NAME
        )
        val cursor = resolver.query(
            filesUri,
            projection,
            "${MediaStore.Files.FileColumns.RELATIVE_PATH}=?",
            arrayOf(video.relativePath),
            null
        ) ?: return null
        return cursor.use {
            val idColumn = it.getColumnIndexOrThrow(MediaStore.Files.FileColumns._ID)
            val nameColumn = it.getColumnIndexOrThrow(MediaStore.Files.FileColumns.DISPLAY_NAME)
            buildList {
                while (it.moveToNext()) {
                    val name = it.getString(nameColumn) ?: continue
                    val nameMatch = SubtitleCandidatePolicy.match(video.displayName, name) ?: continue
                    add(
                        SubtitleSource(
                            uri = ContentUris.withAppendedId(filesUri, it.getLong(idColumn)),
                            label = name,
                            mimeType = mimeTypeForName(name),
                            extension = name.substringAfterLast('.', ""),
                            nameMatch = nameMatch
                        )
                    )
                }
            }
        }
    }

    @WorkerThread
    fun resolveSource(uri: Uri, fallbackName: String): SubtitleSource {
        val name = displayName(uri) ?: fallbackName
        return SubtitleSource(
            uri = uri,
            label = name,
            mimeType = mimeTypeForName(name),
            extension = name.substringAfterLast('.', "")
        )
    }

    @WorkerThread
    fun displayName(uri: Uri): String? {
        val projection = arrayOf(OpenableColumns.DISPLAY_NAME)
        return try {
            resolver.query(uri, projection, null, null, null)?.use { cursor ->
                val nameColumn = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (nameColumn >= 0 && cursor.moveToFirst()) cursor.getString(nameColumn) else null
            }
        } catch (_: RuntimeException) {
            null
        }
    }

    @WorkerThread
    fun convertToUtf8(
        source: SubtitleSource,
        encoding: String,
        isCancelled: () -> Boolean
    ): File? {
        val charset = runCatching { Charset.forName(encoding) }.getOrNull() ?: return null
        var output: File? = null
        return try {
            val safeExtension = source.extension
                .takeIf { it.matches(Regex("[A-Za-z0-9]{1,8}")) }
                ?: "srt"
            resolver.openInputStream(source.uri)?.bufferedReader(charset)?.use { reader ->
                val target = File.createTempFile("subtitle_", ".$safeExtension", context.cacheDir)
                output = target
                target.bufferedWriter(Charsets.UTF_8).use { writer ->
                    val buffer = CharArray(DEFAULT_BUFFER_SIZE)
                    while (true) {
                        if (Thread.currentThread().isInterrupted || isCancelled()) {
                            throw InterruptedIOException("Subtitle conversion cancelled")
                        }
                        val count = reader.read(buffer)
                        if (count < 0) break
                        writer.write(buffer, 0, count)
                    }
                }
                target
            }
        } catch (_: Exception) {
            output?.delete()
            null
        }
    }

    private fun queryVideoInfo(uri: Uri): VideoInfo? {
        val projection = arrayOf(
            MediaStore.Video.Media.DISPLAY_NAME,
            MediaStore.Video.Media.RELATIVE_PATH,
            MediaStore.Video.Media.VOLUME_NAME
        )
        return resolver.query(uri, projection, null, null, null)?.use { cursor ->
            if (!cursor.moveToFirst()) return@use null
            val nameColumn = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.DISPLAY_NAME)
            val pathColumn = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.RELATIVE_PATH)
            val volumeColumn = cursor.getColumnIndex(MediaStore.Video.Media.VOLUME_NAME)
            VideoInfo(
                displayName = cursor.getString(nameColumn).orEmpty(),
                relativePath = cursor.getString(pathColumn).orEmpty(),
                volumeName = if (volumeColumn >= 0) cursor.getString(volumeColumn) else null
            )
        }
    }

    private data class VideoInfo(
        val displayName: String,
        val relativePath: String,
        val volumeName: String?
    )

    companion object {
        fun mimeTypeForName(name: String): String = when (
            name.substringAfterLast('.', "").lowercase(Locale.US)
        ) {
            "vtt" -> MimeTypes.TEXT_VTT
            "ass", "ssa" -> MimeTypes.TEXT_SSA
            else -> MimeTypes.APPLICATION_SUBRIP
        }

        fun isTextSubtitle(mimeType: String): Boolean =
            mimeType.startsWith("text/") ||
                mimeType == MimeTypes.APPLICATION_SUBRIP ||
                mimeType == MimeTypes.TEXT_SSA
    }
}
