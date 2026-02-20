package com.nshell.nsplayer.ui.main

import android.app.Activity
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.ImageButton
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import com.nshell.nsplayer.ui.base.BaseActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.ViewModelProvider
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.nshell.nsplayer.R
import com.nshell.nsplayer.ui.settings.SettingsViewModel

class MainActivity : BaseActivity() {
    internal lateinit var statusText: TextView
    internal lateinit var emptyText: TextView
    internal lateinit var folderTitle: TextView
    internal lateinit var folderHeader: View
    internal lateinit var headerBackButton: View
    internal lateinit var titleText: TextView
    internal lateinit var selectionBar: View
    internal lateinit var selectionAllButton: ImageButton
    internal lateinit var selectionMoveButton: Button
    internal lateinit var selectionCopyButton: Button
    internal lateinit var selectionDeleteButton: Button
    internal lateinit var adapter: VideoListAdapter
    internal lateinit var viewModel: VideoBrowserViewModel
    internal lateinit var settingsViewModel: SettingsViewModel
    internal lateinit var selectionController: SelectionController
    internal lateinit var transferController: TransferController
    internal lateinit var list: RecyclerView
    internal var browserState = VideoBrowserState()
    internal var initialSettingsApplied = false
    internal var pendingRename: RenameRequest? = null
    internal var pendingFolderRename: FolderRenameRequest? = null
    internal val preferences by lazy { getSharedPreferences(PREFS, MODE_PRIVATE) }

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

    internal val renamePermissionLauncher = registerForActivityResult(
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
            Toast.makeText(
                this,
                getString(R.string.rename_permission_denied),
                Toast.LENGTH_SHORT
            ).show()
        }
    }

    internal val folderRenameTreeLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri ->
        val pending = pendingFolderRename
        pendingFolderRename = null
        if (pending == null) {
            return@registerForActivityResult
        }
        if (uri == null) {
            Toast.makeText(
                this,
                getString(R.string.rename_folder_pick_cancelled),
                Toast.LENGTH_SHORT
            ).show()
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
            launchCopyDestination = { uri -> copyDestinationLauncher.launch(uri) },
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

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != REQUEST_PERMISSION) {
            return
        }
        if (hasVideoPermission()) {
            loadIfPermitted()
        } else {
            statusText.text = getString(R.string.permission_denied)
            statusText.visibility = View.VISIBLE
        }
    }

    companion object {
        internal const val REQUEST_PERMISSION = 1001
        internal const val PREFS = "nsplayer_prefs"
        internal const val KEY_FOLDER_RENAME_TREE_URI = "folder_rename_tree_uri"
        internal const val VOLUME_PREFIX = "volume:"
    }
}
