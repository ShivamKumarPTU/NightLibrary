package com.example.nightlibrary.adapter

import android.graphics.BitmapFactory
import android.graphics.Color
import android.view.ViewGroup
import android.widget.ImageView
import androidx.recyclerview.widget.RecyclerView
import com.example.nightlibrary.R
import com.example.nightlibrary.cache.ThumbnailCache
//import com.example.nightlibrary.core.cache.ThumbnailCache
import com.example.nightlibrary.debug.VaultLogger
import com.example.nightlibrary.entity.MediaEntity
import kotlinx.coroutines.*
import java.io.File

class HorizontalVideoAdapter(
    private val items: List<MediaEntity>,
    private val selectedIds: Set<Long>,
    private val onClick: (MediaEntity) -> Unit,
    private val onLongClick: (MediaEntity) -> Unit
) : RecyclerView.Adapter<HorizontalVideoAdapter.VH>() {

    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    inner class VH(val thumbnail: ImageView) : RecyclerView.ViewHolder(thumbnail) {
        var thumbJob: Job? = null
        fun release() {
            thumbJob?.cancel()
            thumbJob = null
            thumbnail.setImageDrawable(null)
        }
    }

    override fun getItemCount() = items.size

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val screenWidth = parent.resources.displayMetrics.widthPixels
        val width = (screenWidth * 0.45).toInt()
        val imageView = ImageView(parent.context).apply {
            layoutParams = ViewGroup.MarginLayoutParams(width, 220).apply {
                setMargins(8, 0, 8, 0)
            }
            scaleType = ImageView.ScaleType.CENTER_CROP
            setBackgroundColor(Color.BLACK)
            isClickable = true
            isFocusable = true
        }
        return VH(imageView)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        val item = items[position]
        val isSelected = selectedIds.contains(item.id)

        holder.release()

        if (isSelected)
            holder.thumbnail.setColorFilter(Color.parseColor("#80FF9500"))
        else
            holder.thumbnail.clearColorFilter()

        holder.thumbnail.setOnClickListener { onClick(item) }
        holder.thumbnail.setOnLongClickListener {
            onLongClick(item)
            true
        }

        val cacheKey = "video_thumb_h_${item.id}"
        ThumbnailCache.get(cacheKey)?.let {
            holder.thumbnail.setImageBitmap(it)
            return
        }

        holder.thumbnail.setImageResource(R.drawable.ic_media_temp)

        holder.thumbJob = scope.launch {
            val bitmap = withContext(Dispatchers.IO) {
                try {
                    val path = item.thumbnailPath
                    if (!path.isNullOrEmpty()) {
                        val f = File(path)
                        if (f.exists()) {
                            // Thumbnails are stored as plain JPEGs in cacheDir/vault_thumbs
                            return@withContext BitmapFactory.decodeFile(f.absolutePath)
                        }
                    }
                    null
                } catch (e: Exception) {
                    VaultLogger.d("HorizVideoAdapter thumb: ${e.message}")
                    null
                }
            }
            if (bitmap != null) {
                ThumbnailCache.put(cacheKey, bitmap)
                holder.thumbnail.setImageBitmap(bitmap)
            }
        }
    }

    override fun onViewRecycled(holder: VH) {
        holder.release()
        super.onViewRecycled(holder)
    }
}
