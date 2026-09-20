package com.nshell.nsplayer.ui.player

import android.app.PictureInPictureParams
import android.content.ContentUris
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.media.AudioManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.provider.MediaStore
import android.provider.OpenableColumns
import android.util.Rational
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.ImageButton
import android.widget.SeekBar
import android.widget.TextView
import com.nshell.nsplayer.data.recent.RecentPlaybackStore
import com.nshell.nsplayer.data.settings.SettingsRepository
import com.nshell.nsplayer.ui.base.BaseActivity
import com.nshell.nsplayer.ui.base.themeColor
import com.nshell.nsplayer.R
import androidx.activity.result.contract.ActivityResultContracts
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import androidx.appcompat.widget.SwitchCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.common.VideoSize
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.SeekParameters
import androidx.media3.ui.PlayerView
import java.io.File
import java.io.InterruptedIOException
import java.nio.charset.Charset
import java.util.Locale
import java.util.concurrent.Executors

class PlayerActivity : BaseActivity() {
    private lateinit var playerView: PlayerView
    private var player: ExoPlayer? = null
    private lateinit var overlayContainer: View
    private lateinit var topBar: View
    private lateinit var bottomBar: View
    private lateinit var playPauseButton: ImageButton
    private lateinit var prevButton: ImageButton
    private lateinit var nextButton: ImageButton
    private lateinit var rotateButton: ImageButton
    private lateinit var pipButton: ImageButton
    private lateinit var seekBar: SeekBar
    private lateinit var positionText: TextView
    private lateinit var durationText: TextView
    private lateinit var titleText: TextView
    private lateinit var gestureText: TextView
    private lateinit var subtitleButton: ImageButton
    private lateinit var speedButton: TextView
    private lateinit var repeatButton: ImageButton
    private lateinit var shuffleButton: ImageButton
    private lateinit var resumeButton: TextView
    private lateinit var loadingSpinner: View
    private lateinit var errorText: TextView

    private val uiHandler = Handler(Looper.getMainLooper())
    private val seekController = SeekController { android.os.SystemClock.elapsedRealtime() }
    private val subtitleResolver = Executors.newSingleThreadExecutor()
    private val mediaInfoResolver = Executors.newSingleThreadExecutor()
    private val subtitleCandidatesByUri = mutableMapOf<Uri, List<SubtitleSource>>()
    private val pendingSubtitleRequests = mutableSetOf<Pair<Long, Uri>>()
    private val preparedSubtitleUris = mutableMapOf<SubtitleCacheKey, Uri>()
    private val pendingSubtitleConversions = mutableSetOf<Pair<Long, SubtitleCacheKey>>()
    private val subtitleCacheFiles = mutableSetOf<File>()
    private val progressUpdater = object : Runnable {
        override fun run() {
            val activePlayer = player
            if (activePlayer != null) {
                val seekUi = seekController.onPlayerProgress(activePlayer.toPlaybackSnapshot())
                if (overlayContainer.visibility == View.VISIBLE && !isInPictureInPictureMode) {
                    updateProgress(seekUi.displayedPositionMs, activePlayer.duration)
                }
            }
            uiHandler.postDelayed(this, UI_UPDATE_INTERVAL_MS)
        }
    }
    private val hideOverlayRunnable = Runnable { overlayContainer.visibility = View.GONE }
    private val hideGestureTextRunnable = Runnable { gestureText.visibility = View.GONE }
    private val hideResumeButtonRunnable = Runnable { resumeButton.visibility = View.GONE }
    private val showLoadingSpinnerRunnable = Runnable {
        pendingLoadingSpinner = false
        loadingSpinner.visibility = View.VISIBLE
    }

    private lateinit var gestureDetector: GestureDetector
    private var swipeThresholdPx = 0f
    private var isAdjusting = false
    private var swipeMode = SwipeMode.NONE
    private var startX = 0f
    private var startY = 0f
    private var adjustLeftSide = false
    private var startBrightness = -1f
    private var startVolume = 0
    private var swipeSeekStartPositionMs = 0L
    private var maxVolume = 0
    private var audioManager: AudioManager? = null
    private lateinit var videoUri: Uri
    private var playlistEntries: List<PlaylistEntry> = emptyList()
    private var playlistIndex: Int = 0
    private var userOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED

    private val subtitlePreferences by lazy { getSharedPreferences(PREFS, MODE_PRIVATE) }
    private val recentPlaybackStore by lazy { RecentPlaybackStore(this) }
    private val settingsRepository by lazy { SettingsRepository(this) }
    private var autoPipEnabled = false
    private var supportsPictureInPicture = false
    private var subtitleEnabled = false
    private var preferredSubtitleLanguage: String? = null
    private var preferredSubtitleEncoding = ENCODING_UTF8
    private var selectedSubtitle: SubtitleSource? = null
    private var subtitleSelectionMode = SubtitleSelectionMode.AUTO
    private var subtitleCandidates: List<SubtitleSource> = emptyList()
    private var subtitleDialogSelectValue: TextView? = null
    private var subtitleDialogEnableSwitch: SwitchCompat? = null
    private var updatingSubtitleDialog = false
    private var subtitleSelectionRevision = 0L
    private var playbackSpeed = 1.0f
    private var repeatMode = Player.REPEAT_MODE_OFF
    private var shuffleEnabled = false
    private var pendingResumePrompt = false
    private var pendingLoadingSpinner = false
    private var playbackSessionId = 1L
    @Volatile
    private var subtitleResolveGeneration = 0L
    @Volatile
    private var subtitlePreparationGeneration = 0L
    @Volatile
    private var playerActivityDestroyed = false
    private var replacingSubtitleItem = false
    private var renderedIsPlaying: Boolean? = null
    private var renderedPositionSeconds = Long.MIN_VALUE
    private var renderedDurationSeconds = Long.MIN_VALUE

    private val subtitlePickerLauncher =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri == null) {
                return@registerForActivityResult
            }
            try {
                contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION
                )
            } catch (_: SecurityException) {
                // Ignore if persistable permission is not granted.
            }
            val generation = subtitleResolveGeneration
            val selectionRevision = ++subtitleSelectionRevision
            val targetVideo = videoUri
            mediaInfoResolver.execute {
                if (playerActivityDestroyed || generation != subtitleResolveGeneration) {
                    return@execute
                }
                val name = queryDisplayName(uri) ?: uri.lastPathSegment
                    ?: getString(R.string.subtitle_settings)
                val source = SubtitleSource(
                    uri, name, guessSubtitleMimeType(name), name.substringAfterLast('.', "")
                )
                uiHandler.post {
                    if (playerActivityDestroyed || generation != subtitleResolveGeneration ||
                        videoUri != targetVideo || selectionRevision != subtitleSelectionRevision
                    ) {
                        return@post
                    }
                    selectedSubtitle = source
                    subtitleSelectionMode = SubtitleSelectionMode.MANUAL
                    subtitleEnabled = true
                    persistSubtitleEnabled()
                    subtitleDialogSelectValue?.text = source.label
                    updateSubtitleDialogEnabled(true)
                    applySubtitleSelection(source)
                }
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_player)

        supportActionBar?.hide()
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        hideSystemUi()

        playerView = findViewById(R.id.playerView)
        overlayContainer = findViewById(R.id.overlayContainer)
        topBar = findViewById(R.id.topBar)
        bottomBar = findViewById(R.id.bottomBar)
        playPauseButton = findViewById(R.id.playPauseButton)
        prevButton = findViewById(R.id.prevButton)
        nextButton = findViewById(R.id.nextButton)
        rotateButton = findViewById(R.id.rotateButton)
        pipButton = findViewById(R.id.pipButton)
        seekBar = findViewById(R.id.seekBar)
        positionText = findViewById(R.id.positionText)
        durationText = findViewById(R.id.durationText)
        titleText = findViewById(R.id.titleText)
        gestureText = findViewById(R.id.gestureText)
        subtitleButton = findViewById(R.id.subtitleButton)
        speedButton = findViewById(R.id.speedButton)
        repeatButton = findViewById(R.id.repeatButton)
        shuffleButton = findViewById(R.id.shuffleButton)
        resumeButton = findViewById(R.id.resumeButton)
        loadingSpinner = findViewById(R.id.loadingSpinner)
        errorText = findViewById(R.id.errorText)

        audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager?
        if (audioManager != null) {
            maxVolume = audioManager?.getStreamMaxVolume(AudioManager.STREAM_MUSIC) ?: 0
        }
        supportsPictureInPicture =
            packageManager.hasSystemFeature(PackageManager.FEATURE_PICTURE_IN_PICTURE)

        swipeThresholdPx = resources.displayMetrics.density * 8f
        gestureDetector = GestureDetector(this, GestureListener())

        playPauseButton.setOnClickListener { togglePlayback() }
        prevButton.setOnClickListener { playPrevious() }
        nextButton.setOnClickListener { playNext() }
        rotateButton.setOnClickListener { toggleOrientation() }
        pipButton.setOnClickListener { enterPipMode() }
        subtitleButton.setOnClickListener { showSubtitleSettingsDialog() }
        speedButton.setOnClickListener { showSpeedDialog() }
        repeatButton.setOnClickListener { toggleRepeatMode() }
        shuffleButton.setOnClickListener { toggleShuffleMode() }
        resumeButton.setOnClickListener { restartFromBeginning() }

        seekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(bar: SeekBar, progress: Int, fromUser: Boolean) {
                if (fromUser) {
                    player?.let { activePlayer ->
                        val seekUi = seekController.updatePreview(
                            progress.toLong(),
                            activePlayer.toPlaybackSnapshot()
                        )
                        updateProgress(seekUi.displayedPositionMs, activePlayer.duration)
                    }
                }
            }

            override fun onStartTrackingTouch(bar: SeekBar) {
                player?.let { seekController.startPreview(it.toPlaybackSnapshot()) }
            }

            override fun onStopTrackingTouch(bar: SeekBar) {
                val activePlayer = player
                if (activePlayer != null) {
                    seekController.updatePreview(bar.progress.toLong(), activePlayer.toPlaybackSnapshot())
                    seekController.commitPreview(SeekSource.SEEK_BAR, activePlayer.toPlaybackSnapshot())
                        ?.let(::dispatchSeek)
                }
                scheduleOverlayHide()
            }
        })

        val root = findViewById<View>(R.id.playerRoot)
        val touchListener = View.OnTouchListener { v, event ->
            if (event.actionMasked == MotionEvent.ACTION_DOWN) {
                showOverlay()
            }
            if (isTouchOnControls(event)) {
                return@OnTouchListener false
            }
            val handled = gestureDetector.onTouchEvent(event)
            handleSwipeAdjustments(event, v.width, v.height)
            handled || isAdjusting
        }
        root.setOnTouchListener(touchListener)
        playerView.setOnTouchListener(touchListener)
        overlayContainer.setOnTouchListener(touchListener)

        if (savedInstanceState != null) {
            userOrientation = savedInstanceState.getInt(
                STATE_USER_ORIENTATION,
                ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
            )
        }

        if (!loadPlaylistFromIntent()) {
            finish()
            return
        }
        titleText.text = playlistEntries.getOrNull(playlistIndex)?.title ?: titleText.text
        requestExternalVideoTitle()
        loadSubtitlePreferences()
        loadPlaybackOptions()
        loadPlaybackSpeed()
        requestNearbySubtitleCandidates()
        updateSubtitleButtonState()
        updateRepeatButton()
        updateShuffleButton()
        updateNavigationButtons()
        updateSpeedButtonLabel()
        refreshPipSettings()
        if (userOrientation != ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED) {
            requestedOrientation = userOrientation
        }
    }

    override fun onStart() {
        super.onStart()
        initializePlayer()
        updatePictureInPictureParams()
        uiHandler.post(progressUpdater)
        scheduleOverlayHide()
    }

    override fun onResume() {
        super.onResume()
        refreshPipSettings()
    }

    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        if (autoPipEnabled && Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
            enterPipMode()
        }
    }

    override fun onStop() {
        super.onStop()
        uiHandler.removeCallbacks(progressUpdater)
        uiHandler.removeCallbacks(hideOverlayRunnable)
        uiHandler.removeCallbacks(hideGestureTextRunnable)
        uiHandler.removeCallbacks(hideResumeButtonRunnable)
        uiHandler.removeCallbacks(showLoadingSpinnerRunnable)
        pendingLoadingSpinner = false
        loadingSpinner.visibility = View.GONE
        saveRecentPlaybackSnapshot()
        saveResumePosition()
        releasePlayer()
    }

    override fun onDestroy() {
        playerActivityDestroyed = true
        subtitleResolveGeneration++
        subtitleResolver.shutdownNow()
        mediaInfoResolver.shutdownNow()
        clearSubtitleCache()
        super.onDestroy()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putInt(STATE_USER_ORIENTATION, userOrientation)
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) {
            hideSystemUi()
        }
    }

    override fun onPictureInPictureModeChanged(
        isInPictureInPictureMode: Boolean,
        newConfig: Configuration
    ) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
        if (isInPictureInPictureMode) {
            uiHandler.removeCallbacks(hideOverlayRunnable)
            overlayContainer.visibility = View.GONE
            resumeButton.visibility = View.GONE
        } else {
            player?.let { applyAutoOrientation(it.videoSize) }
            showOverlay()
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handlePlaybackIntent(intent)
    }

    private fun initializePlayer() {
        if (player != null || playlistEntries.isEmpty()) {
            return
        }
        val loadControl = DefaultLoadControl.Builder()
            .setBackBuffer(SEEK_BACK_BUFFER_MS, true)
            .setBufferDurationsMs(
                DefaultLoadControl.DEFAULT_MIN_BUFFER_MS,
                DefaultLoadControl.DEFAULT_MAX_BUFFER_MS,
                BUFFER_FOR_PLAYBACK_AFTER_SEEK_MS,
                DefaultLoadControl.DEFAULT_BUFFER_FOR_PLAYBACK_AFTER_REBUFFER_MS
            )
            .build()
        player = ExoPlayer.Builder(this)
            .setLoadControl(loadControl)
            .setSeekParameters(SeekParameters.CLOSEST_SYNC)
            .build()
        playerView.player = player
        player?.addListener(object : Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                player?.let { seekController.onPlayerProgress(it.toPlaybackSnapshot()) }
                updatePlaybackUi()
            }

            override fun onIsLoadingChanged(isLoading: Boolean) {
                player?.let { seekController.onPlayerProgress(it.toPlaybackSnapshot()) }
                updatePlaybackUi()
            }

            override fun onPlaybackStateChanged(state: Int) {
                val activePlayer = player ?: return
                val seekUi = seekController.onPlayerProgress(activePlayer.toPlaybackSnapshot())
                updatePlaybackUi()
                if (state == Player.STATE_READY) {
                    updateProgress(seekUi.displayedPositionMs, activePlayer.duration)
                    maybeShowResumePrompt()
                }
            }

            override fun onPositionDiscontinuity(
                oldPosition: Player.PositionInfo,
                newPosition: Player.PositionInfo,
                reason: Int
            ) {
                val activePlayer = player ?: return
                if (reason != Player.DISCONTINUITY_REASON_SEEK) {
                    return
                }
                val seekUi = seekController.onPositionDiscontinuity(activePlayer.toPlaybackSnapshot())
                updateProgress(seekUi.displayedPositionMs, activePlayer.duration)
                updatePlaybackUi()
            }

            override fun onRenderedFirstFrame() {
                val activePlayer = player ?: return
                seekController.onPlayerProgress(activePlayer.toPlaybackSnapshot())
                updatePlaybackUi()
            }

            override fun onVideoSizeChanged(videoSize: VideoSize) {
                applyAutoOrientation(videoSize)
            }

            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                if (replacingSubtitleItem) {
                    return
                }
                val activePlayer = player ?: return
                val index = activePlayer.currentMediaItemIndex
                if (index < 0 || index >= playlistEntries.size) {
                    return
                }
                seekController.onPlayerProgress(activePlayer.toPlaybackSnapshot())
                playlistIndex = index
                val entry = playlistEntries[index]
                if (entry.uri != videoUri) {
                    videoUri = entry.uri
                    titleText.text = entry.title
                    selectedSubtitle = null
                    subtitleCandidates = subtitleCandidatesByUri[videoUri].orEmpty()
                    loadSubtitlePreferences()
                    autoSelectSubtitleIfAvailable()
                    applySubtitleEnabled(subtitleEnabled)
                    val session = playbackSessionId
                    // Run after listener dispatch so replacement callbacks cannot re-enter this transition.
                    uiHandler.post {
                        if (player === activePlayer && playbackSessionId == session &&
                            activePlayer.currentMediaItemIndex == index && videoUri == entry.uri
                        ) {
                            attachResolvedSubtitleToCurrentItem()
                        }
                    }
                }
                requestNearbySubtitleCandidates()
                recordRecentPlayback(entry.uri, entry.title, 0L, 0L)
                updateNavigationButtons()
            }

            override fun onPlayerErrorChanged(error: PlaybackException?) {
                if (error != null) {
                    seekController.cancel()
                }
                updatePlaybackUi(error)
            }
        })

        val resumePosition = resolveResumePosition()
        val items = buildMediaItems()
        val startIndex = playlistIndex.coerceIn(0, (items.size - 1).coerceAtLeast(0))
        if (resumePosition > 0L) {
            player?.setMediaItems(items, startIndex, resumePosition)
        } else {
            player?.setMediaItems(items, startIndex, 0L)
        }
        player?.prepare()
        applySubtitleEnabled(subtitleEnabled)
        applyPlaybackOptions()
        applyPlaybackSpeed()
        player?.play()
        updatePlayPauseIcon(true)
        updateNavigationButtons()
        updatePictureInPictureParams()
    }

    private fun handlePlaybackIntent(newIntent: Intent) {
        if (!::titleText.isInitialized) {
            setIntent(newIntent)
            return
        }
        if (::videoUri.isInitialized) {
            saveRecentPlaybackSnapshot()
            saveResumePosition()
        }
        setIntent(newIntent)
        if (!loadPlaylistFromIntent()) {
            return
        }
        titleText.text = playlistEntries.getOrNull(playlistIndex)?.title ?: titleText.text
        pendingResumePrompt = false
        uiHandler.removeCallbacks(hideResumeButtonRunnable)
        resumeButton.visibility = View.GONE
        clearSubtitleCache()
        subtitleResolveGeneration++
        subtitleCandidatesByUri.clear()
        pendingSubtitleRequests.clear()
        selectedSubtitle = null
        loadSubtitlePreferences()
        loadPlaybackOptions()
        loadPlaybackSpeed()
        subtitleCandidates = emptyList()
        requestExternalVideoTitle()
        requestNearbySubtitleCandidates()
        updateSubtitleButtonState()
        updateRepeatButton()
        updateShuffleButton()
        updateNavigationButtons()
        updateSpeedButtonLabel()

        val activePlayer = player ?: return
        playbackSessionId++
        seekController.cancel()
        val resumePosition = resolveResumePosition()
        val items = buildMediaItems()
        val startIndex = playlistIndex.coerceIn(0, (items.size - 1).coerceAtLeast(0))
        if (resumePosition > 0L) {
            activePlayer.setMediaItems(items, startIndex, resumePosition)
        } else {
            activePlayer.setMediaItems(items, startIndex, 0L)
        }
        activePlayer.prepare()
        applySubtitleEnabled(subtitleEnabled)
        applyPlaybackOptions()
        applyPlaybackSpeed()
        activePlayer.play()
        updatePlayPauseIcon(true)
        updatePlaybackUi()
        updatePictureInPictureParams()
    }

    private fun refreshPipSettings() {
        autoPipEnabled = settingsRepository.loadAutoPipEnabled()
        pipButton.visibility = if (supportsPictureInPicture) View.VISIBLE else View.GONE
        pipButton.isEnabled = supportsPictureInPicture
        val pipColor = if (autoPipEnabled) {
            themeColor(com.google.android.material.R.attr.colorPrimary)
        } else {
            getColor(android.R.color.white)
        }
        pipButton.setColorFilter(pipColor)
        updatePictureInPictureParams()
    }

    private fun updatePictureInPictureParams() {
        if (!supportsPictureInPicture) {
            return
        }
        setPictureInPictureParams(createPictureInPictureParams())
    }

    private fun createPictureInPictureParams(): PictureInPictureParams {
        val builder = PictureInPictureParams.Builder()
        if (playerView.width > 0 && playerView.height > 0) {
            builder.setAspectRatio(Rational(playerView.width, playerView.height))
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            builder.setAutoEnterEnabled(autoPipEnabled)
        }
        return builder.build()
    }

    private fun enterPipMode(): Boolean {
        if (!supportsPictureInPicture || isInPictureInPictureMode || isFinishing || isDestroyed) {
            return false
        }
        if (player == null) {
            return false
        }
        updatePictureInPictureParams()
        return runCatching {
            enterPictureInPictureMode(createPictureInPictureParams())
        }.getOrDefault(false)
    }

    private fun releasePlayer() {
        seekController.cancel()
        playerView.player = null
        player?.release()
        player = null
    }

    private fun togglePlayback() {
        val activePlayer = player ?: return
        if (activePlayer.isPlaying) {
            activePlayer.pause()
            updatePlayPauseIcon(false)
        } else {
            activePlayer.play()
            updatePlayPauseIcon(true)
        }
        scheduleOverlayHide()
    }

    private fun playPrevious() {
        val activePlayer = player ?: return
        if (playlistEntries.size <= 1) {
            return
        }
        activePlayer.seekToPreviousMediaItem()
        showOverlay()
    }

    private fun playNext() {
        val activePlayer = player ?: return
        if (playlistEntries.size <= 1) {
            return
        }
        activePlayer.seekToNextMediaItem()
        showOverlay()
    }

    private fun updatePlayPauseIcon(isPlaying: Boolean) {
        if (renderedIsPlaying == isPlaying) {
            return
        }
        renderedIsPlaying = isPlaying
        val icon = if (isPlaying) {
            R.drawable.ic_pause
        } else {
            R.drawable.ic_play
        }
        playPauseButton.setImageResource(icon)
    }

    private fun updateNavigationButtons() {
        val hasPlaylist = playlistEntries.size > 1
        if (!hasPlaylist) {
            prevButton.isEnabled = false
            nextButton.isEnabled = false
            prevButton.alpha = 0.4f
            nextButton.alpha = 0.4f
            return
        }
        val canWrap = repeatMode == Player.REPEAT_MODE_ALL
        val hasPrev = canWrap ||
            (player?.hasPreviousMediaItem() ?: (playlistIndex > 0))
        val hasNext = canWrap ||
            (player?.hasNextMediaItem() ?: (playlistIndex < playlistEntries.lastIndex))
        prevButton.isEnabled = hasPrev
        nextButton.isEnabled = hasNext
        prevButton.alpha = if (hasPrev) 1f else 0.4f
        nextButton.alpha = if (hasNext) 1f else 0.4f
    }

    private fun updateProgress(positionMs: Long, durationMs: Long) {
        val positionSeconds = positionMs.coerceAtLeast(0L) / 1000L
        if (durationMs == C.TIME_UNSET || durationMs <= 0) {
            if (seekBar.isEnabled) {
                seekBar.isEnabled = false
            }
            if (renderedDurationSeconds != C.TIME_UNSET) {
                renderedDurationSeconds = C.TIME_UNSET
                durationText.text = "--:--"
            }
            if (renderedPositionSeconds != positionSeconds) {
                renderedPositionSeconds = positionSeconds
                positionText.text = formatTime(positionMs)
            }
            return
        }
        if (!seekBar.isEnabled) {
            seekBar.isEnabled = true
        }
        val safeDuration = durationMs.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
        if (seekBar.max != safeDuration) {
            seekBar.max = safeDuration
        }
        val safePosition = positionMs.coerceIn(0L, safeDuration.toLong()).toInt()
        if (seekBar.progress != safePosition) {
            seekBar.progress = safePosition
        }
        if (renderedPositionSeconds != positionSeconds) {
            renderedPositionSeconds = positionSeconds
            positionText.text = formatTime(positionMs)
        }
        val durationSeconds = durationMs / 1000L
        if (renderedDurationSeconds != durationSeconds) {
            renderedDurationSeconds = durationSeconds
            durationText.text = formatTime(durationMs)
        }
    }

    private fun Player.toPlaybackSnapshot(): PlaybackSnapshot {
        val knownDuration = duration.takeIf { it != C.TIME_UNSET && it > 0L }
        return PlaybackSnapshot(
            sessionId = playbackSessionId,
            mediaItemIndex = currentMediaItemIndex,
            positionMs = currentPosition.coerceAtLeast(0L),
            durationMs = knownDuration,
            isReady = playbackState == Player.STATE_READY,
            isLoading = isLoading
        )
    }

    private fun requestSeek(targetMs: Long, source: SeekSource) {
        val activePlayer = player ?: return
        dispatchSeek(seekController.seekTo(targetMs, source, activePlayer.toPlaybackSnapshot()))
    }

    private fun dispatchSeek(command: SeekCommand) {
        val activePlayer = player ?: return
        val parameters = when (command.accuracy) {
            SeekAccuracy.EXACT -> SeekParameters.EXACT
            SeekAccuracy.CLOSEST_SYNC -> SeekParameters.CLOSEST_SYNC
        }
        activePlayer.setSeekParameters(parameters)
        updateProgress(command.targetMs, activePlayer.duration)
        updatePlaybackUi()
        activePlayer.seekTo(command.targetMs)
    }

    private fun seekBy(deltaMs: Long) {
        val activePlayer = player ?: return
        dispatchSeek(
            seekController.seekBy(
                deltaMs,
                SeekSource.DOUBLE_TAP,
                activePlayer.toPlaybackSnapshot()
            )
        )
        showGestureText(if (deltaMs > 0) "+10s" else "-10s")
        scheduleOverlayHide()
    }

    private fun toggleOverlay() {
        if (overlayContainer.visibility == View.VISIBLE) {
            overlayContainer.visibility = View.GONE
            uiHandler.removeCallbacks(hideOverlayRunnable)
        } else {
            showOverlay()
        }
    }

    private fun showOverlay() {
        overlayContainer.visibility = View.VISIBLE
        player?.let { activePlayer ->
            val seekUi = seekController.onPlayerProgress(activePlayer.toPlaybackSnapshot())
            updateProgress(seekUi.displayedPositionMs, activePlayer.duration)
        }
        scheduleOverlayHide()
    }

    private fun scheduleOverlayHide() {
        overlayContainer.visibility = View.VISIBLE
        uiHandler.removeCallbacks(hideOverlayRunnable)
        uiHandler.postDelayed(hideOverlayRunnable, OVERLAY_AUTO_HIDE_MS)
    }

    private fun showGestureText(text: String) {
        gestureText.text = text
        gestureText.visibility = View.VISIBLE
        uiHandler.removeCallbacks(hideGestureTextRunnable)
        uiHandler.postDelayed(hideGestureTextRunnable, GESTURE_TEXT_HIDE_MS)
    }

    private fun isTouchOnControls(event: MotionEvent): Boolean {
        return isPointInsideView(event, playPauseButton) ||
            isPointInsideView(event, prevButton) ||
            isPointInsideView(event, nextButton) ||
            isPointInsideView(event, rotateButton) ||
            isPointInsideView(event, pipButton) ||
            isPointInsideView(event, seekBar) ||
            isPointInsideView(event, repeatButton) ||
            isPointInsideView(event, shuffleButton) ||
            isPointInsideView(event, speedButton) ||
            isPointInsideView(event, subtitleButton) ||
            isPointInsideView(event, resumeButton) ||
            isPointInsideView(event, positionText) ||
            isPointInsideView(event, durationText) ||
            isPointInsideView(event, topBar) ||
            isPointInsideView(event, bottomBar) ||
            isPointInsideView(event, loadingSpinner) ||
            isPointInsideView(event, errorText)
    }

    private fun isPointInsideView(event: MotionEvent, view: View?): Boolean {
        if (view == null || view.visibility != View.VISIBLE) {
            return false
        }
        val location = IntArray(2)
        view.getLocationOnScreen(location)
        val x = event.rawX
        val y = event.rawY
        return x >= location[0] &&
            x <= location[0] + view.width &&
            y >= location[1] &&
            y <= location[1] + view.height
    }

    private fun handleSwipeAdjustments(event: MotionEvent, width: Int, height: Int) {
        if (width <= 0 || height <= 0) {
            return
        }
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                startX = event.x
                startY = event.y
                adjustLeftSide = startX < width / 2f
                isAdjusting = false
                swipeMode = SwipeMode.NONE
                startBrightness = getCurrentBrightness()
                startVolume = audioManager?.getStreamVolume(AudioManager.STREAM_MUSIC) ?: 0
                swipeSeekStartPositionMs = player?.let {
                    seekController.interactivePosition(it.toPlaybackSnapshot())
                } ?: 0L
            }
            MotionEvent.ACTION_MOVE -> {
                val dy = event.y - startY
                val dx = event.x - startX
                    if (!isAdjusting) {
                    val absDx = kotlin.math.abs(dx)
                    val absDy = kotlin.math.abs(dy)
                    if (absDx <= swipeThresholdPx && absDy <= swipeThresholdPx) {
                        return
                    }
                    isAdjusting = true
                    swipeMode = if (absDx > absDy) {
                        SwipeMode.HORIZONTAL
                    } else {
                        SwipeMode.VERTICAL
                    }
                    if (swipeMode == SwipeMode.HORIZONTAL) {
                        player?.let { seekController.startPreview(it.toPlaybackSnapshot()) }
                    }
                }
                if (swipeMode == SwipeMode.HORIZONTAL) {
                    val activePlayer = player ?: return
                    val duration = activePlayer.duration
                    val seekRange = if (duration == C.TIME_UNSET || duration <= 0L) {
                        SWIPE_SEEK_MAX_RANGE_MS
                    } else {
                        duration.coerceAtMost(SWIPE_SEEK_MAX_RANGE_MS)
                    }
                    val seekDelta = (dx / width.toFloat()) * seekRange.toFloat()
                    val target = if (duration == C.TIME_UNSET || duration <= 0L) {
                        (swipeSeekStartPositionMs + kotlin.math.round(seekDelta).toLong()).coerceAtLeast(0L)
                    } else {
                        (swipeSeekStartPositionMs + kotlin.math.round(seekDelta).toLong()).coerceIn(0L, duration)
                    }
                    val seekUi = seekController.updatePreview(target, activePlayer.toPlaybackSnapshot())
                    updateProgress(seekUi.displayedPositionMs, duration)
                    val deltaLabel = formatTime(kotlin.math.abs(target - swipeSeekStartPositionMs))
                    val direction = if (target >= swipeSeekStartPositionMs) "+" else "-"
                    val positionLabel = formatTime(target)
                    val durationLabel = if (duration > 0 && duration != C.TIME_UNSET) {
                        formatTime(duration)
                    } else {
                        "--:--"
                    }
                    showGestureText("$direction$deltaLabel ($positionLabel/$durationLabel)")
                } else {
                    val delta = -dy / height
                    if (adjustLeftSide) {
                        val target = clamp(startBrightness + delta, 0.02f, 1f)
                        setWindowBrightness(target)
                        val percent = kotlin.math.round(target * 100f).toInt()
                        showGestureText("Brightness $percent%")
                    } else {
                        val manager = audioManager
                        if (manager != null && maxVolume > 0) {
                            val target = clampVolume(startVolume + kotlin.math.round(delta * maxVolume).toInt())
                            manager.setStreamVolume(AudioManager.STREAM_MUSIC, target, 0)
                            val percent = kotlin.math.round(target / maxVolume.toFloat() * 100f).toInt()
                            showGestureText("Volume $percent%")
                        }
                    }
                }
            }
            MotionEvent.ACTION_UP,
            MotionEvent.ACTION_CANCEL -> {
                if (swipeMode == SwipeMode.HORIZONTAL) {
                    val activePlayer = player
                    if (event.actionMasked == MotionEvent.ACTION_UP && activePlayer != null) {
                        seekController.commitPreview(SeekSource.SWIPE, activePlayer.toPlaybackSnapshot())
                            ?.let(::dispatchSeek)
                    } else if (activePlayer != null) {
                        seekController.cancel()
                        updateProgress(activePlayer.currentPosition, activePlayer.duration)
                    }
                }
                isAdjusting = false
                swipeMode = SwipeMode.NONE
                scheduleOverlayHide()
            }
        }
    }

    private fun getCurrentBrightness(): Float {
        val current = window.attributes.screenBrightness
        if (current >= 0f) {
            return current
        }
        return try {
            val system = Settings.System.getInt(contentResolver, Settings.System.SCREEN_BRIGHTNESS)
            clamp(system / 255f, 0.02f, 1f)
        } catch (_: Settings.SettingNotFoundException) {
            0.5f
        }
    }

    private fun setWindowBrightness(value: Float) {
        val params = window.attributes
        params.screenBrightness = value
        window.attributes = params
    }

    private fun clampVolume(target: Int): Int {
        return when {
            target < 0 -> 0
            target > maxVolume -> maxVolume
            else -> target
        }
    }

    private fun clamp(value: Float, min: Float, max: Float): Float {
        return kotlin.math.max(min, kotlin.math.min(value, max))
    }

    private fun formatTime(ms: Long): String {
        val totalSeconds = kotlin.math.max(0, ms / 1000)
        val seconds = totalSeconds % 60
        val minutes = (totalSeconds / 60) % 60
        val hours = totalSeconds / 3600
        return if (hours > 0) {
            String.format(Locale.US, "%d:%02d:%02d", hours, minutes, seconds)
        } else {
            String.format(Locale.US, "%02d:%02d", minutes, seconds)
        }
    }

    private fun hideSystemUi() {
        val controller = WindowInsetsControllerCompat(window, window.decorView)
        controller.hide(WindowInsetsCompat.Type.systemBars())
        controller.systemBarsBehavior =
            WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
    }

    private fun applyAutoOrientation(videoSize: VideoSize) {
        if (userOrientation != ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED ||
            videoSize.width <= 0 || videoSize.height <= 0 || isInPictureInPictureMode
        ) {
            return
        }
        val displayWidth = videoSize.width * videoSize.pixelWidthHeightRatio
        val landscape = if (videoSize.unappliedRotationDegrees % 180 == 0) {
            displayWidth > videoSize.height
        } else {
            videoSize.height > displayWidth
        }
        val orientation = if (landscape) {
            ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        } else {
            ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        }
        if (requestedOrientation != orientation) {
            requestedOrientation = orientation
        }
    }

    private fun toggleOrientation() {
        val current = resources.configuration.orientation
        userOrientation = if (current == Configuration.ORIENTATION_LANDSCAPE) {
            ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT
        } else {
            ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        }
        requestedOrientation = userOrientation
        showOverlay()
    }

    private fun showSubtitleSettingsDialog() {
        subtitleCandidates = subtitleCandidatesByUri[videoUri].orEmpty()
        if (!subtitleCandidatesByUri.containsKey(videoUri)) {
            requestSubtitleCandidates(videoUri, playlistIndex, subtitleResolveGeneration)
        }
        val content = layoutInflater.inflate(R.layout.dialog_subtitle_settings, null)
        val enableSwitch = content.findViewById<SwitchCompat>(R.id.subtitleEnableSwitch)
        val selectRow = content.findViewById<View>(R.id.subtitleSelectRow)
        val selectValue = content.findViewById<TextView>(R.id.subtitleSelectValue)
        val languageRow = content.findViewById<View>(R.id.subtitleLanguageRow)
        val languageValue = content.findViewById<TextView>(R.id.subtitleLanguageValue)
        val encodingRow = content.findViewById<View>(R.id.subtitleEncodingRow)
        val encodingValue = content.findViewById<TextView>(R.id.subtitleEncodingValue)

        enableSwitch.isChecked = subtitleEnabled
        selectValue.text = selectedSubtitle?.label ?: getString(R.string.subtitle_none)
        languageValue.text = getSubtitleLanguageLabel(preferredSubtitleLanguage)
        encodingValue.text = getSubtitleEncodingLabel(preferredSubtitleEncoding)

        subtitleDialogSelectValue = selectValue
        subtitleDialogEnableSwitch = enableSwitch

        enableSwitch.setOnCheckedChangeListener { _, isChecked ->
            if (updatingSubtitleDialog) {
                return@setOnCheckedChangeListener
            }
            subtitleSelectionRevision++
            subtitleEnabled = isChecked
            persistSubtitleEnabled()
            if (isChecked && selectedSubtitle == null) {
                subtitleSelectionMode = SubtitleSelectionMode.AUTO
                persistSubtitleSelection(selectedSubtitle)
                if (subtitleCandidates.isNotEmpty()) {
                    selectedSubtitle = preferredSubtitleCandidate(subtitleCandidates)
                    persistSubtitleSelection(selectedSubtitle)
                    selectValue.text =
                        selectedSubtitle?.label ?: getString(R.string.subtitle_none)
                    applySubtitleSelection(selectedSubtitle)
                    return@setOnCheckedChangeListener
                }
            } else if (!isChecked && selectedSubtitle == null) {
                subtitleSelectionMode = SubtitleSelectionMode.NONE
                persistSubtitleSelection(null)
            }
            applySubtitleEnabled(subtitleEnabled)
            if (isChecked) {
                attachResolvedSubtitleToCurrentItem()
            }
        }

        selectRow.setOnClickListener {
            showSubtitleSelectionDialog(selectValue, enableSwitch)
        }
        languageRow.setOnClickListener {
            showSubtitleLanguageDialog(languageValue)
        }
        encodingRow.setOnClickListener {
            showSubtitleEncodingDialog(encodingValue)
        }

        val dialog = MaterialAlertDialogBuilder(this)
            .setTitle(R.string.subtitle_settings)
            .setView(content)
            .setPositiveButton(R.string.confirm, null)
            .create()
        dialog.setOnDismissListener {
            subtitleDialogSelectValue = null
            subtitleDialogEnableSwitch = null
        }
        dialog.show()
    }

    private fun showSubtitleSelectionDialog(
        selectValue: TextView,
        enableSwitch: SwitchCompat
    ) {
        val options = mutableListOf<SubtitleChoice>()
        options.add(SubtitleChoice.None)
        orderedSubtitleCandidates(subtitleCandidates).forEach {
            options.add(SubtitleChoice.Source(it))
        }
        val current = selectedSubtitle
        if (current != null && options.none { it is SubtitleChoice.Source && it.source.uri == current.uri }) {
            options.add(SubtitleChoice.Source(current))
        }
        options.add(SubtitleChoice.Pick)

        val labels = options.map { choice ->
            when (choice) {
                SubtitleChoice.None -> getString(R.string.subtitle_none)
                SubtitleChoice.Pick -> getString(R.string.subtitle_select_file)
                is SubtitleChoice.Source -> choice.source.label
            }
        }.toTypedArray()

        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.subtitle_select)
            .setItems(labels) { _, which ->
                when (val choice = options[which]) {
                    SubtitleChoice.None -> {
                        subtitleSelectionRevision++
                        selectedSubtitle = null
                        subtitleSelectionMode = SubtitleSelectionMode.NONE
                        subtitleEnabled = false
                        persistSubtitleEnabled()
                        persistSubtitleSelection(null)
                        updateSubtitleDialogEnabled(false, enableSwitch)
                        selectValue.text = getString(R.string.subtitle_none)
                        applySubtitleEnabled(false)
                    }
                    SubtitleChoice.Pick -> {
                        subtitlePickerLauncher.launch(SUBTITLE_MIME_TYPES)
                    }
                    is SubtitleChoice.Source -> {
                        subtitleSelectionRevision++
                        selectedSubtitle = choice.source
                        subtitleSelectionMode = SubtitleSelectionMode.MANUAL
                        subtitleEnabled = true
                        persistSubtitleEnabled()
                        persistSubtitleSelection(selectedSubtitle)
                        updateSubtitleDialogEnabled(true, enableSwitch)
                        selectValue.text = choice.source.label
                        applySubtitleSelection(choice.source)
                    }
                }
            }
            .show()
    }

    private fun updateSubtitleDialogEnabled(
        enabled: Boolean,
        enableSwitch: SwitchCompat? = subtitleDialogEnableSwitch
    ) {
        updatingSubtitleDialog = true
        try {
            enableSwitch?.isChecked = enabled
        } finally {
            updatingSubtitleDialog = false
        }
    }

    private fun showSubtitleLanguageDialog(valueView: TextView) {
        val options = listOf(
            SubtitleLanguage(null, getString(R.string.language_system)),
            SubtitleLanguage("ko", getString(R.string.language_korean)),
            SubtitleLanguage("en", getString(R.string.language_english))
        )
        val labels = options.map { it.label }.toTypedArray()
        val currentIndex = options.indexOfFirst { it.code == preferredSubtitleLanguage }
            .takeIf { it >= 0 } ?: 0
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.subtitle_language)
            .setSingleChoiceItems(labels, currentIndex) { dialog, which ->
                subtitleSelectionRevision++
                preferredSubtitleLanguage = options[which].code
                subtitlePreferences.edit()
                    .putString(KEY_SUBTITLE_LANGUAGE, preferredSubtitleLanguage)
                    .apply()
                valueView.text = options[which].label
                if (subtitleSelectionMode == SubtitleSelectionMode.AUTO) {
                    selectedSubtitle = preferredSubtitleCandidate(subtitleCandidates)
                }
                subtitleDialogSelectValue?.text =
                    selectedSubtitle?.label ?: getString(R.string.subtitle_none)
                applySubtitleSelection(selectedSubtitle)
                dialog.dismiss()
            }
            .show()
    }

    private fun showSubtitleEncodingDialog(valueView: TextView) {
        val options = listOf(
            SubtitleEncoding(ENCODING_UTF8, getString(R.string.subtitle_encoding_utf8)),
            SubtitleEncoding(ENCODING_EUC_KR, getString(R.string.subtitle_encoding_euc_kr)),
            SubtitleEncoding(ENCODING_CP949, getString(R.string.subtitle_encoding_cp949))
        )
        val labels = options.map { it.label }.toTypedArray()
        val currentIndex = options.indexOfFirst { it.code == preferredSubtitleEncoding }
            .takeIf { it >= 0 } ?: 0
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.subtitle_encoding)
            .setSingleChoiceItems(labels, currentIndex) { dialog, which ->
                subtitleSelectionRevision++
                preferredSubtitleEncoding = options[which].code
                subtitlePreferences.edit()
                    .putString(KEY_SUBTITLE_ENCODING, preferredSubtitleEncoding)
                    .apply()
                valueView.text = options[which].label
                if (selectedSubtitle != null) {
                    applySubtitleSelection(selectedSubtitle)
                }
                dialog.dismiss()
            }
            .show()
    }

    private fun applySubtitleSelection(source: SubtitleSource?) {
        selectedSubtitle = source
        persistSubtitleSelection(source)
        applySubtitleEnabled(subtitleEnabled)
        updateSubtitleButtonState()
        val activePlayer = player ?: return
        if (!subtitleEnabled) {
            return
        }
        val configurations = if (source == null) {
            emptyList()
        } else {
            listOf(buildSubtitleConfiguration(source) ?: return)
        }
        val currentItem = activePlayer.currentMediaItem ?: return
        if (currentItem.localConfiguration?.subtitleConfigurations == configurations) {
            return
        }
        val position = activePlayer.currentPosition.coerceAtLeast(0L)
        val playWhenReady = activePlayer.playWhenReady
        val playbackState = activePlayer.playbackState
        val index = activePlayer.currentMediaItemIndex
        val previousSeekParameters = activePlayer.seekParameters
        // A subtitle change may recreate this source, but must not rebuild the entire playlist.
        replacingSubtitleItem = true
        try {
            activePlayer.setSeekParameters(SeekParameters.EXACT)
            activePlayer.replaceMediaItem(
                index,
                currentItem.buildUpon().setSubtitleConfigurations(configurations).build()
            )
            activePlayer.seekTo(index, position)
            if (playbackState != Player.STATE_IDLE && activePlayer.playbackState == Player.STATE_IDLE) {
                activePlayer.prepare()
            }
            activePlayer.playWhenReady = playWhenReady
        } finally {
            activePlayer.setSeekParameters(previousSeekParameters)
            replacingSubtitleItem = false
        }
    }

    private fun buildMediaItems(): List<MediaItem> {
        return playlistEntries.map { entry ->
            val subtitle = when {
                !subtitleEnabled -> null
                entry.uri == videoUri -> selectedSubtitle
                preferredSubtitleEncoding.equals(ENCODING_UTF8, true) ->
                    preferredSubtitleCandidate(subtitleCandidatesByUri[entry.uri].orEmpty())
                else -> null
            }
            buildMediaItem(entry, subtitle)
        }
    }

    private fun buildMediaItem(
        entry: PlaylistEntry,
        subtitle: SubtitleSource?
    ): MediaItem {
        val builder = MediaItem.Builder().setUri(entry.uri)
        if (subtitle != null) {
            buildSubtitleConfiguration(subtitle)?.let { configuration ->
                builder.setSubtitleConfigurations(listOf(configuration))
            }
        }
        if (entry.title.isNotEmpty()) {
            builder.setMediaMetadata(
                androidx.media3.common.MediaMetadata.Builder()
                    .setTitle(entry.title)
                    .build()
            )
        }
        return builder.build()
    }

    private fun buildSubtitleConfiguration(source: SubtitleSource): MediaItem.SubtitleConfiguration? {
        val uri = prepareSubtitleUri(source) ?: return null
        val builder = MediaItem.SubtitleConfiguration.Builder(uri)
            .setMimeType(source.mimeType)
            .setLabel(source.label)
            .setSelectionFlags(C.SELECTION_FLAG_DEFAULT)
        val detectedLanguage = source.nameMatch?.languageTag
        if (!detectedLanguage.isNullOrEmpty()) {
            builder.setLanguage(detectedLanguage)
        }
        return builder.build()
    }

    private fun applySubtitleEnabled(enabled: Boolean) {
        val activePlayer = player ?: return
        val updated = activePlayer.trackSelectionParameters
            .buildUpon()
            .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, !enabled)
            .build()
        activePlayer.trackSelectionParameters = updated
        updateSubtitleButtonState()
    }

    private fun resolveResumePosition(): Long {
        val info = loadResumeInfo() ?: return 0L
        if (info.positionMs < RESUME_MIN_SAVE_MS) {
            clearResumePosition()
            return 0L
        }
        if (info.durationMs > 0L && info.positionMs >= info.durationMs - RESUME_END_THRESHOLD_MS) {
            clearResumePosition()
            return 0L
        }
        pendingResumePrompt = true
        return (info.positionMs - RESUME_BACK_MS).coerceAtLeast(0L)
    }

    private fun loadResumeInfo(): ResumeInfo? {
        val key = resumePositionKey(videoUri)
        val position = subtitlePreferences.getLong(key, -1L)
        if (position <= 0L) {
            return null
        }
        val duration = subtitlePreferences.getLong(resumeDurationKey(videoUri), -1L)
        return ResumeInfo(position, duration)
    }

    private fun saveResumePosition() {
        val activePlayer = player ?: return
        val position = activePlayer.currentPosition
        if (position < RESUME_MIN_SAVE_MS) {
            clearResumePosition()
            return
        }
        val duration = activePlayer.duration
        if (duration != C.TIME_UNSET && duration > 0L &&
            position >= duration - RESUME_END_THRESHOLD_MS
        ) {
            clearResumePosition()
            return
        }
        subtitlePreferences.edit()
            .putLong(resumePositionKey(videoUri), position)
            .putLong(resumeDurationKey(videoUri), if (duration == C.TIME_UNSET) 0L else duration)
            .apply()
    }

    private fun saveRecentPlaybackSnapshot() {
        val activePlayer = player ?: return
        if (!::videoUri.isInitialized) {
            return
        }
        val duration = activePlayer.duration
        val safeDuration = if (duration == C.TIME_UNSET || duration < 0L) 0L else duration
        val title = titleText.text?.toString()?.takeIf { it.isNotBlank() }
            ?: playlistEntries.getOrNull(playlistIndex)?.title.orEmpty()
        recordRecentPlayback(videoUri, title, activePlayer.currentPosition, safeDuration)
    }

    private fun recordRecentPlayback(uri: Uri, title: String, positionMs: Long, durationMs: Long) {
        recentPlaybackStore.recordPlayback(uri, title, positionMs, durationMs)
    }

    private fun clearResumePosition() {
        subtitlePreferences.edit()
            .remove(resumePositionKey(videoUri))
            .remove(resumeDurationKey(videoUri))
            .apply()
    }

    private fun resumePositionKey(uri: Uri): String = KEY_RESUME_PREFIX + uri.toString()

    private fun resumeDurationKey(uri: Uri): String = KEY_RESUME_DURATION_PREFIX + uri.toString()

    private fun maybeShowResumePrompt() {
        if (!pendingResumePrompt) {
            return
        }
        pendingResumePrompt = false
        showResumeButton()
    }

    private fun showResumeButton() {
        resumeButton.visibility = View.VISIBLE
        uiHandler.removeCallbacks(hideResumeButtonRunnable)
        uiHandler.postDelayed(hideResumeButtonRunnable, RESUME_PROMPT_MS)
    }

    private fun restartFromBeginning() {
        uiHandler.removeCallbacks(hideResumeButtonRunnable)
        resumeButton.visibility = View.GONE
        clearResumePosition()
        requestSeek(0L, SeekSource.RESTART)
        showOverlay()
    }

    private fun showPlaybackError(error: PlaybackException) {
        val reason = error.message?.trim().orEmpty()
        errorText.text = if (reason.isNotEmpty()) {
            getString(R.string.playback_error_with_reason, reason)
        } else {
            getString(R.string.playback_error)
        }
        errorText.visibility = View.VISIBLE
        updateLoadingSpinner(false)
        overlayContainer.visibility = View.VISIBLE
        uiHandler.removeCallbacks(hideOverlayRunnable)
    }

    private fun clearPlaybackError() {
        errorText.visibility = View.GONE
    }

    private fun updateLoadingSpinner(visible: Boolean) {
        if (!visible) {
            pendingLoadingSpinner = false
            uiHandler.removeCallbacks(showLoadingSpinnerRunnable)
            loadingSpinner.visibility = View.GONE
            return
        }
        if (loadingSpinner.visibility == View.VISIBLE || pendingLoadingSpinner) {
            return
        }
        pendingLoadingSpinner = true
        uiHandler.postDelayed(showLoadingSpinnerRunnable, LOADING_SPINNER_DELAY_MS)
    }

    private fun updatePlaybackUi(explicitError: PlaybackException? = null) {
        val activePlayer = player ?: return
        val error = explicitError ?: activePlayer.playerError
        if (error != null) {
            showPlaybackError(error)
        } else {
            clearPlaybackError()
        }
        val buffering = activePlayer.playbackState == Player.STATE_BUFFERING || activePlayer.isLoading
        val seeking = seekController.isSeeking()
        updateLoadingSpinner((buffering || seeking) && error == null)
        updatePlayPauseIcon(activePlayer.isPlaying)
    }

    private fun loadPlaybackSpeed() {
        val saved = subtitlePreferences.getFloat(KEY_PLAYBACK_SPEED, 1.0f)
        playbackSpeed = saved.coerceIn(0.5f, 4.0f)
    }

    private fun persistPlaybackSpeed() {
        subtitlePreferences.edit()
            .putFloat(KEY_PLAYBACK_SPEED, playbackSpeed)
            .apply()
    }

    private fun applyPlaybackSpeed() {
        updateSpeedButtonLabel()
        val activePlayer = player ?: return
        activePlayer.setPlaybackParameters(PlaybackParameters(playbackSpeed, 1.0f))
    }

    private fun setPlaybackSpeed(speed: Float) {
        playbackSpeed = speed.coerceIn(0.5f, 4.0f)
        persistPlaybackSpeed()
        applyPlaybackSpeed()
        showOverlay()
    }

    private fun loadPlaybackOptions() {
        repeatMode = subtitlePreferences.getInt(KEY_REPEAT_MODE, Player.REPEAT_MODE_OFF)
        shuffleEnabled = subtitlePreferences.getBoolean(KEY_SHUFFLE_ENABLED, false)
    }

    private fun applyPlaybackOptions() {
        val activePlayer = player ?: return
        activePlayer.repeatMode = repeatMode
        activePlayer.shuffleModeEnabled = shuffleEnabled
        requestNearbySubtitleCandidates()
        updateRepeatButton()
        updateShuffleButton()
    }

    private fun persistPlaybackOptions() {
        subtitlePreferences.edit()
            .putInt(KEY_REPEAT_MODE, repeatMode)
            .putBoolean(KEY_SHUFFLE_ENABLED, shuffleEnabled)
            .apply()
    }

    private fun toggleRepeatMode() {
        repeatMode = when (repeatMode) {
            Player.REPEAT_MODE_OFF -> Player.REPEAT_MODE_ALL
            Player.REPEAT_MODE_ALL -> Player.REPEAT_MODE_ONE
            Player.REPEAT_MODE_ONE -> Player.REPEAT_MODE_OFF
            else -> Player.REPEAT_MODE_OFF
        }
        player?.repeatMode = repeatMode
        requestNearbySubtitleCandidates()
        persistPlaybackOptions()
        updateRepeatButton()
        showOverlay()
    }

    private fun toggleShuffleMode() {
        shuffleEnabled = !shuffleEnabled
        player?.shuffleModeEnabled = shuffleEnabled
        requestNearbySubtitleCandidates()
        persistPlaybackOptions()
        updateShuffleButton()
        showOverlay()
    }

    private fun updateRepeatButton() {
        val label = when (repeatMode) {
            Player.REPEAT_MODE_ONE -> getString(R.string.playback_repeat_one)
            Player.REPEAT_MODE_ALL -> getString(R.string.playback_repeat_all)
            else -> getString(R.string.playback_repeat_off)
        }
        repeatButton.contentDescription = label
        val icon = if (repeatMode == Player.REPEAT_MODE_ONE) {
            R.drawable.ic_repeat_one
        } else {
            R.drawable.ic_repeat
        }
        repeatButton.setImageResource(icon)
        val active = repeatMode != Player.REPEAT_MODE_OFF
        val color = if (active) {
            themeColor(com.google.android.material.R.attr.colorPrimary)
        } else {
            getColor(android.R.color.white)
        }
        repeatButton.setColorFilter(color)
        repeatButton.alpha = if (active) 1f else 1f
        updateNavigationButtons()
    }

    private fun updateShuffleButton() {
        val label = if (shuffleEnabled) {
            getString(R.string.playback_shuffle_on)
        } else {
            getString(R.string.playback_shuffle_off)
        }
        shuffleButton.contentDescription = label
        val color = if (shuffleEnabled) {
            themeColor(com.google.android.material.R.attr.colorPrimary)
        } else {
            getColor(android.R.color.white)
        }
        shuffleButton.setColorFilter(color)
        shuffleButton.alpha = if (shuffleEnabled) 1f else 0.7f
    }

    private fun showSpeedDialog() {
        val speeds = listOf(0.5f, 0.75f, 1.0f, 1.25f, 1.5f, 1.75f, 2.0f, 3.0f, 4.0f)
        val labels = speeds.map { formatSpeedLabel(it) }.toTypedArray()
        val checked = speeds.indexOfFirst { kotlin.math.abs(it - playbackSpeed) < 0.01f }
            .takeIf { it >= 0 } ?: 2
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.playback_speed)
            .setSingleChoiceItems(labels, checked) { dialog, which ->
                setPlaybackSpeed(speeds[which])
                dialog.dismiss()
            }
            .show()
    }

    private fun updateSpeedButtonLabel() {
        speedButton.text = formatSpeedLabel(playbackSpeed)
    }

    private fun formatSpeedLabel(speed: Float): String {
        val label = if (speed % 1f == 0f) {
            String.format(Locale.US, "%.1f", speed)
        } else {
            String.format(Locale.US, "%.2f", speed).trimEnd('0').trimEnd('.')
        }
        return "${label}x"
    }

    private fun loadPlaylistFromIntent(): Boolean {
        val uriList = intent.getStringArrayListExtra(EXTRA_PLAYLIST_URIS)
        val titleList = intent.getStringArrayListExtra(EXTRA_PLAYLIST_TITLES)
        if (uriList != null && titleList != null && uriList.size == titleList.size && uriList.isNotEmpty()) {
            playlistEntries = uriList.mapIndexed { index, uri ->
                PlaylistEntry(Uri.parse(uri), titleList.getOrNull(index) ?: "")
            }
            playlistIndex = intent.getIntExtra(EXTRA_PLAYLIST_INDEX, 0)
                .coerceIn(0, playlistEntries.lastIndex)
        } else {
            val uriText = intent.getStringExtra(EXTRA_URI)
            if (uriText != null) {
                val title = intent.getStringExtra(EXTRA_TITLE) ?: ""
                playlistEntries = listOf(PlaylistEntry(Uri.parse(uriText), title))
                playlistIndex = 0
            } else {
                val externalEntry = buildExternalViewEntry(intent) ?: return false
                playlistEntries = listOf(externalEntry)
                playlistIndex = 0
            }
        }
        videoUri = playlistEntries[playlistIndex].uri
        return true
    }

    private fun buildExternalViewEntry(sourceIntent: Intent): PlaylistEntry? {
        if (sourceIntent.action != Intent.ACTION_VIEW) {
            return null
        }
        val uri = sourceIntent.data ?: return null
        val scheme = uri.scheme?.lowercase(Locale.US)
        if (scheme != "content" && scheme != "file") {
            return null
        }
        val title = uri.lastPathSegment?.substringAfterLast('/') ?: ""
        return PlaylistEntry(uri, title)
    }

    private fun requestExternalVideoTitle() {
        if (intent.action != Intent.ACTION_VIEW || intent.data != videoUri) {
            return
        }
        val uri = videoUri
        val generation = subtitleResolveGeneration
        mediaInfoResolver.execute {
            if (playerActivityDestroyed || generation != subtitleResolveGeneration) {
                return@execute
            }
            val title = queryDisplayName(uri) ?: return@execute
            uiHandler.post {
                if (playerActivityDestroyed || generation != subtitleResolveGeneration ||
                    videoUri != uri
                ) {
                    return@post
                }
                playlistEntries = playlistEntries.map { entry ->
                    if (entry.uri == uri) entry.copy(title = title) else entry
                }
                titleText.text = title
            }
        }
    }

    private data class PlaylistEntry(
        val uri: Uri,
        val title: String
    )

    private data class ResumeInfo(
        val positionMs: Long,
        val durationMs: Long
    )

    private fun updateSubtitleButtonState() {
        val active = subtitleEnabled
        val color = if (active) {
            themeColor(com.google.android.material.R.attr.colorPrimary)
        } else {
            getColor(android.R.color.white)
        }
        subtitleButton.setColorFilter(color)
        subtitleButton.isSelected = active
        subtitleButton.alpha = if (active) 1f else 0.6f
    }

    private fun loadSubtitlePreferences() {
        subtitleEnabled = subtitlePreferences.getBoolean(KEY_SUBTITLE_ENABLED, false)
        preferredSubtitleLanguage = subtitlePreferences.getString(KEY_SUBTITLE_LANGUAGE, null)
        preferredSubtitleEncoding =
            subtitlePreferences.getString(KEY_SUBTITLE_ENCODING, ENCODING_UTF8) ?: ENCODING_UTF8
        val savedVideo = subtitlePreferences.getString(KEY_SUBTITLE_VIDEO_URI, null)
        if (savedVideo != null && savedVideo == videoUri.toString()) {
            val storedMode = subtitlePreferences
                .getString(KEY_SUBTITLE_SELECTION_MODE, null)
                ?.let { stored ->
                    runCatching { SubtitleSelectionMode.valueOf(stored) }.getOrNull()
                }
            if (storedMode == SubtitleSelectionMode.NONE) {
                selectedSubtitle = null
                subtitleSelectionMode = SubtitleSelectionMode.NONE
                return
            }
            val uriString = subtitlePreferences.getString(KEY_SUBTITLE_URI, null)
            val label = subtitlePreferences.getString(KEY_SUBTITLE_LABEL, null)
            val mime = subtitlePreferences.getString(KEY_SUBTITLE_MIME, null)
            val ext = subtitlePreferences.getString(KEY_SUBTITLE_EXT, "") ?: ""
            if (!uriString.isNullOrEmpty() && !label.isNullOrEmpty() && !mime.isNullOrEmpty()) {
                subtitleSelectionMode = storedMode ?: SubtitleSelectionMode.LEGACY
                selectedSubtitle = if (subtitleSelectionMode == SubtitleSelectionMode.AUTO) {
                    null
                } else {
                    SubtitleSource(Uri.parse(uriString), label, mime, ext)
                }
                return
            }
        }
        selectedSubtitle = null
        subtitleSelectionMode = SubtitleSelectionMode.AUTO
    }

    private fun autoSelectSubtitleIfAvailable() {
        reconcileSavedSubtitleSelection(subtitleCandidates)
        if (subtitleSelectionMode != SubtitleSelectionMode.AUTO || subtitleCandidates.isEmpty()) {
            return
        }
        selectedSubtitle = preferredSubtitleCandidate(subtitleCandidates) ?: return
        subtitleSelectionMode = SubtitleSelectionMode.AUTO
        if (!subtitlePreferences.contains(KEY_SUBTITLE_ENABLED)) {
            subtitleEnabled = true
        }
        persistSubtitleSelection(selectedSubtitle)
        persistSubtitleEnabled()
    }

    private fun reconcileSavedSubtitleSelection(candidates: List<SubtitleSource>) {
        val selected = selectedSubtitle
        when (subtitleSelectionMode) {
            SubtitleSelectionMode.AUTO -> selectedSubtitle = null
            SubtitleSelectionMode.MANUAL -> {
                val discoveredSource = selected?.let { saved ->
                    candidates.firstOrNull { candidate -> candidate.uri == saved.uri }
                }
                if (discoveredSource != null) {
                    selectedSubtitle = discoveredSource
                }
            }
            SubtitleSelectionMode.NONE -> Unit
            SubtitleSelectionMode.LEGACY -> {
                if (candidates.isEmpty()) {
                    return
                }
                val referenceMatch = candidates.firstOrNull()?.nameMatch
                val wasAutomaticallyDiscoverable = selected?.let { saved ->
                    candidates.any { candidate -> candidate.uri == saved.uri } ||
                        referenceMatch?.let { match ->
                            SubtitleCandidatePolicy.matchesSameVideo(match, saved.label)
                        } == true
                } == true
                subtitleSelectionMode = if (wasAutomaticallyDiscoverable) {
                    selectedSubtitle = null
                    SubtitleSelectionMode.AUTO
                } else {
                    SubtitleSelectionMode.MANUAL
                }
                persistSubtitleSelection(selectedSubtitle)
            }
        }
    }

    private fun preferredSubtitleCandidate(candidates: List<SubtitleSource>): SubtitleSource? {
        return SubtitleCandidatePolicy.preferredCandidate(
            candidates = candidates,
            preferredLanguages = resolvePreferredSubtitleLanguages(),
            matchOf = { source -> requireNotNull(source.nameMatch) }
        )
    }

    private fun orderedSubtitleCandidates(candidates: List<SubtitleSource>): List<SubtitleSource> {
        return SubtitleCandidatePolicy.orderedCandidates(
            candidates = candidates,
            preferredLanguages = resolvePreferredSubtitleLanguages(),
            matchOf = { source -> requireNotNull(source.nameMatch) }
        )
    }

    private fun resolvePreferredSubtitleLanguages(): List<String> {
        val localeList = resources.configuration.locales
        val effectiveLanguageTags = mutableListOf<String>()
        for (index in 0 until localeList.size()) {
            effectiveLanguageTags.add(localeList[index].toLanguageTag())
        }
        if (effectiveLanguageTags.isEmpty()) {
            effectiveLanguageTags.add(Locale.getDefault().toLanguageTag())
        }
        return SubtitleCandidatePolicy.resolvePreferredLanguages(
            selectedLanguage = preferredSubtitleLanguage,
            effectiveLanguageTags = effectiveLanguageTags
        )
    }

    private fun requestNearbySubtitleCandidates() {
        val generation = subtitleResolveGeneration
        // Discover on demand instead of scanning every video's folder at startup.
        val indices = linkedSetOf(playlistIndex)
        val activePlayer = player
        if (activePlayer != null && activePlayer.mediaItemCount > 0) {
            indices.add(activePlayer.nextMediaItemIndex)
            indices.add(activePlayer.previousMediaItemIndex)
        } else {
            indices.add(playlistIndex + 1)
            indices.add(playlistIndex - 1)
        }
        indices.forEach { index ->
            val uri = playlistEntries.getOrNull(index)?.uri ?: return@forEach
            requestSubtitleCandidates(uri, index, generation)
        }
    }

    private fun requestSubtitleCandidates(uri: Uri, index: Int, generation: Long) {
        val requestKey = generation to uri
        if (subtitleCandidatesByUri.containsKey(uri) ||
            pendingSubtitleRequests.contains(requestKey) ||
            subtitleResolver.isShutdown
        ) {
            return
        }
        pendingSubtitleRequests.add(requestKey)
        subtitleResolver.execute {
            if (generation != subtitleResolveGeneration || playerActivityDestroyed) {
                return@execute
            }
            val candidates = try {
                loadSubtitleCandidates(uri)
            } catch (_: RuntimeException) {
                // External providers may not expose MediaStore columns or may revoke access.
                emptyList()
            }
            uiHandler.post {
                pendingSubtitleRequests.remove(requestKey)
                if (generation != subtitleResolveGeneration || isDestroyed) {
                    return@post
                }
                val entry = playlistEntries.getOrNull(index)
                if (entry?.uri != uri) {
                    return@post
                }
                subtitleCandidatesByUri[uri] = candidates
                if (uri == videoUri && index == playlistIndex) {
                    subtitleCandidates = candidates
                    loadSubtitlePreferences()
                    autoSelectSubtitleIfAvailable()
                    subtitleDialogSelectValue?.text =
                        selectedSubtitle?.label ?: getString(R.string.subtitle_none)
                    updateSubtitleDialogEnabled(subtitleEnabled)
                    updateSubtitleButtonState()
                    attachResolvedSubtitleToCurrentItem()
                } else {
                    attachResolvedSubtitleToQueuedItem(
                        index,
                        entry,
                        preferredSubtitleCandidate(candidates)
                    )
                }
            }
        }
    }

    private fun attachResolvedSubtitleToCurrentItem() {
        val source = selectedSubtitle ?: return
        applySubtitleSelection(source)
    }

    private fun attachResolvedSubtitleToQueuedItem(
        index: Int,
        entry: PlaylistEntry,
        source: SubtitleSource?
    ) {
        if (!subtitleEnabled || source == null ||
            !preferredSubtitleEncoding.equals(ENCODING_UTF8, true)
        ) {
            return
        }
        val activePlayer = player ?: return
        if (index == activePlayer.currentMediaItemIndex || index >= activePlayer.mediaItemCount) {
            return
        }
        activePlayer.replaceMediaItem(index, buildMediaItem(entry, source))
    }

    private fun persistSubtitleEnabled() {
        subtitlePreferences.edit()
            .putBoolean(KEY_SUBTITLE_ENABLED, subtitleEnabled)
            .apply()
    }

    private fun persistSubtitleSelection(source: SubtitleSource?) {
        val editor = subtitlePreferences.edit()
        when (subtitleSelectionMode) {
            SubtitleSelectionMode.AUTO -> {
                val savedVideo = subtitlePreferences.getString(KEY_SUBTITLE_VIDEO_URI, null)
                if (savedVideo == videoUri.toString()) {
                    clearPersistedSubtitleSelection(editor)
                }
            }
            SubtitleSelectionMode.MANUAL -> {
                if (source == null) {
                    clearPersistedSubtitleSelection(editor)
                } else {
                    editor.putString(KEY_SUBTITLE_SELECTION_MODE, SubtitleSelectionMode.MANUAL.name)
                    editor.putString(KEY_SUBTITLE_VIDEO_URI, videoUri.toString())
                    editor.putString(KEY_SUBTITLE_URI, source.uri.toString())
                    editor.putString(KEY_SUBTITLE_LABEL, source.label)
                    editor.putString(KEY_SUBTITLE_MIME, source.mimeType)
                    editor.putString(KEY_SUBTITLE_EXT, source.extension)
                }
            }
            SubtitleSelectionMode.NONE -> {
                editor.putString(KEY_SUBTITLE_SELECTION_MODE, SubtitleSelectionMode.NONE.name)
                editor.putString(KEY_SUBTITLE_VIDEO_URI, videoUri.toString())
                editor.remove(KEY_SUBTITLE_URI)
                editor.remove(KEY_SUBTITLE_LABEL)
                editor.remove(KEY_SUBTITLE_MIME)
                editor.remove(KEY_SUBTITLE_EXT)
            }
            SubtitleSelectionMode.LEGACY -> return
        }
        editor.apply()
    }

    private fun clearPersistedSubtitleSelection(editor: SharedPreferences.Editor) {
        editor.remove(KEY_SUBTITLE_SELECTION_MODE)
        editor.remove(KEY_SUBTITLE_VIDEO_URI)
        editor.remove(KEY_SUBTITLE_URI)
        editor.remove(KEY_SUBTITLE_LABEL)
        editor.remove(KEY_SUBTITLE_MIME)
        editor.remove(KEY_SUBTITLE_EXT)
    }

    private fun loadSubtitleCandidates(uri: Uri): List<SubtitleSource> {
        val meta = queryVideoInfo(uri) ?: return emptyList()
        val relativePath = meta.relativePath
        val displayName = meta.displayName
        if (relativePath.isNullOrEmpty() || displayName.isNullOrEmpty()) {
            return emptyList()
        }
        val volume = meta.volumeName ?: MediaStore.VOLUME_EXTERNAL_PRIMARY
        val filesUri = MediaStore.Files.getContentUri(volume)
        val projection = arrayOf(
            MediaStore.Files.FileColumns._ID,
            MediaStore.Files.FileColumns.DISPLAY_NAME
        )
        val candidates = mutableListOf<SubtitleSource>()
        contentResolver.query(
            filesUri,
            projection,
            "${MediaStore.Files.FileColumns.RELATIVE_PATH}=?",
            arrayOf(relativePath),
            null
        )?.use { cursor ->
            val idCol = cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns._ID)
            val nameCol = cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns.DISPLAY_NAME)
            while (cursor.moveToNext()) {
                val name = cursor.getString(nameCol) ?: continue
                val nameMatch = SubtitleCandidatePolicy.match(displayName, name) ?: continue
                val id = cursor.getLong(idCol)
                val fileUri = ContentUris.withAppendedId(filesUri, id)
                val mime = guessSubtitleMimeType(name)
                val extension = name.substringAfterLast('.', "")
                candidates.add(SubtitleSource(fileUri, name, mime, extension, nameMatch))
            }
        }
        return candidates
    }

    private fun queryVideoInfo(uri: Uri): VideoInfo? {
        val projection = arrayOf(
            MediaStore.Video.Media.DISPLAY_NAME,
            MediaStore.Video.Media.RELATIVE_PATH,
            MediaStore.Video.Media.VOLUME_NAME
        )
        contentResolver.query(uri, projection, null, null, null)?.use { cursor ->
            if (!cursor.moveToFirst()) {
                return null
            }
            val nameCol = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.DISPLAY_NAME)
            val pathCol = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.RELATIVE_PATH)
            val volumeCol = cursor.getColumnIndex(MediaStore.Video.Media.VOLUME_NAME)
            val name = cursor.getString(nameCol) ?: ""
            val relativePath = cursor.getString(pathCol) ?: ""
            val volume = if (volumeCol >= 0) cursor.getString(volumeCol) else null
            return VideoInfo(name, relativePath, volume)
        }
        return null
    }

    private fun queryDisplayName(uri: Uri): String? {
        val projection = arrayOf(OpenableColumns.DISPLAY_NAME)
        return try {
            contentResolver.query(uri, projection, null, null, null)?.use { cursor ->
                val nameCol = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (nameCol >= 0 && cursor.moveToFirst()) cursor.getString(nameCol) else null
            }
        } catch (_: RuntimeException) {
            null
        }
    }

    private fun guessSubtitleMimeType(name: String): String {
        val ext = name.substringAfterLast('.', "").lowercase(Locale.US)
        return when (ext) {
            "vtt" -> MimeTypes.TEXT_VTT
            "ass", "ssa" -> MimeTypes.TEXT_SSA
            "srt", "sub" -> MimeTypes.APPLICATION_SUBRIP
            else -> MimeTypes.APPLICATION_SUBRIP
        }
    }

    private fun prepareSubtitleUri(source: SubtitleSource): Uri? {
        val encoding = preferredSubtitleEncoding
        if (encoding.equals(ENCODING_UTF8, true) || !isTextSubtitleMime(source.mimeType)) {
            return source.uri
        }
        val key = SubtitleCacheKey(source.uri, encoding)
        preparedSubtitleUris[key]?.let { return it }
        val generation = subtitlePreparationGeneration
        val requestKey = generation to key
        if (!mediaInfoResolver.isShutdown && pendingSubtitleConversions.add(requestKey)) {
            mediaInfoResolver.execute {
                if (playerActivityDestroyed || generation != subtitlePreparationGeneration) {
                    return@execute
                }
                val output = convertSubtitleToUtf8(source.uri, encoding, source.extension, generation)
                if (playerActivityDestroyed || generation != subtitlePreparationGeneration) {
                    output?.delete()
                    return@execute
                }
                uiHandler.post {
                    pendingSubtitleConversions.remove(requestKey)
                    if (playerActivityDestroyed || generation != subtitlePreparationGeneration) {
                        output?.delete()
                        return@post
                    }
                    if (output != null) {
                        subtitleCacheFiles.add(output)
                    }
                    preparedSubtitleUris[key] = output?.let(Uri::fromFile) ?: source.uri
                    if (selectedSubtitle?.uri == source.uri && preferredSubtitleEncoding == encoding) {
                        attachResolvedSubtitleToCurrentItem()
                    }
                }
            }
        }
        // Video preparation continues while the optional text conversion runs off the UI thread.
        return null
    }

    private fun isTextSubtitleMime(mimeType: String): Boolean {
        return mimeType.startsWith("text/") ||
            mimeType == MimeTypes.APPLICATION_SUBRIP ||
            mimeType == MimeTypes.TEXT_SSA
    }

    private fun convertSubtitleToUtf8(
        uri: Uri,
        encoding: String,
        extension: String,
        generation: Long
    ): File? {
        val charset = runCatching { Charset.forName(encoding) }.getOrNull() ?: return null
        var output: File? = null
        return try {
            val safeExt = extension.takeIf { it.matches(Regex("[A-Za-z0-9]{1,8}")) } ?: "srt"
            contentResolver.openInputStream(uri)?.bufferedReader(charset)?.use { reader ->
                val target = File.createTempFile("subtitle_", ".$safeExt", cacheDir)
                output = target
                target.bufferedWriter(Charsets.UTF_8).use { writer ->
                    val buffer = CharArray(DEFAULT_BUFFER_SIZE)
                    while (true) {
                        if (Thread.currentThread().isInterrupted ||
                            generation != subtitlePreparationGeneration
                        ) {
                            throw InterruptedIOException("Subtitle conversion cancelled")
                        }
                        val count = reader.read(buffer)
                        if (count < 0) break
                        writer.write(buffer, 0, count)
                    }
                }
                target
            }
        } catch (_: Exception) {
            output?.delete()
            null
        }
    }

    private fun clearSubtitleCache() {
        subtitlePreparationGeneration++
        subtitleCacheFiles.forEach { it.delete() }
        subtitleCacheFiles.clear()
        preparedSubtitleUris.clear()
        pendingSubtitleConversions.clear()
    }

    private fun getSubtitleLanguageLabel(language: String?): String {
        return when (language) {
            "ko" -> getString(R.string.language_korean)
            "en" -> getString(R.string.language_english)
            else -> getString(R.string.language_system)
        }
    }

    private fun getSubtitleEncodingLabel(encoding: String): String {
        return when (encoding) {
            ENCODING_EUC_KR -> getString(R.string.subtitle_encoding_euc_kr)
            ENCODING_CP949 -> getString(R.string.subtitle_encoding_cp949)
            else -> getString(R.string.subtitle_encoding_utf8)
        }
    }

    private data class SubtitleSource(
        val uri: Uri,
        val label: String,
        val mimeType: String,
        val extension: String,
        val nameMatch: SubtitleNameMatch? = null
    )

    private data class SubtitleCacheKey(val uri: Uri, val encoding: String)

    private data class VideoInfo(
        val displayName: String,
        val relativePath: String,
        val volumeName: String?
    )

    private sealed class SubtitleChoice {
        object None : SubtitleChoice()
        object Pick : SubtitleChoice()
        data class Source(val source: SubtitleSource) : SubtitleChoice()
    }

    private data class SubtitleLanguage(val code: String?, val label: String)

    private data class SubtitleEncoding(val code: String, val label: String)

    private enum class SubtitleSelectionMode {
        AUTO,
        MANUAL,
        NONE,
        LEGACY
    }

    private enum class SwipeMode {
        NONE,
        VERTICAL,
        HORIZONTAL
    }

    private inner class GestureListener : GestureDetector.SimpleOnGestureListener() {
        override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
            showOverlay()
            return true
        }

        override fun onDoubleTap(e: MotionEvent): Boolean {
            val activePlayer = player ?: return false
            val x = e.x
            val width = playerView.width
            if (width <= 0) {
                return false
            }
            val third = width / 3f
            if (x < third) {
                seekBy(-SEEK_JUMP_MS)
            } else if (x < third * 2f) {
                togglePlayback()
                showGestureText(if (activePlayer.isPlaying) "Play" else "Pause")
            } else {
                seekBy(SEEK_JUMP_MS)
            }
            showOverlay()
            return true
        }
    }

    companion object {
        const val EXTRA_URI = "extra_uri"
        const val EXTRA_TITLE = "extra_title"
        const val EXTRA_PLAYLIST_URIS = "extra_playlist_uris"
        const val EXTRA_PLAYLIST_TITLES = "extra_playlist_titles"
        const val EXTRA_PLAYLIST_INDEX = "extra_playlist_index"

        fun createLaunchIntent(context: Context): Intent {
            return Intent(context, PlayerActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            }
        }

        private const val STATE_USER_ORIENTATION = "state_user_orientation"
        private const val SEEK_JUMP_MS = 10_000L
        private const val SEEK_BACK_BUFFER_MS = 15_000
        private const val BUFFER_FOR_PLAYBACK_AFTER_SEEK_MS = 350
        private const val UI_UPDATE_INTERVAL_MS = 250L
        private const val OVERLAY_AUTO_HIDE_MS = 2500L
        private const val GESTURE_TEXT_HIDE_MS = 1000L
        private const val SWIPE_SEEK_MAX_RANGE_MS = 10 * 60 * 1000L
        private const val LOADING_SPINNER_DELAY_MS = 150L
        private const val PREFS = "nsplayer_prefs"
        private const val KEY_SUBTITLE_ENABLED = "subtitle_enabled"
        private const val KEY_SUBTITLE_LANGUAGE = "subtitle_language"
        private const val KEY_SUBTITLE_ENCODING = "subtitle_encoding"
        private const val KEY_SUBTITLE_VIDEO_URI = "subtitle_video_uri"
        private const val KEY_SUBTITLE_URI = "subtitle_uri"
        private const val KEY_SUBTITLE_LABEL = "subtitle_label"
        private const val KEY_SUBTITLE_MIME = "subtitle_mime"
        private const val KEY_SUBTITLE_EXT = "subtitle_ext"
        private const val KEY_SUBTITLE_SELECTION_MODE = "subtitle_selection_mode"
        private const val KEY_PLAYBACK_SPEED = "playback_speed"
        private const val KEY_REPEAT_MODE = "playback_repeat_mode"
        private const val KEY_SHUFFLE_ENABLED = "playback_shuffle_enabled"
        private const val KEY_RESUME_PREFIX = "resume_pos_"
        private const val KEY_RESUME_DURATION_PREFIX = "resume_dur_"
        private const val ENCODING_UTF8 = "UTF-8"
        private const val ENCODING_EUC_KR = "EUC-KR"
        private const val ENCODING_CP949 = "CP949"
        private const val RESUME_MIN_SAVE_MS = 2_000L
        private const val RESUME_END_THRESHOLD_MS = 2_000L
        private const val RESUME_BACK_MS = 1_500L
        private const val RESUME_PROMPT_MS = 2_500L
        private val SUBTITLE_MIME_TYPES = arrayOf(
            "text/*",
            "application/x-subrip",
            "application/octet-stream",
            "text/vtt",
            "text/x-ssa",
            "application/x-ass",
            "application/x-ssa"
        )
    }
}
