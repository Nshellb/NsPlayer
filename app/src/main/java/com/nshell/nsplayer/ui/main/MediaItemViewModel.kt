package com.nshell.nsplayer.ui.main

import android.app.Application
import android.content.IntentSender
import android.net.Uri
import android.os.CancellationSignal
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import com.nshell.nsplayer.data.repository.MediaItem
import com.nshell.nsplayer.data.repository.MediaItemDataSource
import com.nshell.nsplayer.data.repository.MediaItemMetadata
import com.nshell.nsplayer.data.repository.MediaItemRepository
import com.nshell.nsplayer.data.repository.RenameOutcome
import java.util.concurrent.Executors
import java.util.concurrent.Future

internal data class MediaItemRequest(val id: Long, val item: MediaItem, val rename: Boolean)

internal sealed class MediaItemState(open val request: MediaItemRequest) {
    data class Loading(override val request: MediaItemRequest) : MediaItemState(request)
    data class LoadFailed(override val request: MediaItemRequest) : MediaItemState(request)
    data class Properties(
        override val request: MediaItemRequest,
        val metadata: MediaItemMetadata
    ) : MediaItemState(request)
    data class RenameInput(
        override val request: MediaItemRequest,
        val originalName: String,
        var draftName: String = originalName
    ) : MediaItemState(request)
    data class Renaming(override val request: MediaItemRequest, val newName: String) : MediaItemState(request)
    data class PermissionRequired(
        override val request: MediaItemRequest,
        val newName: String,
        val intentSender: IntentSender
    ) : MediaItemState(request)
    data class FolderRequired(
        override val request: MediaItemRequest,
        val newName: String,
        val relativePath: String
    ) : MediaItemState(request)
    data class Finished(
        override val request: MediaItemRequest,
        val outcome: RenameOutcome
    ) : MediaItemState(request)
}

/** Provider work outlives a configuration change, but never retains an Activity or dialog. */
internal class MediaItemViewModel(
    application: Application,
    private val repository: MediaItemDataSource
) : AndroidViewModel(application) {
    constructor(application: Application) : this(application, MediaItemRepository(application))

    private val executor = Executors.newSingleThreadExecutor()
    private val mainHandler = Handler(Looper.getMainLooper())
    private val mutableState = MutableLiveData<MediaItemState?>(null)
    val state: LiveData<MediaItemState?> = mutableState
    val currentState: MediaItemState?
        get() = mutableState.value
    private var nextRequestId = 0L
    private var workSignal: CancellationSignal? = null
    private var workFuture: Future<*>? = null
    private var externalRequestId: Long? = null
    @Volatile private var cleared = false

    fun open(item: MediaItem, rename: Boolean) {
        if (cleared || isRenaming() || externalRequestId != null) return
        load(MediaItemRequest(++nextRequestId, item, rename))
    }

    fun retry(id: Long) {
        val current = currentState as? MediaItemState.LoadFailed ?: return
        if (current.request.id == id) load(current.request)
    }

    private fun load(request: MediaItemRequest) {
        mutableState.value = MediaItemState.Loading(request)
        submit(request, { MediaItemState.LoadFailed(request) }) { signal ->
            if (request.rename) {
                val name = if (request.item.type == MediaItem.Type.VIDEO) {
                    repository.loadMetadata(request.item, signal)?.displayName
                        ?.takeIf { it.isNotEmpty() } ?: request.item.title
                } else request.item.title
                MediaItemState.RenameInput(request, name)
            } else {
                repository.loadMetadata(request.item, signal)?.let { MediaItemState.Properties(request, it) }
                    ?: MediaItemState.LoadFailed(request)
            }
        }
    }

    fun updateDraft(id: Long, text: String) {
        val current = currentState as? MediaItemState.RenameInput ?: return
        if (current.request.id == id) current.draftName = text
    }

    fun rename(id: Long, newName: String) {
        val current = currentState as? MediaItemState.RenameInput ?: return
        if (current.request.id != id) return
        performRename(current.request, newName, allowPermissionRequest = true)
    }

    fun close(id: Long) {
        if (currentState?.request?.id != id || isRenaming()) return
        cancelWork()
        mutableState.value = null
    }

    fun claimExternalRequest(id: Long): Boolean {
        val current = currentState
        if (current?.request?.id != id || externalRequestId != null ||
            (current !is MediaItemState.PermissionRequired && current !is MediaItemState.FolderRequired)
        ) return false
        externalRequestId = id
        return true
    }

    fun onPermissionResult(granted: Boolean) {
        val id = externalRequestId ?: return
        externalRequestId = null
        val current = currentState as? MediaItemState.PermissionRequired ?: return
        if (current.request.id != id) return
        if (granted) {
            performRename(current.request, current.newName, allowPermissionRequest = false)
        } else {
            mutableState.value = MediaItemState.Finished(
                current.request, RenameOutcome.Failure(RenameOutcome.Reason.PERMISSION_DENIED)
            )
        }
    }

    fun onFolderPicked(uri: Uri?) {
        val id = externalRequestId ?: return
        externalRequestId = null
        val current = currentState as? MediaItemState.FolderRequired ?: return
        if (current.request.id != id) return
        if (uri == null) {
            mutableState.value = MediaItemState.Finished(
                current.request, RenameOutcome.Failure(RenameOutcome.Reason.PICK_CANCELLED)
            )
        } else {
            performRename(current.request, current.newName, false, uri, current.relativePath)
        }
    }

    fun consumeResult(result: MediaItemState.Finished): Boolean {
        if (currentState !== result) return false
        mutableState.value = null
        return true
    }

    private fun performRename(
        request: MediaItemRequest,
        newName: String,
        allowPermissionRequest: Boolean,
        tree: Uri? = null,
        folderPath: String? = null
    ) {
        mutableState.value = MediaItemState.Renaming(request, newName)
        submit(request, { MediaItemState.Finished(request, RenameOutcome.Failure()) }) { signal ->
            when (val outcome = repository.rename(
                request.item, newName, allowPermissionRequest, signal, tree, folderPath
            )) {
                is RenameOutcome.PermissionRequired ->
                    MediaItemState.PermissionRequired(request, newName, outcome.intentSender)
                is RenameOutcome.FolderRequired ->
                    MediaItemState.FolderRequired(request, newName, outcome.relativePath)
                else -> MediaItemState.Finished(request, outcome)
            }
        }
    }

    private fun isRenaming(): Boolean = when (currentState) {
        is MediaItemState.Renaming, is MediaItemState.PermissionRequired,
        is MediaItemState.FolderRequired, is MediaItemState.Finished -> true
        else -> false
    }

    private fun submit(
        request: MediaItemRequest,
        onFailure: () -> MediaItemState,
        task: (CancellationSignal) -> MediaItemState
    ) {
        cancelWork()
        val signal = CancellationSignal()
        workSignal = signal
        workFuture = executor.submit(Runnable {
            if (signal.isCanceled || cleared) return@Runnable
            val result = try {
                task(signal)
            } catch (e: Exception) {
                if (signal.isCanceled || cleared) return@Runnable
                Log.w("MediaItemViewModel", "Media item operation failed", e)
                onFailure()
            }
            if (signal.isCanceled || cleared) return@Runnable
            mainHandler.post {
                // A dismissed/replaced dialog must not receive an old query's result.
                if (!cleared && !signal.isCanceled && workSignal === signal &&
                    currentState?.request?.id == request.id
                ) {
                    workSignal = null
                    workFuture = null
                    mutableState.value = result
                }
            }
        })
    }

    private fun cancelWork() {
        workSignal?.cancel()
        workFuture?.cancel(true)
        workSignal = null
        workFuture = null
    }

    override fun onCleared() {
        cleared = true
        cancelWork()
        mainHandler.removeCallbacksAndMessages(null)
        executor.shutdownNow()
        super.onCleared()
    }
}
