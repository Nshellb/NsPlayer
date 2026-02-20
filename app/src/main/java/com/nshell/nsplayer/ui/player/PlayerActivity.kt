package com.nshell.nsplayer.ui.player

import android.content.ContentUris
import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.media.AudioManager
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.provider.MediaStore
import android.provider.OpenableColumns
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.ImageButton
import android.widget.SeekBar
import android.widget.TextView
import com.nshell.nsplayer.ui.base.BaseActivity
import com.nshell.nsplayer.R
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.widget.SwitchCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import java.io.IOException
import java.io.File
import java.nio.charset.Charset
import java.util.Locale

class PlayerActivity : BaseActivity() {
    private lateinit var playerView: PlayerView
    private var player: ExoPlayer? = null
    private lateinit var overlayContainer: View
    private lateinit var playPauseButton: ImageButton
    private lateinit var rotateButton: ImageButton
    private lateinit var seekBar: SeekBar
    private lateinit var positionText: TextView
    private lateinit var durationText: TextView
    private lateinit var titleText: TextView
    private lateinit var gestureText: TextView
    private lateinit var subtitleButton: ImageButton
    private lateinit var speedButton: TextView

    private val uiHandler = Handler(Looper.getMainLooper())
    private val progressUpdater = object : Runnable {
        override fun run() {
            if (player != null && !isScrubbing) {
                updateProgress(player?.currentPosition ?: 0, player?.duration ?: C.TIME_UNSET)
            }
            uiHandler.postDelayed(this, UI_UPDATE_INTERVAL_MS)
        }
    }
    private val hideOverlayRunnable = Runnable { overlayContainer.visibility = View.GONE }
    private val hideGestureTextRunnable = Runnable { gestureText.visibility = View.GONE }

    private var isScrubbing = false
    private lateinit var gestureDetector: GestureDetector
    private var swipeThresholdPx = 0f
    private var isAdjusting = false
    private var startX = 0f
    private var startY = 0f
    private var adjustLeftSide = false
    private var startBrightness = -1f
    private var startVolume = 0
    private var maxVolume = 0
    private var audioManager: AudioManager? = null
    private lateinit var videoUri: Uri
    private var userOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED

    private val subtitlePreferences by lazy { getSharedPreferences(PREFS, MODE_PRIVATE) }
    private var subtitleEnabled = false
    private var preferredSubtitleLanguage: String? = null
    private var preferredSubtitleEncoding = ENCODING_UTF8
    private var selectedSubtitle: SubtitleSource? = null
    private var subtitleCandidates: List<SubtitleSource> = emptyList()
    private var subtitleCacheFile: File? = null
    private var subtitleDialogSelectValue: TextView? = null
    private var subtitleDialogEnableSwitch: SwitchCompat? = null
    private var playbackSpeed = 1.0f

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
            val name = queryDisplayName(uri) ?: uri.lastPathSegment ?: getString(R.string.subtitle_settings)
            val mimeType = guessSubtitleMimeType(name)
            val extension = name.substringAfterLast('.', "")
            selectedSubtitle = SubtitleSource(uri, name, mimeType, extension)
            subtitleEnabled = true
            persistSubtitleEnabled()
            subtitleDialogSelectValue?.text = selectedSubtitle?.label ?: getString(R.string.subtitle_none)
            subtitleDialogEnableSwitch?.isChecked = true
            applySubtitleSelection(selectedSubtitle)
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
        playPauseButton = findViewById(R.id.playPauseButton)
        rotateButton = findViewById(R.id.rotateButton)
        seekBar = findViewById(R.id.seekBar)
        positionText = findViewById(R.id.positionText)
        durationText = findViewById(R.id.durationText)
        titleText = findViewById(R.id.titleText)
        gestureText = findViewById(R.id.gestureText)
        subtitleButton = findViewById(R.id.subtitleButton)
        speedButton = findViewById(R.id.speedButton)

        audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager?
        if (audioManager != null) {
            maxVolume = audioManager?.getStreamMaxVolume(AudioManager.STREAM_MUSIC) ?: 0
        }

        swipeThresholdPx = resources.displayMetrics.density * 8f
        gestureDetector = GestureDetector(this, GestureListener())

        playPauseButton.setOnClickListener { togglePlayback() }
        rotateButton.setOnClickListener { toggleOrientation() }
        subtitleButton.setOnClickListener { showSubtitleSettingsDialog() }
        speedButton.setOnClickListener { showSpeedDialog() }

        seekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(bar: SeekBar, progress: Int, fromUser: Boolean) {
                if (fromUser) {
                    positionText.text = formatTime(progress.toLong())
                }
            }

            override fun onStartTrackingTouch(bar: SeekBar) {
                isScrubbing = true
                uiHandler.removeCallbacks(progressUpdater)
            }

            override fun onStopTrackingTouch(bar: SeekBar) {
                isScrubbing = false
                player?.seekTo(bar.progress.toLong())
                uiHandler.post(progressUpdater)
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
            handleVerticalSwipe(event, v.width, v.height)
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

        val uriText = intent.getStringExtra(EXTRA_URI)
        if (uriText == null) {
            finish()
            return
        }
        videoUri = Uri.parse(uriText)
        val title = intent.getStringExtra(EXTRA_TITLE)
        if (title != null) {
            titleText.text = title
        }
        loadSubtitlePreferences()
        loadPlaybackSpeed()
        subtitleCandidates = loadSubtitleCandidates(videoUri)
        updateSubtitleButtonState()
        updateSpeedButtonLabel()
        if (userOrientation != ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED) {
            requestedOrientation = userOrientation
        } else {
            applyAutoOrientation(videoUri)
        }
    }

    override fun onStart() {
        super.onStart()
        initializePlayer()
        uiHandler.post(progressUpdater)
        scheduleOverlayHide()
    }

    override fun onStop() {
        super.onStop()
        uiHandler.removeCallbacks(progressUpdater)
        uiHandler.removeCallbacks(hideOverlayRunnable)
        uiHandler.removeCallbacks(hideGestureTextRunnable)
        releasePlayer()
        clearSubtitleCache()
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

    private fun initializePlayer() {
        if (player != null) {
            return
        }
        player = ExoPlayer.Builder(this).build()
        playerView.player = player
        player?.addListener(object : Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                updatePlayPauseIcon(isPlaying)
            }

            override fun onPlaybackStateChanged(state: Int) {
                if (state == Player.STATE_READY) {
                    updateProgress(player?.currentPosition ?: 0, player?.duration ?: C.TIME_UNSET)
                }
            }
        })

        player?.setMediaItem(buildMediaItem())
        player?.prepare()
        applySubtitleEnabled(subtitleEnabled)
        applyPlaybackSpeed()
        player?.play()
        updatePlayPauseIcon(true)
    }

    private fun releasePlayer() {
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

    private fun updatePlayPauseIcon(isPlaying: Boolean) {
        val icon = if (isPlaying) {
            R.drawable.ic_pause
        } else {
            R.drawable.ic_play
        }
        playPauseButton.setImageResource(icon)
    }

    private fun updateProgress(positionMs: Long, durationMs: Long) {
        if (durationMs == C.TIME_UNSET || durationMs <= 0) {
            seekBar.isEnabled = false
            durationText.text = "--:--"
            positionText.text = formatTime(positionMs)
            return
        }
        seekBar.isEnabled = true
        val safeDuration = durationMs.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
        seekBar.max = safeDuration
        seekBar.progress = positionMs.coerceAtMost(safeDuration.toLong()).toInt()
        positionText.text = formatTime(positionMs)
        durationText.text = formatTime(durationMs)
    }

    private fun seekBy(deltaMs: Long) {
        val activePlayer = player ?: return
        val duration = activePlayer.duration
        var target = activePlayer.currentPosition + deltaMs
        target = if (duration != C.TIME_UNSET && duration > 0) {
            target.coerceIn(0, duration)
        } else {
            target.coerceAtLeast(0)
        }
        activePlayer.seekTo(target)
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
            isPointInsideView(event, rotateButton) ||
            isPointInsideView(event, seekBar) ||
            isPointInsideView(event, overlayContainer.findViewById(R.id.bottomBar))
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

    private fun handleVerticalSwipe(event: MotionEvent, width: Int, height: Int) {
        if (height <= 0) {
            return
        }
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                startX = event.x
                startY = event.y
                adjustLeftSide = startX < width / 2f
                isAdjusting = false
                startBrightness = getCurrentBrightness()
                startVolume = audioManager?.getStreamVolume(AudioManager.STREAM_MUSIC) ?: 0
            }
            MotionEvent.ACTION_MOVE -> {
                val dy = event.y - startY
                val dx = event.x - startX
                if (!isAdjusting) {
                    if (kotlin.math.abs(dy) > swipeThresholdPx && kotlin.math.abs(dy) > kotlin.math.abs(dx)) {
                        isAdjusting = true
                    } else {
                        return
                    }
                }
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
            MotionEvent.ACTION_UP,
            MotionEvent.ACTION_CANCEL -> {
                isAdjusting = false
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

    private fun applyAutoOrientation(uri: Uri) {
        if (userOrientation != ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED) {
            return
        }
        val retriever = MediaMetadataRetriever()
        try {
            retriever.setDataSource(this, uri)
            val widthValue = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)
            val heightValue = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)
            if (widthValue == null || heightValue == null) {
                return
            }
            val width = widthValue.toInt()
            val height = heightValue.toInt()
            if (width > height) {
                requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
            } else {
                requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
            }
        } catch (_: RuntimeException) {
            // Ignore invalid metadata.
        } finally {
            try {
                retriever.release()
            } catch (_: IOException) {
                // Ignore release failures.
            } catch (_: RuntimeException) {
                // Ignore release failures.
            }
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
        subtitleCandidates = loadSubtitleCandidates(videoUri)
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
            subtitleEnabled = isChecked
            persistSubtitleEnabled()
            if (isChecked && selectedSubtitle == null && subtitleCandidates.isNotEmpty()) {
                selectedSubtitle = subtitleCandidates.first()
                persistSubtitleSelection(selectedSubtitle)
                selectValue.text = selectedSubtitle?.label ?: getString(R.string.subtitle_none)
                applySubtitleSelection(selectedSubtitle)
                return@setOnCheckedChangeListener
            }
            applySubtitleEnabled(subtitleEnabled)
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

        val dialog = AlertDialog.Builder(this)
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
        subtitleCandidates.forEach { options.add(SubtitleChoice.Source(it)) }
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

        AlertDialog.Builder(this)
            .setTitle(R.string.subtitle_select)
            .setItems(labels) { _, which ->
                when (val choice = options[which]) {
                    SubtitleChoice.None -> {
                        selectedSubtitle = null
                        subtitleEnabled = false
                        persistSubtitleEnabled()
                        persistSubtitleSelection(null)
                        enableSwitch.isChecked = false
                        selectValue.text = getString(R.string.subtitle_none)
                        applySubtitleEnabled(false)
                    }
                    SubtitleChoice.Pick -> {
                        subtitlePickerLauncher.launch(SUBTITLE_MIME_TYPES)
                    }
                    is SubtitleChoice.Source -> {
                        selectedSubtitle = choice.source
                        subtitleEnabled = true
                        persistSubtitleEnabled()
                        persistSubtitleSelection(selectedSubtitle)
                        enableSwitch.isChecked = true
                        selectValue.text = choice.source.label
                        applySubtitleSelection(choice.source)
                    }
                }
            }
            .show()
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
        AlertDialog.Builder(this)
            .setTitle(R.string.subtitle_language)
            .setSingleChoiceItems(labels, currentIndex) { dialog, which ->
                preferredSubtitleLanguage = options[which].code
                subtitlePreferences.edit()
                    .putString(KEY_SUBTITLE_LANGUAGE, preferredSubtitleLanguage)
                    .apply()
                valueView.text = options[which].label
                if (selectedSubtitle != null) {
                    applySubtitleSelection(selectedSubtitle)
                }
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
        AlertDialog.Builder(this)
            .setTitle(R.string.subtitle_encoding)
            .setSingleChoiceItems(labels, currentIndex) { dialog, which ->
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
        if (source == null) {
            selectedSubtitle = null
            persistSubtitleSelection(null)
            applySubtitleEnabled(subtitleEnabled)
            updateSubtitleButtonState()
            return
        }
        selectedSubtitle = source
        persistSubtitleSelection(source)
        val activePlayer = player ?: return
        val wasPlaying = activePlayer.isPlaying
        val position = activePlayer.currentPosition
        activePlayer.setMediaItem(buildMediaItem(), position)
        activePlayer.prepare()
        applySubtitleEnabled(subtitleEnabled)
        if (wasPlaying) {
            activePlayer.play()
        }
        updateSubtitleButtonState()
    }

    private fun buildMediaItem(): MediaItem {
        val builder = MediaItem.Builder().setUri(videoUri)
        val subtitle = selectedSubtitle
        if (subtitle != null) {
            builder.setSubtitleConfigurations(listOf(buildSubtitleConfiguration(subtitle)))
        }
        return builder.build()
    }

    private fun buildSubtitleConfiguration(source: SubtitleSource): MediaItem.SubtitleConfiguration {
        val uri = prepareSubtitleUri(source)
        val builder = MediaItem.SubtitleConfiguration.Builder(uri)
            .setMimeType(source.mimeType)
            .setLabel(source.label)
            .setSelectionFlags(C.SELECTION_FLAG_DEFAULT)
        if (!preferredSubtitleLanguage.isNullOrEmpty()) {
            builder.setLanguage(preferredSubtitleLanguage)
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

    private fun showSpeedDialog() {
        val speeds = listOf(0.5f, 0.75f, 1.0f, 1.25f, 1.5f, 1.75f, 2.0f, 3.0f, 4.0f)
        val labels = speeds.map { formatSpeedLabel(it) }.toTypedArray()
        val checked = speeds.indexOfFirst { kotlin.math.abs(it - playbackSpeed) < 0.01f }
            .takeIf { it >= 0 } ?: 2
        AlertDialog.Builder(this, R.style.ThemeOverlay_NsPlayer_Dialog)
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

    private fun updateSubtitleButtonState() {
        val active = subtitleEnabled
        val color = if (active) {
            getColor(R.color.brand_green)
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
            val uriString = subtitlePreferences.getString(KEY_SUBTITLE_URI, null)
            val label = subtitlePreferences.getString(KEY_SUBTITLE_LABEL, null)
            val mime = subtitlePreferences.getString(KEY_SUBTITLE_MIME, null)
            val ext = subtitlePreferences.getString(KEY_SUBTITLE_EXT, "") ?: ""
            if (!uriString.isNullOrEmpty() && !label.isNullOrEmpty() && !mime.isNullOrEmpty()) {
                selectedSubtitle = SubtitleSource(Uri.parse(uriString), label, mime, ext)
            }
        }
    }

    private fun persistSubtitleEnabled() {
        subtitlePreferences.edit()
            .putBoolean(KEY_SUBTITLE_ENABLED, subtitleEnabled)
            .apply()
    }

    private fun persistSubtitleSelection(source: SubtitleSource?) {
        val editor = subtitlePreferences.edit()
        if (source == null) {
            editor.remove(KEY_SUBTITLE_VIDEO_URI)
            editor.remove(KEY_SUBTITLE_URI)
            editor.remove(KEY_SUBTITLE_LABEL)
            editor.remove(KEY_SUBTITLE_MIME)
            editor.remove(KEY_SUBTITLE_EXT)
        } else {
            editor.putString(KEY_SUBTITLE_VIDEO_URI, videoUri.toString())
            editor.putString(KEY_SUBTITLE_URI, source.uri.toString())
            editor.putString(KEY_SUBTITLE_LABEL, source.label)
            editor.putString(KEY_SUBTITLE_MIME, source.mimeType)
            editor.putString(KEY_SUBTITLE_EXT, source.extension)
        }
        editor.apply()
    }

    private fun loadSubtitleCandidates(uri: Uri): List<SubtitleSource> {
        val meta = queryVideoInfo(uri) ?: return emptyList()
        val relativePath = meta.relativePath
        val displayName = meta.displayName
        if (relativePath.isNullOrEmpty() || displayName.isNullOrEmpty()) {
            return emptyList()
        }
        val videoBase = displayName.substringBeforeLast('.', displayName).lowercase(Locale.US)
        if (videoBase.isEmpty()) {
            return emptyList()
        }
        val extensions = setOf("srt", "vtt", "ass", "ssa", "sub")
        val volume = meta.volumeName ?: MediaStore.VOLUME_EXTERNAL_PRIMARY
        val filesUri = MediaStore.Files.getContentUri(volume)
        val projection = arrayOf(
            MediaStore.Files.FileColumns._ID,
            MediaStore.Files.FileColumns.DISPLAY_NAME
        )
        val exactMatches = mutableListOf<SubtitleSource>()
        val taggedMatches = mutableListOf<SubtitleSource>()
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
                val lowerName = name.lowercase(Locale.US)
                val dot = lowerName.lastIndexOf('.')
                if (dot <= 0 || dot == lowerName.length - 1) {
                    continue
                }
                val ext = lowerName.substring(dot + 1)
                if (!extensions.contains(ext)) {
                    continue
                }
                val subtitleBase = lowerName.substring(0, dot)
                if (subtitleBase != videoBase && !subtitleBase.startsWith("$videoBase.")) {
                    continue
                }
                val id = cursor.getLong(idCol)
                val fileUri = ContentUris.withAppendedId(filesUri, id)
                val mime = guessSubtitleMimeType(name)
                val extension = name.substringAfterLast('.', "")
                val source = SubtitleSource(fileUri, name, mime, extension)
                if (subtitleBase == videoBase) {
                    exactMatches.add(source)
                } else {
                    taggedMatches.add(source)
                }
            }
        }
        return exactMatches + taggedMatches
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
        contentResolver.query(uri, projection, null, null, null)?.use { cursor ->
            if (!cursor.moveToFirst()) {
                return null
            }
            val nameCol = cursor.getColumnIndexOrThrow(OpenableColumns.DISPLAY_NAME)
            return cursor.getString(nameCol)
        }
        return null
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

    private fun prepareSubtitleUri(source: SubtitleSource): Uri {
        val encoding = preferredSubtitleEncoding
        if (encoding.equals(ENCODING_UTF8, true)) {
            return source.uri
        }
        if (!isTextSubtitleMime(source.mimeType)) {
            return source.uri
        }
        val converted = convertSubtitleToUtf8(source.uri, encoding, source.extension)
        return converted ?: source.uri
    }

    private fun isTextSubtitleMime(mimeType: String): Boolean {
        return mimeType.startsWith("text/") ||
            mimeType == MimeTypes.APPLICATION_SUBRIP ||
            mimeType == MimeTypes.TEXT_SSA
    }

    private fun convertSubtitleToUtf8(uri: Uri, encoding: String, extension: String): Uri? {
        val charset = runCatching { Charset.forName(encoding) }.getOrNull() ?: return null
        return try {
            val text = contentResolver.openInputStream(uri)?.use { input ->
                String(input.readBytes(), charset)
            } ?: return null
            val safeExt = if (extension.isNotEmpty()) extension else "srt"
            val output = File(cacheDir, "subtitle_${System.currentTimeMillis()}.$safeExt")
            output.writeText(text, Charsets.UTF_8)
            subtitleCacheFile?.delete()
            subtitleCacheFile = output
            Uri.fromFile(output)
        } catch (_: Exception) {
            null
        }
    }

    private fun clearSubtitleCache() {
        subtitleCacheFile?.delete()
        subtitleCacheFile = null
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
        val extension: String
    )

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
        private const val STATE_USER_ORIENTATION = "state_user_orientation"
        private const val SEEK_JUMP_MS = 10_000L
        private const val UI_UPDATE_INTERVAL_MS = 500L
        private const val OVERLAY_AUTO_HIDE_MS = 2500L
        private const val GESTURE_TEXT_HIDE_MS = 1000L
        private const val PREFS = "nsplayer_prefs"
        private const val KEY_SUBTITLE_ENABLED = "subtitle_enabled"
        private const val KEY_SUBTITLE_LANGUAGE = "subtitle_language"
        private const val KEY_SUBTITLE_ENCODING = "subtitle_encoding"
        private const val KEY_SUBTITLE_VIDEO_URI = "subtitle_video_uri"
        private const val KEY_SUBTITLE_URI = "subtitle_uri"
        private const val KEY_SUBTITLE_LABEL = "subtitle_label"
        private const val KEY_SUBTITLE_MIME = "subtitle_mime"
        private const val KEY_SUBTITLE_EXT = "subtitle_ext"
        private const val KEY_PLAYBACK_SPEED = "playback_speed"
        private const val ENCODING_UTF8 = "UTF-8"
        private const val ENCODING_EUC_KR = "EUC-KR"
        private const val ENCODING_CP949 = "CP949"
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
