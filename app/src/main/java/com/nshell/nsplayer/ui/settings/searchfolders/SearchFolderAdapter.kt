package com.nshell.nsplayer.ui.settings.searchfolders

import android.content.Context
import android.graphics.Typeface
import android.provider.MediaStore
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.CheckBox
import android.widget.TextView
import androidx.core.view.ViewCompat
import androidx.recyclerview.widget.RecyclerView
import com.nshell.nsplayer.R
import com.nshell.nsplayer.data.repository.SearchFolderRepository

class SearchFolderAdapter(
    private val onToggle: (SearchFolderRepository.FolderEntry, Boolean) -> Unit
) : RecyclerView.Adapter<SearchFolderAdapter.ViewHolder>() {
    sealed class Row {
        data class Header(
            val title: String,
            val indent: Int,
            val count: Int
        ) : Row()

        data class Item(
            val entry: SearchFolderRepository.FolderEntry,
            val indent: Int
        ) : Row()
    }

    private val rows = mutableListOf<Row>()
    private val selectedIds = mutableSetOf<String>()

    fun submit(nextRows: List<Row>, selected: Set<String>) {
        rows.clear()
        rows.addAll(nextRows)
        selectedIds.clear()
        selectedIds.addAll(selected)
        notifyDataSetChanged()
    }

    fun updateSelection(selected: Set<String>) {
        selectedIds.clear()
        selectedIds.addAll(selected)
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_search_folder, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val row = rows[position]
        when (row) {
            is Row.Header -> {
                holder.title.text = row.title
                holder.title.setTypeface(holder.title.typeface, Typeface.BOLD)
                if (row.count > 0) {
                    holder.subtitle.text = holder.itemView.context.getString(
                        R.string.search_folders_item_count_only,
                        row.count
                    )
                    holder.subtitle.visibility = View.VISIBLE
                } else {
                    holder.subtitle.text = ""
                    holder.subtitle.visibility = View.GONE
                }
                holder.checkBox.visibility = View.INVISIBLE
                holder.checkBox.setOnCheckedChangeListener(null)
                holder.itemView.setOnClickListener(null)
                holder.itemView.isClickable = false
                applyIndent(holder, row.indent)
            }
            is Row.Item -> {
                val item = row.entry
                val selected = selectedIds.contains(item.bucketId)
                holder.title.text = item.name
                holder.title.setTypeface(holder.title.typeface, Typeface.NORMAL)
                holder.subtitle.text = buildSubtitle(holder.itemView.context, item)
                holder.subtitle.visibility = View.VISIBLE
                holder.checkBox.visibility = View.VISIBLE
                holder.checkBox.setOnCheckedChangeListener(null)
                holder.checkBox.isChecked = selected
                holder.checkBox.setOnCheckedChangeListener { _, isChecked ->
                    onToggle(item, isChecked)
                }
                holder.itemView.isClickable = true
                holder.itemView.setOnClickListener {
                    onToggle(item, !selected)
                }
                applyIndent(holder, row.indent)
            }
        }
    }

    override fun getItemCount(): Int = rows.size

    private fun buildSubtitle(
        context: Context,
        item: SearchFolderRepository.FolderEntry
    ): String {
        val rel = item.relativePath.trimEnd('/').trim()
        val volumeLabel = buildVolumeLabel(context, item.volumeName)
        val path = when {
            rel.isEmpty() -> volumeLabel
            volumeLabel.isEmpty() -> rel
            else -> "$volumeLabel / $rel"
        }
        return if (path.isEmpty()) {
            context.getString(R.string.search_folders_item_count_only, item.count)
        } else {
            context.getString(R.string.search_folders_item_subtitle, path, item.count)
        }
    }

    private fun buildVolumeLabel(context: Context, volumeName: String?): String {
        return if (volumeName.isNullOrEmpty() || volumeName == MediaStore.VOLUME_EXTERNAL_PRIMARY) {
            context.getString(R.string.storage_internal)
        } else {
            context.getString(R.string.storage_external_format, volumeName)
        }
    }

    private fun applyIndent(holder: ViewHolder, indent: Int) {
        val start = holder.baseStart + dpToPx(holder.itemView, INDENT_DP * indent)
        ViewCompat.setPaddingRelative(
            holder.itemView,
            start,
            holder.baseTop,
            holder.baseEnd,
            holder.baseBottom
        )
    }

    class ViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        val title: TextView = itemView.findViewById(R.id.searchFolderTitle)
        val subtitle: TextView = itemView.findViewById(R.id.searchFolderPath)
        val checkBox: CheckBox = itemView.findViewById(R.id.searchFolderCheckBox)
        val baseStart: Int = ViewCompat.getPaddingStart(itemView)
        val baseEnd: Int = ViewCompat.getPaddingEnd(itemView)
        val baseTop: Int = itemView.paddingTop
        val baseBottom: Int = itemView.paddingBottom
    }

    companion object {
        private const val INDENT_DP = 12

        private fun dpToPx(view: View, dp: Int): Int {
            val density = view.resources.displayMetrics.density
            return Math.round(dp * density)
        }
    }
}
