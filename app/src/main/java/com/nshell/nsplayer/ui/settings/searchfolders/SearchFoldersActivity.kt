package com.nshell.nsplayer.ui.settings.searchfolders

import android.os.Bundle
import android.view.View
import android.widget.TextView
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.ViewModelProvider
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.nshell.nsplayer.R
import com.nshell.nsplayer.data.repository.SearchFolderRepository
import com.nshell.nsplayer.data.settings.SettingsState
import com.nshell.nsplayer.ui.base.BaseActivity
import com.nshell.nsplayer.ui.settings.SettingsViewModel
import android.provider.MediaStore
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.Locale

class SearchFoldersActivity : BaseActivity() {
    private lateinit var settingsViewModel: SettingsViewModel
    private val repository = SearchFolderRepository()
    private val executor: ExecutorService = Executors.newSingleThreadExecutor()
    private lateinit var adapter: SearchFolderAdapter
    private lateinit var list: RecyclerView
    private lateinit var emptyText: TextView
    private lateinit var selectedCountText: TextView
    private lateinit var selectAllButton: TextView
    private lateinit var clearButton: TextView
    private var entries: List<SearchFolderRepository.FolderEntry> = emptyList()
    private var availableIds: Set<String> = emptySet()
    private var selectedIds: MutableSet<String> = mutableSetOf()
    private var useAll: Boolean = true
    private var currentSettings: SettingsState = SettingsState()
    private var rows: List<SearchFolderAdapter.Row> = emptyList()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_search_folders)
        supportActionBar?.hide()

        val backButton = findViewById<View>(R.id.commonBackButton)
        backButton.setOnClickListener { finish() }

        val titleText = findViewById<TextView>(R.id.commonTitleText)
        titleText.text = getString(R.string.search_folders_title)

        list = findViewById(R.id.searchFoldersList)
        emptyText = findViewById(R.id.searchFoldersEmpty)
        selectedCountText = findViewById(R.id.searchFoldersSelectedCount)
        selectAllButton = findViewById(R.id.searchFoldersSelectAll)
        clearButton = findViewById(R.id.searchFoldersClear)

        adapter = SearchFolderAdapter { entry, isChecked ->
            handleToggle(entry, isChecked)
        }
        list.layoutManager = LinearLayoutManager(this)
        list.adapter = adapter

        selectAllButton.setOnClickListener { selectAll() }
        clearButton.setOnClickListener { clearAll() }

        settingsViewModel = ViewModelProvider(this)[SettingsViewModel::class.java]
        settingsViewModel.getSettings().observe(this) { settings ->
            val previousNomedia = currentSettings.nomediaEnabled
            currentSettings = settings
            useAll = settings.searchFoldersUseAll
            refreshSelectionFromSettings()
            if (previousNomedia != settings.nomediaEnabled && entries.isNotEmpty()) {
                loadFolders()
            }
        }
        settingsViewModel.getSettings().value?.let { currentSettings = it }
        useAll = currentSettings.searchFoldersUseAll

        val topBar = findViewById<View>(R.id.commonTopBar)
            ?: findViewById(R.id.searchFoldersTopBarInclude)
        ViewCompat.setOnApplyWindowInsetsListener(topBar) { view, insets ->
            val topInset = insets.getInsets(WindowInsetsCompat.Type.systemBars()).top
            view.setPadding(
                view.paddingLeft,
                topInset + dpToPx(12),
                view.paddingRight,
                view.paddingBottom
            )
            insets
        }

        loadFolders()
    }

    override fun onDestroy() {
        super.onDestroy()
        executor.shutdown()
    }

    private fun dpToPx(dp: Int): Int {
        val density = resources.displayMetrics.density
        return Math.round(dp * density)
    }

    private fun loadFolders() {
        val nomediaEnabled = currentSettings.nomediaEnabled
        executor.execute {
            val result = repository.load(contentResolver, nomediaEnabled)
            runOnUiThread { applyEntries(result) }
        }
    }

    private fun applyEntries(items: List<SearchFolderRepository.FolderEntry>) {
        entries = items
        availableIds = entries.map { it.bucketId }.toSet()
        rows = buildRows(entries)
        syncSelectionFromSettings()
        adapter.submit(rows, selectedIds)
        updateSelectedCount()
        updateEmptyState()
        updateActionState()
    }

    private fun updateEmptyState() {
        val isEmpty = entries.isEmpty()
        emptyText.visibility = if (isEmpty) View.VISIBLE else View.GONE
        list.visibility = if (isEmpty) View.GONE else View.VISIBLE
    }

    private fun updateActionState() {
        val hasItems = entries.isNotEmpty()
        val alpha = if (hasItems) 1f else 0.4f
        selectAllButton.isEnabled = hasItems
        clearButton.isEnabled = hasItems
        selectAllButton.alpha = alpha
        clearButton.alpha = alpha
    }

    private fun refreshSelectionFromSettings() {
        if (entries.isEmpty()) {
            updateSelectedCount()
            return
        }
        syncSelectionFromSettings()
        adapter.updateSelection(selectedIds)
        updateSelectedCount()
    }

    private fun syncSelectionFromSettings() {
        val selected = if (useAll) {
            availableIds
        } else {
            currentSettings.searchFolders.intersect(availableIds)
        }
        selectedIds = selected.toMutableSet()
        if (!useAll && currentSettings.searchFolders.size != selectedIds.size) {
            settingsViewModel.updateSearchFolders(selectedIds, useAll = false)
        }
    }

    private fun updateSelectedCount() {
        val total = entries.size
        val selected = if (useAll) total else selectedIds.size
        selectedCountText.text = getString(
            R.string.search_folders_selected_count,
            selected,
            total
        )
    }

    private fun handleToggle(
        entry: SearchFolderRepository.FolderEntry,
        isChecked: Boolean
    ) {
        if (entries.isEmpty()) {
            return
        }
        if (useAll) {
            if (!isChecked) {
                useAll = false
                selectedIds = availableIds.toMutableSet()
                selectedIds.remove(entry.bucketId)
                settingsViewModel.updateSearchFolders(selectedIds, useAll = false)
            } else {
                return
            }
        } else {
            if (isChecked) {
                selectedIds.add(entry.bucketId)
            } else {
                selectedIds.remove(entry.bucketId)
            }
            if (selectedIds.size == availableIds.size && availableIds.isNotEmpty()) {
                useAll = true
                settingsViewModel.updateSearchFolders(emptySet(), useAll = true)
            } else {
                settingsViewModel.updateSearchFolders(selectedIds, useAll = false)
            }
        }
        adapter.updateSelection(selectedIds)
        updateSelectedCount()
    }

    private fun selectAll() {
        if (entries.isEmpty()) {
            return
        }
        useAll = true
        selectedIds = availableIds.toMutableSet()
        adapter.updateSelection(selectedIds)
        updateSelectedCount()
        settingsViewModel.updateSearchFolders(emptySet(), useAll = true)
    }

    private fun clearAll() {
        useAll = false
        selectedIds.clear()
        adapter.updateSelection(selectedIds)
        updateSelectedCount()
        settingsViewModel.updateSearchFolders(emptySet(), useAll = false)
    }

    private data class TreeNode(
        val name: String,
        val key: String,
        val children: MutableMap<String, TreeNode> = linkedMapOf(),
        var entry: SearchFolderRepository.FolderEntry? = null,
        var totalCount: Int = 0
    )

    private data class VolumeNode(
        val volumeName: String,
        val label: String,
        val children: MutableMap<String, TreeNode> = linkedMapOf(),
        var totalCount: Int = 0
    )

    private fun buildRows(
        entries: List<SearchFolderRepository.FolderEntry>
    ): List<SearchFolderAdapter.Row> {
        if (entries.isEmpty()) {
            return emptyList()
        }
        val roots = mutableMapOf<String, VolumeNode>()
        for (entry in entries) {
            val volume = resolveVolumeName(entry.volumeName)
            val root = roots.getOrPut(volume) {
                VolumeNode(volume, buildVolumeLabel(volume))
            }
            val normalized = normalizePath(entry.relativePath)
            val segments = normalized.trim('/').split('/').filter { it.isNotEmpty() }
            if (segments.isEmpty()) {
                val key = "bucket:${entry.bucketId}"
                val node = root.children.getOrPut(key) { TreeNode(entry.name, key) }
                node.entry = entry
                continue
            }
            var currentMap = root.children
            var node: TreeNode? = null
            val pathBuilder = StringBuilder()
            for (segment in segments) {
                pathBuilder.append(segment).append('/')
                val key = pathBuilder.toString()
                node = currentMap.getOrPut(key) { TreeNode(segment, key) }
                currentMap = node.children
            }
            node?.entry = entry
        }

        roots.values.forEach { root ->
            root.totalCount = root.children.values.sumOf { computeTotals(it) }
        }

        val rows = mutableListOf<SearchFolderAdapter.Row>()
        val sortedRoots = roots.values.sortedBy { it.label.lowercase(Locale.US) }
        for (root in sortedRoots) {
            rows.add(SearchFolderAdapter.Row.Header(root.label, 0, root.totalCount))
            val sortedChildren = root.children.values.sortedBy { it.name.lowercase(Locale.US) }
            for (child in sortedChildren) {
                appendNode(rows, child, 1)
            }
        }
        return rows
    }

    private fun appendNode(
        rows: MutableList<SearchFolderAdapter.Row>,
        node: TreeNode,
        indent: Int
    ) {
        val entry = node.entry
        if (entry != null) {
            rows.add(SearchFolderAdapter.Row.Item(entry, indent))
        } else {
            rows.add(SearchFolderAdapter.Row.Header(node.name, indent, node.totalCount))
        }
        val sortedChildren = node.children.values.sortedBy { it.name.lowercase(Locale.US) }
        for (child in sortedChildren) {
            appendNode(rows, child, indent + 1)
        }
    }

    private fun computeTotals(node: TreeNode): Int {
        var total = node.entry?.count ?: 0
        node.children.values.forEach { child ->
            total += computeTotals(child)
        }
        node.totalCount = total
        return total
    }

    private fun resolveVolumeName(name: String?): String {
        return if (name.isNullOrEmpty()) {
            MediaStore.VOLUME_EXTERNAL_PRIMARY
        } else {
            name
        }
    }

    private fun buildVolumeLabel(volumeName: String?): String {
        val name = resolveVolumeName(volumeName)
        return if (name == MediaStore.VOLUME_EXTERNAL_PRIMARY) {
            getString(R.string.storage_internal)
        } else {
            getString(R.string.storage_external_format, name)
        }
    }

    private fun normalizePath(path: String?): String {
        if (path.isNullOrEmpty()) {
            return ""
        }
        var normalized = path.replace('\\', '/')
        if (!normalized.endsWith("/")) {
            normalized += "/"
        }
        return normalized
    }
}
