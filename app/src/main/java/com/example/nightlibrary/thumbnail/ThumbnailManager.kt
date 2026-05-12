package com.example.nightlibrary.thumbnail

import android.content.Context
import android.graphics.Bitmap
import android.media.ThumbnailUtils
import android.os.Build
import android.provider.MediaStore
import android.util.Size
import com.example.nightlibrary.entity.MediaEntity
import java.io.File
import java.io.FileOutputStream

@Suppress("NULLABILITY_MISMATCH_BASED_ON_JAVA_ANNOTATIONS")
class ThumbnailManager(private val context: Context) {

    fun generateThumbnail(media: MediaEntity): File {
        val source = File(media.filePath)
        val thumbnailFile = File(context.cacheDir, "thumb_${media.id}.jpg")

        // FIX: Modern approach for API 29+ using Size
        val bitmap: Bitmap? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            try {
                ThumbnailUtils.createVideoThumbnail(source, Size(512, 384), null)
            } catch (e: Exception) {
                null
            }
        } else {
            // Fallback for older versions if you ever lower minSdk
            @Suppress("DEPRECATION")
            ThumbnailUtils.createVideoThumbnail(
                source.absolutePath,
                MediaStore.Video.Thumbnails.MINI_KIND
            )
        }

        bitmap?.let { bmp ->
            FileOutputStream(thumbnailFile).use { out ->
                // FIX: Added the missing CompressFormat parameter
                bmp.compress(Bitmap.CompressFormat.JPEG, 70, out)
            }
        }

        return thumbnailFile
    }
}