package com.example.nightlibrary.security

import java.io.*
import java.security.MessageDigest
import javax.crypto.CipherOutputStream

class StreamingEncryptor(
    private val cryptoEngine: VaultCryptoEngine
) {

    data class EncryptResult(
        val filePath: String,
        val checksum: String,
        val iv: ByteArray,
        val fileSize: Long
    )

    fun encryptToFile(
        inputStream: InputStream,
        outputFile: File
    ): EncryptResult {

      //  val cipher = cryptoEngine.createEncryptCipher()
        val cipher = cryptoEngine.createEncryptCipher()
        val digest = MessageDigest.getInstance("SHA-256")

        val iv = cipher.iv

        val fileOutputStream = FileOutputStream(outputFile)

        // WRITE IV HEADER (CRITICAL)
        fileOutputStream.write(iv)

        val cipherOutputStream = CipherOutputStream(
            fileOutputStream,
            cipher
        )

        val buffer = ByteArray(8192)
        var bytesRead: Int
        var totalBytes = 0L

        while (inputStream.read(buffer).also { bytesRead = it } != -1) {

            cipherOutputStream.write(buffer, 0, bytesRead)

            digest.update(buffer, 0, bytesRead)

            totalBytes += bytesRead
        }

        cipherOutputStream.flush()
        cipherOutputStream.close()
        inputStream.close()

        val checksum = digest.digest()
            .joinToString("") { "%02x".format(it) }

        return EncryptResult(
            filePath = outputFile.absolutePath,
            checksum = checksum,
            iv = iv,
            fileSize = outputFile.length()
        )
    }
}