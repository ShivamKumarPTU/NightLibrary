package com.example.nightlibrary.adapter

import android.content.Intent
import android.graphics.BitmapFactory
import android.graphics.Color
import android.text.format.Formatter
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.example.nightlibrary.R
import com.example.nightlibrary.cache.ThumbnailCache
//import com.example.nightlibrary.core.cache.ThumbnailCache
import com.example.nightlibrary.databinding.ItemMediaListBinding
import com.example.nightlibrary.databinding.ItemMediaPhotoBinding
import com.example.nightlibrary.databinding.ItemMediaProgressBinding
import com.example.nightlibrary.databinding.ItemMediaVideoLiveBinding
import com.example.nightlibrary.entity.MediaEntity
import com.example.nightlibrary.manager.DownloadQueueManager
import com.example.nightlibrary.securefileactivity.SecureVideoActivity
import com.example.nightlibrary.worker.VideoPlayerPool
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

class VaultListAdapter(
    private val onItemClick: (MediaEntity) -> Unit,
    private val onLongClick: (MediaEntity) -> Unit,
    private val onMenuClick: (MediaEntity, View) -> Unit,
    private val onCancelClick: (MediaEntity) -> Unit,
    private var isDashboardMode: Boolean = false
) : ListAdapter<MediaEntity, RecyclerView.ViewHolder>(Diff()) {

    private var selectedIds: Set<Long> = emptySet()
    private val adapterScope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    companion object {
        const val TYPE_PROGRESS = 0
        const val TYPE_PHOTO    = 1
        const val TYPE_VIDEO    = 2
        const val TYPE_LIST     = 3
    }

    override fun getItemViewType(position: Int): Int {
        val item = getItem(position) ?: return TYPE_LIST
        if (!item.isCompleted) return TYPE_PROGRESS
        
        return when (item.fileType) {
            "image" -> TYPE_PHOTO
            "video" -> TYPE_VIDEO
            else    -> TYPE_LIST
        }
    }

    fun updateSelectedItems(newIds: Set<Long>) { selectedIds = newIds; notifyDataSetChanged() }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inf = LayoutInflater.from(parent.context)
        return when (viewType) {
            TYPE_PHOTO    -> PhotoVH(ItemMediaPhotoBinding.inflate(inf, parent, false))
            TYPE_VIDEO    -> VideoVH(ItemMediaVideoLiveBinding.inflate(inf, parent, false))
            TYPE_PROGRESS -> ProgressVH(ItemMediaProgressBinding.inflate(inf, parent, false))
            else          -> ListVH(ItemMediaListBinding.inflate(inf, parent, false))
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        val item = try { getItem(position) } catch (e: Exception) { null } ?: return
        val isSelected = selectedIds.contains(item.id)

        holder.itemView.setBackgroundColor(
            if (isSelected) Color.parseColor("#4DFF9500") else Color.TRANSPARENT
        )

        when (holder) {
            is PhotoVH    -> holder.bind(item, isSelected)
            is VideoVH    -> holder.bind(item, isSelected)
            is ListVH     -> holder.bind(item)
            is ProgressVH -> holder.bind(item)
        }

        holder.itemView.setOnClickListener  { 
            val currentPos = holder.bindingAdapterPosition
            if (currentPos != RecyclerView.NO_POSITION && currentPos < currentList.size) {
                onItemClick(getItem(currentPos)) 
            }
        }
        holder.itemView.setOnLongClickListener { 
            val currentPos = holder.bindingAdapterPosition
            if (currentPos != RecyclerView.NO_POSITION && currentPos < currentList.size) {
                onLongClick(getItem(currentPos))
            }
            true 
        }
    }

    inner class PhotoVH(val b: ItemMediaPhotoBinding) : RecyclerView.ViewHolder(b.root) {
        private var loadJob: Job? = null
        fun bind(item: MediaEntity?, isSelected: Boolean) {
            loadJob?.cancel()
            b.ivThumbnail.clearColorFilter()
            if (isSelected) b.ivThumbnail.setColorFilter(Color.parseColor("#80FF9500"))
            if (item == null) {
                b.ivThumbnail.setImageResource(R.drawable.ic_media_temp)
                return
            }
            val cacheKey = "img_thumb_${item.id}"
            ThumbnailCache.get(cacheKey)?.let {
                b.ivThumbnail.scaleType = ImageView.ScaleType.CENTER_CROP
                b.ivThumbnail.setImageBitmap(it)
                return
            }
            b.ivThumbnail.setImageResource(R.drawable.ic_media_temp)
            loadJob = adapterScope.launch {
                val bitmap = withContext(Dispatchers.IO) {
                    try {
                        val thumbPath = item.thumbnailPath
                        if (!thumbPath.isNullOrEmpty()) {
                            val f = File(thumbPath)
                            if (f.exists()) BitmapFactory.decodeFile(f.absolutePath) else null
                        } else null
                    } catch (e: Exception) { null }
                }
                if (bitmap != null) {
                    ThumbnailCache.put(cacheKey, bitmap)
                    b.ivThumbnail.scaleType = ImageView.ScaleType.CENTER_CROP
                    b.ivThumbnail.setImageBitmap(bitmap)
                }
            }
        }
    }

    inner class VideoVH(private val b: ItemMediaVideoLiveBinding) : RecyclerView.ViewHolder(b.root) {
        private var player: ExoPlayer? = null
        private var currentItem: MediaEntity? = null
        private var thumbJob: Job? = null

        @OptIn(UnstableApi::class)
        fun bind(item: MediaEntity, isSelected: Boolean) {
            thumbJob?.cancel()
            currentItem = item
            b.thumbnail.clearColorFilter()
            if (isSelected) b.thumbnail.setColorFilter(Color.parseColor("#80FF9500"))
            val cacheKey = "video_thumb_${item.id}"
            ThumbnailCache.get(cacheKey)?.let {
                b.thumbnail.scaleType = ImageView.ScaleType.CENTER_CROP
                b.thumbnail.setImageBitmap(it)
                return
            }
            b.thumbnail.setImageResource(R.drawable.ic_media_temp)
            thumbJob = adapterScope.launch {
                val bitmap = withContext(Dispatchers.IO) {
                    try {
                        val thumbPath = item.thumbnailPath
                        if (!thumbPath.isNullOrEmpty()) {
                            val f = File(thumbPath)
                            if (f.exists()) BitmapFactory.decodeFile(f.absolutePath) else null
                        } else null
                    } catch (e: Exception) { null }
                }
                if (bitmap != null) {
                    ThumbnailCache.put(cacheKey, bitmap)
                    b.thumbnail.scaleType = ImageView.ScaleType.CENTER_CROP
                    b.thumbnail.setImageBitmap(bitmap)
                }
            }
        }

        fun release() {
            thumbJob?.cancel()
            player?.let { VideoPlayerPool.recycle(it); b.previewPlayerView.player = null }
            player = null
        }
    }

    inner class ListVH(private val b: ItemMediaListBinding) : RecyclerView.ViewHolder(b.root) {
        fun bind(item: MediaEntity) {
            b.tvTitle.text   = item.fileName
            b.tvDetails.text = Formatter.formatShortFileSize(itemView.context, item.fileSize)
            b.ivIcon.setImageResource(when (item.fileType) {
                "audio" -> R.drawable.ic_musicnote
                "pdf"   -> R.drawable.ic_pdf
                else    -> R.drawable.ic_gallery
            })
        }
    }

    inner class ProgressVH(private val b: ItemMediaProgressBinding) : RecyclerView.ViewHolder(b.root) {
        @OptIn(UnstableApi::class)
        fun bind(item: MediaEntity) {
            b.tvProgTitle.text   = item.fileName
            b.progressBar.progress = item.progress
            b.tvPercent.text = "${item.progress}%"
            
            val downloadedStr = Formatter.formatShortFileSize(itemView.context, item.resumeBytes)
            val totalStr = if (item.fileSize > 0) Formatter.formatShortFileSize(itemView.context, item.fileSize) else "..."
            b.tvProgSize.text = "$downloadedStr / $totalStr"

            val isSuccess = item.progress >= 100

            b.ivTick.visibility = if (isSuccess) View.VISIBLE else View.GONE
            b.ivTypeIcon.visibility = if (isSuccess) View.GONE else View.VISIBLE

            if (isSuccess) {
                b.tvStatusText.text = "Secured successfully"
                b.tvPercent.text = ""
                b.btnPauseResume.visibility = View.GONE
                b.btnCancel.visibility = View.GONE
                b.progressBar.visibility = View.VISIBLE
                b.progressBar.progress = 100
                b.progressBar.isIndeterminate = false

                b.root.setOnClickListener {
                    val currentPos = bindingAdapterPosition
                    if (currentPos != RecyclerView.NO_POSITION && currentPos < currentList.size) {
                        val currentItem = getItem(currentPos)
                        if (currentItem.fileType == "video") {
                            val intent = Intent(itemView.context, SecureVideoActivity::class.java).apply {
                                putExtra("id", currentItem.id)
                            }
                            itemView.context.startActivity(intent)
                        } else {
                            onItemClick(currentItem)
                        }
                    }
                }
            } else {
                b.btnPauseResume.visibility = View.VISIBLE
                b.btnCancel.visibility = View.VISIBLE
                b.progressBar.visibility = View.VISIBLE

                b.tvStatusText.text = when {
                    item.isPaused -> "Paused"
                    item.progress > 0 -> "Downloading..."
                    else -> "Connecting..."
                }

                b.btnPauseResume.setImageResource(if (item.isPaused) R.drawable.play else R.drawable.ic_pause2)
                b.btnPauseResume.setOnClickListener {
                    val currentPos = bindingAdapterPosition
                    if (currentPos != RecyclerView.NO_POSITION && currentPos < currentList.size) {
                        val currentItem = getItem(currentPos)
                        val mgr = DownloadQueueManager(itemView.context)
                        if (currentItem.isPaused) mgr.resumeDownload(currentItem) else mgr.pauseDownload(currentItem)
                    }
                }
                b.btnCancel.setOnClickListener { 
                    val currentPos = bindingAdapterPosition
                    if (currentPos != RecyclerView.NO_POSITION && currentPos < currentList.size) {
                        onCancelClick(getItem(currentPos))
                    }
                }
                b.progressBar.isIndeterminate = item.progress == 0 && !item.isPaused

                // Reset click listener while downloading
                b.root.setOnClickListener { 
                    val currentPos = bindingAdapterPosition
                    if (currentPos != RecyclerView.NO_POSITION && currentPos < currentList.size) {
                        onItemClick(getItem(currentPos))
                    }
                }
            }

            b.ivTypeIcon.setImageResource(when(item.fileType) {
                "video" -> R.drawable.ic_video
                "audio" -> R.drawable.ic_musicnote
                "pdf"   -> R.drawable.ic_pdf
                else    -> R.drawable.ic_media
            })
        }
    }

    override fun onViewRecycled(holder: RecyclerView.ViewHolder) {
        if (holder is VideoVH) holder.release()
        super.onViewRecycled(holder)
    }

    fun cancelAllJobs() { adapterScope.cancel() }

    class Diff : DiffUtil.ItemCallback<MediaEntity>() {
        override fun areItemsTheSame(o: MediaEntity, n: MediaEntity)   = o.id == n.id
        override fun areContentsTheSame(o: MediaEntity, n: MediaEntity) = 
            o.progress == n.progress && 
            o.isPaused == n.isPaused && 
            o.isCompleted == n.isCompleted && 
            o.resumeBytes == n.resumeBytes &&
            o.fileSize == n.fileSize &&
            o.fileName == n.fileName
    }
}
