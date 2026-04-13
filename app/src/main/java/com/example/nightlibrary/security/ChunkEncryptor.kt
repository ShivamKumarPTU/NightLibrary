package com.example.nightlibrary.security

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Base64
import android.util.Log
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream

class ChunkEncryptor(
    private val context: Context,
    private val crypto: VaultCryptoEngine
) {

    companion object {
        private const val TAG      = "ChunkEncryptor"
        private const val READ_BUF = 1024 * 1024
    }

    fun encryptStream(
        uri: Uri,
        outputDir: File,
        chunkSize: Int
    ): ChunkIndex = encryptStreamWithProgress(uri, outputDir, chunkSize, onProgress = null)

    fun encryptStreamWithProgress(
        uri: Uri,
        outputDir: File,
        chunkSize: Int,
        onProgress: ((percent: Int) -> Unit)?
    ): ChunkIndex {

        val totalSize: Long = getSourceSize(uri)

        val dek = crypto.generateDek()
        val (wrappedKeyBytes, keyIvBytes) = crypto.wrapDek(dek)
        Log.d(TAG, "DEK generated + wrapped (2 TEE calls done)")

        val wrappedKeyB64 = Base64.encodeToString(wrappedKeyBytes, Base64.NO_WRAP)
        val keyIvB64      = Base64.encodeToString(keyIvBytes, Base64.NO_WRAP)

        var chunkIndex   = 0
        var totalBytes   = 0L
        var lastReported = -1

        val readBuf  = ByteArray(READ_BUF)
        val chunkBuf = ByteArray(chunkSize)

        val inputStream = if (uri.scheme == "file") {
            FileInputStream(File(uri.path!!))
        } else {
            context.contentResolver.openInputStream(uri)
        }

        inputStream?.use { input ->

            while (true) {

                var chunkRead = 0

                while (chunkRead < chunkSize) {
                    val want = minOf(readBuf.size, chunkSize - chunkRead)
                    val n    = input.read(readBuf, 0, want)
                    if (n == -1) break

                    System.arraycopy(readBuf, 0, chunkBuf, chunkRead, n)
                    chunkRead  += n
                    totalBytes += n

                    if (onProgress != null && totalSize > 0) {
                        val pct = ((totalBytes * 100L) / totalSize).toInt().coerceIn(1, 98)
                        if (pct != lastReported) {
                            onProgress(pct)
                            lastReported = pct
                        }
                    }
                }

                if (chunkRead == 0) break

                val cipher     = crypto.createSoftEncryptCipher(dek)
                val iv         = cipher.iv
                val ciphertext = cipher.doFinal(chunkBuf, 0, chunkRead)

                val chunkFile = File(outputDir, "chunk_$chunkIndex.enc")
                BufferedOutputStream(FileOutputStream(chunkFile), 256 * 1024).use { bos ->
                    bos.write(iv)
                    bos.write(ciphertext)
                }

                Log.d(TAG, "Chunk $chunkIndex written=$chunkRead")
                chunkIndex++

                if (chunkRead < chunkSize) break
            }
        }

        val index = ChunkIndex(
            chunkCount    = chunkIndex,
            chunkSize     = chunkSize,
            totalFileSize = totalBytes,
            wrappedKey    = wrappedKeyB64,
            keyIv         = keyIvB64
        )

        saveIndex(outputDir, index)
        Log.d(TAG, "Encryption done chunks=$chunkIndex total=$totalBytes")
        return index
    }

    private fun getSourceSize(uri: Uri): Long {
        if (uri.scheme == "file") {
            return try { File(uri.path ?: "").length() } catch (_: Exception) { -1L }
        }
        return try {
            context.contentResolver.query(
                uri, arrayOf(OpenableColumns.SIZE), null, null, null
            )?.use { cursor ->
                if (cursor.moveToFirst()) cursor.getLong(0) else -1L
            } ?: -1L
        } catch (_: Exception) { -1L }
    }

    private fun saveIndex(folder: File, index: ChunkIndex) {
        val wrappedLine = if (index.wrappedKey != null)
            ""","wrappedKey":"${index.wrappedKey}"""" else ""
        val keyIvLine = if (index.keyIv != null)
            ""","keyIv":"${index.keyIv}"""" else ""

        File(folder, "index.json").writeText(
            """{"chunkCount":${index.chunkCount},"chunkSize":${index.chunkSize},"totalFileSize":${index.totalFileSize}$wrappedLine$keyIvLine}"""
        )
    }
}