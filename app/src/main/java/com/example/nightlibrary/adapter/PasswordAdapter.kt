package com.example.nightlibrary.adapter

import android.graphics.Color
import android.text.method.HideReturnsTransformationMethod
import android.text.method.PasswordTransformationMethod
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.example.nightlibrary.core.security.PasswordCryptoManager
import com.example.nightlibrary.databinding.ItemPasswordBinding
import com.example.nightlibrary.entity.PasswordEntity

class PasswordAdapter(
    private val cryptoManager: PasswordCryptoManager,
    private val onMenuClickListener: (password: PasswordEntity, anchorView: View) -> Unit,
    // ✅ NEW: Selection callbacks
    private val onItemClick: (PasswordEntity) -> Unit = {},
    private val onItemLongClick: (PasswordEntity) -> Unit = {}
) : ListAdapter<PasswordEntity, PasswordAdapter.PasswordViewHolder>(PasswordDiffCallback()) {

    // ✅ NEW: Selection tracking
    private var selectedIds: Set<Long> = emptySet()
    val isSelectionMode: Boolean get() = selectedIds.isNotEmpty()

    fun updateSelectedItems(ids: Set<Long>) {
        val changed = selectedIds != ids
        selectedIds = ids
        if (changed) notifyDataSetChanged()
    }

    inner class PasswordViewHolder(private val binding: ItemPasswordBinding) :
        RecyclerView.ViewHolder(binding.root) {

        fun bind(password: PasswordEntity) {
            val decryptedPassword = try {
                cryptoManager.decrypt(password.encryptedPassword)
            } catch (e: Exception) {
                "Error Decrypting"
            }
            binding.itemSecret.setText(decryptedPassword)
            binding.itemTitle.text = password.serviceName
            binding.itemSubtitle.text = password.username
            binding.itemDescription.text = password.notes ?: ""

            // ✅ NEW: Selection highlight
            val isSelected = selectedIds.contains(password.id)
            itemView.setBackgroundColor(
                if (isSelected) Color.parseColor("#4DFF9500") else Color.TRANSPARENT
            )

            // ✅ NEW: Hide menu + visibility toggle in selection mode
            binding.passwordMoreButton.visibility =
                if (isSelectionMode) View.INVISIBLE else View.VISIBLE
            binding.passwordVisibilityToggle.visibility =
                if (isSelectionMode) View.INVISIBLE else View.VISIBLE

            binding.passwordVisibilityToggle.setOnClickListener {
                if (!isSelectionMode) {
                    val isCurrentlyVisible = binding.passwordVisibilityToggle.isSelected
                    if (isCurrentlyVisible) {
                        binding.itemSecret.transformationMethod =
                            PasswordTransformationMethod.getInstance()
                        binding.passwordVisibilityToggle.isSelected = false
                    } else {
                        binding.itemSecret.transformationMethod =
                            HideReturnsTransformationMethod.getInstance()
                        binding.passwordVisibilityToggle.isSelected = true
                    }
                }
            }

            binding.passwordMoreButton.setOnClickListener {
                if (!isSelectionMode) {
                    onMenuClickListener(password, it)
                }
            }

            // ✅ NEW: Item click — toggles selection in selection mode
            itemView.setOnClickListener {
                onItemClick(password)
            }

            // ✅ NEW: Long press — enters selection mode
            itemView.setOnLongClickListener {
                onItemLongClick(password)
                true
            }
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): PasswordViewHolder {
        val binding =
            ItemPasswordBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return PasswordViewHolder(binding)
    }

    override fun onBindViewHolder(holder: PasswordViewHolder, position: Int) {
        holder.bind(getItem(position))
    }
}

class PasswordDiffCallback : DiffUtil.ItemCallback<PasswordEntity>() {
    override fun areItemsTheSame(oldItem: PasswordEntity, newItem: PasswordEntity): Boolean {
        return oldItem.id == newItem.id
    }

    override fun areContentsTheSame(oldItem: PasswordEntity, newItem: PasswordEntity): Boolean {
        return oldItem == newItem
    }
}