package com.nshell.nsplayer.ui.main

import android.Manifest
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.provider.MediaStore
import android.view.View
import android.widget.Button
import android.widget.ImageView
import android.widget.RadioGroup
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.IntentSenderRequest
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
import androidx.documentfile.provider.DocumentFile
import android.widget.ProgressBar
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.nshell.nsplayer.R
import com.nshell.nsplayer.ui.player.PlayerActivity
import com.nshell.nsplayer.ui.settings.advanced.AdvancedSettingsActivity
import java.text.DateFormat
import java.util.Date
import java.util.Locale
import android.text.format.Formatter
import java.util.concurrent.atomic.AtomicBoolean
import android.util.Log
import java.util.concurrent.CountDownLatch

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
    private lateinit var list: RecyclerView
    private var currentMode = VideoMode.FOLDERS
    private var videoDisplayMode = VideoDisplayMode.LIST
    private var tileSpanCount = 2
    private var sortMode = VideoSortMode.MODIFIED
    private var sortOrder = VideoSortOrder.DESC
    private var inFolderVideos = false
    private var selectedBucketId: String? = null
    private var selectedBucketName: String? = null
    private var hierarchyPath = ""
    private lateinit var preferences: SharedPreferences
    private var pendingCopyItems: List<DisplayItem> = emptyList()
    private var pendingOperation: TransferOperation = TransferOperation.COPY
    private val deletePermissionLock = Any()
    private var pendingDeleteLatch: CountDownLatch? = null
    private var pendingDeleteGranted = false

    private val copyDestinationLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri ->
        if (uri == null) {
            pendingCopyItems = emptyList()
            return@registerForActivityResult
        }
        preferences.edit().putString(KEY_COPY_TREE_URI, uri.toString()).apply()
        handleCopyToTree(uri, pendingCopyItems, pendingOperation)
    }

    private val deletePermissionLauncher = registerForActivityResult(
        ActivityResultContracts.StartIntentSenderForResult()
    ) { result ->
        synchronized(deletePermissionLock) {
            pendingDeleteGranted = result.resultCode == RESULT_OK
            pendingDeleteLatch?.countDown()
        }
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
            startMoveSelected()
        }
        selectionCopyButton.setOnClickListener {
            startCopySelected()
        }
        selectionDeleteButton.setOnClickListener {
            startDeleteSelected()
        }

        preferences = getSharedPreferences(PREFS, MODE_PRIVATE)
        loadPreferences()

        val settingsButton = findViewById<View>(R.id.settingsButton)
        settingsButton.setOnClickListener { showSettingsDialog(it) }

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
            applyVideoDisplayMode()
        } else if (currentMode == VideoMode.HIERARCHY && !isHierarchyRoot()) {
            folderHeader.visibility = View.GONE
            headerBackButton.visibility = View.VISIBLE
            titleText.text = getHierarchyTitle()
            applyVideoDisplayMode()
        } else {
            folderHeader.visibility = View.GONE
            headerBackButton.visibility = View.GONE
            titleText.text = getString(R.string.app_name)
            if (currentMode == VideoMode.FOLDERS || currentMode == VideoMode.VIDEOS || currentMode == VideoMode.HIERARCHY) {
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
        var pendingMode = currentMode
        var pendingDisplay = videoDisplayMode
        var pendingTileSpan = tileSpanCount
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
            val modeChanged = pendingMode != currentMode
            val displayChanged = pendingDisplay != videoDisplayMode
            val tileSpanChanged = pendingTileSpan != tileSpanCount
            val sortChanged = pendingSort != sortMode
            val orderChanged = pendingOrder != sortOrder
            if (displayChanged) {
                videoDisplayMode = pendingDisplay
                saveDisplayMode()
            }
            if (tileSpanChanged) {
                tileSpanCount = pendingTileSpan
                saveTileSpanCount()
            }
            if (sortChanged || orderChanged) {
                sortMode = pendingSort
                sortOrder = pendingOrder
                saveSortMode()
                saveSortOrder()
            }
            if (modeChanged) {
                setMode(pendingMode)
            } else if (displayChanged || (tileSpanChanged && videoDisplayMode == VideoDisplayMode.TILE)) {
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
            Toast.makeText(this, getString(R.string.action_not_ready), Toast.LENGTH_SHORT).show()
            dialog.dismiss()
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

    private fun queryFolderInfo(item: DisplayItem): FolderMeta? {
        val projection = arrayOf(
            MediaStore.Video.Media.RELATIVE_PATH,
            MediaStore.Video.Media.SIZE,
            MediaStore.Video.Media.DATE_MODIFIED
        )
        val (selection, selectionArgs) = when (item.type) {
            DisplayItem.Type.FOLDER -> {
                val bucketId = item.bucketId ?: return null
                "${MediaStore.Video.Media.BUCKET_ID}=?" to arrayOf(bucketId)
            }
            DisplayItem.Type.HIERARCHY -> {
                val path = item.bucketId ?: ""
                if (path.isEmpty()) {
                    null to null
                } else {
                    "${MediaStore.Video.Media.RELATIVE_PATH} LIKE ?" to arrayOf("$path%")
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
            GridLayoutManager(this, tileSpanCount)
        } else {
            LinearLayoutManager(this)
        }
        adapter.notifyDataSetChanged()
    }

    private fun onSelectionChanged(selectedCount: Int, selectionMode: Boolean) {
        selectionBar.visibility = if (selectionMode) View.VISIBLE else View.GONE
    }

    private fun startCopySelected() {
        val selected = adapter.getSelectedItems()
        if (selected.isEmpty()) {
            Toast.makeText(this, getString(R.string.copy_no_selection), Toast.LENGTH_SHORT).show()
            return
        }
        val videos = selected.filter { it.type == DisplayItem.Type.VIDEO && !it.contentUri.isNullOrEmpty() }
        if (videos.isEmpty()) {
            Toast.makeText(this, getString(R.string.copy_no_selection), Toast.LENGTH_SHORT).show()
            return
        }
        pendingCopyItems = videos
        pendingOperation = TransferOperation.COPY
        copyDestinationLauncher.launch(null)
    }

    private fun startMoveSelected() {
        val selected = adapter.getSelectedItems()
        if (selected.isEmpty()) {
            Toast.makeText(this, getString(R.string.copy_no_selection), Toast.LENGTH_SHORT).show()
            return
        }
        val videos = selected.filter { it.type == DisplayItem.Type.VIDEO && !it.contentUri.isNullOrEmpty() }
        if (videos.isEmpty()) {
            Toast.makeText(this, getString(R.string.copy_no_selection), Toast.LENGTH_SHORT).show()
            return
        }
        pendingCopyItems = videos
        pendingOperation = TransferOperation.MOVE
        copyDestinationLauncher.launch(null)
    }

    private fun startDeleteSelected() {
        val selected = adapter.getSelectedItems()
        if (selected.isEmpty()) {
            Toast.makeText(this, getString(R.string.delete_no_selection), Toast.LENGTH_SHORT).show()
            return
        }
        val videos = selected.filter { it.type == DisplayItem.Type.VIDEO && !it.contentUri.isNullOrEmpty() }
        if (videos.isEmpty()) {
            Toast.makeText(this, getString(R.string.delete_no_selection), Toast.LENGTH_SHORT).show()
            return
        }
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.delete_title))
            .setMessage(getString(R.string.delete_message, videos.size))
            .setPositiveButton(R.string.delete_confirm) { _, _ ->
                val cancelFlag = AtomicBoolean(false)
                val progressUi = showCopyProgress(videos.size, 0L, cancelFlag, TransferOperation.DELETE)
                Thread {
                    var successCount = 0
                    var failCount = 0
                    var skipCount = 0
                    var completed = 0
                    var currentFileName = "-"
                    for (item in videos) {
                        if (cancelFlag.get()) {
                            break
                        }
                        val uri = item.contentUri?.let { Uri.parse(it) }
                        currentFileName = item.title
                        if (uri == null) {
                            failCount++
                            completed++
                            updateCopyProgress(
                                progressUi,
                                completed,
                                videos.size,
                                successCount,
                                failCount,
                                skipCount,
                                0L,
                                0L,
                                currentFileName
                            )
                            continue
                        }
                        if (deleteSourceUri(uri)) {
                            successCount++
                        } else {
                            failCount++
                        }
                        completed++
                        updateCopyProgress(
                            progressUi,
                            completed,
                            videos.size,
                            successCount,
                            failCount,
                            skipCount,
                            0L,
                            0L,
                            currentFileName
                        )
                    }
                    runOnUiThread {
                        progressUi.dialog.dismiss()
                        if (cancelFlag.get()) {
                            Toast.makeText(this, getString(R.string.delete_cancelled), Toast.LENGTH_SHORT).show()
                            adapter.clearSelection()
                            loadIfPermitted()
                            return@runOnUiThread
                        }
                        if (failCount == 0) {
                            Toast.makeText(
                                this,
                                getString(R.string.delete_done, successCount),
                                Toast.LENGTH_SHORT
                            ).show()
                        } else if (successCount == 0) {
                            Toast.makeText(this, getString(R.string.delete_failed), Toast.LENGTH_SHORT).show()
                        } else {
                            Toast.makeText(
                                this,
                                getString(R.string.delete_partial, successCount, failCount),
                                Toast.LENGTH_SHORT
                            ).show()
                        }
                        adapter.clearSelection()
                        loadIfPermitted()
                    }
                }.start()
            }
            .setNegativeButton(R.string.delete_cancel, null)
            .show()
    }

    private fun handleCopyToTree(treeUri: Uri, items: List<DisplayItem>, operation: TransferOperation) {
        if (items.isEmpty()) {
            return
        }
        try {
            val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            contentResolver.takePersistableUriPermission(treeUri, flags)
        } catch (_: SecurityException) {
        }
        val root = DocumentFile.fromTreeUri(this, treeUri)
        if (root == null || !root.isDirectory) {
            Toast.makeText(this, getString(R.string.copy_failed), Toast.LENGTH_SHORT).show()
            return
        }
        val cancelFlag = AtomicBoolean(false)
        val conflictState = ConflictDecisionState()
        val sizePlan = calculateCopySizePlan(items)
        val progressUi = showCopyProgress(items.size, sizePlan.totalBytes, cancelFlag, operation)
        Thread {
            var successCount = 0
            var failCount = 0
            var skipCount = 0
            var completed = 0
            var copiedBytes = 0L
            var lastUpdate = 0L
            var currentFileName = "-"
            for (item in items) {
                if (cancelFlag.get()) {
                    break
                }
                val srcUri = item.contentUri?.let { Uri.parse(it) }
                if (srcUri == null) {
                    currentFileName = item.title
                    failCount++
                    completed++
                    updateCopyProgress(
                        progressUi,
                        completed,
                        items.size,
                        successCount,
                        failCount,
                        skipCount,
                        copiedBytes,
                        sizePlan.totalBytes,
                        currentFileName
                    )
                    continue
                }
                val displayName = queryDisplayName(srcUri) ?: item.title
                currentFileName = displayName
                val mimeType = contentResolver.getType(srcUri) ?: "video/*"
                val resolution = resolveTargetFile(root, displayName, cancelFlag, conflictState)
                if (resolution == null) {
                    skipCount++
                    completed++
                    updateCopyProgress(
                        progressUi,
                        completed,
                        items.size,
                        successCount,
                        failCount,
                        skipCount,
                        copiedBytes,
                        sizePlan.totalBytes,
                        currentFileName
                    )
                    continue
                }
                if (resolution.overwriteFailed) {
                    failCount++
                    completed++
                    updateCopyProgress(
                        progressUi,
                        completed,
                        items.size,
                        successCount,
                        failCount,
                        skipCount,
                        copiedBytes,
                        sizePlan.totalBytes,
                        currentFileName
                    )
                    continue
                }
                val targetFile = root.createFile(mimeType, resolution.targetName)
                if (targetFile == null) {
                    failCount++
                    completed++
                    updateCopyProgress(
                        progressUi,
                        completed,
                        items.size,
                        successCount,
                        failCount,
                        skipCount,
                        copiedBytes,
                        sizePlan.totalBytes,
                        currentFileName
                    )
                    continue
                }
                val copied = copyUriToDocument(srcUri, targetFile, cancelFlag) { bytesDelta ->
                    if (bytesDelta > 0) {
                        copiedBytes += bytesDelta
                        val now = SystemClock.elapsedRealtime()
                        if (now - lastUpdate >= PROGRESS_UPDATE_MS) {
                            lastUpdate = now
                            updateCopyProgress(
                                progressUi,
                                completed,
                                items.size,
                                successCount,
                                failCount,
                                skipCount,
                                copiedBytes,
                                sizePlan.totalBytes,
                                currentFileName
                            )
                        }
                    }
                }
                if (copied) {
                    successCount++
                    if (operation == TransferOperation.MOVE && !cancelFlag.get()) {
                        val deleted = deleteSourceUri(srcUri)
                        if (!deleted) {
                            if (successCount > 0) {
                                successCount--
                            }
                            failCount++
                        }
                    }
                } else {
                    failCount++
                    if (!cancelFlag.get()) {
                        targetFile.delete()
                    }
                }
                completed++
                updateCopyProgress(
                    progressUi,
                    completed,
                    items.size,
                    successCount,
                    failCount,
                    skipCount,
                    copiedBytes,
                    sizePlan.totalBytes,
                    currentFileName
                )
            }
            runOnUiThread {
                progressUi.dialog.dismiss()
                if (cancelFlag.get()) {
                    val cancelMessage = if (operation == TransferOperation.MOVE) {
                        getString(R.string.move_cancelled)
                    } else {
                        getString(R.string.copy_cancelled)
                    }
                    Toast.makeText(this, cancelMessage, Toast.LENGTH_SHORT).show()
                    adapter.clearSelection()
                    pendingCopyItems = emptyList()
                    return@runOnUiThread
                }
                if (operation == TransferOperation.MOVE) {
                    if (failCount == 0 && skipCount == 0) {
                        Toast.makeText(this, getString(R.string.move_done, successCount), Toast.LENGTH_SHORT).show()
                    } else if (successCount == 0 && failCount > 0) {
                        Toast.makeText(this, getString(R.string.move_failed), Toast.LENGTH_SHORT).show()
                    } else {
                        Toast.makeText(
                            this,
                            getString(R.string.move_partial, successCount, failCount, skipCount),
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                } else {
                    if (failCount == 0 && skipCount == 0) {
                        Toast.makeText(this, getString(R.string.copy_done, successCount), Toast.LENGTH_SHORT).show()
                    } else if (successCount == 0 && failCount > 0) {
                        Toast.makeText(this, getString(R.string.copy_failed), Toast.LENGTH_SHORT).show()
                    } else {
                        Toast.makeText(
                            this,
                            getString(R.string.copy_partial, successCount, failCount, skipCount),
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                }
                adapter.clearSelection()
                pendingCopyItems = emptyList()
            }
        }.start()
    }

    private fun queryDisplayName(uri: Uri): String? {
        val projection = arrayOf(MediaStore.MediaColumns.DISPLAY_NAME)
        contentResolver.query(uri, projection, null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) {
                val nameCol = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DISPLAY_NAME)
                val name = cursor.getString(nameCol)
                if (!name.isNullOrEmpty()) {
                    return name
                }
            }
        }
        return null
    }

    private fun buildUniqueName(parent: DocumentFile, baseName: String): String {
        if (parent.findFile(baseName) == null) {
            return baseName
        }
        val dot = baseName.lastIndexOf('.')
        val stem = if (dot > 0) baseName.substring(0, dot) else baseName
        val ext = if (dot > 0) baseName.substring(dot) else ""
        var index = 1
        var candidate = "$stem ($index)$ext"
        while (parent.findFile(candidate) != null) {
            index++
            candidate = "$stem ($index)$ext"
        }
        return candidate
    }

    private fun copyUriToDocument(
        sourceUri: Uri,
        target: DocumentFile,
        cancelFlag: AtomicBoolean,
        onBytesCopied: (Long) -> Unit
    ): Boolean {
        return try {
            contentResolver.openInputStream(sourceUri)?.use { input ->
                contentResolver.openOutputStream(target.uri)?.use { output ->
                    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                    var read = input.read(buffer)
                    while (read >= 0) {
                        if (cancelFlag.get()) {
                            return false
                        }
                        if (read > 0) {
                            output.write(buffer, 0, read)
                            onBytesCopied(read.toLong())
                        }
                        read = input.read(buffer)
                    }
                    output.flush()
                    true
                } ?: false
            } ?: false
        } catch (_: Exception) {
            false
        }
    }

    private data class CopySizePlan(
        val totalBytes: Long
    )

    private data class CopyProgressUi(
        val dialog: BottomSheetDialog,
        val progressBar: ProgressBar,
        val countText: TextView,
        val sizeText: TextView,
        val percentText: TextView,
        val fileText: TextView,
        val cancelButton: Button
    )

    private fun showCopyProgress(
        total: Int,
        totalBytes: Long,
        cancelFlag: AtomicBoolean,
        operation: TransferOperation
    ): CopyProgressUi {
        val content = layoutInflater.inflate(R.layout.bottom_sheet_copy_progress, null)
        val progressBar = content.findViewById<ProgressBar>(R.id.copyProgressBar)
        val titleText = content.findViewById<TextView>(R.id.copyProgressTitle)
        val countText = content.findViewById<TextView>(R.id.copyProgressCount)
        val sizeText = content.findViewById<TextView>(R.id.copyProgressSize)
        val percentText = content.findViewById<TextView>(R.id.copyProgressPercent)
        val fileText = content.findViewById<TextView>(R.id.copyProgressFile)
        val cancelButton = content.findViewById<Button>(R.id.copyProgressCancel)
        titleText.text = when (operation) {
            TransferOperation.MOVE -> getString(R.string.move_progress_title)
            TransferOperation.DELETE -> getString(R.string.delete_progress_title)
            else -> getString(R.string.copy_progress_title)
        }
        if (totalBytes > 0L) {
            progressBar.isIndeterminate = false
            progressBar.max = PROGRESS_MAX
            progressBar.progress = 0
        } else {
            progressBar.isIndeterminate = true
        }
        countText.text = getString(R.string.copy_progress_count, 0, total, 0, 0, 0)
        sizeText.text = getString(
            R.string.copy_progress_size,
            Formatter.formatFileSize(this, 0L),
            Formatter.formatFileSize(this, totalBytes.coerceAtLeast(0L))
        )
        percentText.text = getString(R.string.copy_progress_percent, 0)
        fileText.text = getString(R.string.copy_progress_file, "-")
        val dialog = BottomSheetDialog(this)
        dialog.setContentView(content)
        dialog.setCancelable(false)
        dialog.show()
        cancelButton.setOnClickListener {
            cancelFlag.set(true)
            cancelButton.isEnabled = false
            cancelButton.text = getString(R.string.copy_cancelling)
        }
        return CopyProgressUi(dialog, progressBar, countText, sizeText, percentText, fileText, cancelButton)
    }

    private fun updateCopyProgress(
        ui: CopyProgressUi,
        completed: Int,
        total: Int,
        successCount: Int,
        failCount: Int,
        skipCount: Int,
        copiedBytes: Long,
        totalBytes: Long,
        currentFileName: String
    ) {
        runOnUiThread {
            if (!ui.progressBar.isIndeterminate && totalBytes > 0L) {
                val percent = (copiedBytes.coerceAtLeast(0L) * PROGRESS_MAX / totalBytes.coerceAtLeast(1L))
                ui.progressBar.progress = percent.coerceIn(0L, PROGRESS_MAX.toLong()).toInt()
            }
            ui.countText.text = getString(
                R.string.copy_progress_count,
                completed.coerceAtMost(total),
                total,
                successCount,
                failCount,
                skipCount
            )
            ui.sizeText.text = getString(
                R.string.copy_progress_size,
                Formatter.formatFileSize(this, copiedBytes.coerceAtLeast(0L)),
                Formatter.formatFileSize(this, totalBytes.coerceAtLeast(0L))
            )
            val percent = if (totalBytes > 0L) {
                (copiedBytes.coerceAtLeast(0L) * 100 / totalBytes.coerceAtLeast(1L)).toInt()
            } else {
                0
            }
            ui.percentText.text = getString(R.string.copy_progress_percent, percent.coerceIn(0, 100))
            ui.fileText.text = getString(R.string.copy_progress_file, currentFileName)
        }
    }

    private fun calculateCopySizePlan(items: List<DisplayItem>): CopySizePlan {
        var total = 0L
        for (item in items) {
            val srcUri = item.contentUri?.let { Uri.parse(it) } ?: continue
            val size = querySizeBytes(srcUri)
            if (size > 0) {
                total += size
            }
        }
        return CopySizePlan(total)
    }

    private fun querySizeBytes(uri: Uri): Long {
        val projection = arrayOf(MediaStore.MediaColumns.SIZE)
        contentResolver.query(uri, projection, null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) {
                val sizeCol = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.SIZE)
                return cursor.getLong(sizeCol)
            }
        }
        return 0L
    }

    private fun deleteSourceUri(uri: Uri): Boolean {
        try {
            return contentResolver.delete(uri, null, null) > 0
        } catch (e: SecurityException) {
            Log.e(TAG_DELETE, "deleteSourceUri SecurityException uri=$uri", e)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q &&
                e is android.app.RecoverableSecurityException
            ) {
                val granted = requestDeletePermission(e)
                if (granted) {
                    return try {
                        contentResolver.delete(uri, null, null) > 0
                    } catch (retry: Exception) {
                        Log.e(TAG_DELETE, "deleteSourceUri retry failed uri=$uri", retry)
                        false
                    }
                }
            }
            return false
        } catch (e: Exception) {
            Log.e(TAG_DELETE, "deleteSourceUri Exception uri=$uri", e)
            return false
        }
    }

    private fun requestDeletePermission(
        e: android.app.RecoverableSecurityException
    ): Boolean {
        val latch = CountDownLatch(1)
        synchronized(deletePermissionLock) {
            pendingDeleteGranted = false
            pendingDeleteLatch = latch
        }
        runOnUiThread {
            val intentSender = e.userAction.actionIntent.intentSender
            val request = IntentSenderRequest.Builder(intentSender).build()
            deletePermissionLauncher.launch(request)
        }
        latch.await()
        synchronized(deletePermissionLock) {
            pendingDeleteLatch = null
            return pendingDeleteGranted
        }
    }

    private data class ConflictResolution(
        val targetName: String,
        val overwriteFailed: Boolean
    )

    private data class ConflictDecision(
        val choice: ConflictChoice,
        val applyAll: Boolean
    )

    private data class ConflictDecisionState(
        var applyAllChoice: ConflictChoice? = null
    )

    private enum class ConflictChoice {
        OVERWRITE,
        RENAME,
        SKIP
    }

    private fun resolveTargetFile(
        parent: DocumentFile,
        displayName: String,
        cancelFlag: AtomicBoolean,
        conflictState: ConflictDecisionState
    ): ConflictResolution? {
        val existing = parent.findFile(displayName)
        if (existing == null) {
            return ConflictResolution(displayName, false)
        }
        if (cancelFlag.get()) {
            return null
        }
        val choice = conflictState.applyAllChoice ?: run {
            val decision = promptConflictChoice(displayName)
            if (decision.applyAll) {
                conflictState.applyAllChoice = decision.choice
            }
            decision.choice
        }
        return when (choice) {
            ConflictChoice.OVERWRITE -> {
                val deleted = try {
                    existing.delete()
                } catch (e: Exception) {
                    Log.e(TAG_DELETE, "overwrite delete failed name=$displayName uri=${existing.uri}", e)
                    false
                }
                ConflictResolution(displayName, !deleted)
            }
            ConflictChoice.RENAME -> ConflictResolution(buildUniqueName(parent, displayName), false)
            ConflictChoice.SKIP -> null
        }
    }

    private fun promptConflictChoice(fileName: String): ConflictDecision {
        val latch = java.util.concurrent.CountDownLatch(1)
        var choice = ConflictChoice.SKIP
        var applyAll = false
        runOnUiThread {
            val content = layoutInflater.inflate(R.layout.dialog_conflict_resolution, null)
            val applyAllCheck = content.findViewById<android.widget.CheckBox>(R.id.applyToAllCheck)
            AlertDialog.Builder(this)
                .setTitle(getString(R.string.conflict_title))
                .setMessage(getString(R.string.conflict_message, fileName))
                .setView(content)
                .setCancelable(false)
                .setPositiveButton(R.string.conflict_overwrite) { _, _ ->
                    choice = ConflictChoice.OVERWRITE
                    applyAll = applyAllCheck.isChecked
                    latch.countDown()
                }
                .setNeutralButton(R.string.conflict_rename) { _, _ ->
                    choice = ConflictChoice.RENAME
                    applyAll = applyAllCheck.isChecked
                    latch.countDown()
                }
                .setNegativeButton(R.string.conflict_skip) { _, _ ->
                    choice = ConflictChoice.SKIP
                    applyAll = applyAllCheck.isChecked
                    latch.countDown()
                }
                .show()
        }
        latch.await()
        return ConflictDecision(choice, applyAll)
    }

    private fun getSavedTreeUri(): Uri? {
        val value = preferences.getString(KEY_COPY_TREE_URI, null) ?: return null
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

    private fun loadPreferences() {
        val modeValue = preferences.getString(KEY_MODE, VideoMode.FOLDERS.name)
        val displayValue = preferences.getString(KEY_DISPLAY, VideoDisplayMode.LIST.name)
        val tileSpanValue = preferences.getInt(KEY_TILE_SPAN, 2)
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
        tileSpanCount = when (tileSpanValue) {
            2, 3, 4 -> tileSpanValue
            else -> 2
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

    private fun saveTileSpanCount() {
        preferences.edit().putInt(KEY_TILE_SPAN, tileSpanCount).apply()
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
        private const val KEY_MODE = "video_mode"
        private const val KEY_DISPLAY = "video_display"
        private const val KEY_TILE_SPAN = "video_tile_span"
        private const val KEY_SORT = "video_sort"
        private const val KEY_SORT_ORDER = "video_sort_order"
        private const val KEY_COPY_TREE_URI = "copy_tree_uri"
        private const val PROGRESS_MAX = 1000
        private const val PROGRESS_UPDATE_MS = 200L
        private const val TAG_DELETE = "NsPlayerDelete"
    }

    private enum class TransferOperation {
        COPY,
        MOVE,
        DELETE
    }
}
