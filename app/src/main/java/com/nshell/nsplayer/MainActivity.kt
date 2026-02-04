package com.nshell.nsplayer

import android.Manifest
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.RadioGroup
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.ViewModelProvider
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.nshell.nsplayer.ui.DisplayItem
import com.nshell.nsplayer.ui.VideoBrowserViewModel
import com.nshell.nsplayer.ui.VideoDisplayMode
import com.nshell.nsplayer.ui.VideoListAdapter
import com.nshell.nsplayer.ui.VideoMode
import com.nshell.nsplayer.ui.VideoSortMode
import com.nshell.nsplayer.ui.VideoSortOrder

class MainActivity : AppCompatActivity() {
    private lateinit var statusText: TextView
    private lateinit var emptyText: TextView
    private lateinit var folderTitle: TextView
    private lateinit var folderHeader: View
    private lateinit var videoDisplayGroup: View
    private lateinit var headerBackButton: View
    private lateinit var titleText: TextView
    private lateinit var selectionBar: View
    private lateinit var selectionAllButton: Button
    private lateinit var selectionMoveButton: Button
    private lateinit var selectionCopyButton: Button
    private lateinit var adapter: VideoListAdapter
    private lateinit var viewModel: VideoBrowserViewModel
    private lateinit var list: RecyclerView
    private var currentMode = VideoMode.FOLDERS
    private var videoDisplayMode = VideoDisplayMode.LIST
    private var sortMode = VideoSortMode.MODIFIED
    private var sortOrder = VideoSortOrder.DESC
    private var inFolderVideos = false
    private var selectedBucketId: String? = null
    private var selectedBucketName: String? = null
    private var hierarchyPath = ""
    private lateinit var preferences: SharedPreferences

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        supportActionBar?.hide()

        statusText = findViewById(R.id.statusText)
        emptyText = findViewById(R.id.emptyText)
        folderTitle = findViewById(R.id.folderTitle)
        folderHeader = findViewById(R.id.folderHeader)
        videoDisplayGroup = findViewById(R.id.videoDisplayGroup)
        headerBackButton = findViewById(R.id.headerBackButton)
        titleText = findViewById(R.id.titleText)
        selectionBar = findViewById(R.id.selectionBar)
        selectionAllButton = findViewById(R.id.selectionAllButton)
        selectionMoveButton = findViewById(R.id.selectionMoveButton)
        selectionCopyButton = findViewById(R.id.selectionCopyButton)

        list = findViewById(R.id.list)
        list.isVerticalScrollBarEnabled = true
        list.isScrollbarFadingEnabled = true
        list.scrollBarDefaultDelayBeforeFade = 600
        list.scrollBarFadeDuration = 350
        list.layoutManager = LinearLayoutManager(this)
        adapter = VideoListAdapter()
        adapter.setOnItemClickListener { onItemSelected(it) }
        adapter.setOnItemOverflowClickListener { showItemBottomSheet(it) }
        adapter.setOnSelectionChangedListener { selectedCount, selectionMode ->
            onSelectionChanged(selectedCount, selectionMode)
        }
        list.adapter = adapter

        selectionAllButton.setOnClickListener {
            if (adapter.isAllSelected()) {
                adapter.clearSelection()
            } else {
                adapter.selectAll()
            }
        }
        selectionMoveButton.setOnClickListener {
            Toast.makeText(this, getString(R.string.action_not_ready), Toast.LENGTH_SHORT).show()
        }
        selectionCopyButton.setOnClickListener {
            Toast.makeText(this, getString(R.string.action_not_ready), Toast.LENGTH_SHORT).show()
        }

        preferences = getSharedPreferences(PREFS, MODE_PRIVATE)
        loadPreferences()

        val settingsButton = findViewById<View>(R.id.settingsButton)
        settingsButton.setOnClickListener { showSettingsDialog(it) }

        val displayGroup = findViewById<RadioGroup>(R.id.videoDisplayGroup)
        displayGroup.check(
            if (videoDisplayMode == VideoDisplayMode.TILE) R.id.videoDisplayTile else R.id.videoDisplayList
        )
        displayGroup.setOnCheckedChangeListener { _, checkedId ->
            videoDisplayMode = if (checkedId == R.id.videoDisplayTile) {
                VideoDisplayMode.TILE
            } else {
                VideoDisplayMode.LIST
            }
            saveDisplayMode()
            applyVideoDisplayMode()
        }

        val backButton = findViewById<Button>(R.id.backButton)
        backButton.setOnClickListener {
            if (currentMode == VideoMode.HIERARCHY) {
                if (hierarchyPath.isNotEmpty()) {
                    hierarchyPath = getParentPath(hierarchyPath)
                    updateHeaderState()
                    loadIfPermitted()
                }
                return@setOnClickListener
            }
            setMode(VideoMode.FOLDERS)
        }

        headerBackButton.setOnClickListener {
            if (currentMode == VideoMode.HIERARCHY && !isHierarchyRoot()) {
                hierarchyPath = getParentPath(hierarchyPath)
                updateHeaderState()
                loadIfPermitted()
                return@setOnClickListener
            }
            if (inFolderVideos) {
                setMode(VideoMode.FOLDERS)
            }
        }

        viewModel = ViewModelProvider(this)[VideoBrowserViewModel::class.java]
        viewModel.getItems().observe(this) { renderItems(it) }
        viewModel.getLoading().observe(this) { renderLoading(it) }

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (adapter.isSelectionMode()) {
                    adapter.clearSelection()
                    return
                }
                if (currentMode == VideoMode.HIERARCHY && !isHierarchyRoot()) {
                    hierarchyPath = getParentPath(hierarchyPath)
                    updateHeaderState()
                    loadIfPermitted()
                    return
                }
                if (inFolderVideos) {
                    setMode(VideoMode.FOLDERS)
                    return
                }
                isEnabled = false
                onBackPressedDispatcher.onBackPressed()
            }
        })

        val topBar = findViewById<View>(R.id.topBar)
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

        updateHeaderState()
        loadIfPermitted()
    }

    private fun loadIfPermitted() {
        if (hasVideoPermission()) {
            when {
                currentMode == VideoMode.HIERARCHY -> viewModel.loadHierarchy(
                    hierarchyPath,
                    sortMode,
                    sortOrder,
                    contentResolver
                )
                inFolderVideos && selectedBucketId != null -> viewModel.loadFolderVideos(
                    selectedBucketId!!,
                    sortMode,
                    sortOrder,
                    contentResolver
                )
                else -> viewModel.load(currentMode, sortMode, sortOrder, contentResolver)
            }
        } else {
            statusText.text = getString(R.string.permission_needed)
            statusText.visibility = View.VISIBLE
            requestVideoPermission()
        }
    }

    private fun hasVideoPermission(): Boolean {
        val permission = if (Build.VERSION.SDK_INT >= 33) {
            Manifest.permission.READ_MEDIA_VIDEO
        } else {
            Manifest.permission.READ_EXTERNAL_STORAGE
        }
        return ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED
    }

    private fun requestVideoPermission() {
        val permissions = if (Build.VERSION.SDK_INT >= 33) {
            arrayOf(Manifest.permission.READ_MEDIA_VIDEO)
        } else {
            arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)
        }
        ActivityCompat.requestPermissions(this, permissions, REQUEST_PERMISSION)
    }

    private fun renderItems(items: List<DisplayItem>?) {
        adapter.submit(items)
        val isEmpty = items.isNullOrEmpty()
        emptyText.visibility = if (isEmpty) View.VISIBLE else View.GONE
    }

    private fun renderLoading(loading: Boolean?) {
        val show = loading != null && loading
        if (show) {
            statusText.text = getString(R.string.status_loading)
            statusText.visibility = View.GONE
            return
        }
        val current = statusText.text
        if (current != null && current.toString() == getString(R.string.status_loading)) {
            statusText.text = ""
            statusText.visibility = View.GONE
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != REQUEST_PERMISSION) {
            return
        }
        if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            loadIfPermitted()
        } else {
            statusText.text = getString(R.string.permission_denied)
            statusText.visibility = View.VISIBLE
        }
    }

    private fun onItemSelected(item: DisplayItem) {
        when (item.type) {
            DisplayItem.Type.FOLDER -> {
                selectedBucketId = item.bucketId
                selectedBucketName = item.title
                inFolderVideos = true
                updateHeaderState()
                loadIfPermitted()
            }
            DisplayItem.Type.HIERARCHY -> {
                hierarchyPath = item.bucketId ?: ""
                updateHeaderState()
                loadIfPermitted()
            }
            DisplayItem.Type.VIDEO -> {
                val uri = item.contentUri
                if (uri.isNullOrEmpty()) {
                    return
                }
                val intent = Intent(this, PlayerActivity::class.java)
                intent.putExtra(PlayerActivity.EXTRA_URI, uri)
                intent.putExtra(PlayerActivity.EXTRA_TITLE, item.title)
                startActivity(intent)
            }
        }
    }

    private fun updateHeaderState() {
        if (inFolderVideos) {
            folderHeader.visibility = View.GONE
            headerBackButton.visibility = View.VISIBLE
            val title = selectedBucketName ?: "Unknown"
            titleText.text = title
            videoDisplayGroup.visibility = View.VISIBLE
            applyVideoDisplayMode()
        } else if (currentMode == VideoMode.HIERARCHY && !isHierarchyRoot()) {
            folderHeader.visibility = View.GONE
            headerBackButton.visibility = View.VISIBLE
            titleText.text = getHierarchyTitle()
            videoDisplayGroup.visibility = View.GONE
            applyVideoDisplayMode()
        } else {
            folderHeader.visibility = View.GONE
            headerBackButton.visibility = View.GONE
            titleText.text = getString(R.string.app_name)
            videoDisplayGroup.visibility = View.GONE
            if (currentMode == VideoMode.VIDEOS || currentMode == VideoMode.HIERARCHY) {
                applyVideoDisplayMode()
            } else {
                list.layoutManager = LinearLayoutManager(this)
                adapter.setVideoDisplayMode(VideoDisplayMode.LIST)
            }
        }
    }

    private fun showSettingsDialog(anchor: View) {
        val content = layoutInflater.inflate(R.layout.popup_settings, null)
        val modeFolders = content.findViewById<TextView>(R.id.settingsModeFolders)
        val modeHierarchy = content.findViewById<TextView>(R.id.settingsModeHierarchy)
        val modeVideos = content.findViewById<TextView>(R.id.settingsModeVideos)
        val displayList = content.findViewById<TextView>(R.id.settingsDisplayList)
        val displayTile = content.findViewById<TextView>(R.id.settingsDisplayTile)
        val sortTitle = content.findViewById<TextView>(R.id.settingsSortTitle)
        val sortModified = content.findViewById<TextView>(R.id.settingsSortModified)
        val sortDuration = content.findViewById<TextView>(R.id.settingsSortDuration)
        val sortAsc = content.findViewById<TextView>(R.id.settingsSortAsc)
        val sortDesc = content.findViewById<TextView>(R.id.settingsSortDesc)
        val cancelButton = content.findViewById<Button>(R.id.settingsCancel)
        val confirmButton = content.findViewById<Button>(R.id.settingsConfirm)
        val defaultColor = modeFolders.currentTextColor
        val selectedColor = getColor(R.color.brand_green)
        var pendingMode = currentMode
        var pendingDisplay = videoDisplayMode
        var pendingSort = sortMode
        var pendingOrder = sortOrder
        updateModeSelectionUI(
            modeFolders,
            modeHierarchy,
            modeVideos,
            pendingMode,
            selectedColor,
            defaultColor
        )
        updateDisplaySelectionUI(
            displayList,
            displayTile,
            pendingDisplay,
            selectedColor,
            defaultColor
        )
        updateSortSelectionUI(
            sortTitle,
            sortModified,
            sortDuration,
            sortAsc,
            sortDesc,
            pendingSort,
            pendingOrder,
            selectedColor,
            defaultColor
        )

        val dialog = AlertDialog.Builder(this)
            .setView(content)
            .create()
        dialog.setCanceledOnTouchOutside(true)

        modeFolders.setOnClickListener {
            pendingMode = VideoMode.FOLDERS
            updateModeSelectionUI(modeFolders, modeHierarchy, modeVideos, pendingMode, selectedColor, defaultColor)
        }
        modeHierarchy.setOnClickListener {
            pendingMode = VideoMode.HIERARCHY
            updateModeSelectionUI(modeFolders, modeHierarchy, modeVideos, pendingMode, selectedColor, defaultColor)
        }
        modeVideos.setOnClickListener {
            pendingMode = VideoMode.VIDEOS
            updateModeSelectionUI(modeFolders, modeHierarchy, modeVideos, pendingMode, selectedColor, defaultColor)
        }

        displayList.setOnClickListener {
            pendingDisplay = VideoDisplayMode.LIST
            updateDisplaySelectionUI(displayList, displayTile, pendingDisplay, selectedColor, defaultColor)
        }
        displayTile.setOnClickListener {
            pendingDisplay = VideoDisplayMode.TILE
            updateDisplaySelectionUI(displayList, displayTile, pendingDisplay, selectedColor, defaultColor)
        }

        sortTitle.setOnClickListener {
            pendingSort = VideoSortMode.TITLE
            updateSortSelectionUI(
                sortTitle,
                sortModified,
                sortDuration,
                sortAsc,
                sortDesc,
                pendingSort,
                pendingOrder,
                selectedColor,
                defaultColor
            )
        }
        sortModified.setOnClickListener {
            pendingSort = VideoSortMode.MODIFIED
            updateSortSelectionUI(
                sortTitle,
                sortModified,
                sortDuration,
                sortAsc,
                sortDesc,
                pendingSort,
                pendingOrder,
                selectedColor,
                defaultColor
            )
        }
        sortDuration.setOnClickListener {
            pendingSort = VideoSortMode.LENGTH
            updateSortSelectionUI(
                sortTitle,
                sortModified,
                sortDuration,
                sortAsc,
                sortDesc,
                pendingSort,
                pendingOrder,
                selectedColor,
                defaultColor
            )
        }
        sortAsc.setOnClickListener {
            pendingOrder = VideoSortOrder.ASC
            updateSortSelectionUI(
                sortTitle,
                sortModified,
                sortDuration,
                sortAsc,
                sortDesc,
                pendingSort,
                pendingOrder,
                selectedColor,
                defaultColor
            )
        }
        sortDesc.setOnClickListener {
            pendingOrder = VideoSortOrder.DESC
            updateSortSelectionUI(
                sortTitle,
                sortModified,
                sortDuration,
                sortAsc,
                sortDesc,
                pendingSort,
                pendingOrder,
                selectedColor,
                defaultColor
            )
        }

        cancelButton.setOnClickListener { dialog.dismiss() }
        confirmButton.setOnClickListener {
            val modeChanged = pendingMode != currentMode
            val displayChanged = pendingDisplay != videoDisplayMode
            val sortChanged = pendingSort != sortMode
            val orderChanged = pendingOrder != sortOrder
            if (displayChanged) {
                videoDisplayMode = pendingDisplay
                saveDisplayMode()
            }
            if (sortChanged || orderChanged) {
                sortMode = pendingSort
                sortOrder = pendingOrder
                saveSortMode()
                saveSortOrder()
            }
            val mainDisplayGroup = findViewById<RadioGroup>(R.id.videoDisplayGroup)
            mainDisplayGroup.check(
                if (videoDisplayMode == VideoDisplayMode.TILE) R.id.videoDisplayTile else R.id.videoDisplayList
            )
            if (modeChanged) {
                setMode(pendingMode)
            } else if (displayChanged) {
                applyVideoDisplayMode()
            }
            if ((sortChanged || orderChanged) && !modeChanged) {
                loadIfPermitted()
            }
            dialog.dismiss()
        }

        dialog.show()
    }

    private fun showItemBottomSheet(item: DisplayItem?) {
        if (item == null) {
            return
        }
        if (adapter.isSelectionMode()) {
            return
        }
        val content = layoutInflater.inflate(R.layout.bottom_sheet_item, null)
        val title = content.findViewById<TextView>(R.id.bottomSheetTitle)
        title.text = item.title
        val renameButton = content.findViewById<Button>(R.id.actionRename)
        val propertiesButton = content.findViewById<Button>(R.id.actionProperties)

        val dialog = BottomSheetDialog(this)
        dialog.setContentView(content)

        renameButton.setOnClickListener {
            Toast.makeText(this, getString(R.string.action_not_ready), Toast.LENGTH_SHORT).show()
            dialog.dismiss()
        }
        propertiesButton.setOnClickListener {
            Toast.makeText(this, getString(R.string.action_not_ready), Toast.LENGTH_SHORT).show()
            dialog.dismiss()
        }

        dialog.show()
    }

    private fun setMode(mode: VideoMode) {
        currentMode = mode
        inFolderVideos = false
        selectedBucketId = null
        selectedBucketName = null
        if (mode == VideoMode.HIERARCHY) {
            hierarchyPath = ""
        }
        saveMode()
        updateHeaderState()
        loadIfPermitted()
    }

    private fun applyVideoDisplayMode() {
        adapter.setVideoDisplayMode(videoDisplayMode)
        list.layoutManager = if (videoDisplayMode == VideoDisplayMode.TILE) {
            GridLayoutManager(this, 2)
        } else {
            LinearLayoutManager(this)
        }
        adapter.notifyDataSetChanged()
    }

    private fun onSelectionChanged(selectedCount: Int, selectionMode: Boolean) {
        selectionBar.visibility = if (selectionMode) View.VISIBLE else View.GONE
    }

    private fun loadPreferences() {
        val modeValue = preferences.getString(KEY_MODE, VideoMode.FOLDERS.name)
        val displayValue = preferences.getString(KEY_DISPLAY, VideoDisplayMode.LIST.name)
        val sortValue = preferences.getString(KEY_SORT, VideoSortMode.MODIFIED.name)
        val sortOrderValue = preferences.getString(KEY_SORT_ORDER, VideoSortOrder.DESC.name)
        currentMode = try {
            VideoMode.valueOf(modeValue ?: VideoMode.FOLDERS.name)
        } catch (_: IllegalArgumentException) {
            VideoMode.FOLDERS
        }
        videoDisplayMode = try {
            VideoDisplayMode.valueOf(displayValue ?: VideoDisplayMode.LIST.name)
        } catch (_: IllegalArgumentException) {
            VideoDisplayMode.LIST
        }
        sortMode = try {
            VideoSortMode.valueOf(sortValue ?: VideoSortMode.MODIFIED.name)
        } catch (_: IllegalArgumentException) {
            VideoSortMode.MODIFIED
        }
        sortOrder = try {
            VideoSortOrder.valueOf(sortOrderValue ?: VideoSortOrder.DESC.name)
        } catch (_: IllegalArgumentException) {
            VideoSortOrder.DESC
        }
    }

    private fun saveMode() {
        preferences.edit().putString(KEY_MODE, currentMode.name).apply()
    }

    private fun saveDisplayMode() {
        preferences.edit().putString(KEY_DISPLAY, videoDisplayMode.name).apply()
    }

    private fun saveSortMode() {
        preferences.edit().putString(KEY_SORT, sortMode.name).apply()
    }

    private fun saveSortOrder() {
        preferences.edit().putString(KEY_SORT_ORDER, sortOrder.name).apply()
    }

    private fun isHierarchyRoot(): Boolean = hierarchyPath.isEmpty()

    private fun getHierarchyTitle(): String {
        if (hierarchyPath.isEmpty()) {
            return "Root"
        }
        var trimmed = hierarchyPath
        if (trimmed.endsWith("/")) {
            trimmed = trimmed.substring(0, trimmed.length - 1)
        }
        val lastSlash = trimmed.lastIndexOf('/')
        return if (lastSlash >= 0 && lastSlash < trimmed.length - 1) {
            trimmed.substring(lastSlash + 1)
        } else {
            if (trimmed.isEmpty()) "Root" else trimmed
        }
    }

    private fun getParentPath(path: String): String {
        if (path.isEmpty()) {
            return ""
        }
        var trimmed = path
        if (trimmed.endsWith("/")) {
            trimmed = trimmed.substring(0, trimmed.length - 1)
        }
        val lastSlash = trimmed.lastIndexOf('/')
        if (lastSlash < 0) {
            return ""
        }
        return trimmed.substring(0, lastSlash + 1)
    }

    private fun updateModeSelectionUI(
        folders: TextView,
        hierarchy: TextView,
        videos: TextView,
        selectedMode: VideoMode,
        selectedColor: Int,
        defaultColor: Int
    ) {
        folders.setTextColor(if (selectedMode == VideoMode.FOLDERS) selectedColor else defaultColor)
        hierarchy.setTextColor(if (selectedMode == VideoMode.HIERARCHY) selectedColor else defaultColor)
        videos.setTextColor(if (selectedMode == VideoMode.VIDEOS) selectedColor else defaultColor)
    }

    private fun updateDisplaySelectionUI(
        list: TextView,
        tile: TextView,
        selectedDisplay: VideoDisplayMode,
        selectedColor: Int,
        defaultColor: Int
    ) {
        list.setTextColor(if (selectedDisplay == VideoDisplayMode.LIST) selectedColor else defaultColor)
        tile.setTextColor(if (selectedDisplay == VideoDisplayMode.TILE) selectedColor else defaultColor)
    }

    private fun updateSortSelectionUI(
        title: TextView,
        modified: TextView,
        duration: TextView,
        asc: TextView,
        desc: TextView,
        selectedSort: VideoSortMode,
        selectedOrder: VideoSortOrder,
        selectedColor: Int,
        defaultColor: Int
    ) {
        val titleSelected = selectedSort == VideoSortMode.TITLE
        val modifiedSelected = selectedSort == VideoSortMode.MODIFIED
        val durationSelected = selectedSort == VideoSortMode.LENGTH
        title.setTextColor(if (titleSelected) selectedColor else defaultColor)
        modified.setTextColor(if (modifiedSelected) selectedColor else defaultColor)
        duration.setTextColor(if (durationSelected) selectedColor else defaultColor)
        if (titleSelected) {
            asc.text = getString(R.string.sort_title_asc)
            desc.text = getString(R.string.sort_title_desc)
        } else if (modifiedSelected) {
            asc.text = getString(R.string.sort_modified_asc)
            desc.text = getString(R.string.sort_modified_desc)
        } else {
            asc.text = getString(R.string.sort_length_asc)
            desc.text = getString(R.string.sort_length_desc)
        }
        asc.setTextColor(if (selectedOrder == VideoSortOrder.ASC) selectedColor else defaultColor)
        desc.setTextColor(if (selectedOrder == VideoSortOrder.DESC) selectedColor else defaultColor)
    }

    private fun dpToPx(dp: Int): Int {
        val density = resources.displayMetrics.density
        return Math.round(dp * density)
    }

    companion object {
        private const val REQUEST_PERMISSION = 1001
        private const val PREFS = "nsplayer_prefs"
        private const val KEY_MODE = "video_mode"
        private const val KEY_DISPLAY = "video_display"
        private const val KEY_SORT = "video_sort"
        private const val KEY_SORT_ORDER = "video_sort_order"
    }
}
