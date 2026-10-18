package com.nshell.nsplayer.ui.main

import android.app.Activity
import android.net.Uri
import android.text.format.Formatter
import android.widget.Button
import android.widget.CheckBox
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.IntentSenderRequest
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ViewModelProvider
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.nshell.nsplayer.R
import com.nshell.nsplayer.data.transfer.ConflictChoice
import com.nshell.nsplayer.data.transfer.ConflictDecision
import com.nshell.nsplayer.data.transfer.TransferFile
import com.nshell.nsplayer.data.transfer.TransferOperation
import com.nshell.nsplayer.data.transfer.TransferProgress
import com.nshell.nsplayer.data.transfer.TransferResult

/** Activity-bound rendering only. TransferViewModel owns the operation and all pending responses. */
class TransferController(
    private val activity: AppCompatActivity,
    private val adapter: VideoListAdapter,
    private val launchCopyDestination: (Uri?) -> Unit,
    private val launchDeletePermission: (IntentSenderRequest) -> Unit,
    private val onReloadRequested: () -> Unit
) : DefaultLifecycleObserver {
    private val viewModel = ViewModelProvider(activity)[TransferViewModel::class.java]
    private var progressUi: ProgressUi? = null
    private var promptDialog: AlertDialog? = null
    private var displayedPromptId: Long? = null

    init {
        activity.lifecycle.addObserver(this)
        viewModel.state.observe(activity) { render() }
    }

    fun startCopySelected() = startSelected(TransferOperation.COPY)

    fun startMoveSelected() = startSelected(TransferOperation.MOVE)

    fun startDeleteSelected() = startSelected(TransferOperation.DELETE)

    fun onCopyDestinationPicked(uri: Uri?) = viewModel.onDestinationPicked(uri)

    fun onDeletePermissionResult(resultCode: Int) =
        viewModel.onDeletePermissionResult(resultCode == Activity.RESULT_OK)

    override fun onResume(owner: LifecycleOwner) {
        render()
    }

    override fun onDestroy(owner: LifecycleOwner) {
        // Dismissing an old screen must not answer or cancel the retained operation.
        dismissPrompt()
        progressUi?.dialog?.dismiss()
        progressUi = null
        owner.lifecycle.removeObserver(this)
    }

    private fun startSelected(operation: TransferOperation) {
        val files = adapter.getSelectedItems().mapNotNull { item ->
            val uri = item.contentUri?.takeIf { it.isNotEmpty() }
            if (item.type == DisplayItem.Type.VIDEO && uri != null) TransferFile(uri, item.title) else null
        }
        if (files.isEmpty()) {
            val message = if (operation == TransferOperation.DELETE) R.string.delete_no_selection
                else R.string.copy_no_selection
            Toast.makeText(activity, message, Toast.LENGTH_SHORT).show()
            return
        }
        viewModel.begin(operation, files)
    }

    private fun render() {
        if (!activity.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED) ||
            activity.isFinishing || activity.isDestroyed
        ) return
        val state = viewModel.currentState
        if (displayedPromptId != state.prompt?.id) dismissPrompt()
        if (state.progress != null && state.operation != null) {
            val ui = progressUi ?: showProgress(state.operation).also { progressUi = it }
            updateProgress(ui, state.progress, state.cancelling)
        } else {
            progressUi?.dialog?.dismiss()
            progressUi = null
        }

        when (val prompt = state.prompt) {
            is TransferPrompt.Destination -> {
                if (viewModel.claimDestination(prompt.id)) {
                    try {
                        launchCopyDestination(prompt.initialUri)
                    } catch (_: RuntimeException) {
                        viewModel.onDestinationPicked(null)
                        Toast.makeText(activity, R.string.copy_failed, Toast.LENGTH_SHORT).show()
                    }
                }
            }
            is TransferPrompt.DeletePermission -> {
                if (viewModel.claimDeletePermission(prompt.id)) {
                    try {
                        launchDeletePermission(IntentSenderRequest.Builder(prompt.intentSender).build())
                    } catch (_: RuntimeException) {
                        viewModel.onDeletePermissionResult(false)
                    }
                }
            }
            is TransferPrompt.ConfirmDelete -> if (displayedPromptId != prompt.id) showDeleteConfirmation(prompt)
            is TransferPrompt.Conflict -> if (displayedPromptId != prompt.id) showConflict(prompt)
            null -> Unit
        }

        state.result?.let { result ->
            if (viewModel.consumeResult(result)) {
                Toast.makeText(activity, resultMessage(result), Toast.LENGTH_SHORT).show()
                adapter.clearSelection()
                onReloadRequested()
            }
        }
    }

    private fun showDeleteConfirmation(prompt: TransferPrompt.ConfirmDelete) {
        displayedPromptId = prompt.id
        promptDialog = MaterialAlertDialogBuilder(activity)
            .setTitle(R.string.delete_title)
            .setMessage(activity.getString(R.string.delete_message, prompt.count))
            .setPositiveButton(R.string.delete_confirm) { _, _ -> viewModel.confirmDelete(prompt.id, true) }
            .setNegativeButton(R.string.delete_cancel) { _, _ -> viewModel.confirmDelete(prompt.id, false) }
            .setOnCancelListener { viewModel.confirmDelete(prompt.id, false) }
            .show()
    }

    private fun showConflict(prompt: TransferPrompt.Conflict) {
        displayedPromptId = prompt.id
        val content = activity.layoutInflater.inflate(R.layout.dialog_conflict_resolution, null)
        val applyAll = content.findViewById<CheckBox>(R.id.applyToAllCheck)
        applyAll.isChecked = prompt.applyAll
        applyAll.setOnCheckedChangeListener { _, checked -> viewModel.setApplyAll(prompt.id, checked) }
        fun answer(choice: ConflictChoice) {
            viewModel.answerConflict(prompt.id, ConflictDecision(choice, applyAll.isChecked))
        }
        promptDialog = MaterialAlertDialogBuilder(activity)
            .setTitle(R.string.conflict_title)
            .setMessage(activity.getString(R.string.conflict_message, prompt.fileName))
            .setView(content)
            .setPositiveButton(R.string.conflict_overwrite) { _, _ -> answer(ConflictChoice.OVERWRITE) }
            .setNeutralButton(R.string.conflict_rename) { _, _ -> answer(ConflictChoice.RENAME) }
            .setNegativeButton(R.string.conflict_skip) { _, _ -> answer(ConflictChoice.SKIP) }
            .setOnCancelListener { viewModel.cancel() }
            .show().also { it.setCanceledOnTouchOutside(false) }
    }

    private fun dismissPrompt() {
        promptDialog?.dismiss()
        promptDialog = null
        displayedPromptId = null
    }

    private data class ProgressUi(
        val dialog: BottomSheetDialog,
        val progressBar: ProgressBar,
        val countText: TextView,
        val sizeText: TextView,
        val percentText: TextView,
        val fileText: TextView,
        val cancelButton: Button
    )

    private fun showProgress(operation: TransferOperation): ProgressUi {
        val content = activity.layoutInflater.inflate(R.layout.bottom_sheet_copy_progress, null)
        content.findViewById<TextView>(R.id.copyProgressTitle).setText(when (operation) {
            TransferOperation.MOVE -> R.string.move_progress_title
            TransferOperation.DELETE -> R.string.delete_progress_title
            TransferOperation.COPY -> R.string.copy_progress_title
        })
        val dialog = BottomSheetDialog(activity)
        dialog.setContentView(content)
        dialog.setCancelable(false)
        val ui = ProgressUi(
            dialog,
            content.findViewById(R.id.copyProgressBar),
            content.findViewById(R.id.copyProgressCount),
            content.findViewById(R.id.copyProgressSize),
            content.findViewById(R.id.copyProgressPercent),
            content.findViewById(R.id.copyProgressFile),
            content.findViewById(R.id.copyProgressCancel)
        )
        ui.cancelButton.setOnClickListener { viewModel.cancel() }
        dialog.show()
        return ui
    }

    private fun updateProgress(ui: ProgressUi, progress: TransferProgress, cancelling: Boolean) {
        val fraction = if (progress.totalBytes > 0) {
            (progress.copiedBytes.toDouble() / progress.totalBytes).coerceIn(0.0, 1.0)
        } else 0.0
        ui.progressBar.isIndeterminate = progress.totalBytes <= 0L
        ui.progressBar.max = 1000
        ui.progressBar.progress = (fraction * 1000).toInt()
        ui.countText.text = activity.getString(
            R.string.copy_progress_count,
            progress.completed.coerceAtMost(progress.total), progress.total,
            progress.successCount, progress.failCount, progress.skipCount
        )
        ui.sizeText.text = activity.getString(
            R.string.copy_progress_size,
            Formatter.formatFileSize(activity, progress.copiedBytes.coerceAtLeast(0L)),
            Formatter.formatFileSize(activity, progress.totalBytes.coerceAtLeast(0L))
        )
        ui.percentText.text = activity.getString(R.string.copy_progress_percent, (fraction * 100).toInt())
        ui.fileText.text = activity.getString(R.string.copy_progress_file, progress.currentFileName)
        ui.cancelButton.isEnabled = !cancelling
        ui.cancelButton.setText(if (cancelling) R.string.copy_cancelling else R.string.copy_cancel)
    }

    private fun resultMessage(result: TransferResult): String {
        val progress = result.progress
        val cancelled = when (result.operation) {
            TransferOperation.COPY -> R.string.copy_cancelled
            TransferOperation.MOVE -> R.string.move_cancelled
            TransferOperation.DELETE -> R.string.delete_cancelled
        }
        val failed = when (result.operation) {
            TransferOperation.COPY -> R.string.copy_failed
            TransferOperation.MOVE -> R.string.move_failed
            TransferOperation.DELETE -> R.string.delete_failed
        }
        if (result.cancelled) return activity.getString(cancelled)
        if (result.failed || progress.successCount == 0 && progress.failCount > 0) {
            return activity.getString(failed)
        }
        if (progress.failCount == 0 && progress.skipCount == 0) {
            val done = when (result.operation) {
                TransferOperation.COPY -> R.string.copy_done
                TransferOperation.MOVE -> R.string.move_done
                TransferOperation.DELETE -> R.string.delete_done
            }
            return activity.getString(done, progress.successCount)
        }
        return when (result.operation) {
            TransferOperation.COPY -> activity.getString(
                R.string.copy_partial, progress.successCount, progress.failCount, progress.skipCount
            )
            TransferOperation.MOVE -> activity.getString(
                R.string.move_partial, progress.successCount, progress.failCount, progress.skipCount
            )
            TransferOperation.DELETE -> activity.getString(
                R.string.delete_partial, progress.successCount, progress.failCount
            )
        }
    }
}
