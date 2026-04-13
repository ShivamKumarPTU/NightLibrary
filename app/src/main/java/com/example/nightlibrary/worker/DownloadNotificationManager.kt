package com.example.nightlibrary.worker

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import androidx.core.app.NotificationCompat
import com.example.nightlibrary.R
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * Manages a SINGLE shared notification for ALL active downloads.
 *
 * Bug 4 fix: Instead of each worker creating its own notification (9001, 9002...),
 * all workers share notification ID 9000 with InboxStyle showing all active items.
 *
 * Thread-safe via ConcurrentHashMap — multiple workers update concurrently.
 */
object DownloadNotificationManager {

    const val SHARED_NOTIF_ID = 9000
    const val CHANNEL_ID = "vault_download"
    const val STEALTH_CHANNEL_ID = "vault_download_silent"

    /** Counter for one-shot completion/failure notifications (9100+) */
    private val completionIdCounter = AtomicInteger(9100)

    private val activeDownloads = ConcurrentHashMap<Long, DownloadEntry>()

    data class DownloadEntry(
        val fileName: String,
        val progress: Int,
        val speedText: String,
        val downloadedBytes: Long
    )

    // ═══════════════════════════════════════════════════════════
    // REGISTRATION — Workers call these on start/finish
    // ═══════════════════════════════════════════════════════════

    fun register(mediaId: Long, fileName: String) {
        activeDownloads[mediaId] = DownloadEntry(fileName, 0, "", 0L)
    }

    fun update(
        mediaId: Long,
        progress: Int,
        speedText: String,
        downloadedBytes: Long
    ) {
        val existing = activeDownloads[mediaId] ?: return
        activeDownloads[mediaId] = existing.copy(
            progress = progress,
            speedText = speedText,
            downloadedBytes = downloadedBytes
        )
    }

    fun unregister(mediaId: Long) {
        activeDownloads.remove(mediaId)
    }

    fun isActive(mediaId: Long): Boolean = activeDownloads.containsKey(mediaId)

    fun getActiveCount(): Int = activeDownloads.size

    fun getActiveMediaIds(): Set<Long> = activeDownloads.keys.toSet()

    /** Unique ID for completion/failure toasts — never collides with SHARED_NOTIF_ID */
    fun nextCompletionId(): Int = completionIdCounter.incrementAndGet()

    // ═══════════════════════════════════════════════════════════
    // NOTIFICATION BUILDING
    // ═══════════════════════════════════════════════════════════

    fun ensureChannels(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        nm.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID, "Vault Downloads", NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Download progress"
                setShowBadge(false); enableVibration(false); setSound(null, null)
            }
        )
        nm.createNotificationChannel(
            NotificationChannel(
                STEALTH_CHANNEL_ID, "Background Tasks", NotificationManager.IMPORTANCE_MIN
            ).apply {
                description = "Background processing"
                setShowBadge(false); enableVibration(false); setSound(null, null)
                lockscreenVisibility = Notification.VISIBILITY_SECRET
            }
        )
    }

    /**
     * Builds the single aggregate notification shown for ALL active downloads.
     * Called every time ANY worker updates progress.
     */
    fun buildAggregate(context: Context, isSilent: Boolean): Notification {
        val channelId = if (isSilent) STEALTH_CHANNEL_ID else CHANNEL_ID
        val count = activeDownloads.size

        val builder = NotificationCompat.Builder(context, channelId)
            .setSmallIcon(R.drawable.ic_download)
            .setOngoing(true)
            .setSilent(true)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)

        when {
            // ── No active downloads (shouldn't happen, but be safe) ─────
            count == 0 -> {
                builder.setContentTitle("Preparing…")
                    .setProgress(0, 0, true)
            }

            // ── Single download — show detailed info ────────────────────
            count == 1 -> {
                val entry = activeDownloads.values.first()
                val pct = entry.progress.coerceAtLeast(0)

                if (isSilent) {
                    builder.setContentTitle("Securing file…")
                        .setContentText("$pct%")
                        .setPriority(NotificationCompat.PRIORITY_MIN)
                        .setVisibility(NotificationCompat.VISIBILITY_SECRET)
                } else {
                    builder.setContentTitle("Downloading ${entry.fileName}")
                        .setContentText("$pct% ${entry.speedText}")
                        .setPriority(NotificationCompat.PRIORITY_LOW)
                }
                builder.setProgress(100, pct, pct <= 0)
            }

            // ── Multiple downloads — InboxStyle summary ─────────────────
            else -> {
                val avgPct = activeDownloads.values
                    .sumOf { it.progress.coerceAtLeast(0) } / count

                if (isSilent) {
                    builder.setContentTitle("Securing $count files…")
                        .setContentText("$avgPct%")
                        .setPriority(NotificationCompat.PRIORITY_MIN)
                        .setVisibility(NotificationCompat.VISIBILITY_SECRET)
                } else {
                    val inbox = NotificationCompat.InboxStyle()
                        .setBigContentTitle("$count downloads in progress")

                    activeDownloads.values.take(5).forEach { entry ->
                        val pct = entry.progress.coerceAtLeast(0)
                        inbox.addLine("${entry.fileName} — $pct% ${entry.speedText}")
                    }
                    if (count > 5) {
                        inbox.setSummaryText("+${count - 5} more")
                    }

                    builder.setContentTitle("$count downloads in progress")
                        .setContentText("$avgPct% average")
                        .setStyle(inbox)
                        .setPriority(NotificationCompat.PRIORITY_LOW)
                }
                builder.setProgress(100, avgPct, false)
            }
        }

        return builder.build()
    }

    /**
     * Rebuilds and posts the aggregate notification.
     * Call from any worker thread — it's thread-safe.
     */
    fun postUpdate(context: Context, isSilent: Boolean) {
        try {
            val nm = context.getSystemService(Context.NOTIFICATION_SERVICE)
                    as NotificationManager
            if (activeDownloads.isEmpty()) {
                nm.cancel(SHARED_NOTIF_ID)
            } else {
                nm.notify(SHARED_NOTIF_ID, buildAggregate(context, isSilent))
            }
        } catch (_: Exception) {}
    }

    /**
     * Cancel the shared notification (when last worker finishes).
     */
    fun cancelIfEmpty(context: Context) {
        if (activeDownloads.isEmpty()) {
            try {
                (context.getSystemService(Context.NOTIFICATION_SERVICE)
                        as NotificationManager).cancel(SHARED_NOTIF_ID)
            } catch (_: Exception) {}
        }
    }
}