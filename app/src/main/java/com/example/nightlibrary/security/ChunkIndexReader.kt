package com.example.nightlibrary.security

import org.json.JSONObject
import java.io.File

class ChunkIndexReader {

    fun read(folder: File): ChunkIndex? {
        return try {
            val index = readIndex(folder)
            if (index.chunkCount <= 0 || index.totalFileSize <= 0) return null
            index
        } catch (e: Exception) {
            null
        }
    }

    fun readIndex(folder: File): ChunkIndex {
        val indexFile = File(folder, "index.json")
        if (!indexFile.exists()) throw NoSuchFileException(indexFile)
        
        val json = JSONObject(indexFile.readText())

        return ChunkIndex(
            chunkCount    = json.getInt("chunkCount"),
            chunkSize     = json.getInt("chunkSize"),
            totalFileSize = json.getLong("totalFileSize"),
            wrappedKey    = if (json.has("wrappedKey")) json.getString("wrappedKey") else null,
            keyIv         = if (json.has("keyIv")) json.getString("keyIv") else null
        )
    }
}
