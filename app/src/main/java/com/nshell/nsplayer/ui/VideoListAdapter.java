package com.nshell.nsplayer.ui;

import android.graphics.Color;
import android.net.Uri;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.bumptech.glide.Glide;
import com.nshell.nsplayer.R;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

public class VideoListAdapter extends RecyclerView.Adapter<VideoListAdapter.ViewHolder> {
    public interface OnItemClickListener {
        void onItemClick(DisplayItem item);
    }

    public interface OnItemOverflowClickListener {
        void onOverflowClick(DisplayItem item);
    }

    public interface OnSelectionChangedListener {
        void onSelectionChanged(int selectedCount, boolean selectionMode);
    }

    private static final int VIEW_TYPE_DEFAULT = 0;
    private static final int VIEW_TYPE_VIDEO_LIST = 1;
    private static final int VIEW_TYPE_VIDEO_TILE = 2;
    private static final int VIEW_TYPE_TILE_DEFAULT = 3;

    private final List<DisplayItem> items = new ArrayList<>();
    private final Set<Integer> selectedPositions = new HashSet<>();
    private VideoDisplayMode videoDisplayMode = VideoDisplayMode.LIST;
    private OnItemClickListener clickListener;
    private OnItemOverflowClickListener overflowClickListener;
    private OnSelectionChangedListener selectionChangedListener;
    private boolean selectionMode = false;

    public void submit(List<DisplayItem> nextItems) {
        items.clear();
        if (nextItems != null) {
            items.addAll(nextItems);
        }
        clearSelectionInternal();
        notifyDataSetChanged();
    }

    public void setVideoDisplayMode(VideoDisplayMode mode) {
        if (mode != null) {
            this.videoDisplayMode = mode;
        }
    }

    public void setOnItemClickListener(OnItemClickListener listener) {
        this.clickListener = listener;
    }

    public void setOnItemOverflowClickListener(OnItemOverflowClickListener listener) {
        this.overflowClickListener = listener;
    }

    public void setOnSelectionChangedListener(OnSelectionChangedListener listener) {
        this.selectionChangedListener = listener;
    }

    public boolean isSelectionMode() {
        return selectionMode;
    }

    public int getSelectedCount() {
        return selectedPositions.size();
    }

    public boolean isAllSelected() {
        return !items.isEmpty() && selectedPositions.size() == items.size();
    }

    public void selectAll() {
        selectionMode = true;
        selectedPositions.clear();
        for (int i = 0; i < items.size(); i++) {
            selectedPositions.add(i);
        }
        notifySelectionChanged();
        notifyDataSetChanged();
    }

    public void clearSelection() {
        clearSelectionInternal();
        notifyDataSetChanged();
    }

    private void clearSelectionInternal() {
        selectionMode = false;
        selectedPositions.clear();
        notifySelectionChanged();
    }

    private void toggleSelection(int position) {
        if (selectedPositions.contains(position)) {
            selectedPositions.remove(position);
        } else {
            selectedPositions.add(position);
        }
        selectionMode = !selectedPositions.isEmpty();
        notifySelectionChanged();
        notifyItemChanged(position);
    }

    private void notifySelectionChanged() {
        if (selectionChangedListener != null) {
            selectionChangedListener.onSelectionChanged(selectedPositions.size(), selectionMode);
        }
    }

    @NonNull
    @Override
    public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        int layoutId;
        if (viewType == VIEW_TYPE_VIDEO_LIST) {
            layoutId = R.layout.item_video_list;
        } else if (viewType == VIEW_TYPE_VIDEO_TILE) {
            layoutId = R.layout.item_video_tile;
        } else if (viewType == VIEW_TYPE_TILE_DEFAULT) {
            layoutId = R.layout.item_video_tile;
        } else {
            layoutId = R.layout.item_row;
        }
        View view = LayoutInflater.from(parent.getContext()).inflate(layoutId, parent, false);
        return new ViewHolder(view);
    }

    @Override
    public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
        DisplayItem item = items.get(position);
        holder.itemView.setOnClickListener(v -> {
            if (selectionMode) {
                int adapterPosition = holder.getBindingAdapterPosition();
                if (adapterPosition != RecyclerView.NO_POSITION) {
                    toggleSelection(adapterPosition);
                }
                return;
            }
            if (clickListener != null) {
                clickListener.onItemClick(item);
            }
        });
        holder.itemView.setOnLongClickListener(v -> {
            int adapterPosition = holder.getBindingAdapterPosition();
            if (adapterPosition == RecyclerView.NO_POSITION) {
                return false;
            }
            toggleSelection(adapterPosition);
            return true;
        });

        if (item.getType() == DisplayItem.Type.VIDEO) {
            bindVideo(holder, item);
        } else {
            bindDefault(holder, item);
        }

        boolean selected = selectedPositions.contains(position);
        holder.itemView.setBackgroundColor(selected
            ? holder.itemView.getContext().getColor(R.color.selection_bg)
            : Color.TRANSPARENT);
    }

    @Override
    public int getItemCount() {
        return items.size();
    }

    @Override
    public int getItemViewType(int position) {
        DisplayItem item = items.get(position);
        if (item.getType() == DisplayItem.Type.VIDEO) {
            return videoDisplayMode == VideoDisplayMode.TILE
                ? VIEW_TYPE_VIDEO_TILE
                : VIEW_TYPE_VIDEO_LIST;
        }
        if (videoDisplayMode == VideoDisplayMode.TILE) {
            return VIEW_TYPE_TILE_DEFAULT;
        }
        return VIEW_TYPE_DEFAULT;
    }

    static class ViewHolder extends RecyclerView.ViewHolder {
        final TextView title;
        final TextView subtitle;
        final ImageView thumbnail;
        final ImageView folderIcon;
        final ImageView overflowButton;
        final int defaultTitleColor;

        ViewHolder(@NonNull View itemView) {
            super(itemView);
            title = itemView.findViewById(R.id.title);
            subtitle = itemView.findViewById(R.id.subtitle);
            thumbnail = itemView.findViewById(R.id.thumbnail);
            folderIcon = itemView.findViewById(R.id.folderIcon);
            overflowButton = itemView.findViewById(R.id.overflowButton);
            defaultTitleColor = title != null ? title.getCurrentTextColor() : Color.BLACK;
        }
    }

    private void bindDefault(ViewHolder holder, DisplayItem item) {
        holder.title.setText(item.getTitle());
        holder.title.setTextColor(holder.defaultTitleColor);
        String subtitle = item.getSubtitle();
        if (subtitle == null || subtitle.isEmpty()) {
            holder.subtitle.setVisibility(View.GONE);
        } else {
            holder.subtitle.setText(subtitle);
            holder.subtitle.setVisibility(View.VISIBLE);
        }

        if (holder.overflowButton != null) {
            holder.overflowButton.setOnClickListener(v -> {
                if (overflowClickListener != null) {
                    overflowClickListener.onOverflowClick(item);
                }
            });
        }

        if (holder.thumbnail != null) {
            holder.thumbnail.setImageResource(android.R.drawable.ic_menu_agenda);
            holder.thumbnail.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
            holder.thumbnail.setColorFilter(holder.thumbnail.getContext().getColor(R.color.brand_green));
        }

        if (holder.folderIcon != null) {
            boolean showIcon = item.getType() == DisplayItem.Type.FOLDER
                || item.getType() == DisplayItem.Type.HIERARCHY;
            holder.folderIcon.setVisibility(showIcon ? View.VISIBLE : View.GONE);
        }

        if (holder.thumbnail != null) {
            int basePadding = dpToPx(holder.itemView, 8);
            holder.itemView.setPadding(basePadding, basePadding, basePadding, basePadding);
        } else {
            int basePadding = dpToPx(holder.itemView, 12);
            int extra = dpToPx(holder.itemView, item.getIndentLevel() * 12);
            holder.itemView.setPadding(basePadding + extra, basePadding, basePadding, basePadding);
        }
    }

    private void bindVideo(ViewHolder holder, DisplayItem item) {
        holder.title.setText(item.getTitle());
        holder.title.setTextColor(holder.defaultTitleColor);
        String details = formatDetails(item);
        holder.subtitle.setText(details);
        holder.subtitle.setVisibility(View.VISIBLE);

        if (holder.overflowButton != null) {
            holder.overflowButton.setOnClickListener(v -> {
                if (overflowClickListener != null) {
                    overflowClickListener.onOverflowClick(item);
                }
            });
        }

        if (holder.thumbnail != null) {
            String uri = item.getContentUri();
            if (uri != null && !uri.isEmpty()) {
                holder.thumbnail.clearColorFilter();
                Glide.with(holder.thumbnail.getContext())
                    .load(Uri.parse(uri))
                    .centerCrop()
                    .into(holder.thumbnail);
            } else {
                holder.thumbnail.setImageDrawable(null);
            }
        }

        int basePadding = dpToPx(holder.itemView, 8);
        holder.itemView.setPadding(basePadding, basePadding, basePadding, basePadding);
    }

    private String formatDetails(DisplayItem item) {
        String duration = formatDuration(item.getDurationMs());
        String resolution = "";
        if (item.getWidth() > 0 && item.getHeight() > 0) {
            resolution = item.getWidth() + "x" + item.getHeight();
        }
        if (!resolution.isEmpty()) {
            return String.format(Locale.US, "%s • %s", duration, resolution);
        }
        return duration;
    }

    private String formatDuration(long durationMs) {
        long totalSeconds = durationMs / 1000;
        long seconds = totalSeconds % 60;
        long minutes = (totalSeconds / 60) % 60;
        long hours = totalSeconds / 3600;
        if (hours > 0) {
            return String.format(Locale.US, "%d:%02d:%02d", hours, minutes, seconds);
        }
        return String.format(Locale.US, "%d:%02d", minutes, seconds);
    }

    private static int dpToPx(View view, int dp) {
        float density = view.getResources().getDisplayMetrics().density;
        return Math.round(dp * density);
    }

}
