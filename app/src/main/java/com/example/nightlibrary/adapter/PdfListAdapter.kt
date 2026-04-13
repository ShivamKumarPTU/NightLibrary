package com.example.nightlibrary.adapter

import android.graphics.Color
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.example.nightlibrary.databinding.ItemMediaListBinding
import com.example.nightlibrary.entity.MediaEntity

class PdfListAdapter(
    private val items: List<MediaEntity>,
    private var selectedIds: Set<Long> = emptySet(),
    private val onClick: (MediaEntity) -> Unit,
    private val onMenuClick: (MediaEntity, View) -> Unit,
    private val onLongClick: (MediaEntity) -> Unit

) : RecyclerView.Adapter<PdfListAdapter.VH>() {

    inner class VH(val binding: ItemMediaListBinding)
        : RecyclerView.ViewHolder(binding.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val binding = ItemMediaListBinding.inflate(
            LayoutInflater.from(parent.context),
            parent,
            false
        )
        return VH(binding)
    }

    override fun getItemCount() = items.size

    override fun onBindViewHolder(holder: VH, position: Int) {
        val item = items[position]
        val isSelected = selectedIds.contains(item.id)

        holder.binding.tvTitle.text = item.fileName
        holder.binding.tvDetails.text = "${item.fileSize / 1024} KB • PDF"

        // Handle Visual Selection State
        holder.itemView.setBackgroundColor(
            if (isSelected) Color.parseColor("#4DFF9500") // Semi-transparent Amber
            else Color.TRANSPARENT
        )

        holder.itemView.setOnClickListener {
            onClick(item)
        }

        // Add Long Click Listener
        holder.itemView.setOnLongClickListener {
            onLongClick(item)
            true
        }


    }
}