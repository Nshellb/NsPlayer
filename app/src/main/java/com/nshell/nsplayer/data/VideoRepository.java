package com.nshell.nsplayer.data;

import android.content.ContentResolver;

import com.nshell.nsplayer.ui.DisplayItem;
import com.nshell.nsplayer.ui.VideoMode;
import com.nshell.nsplayer.ui.VideoSortMode;
import com.nshell.nsplayer.ui.VideoSortOrder;

import java.util.List;

public interface VideoRepository {
    List<DisplayItem> load(
        VideoMode mode,
        VideoSortMode sortMode,
        VideoSortOrder sortOrder,
        ContentResolver resolver
    );

    List<DisplayItem> loadVideosInFolder(
        String bucketId,
        VideoSortMode sortMode,
        VideoSortOrder sortOrder,
        ContentResolver resolver
    );

    List<DisplayItem> loadHierarchy(
        String path,
        VideoSortMode sortMode,
        VideoSortOrder sortOrder,
        ContentResolver resolver
    );
}
