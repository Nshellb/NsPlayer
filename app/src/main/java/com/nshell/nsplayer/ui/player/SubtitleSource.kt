package com.nshell.nsplayer.ui.player

import android.net.Uri

internal data class SubtitleSource(
    val uri: Uri,
    val label: String,
    val mimeType: String,
    val extension: String,
    val nameMatch: SubtitleNameMatch? = null
)
