package com.example.nightlibrary.worker

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.example.nightlibrary.core.security.VaultFileManager
import com.example.nightlibrary.database.VaultDatabase
import com.example.nightlibrary.entity.MediaEntity
import com.example.nightlibrary.security.ChunkEncryptor
import com.example.nightlibrary.security.VaultCryptoEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest
import java.util.UUID

class LocalImportWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    companion object {
        private const val TAG = "LocalImportWorker"
        private const val VIDEO_CHUNK_SIZE = 4 * 1024 * 1024
        private const val OTHER_CHUNK_SIZE = 512 * 1024
        private const val FINGERPRINT_READ_LIMIT = 1024 * 1024L
        private const val FINGERPRINT_BUFFER_SIZE = 8192
    }

    override suspend fun doWork(): Result {
        val uriString = inputData.getString("uri") ?: return Result.failure()
        val mimeType = inputData.getString("mimeType") ?: return Result.failure()
        val fileName = inputData.getString("fileName") ?: "imported_file"
        val filePath = inputData.getString("filePath")
        val uri: Uri = if (!filePath.isNullOrEmpty()) Uri.fromFile(File(filePath))
        else Uri.parse(uriString)

        val db = VaultDatabase.getDatabase(applicationContext)
        val dao = db.mediaDao()

        var mediaId = 0L
        var tempVaultFolder: File? = null
        var finalVaultFolder: File? = null
        var isCompleted = false

        return try {
            val fileType = resolveType(mimeType)

            val fingerprint = inputData.getString("fingerprint")
                ?.takeIf { it.isNotEmpty() }
                ?: computeContentFingerprint(uri)

            Log.d(TAG, "Fingerprint for $fileName: ${fingerprint.take(16)}…")

            // ✅ FIX: Check for completed duplicate
            if (fingerprint.isNotEmpty() && dao.existsByChecksum(fingerprint)) {
                Log.d(TAG, "⚠️ Duplicate detected, skipping: $fileName")
                return Result.success(
                    workDataOf("result" to "DUPLICATE", "fileName" to fileName)
                )
            }

            // ✅ FIX: Clean up stale incomplete entry from previous crash/restart
            //   If WorkManager re-enqueues this worker after app kill, the old
            //   incomplete DB entry + partial files would be orphaned. Clean up first.
            if (fingerprint.isNotEmpty()) {
                val stale = dao.getIncompleteByChecksum(fingerprint)
                if (stale != null) {
                    Log.d(TAG, "🧹 Cleaning stale incomplete: id=${stale.id} file=${stale.fileName}")
                    try { File(stale.vaultFolder).deleteRecursively() } catch (_: Exception) {}
                    try {
                        val oldThumb = File(applicationContext.filesDir, "vault_thumbs/thumb_${stale.id}.jpg")
                        if (oldThumb.exists()) oldThumb.delete()
                    } catch (_: Exception) {}
                    dao.deleteById(stale.id)
                }
            }

            // Create temp vault folder
            tempVaultFolder = File(
                applicationContext.filesDir,
                "vault_media/temp/${UUID.randomUUID()}"
            ).also { it.mkdirs() }

            val fileSize = getFileSize(uri)

            // Insert DB row → item appears in RecyclerView immediately
            mediaId = dao.insertAndGetId(
                MediaEntity(
                    fileName = fileName,
                    vaultFolder = tempVaultFolder!!.absolutePath,
                    chunkCount = 0,
                    chunkSize = 0,
                    fileSize = fileSize,
                    mimeType = mimeType,
                    fileType = fileType,
                    isCompleted = false,
                    progress = 0,
                    checksum = fingerprint,
                    filePath = "",
                    thumbnailPath = null,
                    downloadUrl = null,
                    currentSpeed = 0.0
                )
            )

            setProgressAsync(workDataOf("progress" to 1, "mediaId" to mediaId))
            dao.updateProgress(mediaId, 1, 0L)

            // ✅ FIX: Suspending acquire — does NOT block the thread
            //   Old: java.util.concurrent.Semaphore.acquire() → BLOCKS thread
            //        → WorkManager can't cancel → marks FAILED on kill
            //   New: kotlinx.coroutines.sync.Semaphore.acquire() → SUSPENDS
            //        → WorkManager CAN cancel → marks ENQUEUED on kill → resumes
            Log.d(TAG, "⏳ Waiting for I/O slot: $fileName")
            setProgressAsync(workDataOf("progress" to 2, "mediaId" to mediaId))

            ImportConcurrencyGuard.ioSemaphore.acquire()
            Log.d(TAG, "✅ Got I/O slot: $fileName")

            try {
                finalVaultFolder = File(
                    applicationContext.filesDir,
                    "vault_media/$fileType/${UUID.randomUUID()}"
                ).also { it.mkdirs() }

                val thumbDeferred: Deferred<String?>
                var totalSize = 0L
                var chunkCount = 0

                coroutineScope {
                    thumbDeferred = async(Dispatchers.IO) {
                        dao.updateProgress(mediaId, 5, 0L)
                        setProgressAsync(workDataOf("progress" to 5, "mediaId" to mediaId))
                        generateThumbnail(uri, mimeType, mediaId)
                    }

                    if (fileType == "image") {
                        dao.updateProgress(mediaId, 10, 0L)
                        setProgressAsync(workDataOf("progress" to 10, "mediaId" to mediaId))

                        val vaultFileManager = VaultFileManager(applicationContext)
                        val encryptedFile = vaultFileManager.encryptToDirectory(
                            uri, finalVaultFolder!!, "full_image.enc"
                        )
                        totalSize = encryptedFile.length()
                        chunkCount = 1

                        dao.updateProgress(mediaId, 95, 0L)
                        setProgressAsync(workDataOf("progress" to 95, "mediaId" to mediaId))
                    } else {
                        val encryptor = ChunkEncryptor(
                            applicationContext, VaultCryptoEngine()
                        )
                        val chSize = if (fileType == "video")
                            VIDEO_CHUNK_SIZE else OTHER_CHUNK_SIZE

                        var lastReportedPct = 0
                        var lastReportedTime = 0L
                        val progressScope = CoroutineScope(Dispatchers.IO)

                        val index = encryptor.encryptStreamWithProgress(
                            uri, finalVaultFolder!!, chSize
                        ) { pct ->
                            val scaledPct = 10 + (pct * 85 / 100)
                            val now = System.currentTimeMillis()

                            if (scaledPct >= lastReportedPct + 5 || now - lastReportedTime >= 1000L) {
                                lastReportedPct = scaledPct
                                lastReportedTime = now

                                setProgressAsync(
                                    workDataOf("progress" to scaledPct, "mediaId" to mediaId)
                                )

                                progressScope.launch {
                                    try {
                                        dao.updateProgress(mediaId, scaledPct, 0L)
                                    } catch (e: Exception) {
                                        Log.w(TAG, "Progress DB update failed: ${e.message}")
                                    }
                                }
                            }
                        }
                        totalSize = index.totalFileSize
                        chunkCount = index.chunkCount
                    }
                }

                val thumbnailPath = thumbDeferred.await()

                dao.updateProgress(mediaId, 98, 0L)
                setProgressAsync(workDataOf("progress" to 98, "mediaId" to mediaId))

                dao.update(
                    MediaEntity(
                        id = mediaId,
                        fileName = fileName,
                        vaultFolder = finalVaultFolder!!.absolutePath,
                        chunkCount = chunkCount,
                        chunkSize = if (fileType == "video")
                            VIDEO_CHUNK_SIZE else OTHER_CHUNK_SIZE,
                        fileSize = totalSize,
                        mimeType = mimeType,
                        fileType = fileType,
                        isCompleted = true,
                        progress = 100,
                        checksum = fingerprint,
                        filePath = "",
                        thumbnailPath = thumbnailPath,
                        downloadUrl = null,
                        currentSpeed = 0.0
                    )
                )

                isCompleted = true
                Result.success(
                    workDataOf("result" to "SUCCESS", "mediaId" to mediaId)
                )

            } finally {
                ImportConcurrencyGuard.ioSemaphore.release()
                Log.d(TAG, "🔓 Released I/O slot: $fileName")
            }

        } catch (e: Exception) {
            Log.e(TAG, "Import failed: ${e.message}", e)
            Result.failure(
                workDataOf("error" to (e.message ?: "Unknown error"))
            )
        } finally {
            try { tempVaultFolder?.deleteRecursively() } catch (_: Exception) {}

            if (uriString.startsWith("file://")) {
                try {
                    val srcPath = android.net.Uri.parse(uriString).path
                    if (!srcPath.isNullOrEmpty()) {
                        val srcFile = File(srcPath)
                        if (srcFile.exists()) {
                            srcFile.delete()
                            Log.d(TAG, "🗑 Cleaned camera temp source: ${srcFile.name}")
                        }
                    }
                } catch (_: Exception) {}
            }

            if (!isCompleted && mediaId > 0) {
                Log.d(TAG, "Cleaning up failed import: mediaId=$mediaId")
                try { finalVaultFolder?.deleteRecursively() } catch (_: Exception) {}
                try { dao.deleteById(mediaId) } catch (_: Exception) {}
                try {
                    val thumbFile = File(
                        applicationContext.filesDir, "vault_thumbs/thumb_$mediaId.jpg"
                    )
                    if (thumbFile.exists()) thumbFile.delete()
                } catch (_: Exception) {}
            }
        }
    }

    private fun computeContentFingerprint(uri: Uri): String {
        return try {
            val resolver = applicationContext.contentResolver
            val md = MessageDigest.getInstance("SHA-256")
            val fileSize: Long = getFileSize(uri)
            resolver.openInputStream(uri)?.use { input ->
                val buffer = ByteArray(FINGERPRINT_BUFFER_SIZE)
                var totalRead = 0L
                while (totalRead < FINGERPRINT_READ_LIMIT) {
                    val n = input.read(buffer)
                    if (n == -1) break
                    md.update(buffer, 0, n)
                    totalRead += n
                }
            }
            md.update(fileSize.toString().toByteArray(Charsets.UTF_8))
            md.digest().joinToString("") { "%02x".format(it) }
        } catch (e: Exception) {
            Log.w(TAG, "Fingerprint failed: ${e.message}")
            ""
        }
    }

    private fun getFileSize(uri: Uri): Long {
        if (uri.scheme == "file") {
            return try { File(uri.path ?: return 0L).length() } catch (_: Exception) { 0L }
        }
        return try {
            applicationContext.contentResolver.query(
                uri, arrayOf(OpenableColumns.SIZE), null, null, null
            )?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val idx = cursor.getColumnIndex(OpenableColumns.SIZE)
                    if (idx != -1) cursor.getLong(idx) else 0L
                } else 0L
            } ?: 0L
        } catch (_: Exception) { 0L }
    }

    private fun generateThumbnail(uri: Uri, mimeType: String, id: Long): String? {
        return try {
            val thumbDir = File(
                applicationContext.filesDir, "vault_thumbs"
            ).also { it.mkdirs() }
            val thumbFile = File(thumbDir, "thumb_$id.jpg")
            val bitmap: Bitmap? = when {
                mimeType.startsWith("image") -> {
                    applicationContext.contentResolver
                        .openInputStream(uri)?.use { input ->
                            BitmapFactory.decodeStream(
                                input, null,
                                BitmapFactory.Options().apply { inSampleSize = 2 }
                            )
                        }
                }
                mimeType.startsWith("video") -> {
                    MediaMetadataRetriever().use { retriever ->
                        retriever.setDataSource(applicationContext, uri)
                        retriever.getFrameAtTime(0, MediaMetadataRetriever.OPTION_NEXT_SYNC)
                    }
                }
                else -> null
            }
            if (bitmap != null) {
                FileOutputStream(thumbFile).use { out ->
                    bitmap.compress(Bitmap.CompressFormat.JPEG, 90, out)
                }
                bitmap.recycle()
                thumbFile.absolutePath
            } else null
        } catch (e: Exception) {
            Log.w(TAG, "Thumbnail generation failed: ${e.message}")
            null
        }
    }

    private fun resolveType(mime: String?): String {
        if (mime == null) return "document"
        val lowerMime = mime.lowercase()
        return when {
            lowerMime.startsWith("image") -> "image"
            lowerMime.startsWith("video") -> "video"
            lowerMime.startsWith("audio") -> "audio"
            lowerMime == "application/pdf" -> "pdf"
            else -> "document"
        }
    }
}