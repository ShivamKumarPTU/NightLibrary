package com.example.nightlibrary.adapter

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.example.nightlibrary.databinding.ItemQualityBinding
import com.example.nightlibrary.entity.QualityItem

class QualityAdapter(
    private val items: List<QualityItem>,
    private val onClick: (QualityItem) -> Unit
) : RecyclerView.Adapter<QualityAdapter.VH>() {

    inner class VH(val binding: ItemQualityBinding) :
        RecyclerView.ViewHolder(binding.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {

        val binding = ItemQualityBinding.inflate(
            LayoutInflater.from(parent.context),
            parent,
            false
        )

        return VH(binding)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {

        val item = items[position]

        holder.binding.tvQuality.text = item.quality
        holder.binding.tvSize.text = item.size

        holder.itemView.setOnClickListener {
            onClick(item)
        }
    }

    override fun getItemCount() = items.size
}