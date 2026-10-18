package com.nshell.nsplayer.data.transfer

import android.content.Context
import android.content.Intent
import android.content.IntentSender
import android.net.Uri
import android.os.Build
import android.os.SystemClock
import android.provider.DocumentsContract
import android.provider.MediaStore
import android.util.Log
import android.webkit.MimeTypeMap
import androidx.documentfile.provider.DocumentFile
import java.io.IOException

/** Blocking storage operations. Call only from the transfer worker. */
internal class TransferEngine(
    context: Context,
    private val cancellation: TransferCancellation,
    private val requestConflict: (String) -> ConflictDecision?,
    private val requestDeletePermission: (IntentSender) -> Boolean
) {
    private val context = context.applicationContext

    fun execute(
        operation: TransferOperation,
        items: List<TransferFile>,
        treeUri: Uri?,
        onProgress: (TransferProgress) -> Unit
    ): TransferResult {
        var progress = TransferProgress(total = items.size)
        var failed = false
        try {
            if (cancellation.isCancelled) return TransferResult(operation, progress, cancelled = true)
            val root = if (operation == TransferOperation.DELETE) null else {
                val destination = requireNotNull(treeUri)
                try {
                    context.contentResolver.takePersistableUriPermission(
                        destination,
                        Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                    )
                } catch (_: SecurityException) {
                    // A temporary grant can still be used for this operation.
                }
                DocumentFile.fromTreeUri(context, destination)?.takeIf { it.isDirectory }
                    ?: throw IOException("Transfer destination is not a directory")
            }
            val targetVolumePath = treeUri?.let(::queryTreeVolumePath)
            if (operation != TransferOperation.DELETE) {
                var totalBytes = 0L
                for (item in items) {
                    if (cancellation.isCancelled) break
                    totalBytes += querySizeBytes(Uri.parse(item.uri)).coerceAtLeast(0L)
                }
                progress = progress.copy(totalBytes = totalBytes)
            }
            onProgress(progress)
            val conflictState = ConflictDecisionState()
            var lastUpdate = 0L
            for (item in items) {
                if (cancellation.isCancelled) break
                progress = progress.copy(currentFileName = item.title)
                onProgress(progress)
                val outcome = try {
                    val source = Uri.parse(item.uri)
                    if (operation == TransferOperation.DELETE) {
                        if (deleteSourceUri(source)) Outcome.SUCCESS else Outcome.FAILURE
                    } else {
                        transferFile(
                            source, item.title, requireNotNull(root), targetVolumePath,
                            operation, conflictState
                        ) { bytes ->
                            progress = progress.copy(copiedBytes = progress.copiedBytes + bytes)
                            val now = SystemClock.elapsedRealtime()
                            if (now - lastUpdate >= PROGRESS_UPDATE_MS) {
                                lastUpdate = now
                                onProgress(progress)
                            }
                        }
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Transfer item failed", e)
                    Outcome.FAILURE
                }
                if (cancellation.isCancelled) break
                progress = progress.copy(
                    completed = progress.completed + 1,
                    successCount = progress.successCount + if (outcome == Outcome.SUCCESS) 1 else 0,
                    failCount = progress.failCount + if (outcome == Outcome.FAILURE) 1 else 0,
                    skipCount = progress.skipCount + if (outcome == Outcome.SKIPPED) 1 else 0
                )
                onProgress(progress)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Transfer preparation failed", e)
            failed = true
        }
        return TransferResult(operation, progress, cancellation.isCancelled, failed)
    }

    private fun transferFile(
        source: Uri,
        title: String,
        root: DocumentFile,
        targetVolumePath: VolumePath?,
        operation: TransferOperation,
        conflictState: ConflictDecisionState,
        onBytesCopied: (Long) -> Unit
    ): Outcome {
        if (targetVolumePath != null && isSameDirectoryMove(source, targetVolumePath)) {
            return Outcome.SKIPPED
        }
        val name = queryDisplayName(source) ?: title
        val mimeType = resolveMimeType(source, name)
        val resolution = resolveTargetFile(root, name, conflictState) ?: return Outcome.SKIPPED
        if (cancellation.isCancelled) return Outcome.SKIPPED
        val target = resolution.reusableTarget
            ?: createTargetDocumentFile(root, mimeType, resolution.targetName)
            ?: return Outcome.FAILURE
        if (!copyUriToDocument(source, target, onBytesCopied)) {
            deleteDocumentFile(target)
            return Outcome.FAILURE
        }
        if (operation == TransferOperation.MOVE && !cancellation.isCancelled && !deleteSourceUri(source)) {
            return Outcome.FAILURE
        }
        return Outcome.SUCCESS
    }

    private enum class Outcome { SUCCESS, FAILURE, SKIPPED }

    private fun queryDisplayName(uri: Uri): String? {
        val projection = arrayOf(MediaStore.MediaColumns.DISPLAY_NAME)
        context.contentResolver.query(uri, projection, null, null, null)?.use { cursor ->
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
            if (cancellation.isCancelled) return candidate
            index++
            candidate = "$stem ($index)$ext"
        }
        return candidate
    }

    private fun copyUriToDocument(
        sourceUri: Uri,
        target: DocumentFile,
        onBytesCopied: (Long) -> Unit
    ): Boolean {
        if (cancellation.isCancelled) return false
        return try {
            context.contentResolver.openInputStream(sourceUri)?.use { input ->
                openOutputStreamForOverwrite(target.uri)?.use { output ->
                    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                    var read = input.read(buffer)
                    while (read >= 0) {
                        if (cancellation.isCancelled) {
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

    private fun openOutputStreamForOverwrite(uri: Uri): java.io.OutputStream? {
        val resolver = context.contentResolver
        val modes = arrayOf("rwt", "wt", "w")
        for (mode in modes) {
            try {
                val output = resolver.openOutputStream(uri, mode)
                if (output != null) {
                    return output
                }
            } catch (_: Exception) {
            }
        }
        return try {
            resolver.openOutputStream(uri)
        } catch (_: Exception) {
            null
        }
    }

    private fun deleteDocumentFile(file: DocumentFile): Boolean {
        repeat(DELETE_RETRY_COUNT) { attempt ->
            val deleted = try {
                file.delete()
            } catch (_: Exception) {
                false
            }
            if (deleted) {
                return true
            }
            if (attempt < DELETE_RETRY_COUNT - 1) {
                try {
                    Thread.sleep(DELETE_RETRY_DELAY_MS)
                } catch (_: InterruptedException) {
                    Thread.currentThread().interrupt()
                    return false
                }
            }
        }
        return false
    }

    private fun resolveMimeType(sourceUri: Uri, displayName: String): String {
        val direct = context.contentResolver.getType(sourceUri)
        if (!direct.isNullOrEmpty() && !direct.endsWith("/*")) {
            return direct
        }
        val byName = guessMimeTypeFromName(displayName)
        if (!byName.isNullOrEmpty()) {
            return byName
        }
        return if (!direct.isNullOrEmpty()) {
            direct
        } else {
            "application/octet-stream"
        }
    }

    private fun guessMimeTypeFromName(displayName: String): String? {
        val ext = displayName.substringAfterLast('.', "").lowercase()
        if (ext.isEmpty()) {
            return null
        }
        return MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext)
    }

    private fun createTargetDocumentFile(
        parent: DocumentFile,
        mimeType: String,
        targetName: String
    ): DocumentFile? {
        val nameParts = parseTargetName(targetName)
        val knownUris = parent.listFiles().map { it.uri.toString() }.toHashSet()
        repeat(CREATE_RETRY_COUNT) { attempt ->
            if (cancellation.isCancelled) return null
            tryCreateWithDocumentFile(parent, mimeType, nameParts)?.let { return it }
            tryCreateWithDocumentsContract(parent, mimeType, nameParts)?.let { return it }
            findCreatedCandidate(parent, nameParts, knownUris)?.let { return it }
            if (attempt < CREATE_RETRY_COUNT - 1) {
                try {
                    Thread.sleep(CREATE_RETRY_DELAY_MS)
                } catch (_: InterruptedException) {
                    Thread.currentThread().interrupt()
                    return null
                }
            }
        }
        return null
    }

    private fun tryCreateWithDocumentFile(
        parent: DocumentFile,
        mimeType: String,
        nameParts: TargetNameParts
    ): DocumentFile? {
        for (createName in createNameCandidates(nameParts)) {
            if (cancellation.isCancelled) return null
            try {
                val created = parent.createFile(mimeType, createName)
                if (created != null) {
                    normalizeCreatedFileName(parent, created, nameParts)?.let { return it }
                    return created
                }
            } catch (_: Exception) {
            }
        }
        return null
    }

    private fun tryCreateWithDocumentsContract(
        parent: DocumentFile,
        mimeType: String,
        nameParts: TargetNameParts
    ): DocumentFile? {
        val parentDocumentUri = buildParentDocumentUri(parent.uri)
        for (createName in createNameCandidates(nameParts)) {
            if (cancellation.isCancelled) return null
            try {
                val createdUri = DocumentsContract.createDocument(
                    context.contentResolver,
                    parentDocumentUri,
                    mimeType,
                    createName
                ) ?: continue
                DocumentFile.fromSingleUri(context, createdUri)?.let { created ->
                    normalizeCreatedFileName(parent, created, nameParts)?.let { return it }
                    return created
                }
            } catch (_: Exception) {
            }
        }
        return null
    }

    private fun buildParentDocumentUri(parentUri: Uri): Uri {
        return try {
            val treeDocumentId = DocumentsContract.getTreeDocumentId(parentUri)
            DocumentsContract.buildDocumentUriUsingTree(parentUri, treeDocumentId)
        } catch (_: Exception) {
            parentUri
        }
    }

    private fun normalizeCreatedFileName(
        parent: DocumentFile,
        created: DocumentFile,
        nameParts: TargetNameParts
    ): DocumentFile? {
        val createdName = created.name ?: return null
        if (createdName == nameParts.targetName) {
            return created
        }
        if (nameParts.hasExtension && createdName == nameParts.baseName) {
            val renamed = try {
                created.renameTo(nameParts.targetName)
            } catch (_: Exception) {
                false
            }
            if (renamed) {
                parent.findFile(nameParts.targetName)?.let { return it }
                return created
            }
        }
        return null
    }

    private fun findCreatedCandidate(
        parent: DocumentFile,
        nameParts: TargetNameParts,
        knownUris: Set<String>
    ): DocumentFile? {
        val expectedSuffix = ".${nameParts.extensionPart}"
        val prefix = "${nameParts.baseName} ("
        for (child in parent.listFiles()) {
            if (knownUris.contains(child.uri.toString())) {
                continue
            }
            val childName = child.name ?: continue
            if (childName == nameParts.targetName) {
                return child
            }
            if (!nameParts.hasExtension && childName == nameParts.baseName) {
                return child
            }
            if (childName.startsWith(prefix) && childName.endsWith(expectedSuffix)) {
                return child
            }
        }
        return null
    }

    private fun createNameCandidates(nameParts: TargetNameParts): List<String> {
        val candidates = LinkedHashSet<String>()
        if (nameParts.hasExtension && nameParts.baseName.isNotEmpty()) {
            candidates.add(nameParts.baseName)
        }
        candidates.add(nameParts.targetName)

        val fallbackBase = stripTrailingCounter(nameParts.baseName)
        val extSuffix = if (nameParts.hasExtension && nameParts.extensionPart.isNotEmpty()) {
            ".${nameParts.extensionPart}"
        } else {
            ""
        }
        for (index in 1..CREATE_NAME_SUFFIX_MAX) {
            candidates.add("$fallbackBase ($index)$extSuffix")
        }
        candidates.add("$fallbackBase (${System.currentTimeMillis()})$extSuffix")
        return candidates.toList()
    }

    private fun stripTrailingCounter(baseName: String): String {
        if (baseName.isEmpty()) {
            return "copied_file"
        }
        val pattern = Regex("""^(.*)\s\(\d+\)$""")
        val match = pattern.matchEntire(baseName)
        val stripped = match?.groupValues?.getOrNull(1)?.trim()
        return if (!stripped.isNullOrEmpty()) {
            stripped
        } else {
            baseName
        }
    }

    private fun parseTargetName(targetName: String): TargetNameParts {
        var safeTarget = targetName.trim().replace('/', '_').replace('\\', '_')
        if (safeTarget.isEmpty()) {
            safeTarget = "copied_file"
        }
        val dotIndex = safeTarget.lastIndexOf('.')
        if (dotIndex <= 0 || dotIndex == safeTarget.lastIndex) {
            return TargetNameParts(
                targetName = safeTarget,
                baseName = safeTarget,
                extensionPart = "",
                hasExtension = false
            )
        }
        return TargetNameParts(
            targetName = safeTarget,
            baseName = safeTarget.substring(0, dotIndex),
            extensionPart = safeTarget.substring(dotIndex + 1),
            hasExtension = true
        )
    }

    private data class TargetNameParts(
        val targetName: String,
        val baseName: String,
        val extensionPart: String,
        val hasExtension: Boolean
    )

    private fun querySizeBytes(uri: Uri): Long {
        val projection = arrayOf(MediaStore.MediaColumns.SIZE)
        context.contentResolver.query(uri, projection, null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) {
                val sizeCol = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.SIZE)
                return cursor.getLong(sizeCol)
            }
        }
        return 0L
    }

    private fun deleteSourceUri(uri: Uri): Boolean {
        if (cancellation.isCancelled) return false
        try {
            return context.contentResolver.delete(uri, null, null) > 0
        } catch (e: SecurityException) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q &&
                e is android.app.RecoverableSecurityException
            ) {
                val granted = requestDeletePermission(e.userAction.actionIntent.intentSender)
                if (granted && !cancellation.isCancelled) {
                    return try {
                        context.contentResolver.delete(uri, null, null) > 0
                    } catch (retry: Exception) {
                        false
                    }
                }
            }
            return false
        } catch (e: Exception) {
            return false
        }
    }

    private data class ConflictResolution(
        val targetName: String,
        val reusableTarget: DocumentFile? = null
    )

    private data class ConflictDecisionState(var applyAllChoice: ConflictChoice? = null)

    private fun resolveTargetFile(
        parent: DocumentFile,
        displayName: String,
        conflictState: ConflictDecisionState
    ): ConflictResolution? {
        val existing = parent.findFile(displayName)
        if (existing == null) {
            return ConflictResolution(displayName)
        }
        if (cancellation.isCancelled) {
            return null
        }
        val choice = conflictState.applyAllChoice ?: run {
            val decision = requestConflict(displayName) ?: return null
            if (decision.applyAll) {
                conflictState.applyAllChoice = decision.choice
            }
            decision.choice
        }
        if (cancellation.isCancelled) return null
        return when (choice) {
            ConflictChoice.OVERWRITE -> {
                val deleted = deleteDocumentFile(existing)
                if (deleted) {
                    ConflictResolution(displayName)
                } else {
                    // Some providers are slow or report delete failure; overwrite in-place instead.
                    ConflictResolution(
                        targetName = displayName,
                        reusableTarget = existing
                    )
                }
            }
            ConflictChoice.RENAME -> ConflictResolution(buildUniqueName(parent, displayName))
            ConflictChoice.SKIP -> null
        }
    }

    private data class VolumePath(
        val volumeName: String,
        val relativePath: String
    )

    fun buildInitialCopyTreeUri(items: List<TransferFile>): Uri? {
        val source = items.firstOrNull() ?: return null
        val srcUri = Uri.parse(source.uri)
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
        context.contentResolver.query(uri, projection, null, null, null)?.use { cursor ->
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

    private fun queryTreeVolumePath(treeUri: Uri): VolumePath? {
        val documentId = try {
            DocumentsContract.getTreeDocumentId(treeUri)
        } catch (_: Exception) {
            try {
                DocumentsContract.getDocumentId(treeUri)
            } catch (_: Exception) {
                null
            }
        } ?: return null
        val separatorIndex = documentId.indexOf(':')
        val treeVolumeName = if (separatorIndex >= 0) {
            documentId.substring(0, separatorIndex)
        } else {
            documentId
        }
        val treeRelativePath = if (separatorIndex >= 0 && separatorIndex + 1 < documentId.length) {
            documentId.substring(separatorIndex + 1)
        } else {
            ""
        }
        return VolumePath(
            volumeName = resolveMediaStoreVolumeName(treeVolumeName),
            relativePath = treeRelativePath
        )
    }

    private fun isSameDirectoryMove(sourceUri: Uri, targetDirectory: VolumePath): Boolean {
        val sourcePath = queryVolumePath(sourceUri) ?: return false
        if (!sourcePath.volumeName.equals(targetDirectory.volumeName, ignoreCase = true)) {
            return false
        }
        val sourceRelative = normalizeRelativeDocPath(sourcePath.relativePath)
        val targetRelative = normalizeRelativeDocPath(targetDirectory.relativePath)
        return sourceRelative.equals(targetRelative, ignoreCase = true)
    }

    private fun resolveMediaStoreVolumeName(documentVolumeName: String): String {
        return when (documentVolumeName.lowercase()) {
            "primary" -> MediaStore.VOLUME_EXTERNAL_PRIMARY
            else -> documentVolumeName
        }
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

    private companion object {
        const val TAG = "TransferEngine"
        const val EXTERNAL_STORAGE_AUTHORITY = "com.android.externalstorage.documents"
        const val PROGRESS_UPDATE_MS = 200L
        const val DELETE_RETRY_COUNT = 3
        const val DELETE_RETRY_DELAY_MS = 80L
        const val CREATE_RETRY_COUNT = 3
        const val CREATE_RETRY_DELAY_MS = 120L
        const val CREATE_NAME_SUFFIX_MAX = 12
    }
}
