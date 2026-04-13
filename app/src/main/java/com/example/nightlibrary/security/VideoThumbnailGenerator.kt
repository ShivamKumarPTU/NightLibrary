package com.example.nightlibrary.security

import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.os.Build
import android.provider.MediaStore
import android.util.Log
import android.media.ThumbnailUtils
import java.io.File
import java.io.FileOutputStream
import java.util.UUID

object VideoThumbnailGenerator {

    private const val TAG = "VaultThumbGen"

    fun generateThumbnail(
        videoFile: File,
        cacheDir: File
    ): File? {

        if (!videoFile.exists()) {
            Log.e(TAG, "Video file does not exist")
            return null
        }

        try {

            val bitmap: Bitmap? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {

                Log.d(TAG, "Using ThumbnailUtils (Android Q+)")

                ThumbnailUtils.createVideoThumbnail(
                    videoFile,
                    android.util.Size(512, 512),
                    null
                )

            } else {

                Log.d(TAG, "Using MediaMetadataRetriever (< Q)")

                val retriever = MediaMetadataRetriever()
                retriever.setDataSource(videoFile.absolutePath)

                val frame = retriever.getFrameAtTime(
                    1_000_000,
                    MediaMetadataRetriever.OPTION_CLOSEST_SYNC
                )

                retriever.release()

                frame
            }

            if (bitmap == null) {
                Log.e(TAG, "Thumbnail bitmap null")
                return null
            }

            val thumbFile = File(
                cacheDir,
                "thumb_${UUID.randomUUID()}.jpg"
            )

            FileOutputStream(thumbFile).use { out ->
                bitmap.compress(Bitmap.CompressFormat.JPEG, 85, out)
            }

            Log.d(TAG, "Thumbnail generated: ${thumbFile.absolutePath}")

            return thumbFile

        } catch (e: Exception) {

            Log.e(TAG, "Thumbnail generation error: ${e.message}")
            return null
        }
    }
}