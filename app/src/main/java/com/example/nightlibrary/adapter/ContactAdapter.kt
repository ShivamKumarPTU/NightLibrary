package com.example.nightlibrary.adapter

import android.graphics.Color
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.example.nightlibrary.databinding.ItemContactBinding
import com.example.nightlibrary.entity.ContactEntity

class ContactAdapter(
    private val onMenuClickListener: (contact: ContactEntity, anchorView: View) -> Unit,
    // ✅ NEW: Selection callbacks
    private val onItemClick: (ContactEntity) -> Unit = {},
    private val onItemLongClick: (ContactEntity) -> Unit = {}
) : ListAdapter<ContactEntity, ContactAdapter.ContactViewHolder>(ContactDiffCallback()) {

    // ✅ NEW: Selection tracking
    private var selectedIds: Set<Long> = emptySet()
    val isSelectionMode: Boolean get() = selectedIds.isNotEmpty()

    fun updateSelectedItems(ids: Set<Long>) {
        val changed = selectedIds != ids
        selectedIds = ids
        if (changed) notifyDataSetChanged()
    }

    inner class ContactViewHolder(private val binding: ItemContactBinding) :
        RecyclerView.ViewHolder(binding.root) {

        fun bind(contact: ContactEntity) {
            binding.contactName.text = contact.name
            binding.contactPhoneNumber.text = contact.phone
            binding.contactDescription.text = contact.notes ?: ""

            binding.contactInitialText.text = if (contact.name.isNotBlank()) {
                contact.name.first().uppercase()
            } else {
                "?"
            }

            // ✅ NEW: Selection highlight
            val isSelected = selectedIds.contains(contact.id)
            itemView.setBackgroundColor(
                if (isSelected) Color.parseColor("#4DFF9500") else Color.TRANSPARENT
            )

            // ✅ NEW: Hide menu button in selection mode
            binding.contactMoreButton.visibility =
                if (isSelectionMode) View.INVISIBLE else View.VISIBLE

            // ✅ UPDATED: Menu button only works outside selection mode
            binding.contactMoreButton.setOnClickListener {
                if (!isSelectionMode) {
                    onMenuClickListener(contact, it)
                }
            }

            // ✅ NEW: Item click — toggles selection in selection mode
            itemView.setOnClickListener {
                onItemClick(contact)
            }

            // ✅ NEW: Long press — enters selection mode
            itemView.setOnLongClickListener {
                onItemLongClick(contact)
                true
            }
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ContactViewHolder {
        val binding =
            ItemContactBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return ContactViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ContactViewHolder, position: Int) {
        holder.bind(getItem(position))
    }
}

class ContactDiffCallback : DiffUtil.ItemCallback<ContactEntity>() {
    override fun areItemsTheSame(oldItem: ContactEntity, newItem: ContactEntity): Boolean {
        return oldItem.id == newItem.id
    }

    override fun areContentsTheSame(oldItem: ContactEntity, newItem: ContactEntity): Boolean {
        return oldItem == newItem
    }
}