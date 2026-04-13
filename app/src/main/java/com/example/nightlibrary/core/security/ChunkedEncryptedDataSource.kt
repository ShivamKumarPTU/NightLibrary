package com.example.nightlibrary.core.security

import android.net.Uri
import android.util.Base64
import android.util.Log
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.TransferListener
import com.example.nightlibrary.security.ChunkCache
import com.example.nightlibrary.security.VaultCryptoEngine
import java.io.File
import java.io.IOException
import javax.crypto.SecretKey

@UnstableApi
class ChunkedEncryptedDataSource(
    private val vaultFolder: File,
    private val crypto: VaultCryptoEngine
) : DataSource {

    companion object {
        private const val TAG = "ChunkedEncryptedDS"
        private const val IV_SIZE = 16
    }

    private var totalFileSize: Long = 0L
    private var chunkSize: Int = 0
    private var chunkCount: Int = 0

    private var currentPosition: Long = 0L
    private var bytesRemaining: Long = 0L
    private var opened = false

    private var singleFileMode = false
    private var singleFileData: ByteArray? = null

    private var envelopeDek: SecretKey? = null

    @Throws(IOException::class)
    override fun open(dataSpec: DataSpec): Long {
        try {
            val singleFile = File(vaultFolder, "full_image.enc")
            if (singleFile.exists()) {
                return openSingleFile(singleFile, dataSpec)
            }

            val indexFile = File(vaultFolder, "index.json")

            if (indexFile.exists()) {
                val indexReader = ChunkIndexReader()
                val index = indexReader.readIndex(vaultFolder)
                totalFileSize = index.totalFileSize
                chunkSize = index.chunkSize
                chunkCount = index.chunkCount

                if (index.wrappedKey != null && index.keyIv != null) {
                    try {
                        val wrappedBytes = Base64.decode(index.wrappedKey, Base64.NO_WRAP)
                        val ivBytes = Base64.decode(index.keyIv, Base64.NO_WRAP)
                        envelopeDek = crypto.unwrapDek(wrappedBytes, ivBytes)
                        Log.d(TAG, "Envelope mode: DEK unwrapped")
                    } catch (e: Exception) {
                        Log.w(TAG, "Envelope unwrap failed, using legacy: ${e.message}")
                        envelopeDek = null
                    }
                } else {
                    envelopeDek = null
                    Log.d(TAG, "Legacy mode: TEE per chunk")
                }
            } else {
                val chunks = getChunkFiles()
                if (chunks.isEmpty()) {
                    throw IOException("No encrypted data found in ${vaultFolder.name}")
                }
                chunkCount = chunks.size
                val firstChunkSize = chunks[0].length() - IV_SIZE
                chunkSize = firstChunkSize.toInt()
                var total = 0L
                for (chunk in chunks) {
                    total += chunk.length() - IV_SIZE
                }
                totalFileSize = total
                envelopeDek = null
            }

            singleFileMode = false
            currentPosition = dataSpec.position

            bytesRemaining = if (dataSpec.length != C.LENGTH_UNSET.toLong()) {
                dataSpec.length
            } else {
                totalFileSize - currentPosition
            }

            opened = true

            Log.d(TAG, "Opened: totalSize=$totalFileSize, chunks=$chunkCount, " +
                    "chunkSize=$chunkSize, envelope=${envelopeDek != null}")

            return bytesRemaining

        } catch (e: Exception) {
            throw IOException("Failed to open encrypted data source", e)
        }
    }

    private fun openSingleFile(file: File, dataSpec: DataSpec): Long {
        singleFileMode = true

        val encrypted = file.readBytes()
        val iv = encrypted.copyOfRange(0, IV_SIZE)
        val cipher = crypto.createDecryptCipher(iv)
        singleFileData = cipher.doFinal(encrypted, IV_SIZE, encrypted.size - IV_SIZE)

        totalFileSize = singleFileData!!.size.toLong()
        currentPosition = dataSpec.position

        bytesRemaining = if (dataSpec.length != C.LENGTH_UNSET.toLong()) {
            dataSpec.length
        } else {
            totalFileSize - currentPosition
        }

        opened = true
        Log.d(TAG, "Opened single-file: totalSize=$totalFileSize")
        return bytesRemaining
    }

    @Throws(IOException::class)
    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (bytesRemaining == 0L) return C.RESULT_END_OF_INPUT

        return if (singleFileMode) {
            readFromSingleFile(buffer, offset, length)
        } else {
            readFromChunks(buffer, offset, length)
        }
    }

    private fun readFromSingleFile(buffer: ByteArray, offset: Int, length: Int): Int {
        val data = singleFileData ?: return C.RESULT_END_OF_INPUT
        val toRead = minOf(length.toLong(), bytesRemaining).toInt()
        if (currentPosition >= data.size) return C.RESULT_END_OF_INPUT

        val available = (data.size - currentPosition.toInt()).coerceAtMost(toRead)
        System.arraycopy(data, currentPosition.toInt(), buffer, offset, available)

        currentPosition += available
        bytesRemaining -= available
        return available
    }

    private fun readFromChunks(buffer: ByteArray, offset: Int, length: Int): Int {
        val toRead = minOf(length.toLong(), bytesRemaining).toInt()
        var bytesRead = 0

        while (bytesRead < toRead) {
            val chunkIndex = (currentPosition / chunkSize).toInt()
            val offsetInChunk = (currentPosition % chunkSize).toInt()

            if (chunkIndex >= chunkCount) {
                return if (bytesRead > 0) bytesRead else C.RESULT_END_OF_INPUT
            }

            val chunkData = getDecryptedChunk(chunkIndex)
                ?: return if (bytesRead > 0) bytesRead else C.RESULT_END_OF_INPUT

            val availableInChunk = chunkData.size - offsetInChunk
            if (availableInChunk <= 0) break

            val copyLen = minOf(toRead - bytesRead, availableInChunk)

            System.arraycopy(chunkData, offsetInChunk, buffer, offset + bytesRead, copyLen)

            bytesRead += copyLen
            currentPosition += copyLen
            bytesRemaining -= copyLen
        }

        return if (bytesRead > 0) bytesRead else C.RESULT_END_OF_INPUT
    }

    override fun getUri(): Uri? = Uri.parse("vault://video")

    override fun close() {
        opened = false
        singleFileData = null
        bytesRemaining = 0L
        envelopeDek = null
    }

    override fun addTransferListener(transferListener: TransferListener) {}

    private fun getDecryptedChunk(index: Int): ByteArray? {
        ChunkCache.get(index)?.let { return it }

        val chunkFile = File(vaultFolder, "chunk_$index.enc")
        if (!chunkFile.exists()) {
            Log.w(TAG, "Chunk file missing: chunk_$index.enc")
            return null
        }

        return try {
            val encrypted = chunkFile.readBytes()

            if (encrypted.size <= IV_SIZE) {
                Log.w(TAG, "Chunk $index too small: ${encrypted.size} bytes")
                return null
            }

            val iv = encrypted.copyOfRange(0, IV_SIZE)

            val decrypted = if (envelopeDek != null) {
                val cipher = crypto.createSoftDecryptCipher(envelopeDek!!, iv)
                cipher.doFinal(encrypted, IV_SIZE, encrypted.size - IV_SIZE)
            } else {
                val cipher = crypto.createDecryptCipher(iv)
                cipher.doFinal(encrypted, IV_SIZE, encrypted.size - IV_SIZE)
            }

            ChunkCache.put(index, decrypted)
            decrypted
        } catch (e: Exception) {
            Log.e(TAG, "Failed to decrypt chunk $index: ${e.message}", e)
            null
        }
    }

    private fun getChunkFiles(): List<File> {
        return vaultFolder
            .listFiles { f -> f.name.startsWith("chunk_") && f.name.endsWith(".enc") }
            ?.sortedBy { it.name.removePrefix("chunk_").removeSuffix(".enc").toIntOrNull() ?: 0 }
            ?: emptyList()
    }
}