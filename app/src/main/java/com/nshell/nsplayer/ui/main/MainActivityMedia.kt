package com.nshell.nsplayer.ui.main

import android.provider.MediaStore
import android.text.format.Formatter
import android.widget.TextView
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.nshell.nsplayer.R
import com.nshell.nsplayer.data.repository.MediaItem
import com.nshell.nsplayer.data.repository.MediaItemMetadata
import java.text.DateFormat
import java.util.Date

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
    mediaItemController.showProperties(item)
}

internal fun MainActivity.showRenameDialog(item: DisplayItem) {
    mediaItemController.showRename(item)
}

internal fun MainActivity.buildItemProperties(item: MediaItem, metadata: MediaItemMetadata?): ItemProperties {
    val typeLabel = if (item.type == MediaItem.Type.VIDEO) {
        getString(R.string.property_type_file)
    } else {
        getString(R.string.property_type_folder)
    }
    return if (item.type == MediaItem.Type.VIDEO) {
        val meta = metadata
        val fullName = meta?.displayName ?: item.title
        val location = if (meta != null) buildLocationLabel(meta.volumeName, meta.relativePath)
            else getString(R.string.property_unknown)
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
        val folderInfo = metadata
        val location = when {
            item.type == MediaItem.Type.HIERARCHY && item.bucketId.isNullOrEmpty() ->
                getString(R.string.property_root)
            item.type == MediaItem.Type.HIERARCHY && folderInfo != null ->
                buildLocationLabel(folderInfo.volumeName, folderInfo.relativePath)
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

internal fun MainActivity.formatModifiedDate(modifiedSeconds: Long): String {
    val formatter = DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT)
    return formatter.format(Date(modifiedSeconds * 1000))
}
