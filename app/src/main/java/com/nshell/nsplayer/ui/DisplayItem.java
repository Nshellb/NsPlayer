package com.nshell.nsplayer.ui;

public class DisplayItem {
    public enum Type {
        FOLDER,
        HIERARCHY,
        VIDEO
    }

    private final Type type;
    private final String title;
    private final String subtitle;
    private final int indentLevel;
    private final String bucketId;
    private final long durationMs;
    private final int width;
    private final int height;
    private final String contentUri;

    public DisplayItem(Type type, String title, String subtitle, int indentLevel) {
        this(type, title, subtitle, indentLevel, null, 0L, 0, 0, null);
    }

    public DisplayItem(Type type, String title, String subtitle, int indentLevel, String bucketId) {
        this(type, title, subtitle, indentLevel, bucketId, 0L, 0, 0, null);
    }

    public DisplayItem(
        Type type,
        String title,
        String subtitle,
        int indentLevel,
        long durationMs,
        int width,
        int height,
        String contentUri
    ) {
        this(type, title, subtitle, indentLevel, null, durationMs, width, height, contentUri);
    }

    private DisplayItem(
        Type type,
        String title,
        String subtitle,
        int indentLevel,
        String bucketId,
        long durationMs,
        int width,
        int height,
        String contentUri
    ) {
        this.type = type;
        this.title = title;
        this.subtitle = subtitle;
        this.indentLevel = indentLevel;
        this.bucketId = bucketId;
        this.durationMs = durationMs;
        this.width = width;
        this.height = height;
        this.contentUri = contentUri;
    }

    public Type getType() {
        return type;
    }

    public String getTitle() {
        return title;
    }

    public String getSubtitle() {
        return subtitle;
    }

    public int getIndentLevel() {
        return indentLevel;
    }

    public String getBucketId() {
        return bucketId;
    }

    public long getDurationMs() {
        return durationMs;
    }

    public int getWidth() {
        return width;
    }

    public int getHeight() {
        return height;
    }

    public String getContentUri() {
        return contentUri;
    }
}
