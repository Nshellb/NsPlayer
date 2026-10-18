package com.nshell.nsplayer.ui.main

import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import com.nshell.nsplayer.data.repository.VideoQueryOptions
import java.util.concurrent.Executor
import java.util.concurrent.Future
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

/** Owns the search worker; all public actions and state delivery run on the main thread. */
class VideoSearchViewModel(
    private val savedState: SavedStateHandle,
    private val loader: VideoSearchLoader,
    private val worker: ScheduledExecutorService,
    private val mainExecutor: Executor
) : ViewModel() {
    private val state = MutableLiveData(restoreState())
    private val generation = AtomicLong()
    private var pending: Future<*>? = null
    private var options: VideoQueryOptions? = null
    private var permitted = false
    @Volatile private var cleared = false

    val currentState: VideoSearchState get() = requireNotNull(state.value)
    fun getState(): LiveData<VideoSearchState> = state

    fun configure(options: VideoQueryOptions, permitted: Boolean) {
        if (cleared || (this.options == options && this.permitted == permitted)) return
        invalidate()
        this.options = options.copy(folderIds = options.folderIds?.toSet())
        this.permitted = permitted
        // Results from another filter or a revoked permission cannot remain visible.
        publish(currentState.copy(results = null, previews = emptyList()))
        requestCurrent(debounce = false)
    }

    fun enter() {
        if (cleared || currentState.active) return
        invalidate()
        publish(VideoSearchState(active = true))
    }

    fun exit(): Boolean {
        if (cleared || !currentState.active) return false
        invalidate()
        publish(VideoSearchState())
        return true
    }

    fun changeInput(input: String) {
        if (cleared || !currentState.active || currentState.input == input) return
        invalidate()
        publish(VideoSearchState(active = true, input = input))
        requestCurrent(debounce = true)
    }

    fun submit(input: String = currentState.input) {
        if (cleared || !currentState.active) return
        invalidate()
        val query = input.trim()
        publish(VideoSearchState(
            active = true,
            input = query,
            committedQuery = query.takeIf { it.isNotEmpty() }
        ))
        requestCurrent(debounce = false)
    }

    fun refresh() {
        if (!cleared) requestCurrent(debounce = false)
    }

    private fun requestCurrent(debounce: Boolean) {
        val requestId = invalidate()
        val snapshot = currentState
        val query = snapshot.committedQuery ?: snapshot.input.trim()
        val queryOptions = options
        if (!snapshot.active || query.isEmpty() || queryOptions == null) {
            publish(snapshot.copy(loading = false, error = null))
            return
        }
        if (!permitted) {
            publish(snapshot.copy(loading = false, error = SecurityException("Video permission required")))
            return
        }
        publish(snapshot.copy(loading = true, error = null, previews = emptyList()))
        try {
            pending = worker.schedule(Runnable {
                if (!isCurrent(requestId)) return@Runnable
                val result = try {
                    loader.search(query, queryOptions, if (snapshot.showingResults) null else PREVIEW_LIMIT)
                } catch (error: Exception) {
                    mainExecutor.execute {
                        if (isCurrent(requestId)) publish(currentState.copy(loading = false, error = error))
                    }
                    return@Runnable
                }
                mainExecutor.execute {
                    if (isCurrent(requestId)) {
                        publish(currentState.copy(
                            results = if (snapshot.showingResults) result else null,
                            previews = if (snapshot.showingResults) emptyList() else result,
                            loading = false,
                            error = null
                        ))
                    }
                }
            }, if (debounce) PREVIEW_DELAY_MS else 0L, TimeUnit.MILLISECONDS)
        } catch (error: RejectedExecutionException) {
            if (isCurrent(requestId)) publish(currentState.copy(loading = false, error = error))
        }
    }

    private fun invalidate(): Long {
        val next = generation.incrementAndGet()
        pending?.cancel(false)
        pending = null
        return next
    }

    private fun isCurrent(requestId: Long) = !cleared && generation.get() == requestId

    private fun publish(value: VideoSearchState) {
        savedState[KEY_ACTIVE] = value.active
        savedState[KEY_INPUT] = value.input
        savedState[KEY_COMMITTED] = value.committedQuery
        if (state.value != value) state.value = value
    }

    private fun restoreState(): VideoSearchState {
        if (savedState.get<Boolean>(KEY_ACTIVE) != true) return VideoSearchState()
        val input = savedState.get<String>(KEY_INPUT).orEmpty()
        val committed = savedState.get<String>(KEY_COMMITTED)
            ?.takeIf { it.isNotBlank() && it == input.trim() }
        return VideoSearchState(active = true, input = input, committedQuery = committed)
    }

    override fun onCleared() {
        cleared = true
        invalidate()
        worker.shutdownNow()
        super.onCleared()
    }

    private companion object {
        const val KEY_ACTIVE = "search_active"
        const val KEY_INPUT = "search_input"
        const val KEY_COMMITTED = "search_committed"
        const val PREVIEW_DELAY_MS = 180L
        const val PREVIEW_LIMIT = 20
    }
}
