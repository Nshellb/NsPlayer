package com.nshell.nsplayer.ui.player

import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.Looper
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicLong

/** Owns subtitle workers, request invalidation, candidate results, and converted-file cache. */
internal class SubtitleCoordinator(context: Context) {
    private data class PreparationKey(val uri: Uri, val encoding: String)

    private val repository = SubtitleRepository(context)
    private val mainHandler = Handler(Looper.getMainLooper())
    private val currentResolver = Executors.newSingleThreadExecutor()
    private val prefetchResolver = Executors.newSingleThreadExecutor()
    private val metadataResolver = Executors.newSingleThreadExecutor()
    private val conversionResolver = Executors.newSingleThreadExecutor()
    private val discoveryGeneration = AtomicLong()
    private val preparationGeneration = AtomicLong()
    private val candidates = mutableMapOf<Uri, List<SubtitleSource>>()
    private val pendingCurrent = mutableSetOf<Pair<Long, Uri>>()
    private val pendingPrefetch = mutableSetOf<Pair<Long, Uri>>()
    private val preparedUris = mutableMapOf<PreparationKey, Uri>()
    private val preparedFiles = mutableSetOf<File>()
    private val preparationCallbacks = mutableMapOf<PreparationKey, (Uri?) -> Unit>()
    @Volatile private var closed = false

    fun candidatesFor(uri: Uri): List<SubtitleSource> = candidates[uri].orEmpty()

    fun hasCandidateResult(uri: Uri): Boolean = candidates.containsKey(uri)

    fun invalidateCandidate(uri: Uri) {
        candidates.remove(uri)
    }

    fun requestCandidates(
        uri: Uri,
        current: Boolean,
        onComplete: (List<SubtitleSource>?) -> Unit
    ) {
        if (closed) return
        if (candidates.containsKey(uri)) return
        val generation = discoveryGeneration.get()
        val key = generation to uri
        val pending = if (current) pendingCurrent else pendingPrefetch
        if (key in pending || (!current && key in pendingCurrent)) return
        pending.add(key)
        val executor = if (current) currentResolver else prefetchResolver
        executor.execute {
            val result = try {
                repository.findCandidates(uri)
            } catch (_: RuntimeException) {
                null
            }
            mainHandler.post {
                pending.remove(key)
                if (closed || generation != discoveryGeneration.get()) return@post
                val previous = candidates[uri]
                if (result != null && (previous == null || previous.isEmpty())) {
                    candidates[uri] = result
                }
                onComplete(result?.let { candidates[uri].orEmpty() })
            }
        }
    }

    fun resolveSource(uri: Uri, fallbackName: String, onComplete: (SubtitleSource) -> Unit) {
        submitMetadata(
            task = { repository.resolveSource(uri, fallbackName) },
            onComplete = { result -> result?.let(onComplete) }
        )
    }

    fun resolveDisplayName(uri: Uri, onComplete: (String?) -> Unit) {
        submitMetadata(
            task = { repository.displayName(uri) },
            onComplete = onComplete
        )
    }

    /** Returns immediately available URI, or null while a conversion is queued. */
    fun prepare(
        source: SubtitleSource,
        encoding: String,
        onComplete: (Uri?) -> Unit
    ): Uri? {
        if (closed) return null
        if (encoding.equals(UTF8, ignoreCase = true) ||
            !SubtitleRepository.isTextSubtitle(source.mimeType)
        ) {
            return source.uri
        }
        val key = PreparationKey(source.uri, encoding)
        preparedUris[key]?.let { return it }
        if (preparationCallbacks.putIfAbsent(key, onComplete) != null) return null

        val generation = preparationGeneration.get()
        conversionResolver.execute {
            val output = repository.convertToUtf8(source, encoding) {
                closed || generation != preparationGeneration.get()
            }
            if (closed || generation != preparationGeneration.get()) {
                output?.delete()
                return@execute
            }
            mainHandler.post {
                if (closed || generation != preparationGeneration.get()) {
                    output?.delete()
                    return@post
                }
                val uri = output?.let(Uri::fromFile)
                if (output != null && uri != null) {
                    preparedFiles.add(output)
                    preparedUris[key] = uri
                }
                preparationCallbacks.remove(key)?.invoke(uri)
            }
        }
        return null
    }

    fun resetDiscovery() {
        discoveryGeneration.incrementAndGet()
        candidates.clear()
        pendingCurrent.clear()
        pendingPrefetch.clear()
    }

    fun clearPrepared() {
        preparationGeneration.incrementAndGet()
        preparedFiles.forEach { it.delete() }
        preparedFiles.clear()
        preparedUris.clear()
        preparationCallbacks.clear()
    }

    fun close() {
        if (closed) return
        closed = true
        resetDiscovery()
        clearPrepared()
        mainHandler.removeCallbacksAndMessages(null)
        currentResolver.shutdownNow()
        prefetchResolver.shutdownNow()
        metadataResolver.shutdownNow()
        conversionResolver.shutdownNow()
    }

    private fun <T> submitMetadata(task: () -> T, onComplete: (T?) -> Unit) {
        if (closed) return
        val generation = discoveryGeneration.get()
        metadataResolver.execute {
            val result = try {
                task()
            } catch (_: RuntimeException) {
                null
            }
            mainHandler.post {
                if (!closed && generation == discoveryGeneration.get()) onComplete(result)
            }
        }
    }

    private companion object {
        const val UTF8 = "UTF-8"
    }
}
