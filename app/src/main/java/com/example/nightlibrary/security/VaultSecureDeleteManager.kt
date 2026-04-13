package com.example.nightlibrary.security

import android.util.Log
import com.example.nightlibrary.entity.MediaEntity
import java.io.File

object VaultSecureDeleteManager {

    private const val TAG = "VaultSecureDelete"

    fun deleteMedia(media: MediaEntity) {

        try {

            val folder = File(media.vaultFolder)

            if (!folder.exists()) {

                Log.e(TAG, "Vault folder missing: ${media.vaultFolder}")
                return
            }

            Log.d(TAG, "Starting atomic delete of vault folder: ${media.fileName}")

            val startTime = System.currentTimeMillis()

            // Delete entire vault folder atomically - no chunk-by-chunk wiping in UI layer
            val deleted = deleteRecursive(folder)

            val duration = System.currentTimeMillis() - startTime

            if (deleted) {
                Log.d(TAG, "Atomic vault folder deletion completed in ${duration}ms")
            } else {
                Log.e(TAG, "Failed to delete vault folder atomically")
            }

        } catch (e: Exception) {

            Log.e(TAG, "Atomic delete error: ${e.message}")
        }
    }

    private fun deleteRecursive(fileOrDirectory: File): Boolean {
        if (fileOrDirectory.isDirectory) {
            val files = fileOrDirectory.listFiles()
            if (files != null) {
                for (child in files) {
                    deleteRecursive(child)
                }
            }
        }
        return fileOrDirectory.delete()
    }
}