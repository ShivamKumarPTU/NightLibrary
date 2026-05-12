package com.example.nightlibrary.core.security

import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Log
import androidx.core.content.FileProvider
import java.util.concurrent.ConcurrentHashMap

/**
 * ✅ Custom FileProvider that caches metadata lookups.
 *
 * PROBLEM: When sharing N files, the system + receiving app call
 * query() on EACH URI multiple times (for MIME type, size, name).
 * Default FileProvider does a disk stat() call every time.
 * With 50 files, this means 150+ disk operations on the main thread.
 *
 * FIX: Cache the results after the first lookup.
 * The cache is cleared when files are deleted.
 */
class CachingFileProvider : FileProvider() {

    companion object {
        private const val TAG = "CachingFileProvider"

        // URI path → cached metadata
        private val metadataCache = ConcurrentHashMap<String, CachedMetadata>()

        data class CachedMetadata(
            val displayName: String,
            val size: Long,
            val mimeType: String
        )

        /**
         * Pre-populate cache before launching share intent.
         * Call this for each file you're about to share.
         */
        fun preCache(uri: Uri, name: String, size: Long, mimeType: String) {
            val key = uri.path ?: return
            metadataCache[key] = CachedMetadata(name, size, mimeType)
            Log.d(TAG, "Pre-cached: $name ($size bytes)")
        }

        /**
         * Clear the cache (call after share completes or files are deleted).
         */
        fun clearCache() {
            metadataCache.clear()
        }
    }

    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?
    ): Cursor {
        val key = uri.path

        // ✅ Try cache first
        val cached = key?.let { metadataCache[it] }
        if (cached != null) {
            val cols = projection ?: arrayOf(
                OpenableColumns.DISPLAY_NAME,
                OpenableColumns.SIZE
            )
            val cursor = MatrixCursor(cols)
            val row = arrayOfNulls<Any>(cols.size)
            cols.forEachIndexed { i, col ->
                row[i] = when (col) {
                    OpenableColumns.DISPLAY_NAME -> cached.displayName
                    OpenableColumns.SIZE -> cached.size
                    else -> null
                }
            }
            cursor.addRow(row)
            return cursor
        }

        // Fallback to default FileProvider behavior
        return super.query(uri, projection, selection, selectionArgs, sortOrder)!!
    }

    override fun getType(uri: Uri): String? {
        val key = uri.path
        val cached = key?.let { metadataCache[it] }
        if (cached != null) return cached.mimeType

        return super.getType(uri)
    }
}
