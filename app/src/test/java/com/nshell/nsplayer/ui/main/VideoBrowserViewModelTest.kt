package com.nshell.nsplayer.ui.main

import android.content.ContentResolver
import androidx.arch.core.executor.testing.InstantTaskExecutorRule
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModelStore
import com.nshell.nsplayer.data.cache.VideoListCache
import com.nshell.nsplayer.data.cache.VideoListCacheStore
import com.nshell.nsplayer.data.repository.VideoRepository
import java.io.IOException
import java.util.ArrayDeque
import java.util.concurrent.AbstractExecutorService
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.TimeUnit
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class VideoBrowserViewModelTest {
    @get:Rule val instantTasks = InstantTaskExecutorRule()

    private val queryWorker = QueuedExecutor()
    private val cacheWorker = QueuedExecutor()
    private val prefetchWorker = QueuedExecutor()
    private val main = QueuedExecutor()
    private val cache = FakeCache()
    private val key = VideoListCache.Key(
        VideoListCache.QueryType.MODE, VideoMode.VIDEOS,
        VideoSortMode.TITLE, VideoSortOrder.ASC, false, "all", null, null
    )
    private val fresh = listOf(DisplayItem(DisplayItem.Type.VIDEO, "fresh", null, 0))
    private val cached = listOf(DisplayItem(DisplayItem.Type.VIDEO, "cached", null, 0))

    private fun newViewModel() = VideoBrowserViewModel(
        SavedStateHandle(), UnusedRepository, queryWorker, cacheWorker,
        prefetchWorker, cache, main
    )

    @Test fun initialFailureStopsLoadingAndRetryCanSucceed() {
        val vm = newViewModel()
        val error = SecurityException("permission revoked")
        vm.loadInternal(key, false, false, loader = { throw error })
        assertTrue(vm.currentLoadState.isLoading)
        queryWorker.runNext()
        main.runNext()
        assertSame(error, vm.currentLoadState.error)
        assertEquals(VideoBrowserLoadState.Phase.FAILED, vm.currentLoadState.phase)
        assertFalse(vm.currentLoadState.isLoading)
        assertFalse(vm.currentLoadState.isRefreshing)

        vm.loadInternal(key, false, true, loader = { fresh })
        assertNull(vm.currentLoadState.error)
        assertTrue(vm.currentLoadState.isRefreshing)
        queryWorker.runNext()
        main.runNext()
        assertEquals(VideoBrowserLoadState(fresh, VideoBrowserLoadState.Phase.READY), vm.currentLoadState)
    }

    @Test fun failedRefreshRetainsExistingList() {
        val vm = newViewModel()
        vm.loadInternal(key, false, false, loader = { fresh })
        queryWorker.runNext()
        main.runNext()

        vm.loadInternal(key, false, true, loader = { throw IOException() })
        queryWorker.runNext()
        main.runNext()
        assertEquals(fresh, vm.currentLoadState.items)
        assertNotNull(vm.currentLoadState.error)
        assertFalse(vm.currentLoadState.isRefreshing)
    }

    @Test fun cacheArrivingAfterFailureProvidesFallbackWithoutRestartingLoading() {
        val vm = newViewModel()
        cache.items = cached
        vm.loadInternal(key, true, true, loader = { throw IOException() })
        queryWorker.runNext()
        main.runNext()
        cacheWorker.runNext()
        main.runNext()
        assertEquals(cached, vm.currentLoadState.items)
        assertEquals(VideoBrowserLoadState.Phase.FAILED, vm.currentLoadState.phase)
        assertNotNull(vm.currentLoadState.error)
        assertFalse(vm.currentLoadState.isRefreshing)
    }

    @Test fun changedQueryDoesNotRetainItemsFromDifferentFilter() {
        val vm = newViewModel()
        vm.loadInternal(key, false, false, loader = { fresh })
        queryWorker.runNext()
        main.runNext()
        vm.loadInternal(key.copy(searchFoldersHash = "other"), false, false,
            loader = { throw IOException() })
        assertNull(vm.currentLoadState.items)
        queryWorker.runNext()
        main.runNext()
        assertNull(vm.currentLoadState.items)
        assertNotNull(vm.currentLoadState.error)
    }

    @Test fun cacheMissAndQueryFailureReachTerminalState() {
        val vm = newViewModel()
        vm.loadInternal(key, true, false, loader = { throw IOException() })
        cacheWorker.runNext()
        queryWorker.runNext()
        main.runNext()
        assertEquals(VideoBrowserLoadState.Phase.FAILED, vm.currentLoadState.phase)
        assertNull(vm.currentLoadState.items)
        assertFalse(vm.currentLoadState.isLoading)
    }

    @Test fun cacheArrivingBeforeFailureIsRetained() {
        val vm = newViewModel()
        cache.items = cached
        vm.loadInternal(key, true, false, loader = { throw IOException() })
        cacheWorker.runNext()
        main.runNext()
        assertEquals(cached, vm.currentLoadState.items)
        queryWorker.runNext()
        main.runNext()
        assertEquals(cached, vm.currentLoadState.items)
        assertEquals(VideoBrowserLoadState.Phase.FAILED, vm.currentLoadState.phase)
    }

    @Test fun lateCacheCannotReplaceFreshSuccess() {
        val vm = newViewModel()
        cache.items = cached
        vm.loadInternal(key, true, false, loader = { fresh })
        cacheWorker.runNext() // The cached result is waiting for main-thread delivery.
        queryWorker.runNext()
        main.runLast() // Deliver the fresh result first.
        main.runNext()
        assertEquals(fresh, vm.currentLoadState.items)
        assertEquals(VideoBrowserLoadState.Phase.READY, vm.currentLoadState.phase)
    }

    @Test fun cacheReadWriteAndPrefetchFailuresDoNotFailSuccessfulLoad() {
        val vm = newViewModel()
        cache.readError = IOException("cache read")
        cache.writeError = IOException("cache write")
        vm.loadInternal(key, true, false, loader = { fresh }, prefetch = { _, _ ->
            throw IOException("prefetch")
        })
        cacheWorker.runNext()
        queryWorker.runNext()
        main.runNext()
        cacheWorker.runNext()
        assertEquals(VideoBrowserLoadState(fresh, VideoBrowserLoadState.Phase.READY), vm.currentLoadState)
    }

    @Test fun staleFailureCannotStopNewRequest() {
        val vm = newViewModel()
        vm.loadInternal(key, false, false, loader = { throw IOException() })
        queryWorker.runNext()
        vm.loadInternal(key, false, true, loader = { fresh })
        main.runNext() // Deliver the old failure while the new request is pending.
        assertTrue(vm.currentLoadState.isRefreshing)
        assertNull(vm.currentLoadState.error)
        queryWorker.runNext()
        main.runNext()
        assertEquals(fresh, vm.currentLoadState.items)
    }

    @Test fun staleSuccessCannotReplaceNewerResult() {
        val vm = newViewModel()
        vm.loadInternal(key, false, false, loader = { cached })
        queryWorker.runNext()
        vm.loadInternal(key, false, false, loader = { fresh })
        queryWorker.runNext()
        main.runLast()
        main.runNext()
        assertEquals(fresh, vm.currentLoadState.items)
    }

    @Test fun navigationInvalidatesQueuedCacheAndQueryDeliveries() {
        val vm = newViewModel()
        cache.items = cached
        vm.loadInternal(key, true, false, loader = { fresh })
        cacheWorker.runNext()
        queryWorker.runNext()
        vm.setState(VideoBrowserState(currentMode = VideoMode.HIERARCHY, hierarchyPath = "Movies/"))
        main.runNext()
        main.runNext()
        assertEquals(VideoBrowserLoadState(), vm.currentLoadState)
    }

    @Test fun rejectedQueryStopsBothLoadingIndicators() {
        val vm = newViewModel()
        queryWorker.shutdown()
        vm.loadInternal(key, false, true, loader = { fresh })
        assertTrue(vm.currentLoadState.error is RejectedExecutionException)
        assertFalse(vm.currentLoadState.isLoading)
        assertFalse(vm.currentLoadState.isRefreshing)
    }

    @Test fun rejectedCacheWorkDoesNotPreventFreshResult() {
        val vm = newViewModel()
        cacheWorker.shutdown()
        vm.loadInternal(key, true, false, loader = { fresh })
        queryWorker.runNext()
        main.runNext()
        assertEquals(VideoBrowserLoadState(fresh, VideoBrowserLoadState.Phase.READY), vm.currentLoadState)
    }

    @Test fun cancellationDiscardsPendingFailureAndStopsRefreshing() {
        val vm = newViewModel()
        vm.loadInternal(key, false, true, loader = { throw IOException() })
        queryWorker.runNext()
        vm.cancelLoad()
        main.runNext()
        assertEquals(VideoBrowserLoadState(), vm.currentLoadState)
    }

    @Test fun clearShutsDownOwnedWorkersAndDiscardsPendingDelivery() {
        val vm = newViewModel()
        val store = ViewModelStore()
        store.put("browser", vm)
        vm.loadInternal(key, false, false, loader = { fresh })
        queryWorker.runNext()
        val beforeClear = vm.currentLoadState
        store.clear()
        main.runNext()
        assertEquals(beforeClear, vm.currentLoadState)
        assertTrue(queryWorker.isShutdown)
        assertTrue(cacheWorker.isShutdown)
        assertTrue(prefetchWorker.isShutdown)
    }

    private class FakeCache : VideoListCacheStore {
        var items: List<DisplayItem>? = null
        var readError: Exception? = null
        var writeError: Exception? = null
        override fun read(key: VideoListCache.Key): List<DisplayItem>? {
            readError?.let { throw it }
            return items
        }
        override fun write(key: VideoListCache.Key, items: List<DisplayItem>) {
            writeError?.let { throw it }
            this.items = items
        }
    }

    private class QueuedExecutor : AbstractExecutorService() {
        private val tasks = ArrayDeque<Runnable>()
        private var stopped = false
        fun runNext() = tasks.removeFirst().run()
        fun runLast() = tasks.removeLast().run()
        override fun execute(command: Runnable) {
            if (stopped) throw RejectedExecutionException()
            tasks.addLast(command)
        }
        override fun shutdown() { stopped = true }
        override fun shutdownNow(): MutableList<Runnable> {
            stopped = true
            return tasks.toMutableList().also { tasks.clear() }
        }
        override fun isShutdown() = stopped
        override fun isTerminated() = stopped && tasks.isEmpty()
        override fun awaitTermination(timeout: Long, unit: TimeUnit) = isTerminated
    }

    // These tests inject the loader at the common request boundary, avoiding Android I/O.
    private object UnusedRepository : VideoRepository {
        override fun load(
            mode: VideoMode, sortMode: VideoSortMode, sortOrder: VideoSortOrder,
            resolver: ContentResolver, nomediaEnabled: Boolean,
            searchFoldersUseAll: Boolean, searchFolders: Set<String>
        ): List<DisplayItem> = error("Unexpected repository access")

        override fun loadVideosInFolder(
            bucketId: String, sortMode: VideoSortMode, sortOrder: VideoSortOrder,
            resolver: ContentResolver, nomediaEnabled: Boolean,
            searchFoldersUseAll: Boolean, searchFolders: Set<String>
        ): List<DisplayItem> = error("Unexpected repository access")

        override fun loadHierarchy(
            path: String, sortMode: VideoSortMode, sortOrder: VideoSortOrder,
            resolver: ContentResolver, nomediaEnabled: Boolean,
            searchFoldersUseAll: Boolean, searchFolders: Set<String>
        ): List<DisplayItem> = error("Unexpected repository access")

        override fun searchVideos(
            query: String, sortMode: VideoSortMode, sortOrder: VideoSortOrder,
            resolver: ContentResolver, nomediaEnabled: Boolean,
            searchFoldersUseAll: Boolean, searchFolders: Set<String>, limit: Int?
        ): List<DisplayItem> = error("Unexpected repository access")
    }
}
