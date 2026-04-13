package com.example.nightlibrary.security

import android.graphics.BitmapFactory
import android.util.Log
import java.io.File
import java.io.FileInputStream

object EncryptedThumbnailLoader {

    private const val TAG = "VaultThumbLoader"

    fun loadThumbnail(
        encryptedFile: File,
        cryptoEngine: VaultCryptoEngine
    ): android.graphics.Bitmap? {

        return try {
            // ✅ STEP 1: Try to load as plaintext JPEG first
            // (LocalImportWorker saves plaintext thumbnails, not encrypted)
            val plainBitmap = BitmapFactory.decodeFile(encryptedFile.absolutePath)
            if (plainBitmap != null) {
                Log.d(TAG, "Loaded plaintext thumbnail successfully: ${encryptedFile.name}")
                return plainBitmap
            }

            Log.d(TAG, "Plaintext load failed, attempting decryption fallback")

            // ✅ STEP 2: Fallback: Try to load as encrypted (for legacy or future encrypted thumbnails)
            val input = FileInputStream(encryptedFile)

            val iv = ByteArray(16)
            input.read(iv)

            val cipher = cryptoEngine.createDecryptCipher(iv)

            val decrypted = cipher.update(input.readBytes())

            Log.d(TAG, "Successfully decrypted thumbnail, decoding ${decrypted.size} bytes")
            BitmapFactory.decodeByteArray(decrypted, 0, decrypted.size)

        } catch (e: Exception) {
            Log.e(TAG, "Failed to load thumbnail: ${e.message}", e)
            null
        }
    }
}