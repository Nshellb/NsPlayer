package com.nshell.nsplayer.ui.main

import android.app.Activity
import android.net.Uri
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.IntentSenderRequest
import androidx.appcompat.app.AlertDialog
import androidx.core.widget.doAfterTextChanged
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ViewModelProvider
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.nshell.nsplayer.R
import com.nshell.nsplayer.data.repository.MediaItem
import com.nshell.nsplayer.data.repository.RenameOutcome

/** Displays retained query/rename state; no storage access belongs to the dialog callbacks. */
internal class MediaItemController(
    private val activity: MainActivity,
    private val launchRenamePermission: (IntentSenderRequest) -> Unit,
    private val launchFolderPicker: () -> Unit,
    private val onReloadRequested: () -> Unit
) : DefaultLifecycleObserver {
    private val viewModel = ViewModelProvider(activity)[MediaItemViewModel::class.java]
    private var dialog: AlertDialog? = null
    private var content: View? = null
    private var displayedRequestId: Long? = null
    private var renameInputInitialized = false

    init {
        activity.lifecycle.addObserver(this)
        viewModel.state.observe(activity) { render() }
    }

    fun showProperties(item: DisplayItem) = viewModel.open(item.toMediaItem(), rename = false)

    fun showRename(item: DisplayItem) {
        if (item.type == DisplayItem.Type.HIERARCHY && item.bucketId.isNullOrEmpty()) {
            Toast.makeText(activity, R.string.rename_root_not_allowed, Toast.LENGTH_SHORT).show()
            return
        }
        viewModel.open(item.toMediaItem(), rename = true)
    }

    fun onRenamePermissionResult(resultCode: Int) = viewModel.onPermissionResult(resultCode == Activity.RESULT_OK)

    fun onFolderPicked(uri: Uri?) = viewModel.onFolderPicked(uri)

    override fun onResume(owner: LifecycleOwner) = render()

    override fun onDestroy(owner: LifecycleOwner) {
        // Recreation only detaches views; explicit close cancels the query in the ViewModel.
        dismissDialog()
        owner.lifecycle.removeObserver(this)
    }

    private fun render() {
        if (!activity.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED) ||
            activity.isFinishing || activity.isDestroyed
        ) return
        val state = viewModel.currentState
        if (state == null) {
            dismissDialog()
            return
        }
        if (state is MediaItemState.Finished) {
            dismissDialog()
            if (viewModel.consumeResult(state)) {
                val message = when (val result = state.outcome) {
                    RenameOutcome.Success -> R.string.rename_done
                    is RenameOutcome.Failure -> when (result.reason) {
                        RenameOutcome.Reason.FAILED -> R.string.rename_failed
                        RenameOutcome.Reason.INVALID_ROOT -> R.string.rename_folder_root_invalid
                        RenameOutcome.Reason.NOT_FOUND -> R.string.rename_folder_not_found
                        RenameOutcome.Reason.PERMISSION_DENIED -> R.string.rename_permission_denied
                        RenameOutcome.Reason.PICK_CANCELLED -> R.string.rename_folder_pick_cancelled
                    }
                    else -> R.string.rename_failed
                }
                Toast.makeText(activity, message, Toast.LENGTH_SHORT).show()
                if (state.outcome == RenameOutcome.Success) onReloadRequested()
            }
            return
        }
        if (displayedRequestId != state.request.id) {
            dismissDialog()
            if (state.request.rename) showRenameDialog(state.request) else showPropertiesDialog(state.request)
        }
        if (state.request.rename) renderRename(state) else renderProperties(state)

        when (state) {
            is MediaItemState.PermissionRequired -> if (viewModel.claimExternalRequest(state.request.id)) {
                try {
                    launchRenamePermission(IntentSenderRequest.Builder(state.intentSender).build())
                } catch (_: RuntimeException) {
                    viewModel.onPermissionResult(false)
                }
            }
            is MediaItemState.FolderRequired -> if (viewModel.claimExternalRequest(state.request.id)) {
                Toast.makeText(activity, R.string.rename_folder_pick_root, Toast.LENGTH_SHORT).show()
                try {
                    launchFolderPicker()
                } catch (_: RuntimeException) {
                    viewModel.onFolderPicked(null)
                }
            }
            else -> Unit
        }
    }

    private fun showPropertiesDialog(request: MediaItemRequest) {
        displayedRequestId = request.id
        val view = activity.layoutInflater.inflate(R.layout.dialog_item_properties, null)
        content = view
        dialog = MaterialAlertDialogBuilder(activity)
            .setView(view)
            .setOnCancelListener { viewModel.close(request.id) }
            .show()
        view.findViewById<Button>(R.id.propertiesClose).setOnClickListener { viewModel.close(request.id) }
        view.findViewById<Button>(R.id.propertiesRetry).setOnClickListener { viewModel.retry(request.id) }
    }

    private fun renderProperties(state: MediaItemState) {
        val view = content ?: return
        val properties = activity.buildItemProperties(state.request.item, (state as? MediaItemState.Properties)?.metadata)
        view.findViewById<TextView>(R.id.propertiesTitle).text = properties.title
        view.findViewById<TextView>(R.id.propertiesType).text = properties.typeLabel
        view.findViewById<TextView>(R.id.propertiesNameValue).text = properties.fullName
        view.findViewById<TextView>(R.id.propertiesLocationValue).text = properties.location
        view.findViewById<TextView>(R.id.propertiesSizeValue).text = properties.size
        view.findViewById<TextView>(R.id.propertiesModifiedValue).text = properties.modified
        view.findViewById<TextView>(R.id.propertiesSubtitleValue).text = properties.subtitle
        renderLoadStatus(view, state, R.id.propertiesProgress, R.id.propertiesStatus, R.id.propertiesRetry)
    }

    private fun showRenameDialog(request: MediaItemRequest) {
        displayedRequestId = request.id
        val view = activity.layoutInflater.inflate(R.layout.dialog_rename, null)
        content = view
        val input = view.findViewById<EditText>(R.id.renameInput)
        input.setSingleLine(true)
        input.setText(request.item.title)
        input.doAfterTextChanged { viewModel.updateDraft(request.id, it?.toString().orEmpty()) }
        dialog = MaterialAlertDialogBuilder(activity)
            .setTitle(R.string.rename_title)
            .setView(view)
            .setPositiveButton(R.string.confirm, null)
            .setNegativeButton(R.string.cancel, null)
            .setOnCancelListener { viewModel.close(request.id) }
            .show().also { current ->
                current.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener { confirmRename(request.id) }
                current.getButton(AlertDialog.BUTTON_NEGATIVE).setOnClickListener { viewModel.close(request.id) }
            }
        view.findViewById<Button>(R.id.renameRetry).setOnClickListener { viewModel.retry(request.id) }
    }

    private fun renderRename(state: MediaItemState) {
        val view = content ?: return
        val currentDialog = dialog ?: return
        val input = view.findViewById<EditText>(R.id.renameInput)
        val editing = state as? MediaItemState.RenameInput
        val changing = state is MediaItemState.Renaming || state is MediaItemState.PermissionRequired ||
            state is MediaItemState.FolderRequired
        input.isEnabled = editing != null
        currentDialog.getButton(AlertDialog.BUTTON_POSITIVE).isEnabled = editing != null
        currentDialog.getButton(AlertDialog.BUTTON_NEGATIVE).isEnabled = !changing
        currentDialog.setCancelable(!changing)
        if (editing != null && !renameInputInitialized) {
            renameInputInitialized = true
            input.setText(editing.draftName)
            val dot = editing.draftName.lastIndexOf('.')
            val end = if (editing.request.item.type == MediaItem.Type.VIDEO && dot > 0) dot else editing.draftName.length
            input.setSelection(0, end)
        }
        val submittedName = when (state) {
            is MediaItemState.Renaming -> state.newName
            is MediaItemState.PermissionRequired -> state.newName
            is MediaItemState.FolderRequired -> state.newName
            else -> null
        }
        if (submittedName != null && input.text.toString() != submittedName) input.setText(submittedName)
        renderLoadStatus(view, state, R.id.renameProgress, R.id.renameStatus, R.id.renameRetry)
        if (changing) {
            view.findViewById<View>(R.id.renameProgress).visibility = View.VISIBLE
            view.findViewById<TextView>(R.id.renameStatus).apply {
                setText(R.string.rename_in_progress)
                visibility = View.VISIBLE
            }
        }
    }

    private fun renderLoadStatus(view: View, state: MediaItemState, progressId: Int, statusId: Int, retryId: Int) {
        val loading = state is MediaItemState.Loading
        val failed = state is MediaItemState.LoadFailed
        view.findViewById<View>(progressId).visibility = if (loading) View.VISIBLE else View.GONE
        view.findViewById<TextView>(statusId).apply {
            visibility = if (loading || failed) View.VISIBLE else View.GONE
            setText(if (failed) R.string.media_details_load_failed else R.string.status_loading)
        }
        view.findViewById<View>(retryId).visibility = if (failed) View.VISIBLE else View.GONE
    }

    private fun confirmRename(id: Long) {
        val state = viewModel.currentState as? MediaItemState.RenameInput ?: return
        if (state.request.id != id) return
        val name = if (state.request.item.type == MediaItem.Type.VIDEO) {
            activity.resolveRenameInput(state.draftName, state.originalName)
        } else state.draftName.trim()
        when {
            name.isEmpty() -> Toast.makeText(activity, R.string.rename_empty, Toast.LENGTH_SHORT).show()
            name == state.originalName -> viewModel.close(id)
            activity.containsInvalidNameChars(name) ->
                Toast.makeText(activity, R.string.rename_invalid, Toast.LENGTH_SHORT).show()
            else -> viewModel.rename(id, name)
        }
    }

    private fun dismissDialog() {
        dialog?.dismiss()
        dialog = null
        content = null
        displayedRequestId = null
        renameInputInitialized = false
    }

    private fun DisplayItem.toMediaItem() = MediaItem(
        type = when (type) {
            DisplayItem.Type.VIDEO -> MediaItem.Type.VIDEO
            DisplayItem.Type.FOLDER -> MediaItem.Type.FOLDER
            DisplayItem.Type.HIERARCHY -> MediaItem.Type.HIERARCHY
        },
        title = title,
        contentUri = contentUri,
        bucketId = bucketId
    )
}
