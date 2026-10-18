package com.nshell.nsplayer.ui.main

import android.app.Application
import android.net.Uri
import android.os.CancellationSignal
import android.os.Looper
import androidx.lifecycle.Observer
import androidx.lifecycle.ViewModelStore
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.nshell.nsplayer.data.repository.MediaItem
import com.nshell.nsplayer.data.repository.MediaItemDataSource
import com.nshell.nsplayer.data.repository.MediaItemMetadata
import com.nshell.nsplayer.data.repository.RenameOutcome
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

@RunWith(AndroidJUnit4::class)
class MediaItemViewModelTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val item = MediaItem(MediaItem.Type.VIDEO, "video.mp4", "content://media/video/1", null)
    private val metadata = MediaItemMetadata("video.mp4", "Movies/", 1024L, 100L, "external_primary")

    @Test
    fun slowMetadataQueryShowsLoadingWithoutBlockingTheMainThread() {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val queriedOnMain = AtomicBoolean(true)
        val source = FakeSource(load = { _, _ ->
            queriedOnMain.set(Looper.myLooper() == Looper.getMainLooper())
            entered.countDown()
            check(release.await(5, TimeUnit.SECONDS))
            metadata
        })
        withViewModel(source) { model ->
            try {
                onMain {
                    model.open(item, rename = false)
                    assertTrue(model.currentState is MediaItemState.Loading)
                }
                assertTrue(entered.await(5, TimeUnit.SECONDS))
                assertFalse(queriedOnMain.get())
                release.countDown()
                val loaded = awaitState(model) { it is MediaItemState.Properties } as MediaItemState.Properties
                assertEquals(metadata, loaded.metadata)
            } finally {
                release.countDown()
            }
        }
    }

    @Test
    fun closingAQueryDiscardsItsLateResultBeforeOpeningAnotherItem() {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val firstSignal = AtomicReference<CancellationSignal>()
        val nextItem = item.copy(title = "next.mp4", contentUri = "content://media/video/2")
        val source = FakeSource(load = { requested, signal ->
            if (requested == item) {
                firstSignal.set(signal)
                entered.countDown()
                // Model a provider that finishes even after cancellation/interruption.
                var released = false
                while (!released) {
                    try {
                        released = release.await(5, TimeUnit.SECONDS)
                        check(released)
                    } catch (_: InterruptedException) {
                    }
                }
            }
            metadata.copy(displayName = requested.title)
        })
        withViewModel(source) { model ->
            val staleResult = AtomicBoolean(false)
            val observer = Observer<MediaItemState?> { state ->
                if (state is MediaItemState.Properties && state.request.item == item) staleResult.set(true)
            }
            try {
                onMain { model.open(item, rename = false) }
                assertTrue(entered.await(5, TimeUnit.SECONDS))
                onMain {
                    model.close(requireNotNull(model.currentState).request.id)
                    model.state.observeForever(observer)
                    model.open(nextItem, rename = false)
                }
                assertTrue(firstSignal.get().isCanceled)
                release.countDown()
                val loaded = awaitState(model) { it is MediaItemState.Properties } as MediaItemState.Properties
                assertEquals(nextItem, loaded.request.item)
                assertFalse(staleResult.get())
            } finally {
                release.countDown()
                onMain { model.state.removeObserver(observer) }
            }
        }
    }

    @Test
    fun queryFailureLeavesLoadingAndCanBeRetried() {
        val calls = AtomicInteger()
        val source = FakeSource(load = { _, _ ->
            if (calls.incrementAndGet() == 1) throw SecurityException("Unavailable provider")
            metadata
        })
        withViewModel(source) { model ->
            onMain { model.open(item, rename = false) }
            val failed = awaitState(model) { it is MediaItemState.LoadFailed }
            onMain { model.retry(failed.request.id) }
            awaitState(model) { it is MediaItemState.Properties }
            assertEquals(2, calls.get())
        }
    }

    @Test
    fun renameRunsOffTheMainThreadAndIgnoresDuplicateConfirmation() {
        val writes = AtomicInteger()
        val renamedOnMain = AtomicBoolean(true)
        val source = FakeSource(load = { _, _ -> metadata }, write = {
            renamedOnMain.set(Looper.myLooper() == Looper.getMainLooper())
            writes.incrementAndGet()
            RenameOutcome.Success
        })
        withViewModel(source) { model ->
            onMain { model.open(item, rename = true) }
            val ready = awaitState(model) { it is MediaItemState.RenameInput }
            onMain {
                model.rename(ready.request.id, "renamed.mp4")
                model.rename(ready.request.id, "renamed.mp4")
            }
            val result = awaitState(model) { it is MediaItemState.Finished } as MediaItemState.Finished
            assertEquals(RenameOutcome.Success, result.outcome)
            assertEquals(1, writes.get())
            assertFalse(renamedOnMain.get())
        }
    }

    private class FakeSource(
        val load: (MediaItem, CancellationSignal) -> MediaItemMetadata?,
        val write: () -> RenameOutcome = { RenameOutcome.Success }
    ) : MediaItemDataSource {
        override fun loadMetadata(item: MediaItem, signal: CancellationSignal) = load(item, signal)

        override fun rename(
            item: MediaItem,
            newName: String,
            allowPermissionRequest: Boolean,
            signal: CancellationSignal,
            selectedTree: Uri?,
            folderPath: String?
        ) = write()
    }

    private fun withViewModel(source: MediaItemDataSource, block: (MediaItemViewModel) -> Unit) {
        val store = ViewModelStore()
        lateinit var model: MediaItemViewModel
        onMain {
            model = MediaItemViewModel(instrumentation.targetContext.applicationContext as Application, source)
            store.put("media-item", model)
        }
        try {
            block(model)
        } finally {
            onMain { store.clear() }
        }
    }

    private fun awaitState(model: MediaItemViewModel, predicate: (MediaItemState) -> Boolean): MediaItemState {
        val delivered = CountDownLatch(1)
        val result = AtomicReference<MediaItemState>()
        val observer = Observer<MediaItemState?> { state ->
            if (state != null && predicate(state)) {
                result.set(state)
                delivered.countDown()
            }
        }
        onMain { model.state.observeForever(observer) }
        return try {
            assertTrue("Expected media item state was not delivered", delivered.await(5, TimeUnit.SECONDS))
            requireNotNull(result.get())
        } finally {
            onMain { model.state.removeObserver(observer) }
        }
    }

    private fun onMain(action: () -> Unit) = instrumentation.runOnMainSync { action() }
}
