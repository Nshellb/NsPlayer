package com.nshell.nsplayer.ui.main

import androidx.arch.core.executor.testing.InstantTaskExecutorRule
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModelStore
import com.nshell.nsplayer.data.repository.VideoQueryOptions
import com.nshell.nsplayer.data.repository.VideoSortDirection
import com.nshell.nsplayer.data.repository.VideoSortField
import java.io.IOException
import java.util.ArrayDeque
import java.util.concurrent.Delayed
import java.util.concurrent.Executor
import java.util.concurrent.FutureTask
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.TimeUnit
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class VideoSearchViewModelTest {
    @get:Rule val instantTasks = InstantTaskExecutorRule()

    private val worker = ManualWorker()
    private val deliveries = ArrayDeque<Runnable>()
    private val main = Executor { deliveries.addLast(it) }
    private val options = VideoQueryOptions(
        VideoSortField.TITLE,
        VideoSortDirection.ASCENDING,
        respectNoMedia = false,
        folderIds = null
    )
    private val calls = mutableListOf<Triple<String, VideoQueryOptions, Int?>>()
    private var failure: Exception? = null
    private val loader = VideoSearchLoader { query, filters, limit ->
        calls.add(Triple(query, filters, limit))
        failure?.let { throw it }
        listOf(DisplayItem(DisplayItem.Type.VIDEO, query, null, 0))
    }

    private fun create(handle: SavedStateHandle = SavedStateHandle()) =
        VideoSearchViewModel(handle, loader, worker, main)

    private fun active(): VideoSearchViewModel = create().also {
        it.configure(options, true)
        it.enter()
    }

    private fun finish() {
        worker.runAll()
        while (deliveries.isNotEmpty()) deliveries.removeFirst().run()
    }

    @Test fun rapidTypingDebouncesAndOnlyQueriesLatestPreview() {
        val vm = active()
        vm.changeInput("ca")
        vm.changeInput("cat")
        assertTrue(calls.isEmpty())
        assertEquals(180L, worker.lastDelayMs)
        finish()
        assertEquals(listOf(Triple("cat", options, 20)), calls)
        assertEquals("cat", vm.currentState.previews.single().title)
        assertFalse(vm.currentState.showingResults)
    }

    @Test fun submitCancelsPreviewAndRunsUnlimitedSearchImmediately() {
        val vm = active()
        vm.changeInput("preview")
        vm.submit("  selected  ")
        assertEquals(0L, worker.lastDelayMs)
        finish()
        assertEquals(listOf(Triple("selected", options, null)), calls)
        assertEquals("selected", vm.currentState.input)
        assertTrue(vm.currentState.showingResults)
        assertEquals("selected", vm.currentState.results!!.single().title)
        assertTrue(vm.currentState.previews.isEmpty())
    }

    @Test fun latePreviewCannotReplaceCommittedResult() {
        val vm = active()
        vm.changeInput("old")
        worker.runAll() // Leave the preview waiting on the main thread.
        vm.submit("new")
        finish()
        assertEquals("new", vm.currentState.results!!.single().title)
        assertTrue(vm.currentState.previews.isEmpty())
    }

    @Test fun editingCommittedQueryDiscardsLateResults() {
        val vm = active()
        vm.submit("old")
        worker.runAll()
        vm.changeInput("new")
        deliveries.removeFirst().run()
        assertFalse(vm.currentState.showingResults)
        assertNull(vm.currentState.results)
        finish()
        assertEquals("new", vm.currentState.previews.single().title)
    }

    @Test fun clearingInputInvalidatesQueuedFailure() {
        val vm = active()
        failure = IOException()
        vm.submit("old")
        worker.runAll()
        vm.changeInput("")
        deliveries.removeFirst().run()
        assertEquals(VideoSearchState(active = true), vm.currentState)
    }

    @Test fun clearingInputBeforeDebounceDoesNotQueryRepository() {
        val vm = active()
        vm.changeInput("draft")
        vm.changeInput("")
        finish()
        assertTrue(calls.isEmpty())
        assertEquals(VideoSearchState(active = true), vm.currentState)
    }

    @Test fun emptySubmissionReturnsToEditingWithoutQuerying() {
        val vm = active()
        vm.submit("query")
        worker.runAll()
        vm.submit("  ")
        finish()
        assertEquals(1, calls.size)
        assertEquals(VideoSearchState(active = true), vm.currentState)
    }

    @Test fun exitDiscardsPendingSuccessAndClearsSavedSearch() {
        val handle = SavedStateHandle()
        val vm = create(handle)
        vm.configure(options, true)
        vm.enter()
        vm.submit("query")
        worker.runAll()
        assertTrue(vm.exit())
        finish()
        assertEquals(VideoSearchState(), vm.currentState)
        assertFalse(vm.exit())
        assertEquals(VideoSearchState(), create(copyHandle(handle)).currentState)
    }

    @Test fun failureStopsLoadingAndRefreshRetriesSameQuery() {
        val vm = active()
        val error = IOException()
        failure = error
        vm.submit("query")
        finish()
        assertFalse(vm.currentState.loading)
        assertSame(error, vm.currentState.error)
        failure = null
        vm.refresh()
        finish()
        assertNull(vm.currentState.error)
        assertEquals("query", vm.currentState.results!!.single().title)
    }

    @Test fun restorationRequeriesCommittedSearchAfterConfiguration() {
        val handle = SavedStateHandle()
        val vm = create(handle)
        vm.configure(options, true)
        vm.enter()
        vm.submit("saved")
        finish()
        val restored = create(copyHandle(handle))
        assertEquals("saved", restored.currentState.input)
        assertTrue(restored.currentState.showingResults)
        assertNull(restored.currentState.results)
        restored.configure(options, true)
        finish()
        assertEquals("saved", restored.currentState.results!!.single().title)
    }

    @Test fun failedRefreshRetainsPreviousResults() {
        val vm = active()
        vm.submit("query")
        finish()
        val original = vm.currentState.results
        failure = IOException()
        vm.refresh()
        finish()
        assertSame(original, vm.currentState.results)
        assertFalse(vm.currentState.loading)
        assertNotNull(vm.currentState.error)
    }

    @Test fun restorationPreservesUncommittedInputAndLoadsPreviewOnly() {
        val handle = SavedStateHandle()
        val vm = create(handle)
        vm.enter()
        vm.changeInput("draft ")
        val restored = create(copyHandle(handle))
        restored.configure(options, true)
        finish()
        assertEquals("draft ", restored.currentState.input)
        assertFalse(restored.currentState.showingResults)
        assertEquals(listOf(Triple("draft", options, 20)), calls)
    }

    @Test fun sameConfigurationAndRestoredViewTextDoNotRepeatCompletedSearch() {
        val vm = active()
        vm.submit("query")
        finish()
        vm.configure(options.copy(), true)
        vm.changeInput("query")
        finish()
        assertEquals(1, calls.size)
        assertTrue(vm.currentState.showingResults)
    }

    @Test fun changedFiltersInvalidateOldDeliveriesAndRequery() {
        val vm = active()
        vm.submit("query")
        worker.runAll()
        val filtered = options.copy(folderIds = setOf("Movies/"))
        vm.configure(filtered, true)
        deliveries.removeFirst().run()
        assertNull(vm.currentState.results)
        assertTrue(vm.currentState.loading)
        finish()
        assertEquals(filtered, calls.last().second)
        assertNotNull(vm.currentState.results)
    }

    @Test fun permissionRevocationInvalidatesResultsAndGrantResumesQuery() {
        val vm = active()
        vm.submit("query")
        worker.runAll()
        vm.configure(options, false)
        finish()
        assertNull(vm.currentState.results)
        assertFalse(vm.currentState.loading)
        assertTrue(vm.currentState.error is SecurityException)
        vm.configure(options, true)
        finish()
        assertNotNull(vm.currentState.results)
        assertNull(vm.currentState.error)
    }

    @Test fun rejectedRequestIsReportedWithoutLeavingLoadingActive() {
        val vm = active()
        worker.shutdownNow()
        vm.submit("query")
        assertTrue(vm.currentState.error is RejectedExecutionException)
        assertFalse(vm.currentState.loading)
    }

    @Test fun clearingViewModelStopsWorkerAndIgnoresPendingDelivery() {
        val vm = active()
        vm.submit("query")
        worker.runAll()
        val before = vm.currentState
        val store = ViewModelStore()
        store.put("search", vm)
        store.clear()
        finish()
        assertTrue(worker.isShutdown)
        assertEquals(before, vm.currentState)
    }

    private fun copyHandle(handle: SavedStateHandle) = SavedStateHandle(
        handle.keys().associateWith { handle.get<Any?>(it) }
    )

    // No real threads or sleeps: tests control both worker and UI delivery order.
    private class ManualWorker : ScheduledThreadPoolExecutor(1) {
        private val tasks = ArrayDeque<ManualTask>()
        var lastDelayMs = -1L
        override fun schedule(command: Runnable, delay: Long, unit: TimeUnit): ScheduledFuture<*> {
            if (isShutdown) throw RejectedExecutionException()
            lastDelayMs = unit.toMillis(delay)
            return ManualTask(command).also { tasks.addLast(it) }
        }
        fun runAll() {
            while (tasks.isNotEmpty()) tasks.removeFirst().run()
        }
        override fun shutdownNow(): MutableList<Runnable> {
            tasks.forEach { it.cancel(false) }
            tasks.clear()
            return super.shutdownNow()
        }
    }

    private class ManualTask(command: Runnable) : FutureTask<Unit>(command, Unit), ScheduledFuture<Unit> {
        override fun getDelay(unit: TimeUnit) = 0L
        override fun compareTo(other: Delayed) = 0
    }
}
