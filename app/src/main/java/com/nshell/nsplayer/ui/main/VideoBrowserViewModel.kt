package com.nshell.nsplayer.ui.main

import android.content.ContentResolver
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import com.nshell.nsplayer.NsPlayerApp
import com.nshell.nsplayer.data.cache.VideoListCache
import com.nshell.nsplayer.data.repository.MediaStoreVideoRepository
import com.nshell.nsplayer.data.repository.VideoRepository
import java.security.MessageDigest
import java.util.Locale
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class VideoBrowserViewModel(
    private val savedStateHandle: SavedStateHandle
) : ViewModel() {
    private val items = MutableLiveData<List<DisplayItem>?>(null)
    private val loading = MutableLiveData(false)
    private val refreshing = MutableLiveData(false)
    private val state = MutableLiveData(restoreNavigationState())
    private val repository: VideoRepository = MediaStoreVideoRepository()
    private val executor: ExecutorService = Executors.newSingleThreadExecutor()
    private val cacheExecutor: ExecutorService = Executors.newSingleThreadExecutor()
    private val prefetchExecutor: ExecutorService = Executors.newSingleThreadExecutor()
    private val cache: VideoListCache? = NsPlayerApp.appContext()?.let { VideoListCache(it) }
    private val requestCounter = AtomicLong(0L)

    fun getItems(): LiveData<List<DisplayItem>?> = items

    fun getLoading(): LiveData<Boolean> = loading

    fun getRefreshing(): LiveData<Boolean> = refreshing

    fun getState(): LiveData<VideoBrowserState> = state

    fun setState(newState: VideoBrowserState) {
        val normalized = normalizeNavigationState(newState)
        state.value = normalized
        saveNavigationState(normalized)
    }

    fun updateState(update: (VideoBrowserState) -> VideoBrowserState) {
        val current = state.value ?: VideoBrowserState()
        val updated = normalizeNavigationState(update(current))
        state.value = updated
        saveNavigationState(updated)
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
        return normalizeNavigationState(
            VideoBrowserState(
                currentMode = mode,
                inFolderVideos = savedStateHandle[KEY_IN_FOLDER] ?: false,
                selectedBucketId = savedStateHandle[KEY_BUCKET_ID],
                selectedBucketName = savedStateHandle[KEY_BUCKET_NAME],
                hierarchyPath = savedStateHandle[KEY_HIERARCHY_PATH] ?: ""
            )
        )
    }

    private fun saveNavigationState(value: VideoBrowserState) {
        savedStateHandle[KEY_NAVIGATION_SAVED] = true
        savedStateHandle[KEY_MODE] = value.currentMode.name
        savedStateHandle[KEY_IN_FOLDER] = value.inFolderVideos
        savedStateHandle[KEY_BUCKET_ID] = value.selectedBucketId
        savedStateHandle[KEY_BUCKET_NAME] = value.selectedBucketName
        savedStateHandle[KEY_HIERARCHY_PATH] = value.hierarchyPath
    }

    private fun normalizeNavigationState(value: VideoBrowserState): VideoBrowserState {
        val validFolder = value.currentMode == VideoMode.FOLDERS &&
            value.inFolderVideos &&
            !value.selectedBucketId.isNullOrEmpty()
        return value.copy(
            inFolderVideos = validFolder,
            selectedBucketId = if (validFolder) value.selectedBucketId else null,
            selectedBucketName = if (validFolder) value.selectedBucketName else null,
            hierarchyPath = if (value.currentMode == VideoMode.HIERARCHY) {
                value.hierarchyPath
            } else {
                ""
            }
        )
    }

    fun load(
        mode: VideoMode,
        sortMode: VideoSortMode,
        sortOrder: VideoSortOrder,
        resolver: ContentResolver,
        nomediaEnabled: Boolean,
        searchFoldersUseAll: Boolean,
        searchFolders: Set<String>,
        useCache: Boolean = false,
        showRefreshing: Boolean = false
    ) {
        val searchFoldersHash = buildSearchFoldersHash(searchFoldersUseAll, searchFolders)
        val key = VideoListCache.Key(
            queryType = VideoListCache.QueryType.MODE,
            mode = mode,
            sortMode = sortMode,
            sortOrder = sortOrder,
            nomediaEnabled = nomediaEnabled,
            searchFoldersHash = searchFoldersHash,
            bucketId = null,
            hierarchyPath = null
        )
        loadInternal(
            key,
            useCache,
            showRefreshing,
            loader = {
                repository.load(
                    mode,
                    sortMode,
                    sortOrder,
                    resolver,
                    nomediaEnabled,
                    searchFoldersUseAll,
                    searchFolders
                )
            }
        )
    }

    fun loadFolderVideos(
        bucketId: String,
        sortMode: VideoSortMode,
        sortOrder: VideoSortOrder,
        resolver: ContentResolver,
        nomediaEnabled: Boolean,
        searchFoldersUseAll: Boolean,
        searchFolders: Set<String>,
        useCache: Boolean = false,
        showRefreshing: Boolean = false
    ) {
        val searchFoldersHash = buildSearchFoldersHash(searchFoldersUseAll, searchFolders)
        val key = VideoListCache.Key(
            queryType = VideoListCache.QueryType.FOLDER,
            mode = VideoMode.FOLDERS,
            sortMode = sortMode,
            sortOrder = sortOrder,
            nomediaEnabled = nomediaEnabled,
            searchFoldersHash = searchFoldersHash,
            bucketId = bucketId,
            hierarchyPath = null
        )
        loadInternal(
            key,
            useCache,
            showRefreshing,
            loader = {
                repository.loadVideosInFolder(
                    bucketId,
                    sortMode,
                    sortOrder,
                    resolver,
                    nomediaEnabled,
                    searchFoldersUseAll,
                    searchFolders
                )
            }
        )
    }

    fun loadHierarchy(
        path: String,
        sortMode: VideoSortMode,
        sortOrder: VideoSortOrder,
        resolver: ContentResolver,
        nomediaEnabled: Boolean,
        searchFoldersUseAll: Boolean,
        searchFolders: Set<String>,
        useCache: Boolean = false,
        showRefreshing: Boolean = false
    ) {
        val searchFoldersHash = buildSearchFoldersHash(searchFoldersUseAll, searchFolders)
        val key = VideoListCache.Key(
            queryType = VideoListCache.QueryType.HIERARCHY,
            mode = VideoMode.HIERARCHY,
            sortMode = sortMode,
            sortOrder = sortOrder,
            nomediaEnabled = nomediaEnabled,
            searchFoldersHash = searchFoldersHash,
            bucketId = null,
            hierarchyPath = path
        )
        loadInternal(
            key,
            useCache,
            showRefreshing,
            loader = {
                repository.loadHierarchy(
                    path,
                    sortMode,
                    sortOrder,
                    resolver,
                    nomediaEnabled,
                    searchFoldersUseAll,
                    searchFolders
                )
            },
            prefetch = { items, requestId ->
                prefetchHierarchyChildren(
                    items,
                    sortMode,
                    sortOrder,
                    resolver,
                    nomediaEnabled,
                    searchFoldersUseAll,
                    searchFolders,
                    requestId
                )
            }
        )
    }

    fun setRefreshing(value: Boolean) {
        refreshing.value = value
    }

    private fun loadInternal(
        key: VideoListCache.Key,
        useCache: Boolean,
        showRefreshing: Boolean,
        loader: () -> List<DisplayItem>,
        prefetch: ((List<DisplayItem>, Long) -> Unit)? = null
    ) {
        val requestId = requestCounter.incrementAndGet()
        if (showRefreshing && !useCache) {
            refreshing.value = true
        } else if (!useCache) {
            loading.value = true
        }
        if (useCache) {
            cacheExecutor.execute {
                val cached = cache?.read(key)
                if (!cached.isNullOrEmpty()) {
                    if (requestId == requestCounter.get()) {
                        items.postValue(cached)
                        if (showRefreshing) {
                            refreshing.postValue(false)
                        } else {
                            loading.postValue(false)
                        }
                    }
                } else if (requestId == requestCounter.get()) {
                    if (showRefreshing) {
                        refreshing.postValue(true)
                    } else {
                        loading.postValue(true)
                    }
                }
            }
        }
        executor.execute {
            val result = loader()
            cache?.write(key, result)
            if (requestId == requestCounter.get()) {
                items.postValue(result)
                loading.postValue(false)
                refreshing.postValue(false)
                prefetch?.invoke(result, requestId)
            }
        }
    }

    override fun onCleared() {
        super.onCleared()
        executor.shutdown()
        cacheExecutor.shutdown()
        prefetchExecutor.shutdown()
    }

    private fun prefetchHierarchyChildren(
        items: List<DisplayItem>,
        sortMode: VideoSortMode,
        sortOrder: VideoSortOrder,
        resolver: ContentResolver,
        nomediaEnabled: Boolean,
        searchFoldersUseAll: Boolean,
        searchFolders: Set<String>,
        requestId: Long
    ) {
        val childPaths = items.asSequence()
            .filter { it.type == DisplayItem.Type.HIERARCHY }
            .mapNotNull { it.bucketId?.takeIf { id -> id.isNotEmpty() } }
            .distinct()
            .take(PREFETCH_LIMIT)
            .toList()
        if (childPaths.isEmpty()) {
            return
        }
        prefetchExecutor.execute {
            if (requestId != requestCounter.get()) {
                return@execute
            }
            for (path in childPaths) {
                if (requestId != requestCounter.get()) {
                    break
                }
                val key = VideoListCache.Key(
                    queryType = VideoListCache.QueryType.HIERARCHY,
                    mode = VideoMode.HIERARCHY,
                    sortMode = sortMode,
                    sortOrder = sortOrder,
                    nomediaEnabled = nomediaEnabled,
                    searchFoldersHash = buildSearchFoldersHash(searchFoldersUseAll, searchFolders),
                    bucketId = null,
                    hierarchyPath = path
                )
                val cached = cache?.read(key)
                if (!cached.isNullOrEmpty()) {
                    continue
                }
                val result = repository.loadHierarchy(
                    path,
                    sortMode,
                    sortOrder,
                    resolver,
                    nomediaEnabled,
                    searchFoldersUseAll,
                    searchFolders
                )
                cache?.write(key, result)
            }
        }
    }

    private fun buildSearchFoldersHash(useAll: Boolean, folders: Set<String>): String {
        if (useAll) {
            return "all"
        }
        if (folders.isEmpty()) {
            return "none"
        }
        val normalized = folders.sorted().joinToString("|")
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(normalized.toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { byte ->
            String.format(Locale.US, "%02x", byte)
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
