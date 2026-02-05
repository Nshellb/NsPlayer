package com.nshell.nsplayer.ui.main

import android.Manifest
import android.app.Activity
import android.app.RecoverableSecurityException
import android.content.ContentValues
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.text.format.Formatter
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.ImageView
import android.widget.RadioGroup
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
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
import com.nshell.nsplayer.R
import com.nshell.nsplayer.data.settings.SettingsState
import com.nshell.nsplayer.ui.player.PlayerActivity
import com.nshell.nsplayer.ui.settings.SettingsViewModel
import com.nshell.nsplayer.ui.settings.advanced.AdvancedSettingsActivity
import androidx.documentfile.provider.DocumentFile
import java.text.DateFormat
import java.util.Date
import java.util.Locale

class MainActivity : AppCompatActivity() {
    private lateinit var statusText: TextView
    private lateinit var emptyText: TextView
    private lateinit var folderTitle: TextView
    private lateinit var folderHeader: View
    private lateinit var headerBackButton: View
    private lateinit var titleText: TextView
    private lateinit var selectionBar: View
    private lateinit var selectionAllButton: Button
    private lateinit var selectionMoveButton: Button
    private lateinit var selectionCopyButton: Button
    private lateinit var selectionDeleteButton: Button
    private lateinit var adapter: VideoListAdapter
    private lateinit var viewModel: VideoBrowserViewModel
    private lateinit var settingsViewModel: SettingsViewModel
    private lateinit var selectionController: SelectionController
    private lateinit var transferController: TransferController
    private lateinit var list: RecyclerView
    private var browserState = VideoBrowserState()
    private var initialSettingsApplied = false
    private var pendingRename: RenameRequest? = null
    private var pendingFolderRename: FolderRenameRequest? = null
    private val preferences by lazy { getSharedPreferences(PREFS, MODE_PRIVATE) }

    private val copyDestinationLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri ->
        transferController.onCopyDestinationPicked(uri)
    }

    private val deletePermissionLauncher = registerForActivityResult(
        ActivityResultContracts.StartIntentSenderForResult()
    ) { result ->
        transferController.onDeletePermissionResult(result.resultCode)
    }

    private val renamePermissionLauncher = registerForActivityResult(
        ActivityResultContracts.StartIntentSenderForResult()
    ) { result ->
        val pending = pendingRename
        pendingRename = null
        if (pending == null) {
            return@registerForActivityResult
        }
        if (result.resultCode == Activity.RESULT_OK) {
            renameMediaItem(pending.item, pending.newName, allowPermissionRequest = false)
        } else {
            Toast.makeText(this, getString(R.string.rename_permission_denied), Toast.LENGTH_SHORT).show()
        }
    }

    private val folderRenameTreeLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri ->
        val pending = pendingFolderRename
        pendingFolderRename = null
        if (pending == null) {
            return@registerForActivityResult
        }
        if (uri == null) {
            Toast.makeText(this, getString(R.string.rename_folder_pick_cancelled), Toast.LENGTH_SHORT).show()
            return@registerForActivityResult
        }
        preferences.edit().putString(KEY_FOLDER_RENAME_TREE_URI, uri.toString()).apply()
        handleFolderRenameTree(uri, pending, showToasts = true)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        supportActionBar?.hide()

        statusText = findViewById(R.id.statusText)
        emptyText = findViewById(R.id.emptyText)
        folderTitle = findViewById(R.id.folderTitle)
        folderHeader = findViewById(R.id.folderHeader)
        headerBackButton = findViewById(R.id.headerBackButton)
        titleText = findViewById(R.id.titleText)
        selectionBar = findViewById(R.id.selectionBar)
        selectionAllButton = findViewById(R.id.selectionAllButton)
        selectionMoveButton = findViewById(R.id.selectionMoveButton)
        selectionCopyButton = findViewById(R.id.selectionCopyButton)
        selectionDeleteButton = findViewById(R.id.selectionDeleteButton)

        list = findViewById(R.id.list)
        list.isVerticalScrollBarEnabled = true
        list.isScrollbarFadingEnabled = true
        list.scrollBarDefaultDelayBeforeFade = 600
        list.scrollBarFadeDuration = 350
        list.layoutManager = LinearLayoutManager(this)
        adapter = VideoListAdapter()
        selectionController = SelectionController(selectionBar, selectionAllButton, adapter)
        selectionController.bind()
        adapter.setOnItemClickListener { onItemSelected(it) }
        adapter.setOnItemOverflowClickListener { showItemBottomSheet(it) }
        adapter.setOnSelectionChangedListener { _, selectionMode ->
            selectionController.onSelectionChanged(selectionMode)
        }
        list.adapter = adapter

        viewModel = ViewModelProvider(this)[VideoBrowserViewModel::class.java]
        settingsViewModel = ViewModelProvider(this)[SettingsViewModel::class.java]
        viewModel.getItems().observe(this) { renderItems(it) }
        viewModel.getLoading().observe(this) { renderLoading(it) }
        viewModel.getState().observe(this) { state ->
            val previous = browserState
            browserState = state
            val displayChanged = previous.videoDisplayMode != state.videoDisplayMode
            val tileSpanChanged = previous.tileSpanCount != state.tileSpanCount
            if (displayChanged || tileSpanChanged) {
                applyVideoDisplayMode()
            }
            val headerChanged =
                previous.currentMode != state.currentMode ||
                    previous.inFolderVideos != state.inFolderVideos ||
                    previous.hierarchyPath != state.hierarchyPath ||
                    previous.selectedBucketName != state.selectedBucketName
            if (headerChanged) {
                updateHeaderState()
            }
        }
        settingsViewModel.getSettings().observe(this) { settings ->
            applySettings(settings)
        }

        transferController = TransferController(
            activity = this,
            adapter = adapter,
            preferences = getSharedPreferences(PREFS, MODE_PRIVATE),
            launchCopyDestination = { copyDestinationLauncher.launch(null) },
            launchDeletePermission = { request -> deletePermissionLauncher.launch(request) },
            onReloadRequested = { loadIfPermitted() }
        )

        selectionMoveButton.setOnClickListener { transferController.startMoveSelected() }
        selectionCopyButton.setOnClickListener { transferController.startCopySelected() }
        selectionDeleteButton.setOnClickListener { transferController.startDeleteSelected() }

        val settingsButton = findViewById<View>(R.id.settingsButton)
        settingsButton.setOnClickListener { showSettingsDialog(it) }

        val backButton = findViewById<Button>(R.id.backButton)
        backButton.setOnClickListener {
            if (handleBackNavigation()) {
                return@setOnClickListener
            }
            setMode(VideoMode.FOLDERS)
        }

        headerBackButton.setOnClickListener {
            if (handleBackNavigation()) {
                return@setOnClickListener
            }
            if (browserState.inFolderVideos) {
                setMode(VideoMode.FOLDERS)
            }
        }

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (handleBackNavigation()) {
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
    }

    private fun loadIfPermitted() {
        val current = viewModel.getState().value ?: browserState
        if (hasVideoPermission()) {
            when {
                current.currentMode == VideoMode.HIERARCHY -> viewModel.loadHierarchy(
                    current.hierarchyPath,
                    current.sortMode,
                    current.sortOrder,
                    contentResolver
                )
                current.inFolderVideos && current.selectedBucketId != null -> viewModel.loadFolderVideos(
                    current.selectedBucketId!!,
                    current.sortMode,
                    current.sortOrder,
                    contentResolver
                )
                else -> viewModel.load(
                    current.currentMode,
                    current.sortMode,
                    current.sortOrder,
                    contentResolver
                )
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
                viewModel.updateState {
                    it.copy(
                        selectedBucketId = item.bucketId,
                        selectedBucketName = item.title,
                        inFolderVideos = true
                    )
                }
                loadIfPermitted()
            }
            DisplayItem.Type.HIERARCHY -> {
                viewModel.updateState {
                    it.copy(
                        hierarchyPath = item.bucketId ?: "",
                        inFolderVideos = false,
                        selectedBucketId = null,
                        selectedBucketName = null
                    )
                }
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
        if (browserState.inFolderVideos) {
            folderHeader.visibility = View.GONE
            headerBackButton.visibility = View.VISIBLE
            val title = browserState.selectedBucketName ?: "Unknown"
            titleText.text = title
            applyVideoDisplayMode()
        } else if (browserState.currentMode == VideoMode.HIERARCHY && !isHierarchyRoot()) {
            folderHeader.visibility = View.GONE
            headerBackButton.visibility = View.VISIBLE
            titleText.text = getHierarchyTitle()
            applyVideoDisplayMode()
        } else {
            folderHeader.visibility = View.GONE
            headerBackButton.visibility = View.GONE
            titleText.text = getString(R.string.app_name)
            if (
                browserState.currentMode == VideoMode.FOLDERS ||
                browserState.currentMode == VideoMode.VIDEOS ||
                browserState.currentMode == VideoMode.HIERARCHY
            ) {
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
        val displayTileMultipliers = content.findViewById<View>(R.id.settingsDisplayTileMultipliers)
        val displayTileX2Row = content.findViewById<View>(R.id.settingsDisplayTileX2Row)
        val displayTileX3Row = content.findViewById<View>(R.id.settingsDisplayTileX3Row)
        val displayTileX4Row = content.findViewById<View>(R.id.settingsDisplayTileX4Row)
        val displayTileX2 = content.findViewById<TextView>(R.id.settingsDisplayTileX2)
        val displayTileX3 = content.findViewById<TextView>(R.id.settingsDisplayTileX3)
        val displayTileX4 = content.findViewById<TextView>(R.id.settingsDisplayTileX4)
        val displayTileX2Icon = content.findViewById<ImageView>(R.id.settingsDisplayTileX2Icon)
        val displayTileX3Icon = content.findViewById<ImageView>(R.id.settingsDisplayTileX3Icon)
        val displayTileX4Icon = content.findViewById<ImageView>(R.id.settingsDisplayTileX4Icon)
        val sortTitle = content.findViewById<TextView>(R.id.settingsSortTitle)
        val sortModified = content.findViewById<TextView>(R.id.settingsSortModified)
        val sortDuration = content.findViewById<TextView>(R.id.settingsSortDuration)
        val sortAsc = content.findViewById<TextView>(R.id.settingsSortAsc)
        val sortDesc = content.findViewById<TextView>(R.id.settingsSortDesc)
        val advancedRow = content.findViewById<View>(R.id.settingsAdvancedRow)
        val cancelButton = content.findViewById<Button>(R.id.settingsCancel)
        val confirmButton = content.findViewById<Button>(R.id.settingsConfirm)
        val defaultColor = modeFolders.currentTextColor
        val selectedColor = getColor(R.color.brand_green)
        var pendingMode = browserState.currentMode
        var pendingDisplay = browserState.videoDisplayMode
        var pendingTileSpan = browserState.tileSpanCount
        var pendingSort = browserState.sortMode
        var pendingOrder = browserState.sortOrder
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
            displayTileMultipliers,
            pendingDisplay,
            selectedColor,
            defaultColor
        )
        updateTileSpanSelectionUI(
            displayTileX2,
            displayTileX3,
            displayTileX4,
            displayTileX2Icon,
            displayTileX3Icon,
            displayTileX4Icon,
            pendingTileSpan,
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
            updateDisplaySelectionUI(
                displayList,
                displayTile,
                displayTileMultipliers,
                pendingDisplay,
                selectedColor,
                defaultColor
            )
        }
        displayTile.setOnClickListener {
            val wasTile = pendingDisplay == VideoDisplayMode.TILE
            pendingDisplay = VideoDisplayMode.TILE
            if (!wasTile) {
                pendingTileSpan = 2
                updateTileSpanSelectionUI(
                    displayTileX2,
                    displayTileX3,
                    displayTileX4,
                    displayTileX2Icon,
                    displayTileX3Icon,
                    displayTileX4Icon,
                    pendingTileSpan,
                    selectedColor,
                    defaultColor
                )
            }
            updateDisplaySelectionUI(
                displayList,
                displayTile,
                displayTileMultipliers,
                pendingDisplay,
                selectedColor,
                defaultColor
            )
        }
        displayTileX2Row.setOnClickListener {
            pendingTileSpan = 2
            updateTileSpanSelectionUI(
                displayTileX2,
                displayTileX3,
                displayTileX4,
                displayTileX2Icon,
                displayTileX3Icon,
                displayTileX4Icon,
                pendingTileSpan,
                selectedColor,
                defaultColor
            )
        }
        displayTileX3Row.setOnClickListener {
            pendingTileSpan = 3
            updateTileSpanSelectionUI(
                displayTileX2,
                displayTileX3,
                displayTileX4,
                displayTileX2Icon,
                displayTileX3Icon,
                displayTileX4Icon,
                pendingTileSpan,
                selectedColor,
                defaultColor
            )
        }
        displayTileX4Row.setOnClickListener {
            pendingTileSpan = 4
            updateTileSpanSelectionUI(
                displayTileX2,
                displayTileX3,
                displayTileX4,
                displayTileX2Icon,
                displayTileX3Icon,
                displayTileX4Icon,
                pendingTileSpan,
                selectedColor,
                defaultColor
            )
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

        advancedRow.setOnClickListener {
            dialog.dismiss()
            startActivity(Intent(this, AdvancedSettingsActivity::class.java))
        }

        cancelButton.setOnClickListener { dialog.dismiss() }
        confirmButton.setOnClickListener {
            val modeChanged = pendingMode != browserState.currentMode
            val displayChanged = pendingDisplay != browserState.videoDisplayMode
            val tileSpanChanged = pendingTileSpan != browserState.tileSpanCount
            val sortChanged = pendingSort != browserState.sortMode
            val orderChanged = pendingOrder != browserState.sortOrder
            if (displayChanged) {
                settingsViewModel.updateDisplayMode(pendingDisplay)
            }
            if (tileSpanChanged) {
                settingsViewModel.updateTileSpanCount(pendingTileSpan)
            }
            if (sortChanged || orderChanged) {
                settingsViewModel.updateSortMode(pendingSort)
                settingsViewModel.updateSortOrder(pendingOrder)
            }
            if (displayChanged || tileSpanChanged || sortChanged || orderChanged) {
                viewModel.updateState {
                    it.copy(
                        videoDisplayMode = pendingDisplay,
                        tileSpanCount = pendingTileSpan,
                        sortMode = pendingSort,
                        sortOrder = pendingOrder
                    )
                }
            }
            if (modeChanged) {
                settingsViewModel.updateMode(pendingMode)
                setMode(pendingMode)
            } else if (displayChanged || (tileSpanChanged && pendingDisplay == VideoDisplayMode.TILE)) {
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
        val renameButton = content.findViewById<TextView>(R.id.actionRename)
        val propertiesButton = content.findViewById<TextView>(R.id.actionProperties)

        val dialog = BottomSheetDialog(this)
        dialog.setContentView(content)

        renameButton.setOnClickListener {
            dialog.dismiss()
            showRenameDialog(item)
        }
        propertiesButton.setOnClickListener {
            showItemPropertiesDialog(item)
            dialog.dismiss()
        }

        dialog.show()
    }

    private fun showItemPropertiesDialog(item: DisplayItem) {
        val properties = loadItemProperties(item)
        val content = layoutInflater.inflate(R.layout.dialog_item_properties, null)
        content.findViewById<TextView>(R.id.propertiesTitle).text = properties.title
        content.findViewById<TextView>(R.id.propertiesType).text = properties.typeLabel
        content.findViewById<TextView>(R.id.propertiesNameValue).text = properties.fullName
        content.findViewById<TextView>(R.id.propertiesLocationValue).text = properties.location
        content.findViewById<TextView>(R.id.propertiesSizeValue).text = properties.size
        content.findViewById<TextView>(R.id.propertiesModifiedValue).text = properties.modified
        content.findViewById<TextView>(R.id.propertiesSubtitleValue).text = properties.subtitle

        val dialog = AlertDialog.Builder(this)
            .setView(content)
            .create()
        dialog.setCanceledOnTouchOutside(true)
        content.findViewById<Button>(R.id.propertiesClose).setOnClickListener { dialog.dismiss() }
        dialog.show()
    }

    private fun showRenameDialog(item: DisplayItem) {
        val isVideo = item.type == DisplayItem.Type.VIDEO
        if (!isVideo && item.type != DisplayItem.Type.FOLDER && item.type != DisplayItem.Type.HIERARCHY) {
            Toast.makeText(this, getString(R.string.action_not_ready), Toast.LENGTH_SHORT).show()
            return
        }
        if (item.type == DisplayItem.Type.HIERARCHY && item.bucketId.isNullOrEmpty()) {
            Toast.makeText(this, getString(R.string.rename_root_not_allowed), Toast.LENGTH_SHORT).show()
            return
        }
        val currentName = if (isVideo) {
            queryVideoMetadata(item.contentUri)?.displayName?.ifEmpty { item.title } ?: item.title
        } else {
            item.title
        }
        val input = EditText(this)
        input.setSingleLine(true)
        input.hint = getString(R.string.rename_hint)
        input.setText(currentName)
        if (isVideo) {
            val dot = currentName.lastIndexOf('.')
            if (dot > 0) {
                input.setSelection(0, dot)
            } else {
                input.setSelection(0, currentName.length)
            }
        } else {
            input.setSelection(0, currentName.length)
        }
        AlertDialog.Builder(this)
            .setTitle(R.string.rename_title)
            .setView(input)
            .setPositiveButton(R.string.confirm) { _, _ ->
                val rawName = input.text?.toString()?.trim() ?: ""
                val resolved = if (isVideo) {
                    resolveRenameInput(rawName, currentName)
                } else {
                    rawName.trim()
                }
                when {
                    resolved.isEmpty() -> {
                        Toast.makeText(this, getString(R.string.rename_empty), Toast.LENGTH_SHORT).show()
                    }
                    resolved == currentName -> {
                        return@setPositiveButton
                    }
                    containsInvalidNameChars(resolved) -> {
                        Toast.makeText(this, getString(R.string.rename_invalid), Toast.LENGTH_SHORT).show()
                    }
                    else -> {
                        if (isVideo) {
                            renameMediaItem(item, resolved, allowPermissionRequest = true)
                        } else {
                            val relativePath = resolveFolderRelativePath(item)
                            if (relativePath.isNullOrEmpty()) {
                                Toast.makeText(this, getString(R.string.rename_failed), Toast.LENGTH_SHORT).show()
                                return@setPositiveButton
                            }
                            val request = FolderRenameRequest(item, resolved, relativePath)
                            val savedTree = getSavedFolderRenameTreeUri()
                            if (savedTree != null && hasPersistedTreePermission(savedTree)) {
                                val result = tryFolderRenameWithTree(savedTree, request, showToasts = false)
                                if (result == FolderRenameResult.SUCCESS) {
                                    Toast.makeText(this, getString(R.string.rename_done), Toast.LENGTH_SHORT).show()
                                    loadIfPermitted()
                                    return@setPositiveButton
                                }
                            }
                            pendingFolderRename = request
                            Toast.makeText(
                                this,
                                getString(R.string.rename_folder_pick_root),
                                Toast.LENGTH_SHORT
                            ).show()
                            folderRenameTreeLauncher.launch(null)
                        }
                    }
                }
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun loadItemProperties(item: DisplayItem): ItemProperties {
        val typeLabel = if (item.type == DisplayItem.Type.VIDEO) {
            getString(R.string.property_type_file)
        } else {
            getString(R.string.property_type_folder)
        }
        return if (item.type == DisplayItem.Type.VIDEO) {
            val meta = queryVideoMetadata(item.contentUri)
            val fullName = meta?.displayName ?: item.title
            val location = meta?.relativePath ?: getString(R.string.property_unknown)
            val size = if (meta != null) {
                Formatter.formatFileSize(this, meta.sizeBytes)
            } else {
                getString(R.string.property_unknown)
            }
            val modified = meta?.modifiedSeconds?.takeIf { it > 0 }
                ?.let { formatModifiedDate(it) }
                ?: getString(R.string.property_unknown)
            val subtitle = if (meta != null && hasSubtitle(meta.relativePath, meta.displayName)) {
                getString(R.string.property_subtitle_yes)
            } else {
                getString(R.string.property_subtitle_no)
            }
            ItemProperties(
                title = item.title,
                typeLabel = typeLabel,
                fullName = fullName,
                location = location,
                size = size,
                modified = modified,
                subtitle = subtitle
            )
        } else {
            val folderInfo = queryFolderInfo(item)
            val location = when {
                item.type == DisplayItem.Type.HIERARCHY && item.bucketId.isNullOrEmpty() ->
                    getString(R.string.property_root)
                item.type == DisplayItem.Type.HIERARCHY -> item.bucketId ?: getString(R.string.property_unknown)
                else -> folderInfo?.relativePath ?: getString(R.string.property_unknown)
            }
            val size = folderInfo?.sizeBytes?.takeIf { it > 0 }
                ?.let { Formatter.formatFileSize(this, it) }
                ?: if (folderInfo != null) {
                    Formatter.formatFileSize(this, 0L)
                } else {
                    getString(R.string.property_unknown)
                }
            val modified = folderInfo?.modifiedSeconds?.takeIf { it > 0 }
                ?.let { formatModifiedDate(it) }
                ?: getString(R.string.property_unknown)
            ItemProperties(
                title = item.title,
                typeLabel = typeLabel,
                fullName = item.title,
                location = location,
                size = size,
                modified = modified,
                subtitle = getString(R.string.property_subtitle_na)
            )
        }
    }

    private fun queryVideoMetadata(contentUri: String?): VideoMeta? {
        val uri = contentUri?.let { Uri.parse(it) } ?: return null
        val projection = arrayOf(
            MediaStore.Video.Media.DISPLAY_NAME,
            MediaStore.Video.Media.RELATIVE_PATH,
            MediaStore.Video.Media.SIZE,
            MediaStore.Video.Media.DATE_MODIFIED
        )
        contentResolver.query(uri, projection, null, null, null)?.use { cursor ->
            if (!cursor.moveToFirst()) {
                return null
            }
            val nameCol = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.DISPLAY_NAME)
            val pathCol = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.RELATIVE_PATH)
            val sizeCol = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.SIZE)
            val modifiedCol = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.DATE_MODIFIED)
            return VideoMeta(
                displayName = cursor.getString(nameCol) ?: "",
                relativePath = cursor.getString(pathCol) ?: "",
                sizeBytes = cursor.getLong(sizeCol),
                modifiedSeconds = cursor.getLong(modifiedCol)
            )
        }
        return null
    }

    private fun resolveRenameInput(input: String, originalName: String): String {
        val trimmed = input.trim()
        if (trimmed.isEmpty()) {
            return ""
        }
        if (trimmed.contains('.') || !originalName.contains('.')) {
            return trimmed
        }
        val ext = originalName.substringAfterLast('.', "")
        return if (ext.isNotEmpty()) "$trimmed.$ext" else trimmed
    }

    private fun containsInvalidNameChars(name: String): Boolean {
        return name.contains('/') || name.contains('\\')
    }

    private fun renameMediaItem(
        item: DisplayItem,
        newName: String,
        allowPermissionRequest: Boolean
    ) {
        val uri = item.contentUri?.let { Uri.parse(it) }
        if (uri == null) {
            Toast.makeText(this, getString(R.string.rename_failed), Toast.LENGTH_SHORT).show()
            return
        }
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, newName)
        }
        try {
            val updated = contentResolver.update(uri, values, null, null)
            if (updated > 0) {
                Toast.makeText(this, getString(R.string.rename_done), Toast.LENGTH_SHORT).show()
                loadIfPermitted()
            } else {
                Toast.makeText(this, getString(R.string.rename_failed), Toast.LENGTH_SHORT).show()
            }
        } catch (e: SecurityException) {
            if (allowPermissionRequest &&
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q &&
                e is RecoverableSecurityException
            ) {
                pendingRename = RenameRequest(item, newName)
                val intentSender = e.userAction.actionIntent.intentSender
                val request = androidx.activity.result.IntentSenderRequest.Builder(intentSender).build()
                renamePermissionLauncher.launch(request)
                return
            }
            Toast.makeText(this, getString(R.string.rename_failed), Toast.LENGTH_SHORT).show()
        } catch (_: Exception) {
            Toast.makeText(this, getString(R.string.rename_failed), Toast.LENGTH_SHORT).show()
        }
    }

    private fun resolveFolderRelativePath(item: DisplayItem): String? {
        return when (item.type) {
            DisplayItem.Type.FOLDER -> queryFolderInfo(item)?.relativePath
            DisplayItem.Type.HIERARCHY -> item.bucketId
            else -> null
        }?.trim()
    }

    private fun handleFolderRenameTree(uri: Uri, request: FolderRenameRequest, showToasts: Boolean) {
        val result = tryFolderRenameWithTree(uri, request, showToasts)
        if (result == FolderRenameResult.SUCCESS && showToasts) {
            Toast.makeText(this, getString(R.string.rename_done), Toast.LENGTH_SHORT).show()
            loadIfPermitted()
        }
    }

    private fun tryFolderRenameWithTree(
        uri: Uri,
        request: FolderRenameRequest,
        showToasts: Boolean
    ): FolderRenameResult {
        try {
            val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            contentResolver.takePersistableUriPermission(uri, flags)
        } catch (_: SecurityException) {
        }
        val root = DocumentFile.fromTreeUri(this, uri)
        if (root == null || !root.isDirectory) {
            if (showToasts) {
                Toast.makeText(this, getString(R.string.rename_folder_root_invalid), Toast.LENGTH_SHORT).show()
            }
            return FolderRenameResult.INVALID_ROOT
        }
        val target = findFolderInTree(root, request.relativePath)
        if (target == null) {
            if (showToasts) {
                Toast.makeText(this, getString(R.string.rename_folder_not_found), Toast.LENGTH_SHORT).show()
            }
            return FolderRenameResult.NOT_FOUND
        }
        val success = try {
            target.renameTo(request.newName)
        } catch (_: Exception) {
            false
        }
        if (success) {
            return FolderRenameResult.SUCCESS
        }
        if (showToasts) {
            Toast.makeText(this, getString(R.string.rename_failed), Toast.LENGTH_SHORT).show()
        }
        return FolderRenameResult.FAILED
    }

    private fun findFolderInTree(root: DocumentFile, relativePath: String): DocumentFile? {
        val segments = relativePath.split('/').filter { it.isNotBlank() }
        if (segments.isEmpty()) {
            return null
        }
        fun traverseFrom(startIndex: Int): DocumentFile? {
            var current: DocumentFile? = root
            for (i in startIndex until segments.size) {
                val next = current?.findFile(segments[i]) ?: return null
                if (!next.isDirectory) {
                    return null
                }
                current = next
            }
            return current
        }
        traverseFrom(0)?.let { return it }
        for (start in 1 until segments.size) {
            val found = traverseFrom(start)
            if (found != null) {
                return found
            }
        }
        return null
    }

    private fun getSavedFolderRenameTreeUri(): Uri? {
        val value = preferences.getString(KEY_FOLDER_RENAME_TREE_URI, null) ?: return null
        return try {
            Uri.parse(value)
        } catch (_: Exception) {
            null
        }
    }

    private fun hasPersistedTreePermission(uri: Uri): Boolean {
        val target = uri.toString()
        return contentResolver.persistedUriPermissions.any { perm ->
            perm.isWritePermission && perm.uri.toString() == target
        }
    }

    private fun queryFolderInfo(item: DisplayItem): FolderMeta? {
        val projection = arrayOf(
            MediaStore.Video.Media.RELATIVE_PATH,
            MediaStore.Video.Media.SIZE,
            MediaStore.Video.Media.DATE_MODIFIED
        )
        var selection: String? = null
        var selectionArgs: Array<String>? = null
        when (item.type) {
            DisplayItem.Type.FOLDER -> {
                val bucketId = item.bucketId ?: return null
                selection = "${MediaStore.Video.Media.BUCKET_ID}=?"
                selectionArgs = arrayOf(bucketId)
            }
            DisplayItem.Type.HIERARCHY -> {
                val path = item.bucketId ?: ""
                if (path.isNotEmpty()) {
                    selection = "${MediaStore.Video.Media.RELATIVE_PATH} LIKE ?"
                    selectionArgs = arrayOf("$path%")
                }
            }
            else -> return null
        }
        var relativePath: String? = null
        var sizeTotal = 0L
        var latestModified = 0L
        contentResolver.query(
            MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
            projection,
            selection,
            selectionArgs,
            null
        )?.use { cursor ->
            val pathCol = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.RELATIVE_PATH)
            val sizeCol = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.SIZE)
            val modifiedCol = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.DATE_MODIFIED)
            while (cursor.moveToNext()) {
                if (relativePath == null) {
                    relativePath = cursor.getString(pathCol)
                }
                sizeTotal += cursor.getLong(sizeCol)
                val modified = cursor.getLong(modifiedCol)
                if (modified > latestModified) {
                    latestModified = modified
                }
            }
        }
        if (relativePath == null && item.type == DisplayItem.Type.HIERARCHY) {
            relativePath = item.bucketId
        }
        return FolderMeta(relativePath, sizeTotal, latestModified)
    }

    private fun hasSubtitle(relativePath: String?, displayName: String?): Boolean {
        if (relativePath.isNullOrEmpty() || displayName.isNullOrEmpty()) {
            return false
        }
        val base = displayName.substringBeforeLast('.', displayName)
        if (base.isEmpty()) {
            return false
        }
        val targets = setOf("srt", "vtt", "ass", "ssa", "sub")
        val targetNames = targets.map { "$base.$it".lowercase(Locale.US) }.toSet()
        val projection = arrayOf(MediaStore.MediaColumns.DISPLAY_NAME)
        val selection = "${MediaStore.MediaColumns.RELATIVE_PATH}=?"
        val selectionArgs = arrayOf(relativePath)
        contentResolver.query(
            MediaStore.Files.getContentUri("external"),
            projection,
            selection,
            selectionArgs,
            null
        )?.use { cursor ->
            val nameCol = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DISPLAY_NAME)
            while (cursor.moveToNext()) {
                val name = cursor.getString(nameCol) ?: continue
                if (targetNames.contains(name.lowercase(Locale.US))) {
                    return true
                }
            }
        }
        return false
    }

    private fun formatModifiedDate(modifiedSeconds: Long): String {
        val formatter = DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT)
        return formatter.format(Date(modifiedSeconds * 1000))
    }

    private data class ItemProperties(
        val title: String,
        val typeLabel: String,
        val fullName: String,
        val location: String,
        val size: String,
        val modified: String,
        val subtitle: String
    )

    private data class VideoMeta(
        val displayName: String,
        val relativePath: String,
        val sizeBytes: Long,
        val modifiedSeconds: Long
    )

    private data class FolderMeta(
        val relativePath: String?,
        val sizeBytes: Long,
        val modifiedSeconds: Long
    )

    private data class RenameRequest(
        val item: DisplayItem,
        val newName: String
    )

    private data class FolderRenameRequest(
        val item: DisplayItem,
        val newName: String,
        val relativePath: String
    )

    private enum class FolderRenameResult {
        SUCCESS,
        NOT_FOUND,
        INVALID_ROOT,
        FAILED
    }

    private fun setMode(mode: VideoMode) {
        viewModel.updateState {
            it.copy(
                currentMode = mode,
                inFolderVideos = false,
                selectedBucketId = null,
                selectedBucketName = null,
                hierarchyPath = if (mode == VideoMode.HIERARCHY) "" else it.hierarchyPath
            )
        }
        loadIfPermitted()
    }

    private fun handleBackNavigation(): Boolean {
        if (selectionController.isSelectionMode()) {
            selectionController.clearSelection()
            return true
        }
        val current = viewModel.getState().value ?: browserState
        if (current.currentMode == VideoMode.HIERARCHY && current.hierarchyPath.isNotEmpty()) {
            val nextPath = getParentPath(current.hierarchyPath)
            viewModel.updateState { it.copy(hierarchyPath = nextPath) }
            loadIfPermitted()
            return true
        }
        if (current.inFolderVideos) {
            setMode(VideoMode.FOLDERS)
            return true
        }
        return false
    }

    private fun applyVideoDisplayMode() {
        adapter.setVideoDisplayMode(browserState.videoDisplayMode)
        list.layoutManager = if (browserState.videoDisplayMode == VideoDisplayMode.TILE) {
            GridLayoutManager(this, browserState.tileSpanCount)
        } else {
            LinearLayoutManager(this)
        }
        adapter.notifyDataSetChanged()
    }

    private fun applySettings(settings: SettingsState) {
        viewModel.updateState {
            it.copy(
                currentMode = settings.mode,
                videoDisplayMode = settings.displayMode,
                tileSpanCount = settings.tileSpanCount,
                sortMode = settings.sortMode,
                sortOrder = settings.sortOrder
            )
        }
        if (!initialSettingsApplied) {
            initialSettingsApplied = true
            loadWithSettings(settings)
        }
    }

    private fun loadWithSettings(settings: SettingsState) {
        if (hasVideoPermission()) {
            when (settings.mode) {
                VideoMode.HIERARCHY -> viewModel.loadHierarchy(
                    "",
                    settings.sortMode,
                    settings.sortOrder,
                    contentResolver
                )
                else -> viewModel.load(
                    settings.mode,
                    settings.sortMode,
                    settings.sortOrder,
                    contentResolver
                )
            }
        } else {
            statusText.text = getString(R.string.permission_needed)
            statusText.visibility = View.VISIBLE
            requestVideoPermission()
        }
    }

    private fun isHierarchyRoot(): Boolean = browserState.hierarchyPath.isEmpty()

    private fun getHierarchyTitle(): String {
        if (browserState.hierarchyPath.isEmpty()) {
            return "Root"
        }
        var trimmed = browserState.hierarchyPath
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
        tileMultipliers: View,
        selectedDisplay: VideoDisplayMode,
        selectedColor: Int,
        defaultColor: Int
    ) {
        val isTile = selectedDisplay == VideoDisplayMode.TILE
        list.setTextColor(if (selectedDisplay == VideoDisplayMode.LIST) selectedColor else defaultColor)
        tile.setTextColor(if (isTile) selectedColor else defaultColor)
        tileMultipliers.visibility = if (isTile) View.VISIBLE else View.GONE
    }

    private fun updateTileSpanSelectionUI(
        x2: TextView,
        x3: TextView,
        x4: TextView,
        x2Icon: ImageView,
        x3Icon: ImageView,
        x4Icon: ImageView,
        selectedSpan: Int,
        selectedColor: Int,
        defaultColor: Int
    ) {
        val x2Selected = selectedSpan == 2
        val x3Selected = selectedSpan == 3
        val x4Selected = selectedSpan == 4
        x2.setTextColor(if (x2Selected) selectedColor else defaultColor)
        x3.setTextColor(if (x3Selected) selectedColor else defaultColor)
        x4.setTextColor(if (x4Selected) selectedColor else defaultColor)
        x2Icon.setColorFilter(if (x2Selected) selectedColor else defaultColor)
        x3Icon.setColorFilter(if (x3Selected) selectedColor else defaultColor)
        x4Icon.setColorFilter(if (x4Selected) selectedColor else defaultColor)
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
        private const val KEY_FOLDER_RENAME_TREE_URI = "folder_rename_tree_uri"
    }
}
