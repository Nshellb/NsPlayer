package com.nshell.nsplayer.data;

import android.content.ContentResolver;
import android.content.ContentUris;
import android.database.Cursor;
import android.net.Uri;
import android.provider.MediaStore;

import com.nshell.nsplayer.ui.DisplayItem;
import com.nshell.nsplayer.ui.VideoMode;
import com.nshell.nsplayer.ui.VideoSortMode;
import com.nshell.nsplayer.ui.VideoSortOrder;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public class MediaStoreVideoRepository implements VideoRepository {
    private static final Uri VIDEOS_URI = MediaStore.Video.Media.EXTERNAL_CONTENT_URI;

    @Override
    public List<DisplayItem> load(
        VideoMode mode,
        VideoSortMode sortMode,
        VideoSortOrder sortOrder,
        ContentResolver resolver
    ) {
        List<VideoEntry> entries = queryVideos(resolver);
        sortEntries(entries, sortMode, sortOrder);
        if (mode == VideoMode.VIDEOS) {
            return buildVideoItems(entries);
        }
        if (mode == VideoMode.HIERARCHY) {
            return buildHierarchyLevelItems(entries, "", sortMode, sortOrder);
        }
        return buildFolderItems(entries);
    }

    @Override
    public List<DisplayItem> loadVideosInFolder(
        String bucketId,
        VideoSortMode sortMode,
        VideoSortOrder sortOrder,
        ContentResolver resolver
    ) {
        List<VideoEntry> entries = queryVideosInternal(bucketId, resolver);
        sortEntries(entries, sortMode, sortOrder);
        return buildVideoItems(entries);
    }

    @Override
    public List<DisplayItem> loadHierarchy(
        String path,
        VideoSortMode sortMode,
        VideoSortOrder sortOrder,
        ContentResolver resolver
    ) {
        List<VideoEntry> entries = queryVideos(resolver);
        return buildHierarchyLevelItems(entries, path, sortMode, sortOrder);
    }

    private List<VideoEntry> queryVideos(ContentResolver resolver) {
        return queryVideosInternal(null, resolver);
    }

    private List<VideoEntry> queryVideosInternal(String bucketId, ContentResolver resolver) {
        List<VideoEntry> entries = new ArrayList<>();
        String[] projection = new String[] {
            MediaStore.Video.Media._ID,
            MediaStore.Video.Media.DISPLAY_NAME,
            MediaStore.Video.Media.BUCKET_ID,
            MediaStore.Video.Media.BUCKET_DISPLAY_NAME,
            MediaStore.Video.Media.DURATION,
            MediaStore.Video.Media.WIDTH,
            MediaStore.Video.Media.HEIGHT,
            MediaStore.Video.Media.RELATIVE_PATH,
            MediaStore.Video.Media.DATE_ADDED,
            MediaStore.Video.Media.DATE_MODIFIED
        };
        String sortOrder = MediaStore.Video.Media.DATE_ADDED + " DESC";
        String selection = null;
        String[] selectionArgs = null;
        if (bucketId != null) {
            selection = MediaStore.Video.Media.BUCKET_ID + "=?";
            selectionArgs = new String[] { bucketId };
        }
        try (Cursor cursor = resolver.query(VIDEOS_URI, projection, selection, selectionArgs, sortOrder)) {
            if (cursor == null) {
                return entries;
            }
            int idCol = cursor.getColumnIndexOrThrow(MediaStore.Video.Media._ID);
            int nameCol = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.DISPLAY_NAME);
            int bucketIdCol = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.BUCKET_ID);
            int bucketNameCol = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.BUCKET_DISPLAY_NAME);
            int durationCol = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.DURATION);
            int widthCol = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.WIDTH);
            int heightCol = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.HEIGHT);
            int pathCol = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.RELATIVE_PATH);
            int modifiedCol = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.DATE_MODIFIED);

            while (cursor.moveToNext()) {
                long id = cursor.getLong(idCol);
                String name = cursor.getString(nameCol);
                String bucketIdValue = cursor.getString(bucketIdCol);
                String bucketName = cursor.getString(bucketNameCol);
                long duration = cursor.getLong(durationCol);
                int width = cursor.getInt(widthCol);
                int height = cursor.getInt(heightCol);
                String relativePath = cursor.getString(pathCol);
                long modified = cursor.getLong(modifiedCol);
                entries.add(new VideoEntry(
                    id,
                    name,
                    bucketIdValue,
                    bucketName,
                    duration,
                    width,
                    height,
                    relativePath,
                    modified
                ));
            }
        }
        return entries;
    }

    private List<DisplayItem> buildVideoItems(List<VideoEntry> entries) {
        List<DisplayItem> items = new ArrayList<>();
        for (VideoEntry entry : entries) {
            String uri = ContentUris.withAppendedId(VIDEOS_URI, entry.id).toString();
            items.add(new DisplayItem(
                DisplayItem.Type.VIDEO,
                safe(entry.displayName, "Unknown"),
                null,
                0,
                entry.duration,
                entry.width,
                entry.height,
                uri
            ));
        }
        return items;
    }

    private List<DisplayItem> buildFolderItems(List<VideoEntry> entries) {
        Map<String, FolderAggregate> folders = new HashMap<>();
        for (VideoEntry entry : entries) {
            String key = entry.bucketId != null ? entry.bucketId : safe(entry.bucketName, "Unknown");
            String name = safe(entry.bucketName, "Unknown");
            FolderAggregate aggregate = folders.get(key);
            if (aggregate == null) {
                aggregate = new FolderAggregate(key, name);
                folders.put(key, aggregate);
            }
            aggregate.count++;
        }
        List<FolderAggregate> sorted = new ArrayList<>(folders.values());
        Collections.sort(sorted, Comparator.comparing(a -> a.name.toLowerCase(Locale.US)));
        List<DisplayItem> items = new ArrayList<>();
        for (FolderAggregate aggregate : sorted) {
            items.add(new DisplayItem(
                DisplayItem.Type.FOLDER,
                aggregate.name,
                formatCountSubtitle(aggregate.count, 0),
                0,
                aggregate.bucketId
            ));
        }
        return items;
    }

    private List<DisplayItem> buildHierarchyLevelItems(
        List<VideoEntry> entries,
        String currentPath,
        VideoSortMode sortMode,
        VideoSortOrder sortOrder
    ) {
        String normalizedPath = normalizePath(currentPath);
        Map<String, FolderAggregate> folders = new HashMap<>();
        List<VideoEntry> videos = new ArrayList<>();

        for (VideoEntry entry : entries) {
            String path = normalizePath(entry.relativePath);
            if (path.isEmpty()) {
                path = normalizePath(safe(entry.bucketName, "Unknown"));
            }
            if (!path.startsWith(normalizedPath)) {
                continue;
            }
            String remainder = path.substring(normalizedPath.length());
            if (remainder.isEmpty()) {
                videos.add(entry);
                continue;
            }
            int slash = remainder.indexOf('/');
            String childName = slash >= 0 ? remainder.substring(0, slash) : remainder;
            if (childName.isEmpty()) {
                continue;
            }
            String childPath = normalizedPath + childName + "/";
            FolderAggregate aggregate = folders.get(childPath);
            if (aggregate == null) {
                aggregate = new FolderAggregate(childPath, childName);
                folders.put(childPath, aggregate);
            }
            aggregate.count++;
            String childRemainder = remainder.substring(childName.length());
            if (childRemainder.startsWith("/")) {
                childRemainder = childRemainder.substring(1);
            }
            if (childRemainder.isEmpty()) {
                aggregate.directVideoCount++;
            } else {
                int nextSlash = childRemainder.indexOf('/');
                String nextFolder = nextSlash >= 0 ? childRemainder.substring(0, nextSlash) : childRemainder;
                if (!nextFolder.isEmpty()) {
                    aggregate.directSubfolders.add(nextFolder);
                }
            }
        }

        List<FolderAggregate> sortedFolders = new ArrayList<>(folders.values());
        Collections.sort(sortedFolders, Comparator.comparing(a -> a.name.toLowerCase(Locale.US)));

        List<DisplayItem> items = new ArrayList<>();
        for (FolderAggregate aggregate : sortedFolders) {
            items.add(new DisplayItem(
                DisplayItem.Type.HIERARCHY,
                aggregate.name,
                formatCountSubtitle(aggregate.directVideoCount, aggregate.directSubfolders.size()),
                0,
                aggregate.bucketId
            ));
        }
        sortEntries(videos, sortMode, sortOrder);
        for (VideoEntry entry : videos) {
            items.add(buildVideoItem(entry));
        }
        return items;
    }

    private String safe(String value, String fallback) {
        return value == null || value.isEmpty() ? fallback : value;
    }

    private static class FolderAggregate {
        final String name;
        final String bucketId;
        int count;
        int directVideoCount;
        final java.util.Set<String> directSubfolders = new java.util.HashSet<>();

        FolderAggregate(String bucketId, String name) {
            this.name = name;
            this.bucketId = bucketId;
        }
    }

    private static class VideoEntry {
        final long id;
        final String displayName;
        final String bucketId;
        final String bucketName;
        final long duration;
        final int width;
        final int height;
        final String relativePath;
        final long modifiedSeconds;

        VideoEntry(
            long id,
            String displayName,
            String bucketId,
            String bucketName,
            long duration,
            int width,
            int height,
            String relativePath,
            long modifiedSeconds
        ) {
            this.id = id;
            this.displayName = displayName;
            this.bucketId = bucketId;
            this.bucketName = bucketName;
            this.duration = duration;
            this.width = width;
            this.height = height;
            this.relativePath = relativePath;
            this.modifiedSeconds = modifiedSeconds;
        }
    }

    private DisplayItem buildVideoItem(VideoEntry entry) {
        String uri = ContentUris.withAppendedId(VIDEOS_URI, entry.id).toString();
        return new DisplayItem(
            DisplayItem.Type.VIDEO,
            safe(entry.displayName, "Unknown"),
            null,
            0,
            entry.duration,
            entry.width,
            entry.height,
            uri
        );
    }

    private String normalizePath(String path) {
        if (path == null || path.isEmpty()) {
            return "";
        }
        String normalized = path.replace('\\', '/');
        if (!normalized.endsWith("/")) {
            normalized = normalized + "/";
        }
        return normalized;
    }

    private void sortEntries(List<VideoEntry> entries, VideoSortMode sortMode, VideoSortOrder sortOrder) {
        if (entries == null || entries.size() < 2 || sortMode == null) {
            return;
        }
        Comparator<VideoEntry> comparator;
        if (sortMode == VideoSortMode.TITLE) {
            comparator = Comparator.comparing(entry -> safe(entry.displayName, "").toLowerCase(Locale.US));
        } else if (sortMode == VideoSortMode.LENGTH) {
            comparator = Comparator.comparingLong((VideoEntry entry) -> entry.duration);
        } else {
            comparator = Comparator.comparingLong((VideoEntry entry) -> entry.modifiedSeconds);
        }
        boolean asc = sortOrder == VideoSortOrder.ASC;
        Collections.sort(entries, asc ? comparator : comparator.reversed());
    }

    private String formatCountSubtitle(int videoCount, int folderCount) {
        return String.format(Locale.US, "Video %d, Folder %d", videoCount, folderCount);
    }
}
