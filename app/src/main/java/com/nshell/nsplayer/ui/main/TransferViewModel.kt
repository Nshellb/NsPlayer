package com.nshell.nsplayer.ui.main

import android.app.Application
import android.content.Context
import android.content.IntentSender
import android.net.Uri
import android.os.Handler
import android.os.Looper
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import com.nshell.nsplayer.data.transfer.ConflictDecision
import com.nshell.nsplayer.data.transfer.TransferCancellation
import com.nshell.nsplayer.data.transfer.TransferEngine
import com.nshell.nsplayer.data.transfer.TransferFile
import com.nshell.nsplayer.data.transfer.TransferOperation
import com.nshell.nsplayer.data.transfer.TransferProgress
import com.nshell.nsplayer.data.transfer.TransferReply
import com.nshell.nsplayer.data.transfer.TransferResult
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicLong

internal sealed class TransferPrompt(val id: Long) {
    class Destination(id: Long, val initialUri: Uri?) : TransferPrompt(id)
    class ConfirmDelete(id: Long, val count: Int) : TransferPrompt(id)
    class DeletePermission(
        id: Long,
        val intentSender: IntentSender,
        val reply: TransferReply<Boolean>
    ) : TransferPrompt(id)
    class Conflict(
        id: Long,
        val fileName: String,
        val reply: TransferReply<ConflictDecision>,
        var applyAll: Boolean = false
    ) : TransferPrompt(id)
}

internal data class TransferUiState(
    val operation: TransferOperation? = null,
    val progress: TransferProgress? = null,
    val prompt: TransferPrompt? = null,
    val cancelling: Boolean = false,
    val result: TransferResult? = null
)

/**
 * Retains the operation across Activity recreation; only application context reaches the worker.
 * Operations are in-process only and are cancelled when this ViewModel is cleared.
 */
internal class TransferViewModel(application: Application) : AndroidViewModel(application) {
    private class Run(val operation: TransferOperation, val items: List<TransferFile>) {
        val cancellation = TransferCancellation()
        var executing = false
    }

    private val mutableState = MutableLiveData(TransferUiState())
    val state: LiveData<TransferUiState> = mutableState
    val currentState: TransferUiState
        get() = requireNotNull(mutableState.value)
    private val mainHandler = Handler(Looper.getMainLooper())
    private val executor = Executors.newSingleThreadExecutor()
    private val promptIds = AtomicLong()
    private var activeRun: Run? = null
    private var destinationInFlight: Long? = null
    private var permissionInFlight: Long? = null
    @Volatile private var cleared = false

    fun begin(operation: TransferOperation, items: List<TransferFile>) {
        if (cleared || activeRun != null || currentState.result != null ||
            destinationInFlight != null || permissionInFlight != null || items.isEmpty()
        ) return
        val run = Run(operation, items.toList())
        activeRun = run
        if (operation == TransferOperation.DELETE) {
            mutableState.value = TransferUiState(
                operation = operation,
                prompt = TransferPrompt.ConfirmDelete(promptIds.incrementAndGet(), items.size)
            )
        } else {
            mutableState.value = TransferUiState(operation, TransferProgress(items.size))
            executor.execute {
                val initialUri = runCatching { engine(run).buildInitialCopyTreeUri(run.items) }.getOrNull()
                post(run) {
                    mutableState.value = currentState.copy(
                        progress = null,
                        prompt = TransferPrompt.Destination(promptIds.incrementAndGet(), initialUri)
                    )
                }
            }
        }
    }

    fun confirmDelete(id: Long, confirmed: Boolean) {
        val prompt = currentState.prompt as? TransferPrompt.ConfirmDelete ?: return
        val run = activeRun ?: return
        if (prompt.id != id) return
        if (confirmed) execute(run, null) else cancel()
    }

    // Claim before launching: a new Activity must not launch a second system dialog.
    fun claimDestination(id: Long): Boolean {
        if (currentState.prompt !is TransferPrompt.Destination || currentState.prompt?.id != id ||
            destinationInFlight != null
        ) return false
        destinationInFlight = id
        return true
    }

    fun onDestinationPicked(uri: Uri?) {
        val id = destinationInFlight ?: return
        destinationInFlight = null
        val prompt = currentState.prompt as? TransferPrompt.Destination ?: return
        val run = activeRun ?: return
        if (prompt.id != id) return
        if (uri == null) {
            cancel()
            return
        }
        getApplication<Application>().getSharedPreferences("nsplayer_prefs", Context.MODE_PRIVATE)
            .edit().putString("copy_tree_uri", uri.toString()).apply()
        execute(run, uri)
    }

    fun claimDeletePermission(id: Long): Boolean {
        if (currentState.prompt !is TransferPrompt.DeletePermission || currentState.prompt?.id != id ||
            permissionInFlight != null
        ) return false
        permissionInFlight = id
        return true
    }

    fun onDeletePermissionResult(granted: Boolean) {
        val id = permissionInFlight ?: return
        permissionInFlight = null
        val prompt = currentState.prompt as? TransferPrompt.DeletePermission ?: return
        if (prompt.id != id) return
        mutableState.value = currentState.copy(prompt = null)
        prompt.reply.complete(granted)
    }

    fun answerConflict(id: Long, decision: ConflictDecision) {
        val prompt = currentState.prompt as? TransferPrompt.Conflict ?: return
        if (prompt.id != id) return
        mutableState.value = currentState.copy(prompt = null)
        prompt.reply.complete(decision)
    }

    fun setApplyAll(id: Long, value: Boolean) {
        val prompt = currentState.prompt as? TransferPrompt.Conflict ?: return
        if (prompt.id == id) prompt.applyAll = value
    }

    fun cancel() {
        val run = activeRun ?: return
        run.cancellation.cancel()
        if (run.executing) {
            mutableState.value = currentState.copy(prompt = null, cancelling = true)
        } else {
            activeRun = null
            mutableState.value = TransferUiState()
        }
    }

    fun consumeResult(result: TransferResult): Boolean {
        if (currentState.result !== result) return false
        mutableState.value = TransferUiState()
        return true
    }

    private fun execute(run: Run, destination: Uri?) {
        if (run.executing || run.cancellation.isCancelled) return
        run.executing = true
        mutableState.value = TransferUiState(run.operation, TransferProgress(run.items.size))
        executor.execute {
            val result = engine(run).execute(run.operation, run.items, destination) { progress ->
                post(run) { mutableState.value = currentState.copy(progress = progress) }
            }
            // Cancellation still needs a terminal state to close the progress dialog.
            post(run, allowCancelled = true) {
                activeRun = null
                mutableState.value = TransferUiState(result = result)
            }
        }
    }

    private fun engine(run: Run) = TransferEngine(
        getApplication<Application>(),
        run.cancellation,
        requestConflict = { fileName ->
            run.cancellation.awaitReply<ConflictDecision> { reply ->
                post(run) {
                    mutableState.value = currentState.copy(
                        prompt = TransferPrompt.Conflict(promptIds.incrementAndGet(), fileName, reply)
                    )
                }
            }
        },
        requestDeletePermission = { intentSender ->
            run.cancellation.awaitReply<Boolean> { reply ->
                post(run) {
                    mutableState.value = currentState.copy(
                        prompt = TransferPrompt.DeletePermission(promptIds.incrementAndGet(), intentSender, reply)
                    )
                }
            } == true
        }
    )

    private fun post(run: Run, allowCancelled: Boolean = false, action: () -> Unit) {
        if (cleared) return
        mainHandler.post {
            if (!cleared && activeRun === run && (allowCancelled || !run.cancellation.isCancelled)) {
                action()
            }
        }
    }

    override fun onCleared() {
        cleared = true
        activeRun?.cancellation?.cancel()
        activeRun = null
        mainHandler.removeCallbacksAndMessages(null)
        executor.shutdownNow()
        super.onCleared()
    }
}
