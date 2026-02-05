package com.nshell.nsplayer.ui

import android.graphics.Color
import android.net.Uri
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.nshell.nsplayer.R
import java.util.Locale

class VideoListAdapter : RecyclerView.Adapter<VideoListAdapter.ViewHolder>() {
    fun interface OnItemClickListener {
        fun onItemClick(item: DisplayItem)
    }

    fun interface OnItemOverflowClickListener {
        fun onOverflowClick(item: DisplayItem)
    }

    fun interface OnSelectionChangedListener {
        fun onSelectionChanged(selectedCount: Int, selectionMode: Boolean)
    }

    private val items = mutableListOf<DisplayItem>()
    private val selectedPositions = mutableSetOf<Int>()
    private var videoDisplayMode = VideoDisplayMode.LIST
    private var clickListener: OnItemClickListener? = null
    private var overflowClickListener: OnItemOverflowClickListener? = null
    private var selectionChangedListener: OnSelectionChangedListener? = null
    private var selectionMode = false

    fun submit(nextItems: List<DisplayItem>?) {
        items.clear()
        if (nextItems != null) {
            items.addAll(nextItems)
        }
        clearSelectionInternal()
        notifyDataSetChanged()
    }

    fun setVideoDisplayMode(mode: VideoDisplayMode?) {
        if (mode != null) {
            videoDisplayMode = mode
        }
    }

    fun setOnItemClickListener(listener: OnItemClickListener?) {
        clickListener = listener
    }

    fun setOnItemOverflowClickListener(listener: OnItemOverflowClickListener?) {
        overflowClickListener = listener
    }

    fun setOnSelectionChangedListener(listener: OnSelectionChangedListener?) {
        selectionChangedListener = listener
    }

    fun isSelectionMode(): Boolean = selectionMode

    fun getSelectedCount(): Int = selectedPositions.size

    fun isAllSelected(): Boolean = items.isNotEmpty() && selectedPositions.size == items.size

    fun getSelectedItems(): List<DisplayItem> {
        if (selectedPositions.isEmpty()) {
            return emptyList()
        }
        return selectedPositions.sorted().mapNotNull { index -> items.getOrNull(index) }
    }

    fun selectAll() {
        selectionMode = true
        selectedPositions.clear()
        for (i in items.indices) {
            selectedPositions.add(i)
        }
        notifySelectionChanged()
        notifyDataSetChanged()
    }

    fun clearSelection() {
        clearSelectionInternal()
        notifyDataSetChanged()
    }

    private fun clearSelectionInternal() {
        selectionMode = false
        selectedPositions.clear()
        notifySelectionChanged()
    }

    private fun toggleSelection(position: Int) {
        if (selectedPositions.contains(position)) {
            selectedPositions.remove(position)
        } else {
            selectedPositions.add(position)
        }
        selectionMode = selectedPositions.isNotEmpty()
        notifySelectionChanged()
        notifyItemChanged(position)
    }

    private fun notifySelectionChanged() {
        selectionChangedListener?.onSelectionChanged(selectedPositions.size, selectionMode)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val layoutId = when (viewType) {
            VIEW_TYPE_VIDEO_LIST -> R.layout.item_video_list
            VIEW_TYPE_VIDEO_TILE -> R.layout.item_video_tile
            VIEW_TYPE_TILE_DEFAULT -> R.layout.item_video_tile
            else -> R.layout.item_row
        }
        val view = LayoutInflater.from(parent.context).inflate(layoutId, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val item = items[position]
        holder.itemView.setOnClickListener {
            if (selectionMode) {
                val adapterPosition = holder.bindingAdapterPosition
                if (adapterPosition != RecyclerView.NO_POSITION) {
                    toggleSelection(adapterPosition)
                }
                return@setOnClickListener
            }
            clickListener?.onItemClick(item)
        }
        holder.itemView.setOnLongClickListener {
            val adapterPosition = holder.bindingAdapterPosition
            if (adapterPosition == RecyclerView.NO_POSITION) {
                return@setOnLongClickListener false
            }
            toggleSelection(adapterPosition)
            true
        }

        if (item.type == DisplayItem.Type.VIDEO) {
            bindVideo(holder, item)
        } else {
            bindDefault(holder, item)
        }

        val selected = selectedPositions.contains(position)
        holder.itemView.setBackgroundColor(
            if (selected) holder.itemView.context.getColor(R.color.selection_bg) else Color.TRANSPARENT
        )
    }

    override fun getItemCount(): Int = items.size

    override fun getItemViewType(position: Int): Int {
        val item = items[position]
        if (item.type == DisplayItem.Type.VIDEO) {
            return if (videoDisplayMode == VideoDisplayMode.TILE) VIEW_TYPE_VIDEO_TILE else VIEW_TYPE_VIDEO_LIST
        }
        return if (videoDisplayMode == VideoDisplayMode.TILE) VIEW_TYPE_TILE_DEFAULT else VIEW_TYPE_DEFAULT
    }

    class ViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        val title: TextView? = itemView.findViewById(R.id.title)
        val subtitle: TextView? = itemView.findViewById(R.id.subtitle)
        val subtitleRow: View? = itemView.findViewById(R.id.subtitleRow)
        val durationText: TextView? = itemView.findViewById(R.id.durationText)
        val resolutionText: TextView? = itemView.findViewById(R.id.resolutionText)
        val subtitleDivider: View? = itemView.findViewById(R.id.subtitleDivider)
        val thumbnail: ImageView? = itemView.findViewById(R.id.thumbnail)
        val folderIcon: ImageView? = itemView.findViewById(R.id.folderIcon)
        val overflowButton: ImageView? = itemView.findViewById(R.id.overflowButton)
        val defaultTitleColor: Int = title?.currentTextColor ?: Color.BLACK
    }

    private fun bindDefault(holder: ViewHolder, item: DisplayItem) {
        holder.title?.text = item.title
        holder.title?.setTextColor(holder.defaultTitleColor)
        val subtitle = item.subtitle
        if (subtitle.isNullOrEmpty()) {
            holder.subtitle?.visibility = View.GONE
        } else {
            holder.subtitle?.text = subtitle
            holder.subtitle?.visibility = View.VISIBLE
        }
        holder.subtitleRow?.visibility = View.GONE

        holder.overflowButton?.setOnClickListener {
            overflowClickListener?.onOverflowClick(item)
        }

        holder.thumbnail?.let { thumbnail ->
            val isFolder = item.type == DisplayItem.Type.FOLDER || item.type == DisplayItem.Type.HIERARCHY
            val iconRes = if (isFolder) R.drawable.ic_folder else R.drawable.ic_video
            thumbnail.setImageResource(iconRes)
            thumbnail.scaleType = if (isFolder) {
                ImageView.ScaleType.FIT_CENTER
            } else {
                ImageView.ScaleType.CENTER_INSIDE
            }
            thumbnail.setPadding(0, 0, 0, 0)
            thumbnail.setColorFilter(thumbnail.context.getColor(R.color.brand_green))
        }

        holder.folderIcon?.visibility = if (
            item.type == DisplayItem.Type.FOLDER || item.type == DisplayItem.Type.HIERARCHY
        ) {
            View.VISIBLE
        } else {
            View.GONE
        }

        if (holder.thumbnail != null) {
            val basePadding = dpToPx(holder.itemView, 8)
            holder.itemView.setPadding(basePadding, basePadding, basePadding, basePadding)
        } else {
            val basePadding = dpToPx(holder.itemView, 12)
            val extra = dpToPx(holder.itemView, item.indentLevel * 12)
            holder.itemView.setPadding(basePadding + extra, basePadding, basePadding, basePadding)
        }
    }

    private fun bindVideo(holder: ViewHolder, item: DisplayItem) {
        holder.title?.text = item.title
        holder.title?.setTextColor(holder.defaultTitleColor)
        bindSubtitle(holder, item)

        holder.overflowButton?.setOnClickListener {
            overflowClickListener?.onOverflowClick(item)
        }

        holder.thumbnail?.let { thumbnail ->
            val uri = item.contentUri
            if (!uri.isNullOrEmpty()) {
                thumbnail.clearColorFilter()
                Glide.with(thumbnail.context)
                    .load(Uri.parse(uri))
                    .centerCrop()
                    .into(thumbnail)
            } else {
                thumbnail.setImageDrawable(null)
            }
        }

        val basePadding = dpToPx(holder.itemView, 8)
        holder.itemView.setPadding(basePadding, basePadding, basePadding, basePadding)
    }

    private fun bindSubtitle(holder: ViewHolder, item: DisplayItem) {
        val duration = formatDuration(item.durationMs)
        val resolution = if (item.width > 0 && item.height > 0) {
            "${item.width}x${item.height}"
        } else {
            ""
        }
        holder.subtitle?.visibility = View.GONE
        holder.subtitleRow?.visibility = View.VISIBLE
        holder.durationText?.text = duration
        holder.resolutionText?.text = resolution
        val hasResolution = resolution.isNotEmpty()
        holder.subtitleDivider?.visibility = if (hasResolution) View.VISIBLE else View.GONE
        holder.resolutionText?.visibility = if (hasResolution) View.VISIBLE else View.GONE
    }

    private fun formatDuration(durationMs: Long): String {
        val totalSeconds = durationMs / 1000
        val seconds = totalSeconds % 60
        val minutes = (totalSeconds / 60) % 60
        val hours = totalSeconds / 3600
        return if (hours > 0) {
            String.format(Locale.US, "%d:%02d:%02d", hours, minutes, seconds)
        } else {
            String.format(Locale.US, "%d:%02d", minutes, seconds)
        }
    }

    companion object {
        private const val VIEW_TYPE_DEFAULT = 0
        private const val VIEW_TYPE_VIDEO_LIST = 1
        private const val VIEW_TYPE_VIDEO_TILE = 2
        private const val VIEW_TYPE_TILE_DEFAULT = 3

        private fun dpToPx(view: View, dp: Int): Int {
            val density = view.resources.displayMetrics.density
            return Math.round(dp * density)
        }
    }
}
