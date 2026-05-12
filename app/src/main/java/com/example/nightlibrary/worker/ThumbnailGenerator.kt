package com.example.nightlibrary.worker

import android.content.Context
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.media.ThumbnailUtils
import android.os.Build
import android.util.Log
import java.io.File
import java.io.FileOutputStream
import java.util.*
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ThumbnailGenerator @Inject constructor(
    private val context: Context
) {
    companion object {
        private const val TAG = "ThumbnailGenerator"
        private const val MIN_VALID_FILE_SIZE = 1024L
    }

    fun generate(file: File): String? {
        if (!file.exists() || file.length() < MIN_VALID_FILE_SIZE) {
            Log.w(TAG, "Thumbnail: file invalid or too small")
            return null
        }

        val retriever = MediaMetadataRetriever()
        var bitmap: Bitmap? = null

        try {
            retriever.setDataSource(file.absolutePath)
            val durationMs = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                ?.toLongOrNull() ?: 0L
            val durationUs = durationMs * 1000L

            val timestamps = if (durationUs > 0) {
                listOf(durationUs / 4, durationUs / 10, 3_000_000L, 5_000_000L, 1_000_000L, durationUs / 2, 500_000L)
                    .filter { it < durationUs }
            } else {
                listOf(3_000_000L, 5_000_000L, 1_000_000L, 500_000L, 0L)
            }

            for (ts in timestamps) {
                try {
                    bitmap = retriever.getFrameAtTime(ts, MediaMetadataRetriever.OPTION_CLOSEST)
                    if (bitmap != null && !isBitmapBlank(bitmap)) break
                    bitmap?.recycle()
                    bitmap = null
                } catch (_: Exception) {}
            }

            if (bitmap == null) {
                for (ts in timestamps) {
                    try {
                        bitmap = retriever.getFrameAtTime(ts, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                        if (bitmap != null && !isBitmapBlank(bitmap)) break
                        bitmap?.recycle()
                        bitmap = null
                    } catch (_: Exception) {}
                }
            }

            if (bitmap == null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                try {
                    bitmap = ThumbnailUtils.createVideoThumbnail(file, android.util.Size(512, 512), null)
                    if (bitmap != null && isBitmapBlank(bitmap)) {
                        bitmap.recycle()
                        bitmap = null
                    }
                } catch (_: Exception) {}
            }

            if (bitmap == null) {
                try {
                    bitmap = retriever.frameAtTime
                } catch (_: Exception) {}
            }

            if (bitmap == null) return null

            return saveBitmapToFile(bitmap)
        } catch (e: Exception) {
            Log.e(TAG, "Thumbnail error: ${e.message}")
            return null
        } finally {
            try { retriever.release() } catch (_: Exception) {}
            try { bitmap?.recycle() } catch (_: Exception) {}
        }
    }

    private fun isBitmapBlank(bitmap: Bitmap): Boolean {
        return try {
            val width = bitmap.width
            val height = bitmap.height
            if (width < 10 || height < 10) return true
            var totalBrightness = 0L
            var samples = 0
            val stepX = width / 5
            val stepY = height / 5
            for (x in 0 until 5) {
                for (y in 0 until 5) {
                    val px = bitmap.getPixel(x * stepX, y * stepY)
                    val r = (px shr 16) and 0xFF
                    val g = (px shr 8) and 0xFF
                    val b = px and 0xFF
                    totalBrightness += (r + g + b) / 3
                    samples++
                }
            }
            (totalBrightness / samples) < 10
        } catch (_: Exception) { false }
    }

    private fun saveBitmapToFile(bitmap: Bitmap): String? {
        return try {
            val maxDim = 720
            val resized = if (bitmap.width > maxDim || bitmap.height > maxDim) {
                val ratio = minOf(maxDim.toFloat() / bitmap.width, maxDim.toFloat() / bitmap.height)
                val newW = (bitmap.width * ratio).toInt()
                val newH = (bitmap.height * ratio).toInt()
                Bitmap.createScaledBitmap(bitmap, newW, newH, true).also {
                    if (it != bitmap) bitmap.recycle()
                }
            } else {
                bitmap
            }

            val thumbFile = File(context.filesDir, "vault_thumbs/thumb_${UUID.randomUUID()}.jpg")
            thumbFile.parentFile?.mkdirs()

            FileOutputStream(thumbFile).use { out ->
                resized.compress(Bitmap.CompressFormat.JPEG, 85, out)
            }
            if (resized != bitmap) resized.recycle()
            thumbFile.absolutePath
        } catch (e: Exception) {
            Log.e(TAG, "Thumbnail save failed: ${e.message}")
            null
        }
    }
}
