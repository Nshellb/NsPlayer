package com.nshell.nsplayer.ui.main

import android.content.ContentResolver
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import com.nshell.nsplayer.data.cache.VideoListCacheStore
import com.nshell.nsplayer.data.repository.VideoListItem
import com.nshell.nsplayer.data.repository.VideoQuery
import com.nshell.nsplayer.data.repository.VideoQuerySource
import com.nshell.nsplayer.data.repository.VideoRepository
import java.util.concurrent.Executor
import java.util.concurrent.ExecutorService
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.atomic.AtomicLong

/** Worker executors belong to this ViewModel; mainExecutor dispatches to the UI thread. */
class VideoBrowserViewModel(
    private val savedStateHandle: SavedStateHandle,
    private val repository: VideoRepository,
    private val executor: ExecutorService,
    private val cacheExecutor: ExecutorService,
    private val prefetchExecutor: ExecutorService,
    private val cache: VideoListCacheStore?,
    private val itemMapper: (List<VideoListItem>) -> List<DisplayItem>,
    private val mainExecutor: Executor
) : ViewModel() {
    private val loadState = MutableLiveData(VideoBrowserLoadState())
    private val state = MutableLiveData(restoreNavigationState())
    private val requestCounter = AtomicLong(0L)
    private var freshResultRequestId = 0L
    private var lastQuery: VideoQuery? = null
    @Volatile private var cleared = false

    fun getLoadState(): LiveData<VideoBrowserLoadState> = loadState

    val currentLoadState: VideoBrowserLoadState
        get() = requireNotNull(loadState.value)

    fun getState(): LiveData<VideoBrowserState> = state

    val currentState: VideoBrowserState
        get() = state.value ?: VideoBrowserState()

    fun setState(newState: VideoBrowserState) {
        val normalized = newState.normalizedNavigationState()
        if (!currentState.hasSameNavigation(normalized)) {
            // Invalidate both queued worker results and the old directory's displayed list.
            requestCounter.incrementAndGet()
            loadState.value = VideoBrowserLoadState()
        }
        saveNavigationState(normalized)
        state.value = normalized
    }

    fun updateState(update: (VideoBrowserState) -> VideoBrowserState) {
        setState(update(currentState))
    }

    fun navigateUp(): Boolean {
        val parent = currentState.parentNavigationState() ?: return false
        setState(parent)
        return true
    }

    fun hasSavedNavigationState(): Boolean =
        savedStateHandle[KEY_NAVIGATION_SAVED] ?: false

    private fun restoreNavigationState(): VideoBrowserState {
        if (!hasSavedNavigationState()) {
            return VideoBrowserState()
        }
        val mode = savedStateHandle.get<String>(KEY_MODE)
            ?.let { value -> runCatching { VideoMode.valueOf(value) }.getOrNull() }
            ?: VideoMode.FOLDERS
        return VideoBrowserState(
            currentMode = mode,
            inFolderVideos = savedStateHandle[KEY_IN_FOLDER] ?: false,
            selectedBucketId = savedStateHandle[KEY_BUCKET_ID],
            selectedBucketName = savedStateHandle[KEY_BUCKET_NAME],
            hierarchyPath = savedStateHandle[KEY_HIERARCHY_PATH] ?: ""
        ).normalizedNavigationState()
    }

    private fun saveNavigationState(value: VideoBrowserState) {
        savedStateHandle[KEY_NAVIGATION_SAVED] = true
        savedStateHandle[KEY_MODE] = value.currentMode.name
        savedStateHandle[KEY_IN_FOLDER] = value.inFolderVideos
        savedStateHandle[KEY_BUCKET_ID] = value.selectedBucketId
        savedStateHandle[KEY_BUCKET_NAME] = value.selectedBucketName
        savedStateHandle[KEY_HIERARCHY_PATH] = value.hierarchyPath
    }

    fun load(
        query: VideoQuery,
        resolver: ContentResolver,
        useCache: Boolean = false,
        showRefreshing: Boolean = false
    ) {
        loadInternal(
            query = query,
            useCache = useCache,
            showRefreshing = showRefreshing,
            loader = { repository.load(query, resolver) },
            prefetch = if (query.source is VideoQuerySource.Hierarchy) {
                { items, requestId -> prefetchHierarchyChildren(items, query, resolver, requestId) }
            } else {
                null
            }
        )
    }

    fun cancelLoad() {
        requestCounter.incrementAndGet()
        loadState.value = VideoBrowserLoadState(items = currentLoadState.items)
    }

    internal fun loadInternal(
        query: VideoQuery,
        useCache: Boolean,
        showRefreshing: Boolean,
        loader: () -> List<VideoListItem>,
        prefetch: ((List<VideoListItem>, Long) -> Unit)? = null
    ) {
        if (cleared) return
        val requestId = requestCounter.incrementAndGet()
        freshResultRequestId = 0L
        val retainedItems = currentLoadState.items.takeIf { lastQuery == query }
        lastQuery = query
        loadState.value = VideoBrowserLoadState(
            items = retainedItems,
            phase = if (showRefreshing) VideoBrowserLoadState.Phase.REFRESHING
                else VideoBrowserLoadState.Phase.LOADING
        )
        if (useCache && cache != null) {
            submitOptional(cacheExecutor) {
                if (!isCurrent(requestId)) return@submitOptional
                val cached = cache.read(query) ?: return@submitOptional
                mainExecutor.execute {
                    // A late cache may provide fallback data after failure, but must not
                    // clear the error or replace a successful fresh response.
                    if (isCurrent(requestId) && freshResultRequestId != requestId &&
                        currentLoadState.items == null
                    ) {
                        loadState.value = currentLoadState.copy(items = itemMapper(cached))
                    }
                }
            }
        }
        try {
            executor.execute {
                if (!isCurrent(requestId)) return@execute
                val result = try {
                    loader()
                } catch (error: Exception) {
                    mainExecutor.execute { finishFailure(requestId, error) }
                    return@execute
                }
                if (!isCurrent(requestId)) return@execute
                mainExecutor.execute {
                    if (isCurrent(requestId)) {
                        freshResultRequestId = requestId
                        loadState.value = VideoBrowserLoadState(
                            items = itemMapper(result),
                            phase = VideoBrowserLoadState.Phase.READY
                        )
                        // Disk writes and prefetch are optional and cannot fail this load.
                        submitOptional(cacheExecutor) {
                            if (isCurrent(requestId)) cache?.write(query, result)
                        }
                    }
                }
                if (isCurrent(requestId)) {
                    try {
                        prefetch?.invoke(result, requestId)
                    } catch (_: Exception) {
                        // The foreground result is independent of speculative work.
                    }
                }
            }
        } catch (error: RejectedExecutionException) {
            finishFailure(requestId, error)
        }
    }

    private fun isCurrent(requestId: Long): Boolean =
        !cleared && requestId == requestCounter.get()

    private fun finishFailure(requestId: Long, error: Exception) {
        if (!isCurrent(requestId)) return
        loadState.value = currentLoadState.copy(
            phase = VideoBrowserLoadState.Phase.FAILED,
            error = error
        )
    }

    private fun submitOptional(worker: ExecutorService, task: () -> Unit) {
        try {
            worker.execute {
                try {
                    task()
                } catch (_: Exception) {
                    // Cache and prefetch failures must not change the visible load state.
                }
            }
        } catch (_: RejectedExecutionException) {
            // The ViewModel may have been cleared while a worker was completing.
        }
    }

    override fun onCleared() {
        cleared = true
        requestCounter.incrementAndGet()
        executor.shutdownNow()
        cacheExecutor.shutdownNow()
        prefetchExecutor.shutdownNow()
        super.onCleared()
    }

    private fun prefetchHierarchyChildren(
        items: List<VideoListItem>,
        parentQuery: VideoQuery,
        resolver: ContentResolver,
        requestId: Long
    ) {
        val childPaths = items.asSequence()
            .filter { it.type == VideoListItem.Type.HIERARCHY }
            .mapNotNull { it.id?.takeIf(String::isNotEmpty) }
            .distinct()
            .take(PREFETCH_LIMIT)
            .toList()
        if (childPaths.isEmpty()) {
            return
        }
        if (cache == null) return
        submitOptional(prefetchExecutor) {
            if (!isCurrent(requestId)) {
                return@submitOptional
            }
            for (path in childPaths) {
                if (!isCurrent(requestId)) {
                    break
                }
                val query = VideoQuery(
                    source = VideoQuerySource.Hierarchy(path),
                    options = parentQuery.options
                )
                val cached = cache.read(query)
                if (!cached.isNullOrEmpty()) {
                    continue
                }
                val result = repository.load(query, resolver)
                if (isCurrent(requestId)) {
                    cache.write(query, result)
                }
            }
        }
    }

    companion object {
        private const val PREFETCH_LIMIT = 6
        private const val KEY_NAVIGATION_SAVED = "browser_navigation_saved"
        private const val KEY_MODE = "browser_mode"
        private const val KEY_IN_FOLDER = "browser_in_folder"
        private const val KEY_BUCKET_ID = "browser_bucket_id"
        private const val KEY_BUCKET_NAME = "browser_bucket_name"
        private const val KEY_HIERARCHY_PATH = "browser_hierarchy_path"
    }
}
