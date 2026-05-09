package com.nshell.nsplayer.ui.main

import android.text.Editable
import android.text.TextWatcher
import android.view.KeyEvent
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import androidx.core.content.getSystemService
import androidx.recyclerview.widget.DividerItemDecoration
import androidx.recyclerview.widget.LinearLayoutManager
import com.nshell.nsplayer.R
import kotlin.math.min

private const val SEARCH_PREVIEW_VISIBLE_COUNT = 5
private const val SEARCH_PREVIEW_FETCH_LIMIT = 20
private const val SEARCH_PREVIEW_DEBOUNCE_MS = 180L

internal fun MainActivity.setupSearchUi() {
    searchPreviewAdapter = SearchPreviewAdapter { item ->
        setSearchInputSilently(item.title)
        submitSearchQuery(item.title)
    }
    searchPreviewList.layoutManager = LinearLayoutManager(this)
    searchPreviewList.adapter = searchPreviewAdapter
    searchPreviewList.addItemDecoration(
        DividerItemDecoration(this, DividerItemDecoration.VERTICAL)
    )

    searchButton.setOnClickListener { enterSearchMode() }
    searchExitButton.setOnClickListener { exitSearchMode() }

    searchInput.setOnEditorActionListener { _, actionId, event ->
        val isSearchAction = actionId == EditorInfo.IME_ACTION_SEARCH
        val isEnterKey =
            event?.keyCode == KeyEvent.KEYCODE_ENTER && event.action == KeyEvent.ACTION_DOWN
        if (isSearchAction || isEnterKey) {
            submitSearchQuery()
            true
        } else {
            false
        }
    }

    searchInput.addTextChangedListener(object : TextWatcher {
        override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit

        override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit

        override fun afterTextChanged(s: Editable?) {
            if (suppressSearchTextChange) {
                return
            }
            onSearchTextChanged(s?.toString().orEmpty())
        }
    })
}

internal fun MainActivity.enterSearchMode() {
    if (isSearchMode) {
        return
    }
    if (selectionController.isSelectionMode()) {
        selectionController.clearSelection()
    }
    latestBrowseItems = viewModel.getItems().value
    committedSearchQuery = ""
    isSearchMode = true
    isShowingSearchResults = false
    setSearchInputSilently("")
    hidePreviewList()
    updateHeaderState()
    searchInput.requestFocus()
    showKeyboard(searchInput)
}

internal fun MainActivity.exitSearchMode(): Boolean {
    if (!isSearchMode) {
        return false
    }
    previewDebounceRunnable?.let { searchUiHandler.removeCallbacks(it) }
    previewDebounceRunnable = null
    searchRequestCounter.incrementAndGet()
    setSearchInputSilently("")
    hidePreviewList()
    committedSearchQuery = ""
    isShowingSearchResults = false
    isSearchMode = false
    hideKeyboard(searchInput)
    updateHeaderState()
    restoreBrowseAfterSearch()
    return true
}

internal fun MainActivity.submitSearchQuery(sourceQuery: String? = null) {
    if (!isSearchMode) {
        return
    }
    val query = (sourceQuery ?: searchInput.text?.toString().orEmpty()).trim()
    previewDebounceRunnable?.let { searchUiHandler.removeCallbacks(it) }
    previewDebounceRunnable = null

    if (query.isEmpty()) {
        committedSearchQuery = ""
        isShowingSearchResults = false
        hidePreviewList()
        restoreBrowseAfterSearch()
        return
    }
    if (!hasVideoPermission()) {
        requestMediaPermissions()
        return
    }

    selectionController.clearSelection()
    hidePreviewList()
    hideKeyboard(searchInput)
    committedSearchQuery = query
    isShowingSearchResults = true
    val requestId = searchRequestCounter.incrementAndGet()
    val current = viewModel.getState().value ?: browserState

    searchExecutor.execute {
        val results = searchRepository.searchVideos(
            query = query,
            sortMode = current.sortMode,
            sortOrder = current.sortOrder,
            resolver = contentResolver,
            nomediaEnabled = current.nomediaEnabled,
            searchFoldersUseAll = current.searchFoldersUseAll,
            searchFolders = current.searchFolders
        )
        runOnUiThread {
            if (!isSearchMode || !isShowingSearchResults) {
                return@runOnUiThread
            }
            if (searchRequestCounter.get() != requestId) {
                return@runOnUiThread
            }
            if (committedSearchQuery != query) {
                return@runOnUiThread
            }
            list.layoutManager = LinearLayoutManager(this)
            adapter.setVideoDisplayMode(VideoDisplayMode.LIST)
            adapter.submit(results)
            emptyText.text = getString(R.string.search_empty)
            emptyText.visibility = if (results.isEmpty()) View.VISIBLE else View.GONE
            refreshLayout.isRefreshing = false
        }
    }
}

private fun MainActivity.onSearchTextChanged(rawQuery: String) {
    if (!isSearchMode) {
        return
    }
    val query = rawQuery.trim()
    if (isShowingSearchResults) {
        isShowingSearchResults = false
        committedSearchQuery = ""
        restoreBrowseAfterSearch()
    }
    previewDebounceRunnable?.let { searchUiHandler.removeCallbacks(it) }
    previewDebounceRunnable = null

    if (query.isEmpty()) {
        hidePreviewList()
        return
    }
    if (!hasVideoPermission()) {
        hidePreviewList()
        return
    }

    val runnable = Runnable { requestSearchPreview(query) }
    previewDebounceRunnable = runnable
    searchUiHandler.postDelayed(runnable, SEARCH_PREVIEW_DEBOUNCE_MS)
}

private fun MainActivity.requestSearchPreview(query: String) {
    if (!isSearchMode || query.isBlank()) {
        hidePreviewList()
        return
    }
    val requestId = searchRequestCounter.incrementAndGet()
    val current = viewModel.getState().value ?: browserState

    searchExecutor.execute {
        val previewItems = searchRepository.searchVideos(
            query = query,
            sortMode = current.sortMode,
            sortOrder = current.sortOrder,
            resolver = contentResolver,
            nomediaEnabled = current.nomediaEnabled,
            searchFoldersUseAll = current.searchFoldersUseAll,
            searchFolders = current.searchFolders,
            limit = SEARCH_PREVIEW_FETCH_LIMIT
        )
        runOnUiThread {
            if (!isSearchMode || isShowingSearchResults) {
                return@runOnUiThread
            }
            if (searchRequestCounter.get() != requestId) {
                return@runOnUiThread
            }
            renderSearchPreview(previewItems)
        }
    }
}

private fun MainActivity.renderSearchPreview(items: List<DisplayItem>) {
    if (items.isEmpty()) {
        hidePreviewList()
        return
    }
    searchPreviewAdapter.submitList(items)
    val itemHeight = resources.getDimensionPixelSize(R.dimen.search_preview_item_height)
    val visibleCount = min(items.size, SEARCH_PREVIEW_VISIBLE_COUNT)
    val targetHeight = itemHeight * visibleCount
    val params = searchPreviewList.layoutParams
    if (params.height != targetHeight) {
        params.height = targetHeight
        searchPreviewList.layoutParams = params
    }
    searchPreviewList.visibility = View.VISIBLE
}

internal fun MainActivity.hidePreviewList() {
    searchPreviewAdapter.submitList(emptyList())
    searchPreviewList.visibility = View.GONE
}

internal fun MainActivity.restoreBrowseAfterSearch() {
    val browseItems = latestBrowseItems
    if (browseItems == null) {
        loadIfPermitted(useCache = true)
        return
    }
    if (!isSearchMode) {
        applyVideoDisplayMode()
    }
    adapter.submit(browseItems) {
        restoreTransientUiStateIfNeeded()
    }
    emptyText.text = getString(R.string.empty_state)
    emptyText.visibility = if (browseItems.isEmpty()) View.VISIBLE else View.GONE
}

private fun MainActivity.setSearchInputSilently(text: String) {
    suppressSearchTextChange = true
    searchInput.setText(text)
    searchInput.setSelection(searchInput.text?.length ?: 0)
    suppressSearchTextChange = false
}

private fun MainActivity.showKeyboard(view: View) {
    val imm = getSystemService<InputMethodManager>() ?: return
    view.post {
        view.requestFocus()
        imm.showSoftInput(view, InputMethodManager.SHOW_IMPLICIT)
    }
}

private fun MainActivity.hideKeyboard(view: View) {
    val imm = getSystemService<InputMethodManager>() ?: return
    imm.hideSoftInputFromWindow(view.windowToken, 0)
}
