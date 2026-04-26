package com.nshell.nsplayer.ui.main

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.os.Parcelable
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
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import com.google.android.gms.ads.AdRequest
import com.google.android.gms.ads.AdView
import com.nshell.nsplayer.R
import com.nshell.nsplayer.data.repository.MediaStoreVideoRepository
import com.nshell.nsplayer.ui.settings.SettingsViewModel
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class MainActivity : BaseActivity() {
    internal lateinit var statusText: TextView
    internal lateinit var emptyText: TextView
    internal lateinit var folderTitle: TextView
    internal lateinit var folderHeader: View
    internal lateinit var headerBackButton: View
    internal lateinit var titleText: TextView
    internal lateinit var selectionBar: View
    internal lateinit var selectionPlayButton: ImageButton
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
    internal lateinit var refreshLayout: SwipeRefreshLayout
    internal var browserState = VideoBrowserState()
    internal var initialSettingsApplied = false
    internal var restoredFromSavedState = false
    internal var pendingRestoredSelectionKeys: ArrayList<String>? = null
    internal var pendingListLayoutState: Parcelable? = null
    internal var pendingRename: RenameRequest? = null
    internal var pendingFolderRename: FolderRenameRequest? = null
    internal val preferences by lazy { getSharedPreferences(PREFS, MODE_PRIVATE) }
    private var bannerAdView: AdView? = null
    private val playlistExecutor: ExecutorService = Executors.newSingleThreadExecutor()

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
        selectionPlayButton = findViewById(R.id.selectionPlayButton)
        selectionAllButton = findViewById(R.id.selectionAllButton)
        selectionMoveButton = findViewById(R.id.selectionMoveButton)
        selectionCopyButton = findViewById(R.id.selectionCopyButton)
        selectionDeleteButton = findViewById(R.id.selectionDeleteButton)
        bannerAdView = findViewById(R.id.mainBannerAdView)
        bannerAdView?.loadAd(AdRequest.Builder().build())

        list = findViewById(R.id.list)
        refreshLayout = findViewById(R.id.refreshLayout)
        refreshLayout.setColorSchemeResources(R.color.brand_green)
        list.isVerticalScrollBarEnabled = true
        list.isScrollbarFadingEnabled = true
        list.scrollBarDefaultDelayBeforeFade = 600
        list.scrollBarFadeDuration = 350
        list.itemAnimator = null
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
        refreshLayout.setOnRefreshListener {
            loadIfPermitted(useCache = false, showRefreshing = true)
        }

        viewModel = ViewModelProvider(this)[VideoBrowserViewModel::class.java]
        restoredFromSavedState = restoreNavigationState(savedInstanceState)
        restoreTransientUiState(savedInstanceState)
        settingsViewModel = ViewModelProvider(this)[SettingsViewModel::class.java]
        viewModel.getItems().observe(this) { renderItems(it) }
        viewModel.getLoading().observe(this) { renderLoading(it) }
        viewModel.getRefreshing().observe(this) { refreshing ->
            refreshLayout.isRefreshing = refreshing == true
        }
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
        selectionPlayButton.setOnClickListener { playSelectedItems() }

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

    override fun onResume() {
        super.onResume()
        bannerAdView?.resume()
        settingsViewModel.refresh()
    }

    override fun onPause() {
        bannerAdView?.pause()
        super.onPause()
    }

    override fun onDestroy() {
        bannerAdView?.destroy()
        bannerAdView = null
        playlistExecutor.shutdown()
        super.onDestroy()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        val current = viewModel.getState().value ?: browserState
        outState.putString(STATE_MODE, current.currentMode.name)
        outState.putBoolean(STATE_IN_FOLDER, current.inFolderVideos)
        outState.putString(STATE_BUCKET_ID, current.selectedBucketId)
        outState.putString(STATE_BUCKET_NAME, current.selectedBucketName)
        outState.putString(STATE_HIERARCHY_PATH, current.hierarchyPath)
        val selectedKeys = adapter.getSelectedKeys()
        if (selectedKeys.isNotEmpty()) {
            outState.putStringArrayList(STATE_SELECTION_KEYS, selectedKeys)
        }
        list.layoutManager?.onSaveInstanceState()?.let { state ->
            outState.putParcelable(STATE_LIST_LAYOUT, state)
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
        if (hasVideoPermission()) {
            loadIfPermitted()
        } else {
            statusText.text = getString(R.string.permission_denied)
            statusText.visibility = View.VISIBLE
        }
    }

    private fun playSelectedItems() {
        val selected = adapter.getSelectedItems()
        if (selected.isEmpty()) {
            Toast.makeText(this, getString(R.string.playlist_empty), Toast.LENGTH_SHORT).show()
            return
        }
        Toast.makeText(this, getString(R.string.playlist_loading), Toast.LENGTH_SHORT).show()
        val current = viewModel.getState().value ?: browserState
        val sortMode = current.sortMode
        val sortOrder = current.sortOrder
        val nomediaEnabled = current.nomediaEnabled
        val searchUseAll = current.searchFoldersUseAll
        val searchFolders = current.searchFolders
        val resolver = contentResolver
        val repository = MediaStoreVideoRepository()
        playlistExecutor.execute {
            val collected = mutableListOf<DisplayItem>()
            for (item in selected) {
                when (item.type) {
                    DisplayItem.Type.VIDEO -> {
                        if (!item.contentUri.isNullOrEmpty()) {
                            collected.add(item)
                        }
                    }
                    DisplayItem.Type.FOLDER -> {
                        val bucket = item.bucketId ?: continue
                        collected.addAll(
                            repository.loadVideosInFolder(
                                bucket,
                                sortMode,
                                sortOrder,
                                resolver,
                                nomediaEnabled,
                                searchUseAll,
                                searchFolders
                            )
                        )
                    }
                    DisplayItem.Type.HIERARCHY -> {
                        val path = item.bucketId ?: continue
                        collected.addAll(
                            repository.loadVideosUnderHierarchy(
                                path,
                                sortMode,
                                sortOrder,
                                resolver,
                                nomediaEnabled,
                                searchUseAll,
                                searchFolders
                            )
                        )
                    }
                }
            }
            val unique = LinkedHashMap<String, DisplayItem>()
            for (item in collected) {
                val uri = item.contentUri ?: continue
                if (!unique.containsKey(uri)) {
                    unique[uri] = item
                }
            }
            val playlist = unique.values.toList()
            runOnUiThread {
                if (playlist.isEmpty()) {
                    Toast.makeText(this, getString(R.string.playlist_empty), Toast.LENGTH_SHORT).show()
                    return@runOnUiThread
                }
                startPlaylist(playlist)
                selectionController.clearSelection()
            }
        }
    }

    private fun startPlaylist(items: List<DisplayItem>) {
        val uris = ArrayList<String>(items.size)
        val titles = ArrayList<String>(items.size)
        items.forEach { item ->
            val uri = item.contentUri ?: return@forEach
            uris.add(uri)
            titles.add(item.title)
        }
        if (uris.isEmpty()) {
            Toast.makeText(this, getString(R.string.playlist_empty), Toast.LENGTH_SHORT).show()
            return
        }
        val intent = com.nshell.nsplayer.ui.player.PlayerActivity.createLaunchIntent(this)
        intent.putStringArrayListExtra(
            com.nshell.nsplayer.ui.player.PlayerActivity.EXTRA_PLAYLIST_URIS,
            uris
        )
        intent.putStringArrayListExtra(
            com.nshell.nsplayer.ui.player.PlayerActivity.EXTRA_PLAYLIST_TITLES,
            titles
        )
        intent.putExtra(com.nshell.nsplayer.ui.player.PlayerActivity.EXTRA_PLAYLIST_INDEX, 0)
        startActivity(intent)
    }

    companion object {
        internal const val REQUEST_PERMISSION = 1001
        internal const val PREFS = "nsplayer_prefs"
        internal const val KEY_FOLDER_RENAME_TREE_URI = "folder_rename_tree_uri"
        internal const val VOLUME_PREFIX = "volume:"
        private const val STATE_MODE = "state_mode"
        private const val STATE_IN_FOLDER = "state_in_folder"
        private const val STATE_BUCKET_ID = "state_bucket_id"
        private const val STATE_BUCKET_NAME = "state_bucket_name"
        private const val STATE_HIERARCHY_PATH = "state_hierarchy_path"
        private const val STATE_SELECTION_KEYS = "state_selection_keys"
        private const val STATE_LIST_LAYOUT = "state_list_layout"
    }

    private fun restoreNavigationState(savedInstanceState: Bundle?): Boolean {
        if (savedInstanceState == null) {
            return false
        }
        val modeName = savedInstanceState.getString(STATE_MODE) ?: return false
        val mode = runCatching { VideoMode.valueOf(modeName) }.getOrNull() ?: return false
        val hierarchyPath = savedInstanceState.getString(STATE_HIERARCHY_PATH) ?: ""
        val bucketId = savedInstanceState.getString(STATE_BUCKET_ID)
        val bucketName = savedInstanceState.getString(STATE_BUCKET_NAME)
        val inFolder = mode == VideoMode.FOLDERS &&
            savedInstanceState.getBoolean(STATE_IN_FOLDER, false) &&
            !bucketId.isNullOrEmpty()
        viewModel.updateState {
            it.copy(
                currentMode = mode,
                inFolderVideos = inFolder,
                selectedBucketId = if (inFolder) bucketId else null,
                selectedBucketName = if (inFolder) bucketName else null,
                hierarchyPath = if (mode == VideoMode.HIERARCHY) hierarchyPath else ""
            )
        }
        return true
    }

    private fun restoreTransientUiState(savedInstanceState: Bundle?) {
        if (savedInstanceState == null) {
            return
        }
        pendingRestoredSelectionKeys = savedInstanceState.getStringArrayList(STATE_SELECTION_KEYS)
        @Suppress("DEPRECATION")
        val layoutState = savedInstanceState.getParcelable<Parcelable>(STATE_LIST_LAYOUT)
        pendingListLayoutState = layoutState
    }
}
