package com.nshell.nsplayer.ui.main

import android.content.Intent
import android.content.SharedPreferences
import android.net.Uri
import android.os.Build
import android.os.SystemClock
import android.provider.DocumentsContract
import android.provider.MediaStore
import android.text.format.Formatter
import android.util.Log
import android.app.Activity
import android.widget.Button
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.IntentSenderRequest
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.documentfile.provider.DocumentFile
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.nshell.nsplayer.R
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicBoolean

class TransferController(
    private val activity: AppCompatActivity,
    private val adapter: VideoListAdapter,
    private val preferences: SharedPreferences,
    private val launchCopyDestination: (Uri?) -> Unit,
    private val launchDeletePermission: (IntentSenderRequest) -> Unit,
    private val onReloadRequested: () -> Unit
) {
    private var pendingCopyItems: List<DisplayItem> = emptyList()
    private var pendingOperation: TransferOperation = TransferOperation.COPY
    private val deletePermissionLock = Any()
    private var pendingDeleteLatch: CountDownLatch? = null
    private var pendingDeleteGranted = false

    fun onCopyDestinationPicked(uri: Uri?) {
        if (uri == null) {
            pendingCopyItems = emptyList()
            return
        }
        preferences.edit().putString(KEY_COPY_TREE_URI, uri.toString()).apply()
        handleCopyToTree(uri, pendingCopyItems, pendingOperation)
    }

    fun onDeletePermissionResult(resultCode: Int) {
        synchronized(deletePermissionLock) {
            pendingDeleteGranted = resultCode == Activity.RESULT_OK
            pendingDeleteLatch?.countDown()
        }
    }

    fun startCopySelected() {
        val selected = adapter.getSelectedItems()
        if (selected.isEmpty()) {
            Toast.makeText(activity, activity.getString(R.string.copy_no_selection), Toast.LENGTH_SHORT).show()
            return
        }
        val videos = selected.filter { it.type == DisplayItem.Type.VIDEO && !it.contentUri.isNullOrEmpty() }
        if (videos.isEmpty()) {
            Toast.makeText(activity, activity.getString(R.string.copy_no_selection), Toast.LENGTH_SHORT).show()
            return
        }
        pendingCopyItems = videos
        pendingOperation = TransferOperation.COPY
        launchCopyDestination(buildInitialCopyTreeUri(videos))
    }

    fun startMoveSelected() {
        val selected = adapter.getSelectedItems()
        if (selected.isEmpty()) {
            Toast.makeText(activity, activity.getString(R.string.copy_no_selection), Toast.LENGTH_SHORT).show()
            return
        }
        val videos = selected.filter { it.type == DisplayItem.Type.VIDEO && !it.contentUri.isNullOrEmpty() }
        if (videos.isEmpty()) {
            Toast.makeText(activity, activity.getString(R.string.copy_no_selection), Toast.LENGTH_SHORT).show()
            return
        }
        pendingCopyItems = videos
        pendingOperation = TransferOperation.MOVE
        launchCopyDestination(buildInitialCopyTreeUri(videos))
    }

    fun startDeleteSelected() {
        val selected = adapter.getSelectedItems()
        if (selected.isEmpty()) {
            Toast.makeText(activity, activity.getString(R.string.delete_no_selection), Toast.LENGTH_SHORT).show()
            return
        }
        val videos = selected.filter { it.type == DisplayItem.Type.VIDEO && !it.contentUri.isNullOrEmpty() }
        if (videos.isEmpty()) {
            Toast.makeText(activity, activity.getString(R.string.delete_no_selection), Toast.LENGTH_SHORT).show()
            return
        }
        AlertDialog.Builder(activity)
            .setTitle(activity.getString(R.string.delete_title))
            .setMessage(activity.getString(R.string.delete_message, videos.size))
            .setPositiveButton(R.string.delete_confirm) { _, _ ->
                val cancelFlag = AtomicBoolean(false)
                val progressUi = showCopyProgress(videos.size, 0L, cancelFlag, TransferOperation.DELETE)
                Thread {
                    var successCount = 0
                    var failCount = 0
                    var skipCount = 0
                    var completed = 0
                    var currentFileName = "-"
                    for (item in videos) {
                        if (cancelFlag.get()) {
                            break
                        }
                        val uri = item.contentUri?.let { Uri.parse(it) }
                        currentFileName = item.title
                        if (uri == null) {
                            failCount++
                            completed++
                            updateCopyProgress(
                                progressUi,
                                completed,
                                videos.size,
                                successCount,
                                failCount,
                                skipCount,
                                0L,
                                0L,
                                currentFileName
                            )
                            continue
                        }
                        if (deleteSourceUri(uri)) {
                            successCount++
                        } else {
                            failCount++
                        }
                        completed++
                        updateCopyProgress(
                            progressUi,
                            completed,
                            videos.size,
                            successCount,
                            failCount,
                            skipCount,
                            0L,
                            0L,
                            currentFileName
                        )
                    }
                    activity.runOnUiThread {
                        progressUi.dialog.dismiss()
                        if (cancelFlag.get()) {
                            Toast.makeText(
                                activity,
                                activity.getString(R.string.delete_cancelled),
                                Toast.LENGTH_SHORT
                            ).show()
                            adapter.clearSelection()
                            onReloadRequested()
                            return@runOnUiThread
                        }
                        if (failCount == 0) {
                            Toast.makeText(
                                activity,
                                activity.getString(R.string.delete_done, successCount),
                                Toast.LENGTH_SHORT
                            ).show()
                        } else if (successCount == 0) {
                            Toast.makeText(
                                activity,
                                activity.getString(R.string.delete_failed),
                                Toast.LENGTH_SHORT
                            ).show()
                        } else {
                            Toast.makeText(
                                activity,
                                activity.getString(R.string.delete_partial, successCount, failCount),
                                Toast.LENGTH_SHORT
                            ).show()
                        }
                        adapter.clearSelection()
                        onReloadRequested()
                    }
                }.start()
            }
            .setNegativeButton(R.string.delete_cancel, null)
            .show()
    }

    private fun handleCopyToTree(treeUri: Uri, items: List<DisplayItem>, operation: TransferOperation) {
        if (items.isEmpty()) {
            return
        }
        try {
            val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            activity.contentResolver.takePersistableUriPermission(treeUri, flags)
        } catch (_: SecurityException) {
        }
        val root = DocumentFile.fromTreeUri(activity, treeUri)
        if (root == null || !root.isDirectory) {
            Toast.makeText(activity, activity.getString(R.string.copy_failed), Toast.LENGTH_SHORT).show()
            return
        }
        val cancelFlag = AtomicBoolean(false)
        val conflictState = ConflictDecisionState()
        val sizePlan = calculateCopySizePlan(items)
        val progressUi = showCopyProgress(items.size, sizePlan.totalBytes, cancelFlag, operation)
        Thread {
            var successCount = 0
            var failCount = 0
            var skipCount = 0
            var completed = 0
            var copiedBytes = 0L
            var lastUpdate = 0L
            var currentFileName = "-"
            for (item in items) {
                if (cancelFlag.get()) {
                    break
                }
                val srcUri = item.contentUri?.let { Uri.parse(it) }
                if (srcUri == null) {
                    currentFileName = item.title
                    failCount++
                    completed++
                    updateCopyProgress(
                        progressUi,
                        completed,
                        items.size,
                        successCount,
                        failCount,
                        skipCount,
                        copiedBytes,
                        sizePlan.totalBytes,
                        currentFileName
                    )
                    continue
                }
                val displayName = queryDisplayName(srcUri) ?: item.title
                currentFileName = displayName
                val mimeType = activity.contentResolver.getType(srcUri) ?: "video/*"
                val resolution = resolveTargetFile(root, displayName, cancelFlag, conflictState)
                if (resolution == null) {
                    skipCount++
                    completed++
                    updateCopyProgress(
                        progressUi,
                        completed,
                        items.size,
                        successCount,
                        failCount,
                        skipCount,
                        copiedBytes,
                        sizePlan.totalBytes,
                        currentFileName
                    )
                    continue
                }
                if (resolution.overwriteFailed) {
                    failCount++
                    completed++
                    updateCopyProgress(
                        progressUi,
                        completed,
                        items.size,
                        successCount,
                        failCount,
                        skipCount,
                        copiedBytes,
                        sizePlan.totalBytes,
                        currentFileName
                    )
                    continue
                }
                val targetFile = root.createFile(mimeType, resolution.targetName)
                if (targetFile == null) {
                    failCount++
                    completed++
                    updateCopyProgress(
                        progressUi,
                        completed,
                        items.size,
                        successCount,
                        failCount,
                        skipCount,
                        copiedBytes,
                        sizePlan.totalBytes,
                        currentFileName
                    )
                    continue
                }
                val copied = copyUriToDocument(srcUri, targetFile, cancelFlag) { bytesDelta ->
                    if (bytesDelta > 0) {
                        copiedBytes += bytesDelta
                        val now = SystemClock.elapsedRealtime()
                        if (now - lastUpdate >= PROGRESS_UPDATE_MS) {
                            lastUpdate = now
                            updateCopyProgress(
                                progressUi,
                                completed,
                                items.size,
                                successCount,
                                failCount,
                                skipCount,
                                copiedBytes,
                                sizePlan.totalBytes,
                                currentFileName
                            )
                        }
                    }
                }
                if (copied) {
                    successCount++
                    if (operation == TransferOperation.MOVE && !cancelFlag.get()) {
                        val deleted = deleteSourceUri(srcUri)
                        if (!deleted) {
                            if (successCount > 0) {
                                successCount--
                            }
                            failCount++
                        }
                    }
                } else {
                    failCount++
                    if (!cancelFlag.get()) {
                        targetFile.delete()
                    }
                }
                completed++
                updateCopyProgress(
                    progressUi,
                    completed,
                    items.size,
                    successCount,
                    failCount,
                    skipCount,
                    copiedBytes,
                    sizePlan.totalBytes,
                    currentFileName
                )
            }
            activity.runOnUiThread {
                progressUi.dialog.dismiss()
                if (cancelFlag.get()) {
                    val cancelMessage = if (operation == TransferOperation.MOVE) {
                        activity.getString(R.string.move_cancelled)
                    } else {
                        activity.getString(R.string.copy_cancelled)
                    }
                    Toast.makeText(activity, cancelMessage, Toast.LENGTH_SHORT).show()
                    adapter.clearSelection()
                    pendingCopyItems = emptyList()
                    return@runOnUiThread
                }
                if (operation == TransferOperation.MOVE) {
                    if (failCount == 0 && skipCount == 0) {
                        Toast.makeText(
                            activity,
                            activity.getString(R.string.move_done, successCount),
                            Toast.LENGTH_SHORT
                        ).show()
                    } else if (successCount == 0 && failCount > 0) {
                        Toast.makeText(
                            activity,
                            activity.getString(R.string.move_failed),
                            Toast.LENGTH_SHORT
                        ).show()
                    } else {
                        Toast.makeText(
                            activity,
                            activity.getString(R.string.move_partial, successCount, failCount, skipCount),
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                } else {
                    if (failCount == 0 && skipCount == 0) {
                        Toast.makeText(
                            activity,
                            activity.getString(R.string.copy_done, successCount),
                            Toast.LENGTH_SHORT
                        ).show()
                    } else if (successCount == 0 && failCount > 0) {
                        Toast.makeText(
                            activity,
                            activity.getString(R.string.copy_failed),
                            Toast.LENGTH_SHORT
                        ).show()
                    } else {
                        Toast.makeText(
                            activity,
                            activity.getString(R.string.copy_partial, successCount, failCount, skipCount),
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                }
                adapter.clearSelection()
                pendingCopyItems = emptyList()
            }
        }.start()
    }

    private fun queryDisplayName(uri: Uri): String? {
        val projection = arrayOf(MediaStore.MediaColumns.DISPLAY_NAME)
        activity.contentResolver.query(uri, projection, null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) {
                val nameCol = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DISPLAY_NAME)
                val name = cursor.getString(nameCol)
                if (!name.isNullOrEmpty()) {
                    return name
                }
            }
        }
        return null
    }

    private fun buildUniqueName(parent: DocumentFile, baseName: String): String {
        if (parent.findFile(baseName) == null) {
            return baseName
        }
        val dot = baseName.lastIndexOf('.')
        val stem = if (dot > 0) baseName.substring(0, dot) else baseName
        val ext = if (dot > 0) baseName.substring(dot) else ""
        var index = 1
        var candidate = "$stem ($index)$ext"
        while (parent.findFile(candidate) != null) {
            index++
            candidate = "$stem ($index)$ext"
        }
        return candidate
    }

    private fun copyUriToDocument(
        sourceUri: Uri,
        target: DocumentFile,
        cancelFlag: AtomicBoolean,
        onBytesCopied: (Long) -> Unit
    ): Boolean {
        return try {
            activity.contentResolver.openInputStream(sourceUri)?.use { input ->
                activity.contentResolver.openOutputStream(target.uri)?.use { output ->
                    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                    var read = input.read(buffer)
                    while (read >= 0) {
                        if (cancelFlag.get()) {
                            return false
                        }
                        if (read > 0) {
                            output.write(buffer, 0, read)
                            onBytesCopied(read.toLong())
                        }
                        read = input.read(buffer)
                    }
                    output.flush()
                    true
                } ?: false
            } ?: false
        } catch (_: Exception) {
            false
        }
    }

    private data class CopySizePlan(
        val totalBytes: Long
    )

    private data class CopyProgressUi(
        val dialog: BottomSheetDialog,
        val progressBar: ProgressBar,
        val countText: TextView,
        val sizeText: TextView,
        val percentText: TextView,
        val fileText: TextView,
        val cancelButton: Button
    )

    private fun showCopyProgress(
        total: Int,
        totalBytes: Long,
        cancelFlag: AtomicBoolean,
        operation: TransferOperation
    ): CopyProgressUi {
        val content = activity.layoutInflater.inflate(R.layout.bottom_sheet_copy_progress, null)
        val progressBar = content.findViewById<ProgressBar>(R.id.copyProgressBar)
        val titleText = content.findViewById<TextView>(R.id.copyProgressTitle)
        val countText = content.findViewById<TextView>(R.id.copyProgressCount)
        val sizeText = content.findViewById<TextView>(R.id.copyProgressSize)
        val percentText = content.findViewById<TextView>(R.id.copyProgressPercent)
        val fileText = content.findViewById<TextView>(R.id.copyProgressFile)
        val cancelButton = content.findViewById<Button>(R.id.copyProgressCancel)
        titleText.text = when (operation) {
            TransferOperation.MOVE -> activity.getString(R.string.move_progress_title)
            TransferOperation.DELETE -> activity.getString(R.string.delete_progress_title)
            else -> activity.getString(R.string.copy_progress_title)
        }
        if (totalBytes > 0L) {
            progressBar.isIndeterminate = false
            progressBar.max = PROGRESS_MAX
            progressBar.progress = 0
        } else {
            progressBar.isIndeterminate = true
        }
        countText.text = activity.getString(R.string.copy_progress_count, 0, total, 0, 0, 0)
        sizeText.text = activity.getString(
            R.string.copy_progress_size,
            Formatter.formatFileSize(activity, 0L),
            Formatter.formatFileSize(activity, totalBytes.coerceAtLeast(0L))
        )
        percentText.text = activity.getString(R.string.copy_progress_percent, 0)
        fileText.text = activity.getString(R.string.copy_progress_file, "-")
        val dialog = BottomSheetDialog(activity)
        dialog.setContentView(content)
        dialog.setCancelable(false)
        dialog.show()
        cancelButton.setOnClickListener {
            cancelFlag.set(true)
            cancelButton.isEnabled = false
            cancelButton.text = activity.getString(R.string.copy_cancelling)
        }
        return CopyProgressUi(dialog, progressBar, countText, sizeText, percentText, fileText, cancelButton)
    }

    private fun updateCopyProgress(
        ui: CopyProgressUi,
        completed: Int,
        total: Int,
        successCount: Int,
        failCount: Int,
        skipCount: Int,
        copiedBytes: Long,
        totalBytes: Long,
        currentFileName: String
    ) {
        activity.runOnUiThread {
            if (!ui.progressBar.isIndeterminate && totalBytes > 0L) {
                val percent = (copiedBytes.coerceAtLeast(0L) * PROGRESS_MAX / totalBytes.coerceAtLeast(1L))
                ui.progressBar.progress = percent.coerceIn(0L, PROGRESS_MAX.toLong()).toInt()
            }
            ui.countText.text = activity.getString(
                R.string.copy_progress_count,
                completed.coerceAtMost(total),
                total,
                successCount,
                failCount,
                skipCount
            )
            ui.sizeText.text = activity.getString(
                R.string.copy_progress_size,
                Formatter.formatFileSize(activity, copiedBytes.coerceAtLeast(0L)),
                Formatter.formatFileSize(activity, totalBytes.coerceAtLeast(0L))
            )
            val percent = if (totalBytes > 0L) {
                (copiedBytes.coerceAtLeast(0L) * 100 / totalBytes.coerceAtLeast(1L)).toInt()
            } else {
                0
            }
            ui.percentText.text = activity.getString(R.string.copy_progress_percent, percent.coerceIn(0, 100))
            ui.fileText.text = activity.getString(R.string.copy_progress_file, currentFileName)
        }
    }

    private fun calculateCopySizePlan(items: List<DisplayItem>): CopySizePlan {
        var total = 0L
        for (item in items) {
            val srcUri = item.contentUri?.let { Uri.parse(it) } ?: continue
            val size = querySizeBytes(srcUri)
            if (size > 0) {
                total += size
            }
        }
        return CopySizePlan(total)
    }

    private fun querySizeBytes(uri: Uri): Long {
        val projection = arrayOf(MediaStore.MediaColumns.SIZE)
        activity.contentResolver.query(uri, projection, null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) {
                val sizeCol = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.SIZE)
                return cursor.getLong(sizeCol)
            }
        }
        return 0L
    }

    private fun deleteSourceUri(uri: Uri): Boolean {
        try {
            return activity.contentResolver.delete(uri, null, null) > 0
        } catch (e: SecurityException) {
            Log.e(TAG_DELETE, "deleteSourceUri SecurityException uri=$uri", e)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q &&
                e is android.app.RecoverableSecurityException
            ) {
                val granted = requestDeletePermission(e)
                if (granted) {
                    return try {
                        activity.contentResolver.delete(uri, null, null) > 0
                    } catch (retry: Exception) {
                        Log.e(TAG_DELETE, "deleteSourceUri retry failed uri=$uri", retry)
                        false
                    }
                }
            }
            return false
        } catch (e: Exception) {
            Log.e(TAG_DELETE, "deleteSourceUri Exception uri=$uri", e)
            return false
        }
    }

    private fun requestDeletePermission(
        e: android.app.RecoverableSecurityException
    ): Boolean {
        val latch = CountDownLatch(1)
        synchronized(deletePermissionLock) {
            pendingDeleteGranted = false
            pendingDeleteLatch = latch
        }
        activity.runOnUiThread {
            val intentSender = e.userAction.actionIntent.intentSender
            val request = IntentSenderRequest.Builder(intentSender).build()
            launchDeletePermission(request)
        }
        latch.await()
        synchronized(deletePermissionLock) {
            pendingDeleteLatch = null
            return pendingDeleteGranted
        }
    }

    private data class ConflictResolution(
        val targetName: String,
        val overwriteFailed: Boolean
    )

    private data class ConflictDecision(
        val choice: ConflictChoice,
        val applyAll: Boolean
    )

    private data class ConflictDecisionState(
        var applyAllChoice: ConflictChoice? = null
    )

    private enum class ConflictChoice {
        OVERWRITE,
        RENAME,
        SKIP
    }

    private fun resolveTargetFile(
        parent: DocumentFile,
        displayName: String,
        cancelFlag: AtomicBoolean,
        conflictState: ConflictDecisionState
    ): ConflictResolution? {
        val existing = parent.findFile(displayName)
        if (existing == null) {
            return ConflictResolution(displayName, false)
        }
        if (cancelFlag.get()) {
            return null
        }
        val choice = conflictState.applyAllChoice ?: run {
            val decision = promptConflictChoice(displayName)
            if (decision.applyAll) {
                conflictState.applyAllChoice = decision.choice
            }
            decision.choice
        }
        return when (choice) {
            ConflictChoice.OVERWRITE -> {
                val deleted = try {
                    existing.delete()
                } catch (e: Exception) {
                    Log.e(TAG_DELETE, "overwrite delete failed name=$displayName uri=${existing.uri}", e)
                    false
                }
                ConflictResolution(displayName, !deleted)
            }
            ConflictChoice.RENAME -> ConflictResolution(buildUniqueName(parent, displayName), false)
            ConflictChoice.SKIP -> null
        }
    }

    private fun promptConflictChoice(fileName: String): ConflictDecision {
        val latch = CountDownLatch(1)
        var choice = ConflictChoice.SKIP
        var applyAll = false
        activity.runOnUiThread {
            val content = activity.layoutInflater.inflate(R.layout.dialog_conflict_resolution, null)
            val applyAllCheck = content.findViewById<android.widget.CheckBox>(R.id.applyToAllCheck)
            AlertDialog.Builder(activity)
                .setTitle(activity.getString(R.string.conflict_title))
                .setMessage(activity.getString(R.string.conflict_message, fileName))
                .setView(content)
                .setCancelable(false)
                .setPositiveButton(R.string.conflict_overwrite) { _, _ ->
                    choice = ConflictChoice.OVERWRITE
                    applyAll = applyAllCheck.isChecked
                    latch.countDown()
                }
                .setNeutralButton(R.string.conflict_rename) { _, _ ->
                    choice = ConflictChoice.RENAME
                    applyAll = applyAllCheck.isChecked
                    latch.countDown()
                }
                .setNegativeButton(R.string.conflict_skip) { _, _ ->
                    choice = ConflictChoice.SKIP
                    applyAll = applyAllCheck.isChecked
                    latch.countDown()
                }
                .show()
        }
        latch.await()
        return ConflictDecision(choice, applyAll)
    }

    private data class VolumePath(
        val volumeName: String,
        val relativePath: String
    )

    private fun buildInitialCopyTreeUri(items: List<DisplayItem>): Uri? {
        val source = items.firstOrNull() ?: return null
        val srcUri = source.contentUri?.let { Uri.parse(it) } ?: return null
        val volumePath = queryVolumePath(srcUri) ?: return null
        val documentVolume = resolveDocumentVolumeName(volumePath.volumeName) ?: return null
        val relative = normalizeRelativeDocPath(volumePath.relativePath)
        val docId = if (relative.isEmpty()) "$documentVolume:" else "$documentVolume:$relative"
        return try {
            DocumentsContract.buildTreeDocumentUri(EXTERNAL_STORAGE_AUTHORITY, docId)
        } catch (_: Exception) {
            null
        }
    }

    private fun queryVolumePath(uri: Uri): VolumePath? {
        val projection = arrayOf(
            MediaStore.MediaColumns.RELATIVE_PATH,
            MediaStore.MediaColumns.VOLUME_NAME
        )
        activity.contentResolver.query(uri, projection, null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) {
                val relCol = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.RELATIVE_PATH)
                val volumeCol = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.VOLUME_NAME)
                val relative = cursor.getString(relCol) ?: ""
                val volume = cursor.getString(volumeCol)
                return VolumePath(resolveVolumeName(volume), relative)
            }
        }
        return null
    }

    private fun resolveVolumeName(volumeName: String?): String {
        return if (volumeName.isNullOrEmpty()) {
            MediaStore.VOLUME_EXTERNAL_PRIMARY
        } else {
            volumeName
        }
    }

    private fun resolveDocumentVolumeName(volumeName: String): String? {
        return when (volumeName) {
            MediaStore.VOLUME_EXTERNAL_PRIMARY, MediaStore.VOLUME_EXTERNAL -> "primary"
            else -> volumeName
        }
    }

    private fun normalizeRelativeDocPath(path: String?): String {
        if (path.isNullOrEmpty()) {
            return ""
        }
        var normalized = path.replace('\\', '/').trim()
        normalized = normalized.trim('/')
        return normalized
    }

    companion object {
        private const val EXTERNAL_STORAGE_AUTHORITY = "com.android.externalstorage.documents"
        private const val KEY_COPY_TREE_URI = "copy_tree_uri"
        private const val PROGRESS_MAX = 1000
        private const val PROGRESS_UPDATE_MS = 200L
        private const val TAG_DELETE = "NsPlayerDelete"
    }

    private enum class TransferOperation {
        COPY,
        MOVE,
        DELETE
    }
}
