package com.nshell.nsplayer;

import android.Manifest;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.content.Intent;
import android.content.SharedPreferences;
import android.view.View;
import android.widget.Button;
import android.widget.RadioGroup;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.app.AlertDialog;
import androidx.activity.OnBackPressedCallback;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.lifecycle.ViewModelProvider;
import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.bottomsheet.BottomSheetDialog;
import com.nshell.nsplayer.ui.DisplayItem;
import com.nshell.nsplayer.ui.VideoBrowserViewModel;
import com.nshell.nsplayer.ui.VideoDisplayMode;
import com.nshell.nsplayer.ui.VideoListAdapter;
import com.nshell.nsplayer.ui.VideoMode;
import com.nshell.nsplayer.ui.VideoSortMode;
import com.nshell.nsplayer.ui.VideoSortOrder;

import java.util.List;

public class MainActivity extends AppCompatActivity {
    private static final int REQUEST_PERMISSION = 1001;
    private static final String PREFS = "nsplayer_prefs";
    private static final String KEY_MODE = "video_mode";
    private static final String KEY_DISPLAY = "video_display";
    private static final String KEY_SORT = "video_sort";
    private static final String KEY_SORT_ORDER = "video_sort_order";

    private TextView statusText;
    private TextView emptyText;
    private TextView folderTitle;
    private View folderHeader;
    private View videoDisplayGroup;
    private View headerBackButton;
    private TextView titleText;
    private View selectionBar;
    private Button selectionAllButton;
    private Button selectionMoveButton;
    private Button selectionCopyButton;
    private VideoListAdapter adapter;
    private VideoBrowserViewModel viewModel;
    private RecyclerView list;
    private VideoMode currentMode = VideoMode.FOLDERS;
    private VideoDisplayMode videoDisplayMode = VideoDisplayMode.LIST;
    private VideoSortMode sortMode = VideoSortMode.MODIFIED;
    private VideoSortOrder sortOrder = VideoSortOrder.DESC;
    private boolean inFolderVideos = false;
    private String selectedBucketId;
    private String selectedBucketName;
    private String hierarchyPath = "";
    private SharedPreferences preferences;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        if (getSupportActionBar() != null) {
            getSupportActionBar().hide();
        }

        statusText = findViewById(R.id.statusText);
        emptyText = findViewById(R.id.emptyText);
        folderTitle = findViewById(R.id.folderTitle);
        folderHeader = findViewById(R.id.folderHeader);
        videoDisplayGroup = findViewById(R.id.videoDisplayGroup);
        headerBackButton = findViewById(R.id.headerBackButton);
        titleText = findViewById(R.id.titleText);
        selectionBar = findViewById(R.id.selectionBar);
        selectionAllButton = findViewById(R.id.selectionAllButton);
        selectionMoveButton = findViewById(R.id.selectionMoveButton);
        selectionCopyButton = findViewById(R.id.selectionCopyButton);

        list = findViewById(R.id.list);
        list.setLayoutManager(new LinearLayoutManager(this));
        adapter = new VideoListAdapter();
        adapter.setOnItemClickListener(this::onItemSelected);
        adapter.setOnItemOverflowClickListener(this::showItemBottomSheet);
        adapter.setOnSelectionChangedListener(this::onSelectionChanged);
        list.setAdapter(adapter);

        selectionAllButton.setOnClickListener(v -> {
            if (adapter.isAllSelected()) {
                adapter.clearSelection();
            } else {
                adapter.selectAll();
            }
        });
        selectionMoveButton.setOnClickListener(v -> {
            Toast.makeText(this, getString(R.string.action_not_ready), Toast.LENGTH_SHORT).show();
        });
        selectionCopyButton.setOnClickListener(v -> {
            Toast.makeText(this, getString(R.string.action_not_ready), Toast.LENGTH_SHORT).show();
        });

        preferences = getSharedPreferences(PREFS, MODE_PRIVATE);
        loadPreferences();

        View settingsButton = findViewById(R.id.settingsButton);
        settingsButton.setOnClickListener(this::showSettingsDialog);

        RadioGroup displayGroup = findViewById(R.id.videoDisplayGroup);
        displayGroup.check(videoDisplayMode == VideoDisplayMode.TILE
            ? R.id.videoDisplayTile
            : R.id.videoDisplayList);
        displayGroup.setOnCheckedChangeListener((group, checkedId) -> {
            if (checkedId == R.id.videoDisplayTile) {
                videoDisplayMode = VideoDisplayMode.TILE;
            } else {
                videoDisplayMode = VideoDisplayMode.LIST;
            }
            saveDisplayMode();
            applyVideoDisplayMode();
        });

        Button backButton = findViewById(R.id.backButton);
        backButton.setOnClickListener(v -> {
            if (currentMode == VideoMode.HIERARCHY) {
                if (hierarchyPath != null && !hierarchyPath.isEmpty()) {
                    hierarchyPath = getParentPath(hierarchyPath);
                    updateHeaderState();
                    loadIfPermitted();
                }
                return;
            }
            setMode(VideoMode.FOLDERS);
        });

        headerBackButton.setOnClickListener(v -> {
            if (currentMode == VideoMode.HIERARCHY && !isHierarchyRoot()) {
                hierarchyPath = getParentPath(hierarchyPath);
                updateHeaderState();
                loadIfPermitted();
                return;
            }
            if (inFolderVideos) {
                setMode(VideoMode.FOLDERS);
            }
        });

        viewModel = new ViewModelProvider(this).get(VideoBrowserViewModel.class);
        viewModel.getItems().observe(this, this::renderItems);
        viewModel.getLoading().observe(this, this::renderLoading);

        getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) {
            @Override
            public void handleOnBackPressed() {
                if (adapter.isSelectionMode()) {
                    adapter.clearSelection();
                    return;
                }
                if (currentMode == VideoMode.HIERARCHY && !isHierarchyRoot()) {
                    hierarchyPath = getParentPath(hierarchyPath);
                    updateHeaderState();
                    loadIfPermitted();
                    return;
                }
                if (inFolderVideos) {
                    setMode(VideoMode.FOLDERS);
                    return;
                }
                setEnabled(false);
                getOnBackPressedDispatcher().onBackPressed();
            }
        });

        View topBar = findViewById(R.id.topBar);
        ViewCompat.setOnApplyWindowInsetsListener(topBar, (view, insets) -> {
            int topInset = insets.getInsets(WindowInsetsCompat.Type.systemBars()).top;
            view.setPadding(
                view.getPaddingLeft(),
                topInset + dpToPx(12),
                view.getPaddingRight(),
                view.getPaddingBottom()
            );
            return insets;
        });

        updateHeaderState();
        loadIfPermitted();
    }

    private void loadIfPermitted() {
        if (hasVideoPermission()) {
            if (currentMode == VideoMode.HIERARCHY) {
                viewModel.loadHierarchy(hierarchyPath, sortMode, sortOrder, getContentResolver());
            } else if (inFolderVideos && selectedBucketId != null) {
                viewModel.loadFolderVideos(selectedBucketId, sortMode, sortOrder, getContentResolver());
            } else {
                viewModel.load(currentMode, sortMode, sortOrder, getContentResolver());
            }
        } else {
            statusText.setText(getString(R.string.permission_needed));
            statusText.setVisibility(View.VISIBLE);
            requestVideoPermission();
        }
    }

    private boolean hasVideoPermission() {
        String permission = Build.VERSION.SDK_INT >= 33
            ? Manifest.permission.READ_MEDIA_VIDEO
            : Manifest.permission.READ_EXTERNAL_STORAGE;
        return ContextCompat.checkSelfPermission(this, permission)
            == PackageManager.PERMISSION_GRANTED;
    }

    private void requestVideoPermission() {
        String[] permissions;
        if (Build.VERSION.SDK_INT >= 33) {
            permissions = new String[] { Manifest.permission.READ_MEDIA_VIDEO };
        } else {
            permissions = new String[] { Manifest.permission.READ_EXTERNAL_STORAGE };
        }
        ActivityCompat.requestPermissions(this, permissions, REQUEST_PERMISSION);
    }

    private void renderItems(List<DisplayItem> items) {
        adapter.submit(items);
        boolean isEmpty = items == null || items.isEmpty();
        emptyText.setVisibility(isEmpty ? View.VISIBLE : View.GONE);
    }

    private void renderLoading(Boolean loading) {
        boolean show = loading != null && loading;
        if (show) {
            statusText.setText(getString(R.string.status_loading));
            statusText.setVisibility(View.GONE);
            return;
        }
        CharSequence current = statusText.getText();
        if (current != null && current.toString().equals(getString(R.string.status_loading))) {
            statusText.setText("");
            statusText.setVisibility(View.GONE);
        }
    }

    @Override
    public void onRequestPermissionsResult(
        int requestCode,
        @NonNull String[] permissions,
        @NonNull int[] grantResults
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode != REQUEST_PERMISSION) {
            return;
        }
        if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            loadIfPermitted();
        } else {
            statusText.setText(getString(R.string.permission_denied));
            statusText.setVisibility(View.VISIBLE);
        }
    }

    private void onItemSelected(DisplayItem item) {
        if (item.getType() == DisplayItem.Type.FOLDER) {
            selectedBucketId = item.getBucketId();
            selectedBucketName = item.getTitle();
            inFolderVideos = true;
            updateHeaderState();
            loadIfPermitted();
            return;
        }
        if (item.getType() == DisplayItem.Type.HIERARCHY) {
            hierarchyPath = item.getBucketId();
            updateHeaderState();
            loadIfPermitted();
            return;
        }
        if (item.getType() == DisplayItem.Type.VIDEO) {
            String uri = item.getContentUri();
            if (uri == null || uri.isEmpty()) {
                return;
            }
            Intent intent = new Intent(this, PlayerActivity.class);
            intent.putExtra(PlayerActivity.EXTRA_URI, uri);
            intent.putExtra(PlayerActivity.EXTRA_TITLE, item.getTitle());
            startActivity(intent);
        }
    }

    private void updateHeaderState() {
        if (inFolderVideos) {
            folderHeader.setVisibility(View.GONE);
            if (headerBackButton != null) {
                headerBackButton.setVisibility(View.VISIBLE);
            }
            String title = selectedBucketName != null ? selectedBucketName : "Unknown";
            titleText.setText(title);
            videoDisplayGroup.setVisibility(View.VISIBLE);
            applyVideoDisplayMode();
        } else if (currentMode == VideoMode.HIERARCHY && !isHierarchyRoot()) {
            folderHeader.setVisibility(View.GONE);
            if (headerBackButton != null) {
                headerBackButton.setVisibility(View.VISIBLE);
            }
            titleText.setText(getHierarchyTitle());
            videoDisplayGroup.setVisibility(View.GONE);
            applyVideoDisplayMode();
        } else {
            folderHeader.setVisibility(View.GONE);
            if (headerBackButton != null) {
                headerBackButton.setVisibility(View.GONE);
            }
            titleText.setText(getString(R.string.app_name));
            videoDisplayGroup.setVisibility(View.GONE);
            if (currentMode == VideoMode.VIDEOS || currentMode == VideoMode.HIERARCHY) {
                applyVideoDisplayMode();
            } else {
                list.setLayoutManager(new LinearLayoutManager(this));
                adapter.setVideoDisplayMode(VideoDisplayMode.LIST);
            }
        }
    }

    private void showSettingsDialog(View anchor) {
        View content = getLayoutInflater().inflate(R.layout.popup_settings, null);
        TextView modeFolders = content.findViewById(R.id.settingsModeFolders);
        TextView modeHierarchy = content.findViewById(R.id.settingsModeHierarchy);
        TextView modeVideos = content.findViewById(R.id.settingsModeVideos);
        TextView displayList = content.findViewById(R.id.settingsDisplayList);
        TextView displayTile = content.findViewById(R.id.settingsDisplayTile);
        TextView sortTitle = content.findViewById(R.id.settingsSortTitle);
        TextView sortModified = content.findViewById(R.id.settingsSortModified);
        TextView sortDuration = content.findViewById(R.id.settingsSortDuration);
        TextView sortAsc = content.findViewById(R.id.settingsSortAsc);
        TextView sortDesc = content.findViewById(R.id.settingsSortDesc);
        Button cancelButton = content.findViewById(R.id.settingsCancel);
        Button confirmButton = content.findViewById(R.id.settingsConfirm);
        int defaultColor = modeFolders.getCurrentTextColor();
        int selectedColor = getColor(R.color.brand_green);
        final VideoMode[] pendingMode = { currentMode };
        final VideoDisplayMode[] pendingDisplay = { videoDisplayMode };
        final VideoSortMode[] pendingSort = { sortMode };
        final VideoSortOrder[] pendingOrder = { sortOrder };
        updateModeSelectionUI(
            modeFolders,
            modeHierarchy,
            modeVideos,
            pendingMode[0],
            selectedColor,
            defaultColor
        );
        updateDisplaySelectionUI(
            displayList,
            displayTile,
            pendingDisplay[0],
            selectedColor,
            defaultColor
        );
        updateSortSelectionUI(
            sortTitle,
            sortModified,
            sortDuration,
            sortAsc,
            sortDesc,
            pendingSort[0],
            pendingOrder[0],
            selectedColor,
            defaultColor
        );

        AlertDialog dialog = new AlertDialog.Builder(this)
            .setView(content)
            .create();
        dialog.setCanceledOnTouchOutside(true);

        modeFolders.setOnClickListener(v -> {
            pendingMode[0] = VideoMode.FOLDERS;
            updateModeSelectionUI(modeFolders, modeHierarchy, modeVideos, pendingMode[0], selectedColor, defaultColor);
        });
        modeHierarchy.setOnClickListener(v -> {
            pendingMode[0] = VideoMode.HIERARCHY;
            updateModeSelectionUI(modeFolders, modeHierarchy, modeVideos, pendingMode[0], selectedColor, defaultColor);
        });
        modeVideos.setOnClickListener(v -> {
            pendingMode[0] = VideoMode.VIDEOS;
            updateModeSelectionUI(modeFolders, modeHierarchy, modeVideos, pendingMode[0], selectedColor, defaultColor);
        });

        displayList.setOnClickListener(v -> {
            pendingDisplay[0] = VideoDisplayMode.LIST;
            updateDisplaySelectionUI(displayList, displayTile, pendingDisplay[0], selectedColor, defaultColor);
        });
        displayTile.setOnClickListener(v -> {
            pendingDisplay[0] = VideoDisplayMode.TILE;
            updateDisplaySelectionUI(displayList, displayTile, pendingDisplay[0], selectedColor, defaultColor);
        });

        sortTitle.setOnClickListener(v -> {
            pendingSort[0] = VideoSortMode.TITLE;
            updateSortSelectionUI(
                sortTitle,
                sortModified,
                sortDuration,
                sortAsc,
                sortDesc,
                pendingSort[0],
                pendingOrder[0],
                selectedColor,
                defaultColor
            );
        });
        sortModified.setOnClickListener(v -> {
            pendingSort[0] = VideoSortMode.MODIFIED;
            updateSortSelectionUI(
                sortTitle,
                sortModified,
                sortDuration,
                sortAsc,
                sortDesc,
                pendingSort[0],
                pendingOrder[0],
                selectedColor,
                defaultColor
            );
        });
        sortDuration.setOnClickListener(v -> {
            pendingSort[0] = VideoSortMode.LENGTH;
            updateSortSelectionUI(
                sortTitle,
                sortModified,
                sortDuration,
                sortAsc,
                sortDesc,
                pendingSort[0],
                pendingOrder[0],
                selectedColor,
                defaultColor
            );
        });
        sortAsc.setOnClickListener(v -> {
            pendingOrder[0] = VideoSortOrder.ASC;
            updateSortSelectionUI(
                sortTitle,
                sortModified,
                sortDuration,
                sortAsc,
                sortDesc,
                pendingSort[0],
                pendingOrder[0],
                selectedColor,
                defaultColor
            );
        });
        sortDesc.setOnClickListener(v -> {
            pendingOrder[0] = VideoSortOrder.DESC;
            updateSortSelectionUI(
                sortTitle,
                sortModified,
                sortDuration,
                sortAsc,
                sortDesc,
                pendingSort[0],
                pendingOrder[0],
                selectedColor,
                defaultColor
            );
        });

        cancelButton.setOnClickListener(v -> dialog.dismiss());
        confirmButton.setOnClickListener(v -> {
            boolean modeChanged = pendingMode[0] != currentMode;
            boolean displayChanged = pendingDisplay[0] != videoDisplayMode;
            boolean sortChanged = pendingSort[0] != sortMode;
            boolean orderChanged = pendingOrder[0] != sortOrder;
            if (displayChanged) {
                videoDisplayMode = pendingDisplay[0];
                saveDisplayMode();
            }
            if (sortChanged || orderChanged) {
                sortMode = pendingSort[0];
                sortOrder = pendingOrder[0];
                saveSortMode();
                saveSortOrder();
            }
            RadioGroup mainDisplayGroup = findViewById(R.id.videoDisplayGroup);
            mainDisplayGroup.check(videoDisplayMode == VideoDisplayMode.TILE
                ? R.id.videoDisplayTile
                : R.id.videoDisplayList);
            if (modeChanged) {
                setMode(pendingMode[0]);
            } else if (displayChanged) {
                applyVideoDisplayMode();
            }
            if ((sortChanged || orderChanged) && !modeChanged) {
                loadIfPermitted();
            }
            dialog.dismiss();
        });

        dialog.show();
    }

    private void showItemBottomSheet(DisplayItem item) {
        if (item == null) {
            return;
        }
        if (adapter.isSelectionMode()) {
            return;
        }
        View content = getLayoutInflater().inflate(R.layout.bottom_sheet_item, null);
        TextView title = content.findViewById(R.id.bottomSheetTitle);
        title.setText(item.getTitle());
        Button renameButton = content.findViewById(R.id.actionRename);
        Button propertiesButton = content.findViewById(R.id.actionProperties);

        BottomSheetDialog dialog = new BottomSheetDialog(this);
        dialog.setContentView(content);

        renameButton.setOnClickListener(v -> {
            Toast.makeText(this, getString(R.string.action_not_ready), Toast.LENGTH_SHORT).show();
            dialog.dismiss();
        });
        propertiesButton.setOnClickListener(v -> {
            Toast.makeText(this, getString(R.string.action_not_ready), Toast.LENGTH_SHORT).show();
            dialog.dismiss();
        });

        dialog.show();
    }

    private void setMode(VideoMode mode) {
        currentMode = mode;
        inFolderVideos = false;
        selectedBucketId = null;
        selectedBucketName = null;
        if (mode == VideoMode.HIERARCHY) {
            hierarchyPath = "";
        }
        saveMode();
        updateHeaderState();
        loadIfPermitted();
    }

    private void applyVideoDisplayMode() {
        adapter.setVideoDisplayMode(videoDisplayMode);
        if (videoDisplayMode == VideoDisplayMode.TILE) {
            list.setLayoutManager(new GridLayoutManager(this, 2));
        } else {
            list.setLayoutManager(new LinearLayoutManager(this));
        }
        adapter.notifyDataSetChanged();
    }

    private void onSelectionChanged(int selectedCount, boolean selectionMode) {
        if (selectionBar == null) {
            return;
        }
        selectionBar.setVisibility(selectionMode ? View.VISIBLE : View.GONE);
    }


    private void loadPreferences() {
        String modeValue = preferences.getString(KEY_MODE, VideoMode.FOLDERS.name());
        String displayValue = preferences.getString(KEY_DISPLAY, VideoDisplayMode.LIST.name());
        String sortValue = preferences.getString(KEY_SORT, VideoSortMode.MODIFIED.name());
        String sortOrderValue = preferences.getString(KEY_SORT_ORDER, VideoSortOrder.DESC.name());
        try {
            currentMode = VideoMode.valueOf(modeValue);
        } catch (IllegalArgumentException ignored) {
            currentMode = VideoMode.FOLDERS;
        }
        try {
            videoDisplayMode = VideoDisplayMode.valueOf(displayValue);
        } catch (IllegalArgumentException ignored) {
            videoDisplayMode = VideoDisplayMode.LIST;
        }
        try {
            sortMode = VideoSortMode.valueOf(sortValue);
        } catch (IllegalArgumentException ignored) {
            sortMode = VideoSortMode.MODIFIED;
        }
        try {
            sortOrder = VideoSortOrder.valueOf(sortOrderValue);
        } catch (IllegalArgumentException ignored) {
            sortOrder = VideoSortOrder.DESC;
        }
    }

    private void saveMode() {
        preferences.edit().putString(KEY_MODE, currentMode.name()).apply();
    }

    private void saveDisplayMode() {
        preferences.edit().putString(KEY_DISPLAY, videoDisplayMode.name()).apply();
    }

    private void saveSortMode() {
        preferences.edit().putString(KEY_SORT, sortMode.name()).apply();
    }

    private void saveSortOrder() {
        preferences.edit().putString(KEY_SORT_ORDER, sortOrder.name()).apply();
    }


    private boolean isHierarchyRoot() {
        return hierarchyPath == null || hierarchyPath.isEmpty();
    }

    private String getHierarchyTitle() {
        if (hierarchyPath == null || hierarchyPath.isEmpty()) {
            return "Root";
        }
        String trimmed = hierarchyPath;
        if (trimmed.endsWith("/")) {
            trimmed = trimmed.substring(0, trimmed.length() - 1);
        }
        int lastSlash = trimmed.lastIndexOf('/');
        if (lastSlash >= 0 && lastSlash < trimmed.length() - 1) {
            return trimmed.substring(lastSlash + 1);
        }
        return trimmed.isEmpty() ? "Root" : trimmed;
    }

    private String getParentPath(String path) {
        if (path == null || path.isEmpty()) {
            return "";
        }
        String trimmed = path;
        if (trimmed.endsWith("/")) {
            trimmed = trimmed.substring(0, trimmed.length() - 1);
        }
        int lastSlash = trimmed.lastIndexOf('/');
        if (lastSlash < 0) {
            return "";
        }
        return trimmed.substring(0, lastSlash + 1);
    }

    private void updateModeSelectionUI(
        TextView folders,
        TextView hierarchy,
        TextView videos,
        VideoMode selectedMode,
        int selectedColor,
        int defaultColor
    ) {
        folders.setTextColor(selectedMode == VideoMode.FOLDERS ? selectedColor : defaultColor);
        hierarchy.setTextColor(selectedMode == VideoMode.HIERARCHY ? selectedColor : defaultColor);
        videos.setTextColor(selectedMode == VideoMode.VIDEOS ? selectedColor : defaultColor);
    }

    private void updateDisplaySelectionUI(
        TextView list,
        TextView tile,
        VideoDisplayMode selectedDisplay,
        int selectedColor,
        int defaultColor
    ) {
        list.setTextColor(selectedDisplay == VideoDisplayMode.LIST ? selectedColor : defaultColor);
        tile.setTextColor(selectedDisplay == VideoDisplayMode.TILE ? selectedColor : defaultColor);
    }

    private void updateSortSelectionUI(
        TextView title,
        TextView modified,
        TextView duration,
        TextView asc,
        TextView desc,
        VideoSortMode selectedSort,
        VideoSortOrder selectedOrder,
        int selectedColor,
        int defaultColor
    ) {
        boolean titleSelected = selectedSort == VideoSortMode.TITLE;
        boolean modifiedSelected = selectedSort == VideoSortMode.MODIFIED;
        boolean durationSelected = selectedSort == VideoSortMode.LENGTH;
        title.setTextColor(titleSelected ? selectedColor : defaultColor);
        modified.setTextColor(modifiedSelected ? selectedColor : defaultColor);
        duration.setTextColor(durationSelected ? selectedColor : defaultColor);
        if (titleSelected) {
            asc.setText(getString(R.string.sort_title_asc));
            desc.setText(getString(R.string.sort_title_desc));
        } else if (modifiedSelected) {
            asc.setText(getString(R.string.sort_modified_asc));
            desc.setText(getString(R.string.sort_modified_desc));
        } else {
            asc.setText(getString(R.string.sort_length_asc));
            desc.setText(getString(R.string.sort_length_desc));
        }
        asc.setTextColor(selectedOrder == VideoSortOrder.ASC ? selectedColor : defaultColor);
        desc.setTextColor(selectedOrder == VideoSortOrder.DESC ? selectedColor : defaultColor);
    }

    private int dpToPx(int dp) {
        float density = getResources().getDisplayMetrics().density;
        return Math.round(dp * density);
    }

}
