package com.example.nightlibrary.worker

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import androidx.media3.common.MediaItem
import androidx.media3.exoplayer.ExoPlayer
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.example.nightlibrary.database.VaultDatabase
import com.example.nightlibrary.security.VaultCryptoEngine
import java.io.File
import java.io.FileOutputStream
import java.util.UUID
import javax.crypto.CipherInputStream

class ThumbnailWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    private val TAG = "VaultThumbFinal"

    override suspend fun doWork(): Result {
        try {
            val mediaId = inputData.getLong("mediaId", -1)
            val db = VaultDatabase.getDatabase(applicationContext)
            val media = db.mediaDao().getById(mediaId) ?: return Result.failure()

            val vaultFolder = File(media.vaultFolder)
            val tempVideo = File(applicationContext.cacheDir, "thumb_${UUID.randomUUID()}.mp4")

            Log.d(TAG, "Decrypting first chunk for Exo frame")

            val chunk = File(vaultFolder, "chunk_0.enc")
            val crypto = VaultCryptoEngine()

            chunk.inputStream().use { input ->
                val iv = ByteArray(16)
                input.read(iv)
                val cipher = crypto.createDecryptCipher(iv)
                val cis = CipherInputStream(input, cipher)
                tempVideo.outputStream().use { cis.copyTo(it) }
            }

            val retriever = android.media.MediaMetadataRetriever()
            retriever.setDataSource(tempVideo.absolutePath)
            val frame = retriever.getFrameAtTime(0)
            retriever.release()

            if (frame == null) {
                Log.e(TAG, "Frame decode failed → fallback default")
                tempVideo.delete()
                return Result.failure()
            }

            val thumb = File(applicationContext.cacheDir, "thumb.jpg")
            FileOutputStream(thumb).use {
                frame.compress(Bitmap.CompressFormat.JPEG, 85, it)
            }

            db.mediaDao().update(media.copy(thumbnailPath = thumb.absolutePath))

            tempVideo.delete()

            Log.d(TAG, "Thumbnail generated OK")
            return Result.success()

        } catch (e: Exception) {
            Log.e(TAG, "ThumbnailWorker crash ${e.message}")
            return Result.failure()
        }
    }
}