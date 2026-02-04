package com.nshell.nsplayer.ui

data class DisplayItem(
    val type: Type,
    val title: String,
    val subtitle: String?,
    val indentLevel: Int,
    val bucketId: String? = null,
    val durationMs: Long = 0L,
    val width: Int = 0,
    val height: Int = 0,
    val contentUri: String? = null
) {
    enum class Type {
        FOLDER,
        HIERARCHY,
        VIDEO
    }

    constructor(type: Type, title: String, subtitle: String?, indentLevel: Int) : this(
        type,
        title,
        subtitle,
        indentLevel,
        null,
        0L,
        0,
        0,
        null
    )

    constructor(
        type: Type,
        title: String,
        subtitle: String?,
        indentLevel: Int,
        bucketId: String?
    ) : this(
        type,
        title,
        subtitle,
        indentLevel,
        bucketId,
        0L,
        0,
        0,
        null
    )

    constructor(
        type: Type,
        title: String,
        subtitle: String?,
        indentLevel: Int,
        durationMs: Long,
        width: Int,
        height: Int,
        contentUri: String?
    ) : this(
        type,
        title,
        subtitle,
        indentLevel,
        null,
        durationMs,
        width,
        height,
        contentUri
    )
}
