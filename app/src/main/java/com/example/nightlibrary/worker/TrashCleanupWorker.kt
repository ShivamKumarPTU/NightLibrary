package com.example.nightlibrary.worker

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.example.nightlibrary.database.VaultDatabase
import com.example.nightlibrary.entity.MediaEntity
import com.example.nightlibrary.security.SecureWipe
import java.io.File
import java.util.concurrent.TimeUnit

class TrashCleanupWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        return try {
            val db = VaultDatabase.getDatabase(applicationContext)
            val dao = db.mediaDao()

            // Get items marked as 'isInTrash = true'
            val trashItems: List<MediaEntity> = dao.getTrashOnce()

            // Calculate 7 days ago timestamp
            val cutoff = System.currentTimeMillis() - (7 * 24 * 60 * 60 * 1000L)

            for (item in trashItems) {
                if (item.createdAt < cutoff) {
                    // 1. Wipe the specific filePath if it exists (for legacy/non-chunked)
                    item.filePath?.let { path ->
                        val file = File(path)
                        if (file.exists()) {
                            SecureWipe.wipe(file)
                        }
                    }

                    // 2. Wipe the entire vault folder (standard for this app)
                    val vaultFolder = File(item.vaultFolder)
                    if (vaultFolder.exists()) {
                        SecureWipe.wipeVaultFolder(vaultFolder)
                    }

                    // 3. Delete from DB
                    dao.deleteById(item.id)
                }
            }

            Result.success()
        } catch (e: Exception) {
            Result.retry()
        }
    }

    companion object {
        private const val WORK_NAME = "trash_cleanup_work"

        fun enqueue(context: Context) {
            val request = PeriodicWorkRequestBuilder<TrashCleanupWorker>(
                1, TimeUnit.DAYS
            ).build()

            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                WORK_NAME,
                ExistingPeriodicWorkPolicy.KEEP,
                request
            )
        }
    }
}
