# Custom Video Player Design (NsPlayer)

Goal: implement a custom local-video player with:
- Play/Pause
- Double-tap left = -10s, right = +10s
- Seek bar
- Left vertical swipe = screen brightness
- Right vertical swipe = volume
- Use only videos detected on device (MediaStore)

## 1) Core playback stack
- Use Jetpack Media3 (ExoPlayer) for reliable seeking and gesture-friendly control.
- Replace current `VideoView + MediaController` with:
  - `ExoPlayer` instance
  - `PlayerView` (or `StyledPlayerView`) with controller disabled
  - Custom overlay for controls and gestures

Rationale:
- Accurate seek, fast scrubbing, and easy hook points for UI sync.
- Full control of controller UI.

## 2) UI layout (activity_player.xml)
Root: `FrameLayout`
- `PlayerView` (match_parent)
  - `use_controller=false`
  - `resize_mode=fit` (or `zoom` based on design)
- `PlayerOverlayView` (custom layout)
  - Top bar (title, back)
  - Center play/pause + feedback texts
  - Bottom bar: seek bar + current time / duration
- Gesture layers (left/right transparent views) to route swipes

## 3) Components

### A) PlayerActivity
Responsibilities:
- Create/prepare/release ExoPlayer
- Bind UI controls (play/pause, seek)
- Sync seek bar with playback position
- Handle orientation + fullscreen system UI
- Receive video URI from intent

Lifecycle:
- onStart/onResume: prepare and play
- onPause/onStop: pause and release (depending on requirement)

### B) PlayerGestureHandler
Handles:
- Double tap left/right for seek
- Vertical swipe left for brightness
- Vertical swipe right for volume

Implementation details:
- Use `GestureDetector` for double-tap
- Use `onTouchListener` for vertical swipe detection
- Determine screen half: `x < width/2` => left, else right
- Use thresholds:
  - Start swipe when vertical delta > 8dp (prevent accidental)
  - Ignore horizontal-dominant gestures

Brightness control:
- Read current window brightness (`WindowManager.LayoutParams.screenBrightness`)
- If value is `-1`, fetch system brightness as baseline
- Apply `screenBrightness` in [0.02, 1.0]

Volume control:
- Use `AudioManager` with `STREAM_MUSIC`
- Adjust by step size based on swipe distance
- Clamp to [0, max]

### C) PlayerOverlayController
State:
- `isPlaying`
- `currentPositionMs`, `durationMs`
- `isScrubbing`
- `lastGestureMessage` (e.g., "+10s", "Brightness 70%")

Behavior:
- Show/hide overlay on single tap
- Auto-hide after 2-3 seconds of inactivity
- Show temporary toast-like feedback on gestures

## 4) Data flow
1. `PlayerActivity` receives `Uri`
2. `ExoPlayer` prepares media item
3. `PlayerOverlayController` observes player state
4. Seek bar updates every 250-500ms
5. Gestures adjust:
   - double tap => `player.seekTo(...)`
   - swipe => brightness/volume and update overlay message

## 5) Permissions and MediaStore
No new permissions beyond existing local media access.
Use already existing MediaStore repository for listing.
Player receives exact `Uri` and plays directly.

## 6) Edge cases
- If duration is unknown, disable seek bar.
- Clamp seek to [0, duration].
- Persist last brightness and restore on exit (optional).
- If overlay hidden, gestures still active.

## 7) Implementation steps (suggested)
1. Add Media3 dependencies.
2. Replace `VideoView` with `PlayerView` in `activity_player.xml`.
3. Add custom overlay layout.
4. Implement ExoPlayer setup/release.
5. Add gesture detection + feedback.
6. Wire seek bar with player position.

## 8) Testing checklist
- Double-tap seek works left/right
- Swipe brightness/volume does not conflict with seek bar drag
- Seek bar drag pauses updates while scrubbing
- Local videos load and play in landscape/portrait
- Overlay auto-hide and toggle
