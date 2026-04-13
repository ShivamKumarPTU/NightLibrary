package com.example.nightlibrary.adapter

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.view.ViewGroup
import android.widget.ImageView
import androidx.recyclerview.widget.RecyclerView
import com.example.nightlibrary.R
import com.example.nightlibrary.cache.ThumbnailCache
//mport com.example.nightlibrary.core.cache.ThumbnailCache
import com.example.nightlibrary.core.security.VaultFileManager
import com.example.nightlibrary.entity.MediaEntity
import kotlinx.coroutines.*
import java.io.File

class HorizontalImageAdapter(
    private val items: List<MediaEntity>,
    private val selectedIds: Set<Long>,
    private val onClick: (MediaEntity) -> Unit,
    private val onLongClick: (MediaEntity) -> Unit
) : RecyclerView.Adapter<HorizontalImageAdapter.VH>() {

    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    inner class VH(val image: ImageView) : RecyclerView.ViewHolder(image) {
        var loadJob: Job? = null
        fun release() {
            loadJob?.cancel()
            loadJob = null
            image.setImageDrawable(null)
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val screenWidth = parent.resources.displayMetrics.widthPixels
        val width = (screenWidth * 0.45).toInt()
        val image = ImageView(parent.context).apply {
            layoutParams = ViewGroup.MarginLayoutParams(width, 220).apply {
                setMargins(8, 0, 8, 0)
            }
            scaleType = ImageView.ScaleType.CENTER_CROP
            // FIX 1: Ensure ImageView is clickable so touch events propagate
            isClickable = true
            isFocusable = true
        }
        return VH(image)
    }

    override fun getItemCount() = items.size

    override fun onBindViewHolder(holder: VH, position: Int) {

        val item = items[position]
        val isSelected = selectedIds.contains(item.id)

        holder.release()

        // Selection UI
        if (isSelected) {
            holder.image.setColorFilter(Color.parseColor("#80FF9500"))
            holder.itemView.setBackgroundColor(Color.parseColor("#FF9500"))
        } else {
            holder.image.clearColorFilter()
            holder.itemView.setBackgroundColor(Color.TRANSPARENT)
        }

        // Click listeners
        holder.itemView.setOnClickListener { onClick(item) }
        holder.itemView.setOnLongClickListener {
            onLongClick(item)
            true
        }

        // ✅ STABLE CACHE KEY (IMPORTANT)
        val cacheKey = "img_${item.id}"

        // ✅ 1. INSTANT LOAD FROM CACHE (NO BLINK)
        val cached = ThumbnailCache.get(cacheKey)
        if (cached != null) {
            holder.image.setImageBitmap(cached)

            // ✅ PRELOAD NEXT IMAGE (GALLERY FEEL)
            preloadNext(position)

            return
        }

        // ❌ NO placeholder → prevents blink

        holder.loadJob = scope.launch {

            val bitmap = withContext(Dispatchers.IO) {
                try {
                    val thumbPath = item.thumbnailPath

                    if (!thumbPath.isNullOrEmpty()) {
                        val f = File(thumbPath)
                        if (f.exists()) {
                            BitmapFactory.decodeFile(
                                f.absolutePath,
                                BitmapFactory.Options().apply {
                                    inSampleSize = 2
                                    inPreferredConfig = Bitmap.Config.RGB_565
                                }
                            )
                        } else null
                    } else null

                } catch (e: Exception) {
                    null
                }
            }

            if (bitmap != null) {
                ThumbnailCache.put(cacheKey, bitmap)
                holder.image.setImageBitmap(bitmap)

                // ✅ PRELOAD NEXT AFTER LOAD
                preloadNext(position)
            }
        }
    }
    private fun preloadNext(position: Int) {

        if (position + 1 >= items.size) return

        val next = items[position + 1]
        val cacheKey = "img_${next.id}"

        if (ThumbnailCache.get(cacheKey) != null) return

        scope.launch(Dispatchers.IO) {
            try {
                val thumbPath = next.thumbnailPath

                if (!thumbPath.isNullOrEmpty()) {
                    val f = File(thumbPath)
                    if (f.exists()) {
                        val bitmap = BitmapFactory.decodeFile(
                            f.absolutePath,
                            BitmapFactory.Options().apply {
                                inSampleSize = 2
                                inPreferredConfig = Bitmap.Config.RGB_565
                            }
                        )
                        if (bitmap != null) {
                            ThumbnailCache.put(cacheKey, bitmap)
                        }
                    }
                }
            } catch (_: Exception) {}
        }
    }
    override fun onViewRecycled(holder: VH) {
        holder.release()
        super.onViewRecycled(holder)
    }
}
