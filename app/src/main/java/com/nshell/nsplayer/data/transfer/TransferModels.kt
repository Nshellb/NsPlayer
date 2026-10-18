package com.nshell.nsplayer.data.transfer

internal enum class TransferOperation { COPY, MOVE, DELETE }

internal data class TransferFile(val uri: String, val title: String)

internal enum class ConflictChoice { OVERWRITE, RENAME, SKIP }

internal data class ConflictDecision(val choice: ConflictChoice, val applyAll: Boolean)

internal data class TransferProgress(
    val total: Int,
    val completed: Int = 0,
    val successCount: Int = 0,
    val failCount: Int = 0,
    val skipCount: Int = 0,
    val copiedBytes: Long = 0L,
    val totalBytes: Long = 0L,
    val currentFileName: String = "-"
)

internal data class TransferResult(
    val operation: TransferOperation,
    val progress: TransferProgress,
    val cancelled: Boolean,
    val failed: Boolean = false
)
