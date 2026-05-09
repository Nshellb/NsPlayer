package com.nshell.nsplayer.ui.main

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.nshell.nsplayer.R
import android.net.Uri

class SearchPreviewAdapter(
    private val onItemClick: (DisplayItem) -> Unit
) : ListAdapter<DisplayItem, SearchPreviewAdapter.ViewHolder>(DIFF_CALLBACK) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_search_preview, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val item = getItem(position)
        holder.title.text = item.title
        val subtitle = item.subtitle
        if (subtitle.isNullOrBlank()) {
            holder.subtitle.visibility = View.GONE
        } else {
            holder.subtitle.text = subtitle
            holder.subtitle.visibility = View.VISIBLE
        }

        val uri = item.contentUri
        if (!uri.isNullOrBlank()) {
            holder.thumbnail.scaleType = ImageView.ScaleType.CENTER_CROP
            holder.thumbnail.clearColorFilter()
            Glide.with(holder.thumbnail)
                .load(Uri.parse(uri))
                .override(128, 72)
                .centerCrop()
                .into(holder.thumbnail)
        } else {
            Glide.with(holder.thumbnail).clear(holder.thumbnail)
            holder.thumbnail.setImageResource(R.drawable.ic_video)
            holder.thumbnail.scaleType = ImageView.ScaleType.CENTER_INSIDE
            holder.thumbnail.setColorFilter(holder.itemView.context.getColor(R.color.brand_green))
        }
        holder.itemView.setOnClickListener { onItemClick(item) }
    }

    override fun onViewRecycled(holder: ViewHolder) {
        Glide.with(holder.thumbnail).clear(holder.thumbnail)
        super.onViewRecycled(holder)
    }

    class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val thumbnail: ImageView = view.findViewById(R.id.searchPreviewThumbnail)
        val title: TextView = view.findViewById(R.id.searchPreviewTitle)
        val subtitle: TextView = view.findViewById(R.id.searchPreviewSubtitle)
    }

    companion object {
        private val DIFF_CALLBACK = object : DiffUtil.ItemCallback<DisplayItem>() {
            override fun areItemsTheSame(oldItem: DisplayItem, newItem: DisplayItem): Boolean {
                return oldItem.contentUri == newItem.contentUri && oldItem.title == newItem.title
            }

            override fun areContentsTheSame(oldItem: DisplayItem, newItem: DisplayItem): Boolean {
                return oldItem == newItem
            }
        }
    }
}
