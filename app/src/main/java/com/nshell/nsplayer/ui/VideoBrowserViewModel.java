package com.nshell.nsplayer.ui;

import android.content.ContentResolver;

import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;
import androidx.lifecycle.ViewModel;

import com.nshell.nsplayer.data.MediaStoreVideoRepository;
import com.nshell.nsplayer.data.VideoRepository;
import com.nshell.nsplayer.ui.VideoSortMode;
import com.nshell.nsplayer.ui.VideoSortOrder;

import java.util.Collections;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class VideoBrowserViewModel extends ViewModel {
    private final MutableLiveData<List<DisplayItem>> items =
        new MutableLiveData<>(Collections.emptyList());
    private final MutableLiveData<Boolean> loading = new MutableLiveData<>(false);
    private final VideoRepository repository = new MediaStoreVideoRepository();
    private final ExecutorService executor = Executors.newSingleThreadExecutor();

    public LiveData<List<DisplayItem>> getItems() {
        return items;
    }

    public LiveData<Boolean> getLoading() {
        return loading;
    }

    public void load(
        VideoMode mode,
        VideoSortMode sortMode,
        VideoSortOrder sortOrder,
        ContentResolver resolver
    ) {
        loading.setValue(true);
        executor.execute(() -> {
            List<DisplayItem> result = repository.load(mode, sortMode, sortOrder, resolver);
            items.postValue(result);
            loading.postValue(false);
        });
    }

    public void loadFolderVideos(
        String bucketId,
        VideoSortMode sortMode,
        VideoSortOrder sortOrder,
        ContentResolver resolver
    ) {
        loading.setValue(true);
        executor.execute(() -> {
            List<DisplayItem> result = repository.loadVideosInFolder(bucketId, sortMode, sortOrder, resolver);
            items.postValue(result);
            loading.postValue(false);
        });
    }

    public void loadHierarchy(
        String path,
        VideoSortMode sortMode,
        VideoSortOrder sortOrder,
        ContentResolver resolver
    ) {
        loading.setValue(true);
        executor.execute(() -> {
            List<DisplayItem> result = repository.loadHierarchy(path, sortMode, sortOrder, resolver);
            items.postValue(result);
            loading.postValue(false);
        });
    }

    @Override
    protected void onCleared() {
        super.onCleared();
        executor.shutdown();
    }
}
