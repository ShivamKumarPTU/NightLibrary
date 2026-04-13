package com.example.nightlibrary.security

import android.util.Log
import com.example.nightlibrary.entity.MediaEntity
import java.io.File

object ChunkIntegrityChecker {

    private const val TAG = "VaultIntegrity"

    fun verifyMedia(media: MediaEntity): Boolean {

        try {

            val folder = File(media.vaultFolder)

            if (!folder.exists()) {

                Log.e(TAG, "Vault folder missing: ${media.vaultFolder}")
                return false
            }

            for (i in 0 until media.chunkCount) {

                val chunk = File(folder, "chunk_$i.enc")

                if (!chunk.exists()) {

                    Log.e(TAG, "Missing chunk: chunk_$i.enc")
                    return false
                }

                if (chunk.length() == 0L) {

                    Log.e(TAG, "Corrupted chunk size=0: chunk_$i.enc")
                    return false
                }
            }

            Log.d(TAG, "Integrity verified for ${media.fileName}")

            return true

        } catch (e: Exception) {

            Log.e(TAG, "Integrity check error ${e.message}")
            return false
        }
    }
}