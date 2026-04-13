package com.example.nightlibrary.adapter

import android.text.format.Formatter
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.example.nightlibrary.R
import com.example.nightlibrary.entity.MediaEntity
import com.example.nightlibrary.model.InProgressItem
import com.example.nightlibrary.model.ShareTask
import com.google.android.material.progressindicator.LinearProgressIndicator

/**
 * Sectioned adapter for the In Progress tab.
 */
class InProgressSectionAdapter(
    private val onPauseResume: (MediaEntity) -> Unit,
    private val onCancelDownload: (MediaEntity) -> Unit,
    private val onCancelImport: (MediaEntity) -> Unit,
    private val onCancelShare: (String) -> Unit,
    private val onRetryDownload: (MediaEntity) -> Unit,
    private val onCancelAllInSection: (InProgressItem.SectionType) -> Unit
) : ListAdapter<InProgressItem, RecyclerView.ViewHolder>(InProgressDiffCallback()) {

    companion object {
        private const val TYPE_HEADER = 0
        private const val TYPE_SHARE = 1
        private const val TYPE_DOWNLOAD = 2
        private const val TYPE_IMPORT = 3
        private const val TYPE_EMPTY = 4
    }

    override fun getItemViewType(position: Int): Int {
        return when (getItem(position)) {
            is InProgressItem.SectionHeader -> TYPE_HEADER
            is InProgressItem.ShareItem -> TYPE_SHARE
            is InProgressItem.DownloadItem -> TYPE_DOWNLOAD
            is InProgressItem.ImportItem -> TYPE_IMPORT
            is InProgressItem.EmptyState -> TYPE_EMPTY
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        return when (viewType) {
            TYPE_HEADER -> HeaderVH(inflater.inflate(R.layout.item_in_progress_header, parent, false))
            TYPE_SHARE -> ShareVH(inflater.inflate(R.layout.item_in_progress_share, parent, false))
            TYPE_DOWNLOAD -> DownloadVH(inflater.inflate(R.layout.item_media_progress, parent, false))
            TYPE_IMPORT -> ImportVH(inflater.inflate(R.layout.item_media_progress, parent, false))
            TYPE_EMPTY -> EmptyVH(inflater.inflate(R.layout.item_in_progress_empty, parent, false))
            else -> throw IllegalStateException("Unknown viewType")
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        onBindViewHolder(holder, position, emptyList())
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int, payloads: List<Any>) {
        val item = getItem(position)
        if (payloads.isNotEmpty()) {
            when (holder) {
                is ShareVH -> holder.updateProgress(item as InProgressItem.ShareItem)
                is DownloadVH -> holder.updateProgress(item as InProgressItem.DownloadItem)
                is ImportVH -> holder.updateProgress(item as InProgressItem.ImportItem)
            }
        } else {
            when (item) {
                is InProgressItem.SectionHeader -> (holder as HeaderVH).bind(item)
                is InProgressItem.ShareItem -> (holder as ShareVH).bind(item.task)
                is InProgressItem.DownloadItem -> (holder as DownloadVH).bind(item.media)
                is InProgressItem.ImportItem -> (holder as ImportVH).bind(item.media)
                is InProgressItem.EmptyState -> {}
            }
        }
    }

    inner class HeaderVH(view: View) : RecyclerView.ViewHolder(view) {
        private val ivIcon: ImageView = view.findViewById(R.id.iv_section_icon)
        private val tvTitle: TextView = view.findViewById(R.id.tv_section_title)
        private val tvCount: TextView = view.findViewById(R.id.tv_section_count)
        private val btnCancelAll: TextView = view.findViewById(R.id.btn_cancel_all)

        fun bind(header: InProgressItem.SectionHeader) {
            ivIcon.setImageResource(header.iconRes)
            tvTitle.text = header.title
            tvCount.text = "(${header.activeCount})"
            btnCancelAll.visibility = if (header.showCancelAll && header.activeCount > 1) View.VISIBLE else View.GONE
            btnCancelAll.setOnClickListener { onCancelAllInSection(header.sectionType) }
        }
    }

    inner class ShareVH(view: View) : RecyclerView.ViewHolder(view) {
        private val tvTitle: TextView = view.findViewById(R.id.tv_share_title)
        private val tvStatus: TextView = view.findViewById(R.id.tv_share_status)
        private val progressBar: LinearProgressIndicator = view.findViewById(R.id.share_progress_bar)
        private val tvPercent: TextView = view.findViewById(R.id.tv_share_percent)
        private val btnCancel: ImageButton = view.findViewById(R.id.btn_cancel_share)
        private val ivIcon: ImageView = view.findViewById(R.id.iv_share_icon)

        fun bind(task: ShareTask) {
            tvTitle.text = if (task.totalFiles == 1) "Sharing ${task.fileNames.firstOrNull() ?: "file"}" 
                           else "Sharing ${task.totalFiles} files"
            updateProgress(InProgressItem.ShareItem(task))
            
            if (task.isCompleted) {
                ivIcon.setImageResource(R.drawable.ic_check)
                btnCancel.visibility = View.GONE
            } else if (task.isCancelled || task.error != null) {
                ivIcon.setImageResource(R.drawable.ic_cross)
                btnCancel.visibility = View.GONE
            } else {
                ivIcon.setImageResource(R.drawable.ic_share)
                btnCancel.visibility = View.VISIBLE
                btnCancel.setOnClickListener { onCancelShare(task.id) }
            }
        }

        fun updateProgress(item: InProgressItem.ShareItem) {
            val task = item.task
            tvStatus.text = task.displayStatus
            progressBar.setProgress(task.overallProgress, true)
            tvPercent.text = if (task.isCompleted) "✓" else "${task.overallProgress}%"
        }
    }

    inner class DownloadVH(view: View) : RecyclerView.ViewHolder(view) {
        private val ivTypeIcon: ImageView = view.findViewById(R.id.iv_type_icon)
        private val ivTick: ImageView = view.findViewById(R.id.iv_tick)
        private val tvTitle: TextView = view.findViewById(R.id.tv_prog_title)
        private val tvSize: TextView = view.findViewById(R.id.tv_prog_size)
        private val btnPauseResume: ImageButton = view.findViewById(R.id.btn_pause_resume)
        private val btnCancel: ImageButton = view.findViewById(R.id.btn_cancel)
        private val progressBar: LinearProgressIndicator = view.findViewById(R.id.progress_bar)
        private val tvStatus: TextView = view.findViewById(R.id.tv_status_text)
        private val tvPercent: TextView = view.findViewById(R.id.tv_percent)
        private val tvSpeed: TextView = view.findViewById(R.id.tv_speed)

        fun bind(media: MediaEntity) {
            tvTitle.text = media.fileName
            ivTypeIcon.setImageResource(when (media.fileType) {
                "video" -> R.drawable.ic_video
                "audio" -> R.drawable.ic_musicnote
                "pdf" -> R.drawable.ic_pdf
                else -> R.drawable.ic_media
            })
            
            btnCancel.setOnClickListener { onCancelDownload(media) }
            btnPauseResume.setOnClickListener {
                if (media.isFailed) onRetryDownload(media) else onPauseResume(media)
            }
            
            updateProgress(InProgressItem.DownloadItem(media))
        }

        fun updateProgress(item: InProgressItem.DownloadItem) {
            val media = item.media
            val progress = media.progress.coerceIn(0, 100)
            val isSuccess = progress >= 100
            
            progressBar.setProgress(progress, true)
            progressBar.isIndeterminate = progress == 0 && !media.isPaused && !media.isFailed
            
            tvPercent.text = if (isSuccess) "✓" else "$progress%"
            ivTick.visibility = if (isSuccess) View.VISIBLE else View.GONE
            ivTypeIcon.visibility = if (isSuccess) View.GONE else View.VISIBLE
            
            val downloadedStr = Formatter.formatShortFileSize(itemView.context, media.downloadedBytes.coerceAtLeast(media.resumeBytes))
            val totalStr = if (media.fileSize > 0) Formatter.formatShortFileSize(itemView.context, media.fileSize) else "…"
            tvSize.text = "$downloadedStr / $totalStr"

            val speed = media.currentSpeed
            tvSpeed.text = formatSpeed(speed)
            tvSpeed.visibility = if (speed > 0 && !media.isPaused && !media.isFailed && !isSuccess) View.VISIBLE else View.GONE

            tvStatus.text = when {
                isSuccess -> "Secured successfully"
                media.isFailed -> "Failed: ${media.failReason?.take(30) ?: "Unknown error"}"
                media.isPaused -> "Paused"
                progress == 0 -> "Connecting…"
                progress >= 95 -> "Encrypting…"
                else -> "Downloading…"
            }
            
            if (isSuccess) {
                btnPauseResume.visibility = View.GONE
                btnCancel.visibility = View.GONE
            } else {
                btnPauseResume.visibility = View.VISIBLE
                btnCancel.visibility = View.VISIBLE
                btnPauseResume.setImageResource(when {
                    media.isFailed -> R.drawable.ic_retry
                    media.isPaused -> R.drawable.play
                    else -> R.drawable.ic_pause2
                })
            }
        }

        private fun formatSpeed(bytesPerSec: Double): String = when {
            bytesPerSec <= 0 -> ""
            bytesPerSec < 1024 -> "${bytesPerSec.toInt()} B/s"
            bytesPerSec < 1024 * 1024 -> "%.1f KB/s".format(bytesPerSec / 1024)
            else -> "%.1f MB/s".format(bytesPerSec / (1024 * 1024))
        }
    }

    inner class ImportVH(view: View) : RecyclerView.ViewHolder(view) {
        private val ivTypeIcon: ImageView = view.findViewById(R.id.iv_type_icon)
        private val ivTick: ImageView = view.findViewById(R.id.iv_tick)
        private val tvTitle: TextView = view.findViewById(R.id.tv_prog_title)
        private val tvSize: TextView = view.findViewById(R.id.tv_prog_size)
        private val btnPauseResume: ImageButton = view.findViewById(R.id.btn_pause_resume)
        private val btnCancel: ImageButton = view.findViewById(R.id.btn_cancel)
        private val progressBar: LinearProgressIndicator = view.findViewById(R.id.progress_bar)
        private val tvStatus: TextView = view.findViewById(R.id.tv_status_text)
        private val tvPercent: TextView = view.findViewById(R.id.tv_percent)
        private val tvSpeed: TextView = view.findViewById(R.id.tv_speed)

        fun bind(media: MediaEntity) {
            tvTitle.text = media.fileName
            tvSpeed.visibility = View.GONE
            btnPauseResume.visibility = View.GONE
            btnCancel.setOnClickListener { onCancelImport(media) }
            
            ivTypeIcon.setImageResource(when (media.fileType) {
                "image" -> R.drawable.ic_gallery
                "video" -> R.drawable.ic_video
                "audio" -> R.drawable.ic_musicnote
                "pdf" -> R.drawable.ic_pdf
                else -> R.drawable.ic_media
            })
            
            updateProgress(InProgressItem.ImportItem(media))
        }

        fun updateProgress(item: InProgressItem.ImportItem) {
            val media = item.media
            val progress = media.progress.coerceIn(0, 100)
            val isSuccess = progress >= 100
            
            progressBar.setProgress(progress, true)
            progressBar.isIndeterminate = progress == 0 && !isSuccess
            tvPercent.text = if (isSuccess) "✓" else "$progress%"
            
            ivTick.visibility = if (isSuccess) View.VISIBLE else View.GONE
            ivTypeIcon.visibility = if (isSuccess) View.GONE else View.VISIBLE
            
            tvSize.text = if (media.fileSize > 0) Formatter.formatShortFileSize(itemView.context, media.fileSize) else "Calculating…"
            
            tvStatus.text = when {
                isSuccess -> "Secured successfully"
                progress < 5 -> "Preparing…"
                progress < 10 -> "Generating thumbnail…"
                progress < 95 -> "Encrypting…"
                else -> "Finalizing…"
            }
            
            btnCancel.visibility = if (isSuccess) View.GONE else View.VISIBLE
        }
    }

    inner class EmptyVH(view: View) : RecyclerView.ViewHolder(view)

    class InProgressDiffCallback : DiffUtil.ItemCallback<InProgressItem>() {
        override fun areItemsTheSame(oldItem: InProgressItem, newItem: InProgressItem) = oldItem.stableId == newItem.stableId

        override fun areContentsTheSame(oldItem: InProgressItem, newItem: InProgressItem): Boolean {
            return when {
                oldItem is InProgressItem.DownloadItem && newItem is InProgressItem.DownloadItem -> {
                    val o = oldItem.media; val n = newItem.media
                    o.progress == n.progress && o.isPaused == n.isPaused && o.isFailed == n.isFailed && 
                    o.currentSpeed == n.currentSpeed && o.downloadedBytes == n.downloadedBytes && 
                    o.resumeBytes == n.resumeBytes && o.fileSize == n.fileSize && o.fileName == n.fileName
                }
                oldItem is InProgressItem.ImportItem && newItem is InProgressItem.ImportItem -> {
                    oldItem.media.progress == newItem.media.progress && 
                    oldItem.media.isCompleted == newItem.media.isCompleted &&
                    oldItem.media.fileName == newItem.media.fileName
                }
                oldItem is InProgressItem.ShareItem && newItem is InProgressItem.ShareItem -> oldItem.task == newItem.task
                oldItem is InProgressItem.SectionHeader && newItem is InProgressItem.SectionHeader -> 
                    oldItem.activeCount == newItem.activeCount && oldItem.title == newItem.title
                oldItem is InProgressItem.EmptyState && newItem is InProgressItem.EmptyState -> true
                else -> false
            }
        }

        override fun getChangePayload(oldItem: InProgressItem, newItem: InProgressItem): Any? {
            return if (oldItem::class == newItem::class && oldItem !is InProgressItem.SectionHeader && oldItem !is InProgressItem.EmptyState) {
                "PROGRESS_UPDATE"
            } else null
        }
    }
}
