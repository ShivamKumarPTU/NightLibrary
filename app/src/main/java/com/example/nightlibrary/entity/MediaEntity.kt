package com.example.nightlibrary.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "media",
    indices = [
        Index(value = ["fileType"]),
        Index(value = ["createdAt"]),
        Index(value = ["isInTrash"]),
        Index(value = ["vaultFolder"], unique = true),
        Index(value = ["isCompleted", "isInTrash"]),
        Index(value = ["isCompleted", "downloadUrl"])
    ]
)
data class MediaEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val fileName: String,
    val vaultFolder: String,
    val chunkCount: Int,
    val chunkSize: Int,
    val fileSize: Long,
    val mimeType: String,
    val fileType: String,
    val isCompleted: Boolean,
    val filePath: String? = null,
    val iv: ByteArray? = null,
    val progress: Int,
    val checksum: String,
    val downloadUrl: String? = null,
    val streamUrl: String? = null,       // ← ADD THIS LINE
    val resumeBytes: Long = 0L,
    val isInTrash: Boolean = false,
    val isPaused: Boolean = false,
    val thumbnailPath: String? = null,
    val isFailed: Boolean = false,
    val failReason: String? = null,
    val downloadedBytes: Long = 0L,
    val useYtDlp: Boolean = false,
    val isHls: Boolean = false,
    val createdAt: Long = System.currentTimeMillis(),
    val currentSpeed: Double = 0.0,
    // ✅ NEW — Problem 6
    val duration: Long = 0L,
    // 🔒 Private Import Support
    val isPrivate: Boolean = false
)