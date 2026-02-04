package com.nshell.nsplayer;

import android.content.pm.ActivityInfo;
import android.media.MediaMetadataRetriever;
import android.net.Uri;
import android.os.Bundle;
import android.widget.MediaController;
import android.widget.VideoView;
import android.widget.FrameLayout;
import java.io.IOException;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.core.view.WindowInsetsControllerCompat;

public class PlayerActivity extends AppCompatActivity {
    public static final String EXTRA_URI = "extra_uri";
    public static final String EXTRA_TITLE = "extra_title";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_player);

        VideoView videoView = findViewById(R.id.videoView);

        if (getSupportActionBar() != null) {
            getSupportActionBar().hide();
        }
        WindowCompat.setDecorFitsSystemWindows(getWindow(), false);
        hideSystemUi();

        String uriText = getIntent().getStringExtra(EXTRA_URI);

        if (uriText != null) {
            Uri uri = Uri.parse(uriText);
            applyAutoOrientation(uri);
            MediaController controller = new MediaController(this);
            controller.setAnchorView(videoView);
            videoView.setMediaController(controller);
            videoView.setVideoURI(uri);
            videoView.setOnPreparedListener(mp -> {
                mp.setOnVideoSizeChangedListener((player, width, height) -> {
                    centerVideo(videoView, width, height);
                });
                centerVideo(videoView, mp.getVideoWidth(), mp.getVideoHeight());
            });
            videoView.start();
        }
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) {
            hideSystemUi();
        }
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

    private void centerVideo(VideoView videoView, int videoWidth, int videoHeight) {
        if (videoWidth <= 0 || videoHeight <= 0) {
            return;
        }
        int viewWidth = videoView.getWidth();
        int viewHeight = videoView.getHeight();
        if (viewWidth == 0 || viewHeight == 0) {
            return;
        }
        float videoAspect = (float) videoWidth / (float) videoHeight;
        float viewAspect = (float) viewWidth / (float) viewHeight;
        int targetWidth;
        int targetHeight;
        if (videoAspect > viewAspect) {
            targetWidth = viewWidth;
            targetHeight = Math.round(viewWidth / videoAspect);
        } else {
            targetHeight = viewHeight;
            targetWidth = Math.round(viewHeight * videoAspect);
        }
        FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(targetWidth, targetHeight);
        params.gravity = android.view.Gravity.CENTER;
        videoView.setLayoutParams(params);
    }
}
