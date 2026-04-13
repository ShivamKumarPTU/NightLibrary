package com.example.nightlibrary.security

data class ChunkIndex(
    val chunkCount: Int,
    val chunkSize: Int,
    val totalFileSize: Long,
    val wrappedKey: String? = null,
    val keyIv: String? = null
)