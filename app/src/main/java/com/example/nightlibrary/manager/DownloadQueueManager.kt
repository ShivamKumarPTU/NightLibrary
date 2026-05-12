package com.example.nightlibrary.manager

import android.content.Context
import android.os.StatFs
import android.util.Log
import androidx.work.*
import com.example.nightlibrary.database.VaultDatabase
import com.example.nightlibrary.entity.MediaEntity
import com.example.nightlibrary.preferences.SecurityPreferenceManager
import com.example.nightlibrary.worker.DownloadNotificationManager
import com.example.nightlibrary.worker.MediaDownloadWorker
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.TimeUnit

class InsufficientStorageException(message: String) : Exception(message)
class FileTooLargeException(message: String) : Exception(message)

class DownloadQueueManager(private val context: Context) {

    companion object {
        private const val TAG = "DownloadQueueManager"
        private const val MIN_FREE_SPACE = 100L * 1024 * 1024
        private const val MAX_DOWNLOAD_SIZE = 10L * 1024 * 1024 * 1024
        fun workName(mediaId: Long): String = "vault_dl_$mediaId"
        const val TAG_ALL_DOWNLOADS = "vault_downloads"
        private fun urlTag(url: String): String {
            val hash = MessageDigest.getInstance("MD5")
                .digest(url.toByteArray())
                .joinToString("") { "%02x".format(it) }
                .take(16)
            return "url_$hash"
        }
    }

    private val workManager = WorkManager.getInstance(context)
    private val scope = CoroutineScope(Dispatchers.IO)

    // ═══════════════════════════════════════════════════════════════
    // ENQUEUE
    // ═══════════════════════════════════════════════════════════════

    suspend fun enqueueDownload(
        downloadUrl: String,
        fileName: String,
        incognito: Boolean,
        silent: Boolean = false,
        mimeType: String = "video/mp4",
        headers: Map<String, String>? = null,
        originalUrl: String? = null,
        formatId: String? = null,
        useYtDlp: Boolean = false,
        isHls: Boolean = false,
        estimatedSize: Long = -1L,
        duration: Long = 0L,
        fileType: String? = null,
        streamUrl: String? = null              // ← ADD THIS PARAMETER
    ): Long {

        val pageUrl = originalUrl ?: downloadUrl
        val existingMediaId = checkDuplicateDownload(pageUrl, downloadUrl)
        if (existingMediaId != null) {
            Log.w(TAG, "⚡ Duplicate URL already active: mediaId=$existingMediaId")
            return existingMediaId
        }

        val freeSpace = getAvailableDiskSpace()
        if (freeSpace < MIN_FREE_SPACE) throw InsufficientStorageException("Only ${freeSpace / (1024 * 1024)} MB free.")
        if (estimatedSize > MAX_DOWNLOAD_SIZE) throw FileTooLargeException("File exceeds limit.")
        if (estimatedSize > 0 && estimatedSize > freeSpace - MIN_FREE_SPACE) throw InsufficientStorageException("Not enough space.")

        try { SecurityPreferenceManager(context).isSilentMode = silent } catch (_: Exception) {}

        val db = VaultDatabase.getDatabase(context)
        val dao = db.mediaDao()

        val resolvedFileType = fileType ?: when {
            mimeType.startsWith("image") -> "image"
            mimeType.startsWith("video") -> "video"
            mimeType.startsWith("audio") -> "audio"
            mimeType == "application/pdf" -> "pdf"
            else -> "video"
        }

        val tempFolder = File(context.filesDir, "vault_media/temp/${UUID.randomUUID()}")
        tempFolder.mkdirs()
        val headersJson = headers?.let { JSONObject(it).toString() }

        val mediaId = dao.insertAndGetId(
            MediaEntity(
                fileName = fileName,
                vaultFolder = tempFolder.absolutePath,
                chunkCount = 0,
                chunkSize = 0,
                fileSize = estimatedSize.coerceAtLeast(0),
                mimeType = mimeType,
                fileType = resolvedFileType,
                isCompleted = false,
                progress = 0,
                checksum = "",
                downloadUrl = downloadUrl,
                thumbnailPath = null,
                isPaused = false,
                isFailed = false,
                failReason = null,
                downloadedBytes = 0L,
                useYtDlp = useYtDlp,
                isHls = isHls,
                streamUrl = streamUrl,
                resumeBytes = 0L,
                isInTrash = false,
                currentSpeed = 0.0,
                duration = duration
            )
        )

        val data = workDataOf(
            "url" to downloadUrl,
            "originalUrl" to originalUrl,
            "streamUrl" to (streamUrl ?: ""),      // ← ADD THIS LINE
            "formatId" to formatId,
            "fileName" to fileName,
            "mediaId" to mediaId,
            "mimeType" to mimeType,
            "fileType" to resolvedFileType,
            "incognito" to incognito,
            "silent" to silent,
            "headers" to headersJson,
            "useYtDlp" to useYtDlp,
            "isHls" to isHls,
            "resumeFromBytes" to 0L,
            "duration" to duration
        )

        val request = OneTimeWorkRequestBuilder<MediaDownloadWorker>()
            .setInputData(data)
            .addTag(TAG_ALL_DOWNLOADS)
            .addTag("download_${mediaId}")
            .addTag(urlTag(pageUrl))
            .addTag(urlTag(downloadUrl))
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 15, TimeUnit.SECONDS)
            .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
            .setConstraints(
                Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.CONNECTED)
                    .setRequiresStorageNotLow(true)
                    .build()
            )
            .build()

        workManager.enqueueUniqueWork(workName(mediaId), ExistingWorkPolicy.KEEP, request)
        
        // 🔥 Phase 3: Immediate High-Priority Service Start
        try {
            com.example.nightlibrary.worker.MediaDownloadService.start(context, mediaId)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to start priority service: ${e.message}")
        }

        Log.d(TAG, "▶ Enqueued: id=$mediaId name=$fileName type=$resolvedFileType duration=${duration}s incognito=$incognito silent=$silent")
        return mediaId
    }

    private suspend fun checkDuplicateDownload(pageUrl: String, downloadUrl: String): Long? {
        try {
            val tag = urlTag(pageUrl)
            val existing = workManager.getWorkInfosByTag(tag).get()
            val active = existing.any { it.state == WorkInfo.State.RUNNING || it.state == WorkInfo.State.ENQUEUED }
            if (active) {
                val dao = VaultDatabase.getDatabase(context).mediaDao()
                val entity = dao.getActiveDownloadByUrl(downloadUrl) ?: dao.getActiveDownloadByUrl(pageUrl)
                if (entity != null) return entity.id
                return -2L
            }
        } catch (_: Exception) {}
        try {
            val dao = VaultDatabase.getDatabase(context).mediaDao()
            val entity = dao.getActiveDownloadByUrl(downloadUrl) ?: dao.getActiveDownloadByUrl(pageUrl)
            if (entity != null) return entity.id
        } catch (_: Exception) {}
        return null
    }

    // ═══════════════════════════════════════════════════════════════
    // ✅ FIXED: PAUSE — Handles both direct HTTP and HLS/yt-dlp
    //
    // Direct HTTP: partial file exists in vault_downloads/ → save its size
    // HLS/yt-dlp: no partial file → use downloadedBytes from DB
    //             (worker's saveResumeState already wrote to DB)
    // ═══════════════════════════════════════════════════════════════

    fun pauseDownload(media: MediaEntity) {
        workManager.cancelUniqueWork(workName(media.id))
        scope.launch {
            try {
                val dao = VaultDatabase.getDatabase(context).mediaDao()

                // First: mark as paused + clear speed
                dao.setPaused(media.id, true)
                dao.clearSpeed(media.id)

                // Second: try to find partial file (direct HTTP downloads)
                val resumeDir = File(context.filesDir, "vault_downloads")
                val partialFile = resumeDir.listFiles()
                    ?.filter { it.name.startsWith("dl_${media.id}_") }
                    ?.maxByOrNull { it.length() }

                if (partialFile != null && partialFile.exists() && partialFile.length() > 0) {
                    // ✅ Direct HTTP: physical partial file exists
                    val bytes = partialFile.length()
                    dao.updateProgress(media.id, media.progress, bytes)
                    Log.d(TAG, "✅ Pause saved (direct): id=${media.id} bytes=$bytes")
                } else {
                    // ✅ HLS/yt-dlp: no partial file — use DB values
                    // The worker's saveResumeState() (via NonCancellable) will update
                    // the DB with actual downloadedBytes shortly after cancellation.
                    // We don't need to do anything extra here — just log.
                    val entity = dao.getById(media.id)
                    val dbBytes = entity?.downloadedBytes ?: 0L
                    Log.d(TAG, "✅ Pause saved (HLS/yt-dlp): id=${media.id} dbBytes=$dbBytes " +
                            "(isHls=${media.isHls}, useYtDlp=${media.useYtDlp})")
                }
            } catch (e: Exception) {
                Log.w(TAG, "Pause save failed: ${e.message}")
            }
        }
    }

    // ═══════════════════════════════════════════════════════════════
    // ✅ FIXED: RESUME — Handles both direct HTTP and HLS/yt-dlp
    //
    // Direct HTTP: pass actual partial file size as resumeFromBytes
    // HLS/yt-dlp: pass 0 → they restart fresh (they have their own
    //             segment management and can't resume mid-stream)
    // ═══════════════════════════════════════════════════════════════

    fun resumeDownload(media: MediaEntity) {
        scope.launch {
            try {
                val dao = VaultDatabase.getDatabase(context).mediaDao()

                // Determine if this is an HLS/yt-dlp download
                val entity = dao.getById(media.id) ?: return@launch
                val isStreamDownload = entity.isHls || entity.useYtDlp

                // Find partial file for direct downloads
                val resumeDir = File(context.filesDir, "vault_downloads")
                val partialFile = resumeDir.listFiles()
                    ?.filter { it.name.startsWith("dl_${media.id}_") }
                    ?.maxByOrNull { it.length() }

                val actualResumeBytes: Long
                if (isStreamDownload) {
                    // ✅ HLS/yt-dlp: always restart fresh to avoid partial file corruption
                    try { partialFile?.delete() } catch (_: Exception) {}
                    actualResumeBytes = 0L
                    Log.d(TAG, "Resume (Stream): id=${entity.id} → Clean restart enforced")
                } else {
                    // ✅ Direct HTTP: use partial file if valid
                    actualResumeBytes = if (partialFile != null && partialFile.exists()) {
                        val fileSize = partialFile.length()
                        if (entity.resumeBytes > 0 && fileSize < entity.resumeBytes * 0.9) {
                            Log.w(TAG, "Partial file too small ($fileSize vs ${entity.resumeBytes}) → fresh start")
                            partialFile.delete()
                            0L
                        } else {
                            Log.d(TAG, "Found partial file: $fileSize bytes")
                            fileSize
                        }
                    } else {
                        Log.d(TAG, "No partial file found → fresh start")
                        0L
                    }
                }

                // Clear paused/failed state
                dao.setPaused(entity.id, false)
                dao.clearFailure(entity.id)
                dao.clearSpeed(entity.id)

                // Reset progress for HLS (will restart), keep for direct
                if (isStreamDownload) {
                    dao.updateProgress(entity.id, 0, 0L)
                } else if (actualResumeBytes != entity.resumeBytes) {
                    dao.updateProgress(entity.id, 0, actualResumeBytes)
                }

                val isSilent = try { SecurityPreferenceManager(context).isSilentMode } catch (_: Exception) { false }

                val data = workDataOf(
                    "url" to (entity.downloadUrl ?: ""),
                    "originalUrl" to entity.downloadUrl,
                    "streamUrl" to (entity.streamUrl ?: ""),   // ← ADD THIS LINE
                    "fileName" to entity.fileName,
                    "mediaId" to entity.id,
                    "mimeType" to entity.mimeType,
                    "fileType" to entity.fileType,
                    "incognito" to false,
                    "silent" to isSilent,
                    "useYtDlp" to entity.useYtDlp,
                    "isHls" to entity.isHls,
                    "resumeFromBytes" to actualResumeBytes,
                    "duration" to entity.duration
                )

                val request = OneTimeWorkRequestBuilder<MediaDownloadWorker>()
                    .setInputData(data)
                    .addTag(TAG_ALL_DOWNLOADS)
                    .addTag("download_${entity.id}")
                    .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 15, TimeUnit.SECONDS)
                    .setConstraints(
                        Constraints.Builder()
                            .setRequiredNetworkType(NetworkType.CONNECTED)
                            .build()
                    )
                    .build()

                workManager.enqueueUniqueWork(workName(entity.id), ExistingWorkPolicy.REPLACE, request)
                
                // 🔥 Phase 3: Immediate High-Priority Service Start
                try {
                    com.example.nightlibrary.worker.MediaDownloadService.start(context, entity.id)
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to start priority service: ${e.message}")
                }

                Log.d(TAG, "▶ Resumed: id=${entity.id} from=$actualResumeBytes stream=$isStreamDownload")
            } catch (e: Exception) {
                Log.e(TAG, "Resume failed: ${e.message}", e)
            }
        }
    }

    // ═══════════════════════════════════════════════════════════════
    // CANCEL
    // ═══════════════════════════════════════════════════════════════

    fun cancelDownload(media: MediaEntity) {
        workManager.cancelUniqueWork(workName(media.id))
        scope.launch {
            try {
                val dao = VaultDatabase.getDatabase(context).mediaDao()
                cleanupTempFiles(media.id)
                dao.getById(media.id)?.let { try { File(it.vaultFolder).deleteRecursively() } catch (_: Exception) {} }
                media.thumbnailPath?.let { try { File(it).delete() } catch (_: Exception) {} }
                dao.deleteById(media.id)
            } catch (_: Exception) {
                try { VaultDatabase.getDatabase(context).mediaDao().deleteById(media.id) } catch (_: Exception) {}
            }
        }
    }

    // ═══════════════════════════════════════════════════════════════
    // RETRY
    // ═══════════════════════════════════════════════════════════════

    fun retryDownload(media: MediaEntity) {
        workManager.cancelUniqueWork(workName(media.id))
        scope.launch {
            try {
                val dao = VaultDatabase.getDatabase(context).mediaDao()
                cleanupTempFiles(media.id)
                dao.clearFailure(media.id)
                dao.clearSpeed(media.id)
                dao.updateProgress(media.id, 0, 0L)
                kotlinx.coroutines.delay(500)

                val entity = dao.getById(media.id) ?: return@launch
                val isSilent = try { SecurityPreferenceManager(context).isSilentMode } catch (_: Exception) { false }

                val data = workDataOf(
                    "url" to (entity.downloadUrl ?: ""),
                    "originalUrl" to entity.downloadUrl,
                    "streamUrl" to (entity.streamUrl ?: ""),   // ← ADD THIS LINE
                    "fileName" to entity.fileName,
                    "mediaId" to entity.id,
                    "mimeType" to entity.mimeType,
                    "fileType" to entity.fileType,
                    "incognito" to false,
                    "silent" to isSilent,
                    "useYtDlp" to entity.useYtDlp,
                    "isHls" to entity.isHls,
                    "resumeFromBytes" to 0L,
                    "duration" to entity.duration
                )

                val request = OneTimeWorkRequestBuilder<MediaDownloadWorker>()
                    .setInputData(data)
                    .addTag(TAG_ALL_DOWNLOADS)
                    .addTag("download_${entity.id}")
                    .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 15, TimeUnit.SECONDS)
                    .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
                    .setConstraints(
                        Constraints.Builder()
                            .setRequiredNetworkType(NetworkType.CONNECTED)
                            .build()
                    )
                    .build()

                workManager.enqueueUniqueWork(workName(entity.id), ExistingWorkPolicy.REPLACE, request)

                // 🔥 Phase 3: Immediate High-Priority Service Start
                try {
                    com.example.nightlibrary.worker.MediaDownloadService.start(context, entity.id)
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to start priority service: ${e.message}")
                }
            } catch (e: Exception) { Log.e(TAG, "Retry failed: ${e.message}", e) }
        }
    }

    // ═══════════════════════════════════════════════════════════════
    // CANCEL ALL
    // ═══════════════════════════════════════════════════════════════

    fun cancelAllDownloads() {
        workManager.cancelAllWorkByTag(TAG_ALL_DOWNLOADS)
        scope.launch {
            try {
                val dao = VaultDatabase.getDatabase(context).mediaDao()
                val active = dao.getActiveDownloadsOnce()
                for (media in active) {
                    try {
                        cleanupTempFiles(media.id)
                        File(media.vaultFolder).deleteRecursively()
                        dao.deleteById(media.id)
                    } catch (_: Exception) {}
                }
            } catch (_: Exception) {}
        }
    }

    // ═══════════════════════════════════════════════════════════════
    // CLEANUP
    // ═══════════════════════════════════════════════════════════════

    fun cleanupOrphanedFiles() {
        scope.launch {
            try {
                val dao = VaultDatabase.getDatabase(context).mediaDao()
                val resumeDir = File(context.filesDir, "vault_downloads")
                if (!resumeDir.exists()) return@launch
                resumeDir.listFiles()?.forEach { file ->
                    val mediaId = file.name.removePrefix("dl_").substringBefore("_").toLongOrNull()
                    if (mediaId != null) {
                        val entity = dao.getById(mediaId)
                        if (entity == null || entity.isCompleted || entity.isInTrash) file.delete()
                    } else if (System.currentTimeMillis() - file.lastModified() > 24 * 60 * 60 * 1000L) {
                        file.delete()
                    }
                }
            } catch (_: Exception) {}
        }
    }

    fun observeDownload(mediaId: Long) = workManager.getWorkInfosByTagLiveData("download_$mediaId")

    private fun cleanupTempFiles(mediaId: Long) {
        try {
            File(context.filesDir, "vault_downloads").listFiles()
                ?.filter { it.name.startsWith("dl_${mediaId}_") }?.forEach { it.delete() }
            context.cacheDir.listFiles()
                ?.filter { it.name.startsWith("dl_${mediaId}_") }?.forEach { it.delete() }
        } catch (_: Exception) {}
    }

    private fun getAvailableDiskSpace(): Long = try {
        val stat = StatFs(context.filesDir.absolutePath)
        stat.availableBlocksLong * stat.blockSizeLong
    } catch (_: Exception) { Long.MAX_VALUE }
}