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

internal fun MainActivity.setupSearchUi() {
    searchPreviewAdapter = SearchPreviewAdapter { item -> submitSearchQuery(item.title) }
    searchPreviewList.layoutManager = LinearLayoutManager(this)
    searchPreviewList.adapter = searchPreviewAdapter
    searchPreviewList.addItemDecoration(DividerItemDecoration(this, DividerItemDecoration.VERTICAL))
    // SavedStateHandle owns the input; view hierarchy restoration must not replay it as an edit.
    searchInput.isSaveEnabled = false
    searchButton.setOnClickListener { enterSearchMode() }
    searchExitButton.setOnClickListener { exitSearchMode() }
    searchInput.setOnEditorActionListener { _, actionId, event ->
        if (actionId == EditorInfo.IME_ACTION_SEARCH ||
            (event?.keyCode == KeyEvent.KEYCODE_ENTER && event.action == KeyEvent.ACTION_DOWN)
        ) {
            submitSearchQuery()
            true
        } else false
    }
    searchInput.addTextChangedListener(object : TextWatcher {
        override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
        override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
        override fun afterTextChanged(s: Editable?) {
            if (!suppressSearchTextChange) searchViewModel.changeInput(s?.toString().orEmpty())
        }
    })
    var renderedState: VideoSearchState? = null
    searchViewModel.getState().observe(this) { state ->
        renderSearchState(state, renderedState)
        renderedState = state
    }
}

internal fun MainActivity.syncSearchConfiguration() {
    val current = browserState
    searchViewModel.configure(
        VideoSearchOptions(
            current.sortMode, current.sortOrder, current.nomediaEnabled,
            current.searchFoldersUseAll, current.searchFolders
        ),
        hasVideoPermission()
    )
}

internal fun MainActivity.enterSearchMode() {
    if (isSearchMode) return
    selectionController.clearSelection()
    syncSearchConfiguration()
    searchViewModel.enter()
    searchInput.requestFocus()
    showKeyboard(searchInput)
}

internal fun MainActivity.exitSearchMode(): Boolean {
    if (!isSearchMode) return false
    selectionController.clearSelection()
    val exited = searchViewModel.exit()
    hideKeyboard(searchInput)
    return exited
}

internal fun MainActivity.submitSearchQuery(sourceQuery: String? = null) {
    if (!isSearchMode) return
    selectionController.clearSelection()
    syncSearchConfiguration()
    searchViewModel.submit(sourceQuery ?: searchInput.text?.toString().orEmpty())
    hideKeyboard(searchInput)
    if (!hasVideoPermission()) requestMediaPermissions()
}

private fun MainActivity.renderSearchState(state: VideoSearchState, previous: VideoSearchState?) {
    setSearchInputSilently(state.input)
    val modeChanged = previous?.active != state.active
    if (modeChanged) updateHeaderState()
    val surfaceChanged = previous?.showingResults != state.showingResults
    if (state.showingResults) {
        if (surfaceChanged) applyVideoDisplayMode()
        if (surfaceChanged || previous?.results !== state.results) {
            adapter.submit(state.results) { restoreTransientUiStateIfNeeded() }
        }
        emptyText.setText(R.string.search_empty)
        emptyText.visibility = if (!state.loading && state.error == null &&
            state.results?.isEmpty() == true
        ) View.VISIBLE else View.GONE
        refreshLayout.isRefreshing = false
        hidePreviewList()
    } else {
        if (surfaceChanged || modeChanged) {
            applyVideoDisplayMode()
            renderLoadState(viewModel.currentLoadState)
        }
        renderSearchPreview(if (state.active) state.previews else emptyList())
    }
    if (state.active && !state.showingResults && state.input.isBlank() &&
        previous?.input?.isNotBlank() == true
    ) {
        renderLoadState(viewModel.currentLoadState)
    }
    if (state.active) refreshLayout.isRefreshing = false
    renderSearchStatus(state)
}

internal fun MainActivity.renderSearchStatus(state: VideoSearchState) {
    if (!state.active || (!state.showingResults && state.input.isBlank())) return
    statusText.setOnClickListener(null)
    statusText.isClickable = false
    statusText.visibility = View.GONE
    when {
        state.error != null -> {
            statusText.setText(
                if (state.error is SecurityException) R.string.permission_needed
                else R.string.search_load_failed
            )
            statusText.visibility = View.VISIBLE
            statusText.setOnClickListener {
                syncSearchConfiguration()
                if (hasVideoPermission()) searchViewModel.refresh() else requestMediaPermissions()
            }
        }
        state.showingResults && state.loading && state.results == null -> {
            statusText.setText(R.string.status_loading)
            statusText.visibility = View.VISIBLE
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
    val targetHeight = itemHeight * min(items.size, SEARCH_PREVIEW_VISIBLE_COUNT)
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

private fun MainActivity.setSearchInputSilently(text: String) {
    if (searchInput.text?.toString() == text) return
    suppressSearchTextChange = true
    try {
        searchInput.setText(text)
        searchInput.setSelection(text.length)
    } finally {
        suppressSearchTextChange = false
    }
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
