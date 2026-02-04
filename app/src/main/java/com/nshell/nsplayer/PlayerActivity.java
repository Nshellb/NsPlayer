package com.nshell.nsplayer;

import android.content.Context;
import android.content.pm.ActivityInfo;
import android.media.AudioManager;
import android.media.MediaMetadataRetriever;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.content.res.Configuration;
import android.view.GestureDetector;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.ImageButton;
import android.widget.SeekBar;
import android.widget.TextView;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.core.view.WindowInsetsControllerCompat;
import androidx.media3.common.C;
import androidx.media3.common.MediaItem;
import androidx.media3.common.Player;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.ui.PlayerView;

import java.io.IOException;
import java.util.Locale;

public class PlayerActivity extends AppCompatActivity {
    public static final String EXTRA_URI = "extra_uri";
    public static final String EXTRA_TITLE = "extra_title";
    private static final String STATE_USER_ORIENTATION = "state_user_orientation";

    private static final long SEEK_JUMP_MS = 10_000;
    private static final long UI_UPDATE_INTERVAL_MS = 500;
    private static final long OVERLAY_AUTO_HIDE_MS = 2500;
    private static final long GESTURE_TEXT_HIDE_MS = 1000;

    private PlayerView playerView;
    private ExoPlayer player;
    private View overlayContainer;
    private ImageButton playPauseButton;
    private ImageButton rotateButton;
    private SeekBar seekBar;
    private TextView positionText;
    private TextView durationText;
    private TextView titleText;
    private TextView gestureText;

    private final Handler uiHandler = new Handler(Looper.getMainLooper());
    private final Runnable progressUpdater = new Runnable() {
        @Override
        public void run() {
            if (player != null && !isScrubbing) {
                updateProgress(player.getCurrentPosition(), player.getDuration());
            }
            uiHandler.postDelayed(this, UI_UPDATE_INTERVAL_MS);
        }
    };
    private final Runnable hideOverlayRunnable = () -> overlayContainer.setVisibility(View.GONE);
    private final Runnable hideGestureTextRunnable = () -> gestureText.setVisibility(View.GONE);

    private boolean isScrubbing = false;
    private GestureDetector gestureDetector;
    private float swipeThresholdPx;
    private boolean isAdjusting = false;
    private float startX;
    private float startY;
    private boolean adjustLeftSide;
    private float startBrightness = -1f;
    private int startVolume = 0;
    private int maxVolume = 0;
    private AudioManager audioManager;
    private Uri videoUri;
    private int userOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_player);

        if (getSupportActionBar() != null) {
            getSupportActionBar().hide();
        }
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        WindowCompat.setDecorFitsSystemWindows(getWindow(), false);
        hideSystemUi();

        playerView = findViewById(R.id.playerView);
        overlayContainer = findViewById(R.id.overlayContainer);
        playPauseButton = findViewById(R.id.playPauseButton);
        rotateButton = findViewById(R.id.rotateButton);
        seekBar = findViewById(R.id.seekBar);
        positionText = findViewById(R.id.positionText);
        durationText = findViewById(R.id.durationText);
        titleText = findViewById(R.id.titleText);
        gestureText = findViewById(R.id.gestureText);

        audioManager = (AudioManager) getSystemService(Context.AUDIO_SERVICE);
        if (audioManager != null) {
            maxVolume = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC);
        }

        swipeThresholdPx = getResources().getDisplayMetrics().density * 8f;
        gestureDetector = new GestureDetector(this, new GestureListener());

        playPauseButton.setOnClickListener(v -> togglePlayback());
        rotateButton.setOnClickListener(v -> toggleOrientation());

        seekBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar bar, int progress, boolean fromUser) {
                if (fromUser) {
                    positionText.setText(formatTime(progress));
                }
            }

            @Override
            public void onStartTrackingTouch(SeekBar bar) {
                isScrubbing = true;
                uiHandler.removeCallbacks(progressUpdater);
            }

            @Override
            public void onStopTrackingTouch(SeekBar bar) {
                isScrubbing = false;
                if (player != null) {
                    player.seekTo(bar.getProgress());
                }
                uiHandler.post(progressUpdater);
                scheduleOverlayHide();
            }
        });

        View root = findViewById(R.id.playerRoot);
        View.OnTouchListener touchListener = (v, event) -> {
            if (event.getActionMasked() == MotionEvent.ACTION_DOWN) {
                showOverlay();
            }
            if (isTouchOnControls(event)) {
                return false;
            }
            boolean handled = gestureDetector.onTouchEvent(event);
            handleVerticalSwipe(event, v.getWidth(), v.getHeight());
            return handled || isAdjusting;
        };
        root.setOnTouchListener(touchListener);
        playerView.setOnTouchListener(touchListener);
        overlayContainer.setOnTouchListener(touchListener);

        if (savedInstanceState != null) {
            userOrientation = savedInstanceState.getInt(
                STATE_USER_ORIENTATION,
                ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
            );
        }

        String uriText = getIntent().getStringExtra(EXTRA_URI);
        if (uriText == null) {
            finish();
            return;
        }
        videoUri = Uri.parse(uriText);
        String title = getIntent().getStringExtra(EXTRA_TITLE);
        if (title != null) {
            titleText.setText(title);
        }
        if (userOrientation != ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED) {
            setRequestedOrientation(userOrientation);
        } else {
            applyAutoOrientation(videoUri);
        }
    }

    @Override
    protected void onStart() {
        super.onStart();
        initializePlayer();
        uiHandler.post(progressUpdater);
        scheduleOverlayHide();
    }

    @Override
    protected void onStop() {
        super.onStop();
        uiHandler.removeCallbacks(progressUpdater);
        uiHandler.removeCallbacks(hideOverlayRunnable);
        uiHandler.removeCallbacks(hideGestureTextRunnable);
        releasePlayer();
    }

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);
        outState.putInt(STATE_USER_ORIENTATION, userOrientation);
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) {
            hideSystemUi();
        }
    }

    private void initializePlayer() {
        if (player != null) {
            return;
        }
        player = new ExoPlayer.Builder(this).build();
        playerView.setPlayer(player);
        player.addListener(new Player.Listener() {
            @Override
            public void onIsPlayingChanged(boolean isPlaying) {
                updatePlayPauseIcon(isPlaying);
            }

            @Override
            public void onPlaybackStateChanged(int state) {
                if (state == Player.STATE_READY) {
                    updateProgress(player.getCurrentPosition(), player.getDuration());
                }
            }
        });

        player.setMediaItem(MediaItem.fromUri(videoUri));
        player.prepare();
        player.play();
        updatePlayPauseIcon(true);
    }

    private void releasePlayer() {
        if (player != null) {
            player.release();
            player = null;
        }
    }

    private void togglePlayback() {
        if (player == null) {
            return;
        }
        if (player.isPlaying()) {
            player.pause();
            updatePlayPauseIcon(false);
        } else {
            player.play();
            updatePlayPauseIcon(true);
        }
        scheduleOverlayHide();
    }

    private void updatePlayPauseIcon(boolean isPlaying) {
        int icon = isPlaying ? android.R.drawable.ic_media_pause : android.R.drawable.ic_media_play;
        playPauseButton.setImageResource(icon);
    }

    private void updateProgress(long positionMs, long durationMs) {
        if (durationMs == C.TIME_UNSET || durationMs <= 0) {
            seekBar.setEnabled(false);
            durationText.setText("--:--");
            positionText.setText(formatTime(positionMs));
            return;
        }
        seekBar.setEnabled(true);
        int safeDuration = (int) Math.min(durationMs, Integer.MAX_VALUE);
        seekBar.setMax(safeDuration);
        seekBar.setProgress((int) Math.min(positionMs, safeDuration));
        positionText.setText(formatTime(positionMs));
        durationText.setText(formatTime(durationMs));
    }

    private void seekBy(long deltaMs) {
        if (player == null) {
            return;
        }
        long duration = player.getDuration();
        long target = player.getCurrentPosition() + deltaMs;
        if (duration != C.TIME_UNSET && duration > 0) {
            target = Math.max(0, Math.min(target, duration));
        } else {
            target = Math.max(0, target);
        }
        player.seekTo(target);
        showGestureText(deltaMs > 0 ? "+10s" : "-10s");
        scheduleOverlayHide();
    }

    private void toggleOverlay() {
        if (overlayContainer.getVisibility() == View.VISIBLE) {
            overlayContainer.setVisibility(View.GONE);
            uiHandler.removeCallbacks(hideOverlayRunnable);
        } else {
            showOverlay();
        }
    }

    private void showOverlay() {
        overlayContainer.setVisibility(View.VISIBLE);
        scheduleOverlayHide();
    }

    private void scheduleOverlayHide() {
        overlayContainer.setVisibility(View.VISIBLE);
        uiHandler.removeCallbacks(hideOverlayRunnable);
        uiHandler.postDelayed(hideOverlayRunnable, OVERLAY_AUTO_HIDE_MS);
    }

    private void showGestureText(String text) {
        gestureText.setText(text);
        gestureText.setVisibility(View.VISIBLE);
        uiHandler.removeCallbacks(hideGestureTextRunnable);
        uiHandler.postDelayed(hideGestureTextRunnable, GESTURE_TEXT_HIDE_MS);
    }

    private boolean isTouchOnControls(MotionEvent event) {
        return isPointInsideView(event, playPauseButton)
            || isPointInsideView(event, rotateButton)
            || isPointInsideView(event, seekBar)
            || isPointInsideView(event, overlayContainer.findViewById(R.id.bottomBar));
    }

    private boolean isPointInsideView(MotionEvent event, View view) {
        if (view == null || view.getVisibility() != View.VISIBLE) {
            return false;
        }
        int[] location = new int[2];
        view.getLocationOnScreen(location);
        float x = event.getRawX();
        float y = event.getRawY();
        return x >= location[0]
            && x <= location[0] + view.getWidth()
            && y >= location[1]
            && y <= location[1] + view.getHeight();
    }

    private void handleVerticalSwipe(MotionEvent event, int width, int height) {
        if (height <= 0) {
            return;
        }
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                startX = event.getX();
                startY = event.getY();
                adjustLeftSide = startX < width / 2f;
                isAdjusting = false;
                startBrightness = getCurrentBrightness();
                startVolume = audioManager != null
                    ? audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
                    : 0;
                break;
            case MotionEvent.ACTION_MOVE:
                float dy = event.getY() - startY;
                float dx = event.getX() - startX;
                if (!isAdjusting) {
                    if (Math.abs(dy) > swipeThresholdPx && Math.abs(dy) > Math.abs(dx)) {
                        isAdjusting = true;
                    } else {
                        return;
                    }
                }
                float delta = -dy / height;
                if (adjustLeftSide) {
                    float target = clamp(startBrightness + delta, 0.02f, 1f);
                    setWindowBrightness(target);
                    int percent = Math.round(target * 100f);
                    showGestureText("Brightness " + percent + "%");
                } else {
                    if (audioManager != null && maxVolume > 0) {
                        int target = clampVolume(startVolume + Math.round(delta * maxVolume));
                        audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, target, 0);
                        int percent = Math.round((target / (float) maxVolume) * 100f);
                        showGestureText("Volume " + percent + "%");
                    }
                }
                break;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                isAdjusting = false;
                scheduleOverlayHide();
                break;
            default:
                break;
        }
    }

    private float getCurrentBrightness() {
        float current = getWindow().getAttributes().screenBrightness;
        if (current >= 0f) {
            return current;
        }
        try {
            int system = Settings.System.getInt(getContentResolver(), Settings.System.SCREEN_BRIGHTNESS);
            return clamp(system / 255f, 0.02f, 1f);
        } catch (Settings.SettingNotFoundException ignored) {
            return 0.5f;
        }
    }

    private void setWindowBrightness(float value) {
        android.view.WindowManager.LayoutParams params = getWindow().getAttributes();
        params.screenBrightness = value;
        getWindow().setAttributes(params);
    }

    private int clampVolume(int target) {
        if (target < 0) {
            return 0;
        }
        if (target > maxVolume) {
            return maxVolume;
        }
        return target;
    }

    private float clamp(float value, float min, float max) {
        return Math.max(min, Math.min(value, max));
    }

    private String formatTime(long ms) {
        long totalSeconds = Math.max(0, ms / 1000);
        long seconds = totalSeconds % 60;
        long minutes = (totalSeconds / 60) % 60;
        long hours = totalSeconds / 3600;
        if (hours > 0) {
            return String.format(Locale.US, "%d:%02d:%02d", hours, minutes, seconds);
        }
        return String.format(Locale.US, "%02d:%02d", minutes, seconds);
    }

    private void hideSystemUi() {
        WindowInsetsControllerCompat controller =
            ViewCompat.getWindowInsetsController(getWindow().getDecorView());
        if (controller != null) {
            controller.hide(WindowInsetsCompat.Type.systemBars());
            controller.setSystemBarsBehavior(
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            );
        }
    }

    private void applyAutoOrientation(Uri uri) {
        if (userOrientation != ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED) {
            return;
        }
        MediaMetadataRetriever retriever = new MediaMetadataRetriever();
        try {
            retriever.setDataSource(this, uri);
            String widthValue = retriever.extractMetadata(
                MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH
            );
            String heightValue = retriever.extractMetadata(
                MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT
            );
            if (widthValue == null || heightValue == null) {
                return;
            }
            int width = Integer.parseInt(widthValue);
            int height = Integer.parseInt(heightValue);
            if (width > height) {
                setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE);
            } else {
                setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED);
            }
        } catch (RuntimeException ignored) {
            // Ignore invalid metadata.
        } finally {
            try {
                retriever.release();
            } catch (IOException | RuntimeException ignored) {
                // Ignore release failures.
            }
        }
    }

    private void toggleOrientation() {
        int current = getResources().getConfiguration().orientation;
        if (current == Configuration.ORIENTATION_LANDSCAPE) {
            userOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT;
            setRequestedOrientation(userOrientation);
        } else {
            userOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE;
            setRequestedOrientation(userOrientation);
        }
        showOverlay();
    }

    private class GestureListener extends GestureDetector.SimpleOnGestureListener {
        @Override
        public boolean onSingleTapConfirmed(MotionEvent e) {
            showOverlay();
            return true;
        }

        @Override
        public boolean onDoubleTap(MotionEvent e) {
            if (player == null) {
                return false;
            }
            float x = e.getX();
            int width = playerView.getWidth();
            if (width <= 0) {
                return false;
            }
            float third = width / 3f;
            if (x < third) {
                seekBy(-SEEK_JUMP_MS);
            } else if (x < third * 2f) {
                togglePlayback();
                showGestureText(player.isPlaying() ? "Play" : "Pause");
            } else {
                seekBy(SEEK_JUMP_MS);
            }
            showOverlay();
            return true;
        }
    }
}
