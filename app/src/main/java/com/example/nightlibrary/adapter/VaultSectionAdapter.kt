package com.example.nightlibrary.adapter

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.annotation.OptIn
import androidx.compose.ui.graphics.LinearGradientShader
import androidx.media3.common.util.UnstableApi
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.example.nightlibrary.R
import com.example.nightlibrary.databinding.ItemSectionHorizontalBinding
import com.example.nightlibrary.databinding.ItemSectionVerticalBinding
import com.example.nightlibrary.entity.MediaEntity
import com.example.nightlibrary.model.VaultSection
import com.example.nightlibrary.security.EncryptedThumbnailLoader
import com.example.nightlibrary.security.VaultCryptoEngine
import java.io.File

class VaultSectionAdapter(
    private val onClick: (MediaEntity) -> Unit,
    private val onMenuClick: (View, MediaEntity) -> Unit,
    private val onLongClick: (MediaEntity) -> Unit
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    private val sections = mutableListOf<VaultSection>()
    private var selectedIds = setOf<Long>()


    fun updateSelectedItems(selectedIds: Set<Long>) {
        this.selectedIds = selectedIds
        notifyDataSetChanged()
    }
    fun updateSections(newSections: List<VaultSection>) {
        sections.clear()
        sections.addAll(newSections)
        notifyDataSetChanged()
    }
    fun submitSections(newSections: List<VaultSection>) {
        sections.clear()
        sections.addAll(newSections)
        notifyDataSetChanged()
    }

    override fun getItemCount() = sections.size

    override fun getItemViewType(position: Int) =
        when (sections[position]) {
            is VaultSection.PhotoSection -> 1
            is VaultSection.VideoSection -> 2
            is VaultSection.AudioSection -> 3
            is VaultSection.PdfSection -> 4
        }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        return when (viewType) {
            1 -> PhotoVH(ItemSectionHorizontalBinding.inflate(inflater, parent, false))
            2 -> VideoVH(ItemSectionHorizontalBinding.inflate(inflater, parent, false))
            3 -> AudioVH(ItemSectionVerticalBinding.inflate(inflater, parent, false))
            else -> PdfVH(ItemSectionVerticalBinding.inflate(inflater, parent, false))
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        when (val section = sections[position]) {
            is VaultSection.PhotoSection -> {
                (holder as PhotoVH).bind(section)
                // FIX: Show selection indicator if needed
                holder.itemView.isSelected = selectedIds.contains(section.items.firstOrNull()?.id)

            }

            is VaultSection.VideoSection -> {
                (holder as VideoVH).bind(section)

                // FIX: Show selection indicator if needed
                holder.itemView.isSelected = selectedIds.contains(section.items.firstOrNull()?.id)
            }

            is VaultSection.AudioSection -> {
                (holder as AudioVH).bind(section)

                // FIX: Show selection indicator if needed
                holder.itemView.isSelected = selectedIds.contains(section.items.firstOrNull()?.id)
            }

            is VaultSection.PdfSection -> {
                (holder as PdfVH).bind(section)
                // FIX: Show selection indicator if needed
                holder.itemView.isSelected = selectedIds.contains(section.items.firstOrNull()?.id)

            }
        }
    }

    inner class PhotoVH(private val b: ItemSectionHorizontalBinding) :
        RecyclerView.ViewHolder(b.root) {

        fun bind(section: VaultSection.PhotoSection) {
            b.tvSectionTitle.text = "Photos"
            b.recyclerHorizontal.apply {

                layoutManager = LinearLayoutManager(
                    context,
                    RecyclerView.HORIZONTAL,
                    false
                )

                adapter = HorizontalImageAdapter(
                    section.items,
                    selectedIds,
                    onClick,
                    onLongClick
                )

                setHasFixedSize(true)
                isNestedScrollingEnabled = true
                overScrollMode = RecyclerView.OVER_SCROLL_NEVER

                // Prevent ViewPager2 from stealing swipe
                setOnTouchListener { v, event ->
                    when (event.action) {
                        android.view.MotionEvent.ACTION_DOWN -> {
                            v.parent.parent.requestDisallowInterceptTouchEvent(true)
                        }
                        android.view.MotionEvent.ACTION_UP,
                        android.view.MotionEvent.ACTION_CANCEL -> {
                            v.parent.parent.requestDisallowInterceptTouchEvent(false)
                        }
                    }
                    false
                }
            }
        }
    }
    inner class VideoVH(private val b: ItemSectionHorizontalBinding) :
        RecyclerView.ViewHolder(b.root) {

        @OptIn(UnstableApi::class)
        fun bind(section: VaultSection.VideoSection) {

            b.tvSectionTitle.text = "Videos"

            b.recyclerHorizontal.apply {

                layoutManager = LinearLayoutManager(
                    context,
                    RecyclerView.HORIZONTAL,
                    false
                )

                adapter = HorizontalVideoAdapter(
                    items = section.items,
                    selectedIds = selectedIds,
                    onClick = onClick,
                    onLongClick = onLongClick
                )

                setHasFixedSize(true)
                isNestedScrollingEnabled = true
                overScrollMode = RecyclerView.OVER_SCROLL_NEVER

                // Prevent ViewPager swipe conflict
                setOnTouchListener { v, event ->
                    when (event.action) {
                        android.view.MotionEvent.ACTION_DOWN ->
                            v.parent.parent.requestDisallowInterceptTouchEvent(true)

                        android.view.MotionEvent.ACTION_UP,
                        android.view.MotionEvent.ACTION_CANCEL ->
                            v.parent.parent.requestDisallowInterceptTouchEvent(false)
                    }
                    false
                }
            }
        }
    }

    inner class AudioVH(private val b: ItemSectionVerticalBinding) :
        RecyclerView.ViewHolder(b.root) {

        fun bind(section: VaultSection.AudioSection) {
            b.tvSectionTitle.text = "Audio"

            b.recyclerVertical.layoutManager =
                LinearLayoutManager(b.root.context)

            b.recyclerVertical.adapter = PdfListAdapter(
                items = section.items,
                selectedIds = selectedIds, // Pass the set from the parent adapter
                onClick = onClick,
                onMenuClick = { item, v -> onMenuClick(v, item) },
                onLongClick = onLongClick // Pass the long click from the parent adapter
            )
        }
    }

    inner class PdfVH(private val b: ItemSectionVerticalBinding) : RecyclerView.ViewHolder(b.root) {

        fun bind(section: VaultSection.PdfSection) {
            b.tvSectionTitle.text = "Documents"

            b.recyclerVertical.layoutManager =
                LinearLayoutManager(b.root.context)

            b.recyclerVertical.adapter = PdfListAdapter(
                items = section.items,
                selectedIds = selectedIds, // Pass the set from the parent adapter
                onClick = onClick,
                onMenuClick = { item, v -> onMenuClick(v, item) },
                onLongClick = onLongClick // Pass the long click from the parent adapter
            )
        }
    }

}