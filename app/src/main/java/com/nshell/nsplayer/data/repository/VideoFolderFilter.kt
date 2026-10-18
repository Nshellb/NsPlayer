package com.nshell.nsplayer.data.repository

internal object VideoFolderFilter {
    fun <T> apply(
        entries: MutableList<T>,
        folderIds: Set<String>?,
        folderIdOf: (T) -> String?
    ): MutableList<T> {
        if (folderIds == null) {
            return entries
        }
        if (folderIds.isEmpty() || entries.isEmpty()) {
            return mutableListOf()
        }
        return entries.filter { entry ->
            folderIdOf(entry)?.let(folderIds::contains) == true
        }.toMutableList()
    }
}
