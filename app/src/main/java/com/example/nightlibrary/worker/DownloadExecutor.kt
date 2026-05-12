package com.example.nightlibrary.worker

import android.content.Context
import android.util.Log
import com.example.nightlibrary.dao.MediaDao
import com.example.nightlibrary.database.VaultDatabase
import com.example.nightlibrary.util.DeviceResourceManager
import com.example.nightlibrary.util.UserAgentManager
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class DownloadExecutor @Inject constructor(
    @ApplicationContext private val context: Context,
    private val thumbnailGenerator: ThumbnailGenerator,
    private val mediaEncryptor: MediaEncryptor,
    private val resourceManager: DeviceResourceManager
) {
    private val TAG = "DownloadExecutor"

    /**
     * 🔥 10/10 Architecture: Logic is now in a reusable Executor
     */
    suspend fun finalizeDownload(
        tmpFile: File,
        mediaId: Long,
        fileType: String,
        isIncognito: Boolean,
        onProgress: (Int, String) -> Unit
    ): Boolean = withContext(Dispatchers.IO) {
        try {
            val db = VaultDatabase.getDatabase(context)
            val dao = db.mediaDao()

            // 1. Thumbnail
            val thumbPath = if (!isIncognito && fileType != "audio") {
                onProgress(93, "Generating thumbnail...")
                thumbnailGenerator.generate(tmpFile)
            } else null

            // 2. Encryption
            onProgress(95, "Encrypting...")
            val result = mediaEncryptor.encrypt(tmpFile, fileType)

            // 3. Database Update
            if (!isIncognito && mediaId != -1L) {
                dao.getById(mediaId)?.let { existing ->
                    dao.update(existing.copy(
                        vaultFolder = result.vaultFolder,
                        chunkCount = result.chunkCount,
                        fileSize = result.totalFileSize,
                        checksum = result.checksum,
                        thumbnailPath = thumbPath,
                        isCompleted = true,
                        progress = 100,
                        isPaused = false,
                        isFailed = false
                    ))
                }
            }
            
            tmpFile.delete()
            true
        } catch (e: Exception) {
            Log.e(TAG, "Finalize failed: ${e.message}")
            false
        }
    }
}
