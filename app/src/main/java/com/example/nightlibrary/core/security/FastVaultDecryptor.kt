package com.example.nightlibrary.core.security

import android.util.Base64
import android.util.Log
import com.example.nightlibrary.security.VaultCryptoEngine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import javax.crypto.SecretKey
import kotlin.coroutines.coroutineContext

/**
 * High-speed parallel decryption for vault files.
 *
 * OLD: createDecryptCipher(iv) per chunk = TEE per chunk
 *      TEE hardware serializes → parallelism was fake
 *      250 chunks = 250 TEE calls = 2-5 minutes
 *
 * NEW: unwrapDek() once (1 TEE call) → createSoftDecryptCipher() per chunk
 *      Software AES is truly parallel across cores
 *      250 chunks × 6 parallel = ~5-15 seconds for 1GB
 */
object FastVaultDecryptor {

    private const val TAG = "FastDecryptor"

    private val PARALLELISM by lazy {
        Runtime.getRuntime().availableProcessors().coerceIn(2, 6)
    }

    /**
     * Decrypts a vault folder to an output file as fast as possible.
     * Handles both single-file (full_image.enc) and chunked formats.
     * Envelope-aware: uses software AES when wrappedKey exists in index.json.
     */
    suspend fun decryptToFile(
        vaultFolder: File,
        outFile: File,
        onProgress: suspend (Int) -> Unit
    ) = withContext(Dispatchers.IO) {

        val startTime = System.currentTimeMillis()
        val crypto = VaultCryptoEngine()

        // ── Single file format ───────────────────────────────────────
        val singleFile = File(vaultFolder, "full_image.enc")
        if (singleFile.exists()) {
            coroutineContext.ensureActive()
            decryptSingleFileFast(singleFile, outFile, crypto)
            onProgress(100)
            val elapsed = System.currentTimeMillis() - startTime
            Log.d(TAG, "Single-file decrypt: ${elapsed}ms")
            return@withContext
        }

        // ── Chunked format ───────────────────────────────────────────
        val chunks = vaultFolder
            .listFiles { f ->
                f.name.startsWith("chunk_") && f.name.endsWith(".enc")
            }
            ?.sortedBy {
                it.name.removePrefix("chunk_")
                    .removeSuffix(".enc")
                    .toIntOrNull() ?: 0
            }
            ?: throw IllegalStateException(
                "No encrypted data in ${vaultFolder.absolutePath}"
            )

        if (chunks.isEmpty()) {
            throw IllegalStateException("No chunks found")
        }

        // ── ✅ FIX: Envelope check — 1 TEE call instead of 250 ──────
        var envelopeDek: SecretKey? = null
        val indexFile = File(vaultFolder, "index.json")

        if (indexFile.exists()) {
            try {
                val index = ChunkIndexReader().readIndex(vaultFolder)
                if (index.wrappedKey != null && index.keyIv != null) {
                    val wrappedBytes = Base64.decode(index.wrappedKey, Base64.NO_WRAP)
                    val ivBytes = Base64.decode(index.keyIv, Base64.NO_WRAP)
                    envelopeDek = crypto.unwrapDek(wrappedBytes, ivBytes)
                    Log.d(TAG, "✅ Envelope mode: 1 TEE call → software for ${chunks.size} chunks")
                } else {
                    Log.d(TAG, "Legacy mode: TEE per chunk (old file, re-import for speed)")
                }
            } catch (e: Exception) {
                Log.w(TAG, "Index read failed, falling back to legacy: ${e.message}")
            }
        }

        Log.d(
            TAG,
            "Parallel decrypt: ${chunks.size} chunks, " +
                    "parallelism=$PARALLELISM, envelope=${envelopeDek != null}"
        )

        val totalEncSize = chunks.sumOf { it.length() }
        var processedEncSize = 0L

        // Capture for use inside lambdas
        val dek = envelopeDek

        outFile.parentFile?.mkdirs()

        FileOutputStream(outFile).channel.use { outChannel ->

            chunks.chunked(PARALLELISM).forEach { batch ->
                coroutineContext.ensureActive()

                // ── Phase 1: Parallel read + decrypt ─────────────────
                val decryptedBatch = coroutineScope {
                    batch.map { chunkFile ->
                        async(Dispatchers.IO) {
                            val encBytes = chunkFile.readBytes()

                            if (encBytes.size < 17) {
                                Log.w(TAG, "Skipping tiny chunk: ${chunkFile.name}")
                                return@async Pair(ByteArray(0), encBytes.size.toLong())
                            }

                            val iv = encBytes.copyOfRange(0, 16)

                            val plaintext = if (dek != null) {
                                // ✅ SOFTWARE: truly parallel, ~0.02ms init per chunk
                                crypto.createSoftDecryptCipher(dek, iv)
                                    .doFinal(encBytes, 16, encBytes.size - 16)
                            } else {
                                // LEGACY: TEE per chunk (hardware serialized)
                                crypto.createDecryptCipher(iv)
                                    .doFinal(encBytes, 16, encBytes.size - 16)
                            }

                            Pair(plaintext, encBytes.size.toLong())
                        }
                    }.map { it.await() } // Await IN ORDER — preserves chunk sequence
                }

                // ── Phase 2: Sequential write (maintains order) ──────
                for ((plaintext, encSize) in decryptedBatch) {
                    coroutineContext.ensureActive()

                    if (plaintext.isNotEmpty()) {
                        outChannel.write(ByteBuffer.wrap(plaintext))
                    }

                    processedEncSize += encSize
                    if (totalEncSize > 0) {
                        val pct = ((processedEncSize * 100L) / totalEncSize)
                            .toInt()
                            .coerceAtMost(99)
                        onProgress(pct)
                    }
                }
            }

            outChannel.force(true)
        }

        // ── Verify ───────────────────────────────────────────────────
        if (!outFile.exists() || outFile.length() == 0L) {
            throw Exception("Decryption produced empty file")
        }

        onProgress(100)

        val elapsed = System.currentTimeMillis() - startTime
        val sizeMB = outFile.length() / (1024.0 * 1024.0)
        Log.d(
            TAG,
            "✅ Parallel decrypt complete: " +
                    "%.1fMB in ${elapsed}ms (%.1f MB/s) envelope=${envelopeDek != null}".format(
                        sizeMB,
                        sizeMB / (elapsed / 1000.0).coerceAtLeast(0.001)
                    )
        )
    }

    /**
     * Fast single-file decryption using NIO.
     * Single file = 1 TEE call regardless, so no envelope needed.
     */
    private fun decryptSingleFileFast(
        encFile: File,
        outFile: File,
        crypto: VaultCryptoEngine
    ) {
        val encBytes = encFile.readBytes()

        if (encBytes.size < 17) {
            throw Exception("Encrypted file too small: ${encBytes.size} bytes")
        }

        val iv = encBytes.copyOfRange(0, 16)
        val plaintext = crypto.createDecryptCipher(iv)
            .doFinal(encBytes, 16, encBytes.size - 16)

        outFile.parentFile?.mkdirs()

        FileOutputStream(outFile).channel.use { ch ->
            ch.write(ByteBuffer.wrap(plaintext))
            ch.force(true)
        }

        if (!outFile.exists() || outFile.length() == 0L) {
            throw Exception("Single-file decryption produced empty output")
        }
    }
}