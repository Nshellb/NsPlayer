package com.nshell.nsplayer.ui.base

import android.content.Context
import android.util.TypedValue
import androidx.annotation.AttrRes
import androidx.core.content.ContextCompat

internal fun Context.themeColor(@AttrRes attribute: Int): Int {
    val value = TypedValue()
    check(theme.resolveAttribute(attribute, value, true)) {
        "Theme attribute 0x${attribute.toString(16)} is not defined"
    }
    return if (value.resourceId != 0) {
        ContextCompat.getColor(this, value.resourceId)
    } else {
        value.data
    }
}
