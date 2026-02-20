package com.nshell.nsplayer.ui.main

import android.app.RecoverableSecurityException
import android.content.ContentValues
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.text.format.Formatter
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.documentfile.provider.DocumentFile
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.nshell.nsplayer.R
import java.text.DateFormat
import java.util.Date
import java.util.Locale

internal fun MainActivity.showItemBottomSheet(item: DisplayItem?) {
    if (item == null) {
        return
    }
    if (adapter.isSelectionMode()) {
        return
    }
    val content = layoutInflater.inflate(R.layout.bottom_sheet_item, null)
    val title = content.findViewById<TextView>(R.id.bottomSheetTitle)
    title.text = item.title
    val renameButton = content.findViewById<TextView>(R.id.actionRename)
    val propertiesButton = content.findViewById<TextView>(R.id.actionProperties)

    val dialog = BottomSheetDialog(this)
    dialog.setContentView(content)

    renameButton.setOnClickListener {
        dialog.dismiss()
        showRenameDialog(item)
    }
    propertiesButton.setOnClickListener {
        showItemPropertiesDialog(item)
        dialog.dismiss()
    }

    dialog.show()
}

internal fun MainActivity.showItemPropertiesDialog(item: DisplayItem) {
    val properties = loadItemProperties(item)
    val content = layoutInflater.inflate(R.layout.dialog_item_properties, null)
    content.findViewById<TextView>(R.id.propertiesTitle).text = properties.title
    content.findViewById<TextView>(R.id.propertiesType).text = properties.typeLabel
    content.findViewById<TextView>(R.id.propertiesNameValue).text = properties.fullName
    content.findViewById<TextView>(R.id.propertiesLocationValue).text = properties.location
    content.findViewById<TextView>(R.id.propertiesSizeValue).text = properties.size
    content.findViewById<TextView>(R.id.propertiesModifiedValue).text = properties.modified
    content.findViewById<TextView>(R.id.propertiesSubtitleValue).text = properties.subtitle

    val dialog = AlertDialog.Builder(this)
        .setView(content)
        .create()
    dialog.setCanceledOnTouchOutside(true)
    content.findViewById<Button>(R.id.propertiesClose).setOnClickListener { dialog.dismiss() }
    dialog.show()
}

internal fun MainActivity.showRenameDialog(item: DisplayItem) {
    val isVideo = item.type == DisplayItem.Type.VIDEO
    if (
        !isVideo &&
        item.type != DisplayItem.Type.FOLDER &&
        item.type != DisplayItem.Type.HIERARCHY
    ) {
        Toast.makeText(this, getString(R.string.action_not_ready), Toast.LENGTH_SHORT).show()
        return
    }
    if (item.type == DisplayItem.Type.HIERARCHY && item.bucketId.isNullOrEmpty()) {
        Toast.makeText(
            this,
            getString(R.string.rename_root_not_allowed),
            Toast.LENGTH_SHORT
        ).show()
        return
    }
    val currentName = if (isVideo) {
        queryVideoMetadata(item.contentUri)?.displayName?.ifEmpty { item.title } ?: item.title
    } else {
        item.title
    }
    val input = EditText(this)
    input.setSingleLine(true)
    input.hint = getString(R.string.rename_hint)
    input.setText(currentName)
    if (isVideo) {
        val dot = currentName.lastIndexOf('.')
        if (dot > 0) {
            input.setSelection(0, dot)
        } else {
            input.setSelection(0, currentName.length)
        }
    } else {
        input.setSelection(0, currentName.length)
    }
    AlertDialog.Builder(this)
        .setTitle(R.string.rename_title)
        .setView(input)
        .setPositiveButton(R.string.confirm) { _, _ ->
            val rawName = input.text?.toString()?.trim() ?: ""
            val resolved = if (isVideo) {
                resolveRenameInput(rawName, currentName)
            } else {
                rawName.trim()
            }
            when {
                resolved.isEmpty() -> {
                    Toast.makeText(
                        this,
                        getString(R.string.rename_empty),
                        Toast.LENGTH_SHORT
                    ).show()
                }
                resolved == currentName -> {
                    return@setPositiveButton
                }
                containsInvalidNameChars(resolved) -> {
                    Toast.makeText(
                        this,
                        getString(R.string.rename_invalid),
                        Toast.LENGTH_SHORT
                    ).show()
                }
                else -> {
                    if (isVideo) {
                        renameMediaItem(item, resolved, allowPermissionRequest = true)
                    } else {
                        val relativePath = resolveFolderRelativePath(item)
                        if (relativePath.isNullOrEmpty()) {
                            Toast.makeText(
                                this,
                                getString(R.string.rename_failed),
                                Toast.LENGTH_SHORT
                            ).show()
                            return@setPositiveButton
                        }
                        val request = FolderRenameRequest(item, resolved, relativePath)
                        val savedTree = getSavedFolderRenameTreeUri()
                        if (savedTree != null && hasPersistedTreePermission(savedTree)) {
                            val result = tryFolderRenameWithTree(
                                savedTree,
                                request,
                                showToasts = false
                            )
                            if (result == FolderRenameResult.SUCCESS) {
                                Toast.makeText(
                                    this,
                                    getString(R.string.rename_done),
                                    Toast.LENGTH_SHORT
                                ).show()
                                loadIfPermitted()
                                return@setPositiveButton
                            }
                        }
                        pendingFolderRename = request
                        Toast.makeText(
                            this,
                            getString(R.string.rename_folder_pick_root),
                            Toast.LENGTH_SHORT
                        ).show()
                        folderRenameTreeLauncher.launch(null)
                    }
                }
            }
        }
        .setNegativeButton(R.string.cancel, null)
        .show()
}

internal fun MainActivity.loadItemProperties(item: DisplayItem): ItemProperties {
    val typeLabel = if (item.type == DisplayItem.Type.VIDEO) {
        getString(R.string.property_type_file)
    } else {
        getString(R.string.property_type_folder)
    }
    return if (item.type == DisplayItem.Type.VIDEO) {
        val meta = queryVideoMetadata(item.contentUri)
        val fullName = meta?.displayName ?: item.title
        val location = buildLocationLabel(meta?.volumeName, meta?.relativePath)
        val size = if (meta != null) {
            Formatter.formatFileSize(this, meta.sizeBytes)
        } else {
            getString(R.string.property_unknown)
        }
        val modified = meta?.modifiedSeconds?.takeIf { it > 0 }
            ?.let { formatModifiedDate(it) }
            ?: getString(R.string.property_unknown)
        ItemProperties(
            title = item.title,
            typeLabel = typeLabel,
            fullName = fullName,
            location = location,
            size = size,
            modified = modified,
            subtitle = getString(R.string.property_subtitle_na)
        )
    } else {
        val folderInfo = queryFolderInfo(item)
        val location = when {
            item.type == DisplayItem.Type.HIERARCHY && item.bucketId.isNullOrEmpty() ->
                getString(R.string.property_root)
            item.type == DisplayItem.Type.HIERARCHY ->
                buildLocationLabel(folderInfo?.volumeName, folderInfo?.relativePath)
            else -> folderInfo?.relativePath ?: getString(R.string.property_unknown)
        }
        val size = folderInfo?.sizeBytes?.takeIf { it > 0 }
            ?.let { Formatter.formatFileSize(this, it) }
            ?: if (folderInfo != null) {
                Formatter.formatFileSize(this, 0L)
            } else {
                getString(R.string.property_unknown)
            }
        val modified = folderInfo?.modifiedSeconds?.takeIf { it > 0 }
            ?.let { formatModifiedDate(it) }
            ?: getString(R.string.property_unknown)
        ItemProperties(
            title = item.title,
            typeLabel = typeLabel,
            fullName = item.title,
            location = location,
            size = size,
            modified = modified,
            subtitle = getString(R.string.property_subtitle_na)
        )
    }
}

internal fun MainActivity.queryVideoMetadata(contentUri: String?): VideoMeta? {
    val uri = contentUri?.let { Uri.parse(it) } ?: return null
    val projection = arrayOf(
        MediaStore.Video.Media.DISPLAY_NAME,
        MediaStore.Video.Media.RELATIVE_PATH,
        MediaStore.Video.Media.SIZE,
        MediaStore.Video.Media.DATE_MODIFIED,
        MediaStore.Video.Media.VOLUME_NAME
    )
    contentResolver.query(uri, projection, null, null, null)?.use { cursor ->
        if (!cursor.moveToFirst()) {
            return null
        }
        val nameCol = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.DISPLAY_NAME)
        val pathCol = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.RELATIVE_PATH)
        val sizeCol = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.SIZE)
        val modifiedCol = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.DATE_MODIFIED)
        val volumeCol = cursor.getColumnIndex(MediaStore.Video.Media.VOLUME_NAME)
        return VideoMeta(
            displayName = cursor.getString(nameCol) ?: "",
            relativePath = cursor.getString(pathCol) ?: "",
            sizeBytes = cursor.getLong(sizeCol),
            modifiedSeconds = cursor.getLong(modifiedCol),
            volumeName = if (volumeCol >= 0) cursor.getString(volumeCol) else null
        )
    }
    return null
}

internal fun MainActivity.resolveRenameInput(input: String, originalName: String): String {
    val trimmed = input.trim()
    if (trimmed.isEmpty()) {
        return ""
    }
    if (trimmed.contains('.') || !originalName.contains('.')) {
        return trimmed
    }
    val ext = originalName.substringAfterLast('.', "")
    return if (ext.isNotEmpty()) "$trimmed.$ext" else trimmed
}

internal fun MainActivity.containsInvalidNameChars(name: String): Boolean {
    return name.contains('/') || name.contains('\\')
}

internal fun MainActivity.renameMediaItem(
    item: DisplayItem,
    newName: String,
    allowPermissionRequest: Boolean
) {
    val uri = item.contentUri?.let { Uri.parse(it) }
    if (uri == null) {
        Toast.makeText(this, getString(R.string.rename_failed), Toast.LENGTH_SHORT).show()
        return
    }
    val values = ContentValues().apply {
        put(MediaStore.MediaColumns.DISPLAY_NAME, newName)
    }
    try {
        val updated = contentResolver.update(uri, values, null, null)
        if (updated > 0) {
            Toast.makeText(this, getString(R.string.rename_done), Toast.LENGTH_SHORT).show()
            loadIfPermitted()
        } else {
            Toast.makeText(this, getString(R.string.rename_failed), Toast.LENGTH_SHORT).show()
        }
    } catch (e: SecurityException) {
        if (allowPermissionRequest &&
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q &&
            e is RecoverableSecurityException
        ) {
            pendingRename = RenameRequest(item, newName)
            val intentSender = e.userAction.actionIntent.intentSender
            val request =
                androidx.activity.result.IntentSenderRequest.Builder(intentSender).build()
            renamePermissionLauncher.launch(request)
            return
        }
        Toast.makeText(this, getString(R.string.rename_failed), Toast.LENGTH_SHORT).show()
    } catch (_: Exception) {
        Toast.makeText(this, getString(R.string.rename_failed), Toast.LENGTH_SHORT).show()
    }
}

internal fun MainActivity.resolveFolderRelativePath(item: DisplayItem): String? {
    return when (item.type) {
        DisplayItem.Type.FOLDER -> queryFolderInfo(item)?.relativePath
        DisplayItem.Type.HIERARCHY -> stripVolumePrefix(item.bucketId)
        else -> null
    }?.trim()
}

internal fun MainActivity.handleFolderRenameTree(
    uri: Uri,
    request: FolderRenameRequest,
    showToasts: Boolean
) {
    val result = tryFolderRenameWithTree(uri, request, showToasts)
    if (result == FolderRenameResult.SUCCESS && showToasts) {
        Toast.makeText(this, getString(R.string.rename_done), Toast.LENGTH_SHORT).show()
        loadIfPermitted()
    }
}

internal fun MainActivity.tryFolderRenameWithTree(
    uri: Uri,
    request: FolderRenameRequest,
    showToasts: Boolean
): FolderRenameResult {
    try {
        val flags =
            Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
        contentResolver.takePersistableUriPermission(uri, flags)
    } catch (_: SecurityException) {
    }
    val root = DocumentFile.fromTreeUri(this, uri)
    if (root == null || !root.isDirectory) {
        if (showToasts) {
            Toast.makeText(
                this,
                getString(R.string.rename_folder_root_invalid),
                Toast.LENGTH_SHORT
            ).show()
        }
        return FolderRenameResult.INVALID_ROOT
    }
    val target = findFolderInTree(root, request.relativePath)
    if (target == null) {
        if (showToasts) {
            Toast.makeText(
                this,
                getString(R.string.rename_folder_not_found),
                Toast.LENGTH_SHORT
            ).show()
        }
        return FolderRenameResult.NOT_FOUND
    }
    val success = try {
        target.renameTo(request.newName)
    } catch (_: Exception) {
        false
    }
    if (success) {
        return FolderRenameResult.SUCCESS
    }
    if (showToasts) {
        Toast.makeText(this, getString(R.string.rename_failed), Toast.LENGTH_SHORT).show()
    }
    return FolderRenameResult.FAILED
}

internal fun MainActivity.findFolderInTree(root: DocumentFile, relativePath: String): DocumentFile? {
    val segments = relativePath.split('/').filter { it.isNotBlank() }
    if (segments.isEmpty()) {
        return null
    }
    fun traverseFrom(startIndex: Int): DocumentFile? {
        var current: DocumentFile? = root
        for (i in startIndex until segments.size) {
            val next = current?.findFile(segments[i]) ?: return null
            if (!next.isDirectory) {
                return null
            }
            current = next
        }
        return current
    }
    traverseFrom(0)?.let { return it }
    for (start in 1 until segments.size) {
        val found = traverseFrom(start)
        if (found != null) {
            return found
        }
    }
    return null
}

internal fun MainActivity.stripVolumePrefix(path: String?): String? {
    if (path.isNullOrEmpty()) {
        return null
    }
    if (!path.startsWith(MainActivity.VOLUME_PREFIX)) {
        return path
    }
    val rest = path.removePrefix(MainActivity.VOLUME_PREFIX)
    val slash = rest.indexOf('/')
    if (slash < 0) {
        return ""
    }
    return rest.substring(slash + 1)
}

internal fun MainActivity.parseVolumePath(path: String?): VolumePath? {
    if (path.isNullOrEmpty() || !path.startsWith(MainActivity.VOLUME_PREFIX)) {
        return null
    }
    val rest = path.removePrefix(MainActivity.VOLUME_PREFIX)
    val slash = rest.indexOf('/')
    if (slash < 0) {
        return VolumePath(rest, "")
    }
    val volume = rest.substring(0, slash)
    val rel = rest.substring(slash + 1)
    return VolumePath(volume, rel)
}

internal fun MainActivity.buildVolumeLabel(volumeName: String?): String {
    val resolved = if (volumeName.isNullOrEmpty()) {
        MediaStore.VOLUME_EXTERNAL_PRIMARY
    } else {
        volumeName
    }
    return if (resolved == MediaStore.VOLUME_EXTERNAL_PRIMARY) {
        getString(R.string.storage_internal)
    } else {
        getString(R.string.storage_external_format, resolved)
    }
}

internal fun MainActivity.buildLocationLabel(volumeName: String?, relativePath: String?): String {
    val path = relativePath?.trim()?.trimStart('/') ?: ""
    val base = buildVolumeLabel(volumeName)
    if (path.isEmpty()) {
        return base
    }
    return "$base / $path"
}

internal fun MainActivity.getSavedFolderRenameTreeUri(): Uri? {
    val value = preferences.getString(MainActivity.KEY_FOLDER_RENAME_TREE_URI, null) ?: return null
    return try {
        Uri.parse(value)
    } catch (_: Exception) {
        null
    }
}

internal fun MainActivity.hasPersistedTreePermission(uri: Uri): Boolean {
    val target = uri.toString()
    return contentResolver.persistedUriPermissions.any { perm ->
        perm.isWritePermission && perm.uri.toString() == target
    }
}

internal fun MainActivity.queryFolderInfo(item: DisplayItem): FolderMeta? {
    val projection = arrayOf(
        MediaStore.Video.Media.RELATIVE_PATH,
        MediaStore.Video.Media.SIZE,
        MediaStore.Video.Media.DATE_MODIFIED
    )
    var selection: String? = null
    var selectionArgs: Array<String>? = null
    var volumeName: String? = null
    var contentUri: Uri = MediaStore.Video.Media.EXTERNAL_CONTENT_URI
    when (item.type) {
        DisplayItem.Type.FOLDER -> {
            val bucketId = item.bucketId ?: return null
            selection = "${MediaStore.Video.Media.BUCKET_ID}=?"
            selectionArgs = arrayOf(bucketId)
        }
        DisplayItem.Type.HIERARCHY -> {
            val rawPath = item.bucketId ?: ""
            val parsed = parseVolumePath(rawPath)
            volumeName = parsed?.volumeName
            if (!volumeName.isNullOrEmpty()) {
                contentUri = MediaStore.Video.Media.getContentUri(volumeName!!)
            }
            val relPath = parsed?.relativePath ?: stripVolumePrefix(rawPath)
            if (!relPath.isNullOrEmpty()) {
                val normalized = if (relPath.endsWith("/")) relPath else "$relPath/"
                selection = "${MediaStore.Video.Media.RELATIVE_PATH} LIKE ?"
                selectionArgs = arrayOf("$normalized%")
            }
        }
        else -> return null
    }
    var relativePath: String? = null
    var sizeTotal = 0L
    var latestModified = 0L
    contentResolver.query(
        contentUri,
        projection,
        selection,
        selectionArgs,
        null
    )?.use { cursor ->
        val pathCol = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.RELATIVE_PATH)
        val sizeCol = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.SIZE)
        val modifiedCol = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.DATE_MODIFIED)
        while (cursor.moveToNext()) {
            if (relativePath == null) {
                relativePath = cursor.getString(pathCol)
            }
            sizeTotal += cursor.getLong(sizeCol)
            val modified = cursor.getLong(modifiedCol)
            if (modified > latestModified) {
                latestModified = modified
            }
        }
    }
    if (relativePath == null && item.type == DisplayItem.Type.HIERARCHY) {
        relativePath = item.bucketId
    }
    return FolderMeta(relativePath, sizeTotal, latestModified, volumeName)
}

internal fun MainActivity.hasSubtitle(relativePath: String?, displayName: String?): Boolean {
    if (relativePath.isNullOrEmpty() || displayName.isNullOrEmpty()) {
        return false
    }
    val base = displayName.substringBeforeLast('.', displayName)
    if (base.isEmpty()) {
        return false
    }
    val targets = setOf("srt", "vtt", "ass", "ssa", "sub")
    val targetNames = targets.map { "$base.$it".lowercase(Locale.US) }.toSet()
    val projection = arrayOf(MediaStore.MediaColumns.DISPLAY_NAME)
    val selection = "${MediaStore.MediaColumns.RELATIVE_PATH}=?"
    val selectionArgs = arrayOf(relativePath)
    contentResolver.query(
        MediaStore.Files.getContentUri("external"),
        projection,
        selection,
        selectionArgs,
        null
    )?.use { cursor ->
        val nameCol = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DISPLAY_NAME)
        while (cursor.moveToNext()) {
            val name = cursor.getString(nameCol) ?: continue
            if (targetNames.contains(name.lowercase(Locale.US))) {
                return true
            }
        }
    }
    return false
}

internal fun MainActivity.formatModifiedDate(modifiedSeconds: Long): String {
    val formatter = DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT)
    return formatter.format(Date(modifiedSeconds * 1000))
}
