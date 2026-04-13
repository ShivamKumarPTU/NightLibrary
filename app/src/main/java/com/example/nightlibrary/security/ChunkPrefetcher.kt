// ════════════════════════════════════════════════════════════════════════════
// FILE 5/5  ▸  ChunkPrefetcher.kt
// DESTINATION: java/com/example/nightlibrary/security/ChunkPrefetcher.kt
//              (replaces existing file — package stays the same)
//
// WHY THIS MUST CHANGE:
//   The old ChunkPrefetcher used CipherInputStream.readBytes() to decrypt,
//   then stored the result in ChunkCache. But it stored bytes read THROUGH
//   CipherInputStream from an already-decrypted or half-decrypted stream.
//   Now that ChunkCache stores plaintext (decrypted bytes) and
//   ChunkedEncryptedDataSource calls cipher.doFinal() expecting to decrypt,
//   the prefetcher MUST also call cipher.doFinal() and store plaintext.
//   If it stored encrypted bytes, the DataSource would try to "decrypt" them
//   again → garbage output → video corruption or crash.
// ════════════════════════════════════════════════════════════════════════════
package com.example.nightlibrary.security

import android.util.Log
import java.io.File
import java.io.FileInputStream

object ChunkPrefetcher {

    private const val TAG           = "VaultChunkPrefetcher"
    private const val PREFETCH_COUNT = 2

    fun prefetchChunks(
        vaultFolder: File,
        startChunk: Int,
        chunkCount: Int,
        cryptoEngine: VaultCryptoEngine
    ) {
        for (i in 1..PREFETCH_COUNT) {
            val nextChunk = startChunk + i
            if (nextChunk >= chunkCount) return

            // Already cached → nothing to do
            if (ChunkCache.get(nextChunk) != null) {
                Log.d(TAG, "Prefetch skipped (cached) chunk=$nextChunk")
                continue
            }

            try {
                val chunkFile = File(vaultFolder, "chunk_$nextChunk.enc")
                if (!chunkFile.exists()) {
                    Log.d(TAG, "Prefetch chunk missing: $nextChunk")
                    continue
                }

                Log.d(TAG, "Prefetch loading chunk=$nextChunk")

                val fileBytes = FileInputStream(chunkFile).use { it.readBytes() }

                // doFinal() ONCE → plaintext. Must match what ChunkedEncryptedDataSource
                // expects when it pulls from ChunkCache (decrypted bytes, not encrypted).
                val iv        = fileBytes.copyOfRange(0, 16)
                val cipher    = cryptoEngine.createDecryptCipher(iv)
                val decrypted = cipher.doFinal(fileBytes, 16, fileBytes.size - 16)

                ChunkCache.put(nextChunk, decrypted)
                Log.d(TAG, "Prefetch stored chunk=$nextChunk")

            } catch (e: Exception) {
                Log.d(TAG, "Prefetch failed chunk=$nextChunk: ${e.message}")
            }
        }
    }
}