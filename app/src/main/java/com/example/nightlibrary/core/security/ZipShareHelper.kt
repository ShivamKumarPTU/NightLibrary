package com.example.nightlibrary.core.security

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.coroutines.coroutineContext

/**
 * Creates a single ZIP file from multiple decrypted files.
 *
 * WHY: Android's share system performs per-URI:
 *   • Permission grants (security handshake)
 *   • ContentResolver metadata queries
 *   • Thumbnail generation
 *   • Binder IPC transactions
 *   • FileProvider path resolution
 *
 * By zipping N files into 1, we reduce ALL of these to a single operation.
 *
 * The ZIP is created with STORED (no compression) for media files
 * since videos/images/audio are already compressed. This makes
 * zipping nearly as fast as a file copy.
 */
object ZipShareHelper {

    private const val TAG = "ZipShareHelper"

    /**
     * Threshold: if sharing more than this many files, auto-zip.
     * For 1-2 files, the system handles it fine.
     * For 3+, the slowdown becomes very noticeable.
     */
    const val AUTO_ZIP_THRESHOLD = 3

    /**
     * Creates a ZIP file from the given decrypted files.
     *
     * @param files     List of decrypted temp files to zip
     * @param names     Display names for each file inside the ZIP
     * @param outputDir Directory to create the ZIP in
     * @param onProgress Callback with overall progress (0-100)
     * @return The created ZIP file
     */
    suspend fun createZip(
        files: List<File>,
        names: List<String>,
        outputDir: File,
        onProgress: suspend (Int) -> Unit = {}
    ): File = withContext(Dispatchers.IO) {

        val zipFile = File(
            outputDir,
            "NightLibrary_${System.currentTimeMillis()}.zip"
        )

        val totalBytes = files.sumOf { it.length() }
        var writtenBytes = 0L

        // Track used names to avoid duplicates inside ZIP
        val usedNames = mutableSetOf<String>()

        ZipOutputStream(
            BufferedOutputStream(FileOutputStream(zipFile), 256 * 1024)
        ).use { zos ->

            // ✅ Use STORED (no compression) for media files
            // Videos, images, audio are already compressed.
            // STORED = just copy bytes = nearly disk-speed.
            // DEFLATED would waste CPU trying to compress an MP4.

            for ((index, file) in files.withIndex()) {
                coroutineContext.ensureActive()

                if (!file.exists() || file.length() == 0L) {
                    Log.w(TAG, "Skipping empty/missing file: ${file.name}")
                    continue
                }

                // Deduplicate names
                val baseName = names.getOrElse(index) { file.name }
                val uniqueName = deduplicateName(baseName, usedNames)
                usedNames.add(uniqueName)

                val entry = ZipEntry(uniqueName).apply {
                    // STORED = no compression (fast for media)
                    method = ZipEntry.STORED
                    size = file.length()
                    compressedSize = file.length()
                    crc = computeCrc32(file)
                }

                zos.putNextEntry(entry)

                // Stream file into ZIP with progress
                val buffer = ByteArray(128 * 1024) // 128KB buffer
                FileInputStream(file).use { fis ->
                    var read: Int
                    while (fis.read(buffer).also { read = it } > 0) {
                        coroutineContext.ensureActive()
                        zos.write(buffer, 0, read)
                        writtenBytes += read

                        if (totalBytes > 0) {
                            val pct = ((writtenBytes * 100L) / totalBytes)
                                .toInt().coerceIn(0, 99)
                            onProgress(pct)
                        }
                    }
                }

                zos.closeEntry()
                Log.d(TAG, "Added to ZIP: $uniqueName (${file.length()} bytes)")
            }
        }

        onProgress(100)
        Log.d(TAG, "✅ ZIP created: ${zipFile.name} " +
                "(${zipFile.length()} bytes, ${files.size} files)")

        zipFile
    }

    /**
     * Computes CRC32 for STORED ZIP entries.
     * Required when method = STORED.
     */
    private fun computeCrc32(file: File): Long {
        val crc = java.util.zip.CRC32()
        val buffer = ByteArray(64 * 1024)
        FileInputStream(file).use { fis ->
            var read: Int
            while (fis.read(buffer).also { read = it } > 0) {
                crc.update(buffer, 0, read)
            }
        }
        return crc.value
    }

    /**
     * Ensures unique file names inside the ZIP.
     * "video.mp4" → "video.mp4", "video (2).mp4", "video (3).mp4"
     */
    private fun deduplicateName(name: String, used: Set<String>): String {
        if (name !in used) return name

        val base = name.substringBeforeLast('.')
        val ext = name.substringAfterLast('.', "")
        var counter = 2

        while (true) {
            val candidate = if (ext.isNotEmpty()) {
                "$base ($counter).$ext"
            } else {
                "$base ($counter)"
            }
            if (candidate !in used) return candidate
            counter++
        }
    }
}
