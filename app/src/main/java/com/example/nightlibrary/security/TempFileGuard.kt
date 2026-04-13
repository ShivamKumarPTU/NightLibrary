package com.example.nightlibrary.security

import android.content.Context
import android.util.Log

/**
 * Cleans up orphaned plaintext download files on app start.
 * These files exist if the app was killed during a download.
 */
class TempFileGuard(private val context: Context) {

    companion object {
        private const val TAG = "TempFileGuard"
        private const val MAX_TEMP_AGE_MS = 60 * 60 * 1000L // 1 hour
    }

    fun cleanOrphanedTempFiles() {
        var cleaned = 0

        // Clean download temp files
        context.cacheDir.listFiles()?.forEach { file ->
            if (file.isFile && file.name.startsWith("dl_")) {
                val age = System.currentTimeMillis() - file.lastModified()
                if (age > MAX_TEMP_AGE_MS) {
                    // Overwrite with zeros before deleting (security)
                    try {
                        val size = file.length()
                        if (size > 0 && size < 500 * 1024 * 1024) { // Only wipe < 500MB
                            file.outputStream().use { out ->
                                val zeros = ByteArray(64 * 1024)
                                var remaining = size
                                while (remaining > 0) {
                                    val chunk = minOf(remaining, zeros.size.toLong()).toInt()
                                    out.write(zeros, 0, chunk)
                                    remaining -= chunk
                                }
                            }
                        }
                    } catch (_: Exception) {}
                    file.delete()
                    cleaned++
                }
            }
        }

        // Clean HLS segment directories
        context.cacheDir.listFiles()?.forEach { dir ->
            if (dir.isDirectory && dir.name.startsWith("hls_segments_")) {
                dir.deleteRecursively()
                cleaned++
            }
        }

        if (cleaned > 0) {
            Log.d(TAG, "Cleaned $cleaned orphaned temp file(s)")
        }
    }
}