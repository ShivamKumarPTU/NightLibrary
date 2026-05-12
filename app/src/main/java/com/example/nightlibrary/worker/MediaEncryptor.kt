package com.example.nightlibrary.worker

import android.content.Context
import android.net.Uri
import com.example.nightlibrary.security.ChunkEncryptor
import com.example.nightlibrary.security.VaultCryptoEngine
import java.io.File
import java.util.*
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class MediaEncryptor @Inject constructor(
    private val context: Context
) {
    fun encrypt(tmpFile: File, fileType: String): EncryptionResult {
        val vaultDir = File(context.filesDir, "vault_media/$fileType/${UUID.randomUUID()}")
            .also { it.mkdirs() }

        val encryptor = ChunkEncryptor(context, VaultCryptoEngine())
        val index = encryptor.encryptStream(Uri.fromFile(tmpFile), vaultDir, 2 * 1024 * 1024)
        
        val checksum = com.example.nightlibrary.security.IntegrityVerifier.generateChecksum(tmpFile)

        return EncryptionResult(
            vaultFolder = vaultDir.absolutePath,
            chunkCount = index.chunkCount,
            totalFileSize = index.totalFileSize,
            checksum = checksum
        )
    }

    data class EncryptionResult(
        val vaultFolder: String,
        val chunkCount: Int,
        val totalFileSize: Long,
        val checksum: String
    )
}
