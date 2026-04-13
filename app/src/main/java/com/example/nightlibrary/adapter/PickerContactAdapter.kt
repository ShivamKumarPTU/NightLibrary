package com.example.nightlibrary.adapter

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.example.nightlibrary.PhoneContact
import com.example.nightlibrary.databinding.ItemPickerContactBinding

class PickerContactAdapter(
    private val onSelectionChanged: () -> Unit
) : ListAdapter<PhoneContact, PickerContactAdapter.VH>(Diff()) {

    val selectedIds = mutableSetOf<Long>()

    fun selectAll() {
        selectedIds.clear()
        selectedIds.addAll(currentList.map { it.id })
        notifyDataSetChanged()
        onSelectionChanged()
    }

    fun clearSelection() {
        selectedIds.clear()
        notifyDataSetChanged()
        onSelectionChanged()
    }

    fun getSelectedContacts(): List<PhoneContact> {
        return currentList.filter { selectedIds.contains(it.id) }
    }

    inner class VH(private val binding: ItemPickerContactBinding) :
        RecyclerView.ViewHolder(binding.root) {

        fun bind(contact: PhoneContact) {
            binding.tvName.text = contact.name
            binding.tvPhone.text = contact.phone
            binding.tvInitial.text = contact.name.firstOrNull()?.uppercase() ?: "?"
            binding.checkbox.isChecked = selectedIds.contains(contact.id)

            val toggleSelection = {
                if (selectedIds.contains(contact.id)) {
                    selectedIds.remove(contact.id)
                } else {
                    selectedIds.add(contact.id)
                }
                binding.checkbox.isChecked = selectedIds.contains(contact.id)
                onSelectionChanged()
            }

            itemView.setOnClickListener { toggleSelection() }
            binding.checkbox.setOnClickListener { toggleSelection() }
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        return VH(
            ItemPickerContactBinding.inflate(
                LayoutInflater.from(parent.context), parent, false
            )
        )
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        holder.bind(getItem(position))
    }

    class Diff : DiffUtil.ItemCallback<PhoneContact>() {
        override fun areItemsTheSame(a: PhoneContact, b: PhoneContact) = a.id == b.id
        override fun areContentsTheSame(a: PhoneContact, b: PhoneContact) = a == b
    }
}