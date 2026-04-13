package com.example.nightlibrary.worker

import android.content.Context
import android.util.Log
import androidx.work.WorkInfo
import androidx.work.WorkManager
import com.example.nightlibrary.database.VaultDatabase
import com.example.nightlibrary.manager.DownloadQueueManager

/**
 * Bug 3 fix: Utility to check whether a temp file belongs to an active download.
 *
 * Used by ImportMediaFragment (or any cleanup routine) BEFORE deleting temp files.
 *
 * Usage:
 *   val guard = ActiveDownloadGuard(context)
 *   tempFiles.forEach { file ->
 *       if (guard.isSafeToDelete(file.name)) file.delete()
 *       else Log.d(TAG, "Skipped active: ${file.name}")
 *   }
 */
class ActiveDownloadGuard(context: Context) {

    companion object {
        private const val TAG = "ActiveDownloadGuard"
    }

    /** Set of mediaIds that are currently downloading (RUNNING or ENQUEUED) */
    private val activeMediaIds: Set<Long>

    /** Set of filenames being actively downloaded */
    private val activeFileNames: Set<String>

    init {
        val workManager = WorkManager.getInstance(context)
        val dao = VaultDatabase.getDatabase(context).mediaDao()

        // ── Source 1: WorkManager active jobs ────────────────────────
        val workInfos = try {
            workManager.getWorkInfosByTag(DownloadQueueManager.TAG_ALL_DOWNLOADS)
                .get()
                .filter {
                    it.state == WorkInfo.State.RUNNING ||
                            it.state == WorkInfo.State.ENQUEUED
                }
        } catch (_: Exception) { emptyList() }

        val wmFileNames = workInfos.mapNotNull { info ->
            // Try inputData first, then progress data
            info.progress.getString("fileName")
                ?: info.outputData.getString("fileName")
        }.toSet()

        val wmMediaIds = workInfos.mapNotNull { info ->
            val id = info.progress.getLong("mediaId", -1L)
            if (id != -1L) id else null
        }.toSet()

        // ── Source 2: DB active downloads ────────────────────────────
        val dbMediaIds = try {
            kotlinx.coroutines.runBlocking {
                dao.getActiveDownloadsOnce().map { it.id }.toSet()
            }
        } catch (_: Exception) { emptySet() }

        // ── Source 3: DownloadNotificationManager registry ──────────
        val registryIds = DownloadNotificationManager.getActiveMediaIds()

        // ── Combine all sources ─────────────────────────────────────
        activeMediaIds = wmMediaIds + dbMediaIds + registryIds
        activeFileNames = wmFileNames

        Log.d(TAG, "Guard initialized: ${activeMediaIds.size} active mediaIds, " +
                "${activeFileNames.size} active fileNames")
    }

    /**
     * Returns true if the file is NOT part of any active download
     * and is safe to delete.
     */
    fun isSafeToDelete(fileName: String): Boolean {
        // Check by filename match
        if (fileName in activeFileNames) return false

        // Check by mediaId extracted from filename: dl_{mediaId}_{uuid}.mp4
        val mediaId = fileName
            .removePrefix("dl_")
            .substringBefore("_")
            .toLongOrNull()

        if (mediaId != null && mediaId in activeMediaIds) return false

        // Also check if the filename contains any active mediaId
        for (id in activeMediaIds) {
            if (fileName.contains("dl_${id}_")) return false
        }

        return true
    }

    /**
     * Returns true if a specific mediaId has an active download.
     */
    fun isActiveDownload(mediaId: Long): Boolean = mediaId in activeMediaIds
}