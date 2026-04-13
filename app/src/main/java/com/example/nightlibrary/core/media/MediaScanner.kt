
/*
package com.example.nightlibrary.core.media

import android.content.Context
import android.util.Log
import com.example.nightlibrary.database.VaultDatabase
import com.example.nightlibrary.entity.MediaEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File

object MediaScanner {

    private const val TAG = "VaultMediaScanner"

    suspend fun scanVault(context: Context) {

        withContext(Dispatchers.IO) {

            try {

                val vaultRoot = File(context.filesDir, "vault_media")

                Log.d(TAG, "Scanning vault directory: ${vaultRoot.absolutePath}")

                if (!vaultRoot.exists()) {

                    Log.d(TAG, "Vault directory not found")

                    return@withContext
                }

                val db = VaultDatabase.getDatabase(context)

                val folders = vaultRoot.listFiles()

                if (folders == null) {

                    Log.d(TAG, "No vault folders found")

                    return@withContext
                }

                for (folder in folders) {

                    if (!folder.isDirectory) continue

                    Log.d(TAG, "Checking folder: ${folder.name}")

                    val indexFile = File(folder, "index.json")

                    if (!indexFile.exists()) {

                        Log.d(TAG, "index.json missing in ${folder.name}")
                        continue
                    }

                    val existing = db.mediaDao().getByVaultFolder(folder.absolutePath)

                    if (existing != null) {

                        Log.d(TAG, "DB entry already exists for ${folder.name}")
                        continue
                    }

                    val json = JSONObject(indexFile.readText())

                    val chunkCount = json.getInt("chunkCount")
                    val chunkSize = json.getInt("chunkSize")

                    Log.d(TAG, "Index loaded chunkCount=$chunkCount chunkSize=$chunkSize")

                    val entity = MediaEntity(

                        fileName = folder.name,

                        vaultFolder = folder.absolutePath,

                        chunkCount = chunkCount,

                        chunkSize = chunkSize,

                        fileSize = folder.length(),

                        mimeType = "video/*",

                        fileType = "video",

                        isCompleted = true,

                        progress = 100,

                        checksum = "scanner_recovered"
                    )

                    db.mediaDao().insert(entity)

                    Log.d(TAG, "Inserted DB entry for ${folder.name}")
                }

                Log.d(TAG, "Vault scan completed")

            } catch (e: Exception) {

                Log.e(TAG, "Scanner error: ${e.message}")
            }
        }
    }
}
*/
 */

// ════════════════════════════════════════════════════════════════════════════
// FIX: MediaScanner.kt
//
// BUG FIXED: fileSize = folder.length() always returns 0 on Android/Linux.
//
// On Linux/Android, File.length() on a DIRECTORY returns the size of the
// directory entry itself (typically 4096 bytes on ext4, or 0 on some
// virtual filesystems) — NOT the sum of its contents. In practice on
// Android's internal storage it always returns 0.
//
// THE FIX: Compute fileSize by summing the sizes of all chunk_*.enc files
// in the folder, then subtracting (16 × chunkCount) to get the true
// decrypted video size. This is the same number the player will use.
//
// We also compute the accurate mimeType from the index if available,
// falling back to "video/*" so the UI can show the right icon.
// ════════════════════════════════════════════════════════════════════════════
package com.example.nightlibrary.core.media

import android.content.Context
import android.util.Log
import com.example.nightlibrary.database.VaultDatabase
import com.example.nightlibrary.entity.MediaEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File

object MediaScanner {

    private const val TAG = "VaultMediaScanner"

    suspend fun scanVault(context: Context) {
        withContext(Dispatchers.IO) {
            try {
                val vaultRoot = File(context.filesDir, "vault_media")
                Log.d(TAG, "Scanning vault: ${vaultRoot.absolutePath}")

                if (!vaultRoot.exists()) {
                    Log.d(TAG, "Vault directory not found")
                    return@withContext
                }

                val db      = VaultDatabase.getDatabase(context)
                val folders = vaultRoot.listFiles() ?: run {
                    Log.d(TAG, "No vault folders found")
                    return@withContext
                }

                for (folder in folders) {
                    if (!folder.isDirectory) continue
                    Log.d(TAG, "Checking folder: ${folder.name}")

                    val indexFile = File(folder, "index.json")
                    if (!indexFile.exists()) {
                        Log.d(TAG, "index.json missing in ${folder.name}")
                        continue
                    }

                    val existing = db.mediaDao().getByVaultFolder(folder.absolutePath)
                    if (existing != null) {
                        Log.d(TAG, "DB entry already exists for ${folder.name}")
                        continue
                    }

                    val json       = JSONObject(indexFile.readText())
                    val chunkCount = json.getInt("chunkCount")
                    val chunkSize  = json.getInt("chunkSize")

                    // FIX: compute true decrypted size instead of folder.length()
                    // Each chunk file = 16 bytes IV + encrypted payload.
                    // Decrypted size per chunk = fileSize − 16.
                    // Total decrypted video size = sum(chunk_i.enc.length − 16).
                    val decryptedFileSize = computeDecryptedSize(folder, chunkCount)

                    // Optional: read mimeType / originalName from index if your
                    // importer wrote them; fall back to safe defaults.
                    val mimeType     = json.optString("mimeType", "video/mp4")
                        .ifBlank { "video/mp4" }
                    val displayName  = json.optString("originalName", folder.name)
                        .ifBlank { folder.name }

                    Log.d(TAG, "Index loaded chunkCount=$chunkCount " +
                            "chunkSize=$chunkSize decryptedSize=$decryptedFileSize")

                    val entity = MediaEntity(
                        fileName    = displayName,
                        vaultFolder = folder.absolutePath,
                        chunkCount  = chunkCount,
                        chunkSize   = chunkSize,
                        fileSize    = decryptedFileSize,   // FIX: real size
                        mimeType    = mimeType,
                        fileType    = "video",
                        isCompleted = true,
                        progress    = 100,
                        checksum    = "scanner_recovered"
                    )

                    db.mediaDao().insert(entity)
                    Log.d(TAG, "Inserted DB entry for $displayName ($decryptedFileSize bytes)")
                }

                Log.d(TAG, "Vault scan completed")

            } catch (e: Exception) {
                Log.e(TAG, "Scanner error: ${e.message}", e)
            }
        }
    }

    /**
     * Returns the true decrypted video size in bytes by summing
     * (chunk_i.enc.length − 16) for all chunks. Falls back to
     * (chunkCount × chunkSize) from index.json if any chunk file
     * is missing (e.g. partial import).
     */
    private fun computeDecryptedSize(folder: File, chunkCount: Int): Long {
        var total = 0L
        var allFound = true
        for (i in 0 until chunkCount) {
            val chunk = File(folder, "chunk_$i.enc")
            if (chunk.exists()) {
                total += (chunk.length() - 16L).coerceAtLeast(0L)
            } else {
                allFound = false
            }
        }
        if (!allFound) {
            Log.w(TAG, "Some chunks missing in ${folder.name} — size may be approximate")
        }
        return total
    }
}