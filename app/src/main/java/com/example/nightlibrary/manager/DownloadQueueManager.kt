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
    // ✅ MODIFIED: Added duration + fileType parameters
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
        duration: Long = 0L,          // ✅ NEW — Problem 6
        fileType: String? = null       // ✅ NEW — Feature A (overrides mimeType detection)
    ): Long {

        // Duplicate check
        val pageUrl = originalUrl ?: downloadUrl
        val existingMediaId = checkDuplicateDownload(pageUrl, downloadUrl)
        if (existingMediaId != null) {
            Log.w(TAG, "⚡ Duplicate URL already active: mediaId=$existingMediaId")
            return existingMediaId
        }

        // Storage checks
        val freeSpace = getAvailableDiskSpace()
        if (freeSpace < MIN_FREE_SPACE) throw InsufficientStorageException("Only ${freeSpace / (1024 * 1024)} MB free.")
        if (estimatedSize > MAX_DOWNLOAD_SIZE) throw FileTooLargeException("File exceeds limit.")
        if (estimatedSize > 0 && estimatedSize > freeSpace - MIN_FREE_SPACE) throw InsufficientStorageException("Not enough space.")

        try { SecurityPreferenceManager(context).isSilentMode = silent } catch (_: Exception) {}

        val db = VaultDatabase.getDatabase(context)
        val dao = db.mediaDao()

        // ✅ Feature A: Use explicit fileType if provided, else detect from mimeType
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
                fileType = resolvedFileType,            // ✅ Feature A
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
                resumeBytes = 0L,
                isInTrash = false,
                currentSpeed = 0.0,
                duration = duration                     // ✅ Problem 6
            )
        )

        val data = workDataOf(
            "url" to downloadUrl,
            "originalUrl" to originalUrl,
            "formatId" to formatId,
            "fileName" to fileName,
            "mediaId" to mediaId,
            "mimeType" to mimeType,
            "fileType" to resolvedFileType,              // ✅ NEW: passed to worker
            "incognito" to incognito,
            "silent" to silent,
            "headers" to headersJson,
            "useYtDlp" to useYtDlp,
            "isHls" to isHls,
            "resumeFromBytes" to 0L,
            "duration" to duration                       // ✅ NEW: passed to worker
        )

        val request = OneTimeWorkRequestBuilder<MediaDownloadWorker>()
            .setInputData(data)
            .addTag(TAG_ALL_DOWNLOADS)
            .addTag("download_${mediaId}")
            .addTag(urlTag(pageUrl))
            .addTag(urlTag(downloadUrl))
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 15, TimeUnit.SECONDS)
            .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
            .setConstraints(Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .setRequiresStorageNotLow(true)
                .build())
            .build()

        workManager.enqueueUniqueWork(workName(mediaId), ExistingWorkPolicy.KEEP, request)
        Log.d(TAG, "▶ Enqueued: id=$mediaId name=$fileName type=$resolvedFileType duration=${duration}s incognito=$incognito silent=$silent")
        return mediaId
    }

    // Duplicate check — unchanged
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
    // RESUME — Now passes duration + fileType
    // ═══════════════════════════════════════════════════════════════

    fun resumeDownload(media: MediaEntity) {
        scope.launch {
            try {
                val dao = VaultDatabase.getDatabase(context).mediaDao()
                val resumeDir = File(context.filesDir, "vault_downloads")
                val partialFile = resumeDir.listFiles()
                    ?.filter { it.name.startsWith("dl_${media.id}_") }
                    ?.maxByOrNull { it.length() }

                val actualResumeBytes = if (partialFile != null && partialFile.exists()) {
                    val fileSize = partialFile.length()
                    if (media.resumeBytes > 0 && fileSize < media.resumeBytes * 0.9) {
                        partialFile.delete(); 0L
                    } else fileSize
                } else 0L

                dao.setPaused(media.id, false)
                dao.clearFailure(media.id)
                dao.clearSpeed(media.id)
                if (actualResumeBytes != media.resumeBytes) dao.updateProgress(media.id, 0, actualResumeBytes)

                val entity = dao.getById(media.id) ?: return@launch
                val isSilent = try { SecurityPreferenceManager(context).isSilentMode } catch (_: Exception) { false }

                val data = workDataOf(
                    "url" to (entity.downloadUrl ?: ""),
                    "originalUrl" to entity.downloadUrl,
                    "fileName" to entity.fileName,
                    "mediaId" to entity.id,
                    "mimeType" to entity.mimeType,
                    "fileType" to entity.fileType,           // ✅ NEW
                    "incognito" to false,
                    "silent" to isSilent,
                    "useYtDlp" to entity.useYtDlp,
                    "isHls" to entity.isHls,
                    "resumeFromBytes" to actualResumeBytes,
                    "duration" to entity.duration             // ✅ NEW
                )

                val request = OneTimeWorkRequestBuilder<MediaDownloadWorker>()
                    .setInputData(data)
                    .addTag(TAG_ALL_DOWNLOADS)
                    .addTag("download_${entity.id}")
                    .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 15, TimeUnit.SECONDS)
                    .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                    .build()

                workManager.enqueueUniqueWork(workName(entity.id), ExistingWorkPolicy.REPLACE, request)
                Log.d(TAG, "▶ Resumed: id=${entity.id} from=$actualResumeBytes")
            } catch (e: Exception) { Log.e(TAG, "Resume failed: ${e.message}", e) }
        }
    }

    // Pause — unchanged
    fun pauseDownload(media: MediaEntity) {
        workManager.cancelUniqueWork(workName(media.id))
        scope.launch {
            try {
                val dao = VaultDatabase.getDatabase(context).mediaDao()
                dao.setPaused(media.id, true)
                dao.clearSpeed(media.id)
            } catch (_: Exception) {}
        }
    }

    // Cancel — unchanged
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

    // Retry — passes duration + fileType
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
                    "fileName" to entity.fileName,
                    "mediaId" to entity.id,
                    "mimeType" to entity.mimeType,
                    "fileType" to entity.fileType,           // ✅ NEW
                    "incognito" to false,
                    "silent" to isSilent,
                    "useYtDlp" to entity.useYtDlp,
                    "isHls" to entity.isHls,
                    "resumeFromBytes" to 0L,
                    "duration" to entity.duration             // ✅ NEW
                )

                val request = OneTimeWorkRequestBuilder<MediaDownloadWorker>()
                    .setInputData(data)
                    .addTag(TAG_ALL_DOWNLOADS)
                    .addTag("download_${entity.id}")
                    .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 15, TimeUnit.SECONDS)
                    .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
                    .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                    .build()

                workManager.enqueueUniqueWork(workName(entity.id), ExistingWorkPolicy.REPLACE, request)
            } catch (e: Exception) { Log.e(TAG, "Retry failed: ${e.message}", e) }
        }
    }

    fun cancelAllDownloads() {
        workManager.cancelAllWorkByTag(TAG_ALL_DOWNLOADS)
        scope.launch {
            try {
                val dao = VaultDatabase.getDatabase(context).mediaDao()
                val active = dao.getActiveDownloadsOnce()
                for (media in active) {
                    try { cleanupTempFiles(media.id); File(media.vaultFolder).deleteRecursively(); dao.deleteById(media.id) } catch (_: Exception) {}
                }
            } catch (_: Exception) {}
        }
    }

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
                    } else if (System.currentTimeMillis() - file.lastModified() > 24 * 60 * 60 * 1000L) file.delete()
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