package com.example.nightlibrary.security

import java.io.File
import java.io.FileInputStream
import java.security.MessageDigest

object IntegrityVerifier {
    fun verify(file: File, expectedChecksum: String, isChunked: Boolean = false): Boolean {
        if (isChunked) return true // Skip raw chunk checks; verify full file after decryption
        
        if (file.isDirectory) return true
        if (!file.exists() || expectedChecksum.isEmpty()) return false

        val calculatedChecksum = generateChecksum(file)
        return calculatedChecksum.equals(expectedChecksum, ignoreCase = true)
    }

    fun verify(bytes: ByteArray, expectedChecksum: String): Boolean {
        if (expectedChecksum.isEmpty()) return true
        val digest = MessageDigest.getInstance("SHA-256")
        val calculatedChecksum =
            digest.digest(bytes).joinToString("") { "%02x".format(it) }
        return calculatedChecksum.equals(expectedChecksum, ignoreCase = true)
    }
    fun generateChecksum(file: File): String {
        if (!file.exists()) return ""

        return try {
            val digest = MessageDigest.getInstance("SHA-256")
            FileInputStream(file).use { inputStream ->
                val buffer = ByteArray(8192)
                var bytesRead: Int
                while (inputStream.read(buffer).also { bytesRead = it } != -1) {
                    digest.update(buffer, 0, bytesRead)
                }
            }
            // Convert bytes to Hex String
            digest.digest().joinToString("") { "%02x".format(it) }
        } catch (e: Exception) {
            ""
        }
    }
}