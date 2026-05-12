package com.example.nightlibrary.security

import android.net.Uri
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.BaseDataSource
import androidx.media3.datasource.DataSpec
import com.example.nightlibrary.security.ChunkIndexReader
import com.example.nightlibrary.security.VaultCryptoEngine
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import kotlin.math.min

@UnstableApi
class VaultChunkDataSource(
    private val vaultFolder: File,
    private val crypto: VaultCryptoEngine
) : BaseDataSource(false) {

    private var uri: Uri? = null
    private lateinit var index: ChunkIndex

    private var filePointer = 0L
    private var bytesRemaining = 0L

    private lateinit var cipher: Cipher

    override fun open(dataSpec: DataSpec): Long {

        uri = dataSpec.uri
        transferInitializing(dataSpec)

        val reader = ChunkIndexReader()
        index = reader.read(vaultFolder) ?: throw IOException("Invalid or missing index")

        filePointer = dataSpec.position
        bytesRemaining = index.totalFileSize - filePointer

        // ⭐ Read IV from first chunk
        val firstChunk = File(vaultFolder, "chunk_0.enc")
        val raf = RandomAccessFile(firstChunk, "r")

        val iv = ByteArray(16)
        raf.read(iv)
        raf.close()

        cipher = crypto.decryptCipher(iv)

        // ⭐ Skip cipher stream to correct counter position
        skipCipher(filePointer)

        transferStarted(dataSpec)

        return bytesRemaining
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {

        if (bytesRemaining == 0L) return C.RESULT_END_OF_INPUT

        val toRead = min(length.toLong(), bytesRemaining).toInt()

        var totalRead = 0

        while (totalRead < toRead) {

            val chunkIndex = (filePointer / index.chunkSize).toInt()
            val chunkOffset = filePointer % index.chunkSize

            val chunkFile = File(vaultFolder, "chunk_$chunkIndex.enc")

            val raf = RandomAccessFile(chunkFile, "r")

            if (chunkIndex == 0) raf.seek(16 + chunkOffset)
            else raf.seek(chunkOffset)

            val temp = ByteArray(toRead - totalRead)
            val read = raf.read(temp)

            raf.close()

            if (read == -1) break

            val decrypted = cipher.update(temp, 0, read)

            System.arraycopy(decrypted, 0, buffer, offset + totalRead, decrypted.size)

            totalRead += decrypted.size
            filePointer += decrypted.size
            bytesRemaining -= decrypted.size
        }

        bytesTransferred(totalRead)

        return totalRead
    }

    private fun skipCipher(bytes: Long) {
        val dummy = ByteArray(4096)
        var remaining = bytes

        while (remaining > 0) {
            val r = min(dummy.size.toLong(), remaining).toInt()
            cipher.update(dummy, 0, r)
            remaining -= r
        }
    }

    override fun close() {
        uri = null
        transferEnded()
    }

    override fun getUri(): Uri? = uri
}