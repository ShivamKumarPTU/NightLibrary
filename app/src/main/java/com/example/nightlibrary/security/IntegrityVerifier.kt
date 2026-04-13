package com.example.nightlibrary.security

import java.io.File
import java.io.FileInputStream
import java.security.MessageDigest

object IntegrityVerifier {
    fun verify(file: File, expectedChecksum: String): Boolean {

        // For chunk storage, integrity check is skipped
        if (file.isDirectory) {
            return true
        }

        if (!file.exists() || expectedChecksum.isEmpty()) return false

        val digest = MessageDigest.getInstance("SHA-256")
        val bytes = file.readBytes()

        val calculatedChecksum =
            digest.digest(bytes).joinToString("") { "%02x".format(it) }

        return calculatedChecksum == expectedChecksum
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