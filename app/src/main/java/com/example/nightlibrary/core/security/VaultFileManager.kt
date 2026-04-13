package com.example.nightlibrary.core.security

import android.content.Context
import android.net.Uri
import com.example.nightlibrary.security.VaultCryptoEngine
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import javax.crypto.CipherInputStream
import javax.crypto.CipherOutputStream

class VaultFileManager(private val context: Context) {

    companion object {
        private const val VAULT_DIR = "vault_media"
    }

    private val cryptoEngine = VaultCryptoEngine()

    private fun getVaultDirectory(): File {
        val dir = File(context.filesDir, VAULT_DIR)
        if (!dir.exists()) dir.mkdirs()
        return dir
    }

    /**
     * Encrypts a file directly into a specified vault directory.
     * Used for images to avoid chunk splitting and enable full-file viewing/swiping.
     */
    fun encryptToDirectory(sourceUri: Uri, targetDir: File, fileName: String): File {
        val encryptedFile = File(targetDir, fileName)
        val cipher = cryptoEngine.createEncryptCipher()
        val iv = cipher.iv
        context.contentResolver.openInputStream(sourceUri)?.use { input ->
            FileOutputStream(encryptedFile).use { fos ->
                fos.write(iv) // Write IV at start
                CipherOutputStream(fos, cipher).use { cos ->
                    input.copyTo(cos, 8192)
                }
            }
        } ?: throw RuntimeException("Failed to open input stream")
        return encryptedFile
    }

    fun encryptFile(sourceUri: Uri, fileName: String): Pair<File, ByteArray> {
        val vaultDir = getVaultDirectory()
        val encryptedFile = File(vaultDir, fileName)
        val cipher = cryptoEngine.createEncryptCipher()
        val iv = cipher.iv
        context.contentResolver.openInputStream(sourceUri)?.use { input ->
            FileOutputStream(encryptedFile).use { fos ->
                fos.write(iv)
                CipherOutputStream(fos, cipher).use { cos ->
                    input.copyTo(cos, 8192)
                }
            }
        } ?: throw RuntimeException("Failed to open input stream")
        return Pair(encryptedFile, iv)
    }

    /**
     * Decrypts a single encrypted file and returns a readable stream.
     */
    fun getEncryptedInputStream(file: File): InputStream {
        val fis = FileInputStream(file)
        val iv = ByteArray(16)
        val read = fis.read(iv)
        if (read != 16) throw Exception("Invalid encrypted file header")
        val cipher = cryptoEngine.createDecryptCipher(iv)
        return CipherInputStream(fis, cipher)
    }

    /**
     * Decrypts media from a vault folder into a single temp file.
     * Handles both the new single-file format (full_image.enc) and the old chunked format.
     *
     * @param onProgress Optional callback invoked with 0..100 as decryption progresses.
     *                   For single-file/legacy modes it reports 0 then 100.
     *                   For chunked mode it reports progress after each chunk.
     */
    fun decryptToTempFile(
        vaultFolder: File,
        mimeType: String,
        mediaId: Long,
        onProgress: ((Int) -> Unit)? = null
    ): File {
        val ext = mimeTypeToExtension(mimeType)
        val shareDir = File(context.filesDir, "vault_share").also { it.mkdirs() }
        val tempFile = File(shareDir, "vault_tmp_${mediaId}_${System.currentTimeMillis()}$ext")
        if (tempFile.exists()) tempFile.delete()

        // 1. Check for single-file format (New Image System)
        val singleFile = File(vaultFolder, "full_image.enc")
        if (singleFile.exists()) {
            onProgress?.invoke(0)
            getEncryptedInputStream(singleFile).use { input ->
                FileOutputStream(tempFile).use { output ->
                    input.copyTo(output, 65536)
                }
            }
            onProgress?.invoke(100)
            return tempFile
        }

        // 2. Check for single-chunk image (Legacy Image System)
        val legacyImage = File(vaultFolder, "chunk_0.enc")
        if (legacyImage.exists() && mimeType.startsWith("image/")) {
            onProgress?.invoke(0)
            getEncryptedInputStream(legacyImage).use { input ->
                FileOutputStream(tempFile).use { output ->
                    input.copyTo(output, 65536)
                }
            }
            onProgress?.invoke(100)
            return tempFile
        }

        // 3. Handle chunked format (Video/Audio/PDF) — report per-chunk progress
        val chunks = vaultFolder
            .listFiles { f -> f.name.startsWith("chunk_") && f.name.endsWith(".enc") }
            ?.sortedBy { f ->
                f.name.removePrefix("chunk_")
                    .removeSuffix(".enc")
                    .toIntOrNull() ?: 0
            } ?: throw IOException("No chunks found in ${vaultFolder.absolutePath}")

        val totalChunks = chunks.size
        FileOutputStream(tempFile).use { out ->
            for ((index, chunk) in chunks.withIndex()) {
                getEncryptedInputStream(chunk).use { it.copyTo(out) }
                // Emit progress after each chunk: smoothly 1..100
                onProgress?.invoke(((index + 1) * 100) / totalChunks)
            }
        }

        return tempFile
    }

    fun deleteFile(filePath: String?) {
        val file = File(filePath ?: return)
        if (file.exists()) file.delete()
    }

    fun getDecryptBytes(file: File): ByteArray {
        return getEncryptedInputStream(file).use { it.readBytes() }
    }

    private fun mimeTypeToExtension(mimeType: String): String = when {
        mimeType == "image/jpeg" || mimeType == "image/jpg" -> ".jpg"
        mimeType == "image/png"  -> ".png"
        mimeType == "image/gif"  -> ".gif"
        mimeType == "image/webp" -> ".webp"
        mimeType == "video/mp4"  -> ".mp4"
        mimeType.startsWith("video/") -> ".mp4"
        mimeType == "audio/mpeg" || mimeType == "audio/mp3" -> ".mp3"
        mimeType == "audio/mp4" || mimeType == "audio/m4a" -> ".m4a"
        mimeType == "audio/ogg"  -> ".ogg"
        mimeType.startsWith("audio/") -> ".mp3"
        mimeType == "application/pdf" -> ".pdf"
        else -> ".bin"
    }
}